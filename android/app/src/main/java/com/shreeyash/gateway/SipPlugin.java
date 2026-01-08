package com.shreeyash.gateway;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Sip")
public class SipPlugin extends Plugin {
    private static final String TAG = "SipPlugin";

    @Override
    public void load() {
        super.load();
        Log.d(TAG, "SipPlugin loaded");
    }

    @PluginMethod
    public void start(PluginCall call) {
        Context context = getContext();
        Intent serviceIntent = new Intent(context, PjsipService.class);
        context.startService(serviceIntent);
        JSObject ret = new JSObject();
        ret.put("started", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void setSimActive(PluginCall call) {
        int slot = call.getInt("slot", 0);
        boolean active = call.getBoolean("active", false);
        NativeSip.setSimActive(slot, active);
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        // Not implemented: advisable to implement a native stop if needed
        JSObject ret = new JSObject();
        ret.put("stopped", false);
        call.resolve(ret);
    }
}
