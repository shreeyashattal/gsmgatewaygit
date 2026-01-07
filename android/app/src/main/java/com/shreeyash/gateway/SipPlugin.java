package com.shreeyash.gateway;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.sip.SipAudioCall;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipRegistrationListener;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "Sip")
public class SipPlugin extends Plugin {

    private SipManager sipManager = null;
    private SipProfile sipProfile = null;
    private SipAudioCall.Listener sipAudioCallListener = null;

    @Override
    public void load() {
        super.load();
        // Check if SIP is supported on this device
        if (SipManager.isApiSupported(getContext())) {
            sipManager = SipManager.newInstance(getContext());
        }
    }

    @PluginMethod
    public void sendRegister(PluginCall call) {
        String server = call.getString("server"); // e.g., "sip.linphone.org" or IP
        String username = call.getString("username");
        String password = call.getString("password");
        // 'message' param is ignored in native implementation as we build profile directly
        // 'port' is typically handled by the SIP URI format or default, but we can append if needed

        if (sipManager == null) {
            call.reject("SIP API is not supported on this device");
            return;
        }

        if (server == null || username == null || password == null) {
            call.reject("Missing required parameters: server, username, password");
            return;
        }

        try {
            SipProfile.Builder builder = new SipProfile.Builder(username, server);
            builder.setPassword(password);
            // Optionally handle port if provided:
            if (call.hasOption("port")) {
                // builder.setPort(call.getInt("port")); // SipProfile.Builder doesn't have direct setPort, it parses from domain or uses setOutboundProxy
            }
            
            sipProfile = builder.build();

            Intent intent = new Intent();
            intent.setAction("com.shreeyash.gateway.INCOMING_CALL");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(getContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            sipManager.open(sipProfile, pendingIntent, null);

            final AtomicBoolean responded = new AtomicBoolean(false);

            sipManager.setRegistrationListener(sipProfile.getUriString(), new SipRegistrationListener() {
                @Override
                public void onRegistering(String localProfileUri) {
                    // Notify JS of status?
                }

                @Override
                public void onRegistrationDone(String localProfileUri, long expiryTime) {
                    if (!responded.getAndSet(true)) {
                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        ret.put("response", "Registered successfully");
                        call.resolve(ret);
                    }
                }

                @Override
                public void onRegistrationFailed(String localProfileUri, int errorCode, String errorMessage) {
                    if (!responded.getAndSet(true)) {
                         JSObject ret = new JSObject();
                        ret.put("success", false);
                        ret.put("error", "Registration Failed: " + errorMessage + " (Code: " + errorCode + ")");
                        call.resolve(ret);
                    }
                }
            });

        } catch (Exception e) {
            call.reject("SIP Registration Error: " + e.getMessage());
        }
    }

    @PluginMethod
    public void close(PluginCall call) {
        if (sipManager != null && sipProfile != null) {
            try {
                sipManager.close(sipProfile.getUriString());
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Failed to close SIP profile: " + e.getMessage());
            }
        } else {
             call.resolve();
        }
    }

    @PluginMethod
    public void getLocalIp(PluginCall call) {
         new Thread(() -> {
            try {
                // Get local IP address
                String localIp = "127.0.0.1";
                java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces();
                
                while (networkInterfaces.hasMoreElements()) {
                    java.net.NetworkInterface networkInterface = networkInterfaces.nextElement();
                    java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                    
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                            localIp = addr.getHostAddress();
                            break;
                        }
                    }
                    
                    if (!localIp.equals("127.0.0.1")) {
                        break;
                    }
                }
                
                JSObject ret = new JSObject();
                ret.put("ip", localIp);
                call.resolve(ret);
            } catch (Exception e) {
                JSObject ret = new JSObject();
                ret.put("ip", "127.0.0.1");
                call.resolve(ret);
            }
        }).start();
    }
}
