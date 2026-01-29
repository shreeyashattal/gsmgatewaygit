package com.shreeyash.gateway;

import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RootAudioRouter - Root-level audio routing for GSM<->SIP Gateway
 *
 * Uses tinymix to configure Qualcomm audio HAL for:
 * 1. CAPTURE: GSM party voice -> our app (via VOC_REC_DL = downlink from modem)
 * 2. INJECTION: PBX/RTP audio -> GSM modem (via Incall_Music mixer path)
 * 3. MUTE: Phone speaker (volume=0) and microphone (TX mute)
 *
 * Telephony terminology:
 * - Uplink (UL) = phone TO network = what LOCAL user says (mic)
 * - Downlink (DL) = network TO phone = what REMOTE/GSM party says
 *
 * Audio Flow:
 * ┌─────────────────────────────────────────────────────────────────────────────────┐
 * │ GSM Party Voice → Modem → VOC_REC_DL → MultiMedia1 → AudioRecord → RTP → PBX   │
 * │ PBX Voice → RTP → AudioTrack → MultiMedia2 → Incall_Music → Modem → GSM Party  │
 * │                                                                                 │
 * │ Phone Speaker: MUTED (RX volumes = 0, output switches disabled)                │
 * │ Phone Mic: MUTED (Voice Tx Device Mute = 1, TX volumes = 0)                    │
 * │                                                                                 │
 * │ NOTE: We do NOT use "Voice Rx Device Mute" as it blocks capture path!          │
 * └─────────────────────────────────────────────────────────────────────────────────┘
 *
 * Tested on: Qualcomm SM6150/SM8150/SM8250/SDM845 platforms
 */
public class RootAudioRouter {

    private static final String TAG = "RootAudioRouter";

    private final int simSlot;
    private boolean isRouting = false;
    private boolean initialized = false;

    // Detected mixer controls
    private Set<String> availableMixerControls = new HashSet<>();
    private String detectedPlatform = "unknown";

    // State tracking for cleanup
    private boolean captureMixerSet = false;
    private boolean injectionMixerSet = false;
    private boolean speakerMuted = false;
    private boolean micMuted = false;

    // Persistent root shell (avoids repeated permission pop-ups)
    private static Process persistentShell = null;
    private static DataOutputStream shellInput = null;
    private static BufferedReader shellOutput = null;
    private static final Object shellLock = new Object();

    public RootAudioRouter(int simSlot) {
        this.simSlot = simSlot;
    }

