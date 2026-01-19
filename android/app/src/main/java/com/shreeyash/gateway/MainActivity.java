package com.shreeyash.gateway;

import android.os.Bundle;
import android.content.Intent;
import android.os.Build;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShellPlugin.class);
        registerPlugin(TelephonyPlugin.class);
        registerPlugin(GsmBridgePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
