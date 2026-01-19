package com.shreeyash.gateway;

import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.os.Build;
import android.util.Log;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitors incoming GSM calls on both SIM slots
 * When GSM call detected, bridges to SIP
 */
public class GsmCallMonitor {
    private static final String TAG = "GsmCallMonitor";
    private static GsmCallMonitor instance;
    
    private Context context;
    private Map<Integer, PhoneStateListener> listeners = new HashMap<>();
    private Map<String, GsmCallBridge> activeGsmCalls = new HashMap<>();
    
    // Reference to SIP handlers (set by SipPlugin)
    private static Map<Integer, RawSipHandler> sipHandlers = new HashMap<>();
    
    private GsmCallMonitor() {}
    
    public static synchronized GsmCallMonitor getInstance() {
        if (instance == null) {
            instance = new GsmCallMonitor();
        }
        return instance;
    }
    
    /**
     * Set SIP handler for a slot (called by SipPlugin)
     */
    public static void setSipHandler(int slot, RawSipHandler sipHandler) {
        sipHandlers.put(slot, sipHandler);
        Log.i(TAG, "SIP handler registered for slot " + slot);
    }
    
    /**
     * Start monitoring GSM calls on all SIM slots
     */
    public void start(Context context) {
        this.context = context;
        
        Log.i(TAG, "Starting GSM call monitoring...");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            startMultiSimMonitoring();
        } else {
            startSingleSimMonitoring();
        }
        
        Log.i(TAG, "GSM call monitoring started");
    }
    
    /**
     * Monitor dual SIM (Android 5.1+)
     */
    private void startMultiSimMonitoring() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "READ_PHONE_STATE permission not granted");
            return;
        }
        
        try {
            SubscriptionManager subManager = (SubscriptionManager) 
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            
            if (subManager == null) {
                Log.e(TAG, "SubscriptionManager is null");
                return;
            }
            
            List<SubscriptionInfo> subInfoList = subManager.getActiveSubscriptionInfoList();
            
            if (subInfoList == null || subInfoList.isEmpty()) {
                Log.w(TAG, "No active subscriptions found");
                return;
            }
            
            for (SubscriptionInfo subInfo : subInfoList) {
                int slot = subInfo.getSimSlotIndex();
                int subId = subInfo.getSubscriptionId();
                
                Log.i(TAG, "Monitoring SIM slot " + slot + " (subId=" + subId + ")");
                
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    tm = tm.createForSubscriptionId(subId);
                }
                
                PhoneStateListener listener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        handleCallStateChange(slot, state, phoneNumber);
                    }
                };
                
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                listeners.put(slot, listener);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting multi-SIM monitoring", e);
        }
    }
    
    /**
     * Monitor single SIM (Android < 5.1)
     */
    private void startSingleSimMonitoring() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "READ_PHONE_STATE permission not granted");
            return;
        }
        
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        
        PhoneStateListener listener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                handleCallStateChange(0, state, phoneNumber);
            }
        };
        
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
        listeners.put(0, listener);
    }
    
    /**
     * Handle GSM call state changes
     */
    private void handleCallStateChange(int slot, int state, String phoneNumber) {
        Log.i(TAG, "========================================");
        Log.i(TAG, "GSM Call State Change");
        Log.i(TAG, "Slot: " + slot);
        Log.i(TAG, "State: " + stateToString(state));
        Log.i(TAG, "Number: " + (phoneNumber != null ? phoneNumber : "unknown"));
        Log.i(TAG, "========================================");
        
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                // Incoming GSM call
                handleIncomingGsmCall(slot, phoneNumber);
                break;
                
            case TelephonyManager.CALL_STATE_OFFHOOK:
                // Call answered/active
                handleGsmCallActive(slot, phoneNumber);
                break;
                
            case TelephonyManager.CALL_STATE_IDLE:
                // Call ended
                handleGsmCallEnded(slot, phoneNumber);
                break;
        }
    }
    
    /**
     * Handle incoming GSM call - bridge to SIP
     */
    private void handleIncomingGsmCall(int slot, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            Log.w(TAG, "No phone number for incoming call");
            return;
        }
        
        Log.e(TAG, "INCOMING GSM CALL on slot " + slot + " from " + phoneNumber);
        Log.e(TAG, "Need to bridge to SIP...");
        
        // Create bridge
        String callKey = slot + ":" + phoneNumber;
        GsmCallBridge bridge = new GsmCallBridge(slot, phoneNumber);
        activeGsmCalls.put(callKey, bridge);
        
        // Bridge to SIP
        bridge.bridgeGsmToSip();
    }
    
    /**
     * Handle GSM call becoming active
     */
    private void handleGsmCallActive(int slot, String phoneNumber) {
        String callKey = slot + ":" + (phoneNumber != null ? phoneNumber : "");
        GsmCallBridge bridge = activeGsmCalls.get(callKey);
        
        if (bridge != null) {
            bridge.onGsmCallActive();
        }
    }
    
    /**
     * Handle GSM call ending
     */
    private void handleGsmCallEnded(int slot, String phoneNumber) {
        String callKey = slot + ":" + (phoneNumber != null ? phoneNumber : "");
        GsmCallBridge bridge = activeGsmCalls.remove(callKey);
        
        if (bridge != null) {
            bridge.cleanup();
        }
    }
    
    private String stateToString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "OFFHOOK";
            default: return "UNKNOWN(" + state + ")";
        }
    }
    
    /**
     * Stop monitoring
     */
    public void stop() {
        Log.i(TAG, "Stopping GSM call monitoring");
        
        if (context != null) {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            
            for (PhoneStateListener listener : listeners.values()) {
                tm.listen(listener, PhoneStateListener.LISTEN_NONE);
            }
        }
        
        listeners.clear();
        activeGsmCalls.clear();
    }
    
    /**
     * Inner class to manage GSM to SIP bridging
     */
    private static class GsmCallBridge {
        int slot;
        String gsmNumber;
        boolean sipCallPlaced = false;
        boolean gsmCallAnswered = false;
        RawSipHandler sipHandler;
        
        GsmCallBridge(int slot, String gsmNumber) {
            this.slot = slot;
            this.gsmNumber = gsmNumber;
            this.sipHandler = sipHandlers.get(slot);
        }
        
        void bridgeGsmToSip() {
            Log.e(TAG, "========================================");
            Log.e(TAG, "BRIDGING GSM TO SIP");
            Log.e(TAG, "GSM Slot: " + slot);
            Log.e(TAG, "GSM Number: " + gsmNumber);
            Log.e(TAG, "========================================");
            
            if (sipHandler == null) {
                Log.e(TAG, "ERROR: No SIP handler for slot " + slot);
                return;
            }
            
            // Make outbound SIP call to PBX with the GSM caller's number
            Log.i(TAG, "Making SIP call to PBX with number: " + gsmNumber);
            
            sipHandler.makeCall(gsmNumber);
            sipCallPlaced = true;
            
            Log.i(TAG, "SIP call placed successfully");
        }
        
        void onGsmCallActive() {
            Log.i(TAG, "GSM call active for " + gsmNumber);
            gsmCallAnswered = true;
            
            // TODO: Start audio bridge between GSM and SIP
            Log.i(TAG, "Need to start audio bridge");
        }
        
        void cleanup() {
            Log.i(TAG, "Cleaning up GSM call bridge for " + gsmNumber);
            
            // Hangup SIP call if active
            if (sipCallPlaced && sipHandler != null) {
                sipHandler.hangupCall();
            }
            
            // TODO: Stop audio bridge
        }
    }
}