    /**
     * Initialize: detect platform and available mixer controls
     */
    public boolean init() {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║       INITIALIZING ROOT AUDIO ROUTER                       ║");
        Log.i(TAG, "║       SIM Slot: " + simSlot + "                                          ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        // Detect platform
        detectedPlatform = detectPlatform();
        Log.i(TAG, "Detected platform: " + detectedPlatform);

        // Discover available mixer controls
        discoverMixerControls();

        if (availableMixerControls.isEmpty()) {
            Log.e(TAG, "No mixer controls found - tinymix may not be available");
            return false;
        }

        Log.i(TAG, "Found " + availableMixerControls.size() + " mixer controls");
        initialized = true;

        // Log key controls for debugging
        logKeyControls();

        return true;
    }

    /**
     * Start audio routing for gateway mode
     */
    public boolean start() {
        if (!initialized) {
            Log.e(TAG, "RootAudioRouter not initialized - call init() first");
            return false;
        }

        if (isRouting) {
            Log.w(TAG, "Audio routing already active");
            return true;
        }

        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║       STARTING GATEWAY AUDIO ROUTING                       ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        boolean success = true;

        // Step 1: Mute phone speaker (prevents local playback)
        if (!mutePhoneSpeaker()) {
            Log.w(TAG, "Could not mute phone speaker - audio may leak locally");
        }

        // Step 2: Mute phone microphone (prevents local mic pickup)
        if (!mutePhoneMicrophone()) {
            Log.w(TAG, "Could not mute phone mic - may have echo");
        }

        // Step 3: Set up capture path (GSM party voice -> our app)
        if (!setupCapturePath()) {
            Log.w(TAG, "Capture path setup incomplete - may still work with CAPTURE_AUDIO_OUTPUT");
        }

        // Step 4: Set up injection path (RTP audio -> GSM modem)
        if (!setupInjectionPath()) {
            Log.w(TAG, "Injection path setup failed - PBX audio won't reach GSM party");
            success = false;
        }

        isRouting = true;

        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║       GATEWAY AUDIO ROUTING ACTIVE                         ║");
        Log.i(TAG, "║       Speaker muted: " + (speakerMuted ? "YES" : "NO ") + "                                ║");
        Log.i(TAG, "║       Mic muted: " + (micMuted ? "YES" : "NO ") + "                                    ║");
        Log.i(TAG, "║       Capture path: " + (captureMixerSet ? "SET" : "N/A") + "                                ║");
        Log.i(TAG, "║       Injection path: " + (injectionMixerSet ? "SET" : "N/A") + "                              ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        return success;
    }

    /**
     * Stop audio routing and restore normal state
     */
    public void stop() {
        if (!isRouting) {
            return;
        }

        Log.i(TAG, "Stopping gateway audio routing...");

        // Restore speaker
        if (speakerMuted) {
            unmutePhoneSpeaker();
        }

        // Restore microphone
        if (micMuted) {
            unmutePhoneMicrophone();
        }

        // Restore capture path
        if (captureMixerSet) {
            restoreCapturePath();
        }

        // Restore injection path
        if (injectionMixerSet) {
            restoreInjectionPath();
        }

        isRouting = false;
        Log.i(TAG, "Gateway audio routing stopped");
    }

    // ==================== PLATFORM DETECTION ====================

    private String detectPlatform() {
        String platform = execRootSync("getprop ro.board.platform");
        if (platform != null) {
            return platform.trim().toLowerCase();
        }
        return "unknown";
    }

    // ==================== MIXER CONTROL DISCOVERY ====================

    private void discoverMixerControls() {
        String output = execRootSync("tinymix");
        if (output == null) {
            Log.e(TAG, "tinymix not available or no root access");
            return;
        }

        // Parse tinymix output to find control names
        // Format on Qualcomm: "ctl_num\ttype\tnum\tname\tvalue"
        // Example: "392	BOOL	2	MultiMedia1 Mixer VOC_REC_DL             Off Off"
        for (String line : output.split("\n")) {
            // Skip header lines and error lines
            if (line.startsWith("Number") || line.startsWith("Mixer") ||
                line.startsWith("ctl") || line.startsWith("Failed") ||
                line.trim().isEmpty()) {
                continue;
            }

            // Split by tab - name is in 4th column (index 3)
            String[] parts = line.split("\t");
            if (parts.length >= 4) {
                // Name is in column 4, but may have trailing value
                String controlName = parts[3].trim();
                // Remove trailing value (anything after multiple spaces)
                int valueStart = controlName.indexOf("  ");
                if (valueStart > 0) {
                    controlName = controlName.substring(0, valueStart).trim();
                }
                if (!controlName.isEmpty()) {
                    availableMixerControls.add(controlName);
                }
            }
        }

        Log.i(TAG, "Sample controls found:");
        int count = 0;
        for (String ctrl : availableMixerControls) {
            if (ctrl.contains("VOC_REC") || ctrl.contains("Incall") ||
                ctrl.contains("Voice") || ctrl.contains("MultiMedia")) {
                Log.i(TAG, "  " + ctrl);
                if (++count >= 10) break;
            }
        }
    }

    private boolean hasControl(String pattern) {
        String patternLower = pattern.toLowerCase();
        for (String control : availableMixerControls) {
            if (control.toLowerCase().contains(patternLower)) {
                return true;
            }
        }
        return false;
    }

    private String findControl(String pattern) {
        String patternLower = pattern.toLowerCase();
        for (String control : availableMixerControls) {
            if (control.toLowerCase().contains(patternLower)) {
                return control;
            }
        }
        return null;
    }

    private void logKeyControls() {
        Log.i(TAG, "Key mixer controls available:");
        Log.i(TAG, "  VOC_REC: " + (hasControl("VOC_REC") ? "YES" : "NO"));
        Log.i(TAG, "  Incall_Music: " + (hasControl("Incall_Music") ? "YES" : "NO"));
        Log.i(TAG, "  Voice Tx Device Mute: " + (hasControl("Voice Tx Device Mute") ? "YES" : "NO"));
        Log.i(TAG, "  Voice Rx Device Mute: " + (hasControl("Voice Rx Device Mute") ? "YES" : "NO"));
        Log.i(TAG, "  MultiMedia1: " + (hasControl("MultiMedia1") ? "YES" : "NO"));
        Log.i(TAG, "  MultiMedia2: " + (hasControl("MultiMedia2") ? "YES" : "NO"));
        Log.i(TAG, "  VoiceMMode1: " + (hasControl("VoiceMMode1") ? "YES" : "NO"));
    }

    // ==================== SPEAKER MUTING ====================

    private boolean mutePhoneSpeaker() {
        Log.i(TAG, "Muting phone speaker (keeping RX path active for capture)...");
        boolean success = false;

        // IMPORTANT: Do NOT use "Voice Rx Device Mute" - it blocks the entire RX path
        // which prevents us from capturing the GSM party's voice via VOICE_DOWNLINK.
        // Instead, we only mute the output stage (volumes) while keeping the path active.

        // Method 1: Set RX volumes to 0 (silences speaker but keeps path active for capture)
        String[] volumeControls = {
            "RX0 Digital Volume",
            "RX1 Digital Volume",
            "RX2 Digital Volume",
            "RX_RX0 Digital Volume",
            "RX_RX1 Digital Volume",
            "HPHL Volume",
            "HPHR Volume",
            "EAR PA Volume",
            "EAR SPKR PA Volume"
        };
        for (String ctrl : volumeControls) {
            if (hasControl(ctrl)) {
                if (setMixerControl(ctrl, "0")) {
                    success = true;
                    Log.i(TAG, "Muted: " + ctrl);
                }
            }
        }

        // Method 2: Disable speaker/earpiece output paths (keeps RX audio in capture path)
        String[] outputControls = {
            "HPHL_RDAC Switch",
            "HPHR_RDAC Switch",
            "EAR_RDAC Switch",
            "SpkrLeft COMP Switch",
            "SpkrRight COMP Switch",
            "HPHL Switch",
            "HPHR Switch",
            "EAR Switch"
        };
        for (String ctrl : outputControls) {
            if (hasControl(ctrl)) {
                if (setMixerControl(ctrl, "0")) {
                    success = true;
                    Log.i(TAG, "Disabled output: " + ctrl);
                }
            }
        }

        // Do NOT use Voice Rx Device Mute - it blocks capture!
        // if (hasControl("Voice Rx Device Mute")) { ... }

        speakerMuted = success;
        if (success) {
            Log.i(TAG, "Speaker muted via volume controls (RX path still active for capture)");
        }
        return success;
    }

    private void unmutePhoneSpeaker() {
        Log.i(TAG, "Unmuting phone speaker...");

        // Restore RX volumes to default
        String[] volumeControls = {
            "RX0 Digital Volume",
            "RX1 Digital Volume",
            "RX2 Digital Volume",
            "RX_RX0 Digital Volume",
            "RX_RX1 Digital Volume",
            "HPHL Volume",
            "HPHR Volume",
            "EAR PA Volume",
            "EAR SPKR PA Volume"
        };
        for (String ctrl : volumeControls) {
            if (hasControl(ctrl)) {
                setMixerControl(ctrl, "84"); // Default value
            }
        }

        // Re-enable speaker/earpiece output paths
        String[] outputControls = {
            "HPHL_RDAC Switch",
            "HPHR_RDAC Switch",
            "EAR_RDAC Switch",
            "SpkrLeft COMP Switch",
            "SpkrRight COMP Switch",
            "HPHL Switch",
            "HPHR Switch",
            "EAR Switch"
        };
        for (String ctrl : outputControls) {
            if (hasControl(ctrl)) {
                setMixerControl(ctrl, "1");
            }
        }

        speakerMuted = false;
    }

    // ==================== MICROPHONE MUTING ====================

    private boolean mutePhoneMicrophone() {
        Log.i(TAG, "Muting phone microphone...");
        boolean success = false;

        // Method 1: Voice Tx Device Mute (most reliable on Qualcomm)
        if (hasControl("Voice Tx Device Mute")) {
            success = setMixerControl("Voice Tx Device Mute", "1");
            if (success) {
                Log.i(TAG, "Mic muted via Voice Tx Device Mute");
            }
        }

        // Method 2: TX path mutes
        String[] txControls = {
            "TX_CDC_DMA_TX_3_Voice Mixer VoiceMMode1",
            "TX_CDC_DMA_TX_3_Voice Mixer VoiceMMode2",
            "SLIM_0_TX_Voice Mixer VoiceMMode1",
            "TX_AIF1_CAP Mixer DEC0",
            "TX_AIF1_CAP Mixer DEC1"
        };

        for (String ctrl : txControls) {
            if (hasControl(ctrl.split(" ")[0])) {
                if (setMixerControl(ctrl, "0")) {
                    success = true;
                }
            }
        }

        // Method 3: Set TX/ADC volumes to 0
        String[] volumeControls = {"ADC1 Volume", "ADC2 Volume", "ADC3 Volume",
                                   "TX0 Digital Volume", "TX1 Digital Volume"};
        for (String ctrl : volumeControls) {
            if (hasControl(ctrl)) {
                setMixerControl(ctrl, "0");
            }
        }

        // Method 4: Disconnect TX input
        if (hasControl("TX0 Input")) {
            setMixerControl("TX0 Input", "ZERO");
        }
        if (hasControl("TX1 Input")) {
            setMixerControl("TX1 Input", "ZERO");
        }

        micMuted = success;
        return success;
    }

    private void unmutePhoneMicrophone() {
        Log.i(TAG, "Unmuting phone microphone...");

        if (hasControl("Voice Tx Device Mute")) {
            setMixerControl("Voice Tx Device Mute", "0");
        }

        // Restore ADC volumes
        String[] volumeControls = {"ADC1 Volume", "ADC2 Volume"};
        for (String ctrl : volumeControls) {
            if (hasControl(ctrl)) {
                setMixerControl(ctrl, "84"); // Default
            }
        }

        micMuted = false;
    }

    // ==================== CAPTURE PATH (GSM -> App) ====================

    private boolean setupCapturePath() {
        Log.i(TAG, "Setting up capture path (GSM party voice -> app)...");
        boolean success = false;

        // Telephony terminology:
        // - Uplink (UL) = from phone TO network = what LOCAL user says (mic)
        // - Downlink (DL) = from network TO phone = what REMOTE/GSM party says
        //
        // For GSM-SIP gateway, we want to capture what GSM PARTY says = DOWNLINK (DL)
        // Key controls: VOC_REC_DL (downlink = what GSM party says)

        // Method 1: VOC_REC path (Bengal/SM6150 and similar)
        if (hasControl("VOC_REC")) {
            // Enable DOWNLINK capture (GSM party's voice - what they say)
            if (setMixerControl("MultiMedia1 Mixer VOC_REC_DL", "1")) {
                Log.i(TAG, "Enabled VOC_REC_DL capture (GSM party voice)");
                success = true;
            }

            // Disable UPLINK capture (we don't want local mic - we're muting it anyway)
            setMixerControl("MultiMedia1 Mixer VOC_REC_UL", "0");

            // Set voice recording config if available
            if (hasControl("Voc Rec Config")) {
                setMixerControl("Voc Rec Config", "2"); // 2 = DL only
            }
        }

        // Method 2: VoiceMMode1 path (SM8150/SM8250 and similar)
        if (!success && hasControl("VoiceMMode1")) {
            if (setMixerControl("SLIMBUS_0_TX Voice Mixer VoiceMMode1", "1")) {
                Log.i(TAG, "Enabled SLIMBUS VoiceMMode1 capture");
                success = true;
            }

            // Also try MultiMedia1 routing
            setMixerControl("MultiMedia1 Mixer SLIM_0_TX", "1");
        }

        // Method 3: Generic voice capture
        if (!success) {
            String[] captureControls = {
                "MultiMedia1 Mixer VoiceMMode1",
                "MultiMedia1 Mixer VOICEMMODE1",
                "Audio Stream Capture",
                "Voice Capture"
            };

            for (String ctrl : captureControls) {
                if (hasControl(ctrl.split(" ")[0])) {
                    if (setMixerControl(ctrl, "1")) {
                        Log.i(TAG, "Enabled capture via: " + ctrl);
                        success = true;
                        break;
                    }
                }
            }
        }

        captureMixerSet = success;
        return success;
    }

    private void restoreCapturePath() {
        Log.i(TAG, "Restoring capture path...");

        if (hasControl("VOC_REC")) {
            setMixerControl("MultiMedia1 Mixer VOC_REC_UL", "0");
            setMixerControl("MultiMedia1 Mixer VOC_REC_DL", "0");
        }

        captureMixerSet = false;
    }

    // ==================== INJECTION PATH (App -> GSM) ====================

    private boolean setupInjectionPath() {
        Log.i(TAG, "Setting up injection path (RTP audio -> GSM party)...");
        boolean success = false;

        // The injection path routes AudioTrack output into the voice call
        // Key control: Incall_Music Audio Mixer MultiMedia2

        // Method 1: Incall_Music (most common on Qualcomm)
        if (hasControl("Incall_Music")) {
            // Use MultiMedia2 for injection (MultiMedia1 is for capture)
            if (setMixerControl("Incall_Music Audio Mixer MultiMedia2", "1")) {
                Log.i(TAG, "Enabled Incall_Music injection via MultiMedia2");
                success = true;
            }

            // Also try MultiMedia9 as backup
            if (hasControl("MultiMedia9")) {
                setMixerControl("Incall_Music Audio Mixer MultiMedia9", "1");
            }
        }

        // Method 2: Voice playback mixer
        if (!success && hasControl("Voice_Playback")) {
            if (setMixerControl("Voice_Playback_TX Mixer MultiMedia2", "1")) {
                Log.i(TAG, "Enabled Voice_Playback injection");
                success = true;
            }
        }

        // Method 3: SLIMBUS injection
        if (!success && hasControl("SLIMBUS")) {
            String[] slimbusControls = {
                "SLIMBUS_0_RX Audio Mixer MultiMedia2",
                "SLIM_0_RX Audio Mixer MultiMedia2"
            };

            for (String ctrl : slimbusControls) {
                if (hasControl(ctrl.split(" ")[0])) {
                    if (setMixerControl(ctrl, "1")) {
                        Log.i(TAG, "Enabled SLIMBUS injection");
                        success = true;
                        break;
                    }
                }
            }
        }

        // Method 4: CS Voice path
        if (!success) {
            String[] voiceControls = {
                "Voice Playback",
                "CS Voice Playback",
                "Voip Playback"
            };

            for (String ctrl : voiceControls) {
                if (hasControl(ctrl)) {
                    if (setMixerControl(ctrl + " Mixer MultiMedia2", "1")) {
                        Log.i(TAG, "Enabled injection via: " + ctrl);
                        success = true;
                        break;
                    }
                }
            }
        }

        injectionMixerSet = success;

        if (!success) {
            Log.e(TAG, "╔════════════════════════════════════════════════════════════╗");
            Log.e(TAG, "║ INJECTION PATH SETUP FAILED                                ║");
            Log.e(TAG, "║                                                            ║");
            Log.e(TAG, "║ Could not find a suitable mixer control for injecting      ║");
            Log.e(TAG, "║ RTP audio into the voice call.                             ║");
            Log.e(TAG, "║                                                            ║");
            Log.e(TAG, "║ PBX audio will NOT be heard by GSM party.                  ║");
            Log.e(TAG, "║                                                            ║");
            Log.e(TAG, "║ Try running 'tinymix' via adb to find the correct control. ║");
            Log.e(TAG, "╚════════════════════════════════════════════════════════════╝");
        }

        return success;
    }

    private void restoreInjectionPath() {
        Log.i(TAG, "Restoring injection path...");

        if (hasControl("Incall_Music")) {
            setMixerControl("Incall_Music Audio Mixer MultiMedia2", "0");
            if (hasControl("MultiMedia9")) {
                setMixerControl("Incall_Music Audio Mixer MultiMedia9", "0");
            }
        }

        injectionMixerSet = false;
    }

    // ==================== MIXER CONTROL UTILITIES ====================

    private boolean setMixerControl(String control, String value) {
        String result = execRootSync("tinymix '" + control + "' '" + value + "'");

        // Verify the setting took effect
        String verify = execRootSync("tinymix '" + control + "'");
        if (verify != null && verify.contains(value)) {
            Log.d(TAG, "Mixer OK: " + control + " = " + value);
            return true;
        }

        // Some controls accept numeric values differently
        if (result != null && !result.toLowerCase().contains("error") &&
            !result.toLowerCase().contains("invalid")) {
            Log.d(TAG, "Mixer set (unverified): " + control + " = " + value);
            return true;
        }

        Log.w(TAG, "Mixer FAILED: " + control + " = " + value);
        return false;
    }

    /**
     * Initialize persistent root shell (call once, reuse for all commands)
     * This prevents repeated "root permission granted" pop-ups
     */
    private static boolean initPersistentShell() {
        synchronized (shellLock) {
            if (persistentShell != null) {
                // Check if still alive
                try {
                    persistentShell.exitValue();
                    // If we get here, process has exited
                    Log.w(TAG, "Persistent shell died, restarting...");
                    persistentShell = null;
                    shellInput = null;
                    shellOutput = null;
                } catch (IllegalThreadStateException e) {
                    // Still running - good
                    return true;
                }
            }

            try {
                Log.i(TAG, "Starting persistent root shell...");
                persistentShell = Runtime.getRuntime().exec("su");
                shellInput = new DataOutputStream(persistentShell.getOutputStream());
                shellOutput = new BufferedReader(new InputStreamReader(persistentShell.getInputStream()));

                // Send a test command to verify root access
                shellInput.writeBytes("echo ROOT_SHELL_READY\n");
                shellInput.flush();

                // Read response with timeout
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < 3000) {
                    if (shellOutput.ready()) {
                        String line = shellOutput.readLine();
                        if (line != null && line.contains("ROOT_SHELL_READY")) {
                            Log.i(TAG, "Persistent root shell ready");
                            return true;
                        }
                    }
                    Thread.sleep(50);
                }

                Log.w(TAG, "Root shell started but no response");
                return true; // May still work

            } catch (Exception e) {
                Log.e(TAG, "Failed to start persistent root shell: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Execute root command using persistent shell
     */
    private String execRootSync(String command) {
        synchronized (shellLock) {
            // Ensure shell is running
            if (!initPersistentShell()) {
                Log.e(TAG, "No root shell available");
                return null;
            }

            try {
                // Use a unique marker to detect end of output
                String endMarker = "END_CMD_" + System.currentTimeMillis();

                // Send command followed by echo of end marker
                shellInput.writeBytes(command + "\n");
                shellInput.writeBytes("echo " + endMarker + "\n");
                shellInput.flush();

                // Read output until we see the end marker
                StringBuilder output = new StringBuilder();
                long startTime = System.currentTimeMillis();

                while (System.currentTimeMillis() - startTime < 5000) { // 5 second timeout
                    if (shellOutput.ready()) {
                        String line = shellOutput.readLine();
                        if (line == null) break;

                        if (line.contains(endMarker)) {
                            // Found end marker, we're done
                            break;
                        }
                        output.append(line).append("\n");
                    } else {
                        // Brief wait if no data available
                        Thread.sleep(10);
                    }
                }

                return output.toString().trim();

            } catch (Exception e) {
                Log.e(TAG, "Root command failed: " + command + " - " + e.getMessage());
                // Shell may be broken, reset it
                closePersistentShell();
                return null;
            }
        }
    }

    /**
     * Close the persistent shell (call when completely done with root commands)
     */
    public static void closePersistentShell() {
        synchronized (shellLock) {
            try {
                if (shellInput != null) {
                    shellInput.writeBytes("exit\n");
                    shellInput.flush();
                    shellInput.close();
                    shellInput = null;
                }
                if (shellOutput != null) {
                    shellOutput.close();
                    shellOutput = null;
                }
                if (persistentShell != null) {
                    persistentShell.waitFor();
                    persistentShell.destroy();
                    persistentShell = null;
                }
                Log.i(TAG, "Persistent root shell closed");
            } catch (Exception e) {
                Log.w(TAG, "Error closing persistent shell: " + e.getMessage());
            }
        }
    }

    // ==================== DIAGNOSTICS ====================

    /**
     * Get current mixer state for debugging
     */
    public String getMixerState() {
        return execRootSync("tinymix");
    }

    /**
     * Test if audio routing is possible
     */
    public boolean testCapabilities() {
        Log.i(TAG, "Testing audio routing capabilities...");

        if (!initialized) {
            init();
        }

        boolean canCapture = hasControl("VOC_REC") || hasControl("VoiceMMode1");
        boolean canInject = hasControl("Incall_Music") || hasControl("Voice_Playback");
        boolean canMuteSpeaker = hasControl("Voice Rx Device Mute") || hasControl("RX_CDC_DMA");
        boolean canMuteMic = hasControl("Voice Tx Device Mute") || hasControl("TX_CDC_DMA");

        Log.i(TAG, "Capabilities:");
        Log.i(TAG, "  Can capture GSM audio: " + canCapture);
        Log.i(TAG, "  Can inject RTP audio: " + canInject);
        Log.i(TAG, "  Can mute speaker: " + canMuteSpeaker);
        Log.i(TAG, "  Can mute mic: " + canMuteMic);

        return canCapture && canInject;
    }

    public boolean isRouting() {
        return isRouting;
    }

    public boolean isSpeakerMuted() {
        return speakerMuted;
    }

    public boolean isMicMuted() {
        return micMuted;
    }

    // ==================== PERIODIC MAINTENANCE ====================

    /**
     * Re-apply critical capture path settings.
     * Call this periodically during call to maintain audio routing.
     * Some Qualcomm audio HALs may reset mixer paths after a few seconds.
     */
    public void refreshCapturePath() {
        if (!isRouting) return;

        // Only refresh the most critical settings to minimize overhead
        if (hasControl("VOC_REC")) {
            execRootSync("tinymix 'MultiMedia1 Mixer VOC_REC_DL' '1'");
            execRootSync("tinymix 'Voc Rec Config' '2'");
        }
    }

    /**
     * Re-apply critical injection path settings.
     * Call this periodically during call to maintain audio routing.
     */
    public void refreshInjectionPath() {
        if (!isRouting) return;

        if (hasControl("Incall_Music")) {
            execRootSync("tinymix 'Incall_Music Audio Mixer MultiMedia2' '1'");
        }
    }

    /**
     * Refresh all critical audio paths.
     * Call every 3-5 seconds during call to maintain routing.
     */
    public void refreshAudioPaths() {
        if (!isRouting) {
            Log.d(TAG, "Not routing - skip refresh");
            return;
        }

        Log.d(TAG, "Refreshing audio paths...");
        refreshCapturePath();
        refreshInjectionPath();
    }
}
