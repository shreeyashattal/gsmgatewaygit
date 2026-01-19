package com.gsmgateway;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Asterisk Manager Interface (AMI) client - Dual SIM version
 */
public class AMIClient {
    private static final String TAG = "AMIClient";
    
    // Use Config class instead of hardcoding
    private final String amiHost;
    private final int amiPort;
    private final String amiUsername;
    private final String amiSecret;
    
    public AMIClient() {
        this.amiHost = Config.AMI_HOST;
        this.amiPort = Config.AMI_PORT;
        this.amiUsername = Config.AMI_USERNAME;
        this.amiSecret = Config.AMI_SECRET;
    }
    
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private Thread readerThread;
    private volatile boolean running = false;
    
    private AMIEventListener eventListener;
    private BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    
    public interface AMIEventListener {
        void onIncomingSIPCall(String channel, String callerID, String trunk);
        void onCallHangup(String channel);
    }
    
    public boolean connect() {
        try {
            socket = new Socket(amiHost, amiPort);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            
            String welcome = readUntilBlankLine();
            Log.i(TAG, "AMI Welcome: " + welcome);
            
            if (!login()) {
                Log.e(TAG, "AMI login failed");
                disconnect();
                return false;
            }
            
            running = true;
            readerThread = new Thread(this::eventLoop);
            readerThread.start();
            
            Log.i(TAG, "Connected to Asterisk AMI");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to AMI: " + e.getMessage(), e);
            return false;
        }
    }
    
    public void disconnect() {
        running = false;
        
        if (readerThread != null) {
            readerThread.interrupt();
        }
        
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing AMI socket", e);
        }
        
        Log.i(TAG, "Disconnected from AMI");
    }
    
    private boolean login() throws Exception {
        sendAction("Login",
            "Username", amiUsername,
            "Secret", amiSecret
        );
        
        String response = responseQueue.take();
        return response.contains("Success");
    }
    
    /**
     * Originate a call from Asterisk to the Grandstream PBX
     * Called when an incoming GSM call arrives
     * 
     * @param incomingNumber The caller's number
     * @param trunk The trunk to use (trunk1 or trunk2)
     */
    public boolean originateCallToPBX(String incomingNumber, String trunk) {
        try {
            String actionID = "originate_" + System.currentTimeMillis();
            String context = trunk.equals("trunk1") ? "from-gsm1" : "from-gsm2";
            
            sendAction("Originate",
                "ActionID", actionID,
                "Channel", "SIP/grandstream-" + trunk + "/" + incomingNumber,
                "Context", context,
                "Exten", "s",
                "Priority", "1",
                "CallerID", incomingNumber,
                "Variable", "GSM_INCOMING=1,TRUNK=" + trunk,
                "Async", "true"
            );
            
            Log.i(TAG, "Originated call to PBX via " + trunk + " for " + incomingNumber);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to originate call: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Answer a channel
     */
    public boolean answerChannel(String channel) {
        try {
            sendAction("Redirect",
                "Channel", channel,
                "Context", "gsm-bridge",
                "Exten", "answer",
                "Priority", "1"
            );
            
            Log.i(TAG, "Answered channel: " + channel);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to answer channel: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Hangup a channel
     */
    public boolean hangupChannel(String channel) {
        try {
            sendAction("Hangup",
                "Channel", channel
            );
            
            Log.i(TAG, "Hung up channel: " + channel);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to hangup channel: " + e.getMessage(), e);
            return false;
        }
    }
    
    private void sendAction(String action, String... keyValues) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Action: ").append(action).append("\r\n");
        
        for (int i = 0; i < keyValues.length; i += 2) {
            sb.append(keyValues[i]).append(": ").append(keyValues[i + 1]).append("\r\n");
        }
        
        sb.append("\r\n");
        
        synchronized (writer) {
            writer.write(sb.toString());
            writer.flush();
        }
    }
    
    private void eventLoop() {
        try {
            while (running && !Thread.interrupted()) {
                String message = readUntilBlankLine();
                if (message == null) break;
                
                Map<String, String> event = parseAMIMessage(message);
                
                if (event.containsKey("Response")) {
                    responseQueue.offer(message);
                } else if (event.containsKey("Event")) {
                    handleEvent(event);
                }
            }
        } catch (Exception e) {
            if (running) {
                Log.e(TAG, "AMI event loop error: " + e.getMessage(), e);
            }
        }
    }
    
    private void handleEvent(Map<String, String> event) {
        String eventType = event.get("Event");
        
        if (eventType == null) return;
        
        switch (eventType) {
            case "Newchannel":
                String channel = event.get("Channel");
                String callerID = event.get("CallerIDNum");
                
                // Determine which trunk based on channel name
                String trunk = null;
                if (channel != null) {
                    if (channel.contains("trunk1") || channel.contains("grandstream-trunk1")) {
                        trunk = "trunk1";
                    } else if (channel.contains("trunk2") || channel.contains("grandstream-trunk2")) {
                        trunk = "trunk2";
                    }
                }
                
                if (trunk != null) {
                    Log.i(TAG, "Incoming SIP call from PBX via " + trunk + ": " + callerID);
                    if (eventListener != null) {
                        eventListener.onIncomingSIPCall(channel, callerID, trunk);
                    }
                }
                break;
                
            case "Hangup":
                String hangupChannel = event.get("Channel");
                Log.i(TAG, "Channel hung up: " + hangupChannel);
                if (eventListener != null) {
                    eventListener.onCallHangup(hangupChannel);
                }
                break;
                
            case "OriginateResponse":
                // PBX answered the originated call
                String responseChannel = event.get("Channel");
                String response = event.get("Response");
                if ("Success".equals(response)) {
                    Log.i(TAG, "PBX answered originated call: " + responseChannel);
                    // The GatewayService will detect this via call state
                }
                break;
        }
    }
    
    private String readUntilBlankLine() throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                break;
            }
            sb.append(line).append("\n");
        }
        
        return sb.length() > 0 ? sb.toString() : null;
    }
    
    private Map<String, String> parseAMIMessage(String message) {
        Map<String, String> map = new HashMap<>();
        
        for (String line : message.split("\n")) {
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                map.put(key, value);
            }
        }
        
        return map;
    }
    
    public void setEventListener(AMIEventListener listener) {
        this.eventListener = listener;
    }
    
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}