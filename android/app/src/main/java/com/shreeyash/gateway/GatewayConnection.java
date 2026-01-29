package com.shreeyash.gateway;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Represents a single call connection in the GSM-SIP Gateway.
 *
 * This class handles the actual call state and audio routing.
 * When we are the ConnectionService, WE control the audio, not the default dialer.
 */
public class GatewayConnection extends Connection {
    private static final String TAG = "GatewayConnection";

    // Call direction
    public enum Direction {
        INCOMING_GSM,   // Someone called the phone's GSM number
        OUTGOING_GSM,   // PBX wants to place a GSM call
        INCOMING_SIP,   // PBX is calling us
        OUTGOING_SIP    // We're calling PBX
    }

    private final Direction direction;
    private final String phoneNumber;
    private final int simSlot;
    private String sipCallId;

    // Listener for connection events
    private ConnectionListener listener;

    public interface ConnectionListener {
        void onConnectionAnswered(GatewayConnection connection);
        void onConnectionDisconnected(GatewayConnection connection, DisconnectCause cause);
        void onConnectionHeld(GatewayConnection connection);
        void onConnectionUnheld(GatewayConnection connection);
    }

    public GatewayConnection(Direction direction, String phoneNumber, int simSlot) {
        this.direction = direction;
        this.phoneNumber = phoneNumber;
        this.simSlot = simSlot;

        // CRITICAL FIX: Remove PROPERTY_SELF_MANAGED
        // This property was breaking the framework's ability to link our wrapper connection
        // to the underlying TelephonyConnection. Without it, the framework properly manages
        // the relationship and propagates answer/disconnect to the real connection while
        // we maintain audio control through the ConnectionManager role.
        // DO NOT SET: setConnectionProperties(PROPERTY_SELF_MANAGED);

        // Set capabilities based on direction
        int capabilities = CAPABILITY_HOLD | CAPABILITY_SUPPORT_HOLD | CAPABILITY_MUTE;
        setConnectionCapabilities(capabilities);

        // Set address
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            setAddress(Uri.fromParts("tel", phoneNumber, null), TelecomManager.PRESENTATION_ALLOWED);
        }

        // Set caller ID
        setCallerDisplayName(phoneNumber, TelecomManager.PRESENTATION_ALLOWED);

        Log.i(TAG, "Created GatewayConnection: " + direction + ", number=" + phoneNumber + ", sim=" + simSlot);
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public int getSimSlot() {
        return simSlot;
    }

    public String getSipCallId() {
        return sipCallId;
    }

    public void setSipCallId(String sipCallId) {
        this.sipCallId = sipCallId;
    }

    /**
     * Called when the call should start ringing (incoming)
     */
    public void setConnectionRinging() {
        Log.i(TAG, "Connection ringing: " + phoneNumber);
        super.setRinging();
    }

    /**
     * Called when the call is dialing (outgoing)
     */
    public void setConnectionDialing() {
        Log.i(TAG, "Connection dialing: " + phoneNumber);
        super.setDialing();
    }

    /**
     * Called when the call is answered and active
     */
    public void setConnectionActive() {
        Log.i(TAG, "Connection active: " + phoneNumber);
        setActive();
    }

    // ==================== Connection Callbacks ====================

    @Override
    public void onAnswer() {
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ onAnswer() called - Framework will propagate to underlying   │");
        Log.i(TAG, "│ TelephonyConnection because PROPERTY_SELF_MANAGED is removed │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");
        Log.i(TAG, "  Number: " + phoneNumber + " | SIM: " + simSlot + " | Direction: " + direction);
        
        // Set connection active immediately for UI feedback
        setActive();
        
        // Notify our gateway service to start audio bridging
        if (listener != null) {
            listener.onConnectionAnswered(this);
        }
    }

    @Override
    public void onAnswer(int videoState) {
        Log.i(TAG, "onAnswer(videoState) called for: " + phoneNumber);
        onAnswer();
    }

    @Override
    public void onReject() {
        Log.i(TAG, "onReject called for: " + phoneNumber);
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        destroy();
        if (listener != null) {
            listener.onConnectionDisconnected(this, new DisconnectCause(DisconnectCause.REJECTED));
        }
    }

    @Override
    public void onDisconnect() {
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ onDisconnect() called - Framework will propagate to          │");
        Log.i(TAG, "│ underlying TelephonyConnection (properly closing GSM call)   │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");
        Log.i(TAG, "  Number: " + phoneNumber + " | SIM: " + simSlot);
        
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
        
        if (listener != null) {
            listener.onConnectionDisconnected(this, new DisconnectCause(DisconnectCause.LOCAL));
        }
    }

    @Override
    public void onAbort() {
        Log.i(TAG, "onAbort called for: " + phoneNumber);
        setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
        destroy();
        if (listener != null) {
            listener.onConnectionDisconnected(this, new DisconnectCause(DisconnectCause.CANCELED));
        }
    }

    @Override
    public void onHold() {
        Log.i(TAG, "onHold called for: " + phoneNumber);
        setOnHold();
        if (listener != null) {
            listener.onConnectionHeld(this);
        }
    }

    @Override
    public void onUnhold() {
        Log.i(TAG, "onUnhold called for: " + phoneNumber);
        setActive();
        if (listener != null) {
            listener.onConnectionUnheld(this);
        }
    }

    @Override
    public void onPlayDtmfTone(char c) {
        Log.i(TAG, "DTMF: " + c);
        // TODO: Send DTMF to SIP or GSM as appropriate
    }

    @Override
    public void onStopDtmfTone() {
        Log.i(TAG, "DTMF stop");
    }

    /**
     * Disconnect the call with a specific cause
     */
    public void disconnect(DisconnectCause cause) {
        Log.i(TAG, "Disconnecting: " + phoneNumber + " cause=" + cause);
        setDisconnected(cause);
        destroy();
    }
}
