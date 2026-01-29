package com.shreeyash.gateway;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Main activity - Capacitor bridge with permission handling and service start
 *
 * Also handles becoming the default dialer app via ConnectionService.
 */
public class MainActivity extends BridgeActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST = 1000;
    private static final int REQUEST_DEFAULT_DIALER = 1001;

    private ActivityResultLauncher<Intent> defaultDialerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Register Capacitor plugins BEFORE super.onCreate
        registerPlugin(ShellPlugin.class);
        registerPlugin(TelephonyPlugin.class);
        registerPlugin(GsmBridgePlugin.class);

        super.onCreate(savedInstanceState);

        // Set up default dialer result launcher
        defaultDialerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (isDefaultDialer()) {
                    Log.i(TAG, "Successfully became default dialer!");
                    Toast.makeText(this, "GSM Gateway is now the default dialer", Toast.LENGTH_SHORT).show();
                } else {
                    Log.w(TAG, "User declined default dialer request");
                    Toast.makeText(this, "Gateway works best as default dialer", Toast.LENGTH_LONG).show();
                }
            }
        );

        // Request permissions on startup
        if (!hasAllPermissions()) {
            requestAllPermissions();
        } else {
            onAllPermissionsGranted();
        }

        // Request battery optimization exemption
        requestBatteryOptimizationExemption();

        // Handle incoming dial intent (if launched via tel: URI)
        handleDialIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDialIntent(intent);
    }

    /**
     * Handle incoming dial intents (tel: URIs)
     */
    private void handleDialIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_DIAL.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null && "tel".equals(data.getScheme())) {
                String phoneNumber = data.getSchemeSpecificPart();
                Log.i(TAG, "Received dial intent for: " + phoneNumber);
                // TODO: Handle dial request - show dialer UI or initiate call
                // For now, we just log it. The Ionic UI can handle this.
            }
        }
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.CALL_PHONE);
        permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);
        permissions.add(Manifest.permission.READ_CALL_LOG);
        permissions.add(Manifest.permission.WRITE_CALL_LOG);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);

        // Android 13+ requires READ_PHONE_NUMBERS for phone number access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        return permissions.toArray(new String[0]);
    }

    private boolean hasAllPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        return true;
    }

    private void requestAllPermissions() {
        Log.i(TAG, "Requesting permissions...");
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST);
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to request battery optimization exemption", e);
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }

            if (allGranted) {
                Toast.makeText(this, "Permissions granted - starting gateway", Toast.LENGTH_SHORT).show();
                onAllPermissionsGranted();
            } else {
                Toast.makeText(this, "Some permissions denied - gateway may not work properly", Toast.LENGTH_LONG).show();
                // Still try to start, some functionality may work
                startGatewayService();
            }
        }
    }

    private void onAllPermissionsGranted() {
        Log.i(TAG, "All permissions granted");

        // Register our PhoneAccounts with the Telecom system
        registerPhoneAccounts();

        // Request to become the default dialer
        requestDefaultDialer();

        // Start the gateway service
        startGatewayService();
    }

    /**
     * Register PhoneAccounts for our ConnectionService
     */
    private void registerPhoneAccounts() {
        try {
            GatewayConnectionService.registerPhoneAccounts(this);
            Log.i(TAG, "PhoneAccounts registered successfully");

            // Enable our phone accounts
            enablePhoneAccounts();
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PhoneAccounts: " + e.getMessage(), e);
        }
    }

    /**
     * Try to enable our phone accounts programmatically
     */
    private void enablePhoneAccounts() {
        TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) return;

        // Log available accounts
        try {
            List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
            Log.i(TAG, "Call-capable phone accounts: " + accounts.size());
            for (PhoneAccountHandle handle : accounts) {
                Log.i(TAG, "  Account: " + handle.getId() + " (" + handle.getComponentName() + ")");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot list phone accounts: " + e.getMessage());
        }
    }

    /**
     * Request to become the default dialer app
     */
    private void requestDefaultDialer() {
        if (isDefaultDialer()) {
            Log.i(TAG, "Already the default dialer");
            return;
        }

        Log.i(TAG, "Requesting to become default dialer...");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ uses RoleManager
                RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                    defaultDialerLauncher.launch(intent);
                }
            } else {
                // Android 9 and below uses TelecomManager
                TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (telecomManager != null) {
                    Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                    intent.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                    defaultDialerLauncher.launch(intent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to request default dialer: " + e.getMessage(), e);
        }
    }

    /**
     * Check if we are the default dialer
     */
    private boolean isDefaultDialer() {
        TelecomManager telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) return false;

        String defaultDialer = telecomManager.getDefaultDialerPackage();
        boolean isDefault = getPackageName().equals(defaultDialer);
        Log.d(TAG, "Default dialer: " + defaultDialer + ", we are default: " + isDefault);
        return isDefault;
    }

    private void startGatewayService() {
        Log.i(TAG, "Starting gateway service");

        try {
            Intent serviceIntent = new Intent(this, GatewayService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start gateway service", e);
            Toast.makeText(this, "Failed to start service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
