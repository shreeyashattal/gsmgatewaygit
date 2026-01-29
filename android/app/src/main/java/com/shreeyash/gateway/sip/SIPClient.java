package com.shreeyash.gateway.sip;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SIP Client - User Agent for direct PBX registration
 * Handles SIP signaling for GSM-SIP gateway
 *
 * Supports two modes:
 * 1. Registration mode: Registers with external PBX (Asterisk, FreeSWITCH, etc.)
 * 2. Trunk mode: Acts as SIP server, PBX registers with us
 */
public class SIPClient {
    private static final String TAG = "SIPClient";

    // Enable detailed SIP message logging
    private static final boolean LOG_SIP_MESSAGES = true;
    private static final boolean LOG_SIP_HEADERS = true;

    // Configuration
    private final String pbxHost;
    private final int pbxPort;
    private final String username;
    private final String password;
    private final String localIp;
    private final int localSipPort;

    // SIP state
    private DatagramSocket sipSocket;
    private volatile boolean running = false;
    private volatile boolean registered = false;
    private int cseq = 1;
    private String registerCallId;

    // Active calls: callId -> SIPCall
    private Map<String, SIPCall> activeCalls = new ConcurrentHashMap<>();

    // Executors
    private ExecutorService executor = Executors.newCachedThreadPool();
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Thread receiverThread;

    // Event listener
    private SIPEventListener eventListener;

    // Authentication state
    private String lastNonce;
    private String lastRealm;

    // Transaction timeouts (RFC 3261)
    private static final int INVITE_TIMEOUT_MS = 32000;  // Timer B
    private static final int BYE_TIMEOUT_MS = 5000;
    private static final int CANCEL_TIMEOUT_MS = 5000;
    private Map<String, ScheduledFuture<?>> transactionTimers = new ConcurrentHashMap<>();

    // Retransmission intervals (RFC 3261 Timer A)
    private static final long[] RETRANSMIT_INTERVALS = {500, 1000, 2000, 4000, 4000, 4000};
    private static final int MAX_RETRANSMITS = 6;

    // Health monitoring
    private volatile long lastReceivedTime = System.currentTimeMillis();
    private static final long HEALTH_CHECK_INTERVAL_MS = 30000;
    private static final long DEAD_THRESHOLD_MS = 90000;

    /**
     * SIP call state
     */
    public static class SIPCall {
        public String callId;
        public String fromTag;
        public String toTag;
        public String remoteUri;
        public String localUri;
        public String fromHeader;
        public String toHeader;
        public String viaHeader;  // Original Via for responses
        public int cseq;
        public String remoteRtpAddress;
        public int remoteRtpPort;
        public int localRtpPort;
        public boolean isIncoming; // Incoming INVITE = outgoing GSM call
        public CallState state = CallState.IDLE;
        // For trunk mode - store sender address
        public InetAddress senderAddress;
        public int senderPort;

        public enum CallState {
            IDLE,           // Initial state
            RINGING,        // 180 Ringing received/sent
            EARLY_MEDIA,    // 183 Session Progress with SDP
            ANSWERED,       // 200 OK received/sent
            CONFIRMED,      // ACK received/sent
            TERMINATED      // Call ended
        }

        // Store original INVITE for CANCEL support
        public SIPMessage originalInvite;
    }

    /**
     * Event listener interface
     */
    public interface SIPEventListener {
        void onRegistered();
        void onRegistrationFailed(String reason);
        void onIncomingCall(SIPCall call, String dialedNumber);
        void onCallAnswered(SIPCall call);
        void onCallEnded(SIPCall call);

        // Extended callbacks for production reliability (default implementations for backwards compatibility)
        default void onEarlyMedia(SIPCall call) {}
        default void onCallProgress(SIPCall call, int statusCode) {}
        default void onCallFailed(SIPCall call, int statusCode, String reason) {}
    }

    // Trunk mode (no registration, just listen)
    private boolean trunkMode = false;

    // Learned PBX address (from incoming REGISTERs in trunk mode)
    private InetAddress learnedPbxAddress;
    private int learnedPbxPort;

    public SIPClient(String pbxHost, int pbxPort, String username, String password,
                     String localIp, int localSipPort) {
        this.pbxHost = pbxHost;
        this.pbxPort = pbxPort;
        this.username = username;
        this.password = password;
        this.localIp = localIp;
        this.localSipPort = localSipPort;

        // If no PBX host, run in trunk/listen mode
        if (pbxHost == null || pbxHost.isEmpty()) {
            this.trunkMode = true;
            this.registerCallId = SIPMessage.generateCallId(localIp);
            Log.i(TAG, "Running in trunk mode (listen only, no registration)");
        } else {
            this.registerCallId = SIPMessage.generateCallId(pbxHost);
        }
    }

