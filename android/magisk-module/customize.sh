#!/system/bin/sh
# GSM-SIP Gateway Magisk Module Installation Script

SKIPUNZIP=1

ui_print "========================================="
ui_print " GSM-SIP Gateway Privileged App Module  "
ui_print "========================================="

# Extract module files
ui_print "- Extracting module files..."
unzip -o "$ZIPFILE" 'system/*' -d $MODPATH >&2
unzip -o "$ZIPFILE" 'module.prop' -d $MODPATH >&2
unzip -o "$ZIPFILE" 'service.sh' -d $MODPATH >&2 || true

# Set permissions
ui_print "- Setting permissions..."
set_perm_recursive $MODPATH 0 0 0755 0644
set_perm_recursive $MODPATH/system/priv-app 0 0 0755 0644

# Create the permissions directories
mkdir -p $MODPATH/system/etc/permissions
mkdir -p $MODPATH/system/etc/default-permissions

# Make service.sh executable
[ -f $MODPATH/service.sh ] && chmod 755 $MODPATH/service.sh

ui_print "- Module installed successfully!"
ui_print ""
ui_print "Privileged permissions granted automatically:"
ui_print "  - CAPTURE_AUDIO_OUTPUT"
ui_print "  - CONTROL_INCALL_EXPERIENCE"
ui_print "  - MODIFY_PHONE_STATE"
ui_print "  - READ_PRECISE_PHONE_STATE"
ui_print ""
ui_print "Runtime permissions will be auto-granted on boot:"
ui_print "  - READ_CALL_LOG, WRITE_CALL_LOG"
ui_print "  - RECORD_AUDIO, READ_PHONE_STATE"
ui_print "  - Default Dialer will be set automatically"
ui_print ""
ui_print "Reboot to activate the module."
