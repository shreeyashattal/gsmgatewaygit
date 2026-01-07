package com.shreeyash.gateway;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.pjsip.pjsua2.*;

public class PjsipService extends Service {
    
    private static final String TAG = "PjsipService";
    private static PjsipService instance;
    private Endpoint endpoint;
    private boolean isInitialized = false;
    private int sipPort = 5060;
    
    public static PjsipService getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initializePjsip();
    }
    
    private void initializePjsip() {
        if (isInitialized) {
            Log.d(TAG, "PJSIP already initialized");
            return;
        }
        
        new Thread(() -> {
            try {
                Log.d(TAG, "Initializing PJSIP for TRUNK mode...");
                
                // Load PJSIP native library
                System.loadLibrary("pjsua2");
                Log.d(TAG, "PJSIP native library loaded");
                
                // Create endpoint
                endpoint = new Endpoint();
                endpoint.libCreate();
                
                // Initialize endpoint
                EpConfig epConfig = new EpConfig();
                
                // Configure logging
                LogConfig logConfig = epConfig.getLogConfig();
                logConfig.setLevel(4); // Info level
                logConfig.setConsoleLevel(4);
                
                // Configure User Agent
                UaConfig uaConfig = epConfig.getUaConfig();
                uaConfig.setUserAgent("GSM-SIP-Trunk/1.0");
                uaConfig.setMaxCalls(4); // Support multiple simultaneous calls
                
                // Configure media for GSM bridge
                MediaConfig mediaConfig = epConfig.getMediaConfig();
                mediaConfig.setClockRate(8000); // Standard for telephony
                mediaConfig.setSndClockRate(8000);
                mediaConfig.setQuality(6); // Higher quality for trunk
                mediaConfig.setEcTailLen(200); // Echo cancellation
                mediaConfig.setNoVad(false); // Voice Activity Detection
                
                // Enable specific codecs for Grandstream compatibility
                mediaConfig.setChannelCount(1); // Mono audio
                
                endpoint.libInit(epConfig);
                
                // Configure codecs - prioritize G.711
                CodecInfoVector2 codecs = endpoint.codecEnum2();
                for (int i = 0; i < codecs.size(); i++) {
                    CodecInfo codec = codecs.get(i);
                    String codecId = codec.getCodecId();
                    
                    // Enable G.711 codecs (best for GSM bridge)
                    if (codecId.contains("PCMU") || codecId.contains("PCMA")) {
                        endpoint.codecSetPriority(codecId, (short) 255); // Highest priority
                        Log.d(TAG, "Enabled codec: " + codecId);
                    } else {
                        // Disable other codecs for simplicity
                        endpoint.codecSetPriority(codecId, (short) 0);
                    }
                }
                
                // Create UDP transport for SIP trunk
                TransportConfig tcfg = new TransportConfig();
                tcfg.setPort(sipPort);
                
                try {
                    endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg);
                    Log.d(TAG, "UDP transport created on port " + sipPort);
                } catch (Exception e) {
                    // Try random port if 5060 is taken
                    Log.w(TAG, "Port 5060 unavailable, trying random port");
                    tcfg.setPort(0);
                    endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg);
                    
                    // Get actual port assigned
                    TransportInfoVector transports = endpoint.transportEnum();
                    if (transports.size() > 0) {
                        TransportInfo ti = transports.get(0);
                        sipPort = ti.getLocalAddress().getPort();
                        Log.d(TAG, "UDP transport created on port " + sipPort);
                    }
                }
                
                // Start the library
                endpoint.libStart();
                
                isInitialized = true;
                Log.d(TAG, "PJSIP initialized successfully in TRUNK mode on port " + sipPort);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize PJSIP: " + e.getMessage(), e);
                isInitialized = false;
            }
        }).start();
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
        if (!isInitialized) {
            initializePjsip();
        }
        return START_STICKY; // Keep service running
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Cleanup PJSIP
        new Thread(() -> {
            try {
                if (endpoint != null && isInitialized) {
                    Log.d(TAG, "Shutting down PJSIP trunk...");
                    endpoint.libDestroy();
                    endpoint.delete();
                    endpoint = null;
                    isInitialized = false;
                    Log.d(TAG, "PJSIP shutdown complete");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down PJSIP: " + e.getMessage());
            }
        }).start();
        
        instance = null;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}