package com.shreeyash.gateway;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Receives phone state changes for BOTH SIM slots
 * Detects which SIM received the call and notifies GatewayService
 *
 * NOTE: On Android 10+, READ_CALL_LOG permission is required to get incoming number.
 * Without it, EXTRA_INCOMING_NUMBER will be null.
 */
public class CallReceiver extends BroadcastReceiver {
    private static final String TAG = "CallReceiver";

    // Track last state per subscription (SIM)
    private static int lastStateSim1 = TelephonyManager.CALL_STATE_IDLE;
    private static int lastStateSim2 = TelephonyManager.CALL_STATE_IDLE;

    // Store incoming number when RINGING (it may not be available at OFFHOOK)
    private static String pendingNumberSim1 = null;
    private static String pendingNumberSim2 = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

        // Log what we received for debugging
        Log.d(TAG, "Phone state broadcast: state=" + state + ", number=" +
              (incomingNumber != null ? incomingNumber : "null (need READ_CALL_LOG permission?)"));

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
            // Incoming call - store the number (it may not be available later)
            if (simSlot == 1) {
                pendingNumberSim1 = incomingNumber;
            } else {
                pendingNumberSim2 = incomingNumber;
            }
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
            // Call ended - clear stored number
            if (simSlot == 1) {
                pendingNumberSim1 = null;
            } else {
                pendingNumberSim2 = null;
            }
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
        String finalNumber = number;

        // If number is null, check permissions and try call log fallback
        if (finalNumber == null || finalNumber.isEmpty()) {
            Log.w(TAG, "Incoming number is null/empty, checking permissions...");

            // Check READ_CALL_LOG permission
            boolean hasCallLogPermission = ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;

            Log.i(TAG, "READ_CALL_LOG permission: " + (hasCallLogPermission ? "GRANTED" : "DENIED"));

            if (hasCallLogPermission) {
                // Try to get the number from call log (slightly delayed)
                Log.i(TAG, "Attempting to get caller ID from call log...");
                // Schedule a delayed lookup since call log entry might not be written yet
                final int slot = simSlot;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    String callLogNumber = getLastIncomingNumber(context);
                    if (callLogNumber != null && !callLogNumber.isEmpty()) {
                        Log.i(TAG, "Got caller ID from call log: " + callLogNumber);
                        // Update the pending number
                        if (slot == 1) {
                            pendingNumberSim1 = callLogNumber;
                        } else {
                            pendingNumberSim2 = callLogNumber;
                        }
                        // Send update to service
                        Intent updateIntent = new Intent(context, GatewayService.class);
                        updateIntent.setAction("UPDATE_CALLER_ID");
                        updateIntent.putExtra("sim_slot", slot);
                        updateIntent.putExtra("number", callLogNumber);
                        context.startService(updateIntent);
                    }
                }, 500); // 500ms delay to allow call log to be written
            } else {
                Log.e(TAG, "Cannot get caller ID: READ_CALL_LOG permission not granted!");
                Log.e(TAG, "On Android 10+, this permission is required for incoming number");
            }

            finalNumber = "Unknown";
        }

        Log.i(TAG, String.format("Incoming call to SIM%d from: %s", simSlot, finalNumber));

        Intent serviceIntent = new Intent(context, GatewayService.class);
        serviceIntent.setAction("INCOMING_GSM_CALL");
        serviceIntent.putExtra("sim_slot", simSlot);
        serviceIntent.putExtra("number", finalNumber);
        context.startService(serviceIntent);
    }

    /**
     * Get the last incoming call number from call log
     * Useful as fallback when EXTRA_INCOMING_NUMBER is null
     */
    private String getLastIncomingNumber(Context context) {
        try {
            Uri uri = CallLog.Calls.CONTENT_URI;
            String[] projection = {CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE};
            String selection = CallLog.Calls.TYPE + " = ?";
            String[] selectionArgs = {String.valueOf(CallLog.Calls.INCOMING_TYPE)};
            String sortOrder = CallLog.Calls.DATE + " DESC LIMIT 1";

            Cursor cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
            if (cursor != null && cursor.moveToFirst()) {
                String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                cursor.close();

                // Only use if it's from the last 30 seconds
                if (System.currentTimeMillis() - date < 30000) {
                    return number;
                }
            }
            if (cursor != null) {
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read call log: " + e.getMessage(), e);
        }
        return null;
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