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
     * - Capture: GSM callee voice (VOC_REC_UL) → RTP → PBX
     *   UL = What the remote GSM party says (their uplink to us = their voice)
     * - Inject: PBX voice → RTP → Incall_Music → GSM callee (DL path)
     *   DL = What we send to the remote party
     * - Mute: Phone earpiece and mic (gateway mode - no local audio)
     *
     * KEY FIX: Capture VOC_REC_UL (not DL) to get remote party's voice without echo
     * The downlink includes our injected audio, causing echo!
     */
    private void setupVoiceCallRouting() {
        Log.i(TAG, "Setting up voice call audio routing for SM6150 gateway mode");

        // ===== CAPTURE PATH (Remote GSM party voice → RTP → PBX) =====
        // Use VOC_REC_UL to capture what the remote GSM party says
        // UL from modem perspective = remote party's voice coming TO us
        // This avoids capturing the injected incall_music (which goes on DL)
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_UL' 1");  // Remote party voice
        execRoot("tinymix 'MultiMedia1 Mixer VOC_REC_DL' 0");  // Don't capture downlink (has our injection)

        // Set voice recording config to UL only
        execRoot("tinymix 'Voc Rec Config' 1");  // 1 = UL only

        // ===== INJECTION PATH (PBX → RTP → GSM callee) =====
        // Use MultiMedia2 for injection (separate from capture on MM1)
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia2' 1");
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia9' 1");

        // Ensure MultiMedia1 doesn't inject (it's for capture only)
        execRoot("tinymix 'Incall_Music Audio Mixer MultiMedia1' 0");

        // ===== MUTE PHONE SPEAKER/EARPIECE =====
        // The gateway user shouldn't hear the call
        execRoot("tinymix 'Voice Rx Device Mute' 1 1 1");
        execRoot("tinymix 'Voice Rx Gain' 0 0 0");

        // Disable all RX paths to prevent local audio
        execRoot("tinymix 'SLIM_0_RX_Voice Mixer VoiceMMode1' 0");
        execRoot("tinymix 'SLIM_0_RX_Voice Mixer VoiceMMode2' 0");
        execRoot("tinymix 'RX_CDC_DMA_RX_0_Voice Mixer VoiceMMode1' 0");
        execRoot("tinymix 'RX_CDC_DMA_RX_0_Voice Mixer VoiceMMode2' 0");
        execRoot("tinymix 'RX0 Digital Volume' 0");
        execRoot("tinymix 'RX1 Digital Volume' 0");
        execRoot("tinymix 'EAR_SPKR DAC Switch' 0");
        execRoot("tinymix 'EAR PA Gain' 0");
        execRoot("tinymix 'HPHL DAC Switch' 0");
        execRoot("tinymix 'HPHR DAC Switch' 0");

        // ===== MUTE PHONE MIC =====
        // The gateway user's mic shouldn't capture audio - mute all TX paths
        execRoot("tinymix 'Voice Tx Device Mute' 1 1 1");

        // Disable TX (mic) paths on SM6150/Bengal
        execRoot("tinymix 'TX_CDC_DMA_TX_3_Voice Mixer VoiceMMode1' 0");
        execRoot("tinymix 'TX_CDC_DMA_TX_3_Voice Mixer VoiceMMode2' 0");
        execRoot("tinymix 'SLIM_0_TX_Voice Mixer VoiceMMode1' 0");
        execRoot("tinymix 'SLIM_0_TX_Voice Mixer VoiceMMode2' 0");

        // Disable ADC (mic input) paths
        execRoot("tinymix 'ADC1 Volume' 0");
        execRoot("tinymix 'ADC2 Volume' 0");
        execRoot("tinymix 'ADC3 Volume' 0");
        execRoot("tinymix 'DEC0 Volume' 0");
        execRoot("tinymix 'DEC1 Volume' 0");

        // Disable mic switches
        execRoot("tinymix 'TX0 Input' 'ZERO'");
        execRoot("tinymix 'TX1 Input' 'ZERO'");

        // Give mixer time to apply settings
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        Log.i(TAG, "Voice call routing configured - Capture: VOC_REC_UL, Inject: Incall_Music via MM2");
        Log.i(TAG, "Phone mic and speaker MUTED for gateway mode");
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
     */
    private void captureLoop() {
        Log.i(TAG, "Starting voice capture using tinycap via stdout pipe");
        Log.i(TAG, "Remote RTP endpoint: " + remoteHost + ":" + remotePort);

        byte[] pcmBuffer = new byte[BUFFER_SIZE];
        byte[] rtpPacket = new byte[RTP_HEADER_SIZE + FRAME_SIZE];
        Process tinycapProc = null;

        try {
            // Start tinycap to capture voice call audio to stdout
            // Device 0 on card 0 typically maps to MultiMedia1 when mixer is configured
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
            long lastLogTime = System.currentTimeMillis();

            while (running && !Thread.interrupted()) {
                int bytesRead = audioIn.read(pcmBuffer);
                if (bytesRead <= 0) {
                    Thread.sleep(5);
                    continue;
                }

                // Check if audio is silent (all zeros or very low amplitude)
                boolean isSilent = isAudioSilent(pcmBuffer, bytesRead);
                if (isSilent) {
                    silentPackets++;
                } else {
                    silentPackets = 0; // Reset on non-silent audio
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

                // Log every 5 seconds
                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 5000) {
                    Log.d(TAG, "Capture: sent " + packetCount + " RTP packets to " + remoteHost + ":" + remotePort +
                          (silentPackets > 100 ? " (SILENT - check mixer routing!)" : ""));
                    lastLogTime = now;
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
     * Check if audio buffer is silent (all zeros or very low amplitude)
     */
    private boolean isAudioSilent(byte[] buffer, int length) {
        int threshold = 100; // Amplitude threshold for silence detection
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        int maxAmplitude = 0;
        for (int i = 0; i < length / 2 && bb.remaining() >= 2; i++) {
            int sample = Math.abs(bb.getShort());
            if (sample > maxAmplitude) maxAmplitude = sample;
        }
        return maxAmplitude < threshold;
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
     */
    private void playbackLoop() {
        Log.i(TAG, "Starting RTP to voice call playback");

        // Try tinyplay first - it routes more reliably to incall_music on SM6150
        if (!playbackWithTinyplay()) {
            Log.w(TAG, "Tinyplay failed, falling back to AudioTrack");
            playbackWithAudioTrack();
        }

        Log.i(TAG, "Playback loop ended");
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
            long lastLogTime = System.currentTimeMillis();

            while (running && !Thread.interrupted()) {
                try {
                    rtpSocket.receive(packet);

                    if (packet.getLength() < RTP_HEADER_SIZE) continue;

                    int payloadLength = packet.getLength() - RTP_HEADER_SIZE;
                    byte[] ulawData = new byte[payloadLength];
                    System.arraycopy(rtpPacket, RTP_HEADER_SIZE, ulawData, 0, payloadLength);

                    // Convert u-law to PCM
                    byte[] pcmData = ulawToPcm(ulawData);

                    // Write to tinyplay
                    playbackOut.write(pcmData);
                    playbackOut.flush();

                    packetCount++;

                    // Log every 5 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime >= 5000) {
                        Log.d(TAG, "Tinyplay playback: received " + packetCount + " RTP packets");
                        lastLogTime = now;
                    }

                } catch (java.net.SocketTimeoutException e) {
                    // Normal timeout - no data received
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