    public void setEventListener(SIPEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Start the SIP client
     */
    public boolean start() {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║              STARTING SIP CLIENT                           ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        try {
            // Bind to IPv4 explicitly (0.0.0.0) to ensure we can receive from IPv4 PBX
            // Without this, Java/Android defaults to IPv6 if available
            sipSocket = new DatagramSocket(localSipPort, InetAddress.getByName("0.0.0.0"));
            sipSocket.setSoTimeout(5000);  // 5 second timeout for receive
            running = true;

            Log.i(TAG, "┌─ SIP Configuration:");
            Log.i(TAG, "│  Local IP:      " + localIp);
            Log.i(TAG, "│  Local Port:    " + localSipPort);
            Log.i(TAG, "│  Mode:          " + (trunkMode ? "TRUNK (acting as SIP server)" : "REGISTRATION (client to PBX)"));
            if (!trunkMode) {
                Log.i(TAG, "│  PBX Host:      " + pbxHost);
                Log.i(TAG, "│  PBX Port:      " + pbxPort);
                Log.i(TAG, "│  Username:      " + username);
            }
            Log.i(TAG, "└─────────────────────────────");

            // Start receiver thread
            receiverThread = new Thread(this::receiveLoop, "SIP-Receiver");
            receiverThread.start();
            Log.i(TAG, "[SIP] ✓ Receiver thread started");

            // Start health monitor
            startHealthMonitor();
            Log.i(TAG, "[SIP] ✓ Health monitor started");

            if (trunkMode) {
                // In trunk mode, just listen - no registration needed
                Log.i(TAG, "[SIP] Running in TRUNK MODE - waiting for PBX to register with us...");
                // Mark as "registered" for compatibility (trunk mode is always ready)
                registered = true;
                if (eventListener != null) {
                    eventListener.onRegistered();
                }
            } else {
                // Normal mode - register with PBX
                Log.i(TAG, "[SIP] Sending REGISTER to " + pbxHost + ":" + pbxPort);

                // Initial registration
                register();

                // Schedule periodic re-registration (every 50 seconds for 60s expiry)
                scheduler.scheduleAtFixedRate(this::register, 50, 50, TimeUnit.SECONDS);
            }

            Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
            Log.i(TAG, "║           ✓ SIP CLIENT STARTED SUCCESSFULLY                ║");
            Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ FATAL: Failed to start SIP client: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Log SIP message for debugging
     */
    private void logSipMessage(String direction, String message, String remoteAddr, int remotePort) {
        if (!LOG_SIP_MESSAGES) return;

        String[] lines = message.split("\r\n");
        String firstLine = lines.length > 0 ? lines[0] : "EMPTY";

        // Determine if request or response
        boolean isRequest = !firstLine.startsWith("SIP/");

        String arrow = direction.equals("TX") ? ">>>" : "<<<";
        String icon = direction.equals("TX") ? "📤" : "📥";

        Log.i(TAG, String.format("[SIP] %s %s %s %s:%d",
            icon, arrow, firstLine, remoteAddr, remotePort));

        if (LOG_SIP_HEADERS) {
            for (int i = 1; i < lines.length && !lines[i].isEmpty(); i++) {
                String line = lines[i];
                // Log important headers
                if (line.toLowerCase().startsWith("from:") ||
                    line.toLowerCase().startsWith("to:") ||
                    line.toLowerCase().startsWith("call-id:") ||
                    line.toLowerCase().startsWith("cseq:") ||
                    line.toLowerCase().startsWith("contact:")) {
                    Log.d(TAG, "[SIP]     " + line);
                }
            }
        }
    }

    /**
     * Start health monitoring for connection status
     */
    private void startHealthMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            long silenceMs = System.currentTimeMillis() - lastReceivedTime;
            if (silenceMs > DEAD_THRESHOLD_MS && !activeCalls.isEmpty()) {
                Log.w(TAG, "No SIP traffic for " + silenceMs + "ms with active calls - possible connection issue");
                // In registration mode, try re-registering
                if (!trunkMode && registered) {
                    Log.i(TAG, "Attempting re-registration due to silence");
                    register();
                }
            }
        }, HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the SIP client
     */
    public void stop() {
        running = false;

        // Unregister
        if (registered) {
            try {
                sendRegister(0); // Expires=0 means unregister
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering", e);
            }
        }

        // Hangup all active calls
        for (SIPCall call : activeCalls.values()) {
            hangup(call);
        }

        scheduler.shutdown();
        executor.shutdown();

        if (receiverThread != null) {
            receiverThread.interrupt();
        }

        if (sipSocket != null && !sipSocket.isClosed()) {
            sipSocket.close();
        }

        Log.i(TAG, "SIP client stopped");
    }

    /**
     * Register with PBX
     */
    private void register() {
        executor.execute(() -> {
            try {
                sendRegister(60); // 60 second registration
            } catch (Exception e) {
                Log.e(TAG, "Registration failed: " + e.getMessage(), e);
                if (eventListener != null) {
                    eventListener.onRegistrationFailed(e.getMessage());
                }
            }
        });
    }

    /**
     * Send REGISTER request
     */
    private void sendRegister(int expires) throws Exception {
        SIPMessage register = SIPMessage.createRegister(
            username, pbxHost, localIp, localSipPort, registerCallId, cseq++, expires);

        // Add authentication if we have credentials from a previous 401
        if (lastNonce != null && lastRealm != null) {
            String authHeader = createDigestAuth("REGISTER", "sip:" + pbxHost);
            register.setHeader("authorization", authHeader);
        }

        sendMessage(register);
        Log.i(TAG, "Sent REGISTER (expires=" + expires + ")");
    }

    /**
     * Make an outgoing call (for incoming GSM calls)
     * @param toExtension The PBX extension to dial
     * @param localRtpPort The local RTP port for audio
     * @param callerNumber The GSM caller's phone number (for caller ID)
     */
    public SIPCall makeCall(String toExtension, int localRtpPort, String callerNumber) {
        // Determine target host (configured PBX or learned from REGISTER)
        String targetHost = pbxHost;
        int targetPort = pbxPort;

        if (trunkMode) {
            if (learnedPbxAddress != null) {
                targetHost = learnedPbxAddress.getHostAddress();
                targetPort = learnedPbxPort;
                Log.i(TAG, "Using learned PBX address: " + targetHost + ":" + targetPort);
            } else {
                Log.e(TAG, "Cannot make call - no PBX address learned yet");
                return null;
            }
        }

        final String destHost = targetHost;
        final int destPort = targetPort;
        // Use caller number for caller ID, fallback to username if not provided
        final String displayCallerId = (callerNumber != null && !callerNumber.isEmpty()) ? callerNumber : username;

        SIPCall call = new SIPCall();
        call.callId = SIPMessage.generateCallId(destHost);
        call.fromTag = SIPMessage.generateTag();
        call.localRtpPort = localRtpPort;
        call.isIncoming = false;
        call.cseq = 1;
        call.state = SIPCall.CallState.RINGING;

        activeCalls.put(call.callId, call);

        executor.execute(() -> {
            try {
                SIPMessage invite = SIPMessage.createInvite(
                    displayCallerId, username, toExtension, destHost, localIp, localSipPort,
                    localRtpPort, call.callId, call.cseq++);

                call.fromHeader = invite.getHeader("from");
                call.toHeader = invite.getHeader("to");
                call.remoteUri = "sip:" + toExtension + "@" + destHost;
                call.originalInvite = invite;  // Store for CANCEL support

                // Send to target with retransmission
                InetAddress destAddr = trunkMode ? learnedPbxAddress : InetAddress.getByName(destHost);
                call.senderAddress = destAddr;
                call.senderPort = destPort;

                sendWithRetransmit(invite, call, destAddr, destPort, MAX_RETRANSMITS);

                // Start INVITE timeout timer
                startInviteTimer(call);

                Log.i(TAG, "Sent INVITE to " + toExtension + " with caller ID: " + displayCallerId + " via " + destHost + ":" + destPort);

            } catch (Exception e) {
                Log.e(TAG, "Failed to send INVITE: " + e.getMessage(), e);
                activeCalls.remove(call.callId);
            }
        });

        return call;
    }

    /**
     * Make an outgoing call (legacy method without caller ID)
     */
    public SIPCall makeCall(String toExtension, int localRtpPort) {
        return makeCall(toExtension, localRtpPort, null);
    }

    /**
     * Check if PBX address is known (either configured or learned)
     */
    public boolean hasPbxAddress() {
        return pbxHost != null || learnedPbxAddress != null;
    }

    /**
     * Answer an incoming call
     */
    public void answerCall(SIPCall call, int localRtpPort) {
        call.localRtpPort = localRtpPort;

        executor.execute(() -> {
            try {
                // Build 200 OK response with SDP
                call.toTag = SIPMessage.generateTag();

                // IMPORTANT: Update call.toHeader to include our tag for later BYE
                // This is critical - BYE uses call.toHeader as From when we're the callee
                if (!call.toHeader.contains("tag=")) {
                    call.toHeader = call.toHeader + ";tag=" + call.toTag;
                    Log.d(TAG, "[SIP] Updated toHeader with our tag: " + call.toHeader);
                }

                StringBuilder sb = new StringBuilder();
                sb.append("SIP/2.0 200 OK\r\n");
                // Use original Via header from INVITE
                if (call.viaHeader != null) {
                    sb.append("Via: ").append(call.viaHeader).append("\r\n");
                }
                sb.append("From: ").append(call.fromHeader).append("\r\n");
                sb.append("To: ").append(call.toHeader).append("\r\n");
                sb.append("Call-ID: ").append(call.callId).append("\r\n");
                sb.append("CSeq: ").append(call.cseq).append(" INVITE\r\n");
                sb.append("Contact: <sip:").append(username).append("@").append(localIp)
                  .append(":").append(localSipPort).append(">\r\n");
                sb.append("User-Agent: GSM-Gateway/1.0\r\n");
                sb.append("Allow: INVITE,ACK,BYE,CANCEL,OPTIONS\r\n");

                String sdp = createSDP(localRtpPort);
                sb.append("Content-Type: application/sdp\r\n");
                sb.append("Content-Length: ").append(sdp.length()).append("\r\n");
                sb.append("\r\n");
                sb.append(sdp);

                // Send to the correct destination (trunk mode support)
                String data = sb.toString();
                InetAddress destAddr;
                int destPort;

                if (call.senderAddress != null) {
                    destAddr = call.senderAddress;
                    destPort = call.senderPort;
                } else if (pbxHost != null) {
                    destAddr = InetAddress.getByName(pbxHost);
                    destPort = pbxPort;
                } else {
                    Log.e(TAG, "Cannot send 200 OK - no destination");
                    return;
                }

                byte[] bytes = data.getBytes();
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length, destAddr, destPort);
                sipSocket.send(packet);

                call.state = SIPCall.CallState.ANSWERED;
                Log.i(TAG, "Sent 200 OK for call " + call.callId + " to " + destAddr.getHostAddress() + ":" + destPort);

            } catch (Exception e) {
                Log.e(TAG, "Failed to answer call: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Hangup a call - sends CANCEL for unanswered calls, BYE for established calls
     */
    public void hangup(SIPCall call) {
        if (call == null || call.state == SIPCall.CallState.TERMINATED) {
            Log.d(TAG, "[SIP] hangup() called on null or terminated call");
            return;
        }

        Log.i(TAG, "┌───────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ [SIP] 📴 HANGUP INITIATED                                 │");
        Log.i(TAG, "│ Call State: " + String.format("%-46s", call.state) + " │");
        Log.i(TAG, "│ Incoming:   " + String.format("%-46s", call.isIncoming) + " │");
        Log.i(TAG, "└───────────────────────────────────────────────────────────┘");

        executor.execute(() -> {
            try {
                // Cancel any pending transaction timers
                cancelTransactionTimer(call.callId);

                // Determine whether to send CANCEL or BYE
                boolean callNotYetAnswered = (call.state == SIPCall.CallState.IDLE ||
                                               call.state == SIPCall.CallState.RINGING ||
                                               call.state == SIPCall.CallState.EARLY_MEDIA);

                if (callNotYetAnswered && !call.isIncoming && call.originalInvite != null) {
                    // Outgoing call not yet answered - send CANCEL
                    Log.i(TAG, "[SIP] Sending CANCEL (call not yet answered)...");
                    SIPMessage cancel = SIPMessage.createCancel(
                        call.callId, call.fromHeader, call.toHeader,
                        call.remoteUri != null ? call.remoteUri : "sip:" + pbxHost,
                        localIp, localSipPort, 1);  // CANCEL uses same CSeq as INVITE

                    // Send to the same destination as the INVITE
                    if (call.senderAddress != null) {
                        sendToAddress(cancel, call.senderAddress, call.senderPort);
                    } else {
                        sendMessage(cancel);
                    }

                    Log.i(TAG, "[SIP] ✓ CANCEL sent for unanswered call");

                } else if (call.state == SIPCall.CallState.ANSWERED ||
                           call.state == SIPCall.CallState.CONFIRMED) {
                    // Established call - send BYE
                    Log.i(TAG, "[SIP] Sending BYE (call was established)...");

                    // For BYE, the sender (us) must be in the From header
                    // For incoming calls, we were the To in the original INVITE, so swap
                    // For outgoing calls, we were the From, so keep as-is
                    String byeFromHeader;
                    String byeToHeader;

                    if (call.isIncoming) {
                        // We received the INVITE, so we're the To in the original dialog
                        // In BYE, we become the From (sender)
                        // NOTE: call.toHeader should already include our tag from answerCall()
                        byeFromHeader = call.toHeader;  // Our identity (was To in INVITE + our tag)
                        byeToHeader = call.fromHeader;  // Remote identity (was From in INVITE + their tag)
                        Log.d(TAG, "[SIP] Swapping From/To for incoming call BYE");
                        Log.d(TAG, "[SIP] Our toTag: " + call.toTag);
                    } else {
                        // We sent the INVITE, so we're already the From
                        byeFromHeader = call.fromHeader;
                        byeToHeader = call.toHeader;
                    }

                    // Log headers for debugging 481 errors
                    Log.i(TAG, "[SIP] BYE Headers:");
                    Log.i(TAG, "[SIP]   From: " + byeFromHeader);
                    Log.i(TAG, "[SIP]   To:   " + byeToHeader);
                    Log.i(TAG, "[SIP]   Call-ID: " + call.callId);

                    // Verify tags are present (critical for 481 debugging)
                    if (!byeFromHeader.contains("tag=")) {
                        Log.w(TAG, "[SIP] ⚠ WARNING: From header missing tag!");
                    }
                    if (!byeToHeader.contains("tag=")) {
                        Log.w(TAG, "[SIP] ⚠ WARNING: To header missing tag!");
                    }

                    SIPMessage bye = SIPMessage.createBye(
                        call.callId, byeFromHeader, byeToHeader,
                        call.remoteUri != null ? call.remoteUri : "sip:" + pbxHost,
                        localIp, localSipPort, call.cseq++);

                    // Send to the correct destination
                    if (call.senderAddress != null) {
                        sendToAddress(bye, call.senderAddress, call.senderPort);
                    } else {
                        sendMessage(bye);
                    }

                    Log.i(TAG, "[SIP] ✓ BYE sent for established call");

                } else if (call.isIncoming && callNotYetAnswered) {
                    // Incoming call we haven't answered - send 486 Busy Here or 603 Decline
                    Log.i(TAG, "[SIP] Sending 603 Decline (incoming call not answered)...");
                    if (call.originalInvite != null) {
                        SIPMessage decline = SIPMessage.createResponse(call.originalInvite, 603, "Decline");
                        sendToCall(call, decline);
                        Log.i(TAG, "[SIP] ✓ 603 Decline sent for incoming call");
                    }
                } else {
                    Log.w(TAG, "[SIP] ⚠ Unhandled hangup case - state=" + call.state + " isIncoming=" + call.isIncoming);
                }

                call.state = SIPCall.CallState.TERMINATED;
                activeCalls.remove(call.callId);
                Log.i(TAG, "[SIP] ✓ Call cleanup complete. Active calls: " + activeCalls.size());

            } catch (Exception e) {
                Log.e(TAG, "[SIP] ❌ Failed to hangup call: " + e.getMessage(), e);
                // Force cleanup even on error
                call.state = SIPCall.CallState.TERMINATED;
                activeCalls.remove(call.callId);
            }
        });
    }

    /**
     * Receive loop
     */
    private void receiveLoop() {
        byte[] buffer = new byte[4096];

        while (running && !Thread.interrupted()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                sipSocket.receive(packet);

                // Update health monitor timestamp
                lastReceivedTime = System.currentTimeMillis();

                String message = new String(packet.getData(), 0, packet.getLength());
                handleMessage(message, packet.getAddress(), packet.getPort());

            } catch (SocketTimeoutException e) {
                // Expected - continue loop (allows periodic checks)
                continue;
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Receive error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Handle incoming SIP message
     */
    private void handleMessage(String rawMessage, InetAddress fromAddr, int fromPort) {
        try {
            // Ignore empty messages (keep-alive packets)
            if (rawMessage == null || rawMessage.trim().isEmpty()) {
                return;
            }

            // Log received message
            logSipMessage("RX", rawMessage, fromAddr.getHostAddress(), fromPort);

            SIPMessage msg = SIPMessage.parse(rawMessage);
            if (msg == null) {
                Log.d(TAG, "[SIP] Unparseable SIP message from " + fromAddr.getHostAddress());
                return;
            }

            if (msg.isRequest()) {
                handleRequest(msg, fromAddr, fromPort);
            } else {
                handleResponse(msg);
            }

        } catch (Exception e) {
            Log.e(TAG, "[SIP] ❌ Error handling SIP message: " + e.getMessage(), e);
        }
    }

    /**
     * Handle incoming SIP request
     */
    private void handleRequest(SIPMessage request, InetAddress fromAddr, int fromPort) {
        String method = request.getMethod();
        Log.i(TAG, "Received " + method + " from " + fromAddr.getHostAddress() + ":" + fromPort);

        switch (method) {
            case "REGISTER":
                handleRegister(request, fromAddr, fromPort);
                break;

            case "INVITE":
                handleInvite(request, fromAddr, fromPort);
                break;

            case "ACK":
                handleAck(request);
                break;

            case "BYE":
                handleBye(request, fromAddr, fromPort);
                break;

            case "CANCEL":
                handleCancel(request, fromAddr, fromPort);
                break;

            case "OPTIONS":
                handleOptions(request, fromAddr, fromPort);
                break;

            default:
                Log.w(TAG, "Unhandled request method: " + method);
        }
    }

    /**
     * Handle incoming REGISTER (PBX registering with us - trunk mode)
     * This allows us to learn the PBX address for sending INVITEs
     */
    private void handleRegister(SIPMessage register, InetAddress fromAddr, int fromPort) {
        Log.i(TAG, "Handling REGISTER from " + fromAddr.getHostAddress() + ":" + fromPort);

        // Learn PBX address for sending INVITEs
        learnedPbxAddress = fromAddr;
        learnedPbxPort = fromPort;
        Log.i(TAG, "Learned PBX address: " + fromAddr.getHostAddress() + ":" + fromPort);

        // Send 200 OK response
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("SIP/2.0 200 OK\r\n");

            // Copy Via header
            String via = register.getHeader("via");
            if (via != null) {
                sb.append("Via: ").append(via).append("\r\n");
            }

            // Copy From, To, Call-ID, CSeq
            sb.append("From: ").append(register.getHeader("from")).append("\r\n");

            String to = register.getHeader("to");
            if (to != null && !to.contains("tag=")) {
                to = to + ";tag=" + SIPMessage.generateTag();
            }
            sb.append("To: ").append(to).append("\r\n");

            sb.append("Call-ID: ").append(register.getCallId()).append("\r\n");
            sb.append("CSeq: ").append(register.getHeader("cseq")).append("\r\n");

            // Contact header
            String contact = register.getHeader("contact");
            if (contact != null) {
                sb.append("Contact: ").append(contact).append("\r\n");
            }

            // Expires
            String expires = register.getHeader("expires");
            if (expires == null) {
                expires = "3600";
            }
            sb.append("Expires: ").append(expires).append("\r\n");

            sb.append("Server: GSM-Gateway/1.0\r\n");
            sb.append("Content-Length: 0\r\n");
            sb.append("\r\n");

            // Send response
            byte[] bytes = sb.toString().getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, fromAddr, fromPort);
            sipSocket.send(packet);

            Log.i(TAG, "Sent 200 OK for REGISTER");

        } catch (Exception e) {
            Log.e(TAG, "Error responding to REGISTER: " + e.getMessage(), e);
        }
    }

    /**
     * Handle incoming INVITE (outgoing GSM call request from PBX)
     */
    private void handleInvite(SIPMessage invite, InetAddress fromAddr, int fromPort) {
        String callId = invite.getCallId();
        String dialedNumber = invite.getDialedNumber();

        Log.i(TAG, "┌───────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ [SIP] 📞 INCOMING INVITE - NEW CALL                       │");
        Log.i(TAG, "├───────────────────────────────────────────────────────────┤");
        Log.i(TAG, "│ Dialed Number: " + String.format("%-43s", dialedNumber) + " │");
        Log.i(TAG, "│ From:          " + String.format("%-43s", fromAddr.getHostAddress() + ":" + fromPort) + " │");
        Log.i(TAG, "│ Call-ID:       " + String.format("%-43s", callId.length() > 43 ? callId.substring(0, 40) + "..." : callId) + " │");
        Log.i(TAG, "└───────────────────────────────────────────────────────────┘");

        // Parse SDP to get remote RTP info
        SDPParser sdp = new SDPParser();
        if (invite.getBody() != null) {
            sdp.parse(invite.getBody());
        }

        // Create call object
        SIPCall call = new SIPCall();
        call.callId = callId;
        call.fromTag = invite.getFromTag();
        call.fromHeader = invite.getHeader("from");
        call.toHeader = invite.getHeader("to");
        call.viaHeader = invite.getHeader("via");
        call.remoteUri = getContactUri(invite);
        call.remoteRtpAddress = sdp.getConnectionAddress();
        call.remoteRtpPort = sdp.getAudioPort();
        call.isIncoming = true;
        call.state = SIPCall.CallState.RINGING;
        call.senderAddress = fromAddr;
        call.senderPort = fromPort;
        call.cseq = 1;
        call.originalInvite = invite;  // Store for response generation

        // Log stored headers for debugging
        Log.d(TAG, "[SIP] Stored From header: " + call.fromHeader);
        Log.d(TAG, "[SIP] Stored To header: " + call.toHeader);
        Log.d(TAG, "[SIP] Remote fromTag: " + call.fromTag);

        activeCalls.put(callId, call);

        Log.i(TAG, "[SIP] 🎧 Remote RTP endpoint from SDP: " + call.remoteRtpAddress + ":" + call.remoteRtpPort);

        // Send 100 Trying
        try {
            SIPMessage trying = SIPMessage.createResponse(invite, 100, "Trying");
            sendToCall(call, trying);
        } catch (Exception e) {
            Log.e(TAG, "Error sending 100 Trying", e);
        }

        // Send 180 Ringing
        try {
            SIPMessage ringing = SIPMessage.createResponse(invite, 180, "Ringing");
            sendToCall(call, ringing);
        } catch (Exception e) {
            Log.e(TAG, "Error sending 180 Ringing", e);
        }

        // Notify listener
        if (eventListener != null) {
            eventListener.onIncomingCall(call, dialedNumber);
        }
    }

    /**
     * Handle ACK
     */
    private void handleAck(SIPMessage ack) {
        String callId = ack.getCallId();
        SIPCall call = activeCalls.get(callId);

        if (call != null) {
            call.state = SIPCall.CallState.CONFIRMED;
            Log.i(TAG, "Call confirmed: " + callId);

            if (eventListener != null) {
                eventListener.onCallAnswered(call);
            }
        }
    }

    /**
     * Handle BYE
     */
    private void handleBye(SIPMessage bye, InetAddress fromAddr, int fromPort) {
        String callId = bye.getCallId();
        SIPCall call = activeCalls.get(callId);

        Log.i(TAG, "┌───────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ [SIP] 📴 BYE RECEIVED - CALL ENDING                       │");
        Log.i(TAG, "│ From: " + String.format("%-52s", fromAddr.getHostAddress() + ":" + fromPort) + " │");
        Log.i(TAG, "└───────────────────────────────────────────────────────────┘");

        // Send 200 OK
        try {
            SIPMessage ok = SIPMessage.createResponse(bye, 200, "OK");
            byte[] bytes = ok.toBytes().getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, fromAddr, fromPort);
            sipSocket.send(packet);
            Log.i(TAG, "[SIP] ✓ Sent 200 OK for BYE");
        } catch (Exception e) {
            Log.e(TAG, "[SIP] ❌ Error sending 200 OK for BYE: " + e.getMessage(), e);
        }

        if (call != null) {
            call.state = SIPCall.CallState.TERMINATED;
            activeCalls.remove(callId);
            Log.i(TAG, "[SIP] ✓ Call terminated by remote party");

            if (eventListener != null) {
                eventListener.onCallEnded(call);
            }
        } else {
            Log.w(TAG, "[SIP] ⚠ BYE for unknown call: " + callId);
        }
    }

    /**
     * Handle CANCEL
     */
    private void handleCancel(SIPMessage cancel, InetAddress fromAddr, int fromPort) {
        String callId = cancel.getCallId();
        SIPCall call = activeCalls.get(callId);

        Log.i(TAG, "┌───────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ [SIP] ⛔ CANCEL RECEIVED - CALL CANCELLED                  │");
        Log.i(TAG, "│ From: " + String.format("%-52s", fromAddr.getHostAddress() + ":" + fromPort) + " │");
        Log.i(TAG, "└───────────────────────────────────────────────────────────┘");

        // Send 200 OK for CANCEL
        try {
            SIPMessage ok = SIPMessage.createResponse(cancel, 200, "OK");
            byte[] bytes = ok.toBytes().getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, fromAddr, fromPort);
            sipSocket.send(packet);
            Log.i(TAG, "[SIP] ✓ Sent 200 OK for CANCEL");
        } catch (Exception e) {
            Log.e(TAG, "[SIP] ❌ Error sending 200 OK for CANCEL: " + e.getMessage(), e);
        }

        if (call != null) {
            // Send 487 Request Terminated for the INVITE
            call.state = SIPCall.CallState.TERMINATED;
            activeCalls.remove(callId);
            Log.i(TAG, "[SIP] ✓ Call cancelled by remote party");

            if (eventListener != null) {
                eventListener.onCallEnded(call);
            }
        } else {
            Log.w(TAG, "[SIP] ⚠ CANCEL for unknown call: " + callId);
        }
    }

    /**
     * Handle OPTIONS
     */
    private void handleOptions(SIPMessage options, InetAddress fromAddr, int fromPort) {
        try {
            SIPMessage ok = SIPMessage.createResponse(options, 200, "OK");
            byte[] bytes = ok.toBytes().getBytes();
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, fromAddr, fromPort);
            sipSocket.send(packet);
        } catch (Exception e) {
            Log.e(TAG, "Error responding to OPTIONS", e);
        }
    }

    /**
     * Handle SIP response
     */
    private void handleResponse(SIPMessage response) {
        int statusCode = response.getStatusCode();
        String cseqHeader = response.getHeader("cseq");
        String method = cseqHeader != null ? cseqHeader.split(" ")[1] : "";

        Log.i(TAG, "Received " + statusCode + " " + response.getReasonPhrase() + " for " + method);

        switch (method) {
            case "REGISTER":
                handleRegisterResponse(response);
                break;

            case "INVITE":
                handleInviteResponse(response);
                break;

            case "BYE":
                // BYE response - call cleanup already done
                break;
        }
    }

    /**
     * Handle REGISTER response
     */
    private void handleRegisterResponse(SIPMessage response) {
        int statusCode = response.getStatusCode();

        if (statusCode == 200) {
            registered = true;
            Log.i(TAG, "Registration successful");
            if (eventListener != null) {
                eventListener.onRegistered();
            }
        } else if (statusCode == 401 || statusCode == 407) {
            // Authentication required
            String authHeader = response.getHeader("www-authenticate");
            if (authHeader == null) {
                authHeader = response.getHeader("proxy-authenticate");
            }

            if (authHeader != null) {
                parseAuthChallenge(authHeader);
                try {
                    sendRegister(60);
                } catch (Exception e) {
                    Log.e(TAG, "Re-registration with auth failed", e);
                }
            }
        } else {
            Log.e(TAG, "Registration failed: " + statusCode);
            if (eventListener != null) {
                eventListener.onRegistrationFailed("Status " + statusCode);
            }
        }
    }

    /**
     * Handle INVITE response
     */
    private void handleInviteResponse(SIPMessage response) {
        int statusCode = response.getStatusCode();
        String callId = response.getCallId();
        SIPCall call = activeCalls.get(callId);

        if (call == null) {
            Log.w(TAG, "[SIP] ⚠ No call found for INVITE response: " + callId);
            return;
        }

        if (statusCode >= 100 && statusCode < 200) {
            // Provisional response (100, 180, 183)
            String icon = statusCode == 180 ? "🔔" : (statusCode == 183 ? "🎵" : "⏳");
            Log.i(TAG, "[SIP] " + icon + " Provisional " + statusCode + " " + response.getReasonPhrase() +
                       " (State: " + call.state + " → " + (statusCode == 183 ? "EARLY_MEDIA" : "RINGING") + ")");

            if (statusCode == 183) {
                // 183 Session Progress - may contain early media SDP
                SDPParser sdp = new SDPParser();
                if (response.getBody() != null && sdp.parse(response.getBody())) {
                    call.remoteRtpAddress = sdp.getConnectionAddress();
                    call.remoteRtpPort = sdp.getAudioPort();
                    call.state = SIPCall.CallState.EARLY_MEDIA;
                    Log.i(TAG, "[SIP] 🎧 Early media RTP: " + call.remoteRtpAddress + ":" + call.remoteRtpPort);

                    if (eventListener != null) {
                        eventListener.onEarlyMedia(call);
                    }
                } else {
                    call.state = SIPCall.CallState.RINGING;
                }
            } else {
                call.state = SIPCall.CallState.RINGING;
            }

            // Notify listener of progress
            if (eventListener != null) {
                eventListener.onCallProgress(call, statusCode);
            }

        } else if (statusCode == 200) {
            // Call answered - cancel INVITE timer
            cancelTransactionTimer(callId);

            Log.i(TAG, "┌───────────────────────────────────────────────────────────┐");
            Log.i(TAG, "│ [SIP] ✅ 200 OK - CALL ANSWERED                            │");
            Log.i(TAG, "└───────────────────────────────────────────────────────────┘");

            call.state = SIPCall.CallState.ANSWERED;
            call.toTag = response.getToTag();
            call.toHeader = response.getHeader("to");

            // Parse SDP for RTP info
            SDPParser sdp = new SDPParser();
            if (response.getBody() != null && sdp.parse(response.getBody())) {
                call.remoteRtpAddress = sdp.getConnectionAddress();
                call.remoteRtpPort = sdp.getAudioPort();
                Log.i(TAG, "[SIP] 🎧 RTP endpoint from 200 OK: " + call.remoteRtpAddress + ":" + call.remoteRtpPort);
            }

            // Send ACK
            try {
                Log.i(TAG, "[SIP] Sending ACK...");
                SIPMessage ack = SIPMessage.createAck(response, localIp, localSipPort);
                if (call.senderAddress != null) {
                    sendToAddress(ack, call.senderAddress, call.senderPort);
                } else {
                    sendMessage(ack);
                }
                call.state = SIPCall.CallState.CONFIRMED;
                Log.i(TAG, "[SIP] ✓ Call state: CONFIRMED - ready for audio");
            } catch (Exception e) {
                Log.e(TAG, "[SIP] ❌ Error sending ACK: " + e.getMessage(), e);
            }

            if (eventListener != null) {
                Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
                Log.i(TAG, "║ SIP 200 OK RECEIVED - CALLING onCallAnswered()             ║");
                Log.i(TAG, "║ This should trigger GSM answer                             ║");
                Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
                eventListener.onCallAnswered(call);
            } else {
                Log.w(TAG, "⚠️  eventListener is NULL! onCallAnswered will NOT be called!");
            }

        } else if (statusCode >= 400) {
            // Error response - cancel INVITE timer
            cancelTransactionTimer(callId);

            Log.e(TAG, "┌───────────────────────────────────────────────────────────┐");
            Log.e(TAG, "│ [SIP] ❌ CALL FAILED: " + String.format("%-36s", statusCode + " " + response.getReasonPhrase()) + " │");
            Log.e(TAG, "└───────────────────────────────────────────────────────────┘");

            call.state = SIPCall.CallState.TERMINATED;
            activeCalls.remove(callId);

            // Send ACK for error responses (required by RFC 3261)
            try {
                SIPMessage ack = SIPMessage.createAck(response, localIp, localSipPort);
                if (call.senderAddress != null) {
                    sendToAddress(ack, call.senderAddress, call.senderPort);
                } else {
                    sendMessage(ack);
                }
                Log.d(TAG, "[SIP] Sent ACK for error response");
            } catch (Exception e) {
                Log.e(TAG, "[SIP] ❌ Error sending ACK for error response: " + e.getMessage(), e);
            }

            if (eventListener != null) {
                eventListener.onCallFailed(call, statusCode, response.getReasonPhrase());
                eventListener.onCallEnded(call);
            }
        }
    }

    /**
     * Parse WWW-Authenticate header
     */
    private void parseAuthChallenge(String authHeader) {
        // Digest realm="asterisk",nonce="abc123"
        if (authHeader.startsWith("Digest ")) {
            authHeader = authHeader.substring(7);
        }

        String[] parts = authHeader.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("realm=")) {
                lastRealm = part.substring(7, part.length() - 1);
            } else if (part.startsWith("nonce=")) {
                lastNonce = part.substring(7, part.length() - 1);
            }
        }

        Log.d(TAG, "Auth challenge: realm=" + lastRealm + ", nonce=" + lastNonce);
    }

    /**
     * Create Digest authentication header
     */
    private String createDigestAuth(String method, String uri) {
        String ha1 = md5(username + ":" + lastRealm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String response = md5(ha1 + ":" + lastNonce + ":" + ha2);

        return String.format(
            "Digest username=\"%s\",realm=\"%s\",nonce=\"%s\",uri=\"%s\",response=\"%s\",algorithm=MD5",
            username, lastRealm, lastNonce, uri, response);
    }

    /**
     * MD5 hash
     */
    private String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /**
     * Get Contact URI from message
     */
    private String getContactUri(SIPMessage msg) {
        String contact = msg.getHeader("contact");
        if (contact != null && contact.contains("<") && contact.contains(">")) {
            return contact.substring(contact.indexOf('<') + 1, contact.indexOf('>'));
        }
        return "sip:" + pbxHost + ":" + pbxPort;
    }

    /**
     * Create SDP
     */
    private String createSDP(int rtpPort) {
        String sessionId = String.valueOf(System.currentTimeMillis());
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=GSMGateway ").append(sessionId).append(" ").append(sessionId)
           .append(" IN IP4 ").append(localIp).append("\r\n");
        sdp.append("s=GSM Gateway Call\r\n");
        sdp.append("c=IN IP4 ").append(localIp).append("\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("m=audio ").append(rtpPort).append(" RTP/AVP 0 8 101\r\n");
        sdp.append("a=rtpmap:0 PCMU/8000\r\n");
        sdp.append("a=rtpmap:8 PCMA/8000\r\n");
        sdp.append("a=rtpmap:101 telephone-event/8000\r\n");
        sdp.append("a=fmtp:101 0-16\r\n");
        sdp.append("a=ptime:20\r\n");
        sdp.append("a=sendrecv\r\n");
        return sdp.toString();
    }

    /**
     * Send SIP message to PBX
     */
    private void sendMessage(SIPMessage msg) throws Exception {
        String data = msg.toBytes();
        sendRaw(data);
    }

    /**
     * Send SIP message to specific call's sender (for responses in trunk mode)
     */
    private void sendToCall(SIPCall call, SIPMessage msg) throws Exception {
        String data = msg.toBytes();
        InetAddress destAddr;
        int destPort;

        if (call.senderAddress != null) {
            // Send back to whoever sent us the INVITE (trunk mode)
            destAddr = call.senderAddress;
            destPort = call.senderPort;
        } else if (pbxHost != null) {
            destAddr = InetAddress.getByName(pbxHost);
            destPort = pbxPort;
        } else {
            Log.e(TAG, "Cannot send message - no destination address");
            return;
        }

        byte[] bytes = data.getBytes();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, destAddr, destPort);
        sipSocket.send(packet);
        Log.d(TAG, "Sent SIP message to " + destAddr.getHostAddress() + ":" + destPort);
    }

    /**
     * Send raw string to PBX (uses configured host or learned address in trunk mode)
     */
    private void sendRaw(String data) throws Exception {
        InetAddress destAddr;
        int destPort;

        if (pbxHost != null && !pbxHost.isEmpty()) {
            // Normal mode - use configured PBX address
            destAddr = InetAddress.getByName(pbxHost);
            destPort = pbxPort;
        } else if (learnedPbxAddress != null) {
            // Trunk mode - use learned PBX address
            destAddr = learnedPbxAddress;
            destPort = learnedPbxPort;
        } else {
            Log.e(TAG, "[SIP] ❌ Cannot send - no PBX address (neither configured nor learned)");
            return;
        }

        // Log outgoing message
        logSipMessage("TX", data, destAddr.getHostAddress(), destPort);

        byte[] bytes = data.getBytes();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, destAddr, destPort);
        sipSocket.send(packet);
    }

    // ==================== TRANSACTION TIMERS ====================

    /**
     * Start INVITE transaction timer (RFC 3261 Timer B)
     */
    private void startInviteTimer(SIPCall call) {
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            if (call.state == SIPCall.CallState.RINGING ||
                call.state == SIPCall.CallState.EARLY_MEDIA ||
                call.state == SIPCall.CallState.IDLE) {
                Log.w(TAG, "INVITE timeout for call " + call.callId);
                call.state = SIPCall.CallState.TERMINATED;
                activeCalls.remove(call.callId);

                if (eventListener != null) {
                    eventListener.onCallFailed(call, 408, "Request Timeout");
                    eventListener.onCallEnded(call);
                }
            }
        }, INVITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        transactionTimers.put(call.callId, timer);
    }

