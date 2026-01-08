package com.shreeyash.gateway;

import android.os.Bundle;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShellPlugin.class);
        registerPlugin(SipPlugin.class);
        registerPlugin(TelephonyPlugin.class);
        super.onCreate(savedInstanceState);

        // Start PJSIP service - MUST BE INSIDE onCreate()
        Intent serviceIntent = new Intent(this, PjsipService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}
