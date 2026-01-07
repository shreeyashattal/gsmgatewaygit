package com.shreeyash.gateway;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShellPlugin.class);
        registerPlugin(SipPlugin.class);
        registerPlugin(TelephonyPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
