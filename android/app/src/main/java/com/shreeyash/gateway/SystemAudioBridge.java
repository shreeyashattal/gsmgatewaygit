package com.shreeyash.gateway;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System Audio Bridge for GSM-SIP Gateway
 *
 * Uses Android's AudioRecord/AudioTrack APIs with VOICE_DOWNLINK audio source.
 * This approach requires the app to be installed as a privileged system app with
 * CAPTURE_AUDIO_OUTPUT permission (granted via Magisk module).
 *
 * Telephony terminology:
 * - Downlink (DL) = network TO phone = what GSM PARTY says
 * - Uplink (UL) = phone TO network = what local user says (we mute this)
 *
 * Audio Flow:
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │ GSM Party → Modem → VOICE_DOWNLINK → AudioRecord → μ-law → RTP → PBX       │
 * │ PBX → RTP → μ-law → AudioTrack → VOICE_CALL stream → Incall_Music → Modem  │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * Audio Sources (in order of preference):
 * 1. VOICE_DOWNLINK (3) - What GSM party says (preferred for gateway)
 * 2. VOICE_CALL (4) - Both directions mixed
 * 3. VOICE_UPLINK (2) - Local mic (not used - we mute it)
 *
 * RootAudioRouter sets up the mixer paths via tinymix:
 * - VOC_REC_DL → MultiMedia1 for capture
 * - Incall_Music → MultiMedia2 for injection
 *
 * References:
 * - BCR (Basic Call Recorder): https://github.com/chenxiaolong/BCR
 * - Android AudioRecord docs: https://developer.android.com/reference/android/media/AudioRecord
 */
public class SystemAudioBridge {
    private static final String TAG = "SystemAudioBridge";

    // Audio configuration (G.711 compatible)
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 160; // 20ms at 8kHz = 160 samples
    private static final int BYTES_PER_FRAME = FRAME_SIZE * 2; // 16-bit = 2 bytes per sample

    // RTP configuration
    private static final int RTP_HEADER_SIZE = 12;
    private static final int PAYLOAD_TYPE_PCMU = 0; // G.711 μ-law
    private static final int RTP_PACKET_SIZE = RTP_HEADER_SIZE + FRAME_SIZE;

    // Audio sources to try (in order of preference for GSM-SIP gateway)
    // Telephony terminology:
    // - VOICE_DOWNLINK (3) = What GSM party says (network → phone)
    // - VOICE_UPLINK (2) = What local user says (phone → network) - we mute this
    // - VOICE_CALL (4) = Both directions mixed
    //
    // RootAudioRouter sets up VOC_REC_DL mixer which maps to VOICE_DOWNLINK
    // Speaker is muted via volume controls (NOT Voice Rx Device Mute which blocks capture)
    private static final int[] CAPTURE_SOURCES = {
        3, // MediaRecorder.AudioSource.VOICE_DOWNLINK - GSM party's voice (preferred)
        4, // MediaRecorder.AudioSource.VOICE_CALL - Both directions mixed
        2, // MediaRecorder.AudioSource.VOICE_UPLINK - Local mic (shouldn't be needed)
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC
    };

    // State
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private DatagramSocket rtpSocket;

    // RTP state
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private long ssrc;

    // Remote endpoint
    private String remoteHost;
    private int remotePort;
    private InetAddress remoteAddress;

    // Local RTP port
    private final int localRtpPort;

    // Threads
    private Thread captureThread;
    private Thread playbackThread;

    // Listener for bridge events
    private BridgeListener listener;

    // Statistics
    private long capturePackets = 0;
    private long playbackPackets = 0;
    private int maxCaptureAmplitude = 0;
    private int maxPlaybackAmplitude = 0;
    private int usedAudioSource = -1;

    public interface BridgeListener {
        void onBridgeStarted();
        void onBridgeStopped();
        void onBridgeError(String error);
    }

    public SystemAudioBridge(int localRtpPort) {
        this.localRtpPort = localRtpPort;
        this.ssrc = (long) (Math.random() * 0xFFFFFFFFL);
    }

    public void setRemoteEndpoint(String host, int port) {
        this.remoteHost = host;
        this.remotePort = port;
    }

    public void setListener(BridgeListener listener) {
        this.listener = listener;
    }

    // Context for diagnostics (set via setContext)
    private static Context appContext;

