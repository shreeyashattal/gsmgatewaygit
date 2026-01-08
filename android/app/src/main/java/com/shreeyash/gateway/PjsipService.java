package com.shreeyash.gateway;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.os.Build;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;

public class PjsipService extends Service {
    private static final String TAG = "PjsipService";
    private static final String CHANNEL_ID = "pjsip_service_channel";
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();

        // Start native SIP core on background thread
        new Thread(() -> {
            try {
                NativeSip.startSip(5080);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to start Native SIP: " + t.getMessage(), t);
            }
        }).start();

        // Query SIMs and set native active flags
        SubscriptionManager sm = getSystemService(SubscriptionManager.class);
        List<SubscriptionInfo> subs = sm != null ? sm.getActiveSubscriptionInfoList() : null;

        boolean sim1 = false, sim2 = false;
        if (subs != null) {
            for (SubscriptionInfo s : subs) {
                if (s.getSimSlotIndex() == 0) sim1 = true;
                if (s.getSimSlotIndex() == 1) sim2 = true;
            }
        }
        NativeSip.setSimActive(0, sim1);
        NativeSip.setSimActive(1, sim2);

        startForeground(1, createNotification());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SIP Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("PJSIP native SIP service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder = new Notification.Builder(this, CHANNEL_ID);
        else builder = new Notification.Builder(this);
        return builder.setContentTitle("SIP Gateway Active").setContentText("Listening on port 5080")
                .setSmallIcon(android.R.drawable.ic_dialog_info).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
