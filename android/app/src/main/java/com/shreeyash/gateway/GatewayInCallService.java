package com.shreeyash.gateway;

import android.content.Intent;
import android.os.Build;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * InCallService implementation for GSM-SIP Gateway
 *
 * This service is bound by the Android Telecom framework when calls are active.
 * It provides reliable call state monitoring and enables access to the VOICE_CALL
 * audio stream when the app is installed as a privileged system app.
 *
 * Requirements:
 * - App must be installed as system/privileged app (via Magisk module)
 * - CONTROL_INCALL_EXPERIENCE permission (granted by privapp-permissions.xml)
 * - CAPTURE_AUDIO_OUTPUT permission (granted by privapp-permissions.xml)
 *
 * This approach is used by BCR (Basic Call Recorder) and is the most reliable
 * way to access call audio on modern Android devices.
 */
public class GatewayInCallService extends InCallService {
    private static final String TAG = "GatewayInCallService";

    // Static reference for other components to access
    private static GatewayInCallService instance;

    // Track active calls
    private final ConcurrentHashMap<String, CallInfo> activeCalls = new ConcurrentHashMap<>();

    // Listener for call events
    private static CallEventListener callEventListener;

    /**
     * Call information container
     */
    public static class CallInfo {
        public final Call call;
        public final String callId;
        public final String phoneNumber;
        public final int direction; // Call.Details.DIRECTION_INCOMING or DIRECTION_OUTGOING
        public final int simSlot;
        public int state;
        public long startTime;
        public long answerTime;

        public CallInfo(Call call, String callId, String phoneNumber, int direction, int simSlot) {
            this.call = call;
            this.callId = callId;
            this.phoneNumber = phoneNumber;
            this.direction = direction;
            this.simSlot = simSlot;
            this.state = call.getState();
            this.startTime = System.currentTimeMillis();
        }

        public boolean isIncoming() {
            return direction == Call.Details.DIRECTION_INCOMING;
        }

        public boolean isActive() {
            return state == Call.STATE_ACTIVE;
        }
    }

    /**
     * Interface for call event notifications
     */
    public interface CallEventListener {
        void onCallAdded(CallInfo callInfo);
        void onCallStateChanged(CallInfo callInfo, int oldState, int newState);
        void onCallRemoved(CallInfo callInfo);
    }

    public static void setCallEventListener(CallEventListener listener) {
        callEventListener = listener;
    }

    public static GatewayInCallService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ GatewayInCallService CREATED                                │");
        Log.i(TAG, "│ InCallService binding successful!                           │");
        Log.i(TAG, "│ CONTROL_INCALL_EXPERIENCE permission is working.            │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.i(TAG, "GatewayInCallService destroyed");
    }

    @Override
    public void onCallAdded(Call call) {
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ CALL ADDED                                                  │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");

        Call.Details details = call.getDetails();
        if (details == null) {
            Log.w(TAG, "Call details is null");
            return;
        }

        String callId = getCallId(call);
        String phoneNumber = getPhoneNumber(details);
        int direction = getCallDirection(details);
        int simSlot = getSimSlot(details);

        Log.i(TAG, "  Call ID:     " + callId);
        Log.i(TAG, "  Number:      " + phoneNumber);
        Log.i(TAG, "  Direction:   " + (direction == Call.Details.DIRECTION_INCOMING ? "INCOMING" : "OUTGOING"));
        Log.i(TAG, "  SIM Slot:    " + simSlot);
        Log.i(TAG, "  State:       " + stateToString(call.getState()));

        CallInfo callInfo = new CallInfo(call, callId, phoneNumber, direction, simSlot);
        activeCalls.put(callId, callInfo);

        // Register callback for state changes
        call.registerCallback(new CallCallback(callInfo));

        // Notify listener
        if (callEventListener != null) {
            callEventListener.onCallAdded(callInfo);
        }

        // Also notify GatewayService directly
        notifyGatewayService("INCALL_CALL_ADDED", callInfo);
    }

    @Override
    public void onCallRemoved(Call call) {
        String callId = getCallId(call);
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ CALL REMOVED: " + String.format("%-44s", callId) + " │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");

        CallInfo callInfo = activeCalls.remove(callId);
        if (callInfo != null) {
            // Notify listener
            if (callEventListener != null) {
                callEventListener.onCallRemoved(callInfo);
            }

            // Notify GatewayService
            notifyGatewayService("INCALL_CALL_REMOVED", callInfo);
        }
    }

    /**
     * Callback for individual call state changes
     */
    private class CallCallback extends Call.Callback {
        private final CallInfo callInfo;

