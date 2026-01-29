package com.shreeyash.gateway;

import android.util.Log;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the state of a single call session with thread-safe state management.
 * Validates state transitions to prevent invalid call flows.
 *
 * Call Flow (Incoming GSM):
 *   IDLE -> GSM_RINGING -> SIP_DIALING -> SIP_ANSWERED -> GSM_ANSWERED -> BRIDGED -> ENDING
 *
 * Call Flow (Outgoing GSM / SIP->GSM):
 *   IDLE -> SIP_RINGING -> GSM_RINGING -> GSM_ANSWERED -> BRIDGED -> ENDING
 */
public class CallSession {
    private static final String TAG = "CallSession";

    // Core session data
    private final int simSlot;
    private volatile String callerNumber;  // Mutable to allow late caller ID update
    private final CallDirection direction;
    private final long startTime;

    // SIP call info
    private String sipCallId;
    private String remoteRtpAddress;
    private int remoteRtpPort;

    // State management - thread-safe
    private final Object stateLock = new Object();
    private volatile CallState state;
    private volatile boolean rtpActive;
    private volatile boolean gsmAnswered;
    private volatile boolean sipAnswered;

    // Timestamps for debugging
    private long stateChangeTime;
    private String endReason;

    public enum CallDirection {
        INCOMING_GSM,  // GSM call coming in -> forward to PBX
        OUTGOING_GSM   // PBX wants to call via GSM
    }

    public enum CallState {
        IDLE,             // Initial state
        GSM_RINGING,      // GSM call ringing (incoming) or being placed (outgoing)
        SIP_DIALING,      // Sending INVITE to PBX
        SIP_RINGING,      // Waiting for PBX to answer (incoming SIP INVITE received)
        SIP_ANSWERED,     // PBX answered (200 OK received/sent)
        GSM_ANSWERED,     // GSM call answered
        BRIDGED,          // Both sides connected, RTP active
        ENDING            // Call is being torn down (terminal state)
    }

    // Valid state transitions - prevents invalid call flows
    private static final Map<CallState, Set<CallState>> VALID_TRANSITIONS = new HashMap<>();
    static {
        // From IDLE: can start with GSM ringing (incoming) or SIP ringing (outgoing via PBX)
        VALID_TRANSITIONS.put(CallState.IDLE, EnumSet.of(
            CallState.GSM_RINGING,
            CallState.SIP_RINGING,
            CallState.ENDING));

        // From GSM_RINGING: either dial PBX (incoming) or wait for answer (outgoing)
        VALID_TRANSITIONS.put(CallState.GSM_RINGING, EnumSet.of(
            CallState.SIP_DIALING,    // Incoming GSM: now dial PBX
            CallState.GSM_ANSWERED,   // Outgoing GSM: GSM answered
            CallState.ENDING));

        // From SIP_DIALING: wait for PBX response
        VALID_TRANSITIONS.put(CallState.SIP_DIALING, EnumSet.of(
            CallState.SIP_RINGING,    // Got 180 Ringing
            CallState.SIP_ANSWERED,   // Got 200 OK (skip ringing)
            CallState.ENDING));

        // From SIP_RINGING: PBX answers or call ends
        VALID_TRANSITIONS.put(CallState.SIP_RINGING, EnumSet.of(
            CallState.SIP_ANSWERED,   // PBX answered
            CallState.GSM_RINGING,    // Outgoing: now place GSM call
            CallState.GSM_ANSWERED,   // Outgoing: GSM answered first
            CallState.ENDING));

        // From SIP_ANSWERED: answer GSM (incoming) or wait for GSM answer (outgoing)
        VALID_TRANSITIONS.put(CallState.SIP_ANSWERED, EnumSet.of(
            CallState.GSM_ANSWERED,   // GSM also answered
            CallState.BRIDGED,        // Direct to bridged if GSM already answered
            CallState.ENDING));

        // From GSM_ANSWERED: answer SIP (outgoing) or wait for bridge
        VALID_TRANSITIONS.put(CallState.GSM_ANSWERED, EnumSet.of(
            CallState.SIP_ANSWERED,   // SIP also answered
            CallState.BRIDGED,        // Bridge established
            CallState.ENDING));

        // From BRIDGED: can only end
        VALID_TRANSITIONS.put(CallState.BRIDGED, EnumSet.of(
            CallState.ENDING));

        // ENDING is terminal - no transitions out
        VALID_TRANSITIONS.put(CallState.ENDING, EnumSet.noneOf(CallState.class));
    }

