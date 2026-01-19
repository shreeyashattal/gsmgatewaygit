package com.gsmgateway;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * Manages RTP audio streaming between GSM call and Asterisk
 * Uses G.711 u-law codec at 8kHz (standard telephony)
 */
public class RTPManager {
    private static final String TAG = "RTPManager";
    
    // RTP Configuration
    private static final String ASTERISK_HOST = "127.0.0.1";
    private final int localRtpPort;  // Port we send to (Asterisk listening)
    private final int remoteRtpPort; // Port we listen on (Asterisk sends here)
    
    // Audio Configuration (G.711 u-law, 8kHz telephony standard)
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 160; // 20ms at 8kHz
    
    // RTP Header
    private static final int RTP_HEADER_SIZE = 12;
    private static final byte RTP_VERSION = (byte) 0x80;
    private static final byte PAYLOAD_TYPE_PCMU = 0; // G.711 u-law
    
    private DatagramSocket rtpSocket;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    
    private Thread sendThread;
    private Thread receiveThread;
    private volatile boolean running = false;
    
    private int sequenceNumber = 0;
    private long timestamp = 0;
    private int ssrc;
    
    public RTPManager(int rtpPort) {
        this.ssrc = (int) (Math.random() * Integer.MAX_VALUE);
        this.localRtpPort = rtpPort;
        this.remoteRtpPort = rtpPort + 10000; // Listen on port + 10000
    }
    
    /**
     * Start RTP streaming
     */
    public boolean start() {
        try {
            // Create RTP socket
            rtpSocket = new DatagramSocket(remoteRtpPort);
            rtpSocket.connect(InetAddress.getByName(ASTERISK_HOST), localRtpPort);
            
            Log.i(TAG, String.format("RTP socket created: listening=%d, sending=%d", 
                remoteRtpPort, localRtpPort));
            
            // Initialize audio recording (from GSM microphone)
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            );
            
            // Initialize audio playback (to GSM speaker)
            int playbackBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, 
                AudioFormat.CHANNEL_OUT_MONO, 
                AUDIO_FORMAT
            );
            audioTrack = new AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                playbackBufferSize * 2,
                AudioTrack.MODE_STREAM
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                return false;
            }
            
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed");
                return false;
            }
            
            // Start audio
            audioRecord.startRecording();
            audioTrack.play();
            
            // Start RTP threads
            running = true;
            sendThread = new Thread(this::sendRTPLoop);
            receiveThread = new Thread(this::receiveRTPLoop);
            sendThread.start();
            receiveThread.start();
            
            Log.i(TAG, String.format("RTP streaming started on ports %d/%d", 
                localRtpPort, remoteRtpPort));
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start RTP: " + e.getMessage(), e);
            stop();
            return false;
        }
    }
    
    /**
     * Stop RTP streaming
     */
    public void stop() {
        running = false;
        
        if (sendThread != null) {
            sendThread.interrupt();
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audioRecord", e);
            }
            audioRecord = null;
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audioTrack", e);
            }
            audioTrack = null;
        }
        
        if (rtpSocket != null && !rtpSocket.isClosed()) {
            rtpSocket.close();
            rtpSocket = null;
        }
        
        Log.i(TAG, "RTP streaming stopped");
    }
    
    /**
     * Send audio from GSM to Asterisk via RTP
     */
    private void sendRTPLoop() {
        byte[] audioBuffer = new byte[FRAME_SIZE * 2]; // 16-bit samples
        byte[] rtpPacket = new byte[RTP_HEADER_SIZE + FRAME_SIZE];
        
        try {
            while (running && !Thread.interrupted()) {
                // Read audio from microphone
                int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (bytesRead <= 0) continue;
                
                // Convert PCM 16-bit to G.711 u-law
                byte[] ulawData = pcmToUlaw(audioBuffer, bytesRead);
                
                // Build RTP packet
                buildRTPHeader(rtpPacket);
                System.arraycopy(ulawData, 0, rtpPacket, RTP_HEADER_SIZE, ulawData.length);
                
                // Send to Asterisk
                DatagramPacket packet = new DatagramPacket(
                    rtpPacket, 
                    RTP_HEADER_SIZE + ulawData.length
                );
                rtpSocket.send(packet);
                
                // Update RTP state
                sequenceNumber++;
                timestamp += FRAME_SIZE;
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "RTP send error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Receive audio from Asterisk via RTP and play to GSM
     */
    private void receiveRTPLoop() {
        byte[] rtpPacket = new byte[1500]; // Max UDP packet size
        DatagramPacket packet = new DatagramPacket(rtpPacket, rtpPacket.length);
        
        try {
            while (running && !Thread.interrupted()) {
                // Receive RTP packet from Asterisk
                rtpSocket.receive(packet);
                
                if (packet.getLength() < RTP_HEADER_SIZE) continue;
                
                // Extract audio payload (skip RTP header)
                int payloadLength = packet.getLength() - RTP_HEADER_SIZE;
                byte[] ulawData = new byte[payloadLength];
                System.arraycopy(rtpPacket, RTP_HEADER_SIZE, ulawData, 0, payloadLength);
                
                // Convert G.711 u-law to PCM 16-bit
                byte[] pcmData = ulawToPcm(ulawData);
                
                // Play to GSM call speaker
                audioTrack.write(pcmData, 0, pcmData.length);
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "RTP receive error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Build RTP header
     */
    private void buildRTPHeader(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        
        buffer.put(RTP_VERSION);                    // V=2, P=0, X=0, CC=0
        buffer.put(PAYLOAD_TYPE_PCMU);              // M=0, PT=0 (PCMU)
        buffer.putShort((short) sequenceNumber);    // Sequence number
        buffer.putInt((int) timestamp);              // Timestamp
        buffer.putInt(ssrc);                         // SSRC
    }
    
    /**
     * Convert PCM 16-bit to G.711 u-law
     */
    private byte[] pcmToUlaw(byte[] pcmData, int length) {
        byte[] ulaw = new byte[length / 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcmData);
        
        for (int i = 0; i < ulaw.length; i++) {
            short pcmSample = buffer.getShort();
            ulaw[i] = linearToUlaw(pcmSample);
        }
        
        return ulaw;
    }
    
    /**
     * Convert G.711 u-law to PCM 16-bit
     */
    private byte[] ulawToPcm(byte[] ulawData) {
        byte[] pcm = new byte[ulawData.length * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcm);
        
        for (byte ulaw : ulawData) {
            short pcmSample = ulawToLinear(ulaw);
            buffer.putShort(pcmSample);
        }
        
        return pcm;
    }
    
    // G.711 u-law encoding/decoding tables
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