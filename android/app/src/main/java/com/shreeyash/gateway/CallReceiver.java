package com.shreeyash.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Receives phone state changes for BOTH SIM slots
 * Detects which SIM received the call and notifies GatewayService
 */
public class CallReceiver extends BroadcastReceiver {
    private static final String TAG = "CallReceiver";
    
    // Track last state per subscription (SIM)
    private static int lastStateSim1 = TelephonyManager.CALL_STATE_IDLE;
    private static int lastStateSim2 = TelephonyManager.CALL_STATE_IDLE;
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
            return;
        }
        
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        
        if (state == null) return;
        
        int callState = getCallState(state);
        
        // Determine which SIM this belongs to
        int simSlot = detectSimSlot(context, intent);
        
        if (simSlot == 0) {
            Log.w(TAG, "Could not determine SIM slot for call");
            return;
        }
        
        int lastState = (simSlot == 1) ? lastStateSim1 : lastStateSim2;
        
        Log.i(TAG, String.format("SIM%d state change: %s → %s", simSlot, 
            stateToString(lastState), stateToString(callState)));
        
        // Detect state transitions
        if (lastState == TelephonyManager.CALL_STATE_IDLE && 
            callState == TelephonyManager.CALL_STATE_RINGING) {
            // Incoming call
            handleIncomingCall(context, simSlot, incomingNumber);
            
        } else if (lastState == TelephonyManager.CALL_STATE_RINGING && 
                   callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            // Incoming call answered
            handleCallAnswered(context, simSlot);
            
        } else if (lastState == TelephonyManager.CALL_STATE_IDLE && 
                   callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            // Outgoing call connected
            handleCallAnswered(context, simSlot);
            
        } else if (lastState != TelephonyManager.CALL_STATE_IDLE && 
                   callState == TelephonyManager.CALL_STATE_IDLE) {
            // Call ended
            handleCallEnded(context, simSlot);
        }
        
        // Update last state for this SIM
        if (simSlot == 1) {
            lastStateSim1 = callState;
        } else {
            lastStateSim2 = callState;
        }
    }
    
    /**
     * Detect which SIM slot this call belongs to
     * Returns 1 for SIM1, 2 for SIM2, or 0 if unknown
     */
    private int detectSimSlot(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            // Older Android - assume SIM1
            return 1;
        }
        
        try {
            // Try to get subscription ID from intent extras
            int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1);
            
            if (subId == -1) {
                // Alternative: Try getting from phone account handle
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Use SubscriptionManager to map
                    SubscriptionManager subManager = (SubscriptionManager) 
                        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    
                    if (subManager != null) {
                        int defaultVoiceSub = SubscriptionManager.getDefaultVoiceSubscriptionId();
                        if (defaultVoiceSub != -1) {
                            subId = defaultVoiceSub;
                        }
                    }
                }
            }
            
            if (subId != -1) {
                // Map subscription ID to SIM slot using DualSIMManager logic
                DualSIMManager simManager = new DualSIMManager(context);
                return simManager.getSimSlotForSubscription(subId);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error detecting SIM slot: " + e.getMessage(), e);
        }
        
        // Fallback: assume SIM1
        Log.w(TAG, "Could not detect SIM slot, defaulting to SIM1");
        return 1;
    }
    
    private int getCallState(String state) {
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            return TelephonyManager.CALL_STATE_RINGING;
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            return TelephonyManager.CALL_STATE_OFFHOOK;
        } else {
            return TelephonyManager.CALL_STATE_IDLE;
        }
    }
    
    private String stateToString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "OFFHOOK";
            default: return "UNKNOWN";
        }
    }
    
    private void handleIncomingCall(Context context, int simSlot, String number) {
        Log.i(TAG, String.format("Incoming call to SIM%d from: %s", simSlot, number));
        
        Intent serviceIntent = new Intent(context, GatewayService.class);
        serviceIntent.setAction("INCOMING_GSM_CALL");
        serviceIntent.putExtra("sim_slot", simSlot);
        serviceIntent.putExtra("number", number);
        context.startService(serviceIntent);
    }
    
    private void handleCallAnswered(Context context, int simSlot) {
        Log.i(TAG, "Call answered on SIM" + simSlot);
        
        Intent serviceIntent = new Intent(context, GatewayService.class);
        serviceIntent.setAction("GSM_CALL_ANSWERED");
        serviceIntent.putExtra("sim_slot", simSlot);
        context.startService(serviceIntent);
    }
    
    private void handleCallEnded(Context context, int simSlot) {
        Log.i(TAG, "Call ended on SIM" + simSlot);
        
        Intent serviceIntent = new Intent(context, GatewayService.class);
        serviceIntent.setAction("GSM_CALL_ENDED");
        serviceIntent.putExtra("sim_slot", simSlot);
        context.startService(serviceIntent);
    }
}