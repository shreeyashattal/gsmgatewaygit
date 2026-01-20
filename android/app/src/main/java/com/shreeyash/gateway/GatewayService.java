package com.shreeyash.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.shreeyash.gateway.sip.SIPClient;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Main gateway service - manages dual SIM GSM-to-SIP gateway
 * Direct SIP registration with PBX (no Asterisk middleware)
 *
 * CALL FLOWS:
 * - Outgoing GSM (PBX -> GSM): SIP INVITE received -> Place GSM call -> GSM answers -> Send 200 OK -> Bridge RTP
 * - Incoming GSM (GSM -> PBX): GSM rings -> Send SIP INVITE -> PBX answers -> Answer GSM -> Bridge RTP
 */
public class GatewayService extends Service implements SIPClient.SIPEventListener {
    private static final String TAG = "GatewayService";
    private static final String CHANNEL_ID = "gateway_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Managers
    private Config config;
    private DualSIMManager simManager;
    private TelephonyManager telephonyManager;
    private TelecomManager telecomManager;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;

    // Audio state for silencing calls
    private int savedRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private int savedRingVolume = 5;
    private int savedNotificationVolume = 5;
    private boolean ringerSilenced = false;

    // SIP Clients (one per SIM for independent registration)
    private Map<Integer, SIPClient> sipClients;

    // Audio Bridges (one per SIM slot) - Native PCM for SM6150
    private Map<Integer, NativePCMAudioBridge> audioBridges;

    // Root audio routers
    private Map<Integer, RootAudioRouter> audioRouters;

    // Active call sessions
    private Map<Integer, CallSession> activeSessions;

    // SIP Call to SIM slot mapping
    private Map<String, Integer> callIdToSimSlot;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Gateway service created (Direct SIP mode)");

        // Initialize configuration
        config = new Config(this);

        // Detect and set local IP if not configured
        String localIp = getLocalIpAddress();
        if (localIp != null) {
            config.setLocalIP(localIp);
            Log.i(TAG, "Detected local IP: " + localIp);
        }

        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Initialize dual SIM manager
        simManager = new DualSIMManager(this);

        // Initialize maps
        sipClients = new HashMap<>();
        audioBridges = new HashMap<>();
        audioRouters = new HashMap<>();
        activeSessions = new HashMap<>();
        callIdToSimSlot = new HashMap<>();

        // Initialize audio bridges and routers for both SIMs
        for (int sim = 1; sim <= 2; sim++) {
            audioBridges.put(sim, new NativePCMAudioBridge(Config.getRTPPort(sim)));
            audioRouters.put(sim, new RootAudioRouter(sim));
        }