    public CallSession(int simSlot, String callerNumber, CallDirection direction) {
        this.simSlot = simSlot;
        this.callerNumber = callerNumber;
        this.direction = direction;
        this.state = CallState.IDLE;
        this.rtpActive = false;
        this.startTime = System.currentTimeMillis();
        this.stateChangeTime = this.startTime;
        this.gsmAnswered = false;
        this.sipAnswered = false;
    }

    // ==================== State Management ====================

    /**
     * Attempt to transition to a new state.
     * Thread-safe with validation.
     *
     * @param newState The desired new state
     * @return true if transition was valid and applied, false otherwise
     */
    public boolean setState(CallState newState) {
        synchronized (stateLock) {
            CallState oldState = this.state;

            // Check if already in this state
            if (oldState == newState) {
                Log.d(TAG, "SIM" + simSlot + " already in state " + newState);
                return true;
            }

            // Check if transition is valid
            Set<CallState> allowed = VALID_TRANSITIONS.get(oldState);
            if (allowed == null || !allowed.contains(newState)) {
                Log.w(TAG, "SIM" + simSlot + " INVALID state transition: " + oldState + " -> " + newState);
                return false;
            }

            // Apply transition
            this.state = newState;
            this.stateChangeTime = System.currentTimeMillis();

            Log.i(TAG, "SIM" + simSlot + " state: " + oldState + " -> " + newState +
                       " (after " + (stateChangeTime - startTime) + "ms)");

            return true;
        }
    }

    /**
     * Force state to ENDING regardless of current state.
     * Use only for error recovery.
     */
    public void forceEnd(String reason) {
        synchronized (stateLock) {
            CallState oldState = this.state;
            this.state = CallState.ENDING;
            this.endReason = reason;
            this.stateChangeTime = System.currentTimeMillis();
            Log.w(TAG, "SIM" + simSlot + " FORCE END: " + oldState + " -> ENDING, reason: " + reason);
        }
    }

    /**
     * Get current state (thread-safe read)
     */
    public CallState getState() {
        synchronized (stateLock) {
            return state;
        }
    }

    /**
     * Check if in a specific state (thread-safe)
     */
    public boolean isInState(CallState checkState) {
        synchronized (stateLock) {
            return state == checkState;
        }
    }

    // ==================== Answer State Management ====================

    /**
     * Mark GSM side as answered (thread-safe)
     */
    public void setGsmAnswered(boolean answered) {
        synchronized (stateLock) {
            this.gsmAnswered = answered;
            Log.i(TAG, "SIM" + simSlot + " GSM answered: " + answered);
        }
    }

    public boolean isGsmAnswered() {
        synchronized (stateLock) {
            return gsmAnswered;
        }
    }

    /**
     * Mark SIP side as answered (thread-safe)
     */
    public void setSipAnswered(boolean answered) {
        synchronized (stateLock) {
            this.sipAnswered = answered;
            Log.i(TAG, "SIM" + simSlot + " SIP answered: " + answered);
        }
    }

    public boolean isSipAnswered() {
        synchronized (stateLock) {
            return sipAnswered;
        }
    }

    /**
     * Check if RTP bridge can start (both sides answered)
     */
    public boolean canStartRTP() {
        synchronized (stateLock) {
            return gsmAnswered && sipAnswered && !rtpActive && state != CallState.ENDING;
        }
    }

    // ==================== RTP State ====================

    public void setRtpActive(boolean active) {
        synchronized (stateLock) {
            this.rtpActive = active;
            Log.i(TAG, "SIM" + simSlot + " RTP active: " + active);
        }
    }

    public boolean isRtpActive() {
        synchronized (stateLock) {
            return rtpActive;
        }
    }

    // ==================== Immutable Getters ====================

