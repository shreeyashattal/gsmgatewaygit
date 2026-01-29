package com.shreeyash.gateway;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native PCM Audio Bridge for SM6150 (Snapdragon 720G) devices
 * Uses direct kernel PCM device access to capture/inject voice call audio
 * Bypasses Android's AudioRecord/AudioTrack which are blocked during calls
 */
public class NativePCMAudioBridge {
    private static final String TAG = "NativePCMAudioBridge";

    // PCM Configuration for voice calls (8kHz mono 16-bit)
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int FRAME_SIZE = 160; // 20ms at 8kHz
    private static final int BUFFER_SIZE = FRAME_SIZE * 2; // 16-bit samples

    // RTP Configuration
    private static final int RTP_HEADER_SIZE = 12;
    private static final byte RTP_VERSION = (byte) 0x80;
    private static final byte PAYLOAD_TYPE_PCMU = 0;

    // Remote RTP endpoint
    private String remoteHost;
    private int remotePort;
    private int localRtpPort;

    // State
    private volatile boolean running = false;
    private DatagramSocket rtpSocket;
    private InetAddress remoteAddress;

    // RTP state
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private int ssrc;

    // Threads
    private Thread captureThread;
    private Thread playbackThread;

    // Root shell process for audio routing
    private Process rootShell;
    private DataOutputStream rootOut;

    // Qualcomm platform detection for mixer control selection
    private enum QualcommPlatform {
        SM6150,   // Snapdragon 720G (Bengal)
        SM8150,   // Snapdragon 855
        SM8250,   // Snapdragon 865
        SDM845,   // Snapdragon 845
        SDM660,   // Snapdragon 660
        UNKNOWN
    }
    private QualcommPlatform detectedPlatform = QualcommPlatform.UNKNOWN;

    // Mixer control discovery
    private Set<String> availableMixerControls = new HashSet<>();
    private boolean mixerDiscovered = false;

    // Mic mute state tracking
    private volatile boolean micMuted = false;

    // Error recovery
    private int consecutiveFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    // Bridge failure listener
    private AudioBridgeListener bridgeListener;

    public interface AudioBridgeListener {
        void onBridgeFailure(String reason);
    }

    public void setBridgeListener(AudioBridgeListener listener) {
        this.bridgeListener = listener;
    }

    public NativePCMAudioBridge(int localRtpPort) {
        this.localRtpPort = localRtpPort;
        this.ssrc = (int) (Math.random() * Integer.MAX_VALUE);
    }

    public void setRemoteAddress(String host, int port) {
        this.remoteHost = host;
        this.remotePort = port;
    }

    /**
     * Start the audio bridge
     */
    public boolean start() {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║          STARTING NATIVE PCM AUDIO BRIDGE                  ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        if (remoteHost == null || remotePort == 0) {
            Log.e(TAG, "❌ ERROR: Remote address not set");
            return false;
        }

        try {
            // Step 1: Initialize root shell
            Log.i(TAG, "┌─ STEP 1: Initializing root shell...");
            if (!initRootShell()) {
                Log.e(TAG, "└─ ❌ FAILED: Could not get root access");
                return false;
            }
            Log.i(TAG, "└─ ✓ Root shell ready");

            // Step 2: Set up audio routing for voice call capture
            Log.i(TAG, "┌─ STEP 2: Setting up voice call audio routing...");
            setupVoiceCallRouting();
            Log.i(TAG, "└─ ✓ Audio routing configured");

            // Step 3: Create RTP socket
            Log.i(TAG, "┌─ STEP 3: Creating RTP socket...");
            remoteAddress = InetAddress.getByName(remoteHost);
            rtpSocket = new DatagramSocket(localRtpPort);
            rtpSocket.setSoTimeout(1000);
            Log.i(TAG, "│  Local RTP port: " + localRtpPort);
            Log.i(TAG, "│  Remote RTP: " + remoteHost + ":" + remotePort);
            Log.i(TAG, "└─ ✓ RTP socket ready");

            running = true;

            // Step 4: Start capture thread (GSM party voice → RTP → PBX)
            Log.i(TAG, "┌─ STEP 4: Starting capture thread (GSM → PBX)...");
            captureThread = new Thread(this::captureLoop, "PCM-Capture");
            captureThread.start();
            Log.i(TAG, "└─ ✓ Capture thread started");

            // Step 5: Start playback thread (PBX → RTP → GSM party)
            Log.i(TAG, "┌─ STEP 5: Starting playback thread (PBX → GSM)...");
            playbackThread = new Thread(this::playbackLoop, "PCM-Playback");
            playbackThread.start();
            Log.i(TAG, "└─ ✓ Playback thread started");

            Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
            Log.i(TAG, "║       ✓ AUDIO BRIDGE STARTED SUCCESSFULLY                  ║");
            Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ FATAL: Failed to start audio bridge: " + e.getMessage(), e);
            stop();
            return false;
        }
    }

    /**
     * Stop the audio bridge
     */
    public void stop() {
        running = false;

        if (captureThread != null) {
            captureThread.interrupt();
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
        }

        // Disable voice call routing
        try {
            disableVoiceCallRouting();
        } catch (Exception e) {
            Log.e(TAG, "Error disabling routing", e);
        }

        // Close root shell
        closeRootShell();

        // Close RTP socket
        if (rtpSocket != null && !rtpSocket.isClosed()) {
            rtpSocket.close();
        }

        Log.i(TAG, "Native PCM audio bridge stopped");
    }

    /**
     * Initialize root shell
     */
    private boolean initRootShell() {
        try {
            rootShell = Runtime.getRuntime().exec("su");
            rootOut = new DataOutputStream(rootShell.getOutputStream());

            // Test root access
            rootOut.writeBytes("id\n");
            rootOut.flush();

            Thread.sleep(100);

            Log.i(TAG, "Root shell initialized");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get root: " + e.getMessage());
            return false;
        }
    }