    public static void setContext(Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * Start the audio bridge
     */
    public boolean start() {
        if (running.get()) {
            Log.w(TAG, "Bridge already running");
            return true;
        }

        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║       STARTING SYSTEM AUDIO BRIDGE                         ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
        Log.i(TAG, "Local RTP port: " + localRtpPort);
        Log.i(TAG, "Remote endpoint: " + remoteHost + ":" + remotePort);

        // Log diagnostics if context available
        if (appContext != null) {
            logDiagnostics(appContext);
        }

        try {
            // Resolve remote address
            remoteAddress = InetAddress.getByName(remoteHost);

            // Create RTP socket
            rtpSocket = new DatagramSocket(localRtpPort);
            rtpSocket.setSoTimeout(100); // 100ms timeout for receive
            Log.i(TAG, "RTP socket created on port " + localRtpPort);

            // Initialize audio capture
            if (!initializeCapture()) {
                Log.e(TAG, "Failed to initialize audio capture");
                cleanup();
                return false;
            }

            // Initialize audio playback
            if (!initializePlayback()) {
                Log.e(TAG, "Failed to initialize audio playback");
                cleanup();
                return false;
            }

            running.set(true);

            // Start capture thread (GSM → RTP → PBX)
            captureThread = new Thread(this::captureLoop, "AudioCapture");
            captureThread.start();

            // Start playback thread (PBX → RTP → GSM)
            playbackThread = new Thread(this::playbackLoop, "AudioPlayback");
            playbackThread.start();

            Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
            Log.i(TAG, "│ AUDIO BRIDGE STARTED SUCCESSFULLY                           │");
            Log.i(TAG, "│ Capture source: " + String.format("%-42s", audioSourceName(usedAudioSource)) + " │");
            Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");

            if (listener != null) {
                listener.onBridgeStarted();
            }

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio bridge: " + e.getMessage(), e);
            cleanup();
            if (listener != null) {
                listener.onBridgeError("Failed to start: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * Initialize audio capture with VOICE_CALL or VOICE_DOWNLINK source
     */
    private boolean initializeCapture() {
        Log.i(TAG, "Initializing audio capture...");
        Log.i(TAG, "CAPTURE_AUDIO_OUTPUT permission required for VOICE_CALL sources");

        int minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size for AudioRecord");
            minBufferSize = BYTES_PER_FRAME * 10;
        }

        int bufferSize = Math.max(minBufferSize * 2, BYTES_PER_FRAME * 20);
        Log.i(TAG, "AudioRecord buffer size: " + bufferSize);

        // Try each audio source
        for (int source : CAPTURE_SOURCES) {
            Log.i(TAG, "Trying audio source: " + audioSourceName(source) + " (" + source + ")");

            try {
                audioRecord = new AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    bufferSize
                );

                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    usedAudioSource = source;
                    Log.i(TAG, "✓ AudioRecord initialized with source: " + audioSourceName(source));
                    return true;
                } else {
                    Log.w(TAG, "✗ AudioRecord not initialized with source: " + audioSourceName(source));
                    audioRecord.release();
                    audioRecord = null;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "✗ SecurityException for source " + audioSourceName(source) +
                          ": " + e.getMessage());
                Log.w(TAG, "  This usually means CAPTURE_AUDIO_OUTPUT permission is not granted");
                Log.w(TAG, "  Install the app via Magisk module to grant this permission");
            } catch (Exception e) {
                Log.w(TAG, "✗ Failed with source " + audioSourceName(source) + ": " + e.getMessage());
            }
        }

        Log.e(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.e(TAG, "║ AUDIO CAPTURE INITIALIZATION FAILED                        ║");
        Log.e(TAG, "║                                                            ║");
        Log.e(TAG, "║ None of the audio sources could be initialized.            ║");
        Log.e(TAG, "║                                                            ║");
        Log.e(TAG, "║ To fix this:                                               ║");
        Log.e(TAG, "║ 1. Install the Magisk module from the app folder           ║");
        Log.e(TAG, "║ 2. Reboot your device                                      ║");
        Log.e(TAG, "║ 3. The app will be a privileged system app with            ║");
        Log.e(TAG, "║    CAPTURE_AUDIO_OUTPUT permission                         ║");
        Log.e(TAG, "╚════════════════════════════════════════════════════════════╝");

        return false;
    }

    /**
     * Initialize audio playback for injecting PBX audio into the call
     */
    private boolean initializePlayback() {
        Log.i(TAG, "Initializing audio playback...");

        int minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);

        if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            minBufferSize = BYTES_PER_FRAME * 10;
        }

        int bufferSize = Math.max(minBufferSize * 2, BYTES_PER_FRAME * 20);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use AudioAttributes for better control
                AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

                AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .setEncoding(AUDIO_FORMAT)
                    .build();

                audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            } else {
                // Legacy constructor for older Android
                audioTrack = new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                );
            }

            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                Log.i(TAG, "✓ AudioTrack initialized for voice call playback");
                return true;
            } else {
                Log.e(TAG, "AudioTrack not initialized");
                audioTrack.release();
                audioTrack = null;
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioTrack: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Stop the audio bridge
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        Log.i(TAG, "Stopping audio bridge...");

        // Interrupt threads
        if (captureThread != null) {
            captureThread.interrupt();
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
        }

        // Wait for threads to finish
        try {
            if (captureThread != null) captureThread.join(1000);
            if (playbackThread != null) playbackThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        cleanup();

        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ AUDIO BRIDGE STOPPED                                        │");
        Log.i(TAG, "│ Capture packets:  " + String.format("%-40d", capturePackets) + " │");
        Log.i(TAG, "│ Playback packets: " + String.format("%-40d", playbackPackets) + " │");
        Log.i(TAG, "│ Max capture amp:  " + String.format("%-40d", maxCaptureAmplitude) + " │");
        Log.i(TAG, "│ Max playback amp: " + String.format("%-40d", maxPlaybackAmplitude) + " │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");

        if (listener != null) {
            listener.onBridgeStopped();
        }
    }

    /**
     * Cleanup resources
     */
    private void cleanup() {
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) { /* ignore */ }
            audioRecord.release();
            audioRecord = null;
        }

        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception e) { /* ignore */ }
            audioTrack.release();
            audioTrack = null;
        }

        if (rtpSocket != null && !rtpSocket.isClosed()) {
            rtpSocket.close();
            rtpSocket = null;
        }
    }

    /**
     * Capture loop: Read from AudioRecord, encode to μ-law, send via RTP
     */
    private void captureLoop() {
        Log.i(TAG, "Capture loop started");
        Log.i(TAG, "Sending audio to: " + remoteHost + ":" + remotePort);

        byte[] pcmBuffer = new byte[BYTES_PER_FRAME];
        byte[] rtpPacket = new byte[RTP_PACKET_SIZE];

        try {
            audioRecord.startRecording();
            Log.i(TAG, "AudioRecord started recording");

            long startTime = System.currentTimeMillis();
            long lastLogTime = startTime;
            int silentPackets = 0;

            while (running.get() && !Thread.interrupted()) {
                // Read PCM audio
                int bytesRead = audioRecord.read(pcmBuffer, 0, BYTES_PER_FRAME);

                if (bytesRead < BYTES_PER_FRAME) {
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e(TAG, "AudioRecord: Invalid operation");
                        break;
                    } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioRecord: Bad value");
                        break;
                    }
                    Thread.sleep(5);
                    continue;
                }

                // Check amplitude
                int amplitude = getMaxAmplitude(pcmBuffer);
                if (amplitude > maxCaptureAmplitude) {
                    maxCaptureAmplitude = amplitude;
                }

                if (amplitude < 100) {
                    silentPackets++;
                } else {
                    silentPackets = 0;
                }

                // Build RTP packet
                buildRtpHeader(rtpPacket);

                // Encode PCM to μ-law
                for (int i = 0; i < FRAME_SIZE; i++) {
                    short sample = (short) ((pcmBuffer[i * 2 + 1] << 8) | (pcmBuffer[i * 2] & 0xFF));
                    rtpPacket[RTP_HEADER_SIZE + i] = linearToUlaw(sample);
                }

                // Send RTP packet
                DatagramPacket packet = new DatagramPacket(
                    rtpPacket, rtpPacket.length, remoteAddress, remotePort);
                rtpSocket.send(packet);

                capturePackets++;
                sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
                timestamp += FRAME_SIZE;

                // Log stats every 5 seconds
                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 5000) {
                    long elapsed = (now - startTime) / 1000;
                    Log.i(TAG, String.format("[CAPTURE] Stats @ %ds: pkts=%d, maxAmp=%d, silent=%d, dest=%s:%d",
                        elapsed, capturePackets, maxCaptureAmplitude, silentPackets,
                        remoteHost, remotePort));

                    if (silentPackets > 200) {
                        Log.w(TAG, "[CAPTURE] ⚠ Audio appears silent! Check if VOICE_CALL source is working.");
                    }

                    lastLogTime = now;
                    maxCaptureAmplitude = 0;
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                Log.e(TAG, "Capture error: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onBridgeError("Capture failed: " + e.getMessage());
                }
            }
        }

        Log.i(TAG, "Capture loop ended");
    }

    /**
     * Playback loop: Receive RTP, decode μ-law, write to AudioTrack
     */
    private void playbackLoop() {
        Log.i(TAG, "Playback loop started");
        Log.i(TAG, "Receiving audio on port: " + localRtpPort);

        byte[] receiveBuffer = new byte[2048];
        byte[] pcmBuffer = new byte[BYTES_PER_FRAME];

        try {
            audioTrack.play();
            Log.i(TAG, "AudioTrack started playing");

            long startTime = System.currentTimeMillis();
            long lastLogTime = startTime;

            while (running.get() && !Thread.interrupted()) {
                try {
                    // Receive RTP packet
                    DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    rtpSocket.receive(packet);

                    int length = packet.getLength();
                    if (length < RTP_HEADER_SIZE + 1) {
                        continue; // Too small
                    }

                    // Extract payload (skip RTP header)
                    int payloadLength = Math.min(length - RTP_HEADER_SIZE, FRAME_SIZE);

                    // Decode μ-law to PCM
                    for (int i = 0; i < payloadLength; i++) {
                        short sample = ulawToLinear(receiveBuffer[RTP_HEADER_SIZE + i]);
                        pcmBuffer[i * 2] = (byte) (sample & 0xFF);
                        pcmBuffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);

                        int amp = Math.abs(sample);
                        if (amp > maxPlaybackAmplitude) {
                            maxPlaybackAmplitude = amp;
                        }
                    }

                    // Write to AudioTrack
                    audioTrack.write(pcmBuffer, 0, payloadLength * 2);
                    playbackPackets++;

                    // Log stats every 5 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime >= 5000) {
                        long elapsed = (now - startTime) / 1000;
                        Log.i(TAG, String.format("[PLAYBACK] Stats @ %ds: pkts=%d, maxAmp=%d",
                            elapsed, playbackPackets, maxPlaybackAmplitude));
                        lastLogTime = now;
                        maxPlaybackAmplitude = 0;
                    }

                } catch (java.net.SocketTimeoutException e) {
                    // Expected - no data received within timeout
                    continue;
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                Log.e(TAG, "Playback error: " + e.getMessage(), e);
            }
        }

        Log.i(TAG, "Playback loop ended");
    }

    /**
     * Build RTP header
     */
    private void buildRtpHeader(byte[] packet) {
        // Version (2), Padding (0), Extension (0), CSRC count (0)
        packet[0] = (byte) 0x80;
        // Marker (0), Payload type (PCMU = 0)
        packet[1] = (byte) PAYLOAD_TYPE_PCMU;
        // Sequence number (big-endian)
        packet[2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        packet[3] = (byte) (sequenceNumber & 0xFF);
        // Timestamp (big-endian)
        packet[4] = (byte) ((timestamp >> 24) & 0xFF);
        packet[5] = (byte) ((timestamp >> 16) & 0xFF);
        packet[6] = (byte) ((timestamp >> 8) & 0xFF);
        packet[7] = (byte) (timestamp & 0xFF);
        // SSRC (big-endian)
        packet[8] = (byte) ((ssrc >> 24) & 0xFF);
        packet[9] = (byte) ((ssrc >> 16) & 0xFF);
        packet[10] = (byte) ((ssrc >> 8) & 0xFF);
        packet[11] = (byte) (ssrc & 0xFF);
    }

    /**
     * Get max amplitude from PCM buffer
     */
    private int getMaxAmplitude(byte[] buffer) {
        int max = 0;
        for (int i = 0; i < buffer.length - 1; i += 2) {
            int sample = Math.abs((short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF)));
            if (sample > max) max = sample;
        }
        return max;
    }

    // μ-law encoding table
    private static final int[] ULAW_ENCODE = new int[8192];
    private static final short[] ULAW_DECODE = new short[256];

    static {
        // Initialize μ-law tables
        for (int i = 0; i < 8192; i++) {
            ULAW_ENCODE[i] = encode_ulaw((short) ((i - 4096) << 3));
        }
        for (int i = 0; i < 256; i++) {
            ULAW_DECODE[i] = decode_ulaw((byte) i);
        }
    }

    private static int encode_ulaw(short sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = (short) -sample;
        if (sample > 32635) sample = 32635;
        sample = (short) (sample + 0x84);

        int exponent = 7;
        for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) ;

        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        return ~(sign | (exponent << 4) | mantissa) & 0xFF;
    }

    private static short decode_ulaw(byte ulaw) {
        int u = ~ulaw & 0xFF;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int sample = ((mantissa << 3) + 0x84) << exponent;
        sample -= 0x84;
        return (short) (sign != 0 ? -sample : sample);
    }

    private byte linearToUlaw(short sample) {
        return (byte) ULAW_ENCODE[(sample >> 3) + 4096];
    }

    private short ulawToLinear(byte ulaw) {
        return ULAW_DECODE[ulaw & 0xFF];
    }

    /**
     * Get audio source name
     */
    private String audioSourceName(int source) {
        switch (source) {
            case 0: return "DEFAULT";
            case 1: return "MIC";
            case 2: return "VOICE_UPLINK";
            case 3: return "VOICE_DOWNLINK";
            case 4: return "VOICE_CALL";
            case 5: return "CAMCORDER";
            case 6: return "VOICE_RECOGNITION";
            case 7: return "VOICE_COMMUNICATION";
            case 9: return "UNPROCESSED";
            case 10: return "VOICE_PERFORMANCE";
            default: return "UNKNOWN(" + source + ")";
        }
    }

    /**
     * Check if bridge is running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get the audio source that was successfully used
     */
    public int getUsedAudioSource() {
        return usedAudioSource;
    }

    /**
     * Get capture packet count
     */
    public long getCapturePackets() {
        return capturePackets;
    }

    /**
     * Get playback packet count
     */
    public long getPlaybackPackets() {
        return playbackPackets;
    }

    /**
     * Check if CAPTURE_AUDIO_OUTPUT permission is granted
     * This is a privileged permission that requires the app to be a system app
     */
    public static boolean hasCaptureAudioPermission(Context context) {
        int result = context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT");
        boolean hasPermission = (result == PackageManager.PERMISSION_GRANTED);
        Log.i(TAG, "CAPTURE_AUDIO_OUTPUT permission: " + (hasPermission ? "GRANTED" : "DENIED"));
        return hasPermission;
    }

    /**
     * Check if the app is installed as a system app
     */
    public static boolean isSystemApp(Context context) {
        try {
            int flags = context.getPackageManager()
                .getApplicationInfo(context.getPackageName(), 0).flags;
            boolean isSystem = (flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isPriv = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Check if installed in /system/priv-app
                String sourceDir = context.getApplicationInfo().sourceDir;
                isPriv = sourceDir != null && sourceDir.startsWith("/system/priv-app");
            }
            Log.i(TAG, "App status - System: " + isSystem + ", Privileged: " + isPriv);
            Log.i(TAG, "App source dir: " + context.getApplicationInfo().sourceDir);
            return isSystem || isPriv;
        } catch (Exception e) {
            Log.w(TAG, "Could not check system app status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Log diagnostic information about audio capabilities
     */
    public static void logDiagnostics(Context context) {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║          AUDIO DIAGNOSTICS                                 ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        // Check system app status
        isSystemApp(context);

        // Check permissions
        hasCaptureAudioPermission(context);

        // Check for MODIFY_PHONE_STATE permission
        int phoneStateResult = context.checkSelfPermission("android.permission.MODIFY_PHONE_STATE");
        Log.i(TAG, "MODIFY_PHONE_STATE permission: " +
            (phoneStateResult == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));

        // Check for CONTROL_INCALL_EXPERIENCE permission
        int incallResult = context.checkSelfPermission("android.permission.CONTROL_INCALL_EXPERIENCE");
        Log.i(TAG, "CONTROL_INCALL_EXPERIENCE permission: " +
            (incallResult == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));

        // Try to get platform info
        try {
            Process process = Runtime.getRuntime().exec("getprop ro.board.platform");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String platform = reader.readLine();
            Log.i(TAG, "Platform: " + platform);
            reader.close();
        } catch (Exception e) {
            Log.w(TAG, "Could not get platform: " + e.getMessage());
        }

        // Check AudioManager state
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            Log.i(TAG, "Audio mode: " + modeToString(audioManager.getMode()));
            Log.i(TAG, "Is music active: " + audioManager.isMusicActive());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                Log.i(TAG, "Output devices count: " + devices.length);
            }
        }

        Log.i(TAG, "────────────────────────────────────────────────────────────");
    }

    private static String modeToString(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            case AudioManager.MODE_CALL_SCREENING: return "CALL_SCREENING";
            default: return "UNKNOWN(" + mode + ")";
        }
    }
}
