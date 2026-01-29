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
import android.telecom.DisconnectCause;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private boolean audioConfigured = false;

    // Audio focus listener to maintain audio routing during calls
    private AudioManager.OnAudioFocusChangeListener audioFocusListener;

    // SIP Clients (one per SIM for independent registration)
    private Map<Integer, SIPClient> sipClients;

    // Audio Bridges (one per SIM slot) - Native PCM with tinycap/tinyplay
    private Map<Integer, NativePCMAudioBridge> audioBridges;

    // Root audio routers
    private Map<Integer, RootAudioRouter> audioRouters;

    // Active call sessions - synchronized access via sessionLock
    private final Object sessionLock = new Object();
    private Map<Integer, CallSession> activeSessions;

    // SIP Call to SIM slot mapping
    private Map<String, Integer> callIdToSimSlot;

    // Call setup timeout management
    private static final long CALL_SETUP_TIMEOUT_MS = 60000; // 60 seconds
    private ScheduledExecutorService scheduler;
    private Map<Integer, ScheduledFuture<?>> callSetupTimers;

    // Audio path maintenance (prevents mixer path reset)
    private static final long AUDIO_REFRESH_INTERVAL_MS = 3000; // 3 seconds
    private Map<Integer, ScheduledFuture<?>> audioRefreshTimers = new HashMap<>();

    // Active GatewayConnections (from ConnectionService - gives us audio control)
    private Map<Integer, GatewayConnection> activeGatewayConnections;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Gateway service created (Direct SIP mode)");

        // CRITICAL: Start foreground IMMEDIATELY to avoid ForegroundServiceDidNotStartInTimeException
        // Android requires startForeground() within ~10 seconds of startForegroundService()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."));

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

        // CRITICAL: Register and enable phone accounts so we can intercept GSM calls
        // This must happen BEFORE initializing SIP to ensure we're ready for incoming calls
        GatewayConnectionService.registerPhoneAccounts(this);
        Log.i(TAG, "✓ Phone accounts registered and enabled");

        // Initialize dual SIM manager
        simManager = new DualSIMManager(this);

        // Initialize maps
        sipClients = new HashMap<>();
        audioBridges = new HashMap<>();
        audioRouters = new HashMap<>();
        activeSessions = new HashMap<>();
        callIdToSimSlot = new HashMap<>();
        callSetupTimers = new HashMap<>();
        activeGatewayConnections = new HashMap<>();

        // Initialize scheduler for timeouts (MUST be before audioFocusListener)
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Initialize audio focus listener (needs scheduler to be initialized)
        audioFocusListener = focusChange -> {
            Log.i(TAG, "Audio focus changed: " + focusChange);
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // We lost focus - try to re-acquire if we have active calls
                    boolean hasActiveCalls;
                    synchronized (sessionLock) {
                        hasActiveCalls = !activeSessions.isEmpty();
                    }
                    if (hasActiveCalls) {
                        Log.w(TAG, "Lost audio focus during active call - re-acquiring...");
                        // Re-request focus immediately
                        scheduler.execute(() -> reacquireAudioFocus());
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.i(TAG, "Audio focus gained");
                    // Re-apply audio mode in case it was changed
                    if (audioConfigured && audioManager != null) {
                        int currentMode = audioManager.getMode();
                        if (currentMode != AudioManager.MODE_IN_CALL) {
                            Log.w(TAG, "Audio mode changed to " + currentMode + ", restoring to IN_CALL");
                            audioManager.setMode(AudioManager.MODE_IN_CALL);
                        }
                    }
                    break;
            }
        };

        // Initialize audio bridges for both SIMs
        // Using NativePCMAudioBridge with tinycap/tinyplay for direct ALSA access
        // This matches the mixer paths set up by RootAudioRouter:
        // - Capture: VOC_REC_DL → MultiMedia1 → tinycap → RTP
        // - Injection: RTP → tinyplay → MultiMedia2 → Incall_Music
        for (int sim = 1; sim <= 2; sim++) {
            audioBridges.put(sim, new NativePCMAudioBridge(Config.getRTPPort(sim)));
            audioRouters.put(sim, new RootAudioRouter(sim));
        }
        Log.i(TAG, "Audio bridges initialized with NativePCMAudioBridge (direct ALSA)");

        // Initialize RootAudioRouters in background thread (tinymix commands are slow)
        scheduler.execute(() -> {
            Log.i(TAG, "Initializing RootAudioRouters in background...");
            for (RootAudioRouter router : audioRouters.values()) {
                router.init(); // Detect audio devices and mixer controls
            }
            Log.i(TAG, "RootAudioRouters initialized");
        });

        // Register for InCallService events (fallback monitoring)
        GatewayInCallService.setCallEventListener(new GatewayInCallService.CallEventListener() {
            @Override
            public void onCallAdded(GatewayInCallService.CallInfo callInfo) {
                Log.i(TAG, "InCallService: Call added - " + callInfo.phoneNumber);
            }

            @Override
            public void onCallStateChanged(GatewayInCallService.CallInfo callInfo, int oldState, int newState) {
                Log.i(TAG, "InCallService: Call state changed - " + oldState + " -> " + newState);
            }

            @Override
            public void onCallRemoved(GatewayInCallService.CallInfo callInfo) {
                Log.i(TAG, "InCallService: Call removed - " + callInfo.phoneNumber);
            }
        });

        // Register for ConnectionService events (PRIMARY - gives us audio control)
        GatewayConnectionService.setServiceListener(new GatewayConnectionService.ConnectionServiceListener() {
            @Override
            public void onIncomingCall(GatewayConnection connection) {
                Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
                Log.i(TAG, "║ ConnectionService: INCOMING GSM CALL                       ║");
                Log.i(TAG, "║ We are the dialer - we control the audio!                  ║");
                Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
                Log.i(TAG, "Number: " + connection.getPhoneNumber() + ", SIM: " + connection.getSimSlot());

                int simSlot = connection.getSimSlot();
                activeGatewayConnections.put(simSlot, connection);

                // DON'T send INVITE here - caller ID may not be available yet
                // Wait for InCallService to provide the caller ID via INCALL_CALL_ADDED
                // InCallService gets the proper caller ID from call.getDetails().getHandle()
                String phoneNumber = connection.getPhoneNumber();
                if (phoneNumber != null && !phoneNumber.isEmpty() && !phoneNumber.equals("Unknown")) {
                    // Caller ID is already available (rare), proceed immediately
                    Log.i(TAG, "Caller ID available from ConnectionService, proceeding");
                    handleIncomingGSMCall(simSlot, phoneNumber);
                } else {
                    // Wait for InCallService to provide caller ID
                    Log.i(TAG, "Waiting for InCallService to provide caller ID...");
                    // Silence ringer immediately while we wait
                    silenceRinger();
                }
            }

            @Override
            public void onOutgoingCall(GatewayConnection connection) {
                Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
                Log.i(TAG, "║ ConnectionService: OUTGOING GSM CALL                       ║");
                Log.i(TAG, "║ We placed this call - we control the audio!                ║");
                Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
                Log.i(TAG, "Number: " + connection.getPhoneNumber() + ", SIM: " + connection.getSimSlot());

                int simSlot = connection.getSimSlot();
                activeGatewayConnections.put(simSlot, connection);

                // Update session state if we have one (PBX-initiated call)
                synchronized (sessionLock) {
                    CallSession session = activeSessions.get(simSlot);
                    if (session != null) {
                        session.setState(CallSession.CallState.GSM_RINGING);
                        Log.i(TAG, "Updated session to GSM_RINGING");
                    }
                }
            }

            @Override
            public void onCallAnswered(GatewayConnection connection) {
                Log.i(TAG, "ConnectionService: Call answered - " + connection.getPhoneNumber());
                int simSlot = connection.getSimSlot();
                handleGSMCallAnswered(simSlot);
            }

            @Override
            public void onCallEnded(GatewayConnection connection, DisconnectCause cause) {
                Log.i(TAG, "ConnectionService: Call ended - " + connection.getPhoneNumber() +
                          ", cause: " + cause.getCode());
                int simSlot = connection.getSimSlot();
                activeGatewayConnections.remove(simSlot);
                handleGSMCallEnded(simSlot);
            }
        });

        // Acquire wakelock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GatewayService::WakeLock"
        );
        wakeLock.acquire();

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
            // Legacy CallReceiver events (may have permission issues on Android 10+)
            // NOTE: CallReceiver often has "Unknown" caller ID on Android 10+
            // We only use this to silence the ringer, NOT to create the session
            // The session is created from INCALL_CALL_ADDED which has proper caller ID
            case "INCOMING_GSM_CALL":
                String incomingNumber = intent.getStringExtra("number");
                Log.i(TAG, "Incoming GSM call on SIM" + simSlot + " from: " + incomingNumber);
                // Silence ringer and initiate SIP call immediately
                // Don't wait for InCallService which may not be triggered
                silenceRinger();
                synchronized (sessionLock) {
                    if (!activeSessions.containsKey(simSlot)) {
                        handleIncomingGSMCall(simSlot, incomingNumber);
                    }
                }
                break;

            case "GSM_CALL_ANSWERED":
                handleGSMCallAnswered(simSlot);
                break;

            case "GSM_CALL_ENDED":
                handleGSMCallEnded(simSlot);
                break;

            case "UPDATE_CALLER_ID":
                String updatedNumber = intent.getStringExtra("number");
                handleCallerIdUpdate(simSlot, updatedNumber);
                break;

            // InCallService events (more reliable, gets caller ID properly)
            case "INCALL_CALL_ADDED":
                String incallNumber = intent.getStringExtra("phone_number");
                int direction = intent.getIntExtra("direction", -1); // -1 = UNKNOWN
                Log.i(TAG, "InCallService: Call added, number=" + incallNumber + ", direction=" + direction);
                // For incoming GSM calls, use InCallService's caller ID (THIS IS THE RELIABLE SOURCE)
                // DIRECTION_UNKNOWN = -1, DIRECTION_INCOMING = 0, DIRECTION_OUTGOING = 1
                // If direction is UNKNOWN (-1) or INCOMING (0), and no session exists, treat as incoming
                if (direction != 1) { // Not explicitly OUTGOING
                    synchronized (sessionLock) {
                        if (!activeSessions.containsKey(simSlot)) {
                            // No session yet - this is where we should initiate the call
                            // InCallService provides the REAL caller ID
                            Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
                            Log.i(TAG, "║ InCallService: Got caller ID - initiating SIP INVITE       ║");
                            Log.i(TAG, "║ Caller: " + String.format("%-50s", incallNumber) + " ║");
                            Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
                            handleIncomingGSMCall(simSlot, incallNumber);
                        } else {
                            // Session already exists - update caller ID if it was Unknown
                            CallSession session = activeSessions.get(simSlot);
                            if (session != null && "Unknown".equals(session.getCallerNumber())) {
                                Log.i(TAG, "Updating caller ID from Unknown to: " + incallNumber);
                                // Update the session's caller number
                                session.updateCallerNumber(incallNumber);
                            }
                        }
                    }
                }
                break;

            case "INCALL_CALL_ACTIVE":
                Log.i(TAG, "InCallService: Call active on SIM" + simSlot);
                handleGSMCallAnswered(simSlot);
                break;

            case "INCALL_CALL_REMOVED":
            case "INCALL_CALL_DISCONNECTED":
                Log.i(TAG, "InCallService: Call ended on SIM" + simSlot);
                handleGSMCallEnded(simSlot);
                break;

            case "INCALL_NUMBER_UPDATED":
                String newNumber = intent.getStringExtra("phone_number");
                Log.i(TAG, "InCallService: Number updated to " + newNumber);
                handleCallerIdUpdate(simSlot, newNumber);
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

        synchronized (sessionLock) {
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

            // Start call setup timeout
            startCallSetupTimer(simSlot);
        }

        updateNotification("Outgoing SIM" + simSlot + ": " + dialedNumber);

        // Place GSM call (outside lock - may take time)
        initiateGSMCall(simSlot, dialedNumber);
    }

    /**
     * Initiate outgoing GSM call
     * Uses our ConnectionService to place the call, giving us audio control
     */
    private void initiateGSMCall(int simSlot, String number) {
        try {
            // Use our ConnectionService's placeCall - this makes us the dialer
            // and gives us control over the audio routing
            Log.i(TAG, "Placing GSM call via GatewayConnectionService to " + number + " on SIM" + simSlot);
            GatewayConnectionService.placeCall(this, number, simSlot);

            synchronized (sessionLock) {
                CallSession session = activeSessions.get(simSlot);
                if (session != null) {
                    session.setState(CallSession.CallState.GSM_RINGING);
                }
            }

            Log.i(TAG, "Call placed via ConnectionService");

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
        synchronized (sessionLock) {
            CallSession session = activeSessions.get(simSlot);
            if (session == null) {
                Log.w(TAG, "No active session for SIM" + simSlot);
                return;
            }

            // Prevent duplicate handling
            if (session.isGsmAnswered()) {
                Log.d(TAG, "GSM already answered for SIM" + simSlot);
                return;
            }

            if (session.isEnding()) {
                Log.d(TAG, "Call is ending, ignoring GSM answer for SIM" + simSlot);
                return;
            }

            Log.i(TAG, "GSM call answered on SIM" + simSlot);
            session.setGsmAnswered(true);
            session.setState(CallSession.CallState.GSM_ANSWERED);

            // For OUTGOING_GSM: GSM answered, now answer the SIP call
            if (session.isOutgoingGSM() && !session.isSipAnswered()) {
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
            if (session.canStartRTP()) {
                startRTPBridgeInternal(simSlot, session);
            }
        }
    }

    // ==================== INCOMING GSM CALL FLOW ====================
    // GSM rings -> We send INVITE to PBX -> PBX answers -> We answer GSM

    /**
     * Handle incoming GSM call
     */
    private void handleIncomingGSMCall(int simSlot, String callerNumber) {
        // Handle null/empty caller number - use "Unknown" as fallback
        if (callerNumber == null || callerNumber.isEmpty()) {
            Log.w(TAG, "Caller number is null/empty - might be restricted or permission issue");
            callerNumber = "Unknown";
        }

        Log.i(TAG, "Incoming GSM call on SIM" + simSlot + " from: " + callerNumber);

        // IMMEDIATELY silence the ringer to prevent phone from ringing/vibrating
        silenceRinger();

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

        SIPClient.SIPCall sipCall;
        synchronized (sessionLock) {
            // Check if SIM is busy
            if (activeSessions.containsKey(simSlot)) {
                Log.w(TAG, "SIM" + simSlot + " is busy");
                return;
            }

            // Configure audio for gateway mode
            configureGatewayAudio();

            // Create session
            CallSession session = new CallSession(simSlot, callerNumber, CallSession.CallDirection.INCOMING_GSM);
            session.setState(CallSession.CallState.GSM_RINGING);
            activeSessions.put(simSlot, session);

            updateNotification("Incoming SIM" + simSlot + ": " + callerNumber);

            // Send SIP INVITE to PBX
            // The extension to dial is the SIP username (e.g., "gsm1" - routes via PBX dialplan)
            // The caller ID is the actual GSM caller's phone number
            int rtpPort = Config.getRTPPort(simSlot);
            String extensionToDial = config.getSIPUsername(simSlot); // e.g., "gsm1"
            sipCall = client.makeCall(extensionToDial, rtpPort, callerNumber);

            if (sipCall == null) {
                Log.e(TAG, "Failed to create SIP call");
                activeSessions.remove(simSlot);
                return;
            }

            session.setSipCallId(sipCall.callId);
            session.setState(CallSession.CallState.SIP_DIALING);
            callIdToSimSlot.put(sipCall.callId, simSlot);

            // Start call setup timeout
            startCallSetupTimer(simSlot);
        }

        Log.i(TAG, "Sent INVITE to PBX for incoming GSM call with caller ID: " + callerNumber);
    }

    /**
     * SIP call was answered by PBX - now answer the GSM call
     */
    private void handleSIPCallAnswered(int simSlot, SIPClient.SIPCall sipCall) {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ handleSIPCallAnswered() CALLED                             ║");
        Log.i(TAG, "║ SIM: " + String.format("%-56s", "SIM" + simSlot) + " ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
        
        boolean shouldAnswerGSM = false;

        synchronized (sessionLock) {
            CallSession session = activeSessions.get(simSlot);
            if (session == null) {
                Log.w(TAG, "❌ No session for SIP call answer on SIM" + simSlot);
                return;
            }

            // Prevent duplicate handling
            if (session.isSipAnswered()) {
                Log.d(TAG, "SIP already answered for SIM" + simSlot);
                return;
            }

            if (session.isEnding()) {
                Log.d(TAG, "Call is ending, ignoring SIP answer for SIM" + simSlot);
                return;
            }

            Log.i(TAG, "✓ SIP call answered on SIM" + simSlot);
            session.setSipAnswered(true);
            session.setRemoteRtpAddress(sipCall.remoteRtpAddress);
            session.setRemoteRtpPort(sipCall.remoteRtpPort);
            session.setState(CallSession.CallState.SIP_ANSWERED);

            // For INCOMING_GSM: PBX answered, need to answer the GSM call
            if (session.isIncomingGSM() && !session.isGsmAnswered()) {
                Log.i(TAG, "✓ This is INCOMING_GSM and GSM not yet answered");
                Log.i(TAG, "✓ Setting shouldAnswerGSM = true");
                shouldAnswerGSM = true;
            } else {
                Log.w(TAG, "⚠️  Not answering GSM:");
                Log.w(TAG, "   isIncomingGSM=" + session.isIncomingGSM());
                Log.w(TAG, "   isGsmAnswered=" + session.isGsmAnswered());
            }

            // Start RTP bridge if both sides are ready
            if (session.canStartRTP()) {
                startRTPBridgeInternal(simSlot, session);
            }
        }

        // Answer GSM outside the lock (may take time)
        if (shouldAnswerGSM) {
            Log.i(TAG, "🔔 Calling answerGSMCall(" + simSlot + ")...");
            answerGSMCall(simSlot);
        } else {
            Log.w(TAG, "⚠️  NOT calling answerGSMCall (shouldAnswerGSM=false)");
        }
    }

    /**
     * Answer the ringing GSM call using ITelephony.answerRingingCall().
     * This answers at the telephony layer (real GSM/IMS call), not just our wrapper.
     * Requires hidden_api_policy=1 (set via Magisk service.sh).
     */
    private void answerGSMCall(int simSlot) {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ answerGSMCall() CALLED - Attempting to answer GSM          ║");
        Log.i(TAG, "║ SIM" + String.format("%-56s", simSlot + " ║"));
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        try {
            boolean answered = false;

            // Method 1: ITelephony.answerRingingCall() - answers the REAL GSM/IMS call
            try {
                Log.i(TAG, "📞 Method 1: Trying ITelephony.answerRingingCall()...");
                Method getITelephony = TelephonyManager.class.getDeclaredMethod("getITelephony");
                getITelephony.setAccessible(true);
                Object iTelephony = getITelephony.invoke(telephonyManager);

                if (iTelephony != null) {
                    // Try answerRingingCall()
                    try {
                        Method answerMethod = iTelephony.getClass().getMethod("answerRingingCall");
                        Log.i(TAG, "   Calling ITelephony.answerRingingCall()");
                        answerMethod.invoke(iTelephony);
                        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
                        Log.i(TAG, "║ ✓ ITelephony.answerRingingCall() SUCCEEDED                ║");
                        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
                        answered = true;
                    } catch (NoSuchMethodException e) {
                        Log.d(TAG, "answerRingingCall() not found, trying alternatives");
                    }

                    // Try answerRingingCallForSubscription(int subId)
                    if (!answered) {
                        try {
                            Method answerMethod = iTelephony.getClass().getMethod(
                                "answerRingingCallForSubscription", int.class);
                            // Get subscription ID for this SIM slot
                            android.telephony.SubscriptionManager subMgr =
                                (android.telephony.SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
                            int subId = android.telephony.SubscriptionManager.getDefaultSubscriptionId();
                            Log.i(TAG, "Calling ITelephony.answerRingingCallForSubscription(" + subId + ")");
                            answerMethod.invoke(iTelephony, subId);
                            Log.i(TAG, ">>> ITelephony.answerRingingCallForSubscription() SUCCEEDED <<<");
                            answered = true;
                        } catch (NoSuchMethodException e) {
                            Log.d(TAG, "answerRingingCallForSubscription() not found");
                        }
                    }
                } else {
                    Log.w(TAG, "ITelephony is null - hidden_api_policy may not be set");
                }
            } catch (Exception e) {
                Log.w(TAG, "ITelephony answer failed: " + e.getMessage());
            }

            // Method 2: InCallService Call.answer()
            if (!answered) {
                GatewayInCallService inCallService = GatewayInCallService.getInstance();
                if (inCallService != null) {
                    Log.i(TAG, "Fallback: InCallService.answerCall()");
                    answered = inCallService.answerCall(simSlot);
                    if (answered) Log.i(TAG, "InCallService.answerCall() succeeded");
                }
            }

            // Method 3: TelecomManager.acceptRingingCall()
            if (!answered) {
                Log.i(TAG, "Fallback: TelecomManager.acceptRingingCall()");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.acceptRingingCall();
                    answered = true;
                    Log.i(TAG, "TelecomManager.acceptRingingCall() called");
                }
            }

            if (answered) {
                Log.i(TAG, "GSM call answer initiated on SIM" + simSlot);
                // Mark as answered - the INCALL_CALL_ACTIVE callback will confirm
                synchronized (sessionLock) {
                    CallSession session = activeSessions.get(simSlot);
                    if (session != null && !session.isGsmAnswered()) {
                        session.setGsmAnswered(true);
                        session.setState(CallSession.CallState.GSM_ANSWERED);
                        if (session.canStartRTP()) {
                            startRTPBridgeInternal(simSlot, session);
                        }
                    }
                }
            } else {
                Log.e(TAG, "All answer methods failed for SIM" + simSlot);
            }

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
     * Start audio bridge - public entry point (acquires lock)
     */
    private void startRTPBridge(int simSlot) {
        synchronized (sessionLock) {
            CallSession session = activeSessions.get(simSlot);
            if (session != null && session.canStartRTP()) {
                startRTPBridgeInternal(simSlot, session);
            }
        }
    }

    /**
     * Start audio bridge with native PCM routing
     * MUST be called while holding sessionLock
     */
    private void startRTPBridgeInternal(int simSlot, CallSession session) {
        // Caller must hold sessionLock

        if (session == null) {
            Log.w(TAG, "No session to bridge for SIM" + simSlot);
            return;
        }

        // Check if RTP is already active
        if (session.isRtpActive()) {
            Log.d(TAG, "RTP bridge already active for SIM" + simSlot);
            return;
        }

        // Check if session is ending
        if (session.isEnding()) {
            Log.d(TAG, "Session ending, not starting RTP for SIM" + simSlot);
            return;
        }

        // Double-check both sides are answered
        if (!session.canStartRTP()) {
            Log.d(TAG, "Cannot start RTP - GSM=" + session.isGsmAnswered() +
                       ", SIP=" + session.isSipAnswered());
            return;
        }

        // Check if the audio bridge is already running
        NativePCMAudioBridge audioBridge = audioBridges.get(simSlot);
        if (audioBridge != null && audioBridge.isRunning()) {
            Log.d(TAG, "Audio bridge already running for SIM" + simSlot);
            session.setRtpActive(true);
            return;
        }

        // Validate RTP endpoint info
        String remoteAddr = session.getRemoteRtpAddress();
        int remotePort = session.getRemoteRtpPort();

        if (remoteAddr == null || remoteAddr.isEmpty() || remotePort == 0) {
            Log.e(TAG, "Invalid remote RTP endpoint: " + remoteAddr + ":" + remotePort);
            Log.e(TAG, "Session state: " + session.toDetailedString());
            return;
        }

        Log.i(TAG, "Starting System Audio Bridge for SIM" + simSlot);
        Log.i(TAG, "Using VOICE_CALL/VOICE_DOWNLINK audio source");
        Log.i(TAG, "Call direction: " + session.getDirection());
        Log.i(TAG, "Remote RTP endpoint: " + remoteAddr + ":" + remotePort);
        Log.i(TAG, "Local RTP port: " + Config.getRTPPort(simSlot));

        // Mark RTP as active BEFORE starting to prevent race conditions
        session.setRtpActive(true);

        // Cancel setup timer since we're now bridged
        cancelCallSetupTimer(simSlot);

        // Start RootAudioRouter FIRST to configure mixer paths
        // This sets up:
        // 1. Voice call capture path (GSM party → our app via VOICE_CALL source)
        // 2. Voice call injection path (RTP audio → GSM modem via Incall_Music)
        // 3. Mutes phone speaker and mic
        RootAudioRouter audioRouter = audioRouters.get(simSlot);
        if (audioRouter != null) {
            Log.i(TAG, "Starting RootAudioRouter for SIM" + simSlot);
            if (!audioRouter.start()) {
                Log.w(TAG, "RootAudioRouter failed to start - audio may not work correctly");
                // Continue anyway - capture might still work with CAPTURE_AUDIO_OUTPUT
            }
        }

        // Configure and start Native PCM Audio Bridge
        audioBridge.setRemoteAddress(remoteAddr, remotePort);

        if (!audioBridge.start()) {
            Log.e(TAG, "Failed to start audio bridge for SIM" + simSlot);
            Log.e(TAG, "Check CAPTURE_AUDIO_OUTPUT permission.");
            session.setRtpActive(false);
            // Stop audio router
            if (audioRouter != null) {
                audioRouter.stop();
            }
            // End call - release lock first to avoid deadlock
            final int slot = simSlot;
            scheduler.execute(() -> endCallInternal(slot, "AUDIO_FAILED"));
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
        endCallInternal(simSlot, "GSM_HANGUP");
    }

    /**
     * Handle caller ID update from CallReceiver
     * Called when caller ID becomes available from call log after initial broadcast
     */
    private void handleCallerIdUpdate(int simSlot, String number) {
        Log.i(TAG, "Caller ID update for SIM" + simSlot + ": " + number);

        synchronized (sessionLock) {
            CallSession session = activeSessions.get(simSlot);
            if (session != null && session.getCallerNumber() != null &&
                (session.getCallerNumber().equals("Unknown") || session.getCallerNumber().isEmpty())) {
                // Update session - but CallSession has immutable callerNumber
                // For now, just log it. In a future update, we could make caller number mutable
                // or recreate the session
                Log.i(TAG, "Session found but caller number is immutable, new number: " + number);

                // If we haven't sent INVITE yet, we could update
                // For now, at least log the real number for debugging
            }
        }
    }

    /**
     * Handle SIP call ended (BYE received)
     */
    private void handleSIPCallEnded(int simSlot, SIPClient.SIPCall sipCall) {
        Log.i(TAG, "SIP call ended on SIM" + simSlot);
        endCallInternal(simSlot, "SIP_HANGUP");
    }

    /**
     * End a complete call session - public entry point
     */
    private void endCall(int simSlot) {
        endCallInternal(simSlot, "NORMAL");
    }

    /**
     * End a complete call session - internal synchronized implementation
     * Prevents double-hangup and race conditions
     *
     * @param simSlot SIM slot number
     * @param reason  Reason code: "GSM_HANGUP", "SIP_HANGUP", "SETUP_TIMEOUT", "AUDIO_FAILED", "NORMAL"
     */
    private void endCallInternal(int simSlot, String reason) {
        CallSession session;
        String sipCallId;
        boolean shouldEndGSM;
        boolean shouldEndSIP;

        synchronized (sessionLock) {
            session = activeSessions.get(simSlot);
            if (session == null) {
                Log.d(TAG, "No session to end for SIM" + simSlot);
                return;
            }

            // Check if already ending
            if (session.isEnding()) {
                Log.d(TAG, "Call already ending for SIM" + simSlot);
                return;
            }

            // Force to ending state
            session.forceEnd(reason);
            Log.i(TAG, "Ending call session: " + session + " reason: " + reason);

            // Cancel setup timer
            cancelCallSetupTimer(simSlot);

            // Stop Native PCM Audio Bridge
            NativePCMAudioBridge audioBridge = audioBridges.get(simSlot);
            if (audioBridge != null && audioBridge.isRunning()) {
                audioBridge.stop();
            }

            // Stop RootAudioRouter (restores normal audio paths)
            RootAudioRouter audioRouter = audioRouters.get(simSlot);
            if (audioRouter != null) {
                audioRouter.stop();
            }

            // Determine what needs cleanup
            sipCallId = session.getSipCallId();
            shouldEndGSM = !"GSM_HANGUP".equals(reason);  // Don't end GSM if GSM initiated hangup
            shouldEndSIP = !"SIP_HANGUP".equals(reason) && sipCallId != null;  // Don't send BYE if SIP sent BYE

            // Remove from maps
            if (sipCallId != null) {
                callIdToSimSlot.remove(sipCallId);
            }
            activeSessions.remove(simSlot);
        }

        // Perform cleanup outside lock (may take time)

        // Hangup SIP call if needed
        if (shouldEndSIP) {
            Log.i(TAG, "Attempting to hangup SIP call: " + sipCallId);
            SIPClient client = sipClients.get(simSlot);
            if (client != null) {
                SIPClient.SIPCall sipCall = client.getCall(sipCallId);
                if (sipCall != null) {
                    Log.i(TAG, "Found SIP call, sending hangup. State: " + sipCall.state + ", isIncoming: " + sipCall.isIncoming);
                    client.hangup(sipCall);
                } else {
                    Log.w(TAG, "SIP call not found in client's active calls: " + sipCallId);
                    Log.w(TAG, "Active SIP calls: " + client.getActiveCalls().keySet());
                }
            } else {
                Log.w(TAG, "SIP client not found for SIM" + simSlot);
            }
        } else {
            Log.d(TAG, "Not hanging up SIP - reason: " + reason + ", sipCallId: " + sipCallId);
        }

        // End GSM call if needed
        if (shouldEndGSM) {
            endGSMCall(simSlot);
        }

        // Restore ringer and audio state
        restoreRinger();
        restoreNormalAudio();

        // Update notification
        updateNotification(getStatusSummary());
    }

    /**
     * End GSM call
     * Uses GatewayConnection if available (gives us proper cleanup)
     */
    private void endGSMCall(int simSlot) {
        try {
            // First try to end via our GatewayConnection
            GatewayConnection connection = activeGatewayConnections.get(simSlot);
            if (connection != null) {
                Log.i(TAG, "Ending GSM call via GatewayConnection");
                connection.disconnect(new DisconnectCause(DisconnectCause.LOCAL));
                activeGatewayConnections.remove(simSlot);
                return;
            }

            // Fallback to TelecomManager
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
        Log.e(TAG, "Call failed on SIM" + simSlot + ": " + reason);
        endCallInternal(simSlot, "CALL_FAILED: " + reason);
        updateNotification("Call failed: " + reason);
    }

    // ==================== CALL SETUP TIMERS ====================

    /**
     * Start a timer that will end the call if it doesn't complete setup
     */
    private void startCallSetupTimer(int simSlot) {
        cancelCallSetupTimer(simSlot);  // Cancel any existing timer

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            synchronized (sessionLock) {
                CallSession session = activeSessions.get(simSlot);
                if (session != null && !session.isBridged() && !session.isEnding()) {
                    Log.e(TAG, "Call setup timeout on SIM" + simSlot + " - state: " + session.getState());
                    // Release lock before calling endCallInternal
                }
            }
            // Always call endCallInternal - it will check if session still exists
            endCallInternal(simSlot, "SETUP_TIMEOUT");
        }, CALL_SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        callSetupTimers.put(simSlot, timer);
        Log.d(TAG, "Started call setup timer for SIM" + simSlot + " (" + CALL_SETUP_TIMEOUT_MS + "ms)");
    }

    /**
     * Cancel the call setup timer
     */
    private void cancelCallSetupTimer(int simSlot) {
        ScheduledFuture<?> timer = callSetupTimers.remove(simSlot);
        if (timer != null) {
            timer.cancel(false);
            Log.d(TAG, "Cancelled call setup timer for SIM" + simSlot);
        }
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
            boolean hasActiveSessions;
            synchronized (sessionLock) {
                hasActiveSessions = !activeSessions.isEmpty();
            }
            if (!hasActiveSessions) {
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
            // Request audio focus with listener to maintain focus
            int result = audioManager.requestAudioFocus(audioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN);  // Use GAIN instead of TRANSIENT for persistent focus

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "Audio focus granted");
            } else {
                Log.w(TAG, "Audio focus request result: " + result);
            }

            // Set mode to in-call - MODE_IN_CALL is required for VOICE_DOWNLINK capture
            // The audio HAL checks this mode to enable incall-rec usecase
            audioManager.setMode(AudioManager.MODE_IN_CALL);

            // Route to earpiece/speaker based on call
            audioManager.setSpeakerphoneOn(false);

            // Mute the phone's own mic (gateway handles audio via RTP)
            audioManager.setMicrophoneMute(true);

            audioConfigured = true;
            Log.i(TAG, "Audio configured for gateway mode (MODE_IN_CALL)");

        } catch (Exception e) {
            Log.e(TAG, "Failed to configure gateway audio: " + e.getMessage(), e);
        }
    }

    /**
     * Re-acquire audio focus if lost during a call
     */
    private void reacquireAudioFocus() {
        try {
            // Brief delay to let system settle
            Thread.sleep(100);

            int result = audioManager.requestAudioFocus(audioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN);

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "Audio focus re-acquired");

                // Ensure mode is still correct
                if (audioManager.getMode() != AudioManager.MODE_IN_CALL) {
                    audioManager.setMode(AudioManager.MODE_IN_CALL);
                    Log.i(TAG, "Restored audio mode to IN_CALL");
                }
            } else {
                Log.w(TAG, "Failed to re-acquire audio focus: " + result);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error re-acquiring audio focus: " + e.getMessage());
        }
    }

    /**
     * Restore normal audio state
     */
    private void restoreNormalAudio() {
        try {
            // Only restore if no active sessions
            boolean hasActiveSessions;
            synchronized (sessionLock) {
                hasActiveSessions = !activeSessions.isEmpty();
            }
            if (!hasActiveSessions) {
                audioConfigured = false;
                audioManager.abandonAudioFocus(audioFocusListener);
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
        synchronized (sessionLock) {
            if (!activeSessions.isEmpty()) {
                sb.append(" | ");
                for (CallSession session : activeSessions.values()) {
                    sb.append("SIM").append(session.getSimSlot()).append(": ");
                    sb.append(session.isBridged() ? "Active" : "Connecting");
                }
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

        Log.i(TAG, "Gateway service destroying...");

        // Clean up all sessions
        Integer[] slots;
        synchronized (sessionLock) {
            slots = activeSessions.keySet().toArray(new Integer[0]);
        }
        for (int simSlot : slots) {
            endCallInternal(simSlot, "SERVICE_SHUTDOWN");
        }

        // Stop SIP clients
        stopSIPClients();

        // Stop all audio bridges
        for (NativePCMAudioBridge audioBridge : audioBridges.values()) {
            audioBridge.stop();
        }

        // Close persistent root shell
        RootAudioRouter.closePersistentShell();

        // Shutdown scheduler
        if (scheduler != null) {
            scheduler.shutdownNow();
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
