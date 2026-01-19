package com.gsmgateway;

/**
 * Tracks the state of a single call session
 * Used to manage call flow: wait for answer before bridging
 */
public class CallSession {
    private int simSlot;
    private String callerNumber;
    private CallDirection direction;
    private CallState state;
    private String sipChannel;
    private boolean rtpActive;
    private long startTime;
    
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
    
    public String getSipChannel() {
        return sipChannel;
    }
    
    public void setSipChannel(String sipChannel) {
        this.sipChannel = sipChannel;
    }
    
    public boolean isRtpActive() {
        return rtpActive;
    }
    
    public void setRtpActive(boolean rtpActive) {
        this.rtpActive = rtpActive;
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
     */
    public boolean canStartRTP() {
        return (state == CallState.SIP_ANSWERED && isIncomingGSM()) ||
               (state == CallState.GSM_ANSWERED && isOutgoingGSM());
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