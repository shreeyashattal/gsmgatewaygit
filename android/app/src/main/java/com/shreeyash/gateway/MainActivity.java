package com.gsmgateway;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Simple setup activity to request permissions and start service
 * Once configured, this app runs headless via the service
 */
public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 1000;
    private static final int OVERLAY_PERMISSION_REQUEST = 1001;
    
    private TextView statusText;
    private Button startButton;
    private Button stopButton;
    
    private String[] requiredPermissions = {
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        statusText = findViewById(R.id.statusText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        
        startButton.setOnClickListener(v -> startGateway());
        stopButton.setOnClickListener(v -> stopGateway());
        
        updateStatus();
    }
    
    private void updateStatus() {
        StringBuilder status = new StringBuilder("GSM Gateway Status\n\n");
        
        // Check permissions
        boolean allGranted = true;
        for (String permission : requiredPermissions) {
            boolean granted = ContextCompat.checkSelfPermission(this, permission) 
                == PackageManager.PERMISSION_GRANTED;
            allGranted &= granted;
            status.append(permission.substring(permission.lastIndexOf('.') + 1))
                .append(": ")
                .append(granted ? "✓" : "✗")
                .append("\n");
        }
        
        // Check battery optimization
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryOptimized = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            batteryOptimized = !pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        status.append("\nBattery Optimization: ")
            .append(batteryOptimized ? "Enabled (needs disable)" : "Disabled ✓")
            .append("\n");
        
        status.append("\n");
        if (allGranted && !batteryOptimized) {
            status.append("Ready to start!\n\n");
            status.append("Phone Configuration:\n");
            status.append("• Keep phone plugged in\n");
            status.append("• Disable screen timeout\n");
            status.append("• Enable 'Do Not Disturb'\n");
            status.append("• Ensure Asterisk is running\n");
            startButton.setEnabled(true);
        } else {
            status.append("Click 'Request Permissions'\n");
            status.append("Then click 'Start Gateway'\n");
            startButton.setEnabled(false);
        }
        
        statusText.setText(status.toString());
    }
    
    public void requestPermissions(android.view.View view) {
        // Request runtime permissions
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST);
        
        // Request battery optimization exemption
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_SHORT).show();
            }
            
            updateStatus();
        }
    }
    
    private void startGateway() {
        Intent serviceIntent = new Intent(this, GatewayService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        Toast.makeText(this, "Gateway service started", Toast.LENGTH_SHORT).show();
        statusText.setText("Gateway Running\n\nService is active in background.\nYou can close this app.\n\nThe service will:\n• Answer incoming GSM calls\n• Forward to Grandstream PBX\n• Accept outgoing calls from PBX\n• Make GSM calls\n\nCheck notification for status.");
        
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
    }
    
    private void stopGateway() {
        Intent serviceIntent = new Intent(this, GatewayService.class);
        stopService(serviceIntent);
        
        Toast.makeText(this, "Gateway service stopped", Toast.LENGTH_SHORT).show();
        updateStatus();
        
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }
}