    public int getSimSlot() {
        return simSlot;
    }

    public String getCallerNumber() {
        return callerNumber;
    }

    /**
     * Update caller number if it was initially unknown.
     * Used when InCallService provides caller ID after session was created.
     */
    public void updateCallerNumber(String newNumber) {
        synchronized (stateLock) {
            if (newNumber != null && !newNumber.isEmpty() && !newNumber.equals("Unknown")) {
                String oldNumber = this.callerNumber;
                this.callerNumber = newNumber;
                Log.i(TAG, "SIM" + simSlot + " caller ID updated: " + oldNumber + " -> " + newNumber);
            }
        }
    }

    public CallDirection getDirection() {
        return direction;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getDuration() {
        return System.currentTimeMillis() - startTime;
    }

    public String getEndReason() {
        return endReason;
    }

    // ==================== SIP Info ====================

    public String getSipCallId() {
        synchronized (stateLock) {
            return sipCallId;
        }
    }

    public void setSipCallId(String sipCallId) {
        synchronized (stateLock) {
            this.sipCallId = sipCallId;
        }
    }

    public String getRemoteRtpAddress() {
        synchronized (stateLock) {
            return remoteRtpAddress;
        }
    }

    public void setRemoteRtpAddress(String remoteRtpAddress) {
        synchronized (stateLock) {
            this.remoteRtpAddress = remoteRtpAddress;
        }
    }

    public int getRemoteRtpPort() {
        synchronized (stateLock) {
            return remoteRtpPort;
        }
    }

    public void setRemoteRtpPort(int remoteRtpPort) {
        synchronized (stateLock) {
            this.remoteRtpPort = remoteRtpPort;
        }
    }

    // ==================== Convenience Methods ====================

    public boolean isIncomingGSM() {
        return direction == CallDirection.INCOMING_GSM;
    }

    public boolean isOutgoingGSM() {
        return direction == CallDirection.OUTGOING_GSM;
    }

    public boolean isRinging() {
        synchronized (stateLock) {
            return state == CallState.GSM_RINGING ||
                   state == CallState.SIP_DIALING ||
                   state == CallState.SIP_RINGING;
        }
    }

    public boolean isBridged() {
        synchronized (stateLock) {
            return state == CallState.BRIDGED;
        }
    }

    public boolean isEnding() {
        synchronized (stateLock) {
            return state == CallState.ENDING;
        }
    }

    public boolean isActive() {
        synchronized (stateLock) {
            return state != CallState.IDLE && state != CallState.ENDING;
        }
    }

    // ==================== Debug ====================

    @Override
    public String toString() {
        synchronized (stateLock) {
            return String.format("CallSession{SIM%d, %s, %s, State=%s, GSM=%s, SIP=%s, RTP=%s, %dms}",
                simSlot,
                direction,
                callerNumber != null ? callerNumber : "unknown",
                state,
                gsmAnswered ? "ANS" : "---",
                sipAnswered ? "ANS" : "---",
                rtpActive ? "ON" : "OFF",
                getDuration());
        }
    }

    /**
     * Get detailed state for logging
     */
    public String toDetailedString() {
        synchronized (stateLock) {
            StringBuilder sb = new StringBuilder();
            sb.append("CallSession {\n");
            sb.append("  simSlot: ").append(simSlot).append("\n");
            sb.append("  direction: ").append(direction).append("\n");
            sb.append("  callerNumber: ").append(callerNumber).append("\n");
            sb.append("  state: ").append(state).append("\n");
            sb.append("  gsmAnswered: ").append(gsmAnswered).append("\n");
            sb.append("  sipAnswered: ").append(sipAnswered).append("\n");
            sb.append("  rtpActive: ").append(rtpActive).append("\n");
            sb.append("  sipCallId: ").append(sipCallId).append("\n");
            sb.append("  remoteRtp: ").append(remoteRtpAddress).append(":").append(remoteRtpPort).append("\n");
            sb.append("  duration: ").append(getDuration()).append("ms\n");
            if (endReason != null) {
                sb.append("  endReason: ").append(endReason).append("\n");
            }
            sb.append("}");
            return sb.toString();
        }
    }
}