    /**
     * Close root shell
     */
    private void closeRootShell() {
        try {
            if (rootOut != null) {
                rootOut.writeBytes("exit\n");
                rootOut.flush();
                rootOut.close();
            }
            if (rootShell != null) {
                rootShell.destroy();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing root shell", e);
        }
    }

    /**
     * Execute command as root (fire-and-forget)
     */
    private void execRoot(String cmd) {
        try {
            rootOut.writeBytes(cmd + "\n");
            rootOut.flush();
            Log.d(TAG, "Root cmd: " + cmd);
        } catch (Exception e) {
            Log.e(TAG, "Root cmd failed: " + cmd, e);
        }
    }

    /**
     * Execute command as root and return output
     */
    private String execRootSync(String cmd) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            Log.e(TAG, "execRootSync failed: " + cmd, e);
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * Detect Qualcomm platform for mixer control selection
     */
    private QualcommPlatform detectQualcommPlatform() {
        String platform = execRootSync("getprop ro.board.platform");
        if (platform == null) {
            Log.w(TAG, "Could not detect platform, using UNKNOWN");
            return QualcommPlatform.UNKNOWN;
        }

        platform = platform.toLowerCase().trim();
        Log.i(TAG, "Detected board platform: " + platform);

        if (platform.contains("sm6150") || platform.contains("bengal")) {
            return QualcommPlatform.SM6150;
        } else if (platform.contains("sm8150") || platform.contains("msmnile")) {
            return QualcommPlatform.SM8150;
        } else if (platform.contains("sm8250") || platform.contains("kona")) {
            return QualcommPlatform.SM8250;
        } else if (platform.contains("sdm845")) {
            return QualcommPlatform.SDM845;
        } else if (platform.contains("sdm660")) {
            return QualcommPlatform.SDM660;
        }

        return QualcommPlatform.UNKNOWN;
    }

    /**
     * Discover available mixer controls
     */
    private void discoverMixerControls() {
        if (mixerDiscovered) return;

        String output = execRootSync("tinymix");
        if (output == null) {
            Log.e(TAG, "tinymix not available - mixer discovery failed");
            return;
        }

        // Parse tinymix output to extract control names
        // Format varies but typically: "123 [control name]" or "123\t[control name]"
        Pattern pattern = Pattern.compile("^\\s*(\\d+)\\s+(.+?)\\s*$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(output);

        while (matcher.find()) {
            String controlName = matcher.group(2).trim();
            availableMixerControls.add(controlName);
        }

        mixerDiscovered = true;
        Log.i(TAG, "Discovered " + availableMixerControls.size() + " mixer controls");

        // Log key controls for gateway audio routing
        Log.i(TAG, "=== KEY MIXER CONTROLS FOR GATEWAY ===");
        String[] keyControls = {
            "VOC_REC_DL",           // CRITICAL: Captures GSM party voice (downlink)
            "VOC_REC_UL",           // Phone mic capture (should be disabled)
            "Incall_Music",         // CRITICAL: Injects PBX audio into GSM call
            "Voice Tx Device Mute", // Mutes phone mic
            "Voice Rx Device Mute", // Mutes phone speaker
            "VoiceMMode1",          // Voice mode mixer
            "SLIMBUS",              // SLIMBUS audio path
            "ADC1 Volume"           // Mic ADC volume
        };
        for (String ctrl : keyControls) {
            boolean found = hasMixerControl(ctrl);
            Log.i(TAG, "  " + ctrl + ": " + (found ? "FOUND" : "not found"));
        }
        Log.i(TAG, "======================================");
    }

    /**
     * Check if a mixer control exists (case-insensitive partial match)
     */
    private boolean hasMixerControl(String control) {
        String lowerControl = control.toLowerCase();
        for (String c : availableMixerControls) {
            if (c.toLowerCase().contains(lowerControl)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set mixer control with verification
     * @return true if the setting was applied and verified
     */
    private boolean setMixerControl(String control, String value) {
        // Try to set the control
        execRoot("tinymix '" + control + "' '" + value + "'");

        // Wait briefly for the setting to take effect
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        // Verify the setting
        String verify = execRootSync("tinymix '" + control + "'");
        if (verify != null && verify.contains(value)) {
            Log.i(TAG, "Mixer OK: " + control + " = " + value);
            return true;
        }

        // Some controls report differently - just check if command didn't error
        if (verify != null && !verify.toLowerCase().contains("error") &&
            !verify.toLowerCase().contains("invalid")) {
            Log.i(TAG, "Mixer SET (unverified): " + control + " = " + value);
            return true;
        }

        Log.w(TAG, "Mixer FAILED: " + control + " = " + value);
        return false;
    }

    /**
     * Set mixer control with value (int version)
     */
    private boolean setMixerControl(String control, int value) {
        return setMixerControl(control, String.valueOf(value));
    }

    /**
     * Set up mixer controls for voice call audio capture/injection
     * Platform-aware with fallbacks for different Qualcomm SoCs
     *
     * Audio Flow for Gateway Mode:
     * ┌─────────────────────────────────────────────────────────────┐
     * │  GSM Party ←──────────────────────────────────→ PBX        │
     * │                                                             │
     * │  [GSM Party Voice]                                          │
     * │      ↓ (Downlink from cell tower)                           │
     * │  VOC_REC_DL → tinycap → PCM → μ-law → RTP → [PBX hears]    │
     * │                                                             │
     * │  [PBX Voice]                                                │
     * │  RTP → μ-law → PCM → tinyplay → Incall_Music → [GSM hears] │
     * │      ↓ (Uplink to cell tower)                               │
     * │                                                             │
     * │  Phone Mic  → MUTED (no local audio)                        │
     * │  Phone Spkr → MUTED (no local playback)                     │
     * └─────────────────────────────────────────────────────────────┘
     *
     * Key Qualcomm mixer controls:
     * - VOC_REC_DL: Voice Downlink Recording (GSM party → Android)
     * - VOC_REC_UL: Voice Uplink Recording (Phone mic → cell tower) - NOT USED
     * - Incall_Music: Inject audio into voice call (Android → GSM party)
     */
    private void setupVoiceCallRouting() {
        Log.i(TAG, "========================================");
        Log.i(TAG, "SETTING UP VOICE CALL AUDIO ROUTING");
        Log.i(TAG, "========================================");

        // Detect platform and discover mixer controls
        detectedPlatform = detectQualcommPlatform();
        Log.i(TAG, "Qualcomm Platform: " + detectedPlatform);

        discoverMixerControls();

        // Configure capture path (GSM → RTP)
        boolean captureOk = setupCapturePath();
        Log.i(TAG, "Capture path configured: " + (captureOk ? "SUCCESS" : "PARTIAL/FAILED"));

        // Configure injection path (RTP → GSM)
        boolean injectionOk = setupInjectionPath();
        Log.i(TAG, "Injection path configured: " + (injectionOk ? "SUCCESS" : "PARTIAL/FAILED"));

        // Mute phone speaker (gateway mode)
        boolean speakerMuted = muteSpeaker();
        Log.i(TAG, "Speaker muted: " + (speakerMuted ? "SUCCESS" : "PARTIAL/FAILED"));

        // Mute phone microphone (gateway mode - critical!)
        boolean micMuteOk = muteMicrophone();
        Log.i(TAG, "Microphone muted: " + (micMuteOk ? "SUCCESS" : "PARTIAL/FAILED - may have echo!"));

        // Give mixer time to apply settings
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        Log.i(TAG, "========================================");
        Log.i(TAG, "VOICE CALL ROUTING COMPLETE");
        Log.i(TAG, "Platform: " + detectedPlatform);
        Log.i(TAG, "Mic muted: " + micMuted);
        Log.i(TAG, "========================================");
    }

    /**
     * Configure capture path based on platform
     * CRITICAL: We capture VOC_REC_DL (Downlink = GSM party voice), NOT VOC_REC_UL (phone mic)
     *
     * VOC_REC_DL = Voice Downlink Recording = What GSM party is saying (from cell tower)
     * VOC_REC_UL = Voice Uplink Recording = What phone mic captures (NOT wanted in gateway mode)
     */
    private boolean setupCapturePath() {
        boolean success = false;

        Log.i(TAG, "Configuring CAPTURE path: GSM party voice (DL) -> RTP -> PBX");

        switch (detectedPlatform) {
            case SM6150:
                // Bengal (Snapdragon 720G)
                // NOTE: VOC_REC controls require TWO values (stereo control)
                // Enable DL (downlink = GSM party voice), disable UL (uplink = phone mic)
                success = setMixerControl("MultiMedia1 Mixer VOC_REC_DL", "1 1");  // GSM party voice (both channels)
                setMixerControl("MultiMedia1 Mixer VOC_REC_UL", "0 0");            // Disable phone mic capture
                setMixerControl("Voc Rec Config", "1");                            // Enable voice recording mode

                // Also try to ensure voice call audio is routed to capture
                // Some devices need this additional routing
                if (hasMixerControl("SLIMBUS_0_TX")) {
                    setMixerControl("MultiMedia1 Mixer SLIM_0_TX", "1");
                }
                break;

            case SM8150:
            case SM8250:
                // Snapdragon 855/865 - try VOC_REC_DL first, then SLIMBUS
                if (hasMixerControl("VOC_REC_DL")) {
                    success = setMixerControl("MultiMedia1 Mixer VOC_REC_DL", "1 1");
                    setMixerControl("MultiMedia1 Mixer VOC_REC_UL", "0 0");
                }
                // SLIMBUS fallback - captures RX path (what phone receives)
                if (!success && hasMixerControl("SLIMBUS")) {
                    success = setMixerControl("SLIMBUS_0_RX Voice Mixer VoiceMMode1", "1");
                    setMixerControl("MultiMedia1 Mixer SLIM_0_RX", "1");
                }
                break;

            case SDM845:
            case SDM660:
                // Older Qualcomm - try VOC_REC_DL first
                if (hasMixerControl("VOC_REC_DL")) {
                    success = setMixerControl("MultiMedia1 Mixer VOC_REC_DL", "1 1");
                    setMixerControl("MultiMedia1 Mixer VOC_REC_UL", "0 0");
                }
                // VoiceMMode fallback - need RX (receive) path
                if (!success && hasMixerControl("VoiceMMode1")) {
                    // Try to route voice RX to multimedia
                    success = setMixerControl("MultiMedia1 Mixer VoiceMMode1", "1");
                }
                if (!success && hasMixerControl("QUAT_MI2S")) {
                    success = setMixerControl("QUAT_MI2S_RX Voice Mixer VoiceMMode1", "1");
                }
                break;

            default:
                // Generic fallback - prioritize DL (downlink) controls
                Log.i(TAG, "Unknown platform, trying common DL capture controls");
                // Try VOC_REC_DL with dual values first (most Qualcomm devices)
                if (hasMixerControl("VOC_REC_DL")) {
                    success = setMixerControl("MultiMedia1 Mixer VOC_REC_DL", "1 1");
                    setMixerControl("MultiMedia1 Mixer VOC_REC_UL", "0 0");
                }
                // Fallbacks
                if (!success) {
                    String[] captureControls = {
                        "MultiMedia1 Mixer SLIM_0_RX",       // SLIMBUS RX path
                        "MultiMedia1 Mixer VoiceMMode1",     // Voice mode mixer
                        "SLIMBUS_0_RX Voice Mixer VoiceMMode1"
                    };
                    for (String ctrl : captureControls) {
                        if (hasMixerControl(ctrl.split(" ")[0])) {
                            success = setMixerControl(ctrl, "1");
                            if (success) {
                                Log.i(TAG, "Capture path configured via: " + ctrl);
                                break;
                            }
                        }
                    }
                }
        }

        if (!success) {
            Log.e(TAG, "WARNING: Could not configure capture path - PBX won't hear GSM party!");
        }
        return success;
    }

    /**
     * Configure injection path (RTP → GSM)
     * This routes PBX audio INTO the GSM call so the GSM party hears the PBX
     *
     * Incall_Music: Special Qualcomm mixer that injects audio into the voice uplink
     * The injected audio gets sent to the cell tower, so the GSM party hears it
     */
    private boolean setupInjectionPath() {
        boolean success = false;

        Log.i(TAG, "Configuring INJECTION path: PBX voice -> RTP -> Incall_Music -> GSM party");

        // Incall_Music is the standard Qualcomm path for injecting audio into voice calls
        // We use MultiMedia2 for playback (tinyplay -d 1 or AudioTrack)
        if (hasMixerControl("Incall_Music")) {
            // Primary: MultiMedia2 for injection
            success = setMixerControl("Incall_Music Audio Mixer MultiMedia2", 1);
            Log.i(TAG, "Incall_Music via MultiMedia2: " + (success ? "OK" : "FAILED"));

            // Also try MM9 as backup on some devices
            if (hasMixerControl("MultiMedia9")) {
                boolean mm9 = setMixerControl("Incall_Music Audio Mixer MultiMedia9", 1);
                Log.d(TAG, "Incall_Music via MultiMedia9: " + (mm9 ? "OK" : "FAILED"));
            }
        }

        // Ensure MM1 doesn't inject (reserved for capture)
        setMixerControl("Incall_Music Audio Mixer MultiMedia1", 0);

        // On some platforms, also need to set Voice TX path to allow injection
        if (hasMixerControl("Voice_Tx Mixer")) {
            setMixerControl("Voice_Tx Mixer Incall_Music", 1);
        }

        // Alternative injection path on some Qualcomm platforms
        if (!success && hasMixerControl("Voip_Tx Mixer")) {
            success = setMixerControl("Voip_Tx Mixer Incall_Music", 1);
            Log.i(TAG, "Alternative injection via Voip_Tx: " + (success ? "OK" : "FAILED"));
        }

        if (!success) {
            Log.e(TAG, "WARNING: Could not configure injection path - GSM party won't hear PBX!");
        }
        return success;
    }

    /**
     * Mute phone speaker/earpiece
     */
    private boolean muteSpeaker() {
        boolean anySuccess = false;

        // Method 1: Voice Rx Device Mute
        if (hasMixerControl("Voice Rx Device Mute")) {
            anySuccess |= setMixerControl("Voice Rx Device Mute", "1 1 1");
        }

        // Method 2: Voice Rx Gain
        if (hasMixerControl("Voice Rx Gain")) {
            setMixerControl("Voice Rx Gain", "0 0 0");
        }

        // Method 3: RX path mutes
        String[] rxPaths = {
            "SLIM_0_RX_Voice Mixer VoiceMMode1",
            "SLIM_0_RX_Voice Mixer VoiceMMode2",
            "RX_CDC_DMA_RX_0_Voice Mixer VoiceMMode1",
            "RX_CDC_DMA_RX_0_Voice Mixer VoiceMMode2"
        };
        for (String path : rxPaths) {
            if (hasMixerControl(path.split(" ")[0])) {
                setMixerControl(path, 0);
            }
        }

        // Method 4: Digital volumes
        String[] volumes = {"RX0 Digital Volume", "RX1 Digital Volume"};
        for (String vol : volumes) {
            if (hasMixerControl(vol)) {
                setMixerControl(vol, 0);
            }
        }

        // Method 5: DAC switches
        String[] dacs = {"EAR_SPKR DAC Switch", "HPHL DAC Switch", "HPHR DAC Switch"};
        for (String dac : dacs) {
            if (hasMixerControl(dac)) {
                setMixerControl(dac, 0);
            }
        }

        return anySuccess;
    }

    /**
     * Mute phone microphone - CRITICAL for gateway mode
     * Uses multiple methods with verification
     */
    private boolean muteMicrophone() {
        Log.i(TAG, "Muting phone microphone for gateway mode");

        boolean anySuccess = false;

        // Method 1: Voice Tx Device Mute (most reliable on Qualcomm)
        if (hasMixerControl("Voice Tx Device Mute")) {
            anySuccess |= setMixerControl("Voice Tx Device Mute", "1 1 1");
        }

        // Method 2: TX path mutes
        String[] txControls = {
            "TX_CDC_DMA_TX_3_Voice Mixer VoiceMMode1",
            "TX_CDC_DMA_TX_3_Voice Mixer VoiceMMode2",
            "SLIM_0_TX_Voice Mixer VoiceMMode1",
            "SLIM_0_TX_Voice Mixer VoiceMMode2"
        };
        for (String ctrl : txControls) {
            if (hasMixerControl(ctrl.split(" ")[0])) {
                anySuccess |= setMixerControl(ctrl, 0);
            }
        }

        // Method 3: ADC Volume zeroing
        String[] adcControls = {"ADC1 Volume", "ADC2 Volume", "ADC3 Volume"};
        for (String ctrl : adcControls) {
            if (hasMixerControl(ctrl)) {
                anySuccess |= setMixerControl(ctrl, 0);
            }
        }

        // Method 4: DEC (decimator) volumes
        String[] decControls = {"DEC0 Volume", "DEC1 Volume"};
        for (String ctrl : decControls) {
            if (hasMixerControl(ctrl)) {
                setMixerControl(ctrl, 0);
            }
        }

        // Method 5: TX Input routing to ZERO
        String[] txInputs = {"TX0 Input", "TX1 Input"};
        for (String ctrl : txInputs) {
            if (hasMixerControl(ctrl)) {
                anySuccess |= setMixerControl(ctrl, "ZERO");
            }
        }

        micMuted = anySuccess;

        if (!anySuccess) {
            Log.e(TAG, "WARNING: Could not mute microphone - may have local echo!");
        } else {
            Log.i(TAG, "Microphone muted successfully using " +
                      (anySuccess ? "multiple methods" : "fallback"));
        }

        return anySuccess;
    }

    /**
     * Check if microphone is muted
     */
    public boolean isMicrophoneMuted() {
        return micMuted;
    }

    /**
     * Dynamically control microphone mute during call
     */
    public void setMicrophoneMute(boolean mute) {
        if (mute == micMuted) return;

        if (mute) {
            muteMicrophone();
        } else {
            unmuteMicrophone();
        }
    }

    /**
     * Unmute microphone (restore normal operation)
     */
    private void unmuteMicrophone() {
        Log.i(TAG, "Unmuting phone microphone");

        // Restore Voice Tx Device Mute
        if (hasMixerControl("Voice Tx Device Mute")) {
            setMixerControl("Voice Tx Device Mute", "0 0 0");
        }

        // Restore TX paths
        String[] txControls = {
            "TX_CDC_DMA_TX_3_Voice Mixer VoiceMMode1",
            "SLIM_0_TX_Voice Mixer VoiceMMode1"
        };
        for (String ctrl : txControls) {
            if (hasMixerControl(ctrl.split(" ")[0])) {
                setMixerControl(ctrl, 1);
            }
        }

        // Restore ADC volumes (default is typically 84)
        String[] adcControls = {"ADC1 Volume", "ADC2 Volume"};
        for (String ctrl : adcControls) {
            if (hasMixerControl(ctrl)) {
                setMixerControl(ctrl, 84);
            }
        }

        micMuted = false;
    }

    /**
     * Disable voice call routing and restore normal audio
     */
    private void disableVoiceCallRouting() {
        Log.i(TAG, "Disabling voice call audio routing, restoring normal audio");

        // Disable voice recording routing
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 0");
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_DL' 0");

        // Disable incall music routing
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0");
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia2' 0");
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia9' 0");

        // Restore phone speaker/earpiece (unmute)
        execRoot("tinymix 'Voice Rx Device Mute' 0 0 0");
        execRoot("tinymix 'Voice Rx Gain' 2000 2000 2000");

        // Restore RX digital volumes
        execRoot("tinymix 'RX0 Digital Volume' 84");
        execRoot("tinymix 'RX1 Digital Volume' 84");

        // Restore earpiece DAC
        execRoot("tinymix 'EAR_SPKR DAC Switch' 1");
        execRoot("tinymix 'HPHL DAC Switch' 1");
        execRoot("tinymix 'HPHR DAC Switch' 1");

        // Restore phone mic
        execRoot("tinymix 'Voice Tx Device Mute' 0 0 0");
    }

    /**
     * Capture voice call audio and send via RTP
     * Uses tinycap with stdout pipe for reliable audio streaming
     *
     * This captures VOC_REC_DL (GSM party voice) and sends it to PBX via RTP
     */
    private void captureLoop() {
        Log.i(TAG, "┌───────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ CAPTURE LOOP: GSM Party Voice → RTP → PBX                 │");
        Log.i(TAG, "├───────────────────────────────────────────────────────────┤");
        Log.i(TAG, "│ Source: VOC_REC_DL (GSM party downlink audio)             │");
        Log.i(TAG, "│ Dest:   " + String.format("%-50s", remoteHost + ":" + remotePort) + " │");
        Log.i(TAG, "│ Codec:  G.711 μ-law @ 8kHz mono                           │");
        Log.i(TAG, "└───────────────────────────────────────────────────────────┘");

        // Log current mixer state for debugging
        logMixerState();

        // Try to find the correct PCM device
        int captureDevice = findCapturePCMDevice();
        Log.i(TAG, "Using PCM capture device: " + captureDevice);

        byte[] pcmBuffer = new byte[BUFFER_SIZE];
        byte[] rtpPacket = new byte[RTP_HEADER_SIZE + FRAME_SIZE];
        Process tinycapProc = null;

        try {
            // Start tinycap to capture voice call audio to stdout
            // Use discovered device, fallback to 0 if discovery failed
            String tinycapCmd = String.format(
                "tinycap /dev/stdout -D 0 -d %d -c 1 -r %d -b 16 2>/dev/null",
                captureDevice, SAMPLE_RATE
            );
            Log.i(TAG, "Starting tinycap: " + tinycapCmd);

            tinycapProc = Runtime.getRuntime().exec(new String[]{"su", "-c", tinycapCmd});

            DataInputStream audioIn = new DataInputStream(tinycapProc.getInputStream());

            // Skip WAV header (44 bytes) that tinycap prepends
            byte[] wavHeader = new byte[44];
            int headerRead = 0;
            int headerAttempts = 0;
            while (headerRead < 44 && running && headerAttempts < 100) {
                int r = audioIn.read(wavHeader, headerRead, 44 - headerRead);
                if (r <= 0) {
                    Thread.sleep(10);
                    headerAttempts++;
                    continue;
                }
                headerRead += r;
            }

            if (headerRead < 44) {
                Log.e(TAG, "Failed to read WAV header from tinycap (only got " + headerRead + " bytes)");
                Log.w(TAG, "Falling back to AudioRecord capture");
                captureWithAudioRecord();
                return;
            }

            Log.i(TAG, "Tinycap started successfully, skipped " + headerRead + " bytes WAV header");

            int packetCount = 0;
            int silentPackets = 0;
            int totalSilentPackets = 0;
            int maxAmplitudeSeen = 0;
            long lastLogTime = System.currentTimeMillis();
            long startTime = System.currentTimeMillis();

            Log.i(TAG, "[CAPTURE] Starting main capture loop...");

            while (running && !Thread.interrupted()) {
                int bytesRead = audioIn.read(pcmBuffer);
                if (bytesRead <= 0) {
                    Thread.sleep(5);
                    continue;
                }

                // Check if audio is silent (all zeros or very low amplitude)
                int amplitude = getMaxAmplitude(pcmBuffer, bytesRead);
                boolean isSilent = amplitude < 100;
                if (amplitude > maxAmplitudeSeen) {
                    maxAmplitudeSeen = amplitude;
                }

                if (isSilent) {
                    silentPackets++;
                    totalSilentPackets++;
                } else {
                    silentPackets = 0; // Reset consecutive silent counter
                }

                // Convert PCM to u-law
                byte[] ulawData = pcmToUlaw(pcmBuffer, bytesRead);

                // Build and send RTP packet
                buildRTPHeader(rtpPacket);
                int payloadSize = Math.min(ulawData.length, FRAME_SIZE);
                System.arraycopy(ulawData, 0, rtpPacket, RTP_HEADER_SIZE, payloadSize);

                DatagramPacket packet = new DatagramPacket(
                    rtpPacket,
                    RTP_HEADER_SIZE + payloadSize,
                    remoteAddress,
                    remotePort
                );
                rtpSocket.send(packet);

                sequenceNumber++;
                timestamp += FRAME_SIZE;
                packetCount++;

                // Detailed logging every 5 seconds
                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 5000) {
                    long elapsed = (now - startTime) / 1000;
                    float silentPct = (packetCount > 0) ? (totalSilentPackets * 100.0f / packetCount) : 0;

                    if (silentPackets > 100) {
                        Log.w(TAG, String.format("[CAPTURE] ⚠ SILENT AUDIO DETECTED! Check VOC_REC_DL mixer routing!"));
                    }

                    Log.i(TAG, String.format("[CAPTURE] Stats @ %ds: pkts=%d, silent=%.1f%%, maxAmp=%d, dest=%s:%d",
                        elapsed, packetCount, silentPct, maxAmplitudeSeen, remoteHost, remotePort));

                    // Reset max amplitude for next interval
                    maxAmplitudeSeen = 0;
                    lastLogTime = now;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Capture error: " + e.getMessage(), e);
            consecutiveFailures++;

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                Log.e(TAG, "Too many consecutive capture failures, notifying bridge failure");
                if (bridgeListener != null) {
                    bridgeListener.onBridgeFailure("Capture failed: " + e.getMessage());
                }
            } else {
                // Try AudioRecord fallback
                captureWithAudioRecord();
            }
        } finally {
            if (tinycapProc != null) {
                tinycapProc.destroy();
            }
        }

        Log.i(TAG, "Capture loop ended");
    }

    /**
     * Get maximum amplitude from audio buffer
     */
    private int getMaxAmplitude(byte[] buffer, int length) {
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        int maxAmplitude = 0;
        for (int i = 0; i < length / 2 && bb.remaining() >= 2; i++) {
            int sample = Math.abs(bb.getShort());
            if (sample > maxAmplitude) maxAmplitude = sample;
        }
        return maxAmplitude;
    }

    /**
     * Log current state of key mixer controls for debugging
     */
    private void logMixerState() {
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ CURRENT MIXER STATE (for debugging)                         │");
        Log.i(TAG, "├─────────────────────────────────────────────────────────────┤");

        // Query key mixer controls
        String[] controlsToCheck = {
            "MultiMedia1 Mixer VOC_REC_DL",
            "MultiMedia1 Mixer VOC_REC_UL",
            "Voc Rec Config",
            "Incall_Music Audio Mixer MultiMedia2",
            "Voice Tx Device Mute",
            "Voice Rx Device Mute"
        };

        for (String control : controlsToCheck) {
            String value = execRootSync("tinymix '" + control + "' 2>/dev/null | head -1");
            if (value != null && !value.isEmpty()) {
                Log.i(TAG, "│ " + String.format("%-40s", control) + " = " + value.trim());
            }
        }

        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");
    }

    /**
     * Find the correct PCM device for voice call capture
     * On Qualcomm devices, MultiMedia1 with VOC_REC routing may use different device numbers
     */
    private int findCapturePCMDevice() {
        Log.i(TAG, "Discovering PCM capture devices...");

        // Read /proc/asound/pcm to find available devices
        String pcmList = execRootSync("cat /proc/asound/pcm 2>/dev/null");
        if (pcmList != null) {
            Log.i(TAG, "Available PCM devices:\n" + pcmList);

            // Look for MultiMedia or voice-related devices
            for (String line : pcmList.split("\n")) {
                // Format: "00-00: MultiMedia1 : MultiMedia1 : playback 1 : capture 1"
                if (line.toLowerCase().contains("multimedia1") && line.contains("capture")) {
                    try {
                        // Extract device number from "00-XX:"
                        String[] parts = line.split("-");
                        if (parts.length >= 2) {
                            String devPart = parts[1].split(":")[0].trim();
                            int device = Integer.parseInt(devPart);
                            Log.i(TAG, "Found MultiMedia1 capture on device " + device);
                            return device;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse PCM line: " + line);
                    }
                }
            }

            // Look for voice recording device
            for (String line : pcmList.split("\n")) {
                if (line.toLowerCase().contains("voc_rec") ||
                    line.toLowerCase().contains("voice_rec") ||
                    line.toLowerCase().contains("incall_rec")) {
                    try {
                        String[] parts = line.split("-");
                        if (parts.length >= 2) {
                            String devPart = parts[1].split(":")[0].trim();
                            int device = Integer.parseInt(devPart);
                            Log.i(TAG, "Found voice recording device " + device);
                            return device;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse PCM line: " + line);
                    }
                }
            }
        }

        // Check if specific devices are available
        // Common Qualcomm device mappings:
        // - Device 0: MultiMedia1 (default)
        // - Device 11: MultiMedia1 on some platforms
        // - Device 12: MultiMedia2
        int[] devicesToTry = {0, 11, 12, 4, 5};

        for (int device : devicesToTry) {
            String result = execRootSync("ls -la /dev/snd/pcmC0D" + device + "c 2>/dev/null");
            if (result != null && result.contains("pcm")) {
                Log.i(TAG, "PCM capture device " + device + " exists");
                return device;
            }
        }

        Log.w(TAG, "Could not determine best capture device, defaulting to 0");
        return 0;
    }

    /**
     * Check if audio buffer is silent (all zeros or very low amplitude)
     */
    private boolean isAudioSilent(byte[] buffer, int length) {
        return getMaxAmplitude(buffer, length) < 100;
    }

    /**
     * Fallback: Use AudioRecord with root permission override
     */
    private void captureWithAudioRecord() {
        Log.i(TAG, "Trying AudioRecord capture with permission override");

        try {
            // Override audio recording permission via appops
            execRoot("appops set com.shreeyash.gateway RECORD_AUDIO allow");
            Thread.sleep(100);

            int minBufferSize = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            );

            android.media.AudioRecord audioRecord = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBufferSize * 2, 4096)
            );

            if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed, trying VOICE_CALL source");
                audioRecord.release();

                // Try VOICE_CALL audio source (requires root permission)
                execRoot("appops set com.shreeyash.gateway OP_RECORD_AUDIO allow");

                audioRecord = new android.media.AudioRecord(
                    4, // VOICE_CALL = 4
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBufferSize * 2, 4096)
                );

                if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord VOICE_CALL init also failed");
                    return;
                }
            }

            Log.i(TAG, "AudioRecord initialized successfully");
            audioRecord.startRecording();

            byte[] pcmBuffer = new byte[BUFFER_SIZE];
            byte[] rtpPacket = new byte[RTP_HEADER_SIZE + FRAME_SIZE];
            int packetCount = 0;

            while (running && !Thread.interrupted()) {
                int bytesRead = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                if (bytesRead <= 0) {
                    Thread.sleep(5);
                    continue;
                }

                byte[] ulawData = pcmToUlaw(pcmBuffer, bytesRead);
                buildRTPHeader(rtpPacket);
                int payloadSize = Math.min(ulawData.length, FRAME_SIZE);
                System.arraycopy(ulawData, 0, rtpPacket, RTP_HEADER_SIZE, payloadSize);

                DatagramPacket packet = new DatagramPacket(
                    rtpPacket,
                    RTP_HEADER_SIZE + payloadSize,
                    remoteAddress,
                    remotePort
                );
                rtpSocket.send(packet);

                sequenceNumber++;
                timestamp += FRAME_SIZE;
                packetCount++;

                if (packetCount % 500 == 0) {
                    Log.d(TAG, "AudioRecord capture: sent " + packetCount + " RTP packets");
                }
            }

            audioRecord.stop();
            audioRecord.release();

        } catch (Exception e) {
            Log.e(TAG, "AudioRecord capture failed: " + e.getMessage(), e);
        }
    }

    /**
     * Receive RTP and play into voice call
     * Tries tinyplay first (more reliable for incall_music), then AudioTrack fallback
     *
     * This receives audio from PBX and injects it into the GSM call via Incall_Music
     */
    private void playbackLoop() {
        Log.i(TAG, "┌───────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ PLAYBACK LOOP: PBX Voice → RTP → Incall_Music → GSM       │");
        Log.i(TAG, "├───────────────────────────────────────────────────────────┤");
        Log.i(TAG, "│ Source: RTP from PBX (port " + String.format("%-5d", localRtpPort) + ")                          │");
        Log.i(TAG, "│ Dest:   Incall_Music → GSM party                          │");
        Log.i(TAG, "│ Codec:  G.711 μ-law @ 8kHz mono                           │");
        Log.i(TAG, "└───────────────────────────────────────────────────────────┘");

        // Try tinyplay first - it routes more reliably to incall_music on SM6150
        Log.i(TAG, "[PLAYBACK] Trying tinyplay (direct ALSA) first...");
        if (!playbackWithTinyplay()) {
            Log.w(TAG, "[PLAYBACK] ⚠ Tinyplay failed, falling back to AudioTrack");
            playbackWithAudioTrack();
        }

        Log.i(TAG, "[PLAYBACK] Playback loop ended");
    }

    /**
     * Use tinyplay for incall music injection - more reliable on Qualcomm
     * Routes directly through ALSA to the incall_music mixer path
     */
    private boolean playbackWithTinyplay() {
        Log.i(TAG, "Starting tinyplay for incall_music injection");

        Process tinyplayProc = null;
        DataOutputStream playbackOut = null;

        try {
            // Ensure incall music mixer is enabled for MultiMedia2
            execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1");

            // Start tinyplay reading from stdin
            // Device 1 typically maps to MultiMedia2 on SM6150
            String tinyplayCmd = String.format(
                "tinyplay /dev/stdin -D 0 -d 1 -c 1 -r %d -b 16",
                SAMPLE_RATE
            );
            Log.i(TAG, "Starting tinyplay: " + tinyplayCmd);

            tinyplayProc = Runtime.getRuntime().exec(new String[]{"su", "-c", tinyplayCmd});
            playbackOut = new DataOutputStream(tinyplayProc.getOutputStream());

            // Write WAV header
            byte[] wavHeader = createWavHeader();
            playbackOut.write(wavHeader);
            playbackOut.flush();

            byte[] rtpPacket = new byte[1500];
            DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length);
            int packetCount = 0;
            int timeoutCount = 0;
            int maxAmplitudeSeen = 0;
            long lastLogTime = System.currentTimeMillis();
            long startTime = System.currentTimeMillis();
            long lastPacketTime = 0;

            Log.i(TAG, "[PLAYBACK] Tinyplay ready, waiting for RTP packets from PBX...");

            while (running && !Thread.interrupted()) {
                try {
                    rtpSocket.receive(packet);
                    lastPacketTime = System.currentTimeMillis();
                    timeoutCount = 0; // Reset timeout counter

                    if (packet.getLength() < RTP_HEADER_SIZE) continue;

                    int payloadLength = packet.getLength() - RTP_HEADER_SIZE;
                    byte[] ulawData = new byte[payloadLength];
                    System.arraycopy(rtpPacket, RTP_HEADER_SIZE, ulawData, 0, payloadLength);

                    // Convert u-law to PCM
                    byte[] pcmData = ulawToPcm(ulawData);

                    // Track amplitude
                    int amplitude = getMaxAmplitude(pcmData, pcmData.length);
                    if (amplitude > maxAmplitudeSeen) maxAmplitudeSeen = amplitude;

                    // Write to tinyplay
                    playbackOut.write(pcmData);
                    playbackOut.flush();

                    packetCount++;

                    // First packet log
                    if (packetCount == 1) {
                        Log.i(TAG, "[PLAYBACK] ✓ First RTP packet received from PBX!");
                    }

                    // Detailed logging every 5 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime >= 5000) {
                        long elapsed = (now - startTime) / 1000;
                        Log.i(TAG, String.format("[PLAYBACK] Stats @ %ds: pkts=%d, maxAmp=%d, src=port %d",
                            elapsed, packetCount, maxAmplitudeSeen, localRtpPort));
                        maxAmplitudeSeen = 0;
                        lastLogTime = now;
                    }

                } catch (java.net.SocketTimeoutException e) {
                    timeoutCount++;
                    // Warn if no packets for too long
                    if (timeoutCount == 5) {
                        Log.w(TAG, "[PLAYBACK] ⚠ No RTP from PBX for 5+ seconds - is PBX sending?");
                    }
                }
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Tinyplay playback error: " + e.getMessage(), e);
            return false;

        } finally {
            try {
                if (playbackOut != null) playbackOut.close();
            } catch (Exception e) {}
            if (tinyplayProc != null) tinyplayProc.destroy();
        }
    }