    /**
     * Cancel a transaction timer
     */
    private void cancelTransactionTimer(String callId) {
        ScheduledFuture<?> timer = transactionTimers.remove(callId);
        if (timer != null) {
            timer.cancel(false);
            Log.d(TAG, "Cancelled transaction timer for " + callId);
        }
    }

    // ==================== RETRANSMISSION ====================

    /**
     * Send SIP message with retransmission (RFC 3261 Timer A)
     */
    private void sendWithRetransmit(SIPMessage msg, SIPCall call,
                                    InetAddress destAddr, int destPort, int maxRetries) {
        final AtomicInteger retryCount = new AtomicInteger(0);

        Runnable retransmitTask = new Runnable() {
            @Override
            public void run() {
                // Stop if call is terminated or answered
                if (call.state == SIPCall.CallState.TERMINATED ||
                    call.state == SIPCall.CallState.ANSWERED ||
                    call.state == SIPCall.CallState.CONFIRMED) {
                    return;
                }

                if (retryCount.get() >= maxRetries) {
                    Log.w(TAG, "Max retransmits reached for " + call.callId);
                    return;
                }

                try {
                    String data = msg.toBytes();
                    byte[] bytes = data.getBytes();
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length, destAddr, destPort);
                    sipSocket.send(packet);

                    int retry = retryCount.incrementAndGet();
                    if (retry > 1) {
                        Log.d(TAG, "Retransmit #" + retry + " for " + msg.getMethod() + " " + call.callId);
                    }

                    // Schedule next retransmit
                    if (retry < maxRetries) {
                        long delay = RETRANSMIT_INTERVALS[Math.min(retry - 1, RETRANSMIT_INTERVALS.length - 1)];
                        scheduler.schedule(this, delay, TimeUnit.MILLISECONDS);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Retransmit failed: " + e.getMessage());
                }
            }
        };

        // Start first transmission
        executor.execute(retransmitTask);
    }

    // ==================== SEND HELPERS ====================

    /**
     * Send SIP message to specific address
     */
    private void sendToAddress(SIPMessage msg, InetAddress destAddr, int destPort) throws Exception {
        String data = msg.toBytes();

        // Log outgoing message
        logSipMessage("TX", data, destAddr.getHostAddress(), destPort);

        byte[] bytes = data.getBytes();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, destAddr, destPort);
        sipSocket.send(packet);
    }

    // ==================== GETTERS ====================

    public boolean isRegistered() { return registered; }
    public SIPCall getCall(String callId) { return activeCalls.get(callId); }
    public Map<String, SIPCall> getActiveCalls() { return activeCalls; }
}
