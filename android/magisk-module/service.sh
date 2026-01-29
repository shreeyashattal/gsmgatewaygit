#!/system/bin/sh
# GSM-SIP Gateway - Boot-time Permission Setup
# This script runs after boot to grant runtime permissions and set default dialer

PACKAGE="com.shreeyash.gateway"
LOGFILE="/data/local/tmp/gsm-gateway-setup.log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" >> "$LOGFILE"
}

# Wait for system to be fully booted
wait_for_boot() {
    log "Waiting for system boot..."
    local count=0
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 1
        count=$((count + 1))
        if [ $count -gt 120 ]; then
            log "Timeout waiting for boot"
            return 1
        fi
    done
    log "System boot completed"
    # Additional delay for PackageManager to be ready
    sleep 10
    return 0
}

# Grant runtime permissions
grant_permissions() {
    log "Granting runtime permissions..."

    # Telephony permissions
    pm grant "$PACKAGE" android.permission.READ_PHONE_STATE 2>/dev/null && log "  READ_PHONE_STATE: granted"
    pm grant "$PACKAGE" android.permission.READ_PHONE_NUMBERS 2>/dev/null && log "  READ_PHONE_NUMBERS: granted"
    pm grant "$PACKAGE" android.permission.CALL_PHONE 2>/dev/null && log "  CALL_PHONE: granted"
    pm grant "$PACKAGE" android.permission.ANSWER_PHONE_CALLS 2>/dev/null && log "  ANSWER_PHONE_CALLS: granted"
    pm grant "$PACKAGE" android.permission.READ_CALL_LOG 2>/dev/null && log "  READ_CALL_LOG: granted"
    pm grant "$PACKAGE" android.permission.WRITE_CALL_LOG 2>/dev/null && log "  WRITE_CALL_LOG: granted"
    pm grant "$PACKAGE" android.permission.PROCESS_OUTGOING_CALLS 2>/dev/null && log "  PROCESS_OUTGOING_CALLS: granted"

    # Audio permissions
    pm grant "$PACKAGE" android.permission.RECORD_AUDIO 2>/dev/null && log "  RECORD_AUDIO: granted"

    # Location for SIM detection
    pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION 2>/dev/null && log "  ACCESS_FINE_LOCATION: granted"
    pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null && log "  ACCESS_COARSE_LOCATION: granted"

    # Notification permission (Android 13+)
    pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null && log "  POST_NOTIFICATIONS: granted"

    # Set AppOps for permissions that might be restricted
    appops set "$PACKAGE" READ_CALL_LOG allow 2>/dev/null
    appops set "$PACKAGE" WRITE_CALL_LOG allow 2>/dev/null
    appops set "$PACKAGE" PROCESS_OUTGOING_CALLS allow 2>/dev/null

    log "Permissions granted"
}

# Set as default dialer using RoleManager
set_default_dialer() {
    log "Setting default dialer via RoleManager..."

    # Method 1: Use RoleManager (Android 10+) - This grants DIALER role which
    # automatically grants call log permissions with RESTRICTION_SYSTEM_EXEMPT flag
    cmd role add-role-holder android.app.role.DIALER "$PACKAGE" 2>/dev/null

    # Verify via role
    local role_holder=$(dumpsys role | grep -A3 'DIALER' | grep 'holders=' | sed 's/.*holders=//')
    if echo "$role_holder" | grep -q "$PACKAGE"; then
        log "DIALER role assigned successfully"
    else
        log "WARNING: DIALER role assignment may have failed"
        # Fallback: Direct settings change
        settings put secure dialer_default_application "$PACKAGE" 2>/dev/null
    fi

    # Verify via settings
    local current_dialer=$(settings get secure dialer_default_application)
    if [ "$current_dialer" = "$PACKAGE" ]; then
        log "Default dialer set successfully: $PACKAGE"
        return 0
    else
        log "Default dialer is: $current_dialer (wanted: $PACKAGE)"
        return 1
    fi
}

# Start the gateway service
start_service() {
    log "Starting gateway service..."
    am start-foreground-service -n "$PACKAGE/.GatewayService" 2>/dev/null && log "Service start requested"
}

# Main execution
main() {
    log "========================================="
    log "GSM-SIP Gateway Boot Setup Starting"
    log "========================================="

    wait_for_boot || exit 1

    # Check if package is installed
    if ! pm list packages | grep -q "$PACKAGE"; then
        log "ERROR: Package $PACKAGE not found"
        exit 1
    fi

    log "Package found: $PACKAGE"

    # Disable hidden API restrictions so ITelephony reflection works
    # This is required for ITelephony.call() and ITelephony.answerRingingCall()
    settings put global hidden_api_policy 1 2>/dev/null && log "Hidden API policy set to unrestricted"

    grant_permissions
    set_default_dialer
    start_service

    log "========================================="
    log "GSM-SIP Gateway Boot Setup Complete"
    log "========================================="
}

# Run in background
main &