        CallCallback(CallInfo callInfo) {
            this.callInfo = callInfo;
        }

        @Override
        public void onStateChanged(Call call, int newState) {
            int oldState = callInfo.state;
            callInfo.state = newState;

            Log.i(TAG, "Call state changed: " + stateToString(oldState) + " -> " + stateToString(newState));

            if (newState == Call.STATE_ACTIVE && callInfo.answerTime == 0) {
                callInfo.answerTime = System.currentTimeMillis();
                Log.i(TAG, "Call answered, duration starts now");
            }

            // Notify listener
            if (callEventListener != null) {
                callEventListener.onCallStateChanged(callInfo, oldState, newState);
            }

            // Notify GatewayService for specific transitions
            if (newState == Call.STATE_ACTIVE) {
                notifyGatewayService("INCALL_CALL_ACTIVE", callInfo);
            } else if (newState == Call.STATE_DISCONNECTED) {
                notifyGatewayService("INCALL_CALL_DISCONNECTED", callInfo);
            }
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            // Update phone number if it becomes available
            if (callInfo.phoneNumber == null || callInfo.phoneNumber.equals("Unknown")) {
                String newNumber = getPhoneNumber(details);
                if (newNumber != null && !newNumber.equals("Unknown")) {
                    Log.i(TAG, "Phone number updated: " + newNumber);
                    // Note: CallInfo.phoneNumber is final, so we'd need to update differently
                    // For now, just notify
                    notifyGatewayService("INCALL_NUMBER_UPDATED", callInfo);
                }
            }
        }
    }

    /**
     * Get unique call identifier
     */
    private String getCallId(Call call) {
        // Use hash code as unique identifier
        // getTelecomCallId() is only available in API 30+
        Call.Details details = call.getDetails();
        if (details != null) {
            // Try to use handle as part of the ID for uniqueness
            if (details.getHandle() != null) {
                return "call_" + details.getHandle().hashCode() + "_" + call.hashCode();
            }
        }
        return "call_" + call.hashCode();
    }

    /**
     * Extract phone number from call details
     * Tries multiple sources to find the caller ID
     */
    private String getPhoneNumber(Call.Details details) {
        if (details == null) {
            Log.w(TAG, "getPhoneNumber: details is null");
            return "Unknown";
        }

        String number = null;

        // Method 1: Try gateway info (for calls through gateways)
        if (details.getGatewayInfo() != null && details.getGatewayInfo().getOriginalAddress() != null) {
            number = details.getGatewayInfo().getOriginalAddress().getSchemeSpecificPart();
            Log.d(TAG, "getPhoneNumber: gatewayInfo.originalAddress = " + number);
            if (number != null && !number.isEmpty()) {
                return number;
            }
        }

        // Method 2: Try handle (main caller ID source)
        if (details.getHandle() != null) {
            number = details.getHandle().getSchemeSpecificPart();
            Log.d(TAG, "getPhoneNumber: handle = " + number);
            if (number != null && !number.isEmpty()) {
                return number;
            }
            // Also log the full URI for debugging
            Log.d(TAG, "getPhoneNumber: handle URI = " + details.getHandle().toString());
        } else {
            Log.d(TAG, "getPhoneNumber: handle is null");
        }

        // Method 3: Try caller display name
        String displayName = details.getCallerDisplayName();
        if (displayName != null && !displayName.isEmpty()) {
            Log.d(TAG, "getPhoneNumber: callerDisplayName = " + displayName);
            // Only use if it looks like a phone number
            if (displayName.matches("[+]?[0-9\\s\\-()]+")) {
                return displayName;
            }
        }

        // Method 4: Try contact display name
        CharSequence contactName = details.getContactDisplayName();
        if (contactName != null && contactName.length() > 0) {
            Log.d(TAG, "getPhoneNumber: contactDisplayName = " + contactName);
        }

        Log.w(TAG, "getPhoneNumber: Could not extract number, returning Unknown");
        return "Unknown";
    }

