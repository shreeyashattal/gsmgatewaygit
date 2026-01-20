package com.shreeyash.gateway;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
        if (remoteHost == null || remotePort == 0) {
            Log.e(TAG, "Remote address not set");
            return false;
        }

        try {
            // Initialize root shell
            if (!initRootShell()) {
                Log.e(TAG, "Failed to get root access");
                return false;
            }

            // Set up audio routing for voice call capture
            setupVoiceCallRouting();

            // Create RTP socket
            remoteAddress = InetAddress.getByName(remoteHost);
            rtpSocket = new DatagramSocket(localRtpPort);
            rtpSocket.setSoTimeout(1000);

            Log.i(TAG, "RTP socket bound to port " + localRtpPort);
            Log.i(TAG, "Remote RTP: " + remoteHost + ":" + remotePort);

            running = true;

            // Start capture thread (voice call -> RTP)
            captureThread = new Thread(this::captureLoop, "PCM-Capture");
            captureThread.start();

            // Start playback thread (RTP -> voice call)
            playbackThread = new Thread(this::playbackLoop, "PCM-Playback");
            playbackThread.start();

            Log.i(TAG, "Native PCM audio bridge started");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio bridge: " + e.getMessage(), e);
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
     * Execute command as root
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
     * Set up mixer controls for voice call audio capture/injection on SM6150
     *
     * Audio Flow for Gateway:
     * - Capture: GSM callee voice (VOC_REC_DL) → RTP → PBX
     * - Inject: PBX voice → RTP → Incall_Music → GSM callee
     * - Mute: Phone earpiece (user shouldn't hear) and phone mic (user shouldn't speak)
     *
     * IMPORTANT: To prevent echo, we use separate MultiMedia devices:
     * - MultiMedia1 for capture (VOC_REC_DL)
     * - MultiMedia2 for playback (Incall_Music)
     */
    private void setupVoiceCallRouting() {
        Log.i(TAG, "Setting up voice call audio routing for SM6150 gateway mode");

        // ===== CAPTURE PATH (Callee voice → RTP → PBX) =====
        // Use MultiMedia1 for voice capture ONLY (downlink = what callee says)
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_DL' 1");  // Capture callee voice (downlink)
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 0");  // Don't capture uplink

        // Set voice recording config to downlink only (prevents capturing injected audio)
        execRoot("tinymix 'Voc Rec Config' 0");  // 0 = DL only, 1 = UL+DL

        // ===== INJECTION PATH (PBX → RTP → GSM callee) =====
        // Use MultiMedia2 for injection to avoid feedback loop with MultiMedia1 capture
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1");
        // Also enable MultiMedia9 as backup path for incall music
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia9' 1");

        // Disable MultiMedia1 from incall music to prevent echo
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0");

        // ===== MUTE PHONE SPEAKER/EARPIECE =====
        // Mute the voice RX device so phone user doesn't hear
        execRoot("tinymix 'Voice Rx Device Mute' 1 1 1");
        execRoot("tinymix 'Voice Rx Gain' 0 0 0");

        // Disable voice routing to earpiece (slimbus path)
        execRoot("tinymix 'SLIM_0_RX_Voice Mixer VoiceMMode1' 0");
        execRoot("tinymix 'SLIM_0_RX_Voice Mixer VoiceMMode2' 0");

        // Disable CDC_DMA voice routing
        execRoot("tinymix 'RX_CDC_DMA_RX_0_Voice Mixer VoiceMMode1' 0");
        execRoot("tinymix 'RX_CDC_DMA_RX_0_Voice Mixer VoiceMMode2' 0");

        // Set RX digital volume to 0
        execRoot("tinymix 'RX0 Digital Volume' 0");
        execRoot("tinymix 'RX1 Digital Volume' 0");

        // Disable earpiece DAC
        execRoot("tinymix 'EAR_SPKR DAC Switch' 0");
        execRoot("tinymix 'EAR PA Gain' 0");

        // Disable headphone outputs
        execRoot("tinymix 'HPHL DAC Switch' 0");
        execRoot("tinymix 'HPHR DAC Switch' 0");

        // ===== MUTE PHONE MIC =====
        // Mute mic so phone user's voice doesn't go to callee
        execRoot("tinymix 'Voice Tx Device Mute' 1 1 1");

        Log.i(TAG, "Voice call routing configured for gateway mode");
    }

    /**
     * Disable voice call routing and restore normal audio
     */
    private void disableVoiceCallRouting() {
        Log.i(TAG, "Disabling voice call audio routing, restoring normal audio");

        // Disable voice recording routing
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_DL' 0");
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 0");

        // Disable incall music routing
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0");

        // Restore phone speaker/earpiece (unmute)
        execRoot("tinymix 'Voice Rx Device Mute' 0 0 0");
        execRoot("tinymix 'Voice Rx Gain' 2000 2000 2000");  // Restore normal gain

        // Restore RX digital volumes
        execRoot("tinymix 'RX0 Digital Volume' 84");
        execRoot("tinymix 'RX1 Digital Volume' 84");
        execRoot("tinymix 'RX2 Digital Volume' 84");

        // Restore earpiece/speaker DAC
        execRoot("tinymix 'EAR_SPKR DAC Switch' 1");
        execRoot("tinymix 'HPHL DAC Switch' 1");
        execRoot("tinymix 'HPHR DAC Switch' 1");

        // Restore voice routing
        // System will usually restore these automatically on next normal call

        // Restore phone mic
        execRoot("tinymix 'Voice Tx Device Mute' 0 0 0");
        execRoot("tinymix 'Voip Tx Mute' 0");

        // Restore ADC volumes
        execRoot("tinymix 'ADC1 Volume' 84");
        execRoot("tinymix 'ADC2 Volume' 84");
        execRoot("tinymix 'ADC3 Volume' 84");
    }

    /**
     * Capture voice call audio and send via RTP
     * Uses tinycap with stdout pipe for reliable audio streaming
     */
    private void captureLoop() {
        Log.i(TAG, "Starting voice capture using tinycap via stdout pipe");

        byte[] pcmBuffer = new byte[BUFFER_SIZE];
        byte[] rtpPacket = new byte[RTP_HEADER_SIZE + FRAME_SIZE];
        Process tinycapProc = null;

        try {
            // Start tinycap to capture voice call audio to stdout
            // Using /dev/stdout for output so we can read from process stdout
            String tinycapCmd = String.format(
                "tinycap /dev/stdout -D 0 -d 0 -c 1 -r %d -b 16 2>/dev/null",
                SAMPLE_RATE
            );
            Log.i(TAG, "Starting tinycap: " + tinycapCmd);

            tinycapProc = Runtime.getRuntime().exec(new String[]{"su", "-c", tinycapCmd});

            DataInputStream audioIn = new DataInputStream(tinycapProc.getInputStream());

            // Skip WAV header (44 bytes) that tinycap prepends
            byte[] wavHeader = new byte[44];
            int headerRead = 0;
            while (headerRead < 44 && running) {
                int r = audioIn.read(wavHeader, headerRead, 44 - headerRead);
                if (r <= 0) {
                    Thread.sleep(10);
                    continue;
                }
                headerRead += r;
            }
            Log.d(TAG, "Skipped " + headerRead + " bytes WAV header");

            int packetCount = 0;
            while (running && !Thread.interrupted()) {
                int bytesRead = audioIn.read(pcmBuffer);
                if (bytesRead <= 0) {
                    Thread.sleep(5);
                    continue;
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

                if (packetCount % 500 == 0) {
                    Log.d(TAG, "Capture: sent " + packetCount + " RTP packets");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Capture error: " + e.getMessage(), e);
            // Try AudioRecord fallback
            captureWithAudioRecord();
        } finally {
            if (tinycapProc != null) {
                tinycapProc.destroy();
            }
        }

        Log.i(TAG, "Capture loop ended");
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
     * Uses AudioTrack with USAGE_VOICE_COMMUNICATION for incall audio injection
     * This is more reliable than tinyplay for routing to the voice call
     */
    private void playbackLoop() {
        Log.i(TAG, "Starting RTP to voice call playback via AudioTrack");

        // Go directly to AudioTrack - it's more reliable for incall music injection
        playbackWithAudioTrack();

        Log.i(TAG, "Playback loop ended");
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
     * This routes audio through the voice call path on modern Android
     */
    private void playbackWithAudioTrack() {
        Log.i(TAG, "Using AudioTrack for playback with voice communication usage");

        try {
            // Try to override audio permission via appops
            execRoot("appops set com.shreeyash.gateway PLAY_AUDIO allow");

            // Use AudioTrack with AudioAttributes for proper voice call routing
            int minBufferSize = android.media.AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            );

            // Build AudioAttributes for voice communication
            android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

            // Build AudioFormat
            android.media.AudioFormat audioFormat = new android.media.AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build();

            android.media.AudioTrack audioTrack = new android.media.AudioTrack(
                audioAttributes,
                audioFormat,
                Math.max(minBufferSize * 2, 4096),
                android.media.AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            if (audioTrack.getState() != android.media.AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack init failed, trying legacy stream");
                playbackWithLegacyAudioTrack();
                return;
            }

            Log.i(TAG, "AudioTrack initialized with USAGE_VOICE_COMMUNICATION");
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
                        Log.d(TAG, "AudioTrack playback: " + packetCount + " packets");
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
