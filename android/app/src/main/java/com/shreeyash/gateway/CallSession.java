package com.shreeyash.gateway;

/**
 * Tracks the state of a single call session
 * Used to manage call flow: wait for answer before bridging
 */
public class CallSession {
    private int simSlot;
    private String callerNumber;
    private CallDirection direction;
    private CallState state;
    private String sipCallId;  // SIP Call-ID
    private String remoteRtpAddress;  // Remote RTP IP
    private int remoteRtpPort;  // Remote RTP port
    private boolean rtpActive;
    private long startTime;
    private boolean gsmAnswered;
    private boolean sipAnswered;
    
    public enum CallDirection {
        INCOMING_GSM,  // GSM call coming in
        OUTGOING_GSM   // GSM call going out (initiated by PBX)
    }
    
    public enum CallState {
        IDLE,
        GSM_RINGING,      // GSM call ringing
        SIP_DIALING,      // Dialing PBX via Asterisk
        SIP_RINGING,      // PBX is ringing
        SIP_ANSWERED,     // PBX answered, about to answer GSM
        GSM_ANSWERED,     // GSM answered, about to answer SIP
        BRIDGED,          // Both sides connected, RTP active
        ENDING            // Call is being torn down
    }
    
    public CallSession(int simSlot, String callerNumber, CallDirection direction) {
        this.simSlot = simSlot;
        this.callerNumber = callerNumber;
        this.direction = direction;
        this.state = CallState.IDLE;
        this.rtpActive = false;
        this.startTime = System.currentTimeMillis();
        this.gsmAnswered = false;
        this.sipAnswered = false;
    }
    
    // Getters and setters
    
    public int getSimSlot() {
        return simSlot;
    }
    
    public String getCallerNumber() {
        return callerNumber;
    }
    
    public CallDirection getDirection() {
        return direction;
    }
    
    public CallState getState() {
        return state;
    }
    
    public void setState(CallState state) {
        this.state = state;
    }
    
    public String getSipCallId() {
        return sipCallId;
    }

    public void setSipCallId(String sipCallId) {
        this.sipCallId = sipCallId;
    }

    public String getRemoteRtpAddress() {
        return remoteRtpAddress;
    }

    public void setRemoteRtpAddress(String remoteRtpAddress) {
        this.remoteRtpAddress = remoteRtpAddress;
    }

    public int getRemoteRtpPort() {
        return remoteRtpPort;
    }

    public void setRemoteRtpPort(int remoteRtpPort) {
        this.remoteRtpPort = remoteRtpPort;
    }
    
    public boolean isRtpActive() {
        return rtpActive;
    }
    
    public void setRtpActive(boolean rtpActive) {
        this.rtpActive = rtpActive;
    }

    public boolean isGsmAnswered() {
        return gsmAnswered;
    }

    public void setGsmAnswered(boolean gsmAnswered) {
        this.gsmAnswered = gsmAnswered;
    }

    public boolean isSipAnswered() {
        return sipAnswered;
    }

    public void setSipAnswered(boolean sipAnswered) {
        this.sipAnswered = sipAnswered;
    }

    public long getStartTime() {
        return startTime;
    }
    
    public long getDuration() {
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * Check if this session is for an incoming GSM call
     */
    public boolean isIncomingGSM() {
        return direction == CallDirection.INCOMING_GSM;
    }
    
    /**
     * Check if this session is for an outgoing GSM call
     */
    public boolean isOutgoingGSM() {
        return direction == CallDirection.OUTGOING_GSM;
    }
    
    /**
     * Check if call is in a ringing state
     */
    public boolean isRinging() {
        return state == CallState.GSM_RINGING || 
               state == CallState.SIP_DIALING || 
               state == CallState.SIP_RINGING;
    }
    
    /**
     * Check if call is connected/bridged
     */
    public boolean isBridged() {
        return state == CallState.BRIDGED;
    }
    
    /**
     * Check if call can start RTP
     * RTP can only start when both sides are answered
     */
    public boolean canStartRTP() {
        return gsmAnswered && sipAnswered;
    }
    
    @Override
    public String toString() {
        return String.format("CallSession{SIM%d, %s, %s, State=%s, RTP=%s}", 
            simSlot, 
            direction, 
            callerNumber, 
            state,
            rtpActive ? "Active" : "Inactive");
    }
}