    /**
     * Get call direction
     */
    private int getCallDirection(Call.Details details) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return details.getCallDirection();
        }
        // Fallback: Check if it was our outgoing call
        // This is less reliable but works for basic cases
        return Call.Details.DIRECTION_UNKNOWN;
    }

    /**
     * Get SIM slot from phone account
     */
    private int getSimSlot(Call.Details details) {
        if (details == null) return 1;

        PhoneAccountHandle accountHandle = details.getAccountHandle();
        if (accountHandle != null) {
            String id = accountHandle.getId();
            if (id != null) {
                // Phone account IDs often contain subscription ID or slot info
                // Try to extract slot number
                try {
                    // Common patterns: "89110001...", slot index at end, etc.
                    if (id.contains("1") && !id.contains("0")) return 2;
                    return 1; // Default to SIM1
                } catch (Exception e) {
                    Log.w(TAG, "Could not determine SIM slot from: " + id);
                }
            }
        }
        return 1; // Default
    }

    /**
     * Notify GatewayService of call events
     */
    private void notifyGatewayService(String action, CallInfo callInfo) {
        try {
            Intent intent = new Intent(this, GatewayService.class);
            intent.setAction(action);
            intent.putExtra("call_id", callInfo.callId);
            intent.putExtra("phone_number", callInfo.phoneNumber);
            intent.putExtra("sim_slot", callInfo.simSlot);
            intent.putExtra("direction", callInfo.direction);
            intent.putExtra("state", callInfo.state);
            startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to notify GatewayService: " + e.getMessage());
        }
    }

    /**
     * Convert call state to string
     */
    private String stateToString(int state) {
        switch (state) {
            case Call.STATE_NEW: return "NEW";
            case Call.STATE_DIALING: return "DIALING";
            case Call.STATE_RINGING: return "RINGING";
            case Call.STATE_HOLDING: return "HOLDING";
            case Call.STATE_ACTIVE: return "ACTIVE";
            case Call.STATE_DISCONNECTED: return "DISCONNECTED";
            case Call.STATE_CONNECTING: return "CONNECTING";
            case Call.STATE_DISCONNECTING: return "DISCONNECTING";
            case Call.STATE_SELECT_PHONE_ACCOUNT: return "SELECT_PHONE_ACCOUNT";
            case Call.STATE_SIMULATED_RINGING: return "SIMULATED_RINGING";
            case Call.STATE_AUDIO_PROCESSING: return "AUDIO_PROCESSING";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /**
     * Get active call count
     */
    public int getActiveCallCount() {
        return activeCalls.size();
    }

    /**
     * Check if any call is active (answered)
     */
    public boolean hasActiveCall() {
        for (CallInfo info : activeCalls.values()) {
            if (info.isActive()) return true;
        }
        return false;
    }

    /**
     * Get call info by call ID
     */
    public CallInfo getCallInfo(String callId) {
        return activeCalls.get(callId);
    }

    /**
     * Answer a ringing call
     * @param simSlot the SIM slot to answer (1 or 2)
     * @return true if a call was answered
     */
    public boolean answerCall(int simSlot) {
        Log.i(TAG, "answerCall requested for SIM" + simSlot);

        for (CallInfo info : activeCalls.values()) {
            if (info.simSlot == simSlot && info.state == Call.STATE_RINGING) {
                Log.i(TAG, "Answering ringing call: " + info.callId);
                try {
                    info.call.answer(VideoProfile.STATE_AUDIO_ONLY);
                    Log.i(TAG, "Call.answer() called successfully");
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to answer call: " + e.getMessage(), e);
                }
            }
        }

        // Try to answer any ringing call if no specific SIM match
        for (CallInfo info : activeCalls.values()) {
            if (info.state == Call.STATE_RINGING) {
                Log.i(TAG, "Answering ringing call (any SIM): " + info.callId);
                try {
                    info.call.answer(VideoProfile.STATE_AUDIO_ONLY);
                    Log.i(TAG, "Call.answer() called successfully");
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to answer call: " + e.getMessage(), e);
                }
            }
        }

        Log.w(TAG, "No ringing call found to answer");
        return false;
    }

    /**
     * Get the ringing call for a SIM slot
     */
    public CallInfo getRingingCall(int simSlot) {
        for (CallInfo info : activeCalls.values()) {
            if (info.simSlot == simSlot && info.state == Call.STATE_RINGING) {
                return info;
            }
        }
        // Fallback: any ringing call
        for (CallInfo info : activeCalls.values()) {
            if (info.state == Call.STATE_RINGING) {
                return info;
            }
        }
        return null;
    }

    /**
     * Disconnect a call
     */
    public boolean disconnectCall(int simSlot) {
        for (CallInfo info : activeCalls.values()) {
            if (info.simSlot == simSlot &&
                (info.state == Call.STATE_ACTIVE || info.state == Call.STATE_RINGING ||
                 info.state == Call.STATE_DIALING || info.state == Call.STATE_CONNECTING)) {
                Log.i(TAG, "Disconnecting call: " + info.callId);
                try {
                    info.call.disconnect();
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to disconnect call: " + e.getMessage(), e);
                }
            }
        }
        return false;
    }
}
