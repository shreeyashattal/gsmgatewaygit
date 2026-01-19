package com.gsmgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Main gateway service - manages dual SIM GSM-to-VoIP gateway
 * 
 * CORRECTED CALL FLOW:
 * - Incoming GSM: Ring → Dial PBX → PBX answers → Answer GSM → Bridge RTP
 * - Outgoing GSM: PBX dials → Dial GSM → GSM answers → Answer PBX → Bridge RTP
 */
public class GatewayService extends Service implements AMIClient.AMIEventListener {
    private static final String TAG = "GatewayService";
    private static final String CHANNEL_ID = "gateway_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    private DualSIMManager simManager;
    private Map<Integer, RTPManager> rtpManagers;  // One per SIM slot
    private AMIClient amiClient;
    private TelephonyManager telephonyManager;
    private TelecomManager telecomManager;
    private PowerManager.WakeLock wakeLock;
    
    // Active call sessions (one per SIM slot)
    private Map<Integer, CallSession> activeSessions;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Gateway service created");
        
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        telecomManager = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
        
        // Initialize dual SIM manager
        simManager = new DualSIMManager(this);
        
        // Initialize RTP managers for both SIMs
        rtpManagers = new HashMap<>();
        rtpManagers.put(1, new RTPManager(simManager.getRTPPort(1)));
        rtpManagers.put(2, new RTPManager(simManager.getRTPPort(2)));
        
        // Initialize active sessions
        activeSessions = new HashMap<>();
        