        // Acquire wakelock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GatewayService::WakeLock"
        );
        wakeLock.acquire();

        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Starting..."));

        // Start SIP clients
        startSIPClients();
    }

    /**
     * Start SIP clients for each SIM
     */
    private void startSIPClients() {
        String pbxHost = config.getPBXHost();
        int pbxPort = config.getPBXPort();
        String localIp = config.getLocalIP();

        if (localIp == null) {
            Log.e(TAG, "Local IP not detected - cannot start SIP");
            updateNotification("ERROR: Cannot detect local IP");
            return;
        }

        // Log configuration status
        if (pbxHost == null) {
            Log.w(TAG, "PBX host not configured - running in trunk/listen mode only");
            Log.w(TAG, "Incoming GSM calls won't be forwarded until PBX host is set");
        } else {
            Log.i(TAG, "PBX configured: " + pbxHost + ":" + pbxPort);
        }
        Log.i(TAG, "Local IP: " + localIp);

        // Start SIP client for SIM1 (always start to listen for incoming)
        if (simManager.isSimActive(1)) {
            startSIPClient(1, pbxHost, pbxPort, localIp);
        }

        // Start SIP client for SIM2
        if (simManager.isSimActive(2)) {
            startSIPClient(2, pbxHost, pbxPort, localIp);
        }

        if (sipClients.isEmpty()) {
            updateNotification("No active SIMs detected");
        } else if (pbxHost == null) {
            updateNotification("Listening (trunk mode) - PBX not configured");
        }
    }

    /**
     * Start SIP client for a specific SIM
     */
    private void startSIPClient(int simSlot, String pbxHost, int pbxPort, String localIp) {
        String username = config.getSIPUsername(simSlot);
        String password = config.getSIPPassword(simSlot);

        // Each SIM gets its own SIP port
        int localSipPort = config.getLocalSIPPort() + (simSlot - 1) * 2;

        SIPClient client = new SIPClient(pbxHost, pbxPort, username, password, localIp, localSipPort);
        client.setEventListener(new SIPClientListener(simSlot));

        if (client.start()) {
            sipClients.put(simSlot, client);
            Log.i(TAG, "SIP client started for SIM" + simSlot + " on port " + localSipPort);
        } else {
            Log.e(TAG, "Failed to start SIP client for SIM" + simSlot);
        }
    }

    /**
     * SIP event listener wrapper for each SIM
     */
    private class SIPClientListener implements SIPClient.SIPEventListener {
        private final int simSlot;

        SIPClientListener(int simSlot) {
            this.simSlot = simSlot;
        }

        @Override
        public void onRegistered() {
            Log.i(TAG, "SIM" + simSlot + " registered with PBX");
            updateNotification(getStatusSummary());
        }

        @Override
        public void onRegistrationFailed(String reason) {
            Log.e(TAG, "SIM" + simSlot + " registration failed: " + reason);
            updateNotification("SIM" + simSlot + " reg failed: " + reason);
        }

        @Override
        public void onIncomingCall(SIPClient.SIPCall sipCall, String dialedNumber) {
            // Incoming SIP call = Outgoing GSM call (PBX wants to call via GSM)
            handleIncomingSIPCall(simSlot, sipCall, dialedNumber);
        }

        @Override
        public void onCallAnswered(SIPClient.SIPCall sipCall) {
            handleSIPCallAnswered(simSlot, sipCall);
        }

        @Override
        public void onCallEnded(SIPClient.SIPCall sipCall) {
            handleSIPCallEnded(simSlot, sipCall);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        int simSlot = intent.getIntExtra("sim_slot", 1);

        if (action == null) {
            return START_STICKY;
        }

        Log.i(TAG, "Action: " + action + ", SIM: " + simSlot);

        switch (action) {
            case "INCOMING_GSM_CALL":
                String incomingNumber = intent.getStringExtra("number");
                handleIncomingGSMCall(simSlot, incomingNumber);
                break;

            case "GSM_CALL_ANSWERED":
                handleGSMCallAnswered(simSlot);
                break;

            case "GSM_CALL_ENDED":
                handleGSMCallEnded(simSlot);
                break;

            case "CONFIGURE":
                // Re-read configuration and restart SIP clients
                stopSIPClients();
                startSIPClients();
                break;
        }

        return START_STICKY;
    }

    // ==================== OUTGOING GSM CALL FLOW ====================
    // PBX sends INVITE -> We place GSM call -> GSM answers -> We send 200 OK

    /**
     * Handle incoming SIP INVITE (PBX wants to make an outgoing GSM call)
     */
    private void handleIncomingSIPCall(int simSlot, SIPClient.SIPCall sipCall, String dialedNumber) {
        Log.i(TAG, "Incoming SIP call on SIM" + simSlot + " to dial: " + dialedNumber);

        // Check if SIM is busy
        if (activeSessions.containsKey(simSlot)) {
            Log.w(TAG, "SIM" + simSlot + " is busy - rejecting call");
            SIPClient client = sipClients.get(simSlot);
            if (client != null) {
                client.hangup(sipCall);
            }
            return;
        }

        // Silence the phone ringer and configure audio for gateway mode
        silenceRinger();
        configureGatewayAudio();

        // Create session
        CallSession session = new CallSession(simSlot, dialedNumber, CallSession.CallDirection.OUTGOING_GSM);
        session.setState(CallSession.CallState.SIP_RINGING);
        session.setSipCallId(sipCall.callId);
        session.setRemoteRtpAddress(sipCall.remoteRtpAddress);
        session.setRemoteRtpPort(sipCall.remoteRtpPort);
        activeSessions.put(simSlot, session);
        callIdToSimSlot.put(sipCall.callId, simSlot);

        updateNotification("Outgoing SIM" + simSlot + ": " + dialedNumber);

        // Place GSM call
        initiateGSMCall(simSlot, dialedNumber);
    }

    /**
     * Initiate outgoing GSM call
     */
    private void initiateGSMCall(int simSlot, String number) {
        try {
            Uri uri = Uri.parse("tel:" + number);
            PhoneAccountHandle account = simManager.getPhoneAccount(simSlot);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && telecomManager != null) {
                Bundle extras = new Bundle();
                if (account != null) {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account);
                    Log.i(TAG, "Using phone account: " + account.getId() + " for SIM" + simSlot);
                }

                telecomManager.placeCall(uri, extras);

                CallSession session = activeSessions.get(simSlot);
                if (session != null) {
                    session.setState(CallSession.CallState.GSM_RINGING);
                }

                Log.i(TAG, "Placed GSM call to " + number + " on SIM" + simSlot);
            } else {
                // Fallback for older devices
                Intent callIntent = new Intent(Intent.ACTION_CALL);
                callIntent.setData(uri);
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (account != null) {
                    callIntent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account);
                }

                startActivity(callIntent);
                Log.i(TAG, "Initiated GSM call (legacy) to " + number + " on SIM" + simSlot);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for GSM call: " + e.getMessage(), e);
            cleanupFailedCall(simSlot, "Permission denied");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate GSM call: " + e.getMessage(), e);
            cleanupFailedCall(simSlot, e.getMessage());
        }
    }

    /**
     * GSM call was answered - now answer the SIP call and start RTP
     */
    private void handleGSMCallAnswered(int simSlot) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null) {
            Log.w(TAG, "No active session for SIM" + simSlot);
            return;
        }

        Log.i(TAG, "GSM call answered on SIM" + simSlot);
        session.setGsmAnswered(true);
        session.setState(CallSession.CallState.GSM_ANSWERED);

        // For OUTGOING_GSM: GSM answered, now answer the SIP call
        if (session.isOutgoingGSM()) {
            SIPClient client = sipClients.get(simSlot);
            SIPClient.SIPCall sipCall = client != null ? client.getCall(session.getSipCallId()) : null;

            if (sipCall != null) {
                int rtpPort = Config.getRTPPort(simSlot);
                client.answerCall(sipCall, rtpPort);
                session.setSipAnswered(true);
                Log.i(TAG, "Answered SIP call for SIM" + simSlot);
            }
        }

        // Start RTP bridge if both sides are ready
        if (session.canStartRTP() && !session.isRtpActive()) {
            startRTPBridge(simSlot);
        }
    }

    // ==================== INCOMING GSM CALL FLOW ====================
    // GSM rings -> We send INVITE to PBX -> PBX answers -> We answer GSM

    /**
     * Handle incoming GSM call
     */
    private void handleIncomingGSMCall(int simSlot, String callerNumber) {
        Log.i(TAG, "Incoming GSM call on SIM" + simSlot + " from: " + callerNumber);

        // IMMEDIATELY silence the ringer to prevent phone from ringing/vibrating
        silenceRinger();

        // Check if SIM is busy
        if (activeSessions.containsKey(simSlot)) {
            Log.w(TAG, "SIM" + simSlot + " is busy");
            return;
        }

        // Configure audio for gateway mode
        configureGatewayAudio();

        // Check if PBX address is known (configured or learned from REGISTER)
        SIPClient client = sipClients.get(simSlot);
        if (client == null || !client.isRegistered()) {
            Log.e(TAG, "SIP client not available for SIM" + simSlot);
            return;
        }

        if (!client.hasPbxAddress()) {
            Log.w(TAG, "Cannot forward incoming GSM call - PBX address not known yet");
            Log.w(TAG, "Waiting for PBX to register (send REGISTER to this device)");
            updateNotification("GSM call waiting - PBX not connected");
            return;
        }

        // Create session
        CallSession session = new CallSession(simSlot, callerNumber, CallSession.CallDirection.INCOMING_GSM);
        session.setState(CallSession.CallState.GSM_RINGING);
        activeSessions.put(simSlot, session);

        updateNotification("Incoming SIM" + simSlot + ": " + callerNumber);

        // Send SIP INVITE to PBX
        int rtpPort = Config.getRTPPort(simSlot);
        SIPClient.SIPCall sipCall = client.makeCall(callerNumber, rtpPort);

        if (sipCall == null) {
            Log.e(TAG, "Failed to create SIP call");
            activeSessions.remove(simSlot);
            return;
        }

        session.setSipCallId(sipCall.callId);
        session.setState(CallSession.CallState.SIP_DIALING);
        callIdToSimSlot.put(sipCall.callId, simSlot);

        Log.i(TAG, "Sent INVITE to PBX for incoming GSM call");
    }

    /**
     * SIP call was answered by PBX - now answer the GSM call
     */
    private void handleSIPCallAnswered(int simSlot, SIPClient.SIPCall sipCall) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null) {
            Log.w(TAG, "No session for SIP call answer on SIM" + simSlot);
            return;
        }

        Log.i(TAG, "SIP call answered on SIM" + simSlot);
        session.setSipAnswered(true);
        session.setRemoteRtpAddress(sipCall.remoteRtpAddress);
        session.setRemoteRtpPort(sipCall.remoteRtpPort);
        session.setState(CallSession.CallState.SIP_ANSWERED);

        // For INCOMING_GSM: PBX answered, now answer the GSM call
        if (session.isIncomingGSM() && !session.isGsmAnswered()) {
            answerGSMCall(simSlot);
        }

        // Start RTP bridge if both sides are ready
        if (session.canStartRTP() && !session.isRtpActive()) {
            startRTPBridge(simSlot);
        }
    }

    /**
     * Answer the ringing GSM call
     */
    private void answerGSMCall(int simSlot) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.acceptRingingCall();
            } else {
                answerPhoneReflection();
            }

            CallSession session = activeSessions.get(simSlot);
            if (session != null) {
                session.setGsmAnswered(true);
                session.setState(CallSession.CallState.GSM_ANSWERED);
            }

            Log.i(TAG, "GSM call answered on SIM" + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "Failed to answer GSM call: " + e.getMessage(), e);
        }
    }

    /**
     * Reflection-based answer for older Android versions
     */
    private void answerPhoneReflection() {
        try {
            Class<?> classTelephony = Class.forName(telephonyManager.getClass().getName());
            Method methodAnswerCall = classTelephony.getDeclaredMethod("answerRingingCall");
            methodAnswerCall.setAccessible(true);
            methodAnswerCall.invoke(telephonyManager);
        } catch (Exception e) {
            Log.e(TAG, "Reflection answer failed: " + e.getMessage(), e);
        }
    }

    // ==================== RTP BRIDGE ====================

    /**
     * Start audio bridge with native PCM routing (SM6150 specific)
     */
    private synchronized void startRTPBridge(int simSlot) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null) {
            Log.w(TAG, "No session to bridge for SIM" + simSlot);
            return;
        }

        // Check if RTP is already active or being started
        if (session.isRtpActive()) {
            Log.d(TAG, "RTP bridge already active for SIM" + simSlot);
            return;
        }

        // Check if the audio bridge is already running
        NativePCMAudioBridge audioBridge = audioBridges.get(simSlot);
        if (audioBridge != null && audioBridge.isRunning()) {
            Log.d(TAG, "Audio bridge already running for SIM" + simSlot);
            session.setRtpActive(true);
            return;
        }

        Log.i(TAG, "Starting native PCM audio bridge for SIM" + simSlot);
        Log.i(TAG, "Remote RTP: " + session.getRemoteRtpAddress() + ":" + session.getRemoteRtpPort());
        updateNotification("Bridging SIM" + simSlot + " call");

        // Mark RTP as active BEFORE starting to prevent race conditions
        session.setRtpActive(true);

        // Configure and start native PCM audio bridge (handles its own routing)
        audioBridge.setRemoteAddress(session.getRemoteRtpAddress(), session.getRemoteRtpPort());

        if (!audioBridge.start()) {
            Log.e(TAG, "Failed to start audio bridge for SIM" + simSlot);
            session.setRtpActive(false);
            endCall(simSlot);
            return;
        }

        session.setState(CallSession.CallState.BRIDGED);

        updateNotification("Active: SIM" + simSlot + " <-> " + session.getCallerNumber());
        Log.i(TAG, "Call fully bridged: " + session);
    }

    // ==================== CALL ENDING ====================

    /**
     * Handle GSM call ended
     */
    private void handleGSMCallEnded(int simSlot) {
        Log.i(TAG, "GSM call ended on SIM" + simSlot);
        endCall(simSlot);
    }

    /**
     * Handle SIP call ended (BYE received)
     */
    private void handleSIPCallEnded(int simSlot, SIPClient.SIPCall sipCall) {
        Log.i(TAG, "SIP call ended on SIM" + simSlot);
        endGSMCall(simSlot);
        endCall(simSlot);
    }

    /**
     * End a complete call session
     */
    private void endCall(int simSlot) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null) {
            return;
        }

        session.setState(CallSession.CallState.ENDING);
        Log.i(TAG, "Ending call session: " + session);

        // Stop native PCM audio bridge
        NativePCMAudioBridge audioBridge = audioBridges.get(simSlot);
        if (audioBridge != null && audioBridge.isRunning()) {
            audioBridge.stop();
        }

        // Hangup SIP call
        if (session.getSipCallId() != null) {
            SIPClient client = sipClients.get(simSlot);
            if (client != null) {
                SIPClient.SIPCall sipCall = client.getCall(session.getSipCallId());
                if (sipCall != null) {
                    client.hangup(sipCall);
                }
            }
            callIdToSimSlot.remove(session.getSipCallId());
        }

        // End GSM call
        endGSMCall(simSlot);

        // Remove session
        activeSessions.remove(simSlot);

        // Restore ringer and audio state
        restoreRinger();
        restoreNormalAudio();

        // Update notification
        updateNotification(getStatusSummary());
    }

    /**
     * End GSM call
     */
    private void endGSMCall(int simSlot) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall();
            } else {
                endCallReflection();
            }
            Log.i(TAG, "Ended GSM call on SIM" + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "Failed to end GSM call: " + e.getMessage(), e);
        }
    }

    /**
     * Reflection-based end call
     */
    private void endCallReflection() {
        try {
            Class<?> classTelephony = Class.forName(telephonyManager.getClass().getName());
            Method methodEndCall = classTelephony.getDeclaredMethod("endCall");
            methodEndCall.setAccessible(true);
            methodEndCall.invoke(telephonyManager);
        } catch (Exception e) {
            Log.e(TAG, "Reflection end call failed: " + e.getMessage(), e);
        }
    }

    private void cleanupFailedCall(int simSlot, String reason) {
        CallSession session = activeSessions.get(simSlot);
        if (session != null && session.getSipCallId() != null) {
            SIPClient client = sipClients.get(simSlot);
            if (client != null) {
                SIPClient.SIPCall sipCall = client.getCall(session.getSipCallId());
                if (sipCall != null) {
                    client.hangup(sipCall);
                }
            }
        }
        activeSessions.remove(simSlot);
        updateNotification("Call failed: " + reason);
    }

    // ==================== AUDIO CONTROL ====================

    /**
     * Silence phone ringer and vibration during gateway calls
     * This prevents the phone from ringing/vibrating when handling gateway calls
     */
    private void silenceRinger() {
        if (ringerSilenced) return;

        try {
            // Method 1: Try to silence via TelecomManager first (most effective for incoming calls)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    telecomManager.silenceRinger();
                    Log.i(TAG, "Silenced ringer via TelecomManager");
                } catch (Exception e) {
                    Log.d(TAG, "TelecomManager silenceRinger not available: " + e.getMessage());
                }
            }

            // Method 2: Save and mute ring/notification volumes (doesn't require DND permission)
            try {
                savedRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                savedNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);

                // Set volumes to 0
                audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0);
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0);
                Log.i(TAG, "Muted ring/notification volumes");
            } catch (Exception e) {
                Log.d(TAG, "Could not mute volumes: " + e.getMessage());
            }

            // Method 3: Try ringer mode change (may fail on MIUI without DND permission)
            try {
                savedRingerMode = audioManager.getRingerMode();
                audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            } catch (SecurityException e) {
                // Expected on MIUI - DND permission required
                Log.d(TAG, "Cannot set ringer mode (DND permission required): " + e.getMessage());
            }

            // Method 4: Adjust vibrate settings
            try {
                audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_OFF);
                audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_OFF);
            } catch (Exception e) {
                Log.d(TAG, "Could not disable vibrate: " + e.getMessage());
            }

            ringerSilenced = true;
            Log.i(TAG, "Phone ringer silenced for gateway call");

        } catch (Exception e) {
            Log.e(TAG, "Failed to silence ringer: " + e.getMessage(), e);
        }
    }

    /**
     * Restore phone ringer to previous state
     */
    private void restoreRinger() {
        if (!ringerSilenced) return;

        try {
            // Only restore if no active sessions
            if (activeSessions.isEmpty()) {
                // Restore volumes
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0);
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotificationVolume, 0);
                } catch (Exception e) {
                    Log.d(TAG, "Could not restore volumes: " + e.getMessage());
                }

                // Restore ringer mode
                try {
                    audioManager.setRingerMode(savedRingerMode);
                } catch (SecurityException e) {
                    Log.d(TAG, "Cannot restore ringer mode (DND permission required)");
                }

                // Restore vibrate
                try {
                    audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_ON);
                    audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_ON);
                } catch (Exception e) {
                    Log.d(TAG, "Could not restore vibrate: " + e.getMessage());
                }

                ringerSilenced = false;
                Log.i(TAG, "Phone ringer restored");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore ringer: " + e.getMessage(), e);
        }
    }

    /**
     * Configure audio for voice call gateway mode
     */
    private void configureGatewayAudio() {
        try {
            // Request audio focus
            audioManager.requestAudioFocus(null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

            // Set mode to in-call
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            // Route to earpiece/speaker based on call
            audioManager.setSpeakerphoneOn(false);

            // Mute the phone's own mic (gateway handles audio via RTP)
            audioManager.setMicrophoneMute(true);

            Log.i(TAG, "Audio configured for gateway mode");

        } catch (Exception e) {
            Log.e(TAG, "Failed to configure gateway audio: " + e.getMessage(), e);
        }
    }

    /**
     * Restore normal audio state
     */
    private void restoreNormalAudio() {
        try {
            // Only restore if no active sessions
            if (activeSessions.isEmpty()) {
                audioManager.abandonAudioFocus(null);
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setMicrophoneMute(false);
                Log.i(TAG, "Audio restored to normal mode");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore normal audio: " + e.getMessage(), e);
        }
    }

    // ==================== UTILITY ====================

    /**
     * Get local IP address
     */
    private String getLocalIpAddress() {
        try {
            // Try WiFi first
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ip = wifiManager.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                        (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }

            // Fallback to network interfaces
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get local IP: " + e.getMessage());
        }
        return null;
    }

    /**
     * Get status summary for notification
     */
    private String getStatusSummary() {
        StringBuilder sb = new StringBuilder();

        // Registration status
        int registeredCount = 0;
        for (Map.Entry<Integer, SIPClient> entry : sipClients.entrySet()) {
            if (entry.getValue().isRegistered()) {
                registeredCount++;
            }
        }

        if (registeredCount > 0) {
            sb.append("Ready (").append(registeredCount).append(" SIM");
            if (registeredCount > 1) sb.append("s");
            sb.append(" registered)");
        } else if (!sipClients.isEmpty()) {
            sb.append("Connecting...");
        } else {
            sb.append("No SIMs configured");
        }

        // Active calls
        if (!activeSessions.isEmpty()) {
            sb.append(" | ");
            for (CallSession session : activeSessions.values()) {
                sb.append("SIM").append(session.getSimSlot()).append(": ");
                sb.append(session.isBridged() ? "Active" : "Connecting");
            }
        }

        return sb.toString();
    }

    /**
     * Stop all SIP clients
     */
    private void stopSIPClients() {
        for (SIPClient client : sipClients.values()) {
            client.stop();
        }
        sipClients.clear();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Clean up all sessions
        for (int simSlot : activeSessions.keySet()) {
            endCall(simSlot);
        }

        // Stop SIP clients
        stopSIPClients();

        // Stop all audio bridges
        for (NativePCMAudioBridge audioBridge : audioBridges.values()) {
            audioBridge.stop();
        }

        // Release wakelock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        Log.i(TAG, "Gateway service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==================== NOTIFICATION ====================

    private Notification createNotification(String text) {
        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "GSM Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM-SIP Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(text));
    }

    // Unused SIPEventListener methods (we use the wrapper class)
    @Override
    public void onRegistered() {}
    @Override
    public void onRegistrationFailed(String reason) {}
    @Override
    public void onIncomingCall(SIPClient.SIPCall call, String dialedNumber) {}
    @Override
    public void onCallAnswered(SIPClient.SIPCall call) {}
    @Override
    public void onCallEnded(SIPClient.SIPCall call) {}
}
