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
    private TrunkAccount trunkAccount;
    private String localIp = "";
    
    @Override
    public void load() {
        super.load();
        Log.d(TAG, "SipPlugin loaded - TRUNK MODE");
        
        // Start PJSIP service
        Context context = getContext();
        Intent serviceIntent = new Intent(context, PjsipService.class);
        context.startService(serviceIntent);
        
        // Wait for service initialization
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        pjsipService = PjsipService.getInstance();
    }
    
    @PluginMethod
    public void startTrunk(PluginCall call) {
        Integer port = call.getInt("port", 5060);
        String pbxIp = call.getString("pbxIp"); // Optional: specific PBX IP to accept calls from
        
        Log.d(TAG, "Starting SIP trunk on port " + port);
        
        new Thread(() -> {
            try {
                if (pjsipService == null || !pjsipService.isInitialized()) {
                    call.reject("PJSIP service not initialized");
                    return;
                }
                
                // Get local IP
                localIp = getDeviceLocalIp();
                
                // Create trunk account (accepts calls, no registration)
                AccountConfig accCfg = new AccountConfig();
                
                // Use local IP as identity (no registration needed)
                String sipUri = "sip:" + localIp + ":" + port;
                accCfg.setIdUri(sipUri);
                
                // No registrar (peer trunk mode)
                accCfg.getRegConfig().setRegisterOnAdd(false);
                
                // Allow all incoming calls (or restrict to PBX IP if provided)
                // No auth credentials needed for peer trunk
                
                // Media settings
                accCfg.getVideoConfig().setAutoTransmitOutgoing(false);
                accCfg.getVideoConfig().setAutoShowIncoming(false);
                
                // Network settings
                accCfg.getNatConfig().setIceEnabled(false); // Not needed for local network
                
                // Create account
                if (trunkAccount != null) {
                    try {
                        trunkAccount.delete();
                    } catch (Exception e) {
                        Log.w(TAG, "Error deleting old trunk: " + e.getMessage());
                    }
                }
                
                trunkAccount = new TrunkAccount(this);
                trunkAccount.create(accCfg);
                
                Log.d(TAG, "SIP trunk started: " + sipUri);
                
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("sipUri", sipUri);
                ret.put("localIp", localIp);
                ret.put("port", port);
                ret.put("message", "Trunk ready. Configure Grandstream to peer with: " + localIp + ":" + port);
                call.resolve(ret);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to start trunk: " + e.getMessage(), e);
                call.reject("Failed to start trunk: " + e.getMessage());
            }
        }).start();
    }
    
    @PluginMethod
    public void stopTrunk(PluginCall call) {
        new Thread(() -> {
            try {
                if (trunkAccount != null) {
                    trunkAccount.delete();
                    trunkAccount = null;
                    
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    call.resolve(ret);
                } else {
                    call.resolve();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping trunk: " + e.getMessage());
                call.reject("Failed to stop trunk: " + e.getMessage());
            }
        }).start();
    }
    
    @PluginMethod
    public void makeCall(PluginCall call) {
        String destination = call.getString("destination"); // e.g., "sip:1000@192.168.1.100"
        
        if (destination == null) {
            call.reject("Destination required");
            return;
        }
        
        new Thread(() -> {
            try {
                if (trunkAccount == null) {
                    call.reject("Trunk not started");
                    return;
                }
                
                // Make outbound call to PBX (when GSM receives call)
                Call outCall = new Call(trunkAccount);
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
    
    @PluginMethod
    public void getStatus(PluginCall call) {
        new Thread(() -> {
            try {
                JSObject ret = new JSObject();
                
                if (trunkAccount != null) {
                    AccountInfo info = trunkAccount.getInfo();
                    ret.put("active", true);
                    ret.put("uri", info.getUri());
                    ret.put("localIp", localIp);
                } else {
                    ret.put("active", false);
                }
                
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Failed to get status: " + e.getMessage());
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
    
    // Called when PBX sends call to trunk
    public void onIncomingCallFromPbx(Call incomingCall, String callerNumber, String calledNumber) {
        Log.d(TAG, "Incoming call from PBX: " + callerNumber + " -> " + calledNumber);
        
        // Notify JavaScript layer to place GSM call
        JSObject data = new JSObject();
        data.put("type", "incomingFromPbx");
        data.put("caller", callerNumber);
        data.put("called", calledNumber);
        notifyListeners("sipCallReceived", data);
        
        // Auto-answer the SIP call (bridge will be established after GSM connects)
        try {
            CallOpParam prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            incomingCall.answer(prm);
        } catch (Exception e) {
            Log.e(TAG, "Error answering call: " + e.getMessage());
        }
    }
}

// Trunk Account - accepts incoming calls
class TrunkAccount extends Account {
    private static final String TAG = "TrunkAccount";
    private SipPlugin plugin;
    
    public TrunkAccount(SipPlugin plugin) {
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
            
            // Extract numbers from SIP URIs
            String caller = extractNumber(remoteUri);
            String called = extractNumber(localUri);
            
            // Notify plugin
            plugin.onIncomingCallFromPbx(call, caller, called);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming call: " + e.getMessage(), e);
        }
    }
    
    private String extractNumber(String sipUri) {
        // Extract number from sip:1000@192.168.1.100 -> 1000
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