        // Acquire wakelock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GatewayService::WakeLock"
        );
        wakeLock.acquire();
        
        // Initialize AMI client
        amiClient = new AMIClient();
        amiClient.setEventListener(this);
        
        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification("Service starting..."));
        
        // Connect to Asterisk
        if (!amiClient.connect()) {
            Log.e(TAG, "Failed to connect to Asterisk AMI");
            updateNotification("ERROR: Cannot connect to Asterisk");
        } else {
            updateNotification("Ready - " + (simManager.isDualSIMActive() ? "Dual SIM Active" : "Waiting for SIMs"));
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        
        String action = intent.getAction();
        int simSlot = intent.getIntExtra("sim_slot", 0);
        
        if (action == null) {
            return START_STICKY;
        }
        
        Log.i(TAG, "Action: " + action + ", SIM: " + simSlot);
        
        switch (action) {
            case "INCOMING_GSM_CALL":
                String incomingNumber = intent.getStringExtra("number");
                handleIncomingGSMCall(simSlot, incomingNumber);
                break;
                
            case "GSM_CALL_ANSWERED":
                handleGSMCallAnswered(simSlot);
                break;
                
            case "GSM_CALL_ENDED":
                handleGSMCallEnded(simSlot);
                break;
        }
        
        return START_STICKY;
    }
    
    /**
     * INCOMING FLOW: GSM call arrives
     * Step 1: Receive incoming call notification
     */
    private void handleIncomingGSMCall(int simSlot, String number) {
        Log.i(TAG, String.format("Incoming GSM call on SIM%d from: %s", simSlot, number));
        
        // Check if SIM is active
        if (!simManager.isSimActive(simSlot)) {
            Log.e(TAG, "SIM" + simSlot + " is not active");
            return;
        }
        
        // Create call session
        CallSession session = new CallSession(simSlot, number, CallSession.CallDirection.INCOMING_GSM);
        session.setState(CallSession.CallState.GSM_RINGING);
        activeSessions.put(simSlot, session);
        
        updateNotification(String.format("Incoming SIM%d: %s", simSlot, number));
        
        // Step 2: Originate call to PBX via Asterisk
        String trunk = simManager.getAsteriskTrunk(simSlot);
        if (!amiClient.originateCallToPBX(number, trunk)) {
            Log.e(TAG, "Failed to originate call to PBX");
            endGSMCall(simSlot);
            activeSessions.remove(simSlot);
            return;
        }
        
        session.setState(CallSession.CallState.SIP_DIALING);
        Log.i(TAG, "Originated call to PBX via " + trunk);
    }
    
    /**
     * INCOMING FLOW: AMI notifies us that PBX answered
     * Step 3: PBX has answered, now answer the GSM call
     */
    private void handlePBXAnswered(String channel, int simSlot) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null || !session.isIncomingGSM()) {
            Log.w(TAG, "No active incoming session for SIM" + simSlot);
            return;
        }
        
        Log.i(TAG, "PBX answered on " + channel + " for SIM" + simSlot);
        session.setSipChannel(channel);
        session.setState(CallSession.CallState.SIP_ANSWERED);
        
        // Step 4: Answer the GSM call
        answerGSMCall(simSlot);
    }
    
    /**
     * Answer GSM call
     */
    private void answerGSMCall(int simSlot) {
        try {
            PhoneAccountHandle account = simManager.getPhoneAccount(simSlot);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (account != null) {
                    telecomManager.acceptRingingCall();
                } else {
                    telecomManager.acceptRingingCall();
                }
            } else {
                answerPhoneReflection();
            }
            
            Log.i(TAG, "GSM call answered on SIM" + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "Failed to answer GSM call: " + e.getMessage(), e);
        }
    }
    
    /**
     * Reflection-based answer for Android < P
     */
    private void answerPhoneReflection() {
        try {
            Class<?> classTelephony = Class.forName(telephonyManager.getClass().getName());
            Method methodAnswerCall = classTelephony.getDeclaredMethod("answerRingingCall");
            methodAnswerCall.setAccessible(true);
            methodAnswerCall.invoke(telephonyManager);
        } catch (Exception e) {
            Log.e(TAG, "Reflection answer failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * INCOMING FLOW: GSM call has been answered (connected)
     * Step 5: Start RTP audio bridge
     */
    private void handleGSMCallAnswered(int simSlot) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null) {
            Log.w(TAG, "No active session for SIM" + simSlot);
            return;
        }
        
        Log.i(TAG, "GSM call connected on SIM" + simSlot);
        
        if (session.isIncomingGSM()) {
            session.setState(CallSession.CallState.GSM_ANSWERED);
        } else {
            session.setState(CallSession.CallState.GSM_ANSWERED);
        }
        
        // Check if we can start RTP
        if (session.canStartRTP()) {
            startRTPBridge(simSlot);
        }
    }
    
    /**
     * Start RTP audio bridge
     */
    private void startRTPBridge(int simSlot) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null) {
            Log.w(TAG, "No session to bridge for SIM" + simSlot);
            return;
        }
        
        Log.i(TAG, "Starting RTP bridge for SIM" + simSlot);
        updateNotification(String.format("Bridging SIM%d call", simSlot));
        
        // Start RTP manager
        RTPManager rtpManager = rtpManagers.get(simSlot);
        if (!rtpManager.start()) {
            Log.e(TAG, "Failed to start RTP manager for SIM" + simSlot);
            endCall(simSlot);
            return;
        }
        
        session.setRtpActive(true);
        session.setState(CallSession.CallState.BRIDGED);
        
        updateNotification(String.format("Active: SIM%d ↔ %s", simSlot, session.getCallerNumber()));
        Log.i(TAG, "Call fully bridged: " + session);
    }
    
    /**
     * OUTGOING FLOW: AMI notifies us of incoming SIP call (outgoing GSM request)
     * Step 1: PBX wants to make a call via this gateway
     */
    @Override
    public void onIncomingSIPCall(String channel, String callerID, String trunk) {
        // Determine which SIM to use based on trunk
        int simSlot = trunk.equals("trunk1") ? 1 : 2;
        
        Log.i(TAG, String.format("Incoming SIP call on %s to dial: %s via SIM%d", channel, callerID, simSlot));
        
        // Check if this SIM is already busy
        if (activeSessions.containsKey(simSlot)) {
            Log.w(TAG, "SIM" + simSlot + " is busy - rejecting call");
            amiClient.hangupChannel(channel);
            return;
        }
        
        // Check if SIM is active
        if (!simManager.isSimActive(simSlot)) {
            Log.e(TAG, "SIM" + simSlot + " is not active");
            amiClient.hangupChannel(channel);
            return;
        }
        
        // Create call session
        CallSession session = new CallSession(simSlot, callerID, CallSession.CallDirection.OUTGOING_GSM);
        session.setState(CallSession.CallState.SIP_RINGING);
        session.setSipChannel(channel);
        activeSessions.put(simSlot, session);
        
        updateNotification(String.format("Outgoing SIM%d: %s", simSlot, callerID));
        
        // Step 2: Initiate GSM call
        initiateGSMCall(simSlot, callerID);
    }
    
    /**
     * Initiate outgoing GSM call
     */
    private void initiateGSMCall(int simSlot, String number) {
        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + number));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // Set phone account for specific SIM
            PhoneAccountHandle account = simManager.getPhoneAccount(simSlot);
            if (account != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                callIntent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account);
            }
            
            startActivity(callIntent);
            
            CallSession session = activeSessions.get(simSlot);
            if (session != null) {
                session.setState(CallSession.CallState.GSM_RINGING);
            }
            
            Log.i(TAG, "Initiated GSM call to " + number + " on SIM" + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate GSM call: " + e.getMessage(), e);
            
            CallSession session = activeSessions.get(simSlot);
            if (session != null && session.getSipChannel() != null) {
                amiClient.hangupChannel(session.getSipChannel());
            }
            activeSessions.remove(simSlot);
        }
    }
    
    /**
     * OUTGOING FLOW: GSM call connected
     * Step 3: Answer the SIP call and start RTP
     */
    // This is handled by handleGSMCallAnswered() which checks canStartRTP()
    
    /**
     * GSM call ended
     */
    private void handleGSMCallEnded(int simSlot) {
        Log.i(TAG, "GSM call ended on SIM" + simSlot);
        endCall(simSlot);
    }
    
    /**
     * AMI Event: SIP call hangup
     */
    @Override
    public void onCallHangup(String channel) {
        Log.i(TAG, "SIP call hung up: " + channel);
        
        // Find which session this channel belongs to
        for (Map.Entry<Integer, CallSession> entry : activeSessions.entrySet()) {
            CallSession session = entry.getValue();
            if (channel.equals(session.getSipChannel())) {
                int simSlot = entry.getKey();
                Log.i(TAG, "SIP hangup for SIM" + simSlot);
                endGSMCall(simSlot);
                break;
            }
        }
    }
    
    /**
     * End a complete call session
     */
    private void endCall(int simSlot) {
        CallSession session = activeSessions.get(simSlot);
        if (session == null) {
            return;
        }
        
        session.setState(CallSession.CallState.ENDING);
        Log.i(TAG, "Ending call session: " + session);
        
        // Stop RTP
        RTPManager rtpManager = rtpManagers.get(simSlot);
        if (rtpManager != null && rtpManager.isRunning()) {
            rtpManager.stop();
        }
        
        // Hangup SIP channel
        if (session.getSipChannel() != null) {
            amiClient.hangupChannel(session.getSipChannel());
        }
        
        // End GSM call
        endGSMCall(simSlot);
        
        // Remove session
        activeSessions.remove(simSlot);
        
        // Update notification
        if (activeSessions.isEmpty()) {
            updateNotification("Ready - " + (simManager.isDualSIMActive() ? "Dual SIM Active" : "Waiting for SIMs"));
        } else {
            updateNotification(getActiveCallsSummary());
        }
    }
    
    /**
     * End GSM call
     */
    private void endGSMCall(int simSlot) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall();
            } else {
                endCallReflection();
            }
            Log.i(TAG, "Ended GSM call on SIM" + simSlot);
        } catch (Exception e) {
            Log.e(TAG, "Failed to end GSM call: " + e.getMessage(), e);
        }
    }
    
    /**
     * Reflection-based end call
     */
    private void endCallReflection() {
        try {
            Class<?> classTelephony = Class.forName(telephonyManager.getClass().getName());
            Method methodEndCall = classTelephony.getDeclaredMethod("endCall");
            methodEndCall.setAccessible(true);
            methodEndCall.invoke(telephonyManager);
        } catch (Exception e) {
            Log.e(TAG, "Reflection end call failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get summary of active calls for notification
     */
    private String getActiveCallsSummary() {
        StringBuilder sb = new StringBuilder();
        for (CallSession session : activeSessions.values()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(String.format("SIM%d: %s", session.getSimSlot(), 
                session.isBridged() ? "Active" : "Connecting"));
        }
        return sb.toString();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Clean up all sessions
        for (int simSlot : activeSessions.keySet()) {
            endCall(simSlot);
        }
        
        // Stop all RTP managers
        for (RTPManager rtpManager : rtpManagers.values()) {
            rtpManager.stop();
        }
        
        // Disconnect AMI
        if (amiClient != null) {
            amiClient.disconnect();
        }
        
        // Release wakelock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        Log.i(TAG, "Gateway service destroyed");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private Notification createNotification(String text) {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "GSM Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .build();
    }
    
    private void updateNotification(String text) {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(text));
    }
}