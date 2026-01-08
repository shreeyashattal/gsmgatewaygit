package com.shreeyash.gateway;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.util.Log;

import org.pjsip.pjsua2.*;

public class PjsipService extends Service {

    private static final String TAG = "PjsipService";
    private static final String CHANNEL_ID = "pjsip_service_channel";
    private static PjsipService instance;
    private Endpoint endpoint;
    private boolean isInitialized = false;
    private int sipPort = 5060;
    private Handler mainHandler;

    public static PjsipService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());

        Log.d(TAG, "PjsipService onCreate()");

        // Create notification channel for foreground service
        createNotificationChannel();

        // Initialize on main thread
        mainHandler.post(this::initializePjsip);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SIP Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("PJSIP service for SIP calls");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void initializePjsip() {
        if (isInitialized) {
            Log.d(TAG, "PJSIP already initialized");
            return;
        }

        try {
            Log.d(TAG, "Initializing PJSIP...");

            System.loadLibrary("pjsua2");
            Log.d(TAG, "PJSIP native library loaded");

            endpoint = new Endpoint();
            endpoint.libCreate();

            EpConfig epConfig = new EpConfig();

            LogConfig logConfig = epConfig.getLogConfig();
            logConfig.setLevel(4);
            logConfig.setConsoleLevel(4);

            UaConfig uaConfig = epConfig.getUaConfig();
            uaConfig.setUserAgent("GSM-SIP-Gateway/2.4.0");
            uaConfig.setMaxCalls(4);

            MediaConfig mediaConfig = epConfig.getMedConfig();
            mediaConfig.setClockRate(16000);
            mediaConfig.setSndClockRate(16000);
            mediaConfig.setQuality(8);
            mediaConfig.setEcTailLen(200);
            mediaConfig.setNoVad(false);
            mediaConfig.setChannelCount(1);

            endpoint.libInit(epConfig);

            TransportConfig tcfg = new TransportConfig();
            tcfg.setPort(sipPort);

            try {
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg);
                Log.d(TAG, "UDP transport created on port " + sipPort);
            } catch (Exception e) {
                Log.w(TAG, "Port 5060 unavailable, trying random port");
                tcfg.setPort(0);
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg);
                sipPort = 0;
                Log.d(TAG, "UDP transport created on random port");
            }

            endpoint.libStart();
            updateCodecPriority("PCMU");

            isInitialized = true;
            Log.d(TAG, "PJSIP initialized successfully on port " + sipPort);

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize PJSIP: " + e.getMessage(), e);
            isInitialized = false;
        }
    }

    public void updateCodecPriority(String preferredCodec) {
        if (endpoint == null) return;

        try {
            Log.d(TAG, "Updating codec priorities. Preferred: " + preferredCodec);

            if (preferredCodec.equals("PCMU")) {
                endpoint.codecSetPriority("PCMU/8000/1", (short) 255);
                endpoint.codecSetPriority("PCMA/8000/1", (short) 128);
            } else if (preferredCodec.equals("PCMA")) {
                endpoint.codecSetPriority("PCMA/8000/1", (short) 255);
                endpoint.codecSetPriority("PCMU/8000/1", (short) 128);
            } else if (preferredCodec.equals("G722")) {
                endpoint.codecSetPriority("G722/16000/1", (short) 255);
                endpoint.codecSetPriority("PCMU/8000/1", (short) 128);
            } else if (preferredCodec.equals("OPUS")) {
                endpoint.codecSetPriority("opus/48000/2", (short) 255);
                endpoint.codecSetPriority("PCMU/8000/1", (short) 128);
            }
            Log.d(TAG, "Codec priority set for: " + preferredCodec);
        } catch (Exception e) {
            Log.w(TAG, "Could not set codec priority: " + e.getMessage());
        }
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public int getSipPort() {
        return sipPort;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "PjsipService onStartCommand()");

        // Start as foreground service
        Notification notification = createNotification();
        startForeground(1, notification);

        if (!isInitialized) {
            mainHandler.post(this::initializePjsip);
        }

        return START_STICKY;
    }

    private Notification createNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("SIP Gateway Active")
                .setContentText("PJSIP service running on port " + sipPort)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "PjsipService onDestroy()");

        mainHandler.post(() -> {
            try {
                if (endpoint != null && isInitialized) {
                    endpoint.libDestroy();
                    endpoint.delete();
                    endpoint = null;
                    isInitialized = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down PJSIP: " + e.getMessage());
            }
        });

        instance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}