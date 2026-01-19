#!/system/bin/sh
# Audio Bridge Configuration for Qualcomm SM6150
# Enables audio routing between GSM voice calls and SIP/PJSIP
# Run with: adb shell su -c "sh /sdcard/sm6150_audio_bridge.sh start"

ACTION=$1

enable_voice_bridge() {
    echo "========================================"
    echo "ENABLING VOICE AUDIO BRIDGE (SM6150)"
    echo "========================================"
    
    # Step 1: Enable voice call audio path
    echo "1. Enabling voice call audio path..."
    tinymix 'Voice Rx Device Mute' 0 0 0
    tinymix 'Voice Tx Device Mute' 0 0 0
    tinymix 'Voice Tx Mute' 0 0 0
    
    # Step 2: Set voice gains to maximum
    echo "2. Setting voice gains..."
    tinymix 'Voice Rx Gain' 20 20 20
    
    # Step 3: Enable HD Voice for better quality
    echo "3. Enabling HD Voice..."
    tinymix 'HD Voice Enable' 1 1
    
    # Step 4: Enable voice sidetone (allows audio loopback)
    echo "4. Enabling voice sidetone..."
    tinymix 'Voice Sidetone Enable' 1
    
    # Step 5: Configure VoIP mode (useful for SIP)
    echo "5. Configuring VoIP mode..."
    tinymix 'Voip Mode Config' 12
    tinymix 'Voip Tx Mute' 0 0
    tinymix 'Voip Rx Gain' 20 20
    
    # Step 6: Enable audio mixer for MultiMedia1 (PJSIP will use this)
    echo "6. Enabling audio mixers..."
    tinymix 'SLIMBUS_0_RX Audio Mixer MultiMedia1' 1 1
    tinymix 'MultiMedia1 Mixer PRI_MI2S_TX' 1 1
    
    # Step 7: Set audio mode to voice call
    echo "7. Setting audio mode to voice call..."
    service call audio 28 i32 2  # MODE_IN_CALL
    
    echo "========================================"
    echo "VOICE BRIDGE ENABLED ✓"
    echo "========================================"
    echo ""
    echo "PCM Device for voice: hw:0,2 (VoiceMMode1)"
    echo "You can now:"
    echo "  - Place a GSM call"
    echo "  - Start PJSIP with device hw:0,2"
    echo "  - Audio will be bridged between them"
    echo ""
}

disable_voice_bridge() {
    echo "========================================"
    echo "DISABLING VOICE AUDIO BRIDGE"
    echo "========================================"
    
    # Restore normal audio mode
    echo "1. Restoring normal audio mode..."
    service call audio 28 i32 0  # MODE_NORMAL
    
    # Disable mixers
    echo "2. Disabling audio mixers..."
    tinymix 'SLIMBUS_0_RX Audio Mixer MultiMedia1' 0 0
    tinymix 'MultiMedia1 Mixer PRI_MI2S_TX' 0 0
    
    # Disable sidetone
    echo "3. Disabling voice sidetone..."
    tinymix 'Voice Sidetone Enable' 0
    
    # Reset voice mutes
    echo "4. Resetting voice mutes..."
    tinymix 'Voice Rx Device Mute' -1 -1 -1
    tinymix 'Voice Tx Device Mute' -1 -1 -1
    tinymix 'Voice Tx Mute' -1 -1 -1
    
    echo "========================================"
    echo "VOICE BRIDGE DISABLED ✓"
    echo "========================================"
}

test_voice_audio() {
    echo "========================================"
    echo "TESTING VOICE AUDIO SETUP"
    echo "========================================"
    
    # Check if voice call is active
    echo "Current audio mode:"
    dumpsys audio | grep "Mode :" | head -1
    echo ""
    
    # Check voice mixer settings
    echo "Voice mixer settings:"
    tinymix 'Voice Rx Device Mute'
    tinymix 'Voice Tx Device Mute'
    tinymix 'Voice Rx Gain'
    tinymix 'Voice Sidetone Enable'
    echo ""
    
    # Check active PCM devices
    echo "Active PCM streams:"
    cat /proc/asound/pcm | grep RUNNING
    echo ""
    
    # Check if VoiceMMode1 is available
    echo "VoiceMMode1 device:"
    ls -la /dev/snd/pcmC0D2* 
    echo ""
    
    echo "To test audio routing:"
    echo "1. Place a GSM call"
    echo "2. Run: tinycap /sdcard/test_voice.wav -D 0 -d 2 -c 1 -r 8000"
    echo "3. This will capture voice call audio to test_voice.wav"
    echo ""
}

capture_voice_audio() {
    echo "Capturing 10 seconds of voice call audio..."
    echo "Make sure a GSM call is active!"
    tinycap /sdcard/voice_capture.wav -D 0 -d 2 -c 1 -r 8000 -b 16 -p 160 -n 500
    echo "Captured to /sdcard/voice_capture.wav"
    echo "Pull with: adb pull /sdcard/voice_capture.wav"
}

case "$ACTION" in
    start|enable)
        enable_voice_bridge
        ;;
    stop|disable)
        disable_voice_bridge
        ;;
    test)
        test_voice_audio
        ;;
    capture)
        capture_voice_audio
        ;;
    *)
        echo "Usage: $0 {start|stop|test|capture}"
        echo ""
        echo "Commands:"
        echo "  start   - Enable voice audio bridge"
        echo "  stop    - Disable voice audio bridge"
        echo "  test    - Test current audio configuration"
        echo "  capture - Capture 10s of voice call audio"
        exit 1
        ;;
esac
