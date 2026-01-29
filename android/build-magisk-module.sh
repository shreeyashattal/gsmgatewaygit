#!/bin/bash
# Build Magisk module for GSM-SIP Gateway

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/magisk-module"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
OUTPUT_ZIP="$SCRIPT_DIR/gsm-sip-gateway-magisk.zip"

echo "========================================="
echo " Building GSM-SIP Gateway Magisk Module"
echo "========================================="

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK not found at $APK_PATH"
    echo "Run './gradlew assembleDebug' first"
    exit 1
fi

# Copy APK to module
echo "Copying APK to module..."
mkdir -p "$MODULE_DIR/system/priv-app/GSMGateway"
cp "$APK_PATH" "$MODULE_DIR/system/priv-app/GSMGateway/GSMGateway.apk"

# Create zip
echo "Creating Magisk module zip..."
cd "$MODULE_DIR"
rm -f "$OUTPUT_ZIP"
zip -r "$OUTPUT_ZIP" . -x "*.DS_Store" -x "*__MACOSX*"

echo ""
echo "========================================="
echo " Module built successfully!"
echo "========================================="
echo ""
echo "Output: $OUTPUT_ZIP"
echo ""
echo "Installation instructions:"
echo "1. Copy $OUTPUT_ZIP to your phone"
echo "2. Open Magisk app"
echo "3. Go to Modules"
echo "4. Install from storage"
echo "5. Select the zip file"
echo "6. Reboot"
echo ""
echo "After reboot, the app will have CAPTURE_AUDIO_OUTPUT permission"
echo "which allows access to the VOICE_CALL audio stream."
