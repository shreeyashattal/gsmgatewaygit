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
                Log.d(TAG, "Initializing PJSIP...");
                
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
                uaConfig.setUserAgent("GSM-SIP-Gateway/2.4.0");
                uaConfig.setMaxCalls(4); 
                
                // Configure media
                MediaConfig mediaConfig = epConfig.getMediaConfig();
                mediaConfig.setClockRate(16000); // 16kHz for Wideband support (G722)
                mediaConfig.setSndClockRate(16000);
                mediaConfig.setQuality(8); 
                mediaConfig.setEcTailLen(200); 
                mediaConfig.setNoVad(false);
                mediaConfig.setChannelCount(1);
                
                endpoint.libInit(epConfig);
                
                // Create UDP transport for SIP
                TransportConfig tcfg = new TransportConfig();
                tcfg.setPort(sipPort);
                
                try {
                    endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg);
                    Log.d(TAG, "UDP transport created on port " + sipPort);
                } catch (Exception e) {
                    Log.w(TAG, "Port 5060 unavailable, trying random port");
                    tcfg.setPort(0);
                    endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, tcfg);
                    
                    TransportInfoVector transports = endpoint.transportEnum();
                    if (transports.size() > 0) {
                        TransportInfo ti = transports.get(0);
                        sipPort = ti.getLocalAddress().getPort();
                        Log.d(TAG, "UDP transport created on port " + sipPort);
                    }
                }
                
                // Start the library
                endpoint.libStart();
                
                // Set default codec priorities (Enable all common ones, prioritize G711)
                updateCodecPriority("PCMU"); 
                
                isInitialized = true;
                Log.d(TAG, "PJSIP initialized successfully on port " + sipPort);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize PJSIP: " + e.getMessage(), e);
                isInitialized = false;
            }
        }).start();
    }
    
    public void updateCodecPriority(String preferredCodec) {
        if (endpoint == null) return;
        
        try {
            Log.d(TAG, "Updating codec priorities. Preferred: " + preferredCodec);
            CodecInfoVector2 codecs = endpoint.codecEnum2();
            
            for (int i = 0; i < codecs.size(); i++) {
                CodecInfo codec = codecs.get(i);
                String codecId = codec.getCodecId(); // e.g., "PCMU/8000/1"
                short priority = 0;
                
                // Map config names to PJSIP codec IDs
                boolean isMatch = false;
                if (preferredCodec.equals("PCMU") && codecId.contains("PCMU")) isMatch = true;
                else if (preferredCodec.equals("PCMA") && codecId.contains("PCMA")) isMatch = true;
                else if (preferredCodec.equals("G722") && codecId.contains("G722")) isMatch = true;
                else if (preferredCodec.equals("OPUS") && codecId.contains("opus")) isMatch = true;
                
                if (isMatch) {
                    priority = 255; // Highest
                } else if (codecId.contains("PCMU") || codecId.contains("PCMA")) {
                    priority = 128; // Fallback
                } else if (codecId.contains("G722") || codecId.contains("opus")) {
                    priority = 120; // High quality fallback
                } else {
                    priority = 0; // Disable others
                }
                
                endpoint.codecSetPriority(codecId, priority);
                if (priority > 0) Log.d(TAG, "Codec " + codecId + " priority set to " + priority);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update codec priority: " + e.getMessage());
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
        if (!isInitialized) {
            initializePjsip();
        }
        return START_STICKY; 
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        new Thread(() -> {
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
        }).start();
        
        instance = null;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
