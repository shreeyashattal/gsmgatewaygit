package com.shreeyash.gateway;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.pjsip.pjsua2.*;

@CapacitorPlugin(name = "Sip")
public class SipPlugin extends Plugin {
    
    private static final String TAG = "SipPlugin";
    private PjsipService pjsipService;
    private Account account;
    private String localIp = "";
    
    @Override
    public void load() {
        super.load();
        Log.d(TAG, "SipPlugin loaded");
        
        Context context = getContext();
        Intent serviceIntent = new Intent(context, PjsipService.class);
        context.startService(serviceIntent);
        
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        pjsipService = PjsipService.getInstance();
    }
    
    @PluginMethod
    public void start(PluginCall call) {
        String mode = call.getString("mode", "SERVER"); 
        String sipServer = call.getString("sipServer");
        Integer sipPort = call.getInt("sipPort", 5060);
        String sipUser = call.getString("sipUser");
        String sipPass = call.getString("sipPass");
        Integer regExpiry = call.getInt("regExpiry", 3600);
        String codec = call.getString("codec", "PCMU"); // Receive codec preference

        Log.d(TAG, "Starting SIP Service in " + mode + " mode. Preferred Codec: " + codec);
        
        new Thread(() -> {
            try {
                if (pjsipService == null || !pjsipService.isInitialized()) {
                    call.reject("PJSIP service not initialized");
                    return;
                }
                
                // Update codec priority based on config
                pjsipService.updateCodecPriority(codec);
                
                localIp = getDeviceLocalIp();
                
                AccountConfig accCfg = new AccountConfig();
                String idUri;
                String registrarUri;

                if ("CLIENT".equals(mode)) {
                    idUri = "sip:" + sipUser + "@" + sipServer;
                    registrarUri = "sip:" + sipServer + ":" + sipPort;
                    
                    accCfg.setIdUri(idUri);
                    accCfg.getRegConfig().setRegistrarUri(registrarUri);
                    accCfg.getRegConfig().setRegisterOnAdd(true);
                    accCfg.getRegConfig().setTimeoutSec(regExpiry);
                    
                    AuthCredInfo cred = new AuthCredInfo("digest", "*", sipUser, 0, sipPass);
                    accCfg.getSipConfig().getAuthCreds().add(cred);
                    
                } else {
                    idUri = "sip:" + localIp + ":" + sipPort;
                    accCfg.setIdUri(idUri);
                    accCfg.getRegConfig().setRegisterOnAdd(false);
                }

                accCfg.getVideoConfig().setAutoTransmitOutgoing(false);
                accCfg.getVideoConfig().setAutoShowIncoming(false);
                accCfg.getNatConfig().setIceEnabled(false); 
                
                if (account != null) {
                    try {
                        account.delete();
                    } catch (Exception e) {
                        Log.w(TAG, "Error deleting old account: " + e.getMessage());
                    }
                }
                
                account = new SipAccount(this);
                account.create(accCfg);
                
                Log.d(TAG, "SIP Account created: " + idUri);
                
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("sipUri", idUri);
                ret.put("localIp", localIp);
                call.resolve(ret);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to start SIP: " + e.getMessage(), e);
                call.reject("Failed to start SIP: " + e.getMessage());
            }
        }).start();
    }
    
    @PluginMethod
    public void stop(PluginCall call) {
        new Thread(() -> {
            try {
                if (account != null) {
                    account.delete();
                    account = null;
                    
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    call.resolve(ret);
                } else {
                    call.resolve();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping SIP: " + e.getMessage());
                call.reject("Failed to stop SIP: " + e.getMessage());
            }
        }).start();
    }
    
    @PluginMethod
    public void makeCall(PluginCall call) {
        String destination = call.getString("destination"); 
        
        if (destination == null) {
            call.reject("Destination required");
            return;
        }
        
        new Thread(() -> {
            try {
                if (account == null) {
                    call.reject("SIP Account not started");
                    return;
                }
                
                Call outCall = new Call(account);
                CallOpParam prm = new CallOpParam(true);
                outCall.makeCall(destination, prm);
                
                Log.d(TAG, "Initiated call to: " + destination);
                
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("destination", destination);
                call.resolve(ret);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to make call: " + e.getMessage(), e);
                call.reject("Failed to make call: " + e.getMessage());
            }
        }).start();
    }
    
    @PluginMethod
    public void getLocalIp(PluginCall call) {
        new Thread(() -> {
            try {
                String ip = getDeviceLocalIp();
                JSObject ret = new JSObject();
                ret.put("ip", ip);
                call.resolve(ret);
            } catch (Exception e) {
                JSObject ret = new JSObject();
                ret.put("ip", "127.0.0.1");
                call.resolve(ret);
            }
        }).start();
    }
    
    private String getDeviceLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> networkInterfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            while (networkInterfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = networkInterfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = 
                    networkInterface.getInetAddresses();
                
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting local IP: " + e.getMessage());
        }
        return "127.0.0.1";
    }
    
    public void onIncomingCall(Call incomingCall, String callerNumber, String calledNumber) {
        Log.d(TAG, "Incoming call: " + callerNumber + " -> " + calledNumber);
        
        JSObject data = new JSObject();
        data.put("type", "incoming");
        data.put("caller", callerNumber);
        data.put("called", calledNumber);
        notifyListeners("sipCallReceived", data);
        
        try {
            CallOpParam prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            incomingCall.answer(prm);
        } catch (Exception e) {
            Log.e(TAG, "Error answering call: " + e.getMessage());
        }
    }
}

class SipAccount extends Account {
    private static final String TAG = "SipAccount";
    private SipPlugin plugin;
    
    public SipAccount(SipPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
        try {
            Call call = new Call(this, prm.getCallId());
            CallInfo ci = call.getInfo();
            
            String remoteUri = ci.getRemoteUri();
            String localUri = ci.getLocalUri();
            
            Log.d(TAG, "Incoming SIP call from: " + remoteUri + " to: " + localUri);
            
            String caller = extractNumber(remoteUri);
            String called = extractNumber(localUri);
            
            plugin.onIncomingCall(call, caller, called);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming call: " + e.getMessage(), e);
        }
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
        try {
            AccountInfo ai = getInfo();
            Log.d(TAG, "Registration state: " + ai.getRegIsActive() + " code: " + prm.getCode());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private String extractNumber(String sipUri) {
        try {
            if (sipUri.contains("sip:")) {
                String temp = sipUri.substring(sipUri.indexOf("sip:") + 4);
                if (temp.contains("@")) {
                    return temp.substring(0, temp.indexOf("@"));
                }
                return temp;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting number from: " + sipUri);
        }
        return sipUri;
    }
}
