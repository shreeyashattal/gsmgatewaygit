package com.shreeyash.gateway;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * AudioBridge - Routes audio between GSM cellular calls and SIP RTP streams
 * 
 * This class manages Android's AudioRecord and AudioTrack to capture/playback
 * audio from GSM calls, bridging them with PJMEDIA RTP streams via JNI.
 * 
 * AUDIO ROUTING:
 * - AudioRecord (MIC) → captures GSM uplink → Native → RTP (to SIP)
 * - RTP (from SIP) → Native → AudioTrack (EARPIECE) → GSM downlink
 */
public class AudioBridge {
    private static final String TAG = "AudioBridge";
    
    // Audio configuration (must match native code)
    private static final int SAMPLE_RATE = 8000;  // 8kHz for telephony
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int SAMPLES_PER_FRAME = 160;  // 20ms at 8kHz
    
    private final int slot;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread captureThread;
    private Thread playbackThread;
    private volatile boolean running = false;
    
    // Buffers for audio samples
    private short[] captureBuffer;
    private short[] playbackBuffer;
    
    public AudioBridge(int slot) {
        this.slot = slot;
        this.captureBuffer = new short[SAMPLES_PER_FRAME];
        this.playbackBuffer = new short[SAMPLES_PER_FRAME];
    }
    
    /**
     * Start audio bridge
     */
    public boolean start() {
        if (running) {
            Log.w(TAG, "AudioBridge already running for slot " + slot);
            return false;
        }
        
        try {
            // Setup AudioRecord for capturing GSM microphone
            int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
            
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                Math.max(minBufferSize, SAMPLES_PER_FRAME * 2 * 4)  // 4 frames buffer
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord");
                return false;
            }
            
            // Setup AudioTrack for playing to GSM earpiece
            minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
            
            audioTrack = new AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                Math.max(minBufferSize, SAMPLES_PER_FRAME * 2 * 4),
                AudioTrack.MODE_STREAM
            );
            
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioTrack");
                audioRecord.release();
                return false;
            }
            
            // Start audio devices
            audioRecord.startRecording();
            audioTrack.play();
            
            running = true;
            
            // Start capture thread (GSM → SIP)
            captureThread = new Thread(this::captureLoop);
            captureThread.setName("AudioCapture-" + slot);
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
            
            // Start playback thread (SIP → GSM)
            playbackThread = new Thread(this::playbackLoop);
            playbackThread.setName("AudioPlayback-" + slot);
            playbackThread.setPriority(Thread.MAX_PRIORITY);
            playbackThread.start();
            
            Log.i(TAG, "AudioBridge started for slot " + slot);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioBridge", e);
            stop();
            return false;
        }
    }
    
    /**
     * Stop audio bridge
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        Log.i(TAG, "Stopping AudioBridge for slot " + slot);
        running = false;
        
        // Wait for threads to finish
        try {
            if (captureThread != null) {
                captureThread.join(1000);
                captureThread = null;
            }
            if (playbackThread != null) {
                playbackThread.join(1000);
                playbackThread = null;
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "Thread join interrupted", e);
        }
        
        // Release audio resources
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioRecord", e);
            }
            audioRecord = null;
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioTrack", e);
            }
            audioTrack = null;
        }
        
        Log.i(TAG, "AudioBridge stopped for slot " + slot);
    }
    
    /**
     * Capture loop - reads from GSM microphone and sends to native bridge
     */
    private void captureLoop() {
        Log.d(TAG, "Capture loop started for slot " + slot);
        
        while (running && audioRecord != null) {
            try {
                // Read samples from GSM microphone
                int samplesRead = audioRecord.read(
                    captureBuffer, 0, captureBuffer.length);
                
                if (samplesRead > 0) {
                    // Send to native code for SIP transmission
                    onGsmAudioCaptured(slot, captureBuffer);
                } else if (samplesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: " + samplesRead);
                    break;
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Capture loop error", e);
                break;
            }
        }
        
        Log.d(TAG, "Capture loop stopped for slot " + slot);
    }
    
    /**
     * Playback loop - gets samples from native bridge and plays to GSM earpiece
     */
    private void playbackLoop() {
        Log.d(TAG, "Playback loop started for slot " + slot);
        
        while (running && audioTrack != null) {
            try {
                // Get samples from native code (SIP → GSM)
                int samplesAvailable = getGsmAudioSamples(slot, playbackBuffer);
                
                if (samplesAvailable > 0) {
                    // Play to GSM earpiece
                    int samplesWritten = audioTrack.write(
                        playbackBuffer, 0, samplesAvailable);
                    
                    if (samplesWritten < 0) {
                        Log.e(TAG, "AudioTrack write error: " + samplesWritten);
                        break;
                    }
                } else {
                    // No samples available, insert silence
                    for (int i = 0; i < playbackBuffer.length; i++) {
                        playbackBuffer[i] = 0;
                    }
                    audioTrack.write(playbackBuffer, 0, playbackBuffer.length);
                }
                
                // Small sleep to prevent busy loop
                Thread.sleep(5);
                
            } catch (Exception e) {
                Log.e(TAG, "Playback loop error", e);
                break;
            }
        }
        
        Log.d(TAG, "Playback loop stopped for slot " + slot);
    }
    
    /* ========================================
       JNI Native Methods
       ======================================== */
    
    /**
     * Called when GSM audio is captured (GSM → SIP direction)
     */
    private native void onGsmAudioCaptured(int slot, short[] samples);
    
    /**
     * Get audio samples for GSM playback (SIP → GSM direction)
     * @return number of samples available
     */
    private native int getGsmAudioSamples(int slot, short[] samples);
}