    /**
     * Create minimal WAV header for tinyplay
     */
    private byte[] createWavHeader() {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int dataSize = Integer.MAX_VALUE - 44; // Large number for streaming

        // RIFF header
        header.put("RIFF".getBytes());
        header.putInt(dataSize + 36); // File size - 8
        header.put("WAVE".getBytes());

        // fmt chunk
        header.put("fmt ".getBytes());
        header.putInt(16); // Chunk size
        header.putShort((short) 1); // Audio format (PCM)
        header.putShort((short) CHANNELS);
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * CHANNELS * 2); // Byte rate
        header.putShort((short) (CHANNELS * 2)); // Block align
        header.putShort((short) BITS_PER_SAMPLE);

        // data chunk
        header.put("data".getBytes());
        header.putInt(dataSize);

        return header.array();
    }

    /**
     * Use Android's AudioTrack for playback with USAGE_VOICE_COMMUNICATION
     * This routes audio through the incall_music path for voice call injection
     */
    private void playbackWithAudioTrack() {
        Log.i(TAG, "Using AudioTrack for incall music playback");

        try {
            // Grant audio permissions via appops
            execRoot("appops set com.shreeyash.gateway PLAY_AUDIO allow");

            // Ensure incall music routing is enabled (use MultiMedia2 to avoid echo)
            execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1");

            int minBufferSize = android.media.AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            );

            // Build AudioAttributes for incall music injection
            // USAGE_VOICE_COMMUNICATION routes through the voice call path
            android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY)
                .build();

            android.media.AudioFormat audioFormat = new android.media.AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build();

            android.media.AudioTrack audioTrack = new android.media.AudioTrack(
                audioAttributes,
                audioFormat,
                Math.max(minBufferSize * 4, 8192),  // Larger buffer for stability
                android.media.AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            if (audioTrack.getState() != android.media.AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack VOICE_COMMUNICATION failed, trying INCALL_MUSIC");
                audioTrack.release();

                // Try with USAGE_ASSISTANCE_SONIFICATION which can route to incall
                audioAttributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

                audioTrack = new android.media.AudioTrack(
                    audioAttributes,
                    audioFormat,
                    Math.max(minBufferSize * 4, 8192),
                    android.media.AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                );

                if (audioTrack.getState() != android.media.AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack init failed, trying legacy stream");
                    playbackWithLegacyAudioTrack();
                    return;
                }
            }

            Log.i(TAG, "AudioTrack initialized for incall playback");
            audioTrack.play();

            byte[] rtpPacket = new byte[1500];
            DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length);
            int packetCount = 0;

            while (running && !Thread.interrupted()) {
                try {
                    rtpSocket.receive(packet);

                    if (packet.getLength() < RTP_HEADER_SIZE) continue;

                    int payloadLength = packet.getLength() - RTP_HEADER_SIZE;
                    byte[] ulawData = new byte[payloadLength];
                    System.arraycopy(rtpPacket, RTP_HEADER_SIZE, ulawData, 0, payloadLength);

                    byte[] pcmData = ulawToPcm(ulawData);
                    int written = audioTrack.write(pcmData, 0, pcmData.length);

                    packetCount++;
                    if (packetCount % 500 == 0) {
                        Log.d(TAG, "AudioTrack playback: " + packetCount + " packets, last write: " + written);
                    }

                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout
                }
            }

            audioTrack.stop();
            audioTrack.release();

        } catch (Exception e) {
            Log.e(TAG, "AudioTrack playback failed: " + e.getMessage(), e);
        }
    }

    /**
     * Legacy AudioTrack playback using STREAM_VOICE_CALL
     */
    private void playbackWithLegacyAudioTrack() {
        Log.i(TAG, "Using legacy AudioTrack with STREAM_VOICE_CALL");

        try {
            int minBufferSize = android.media.AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            );

            @SuppressWarnings("deprecation")
            android.media.AudioTrack audioTrack = new android.media.AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBufferSize * 2, 4096),
                android.media.AudioTrack.MODE_STREAM
            );

            if (audioTrack.getState() != android.media.AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "Legacy AudioTrack init failed");
                return;
            }

            Log.i(TAG, "Legacy AudioTrack initialized");
            audioTrack.play();

            byte[] rtpPacket = new byte[1500];
            DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length);
            int packetCount = 0;

            while (running && !Thread.interrupted()) {
                try {
                    rtpSocket.receive(packet);

                    if (packet.getLength() < RTP_HEADER_SIZE) continue;

                    int payloadLength = packet.getLength() - RTP_HEADER_SIZE;
                    byte[] ulawData = new byte[payloadLength];
                    System.arraycopy(rtpPacket, RTP_HEADER_SIZE, ulawData, 0, payloadLength);

                    byte[] pcmData = ulawToPcm(ulawData);
                    audioTrack.write(pcmData, 0, pcmData.length);

                    packetCount++;
                    if (packetCount % 500 == 0) {
                        Log.d(TAG, "Legacy AudioTrack playback: " + packetCount + " packets");
                    }

                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout
                }
            }

            audioTrack.stop();
            audioTrack.release();

        } catch (Exception e) {
            Log.e(TAG, "Legacy AudioTrack playback failed: " + e.getMessage(), e);
        }
    }

    // ==================== RTP and CODEC ====================

    private void buildRTPHeader(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.put(RTP_VERSION);
        buffer.put(PAYLOAD_TYPE_PCMU);
        buffer.putShort((short) sequenceNumber);
        buffer.putInt((int) timestamp);
        buffer.putInt(ssrc);
    }

    // G.711 u-law encoding table
    private static final int[] ULAW_EXP_TABLE = {
        0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7
    };

    private byte[] pcmToUlaw(byte[] pcmData, int length) {
        byte[] ulaw = new byte[length / 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < ulaw.length && buffer.remaining() >= 2; i++) {
            short sample = buffer.getShort();
            ulaw[i] = linearToUlaw(sample);
        }

        return ulaw;
    }

    private byte[] ulawToPcm(byte[] ulawData) {
        byte[] pcm = new byte[ulawData.length * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);

        for (byte ulaw : ulawData) {
            short sample = ulawToLinear(ulaw);
            buffer.putShort(sample);
        }

        return pcm;
    }

    private byte linearToUlaw(short sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = (short) -sample;
        if (sample > 32635) sample = 32635;

        sample = (short) (sample + 0x84);
        int exponent = ULAW_EXP_TABLE[(sample >> 7) & 0xFF];
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        int ulawByte = ~(sign | (exponent << 4) | mantissa);

        return (byte) ulawByte;
    }

    private short ulawToLinear(byte ulawByte) {
        ulawByte = (byte) ~ulawByte;
        int sign = ulawByte & 0x80;
        int exponent = (ulawByte >> 4) & 0x07;
        int mantissa = ulawByte & 0x0F;

        int sample = ((mantissa << 3) + 0x84) << exponent;
        if (sign != 0) sample = -sample;

        return (short) sample;
    }

    public boolean isRunning() {
        return running;
    }
}
