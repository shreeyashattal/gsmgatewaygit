package com.shreeyash.gateway;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConnectionService for GSM-SIP Gateway
 *
 * This makes us the DIALER. We handle all incoming and outgoing calls,
 * which gives us full control over audio routing.
 *
 * When registered as the default dialer:
 * - Incoming calls come to us via onCreateIncomingConnection
 * - Outgoing calls come to us via onCreateOutgoingConnection
 * - WE control audio, not the default phone app
 */
public class GatewayConnectionService extends ConnectionService {
    private static final String TAG = "GatewayConnService";

    // Phone Account IDs for each SIM
    public static final String ACCOUNT_ID_SIM1 = "gsm_gateway_sim1";
    public static final String ACCOUNT_ID_SIM2 = "gsm_gateway_sim2";

    // Static instance for access from other components
    private static GatewayConnectionService instance;

    // Active connections
    private final ConcurrentHashMap<String, GatewayConnection> activeConnections = new ConcurrentHashMap<>();

    // Connection listener (GatewayService will register)
    private static ConnectionServiceListener serviceListener;

    public interface ConnectionServiceListener {
        void onIncomingCall(GatewayConnection connection);
        void onOutgoingCall(GatewayConnection connection);
        void onCallAnswered(GatewayConnection connection);
        void onCallEnded(GatewayConnection connection, DisconnectCause cause);
    }

    public static void setServiceListener(ConnectionServiceListener listener) {
        serviceListener = listener;
    }

    public static GatewayConnectionService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║     GATEWAY CONNECTION SERVICE CREATED                     ║");
        Log.i(TAG, "║     We are now the dialer!                                 ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.i(TAG, "GatewayConnectionService destroyed");
    }

    // ==================== Phone Account Registration ====================

    /**
     * Register phone accounts for our gateway
     * Call this from MainActivity or GatewayService
     */
    public static void registerPhoneAccounts(Context context) {
        TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) {
            Log.e(TAG, "TelecomManager not available");
            return;
        }

        ComponentName componentName = new ComponentName(context, GatewayConnectionService.class);

        // Register SIM1 account
        registerPhoneAccount(context, telecomManager, componentName, ACCOUNT_ID_SIM1, "GSM Gateway SIM1", 1);

        // Register SIM2 account
        registerPhoneAccount(context, telecomManager, componentName, ACCOUNT_ID_SIM2, "GSM Gateway SIM2", 2);

        Log.i(TAG, "Phone accounts registered");
    }

    private static void registerPhoneAccount(Context context, TelecomManager telecomManager,
                                             ComponentName componentName, String accountId,
                                             String label, int simSlot) {
        PhoneAccountHandle handle = new PhoneAccountHandle(componentName, accountId);

        PhoneAccount.Builder builder = PhoneAccount.builder(handle, label)
            .setCapabilities(
                PhoneAccount.CAPABILITY_CONNECTION_MANAGER
            )
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            .setShortDescription("GSM-SIP Gateway for SIM" + simSlot);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher));
        }

        PhoneAccount account = builder.build();
        telecomManager.registerPhoneAccount(account);

        Log.i(TAG, "Registered phone account: " + accountId);

        // Note: Phone account needs to be enabled to intercept calls
        // This can be done via:
        // - Settings > Apps > Default apps > Phone app, or
        // - ADB: telecom set-phone-account-enabled ... true
    }

    /**
     * Get PhoneAccountHandle for a SIM slot
     */
    public static PhoneAccountHandle getPhoneAccountHandle(Context context, int simSlot) {
        ComponentName componentName = new ComponentName(context, GatewayConnectionService.class);
        String accountId = (simSlot == 1) ? ACCOUNT_ID_SIM1 : ACCOUNT_ID_SIM2;
        return new PhoneAccountHandle(componentName, accountId);
    }

    // ==================== Incoming Calls ====================

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle connectionManagerPhoneAccount,
                                                  ConnectionRequest request) {
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ INCOMING CONNECTION REQUEST                                 │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");

        Bundle extras = request.getExtras();
        String phoneNumber = null;

        // Extract phone number from request
        if (request.getAddress() != null) {
            phoneNumber = request.getAddress().getSchemeSpecificPart();
        }

        // Check extras for additional info
        if (extras != null) {
            if (phoneNumber == null) {
                phoneNumber = extras.getString("phone_number");
            }
            Log.d(TAG, "Extras: " + extras);
        }

        // Determine SIM slot from account
        int simSlot = 1;
        if (connectionManagerPhoneAccount != null) {
            String accountId = connectionManagerPhoneAccount.getId();
            if (ACCOUNT_ID_SIM2.equals(accountId)) {
                simSlot = 2;
            }
        }

        Log.i(TAG, "Incoming call from: " + phoneNumber + " on SIM" + simSlot);

        // Create our connection
        GatewayConnection connection = new GatewayConnection(
            GatewayConnection.Direction.INCOMING_GSM,
            phoneNumber,
            simSlot
        );

        // Set initial state
        connection.setConnectionRinging();

        // Set up listener
        connection.setConnectionListener(new GatewayConnection.ConnectionListener() {
            @Override
            public void onConnectionAnswered(GatewayConnection conn) {
                Log.i(TAG, "Connection answered: " + conn.getPhoneNumber());
                if (serviceListener != null) {
                    serviceListener.onCallAnswered(conn);
                }
            }

            @Override
            public void onConnectionDisconnected(GatewayConnection conn, DisconnectCause cause) {
                Log.i(TAG, "Connection disconnected: " + conn.getPhoneNumber());
                activeConnections.remove(getConnectionKey(conn));
                if (serviceListener != null) {
                    serviceListener.onCallEnded(conn, cause);
                }
            }

            @Override
            public void onConnectionHeld(GatewayConnection conn) {
                Log.i(TAG, "Connection held: " + conn.getPhoneNumber());
            }

            @Override
            public void onConnectionUnheld(GatewayConnection conn) {
                Log.i(TAG, "Connection unheld: " + conn.getPhoneNumber());
            }
        });

        // Store connection
        activeConnections.put(getConnectionKey(connection), connection);

        // Notify listener
        if (serviceListener != null) {
            serviceListener.onIncomingCall(connection);
        }

        return connection;
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount,
                                                  ConnectionRequest request) {
        Log.e(TAG, "Failed to create incoming connection");
    }

    // ==================== Outgoing Calls ====================

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount,
                                                  ConnectionRequest request) {
        Log.i(TAG, "┌─────────────────────────────────────────────────────────────┐");
        Log.i(TAG, "│ OUTGOING CONNECTION REQUEST                                 │");
        Log.i(TAG, "└─────────────────────────────────────────────────────────────┘");

        String phoneNumber = null;
        if (request.getAddress() != null) {
            phoneNumber = request.getAddress().getSchemeSpecificPart();
        }

        Log.i(TAG, "Outgoing call to: " + phoneNumber);

        // Determine SIM slot from account
        int simSlot = 1;
        if (connectionManagerPhoneAccount != null) {
            String accountId = connectionManagerPhoneAccount.getId();
            if (ACCOUNT_ID_SIM2.equals(accountId)) {
                simSlot = 2;
            }
        }

        Log.i(TAG, "Managing outgoing call to: " + phoneNumber + " on SIM" + simSlot);

        // Create our connection
        GatewayConnection connection = new GatewayConnection(
            GatewayConnection.Direction.OUTGOING_GSM,
            phoneNumber,
            simSlot
        );

        // Set initial state
        connection.setConnectionDialing();

        // Set up listener
        connection.setConnectionListener(new GatewayConnection.ConnectionListener() {
            @Override
            public void onConnectionAnswered(GatewayConnection conn) {
                Log.i(TAG, "Outgoing connection answered: " + conn.getPhoneNumber());
                if (serviceListener != null) {
                    serviceListener.onCallAnswered(conn);
                }
            }

            @Override
            public void onConnectionDisconnected(GatewayConnection conn, DisconnectCause cause) {
                Log.i(TAG, "Outgoing connection disconnected: " + conn.getPhoneNumber());
                activeConnections.remove(getConnectionKey(conn));
                if (serviceListener != null) {
                    serviceListener.onCallEnded(conn, cause);
                }
            }

            @Override
            public void onConnectionHeld(GatewayConnection conn) {}

            @Override
            public void onConnectionUnheld(GatewayConnection conn) {}
        });

        // Store connection
        activeConnections.put(getConnectionKey(connection), connection);

        // Notify listener
        if (serviceListener != null) {
            serviceListener.onOutgoingCall(connection);
        }

        return connection;
    }

    @Override
    public void onCreateOutgoingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount,
                                                  ConnectionRequest request) {
        Log.e(TAG, "Failed to create outgoing connection");
    }

    // ==================== Helper Methods ====================

    private String getConnectionKey(GatewayConnection connection) {
        return connection.getSimSlot() + "_" + connection.getPhoneNumber() + "_" + System.identityHashCode(connection);
    }

    /**
     * Get active connection for a SIM slot
     */
    public GatewayConnection getActiveConnection(int simSlot) {
        for (GatewayConnection conn : activeConnections.values()) {
            if (conn.getSimSlot() == simSlot) {
                return conn;
            }
        }
        return null;
    }

    /**
     * Place an outgoing GSM call using ITelephony.call() via reflection.
     * Requires hidden_api_policy=1 (set via Magisk service.sh).
     * ITelephony.call() dials directly through the telephony layer,
     * bypassing TelecomManager and our SimCallManager routing.
     */
    public static void placeCall(Context context, String phoneNumber, int simSlot) {
        Log.i(TAG, "╔════════════════════════════════════════════════════════════╗");
        Log.i(TAG, "║ PLACING GSM CALL via ITelephony                            ║");
        Log.i(TAG, "║ Number: " + String.format("%-50s", phoneNumber) + " ║");
        Log.i(TAG, "║ SIM: " + simSlot + "                                                     ║");
        Log.i(TAG, "╚════════════════════════════════════════════════════════════╝");

        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                Log.e(TAG, "TelephonyManager not available");
                return;
            }

            // Get ITelephony interface via reflection
            // Requires hidden_api_policy=1 to be set
            Method getITelephony = TelephonyManager.class.getDeclaredMethod("getITelephony");
            getITelephony.setAccessible(true);
            Object iTelephony = getITelephony.invoke(telephonyManager);

            if (iTelephony == null) {
                Log.e(TAG, "Could not get ITelephony interface - is hidden_api_policy=1 set?");
                return;
            }

            Log.i(TAG, "Got ITelephony interface: " + iTelephony.getClass().getName());

            // List available methods for debugging
            Method[] methods = iTelephony.getClass().getMethods();
            for (Method m : methods) {
                if (m.getName().contains("call") || m.getName().contains("dial")) {
                    Log.d(TAG, "ITelephony method: " + m.getName() + "(" +
                          java.util.Arrays.toString(m.getParameterTypes()) + ")");
                }
            }

            // Try call(String callingPackage, String number)
            boolean placed = false;
            try {
                Method callMethod = iTelephony.getClass().getMethod("call",
                    String.class, String.class);
                Log.i(TAG, "Calling ITelephony.call(package, number)");
                callMethod.invoke(iTelephony, context.getPackageName(), phoneNumber);
                Log.i(TAG, ">>> ITelephony.call() SUCCEEDED <<<");
                placed = true;
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "ITelephony.call(String,String) not found, trying other signatures");
            }

            if (!placed) {
                // Try call(String callingPackage, String number, boolean isEmergency)
                try {
                    Method callMethod = iTelephony.getClass().getMethod("call",
                        String.class, String.class, boolean.class);
                    Log.i(TAG, "Calling ITelephony.call(package, number, false)");
                    callMethod.invoke(iTelephony, context.getPackageName(), phoneNumber, false);
                    Log.i(TAG, ">>> ITelephony.call(3-arg) SUCCEEDED <<<");
                    placed = true;
                } catch (NoSuchMethodException e) {
                    Log.d(TAG, "ITelephony.call(String,String,boolean) not found");
                }
            }

            if (!placed) {
                // Try dial(String number)
                try {
                    Method dialMethod = iTelephony.getClass().getMethod("dial", String.class);
                    Log.i(TAG, "Calling ITelephony.dial(number)");
                    dialMethod.invoke(iTelephony, phoneNumber);
                    Log.i(TAG, ">>> ITelephony.dial() SUCCEEDED <<<");
                    placed = true;
                } catch (NoSuchMethodException e) {
                    Log.d(TAG, "ITelephony.dial(String) not found");
                }
            }

            if (!placed) {
                Log.e(TAG, "No suitable ITelephony call/dial method found");
                Log.e(TAG, "Available methods on ITelephony:");
                for (Method m : methods) {
                    Log.e(TAG, "  " + m.getName());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "ITelephony placeCall failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get the actual SIM's PhoneAccountHandle (from TelephonyConnectionService)
     */
    private static PhoneAccountHandle getSimPhoneAccountHandle(Context context, int simSlot) {
        TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) return null;

        try {
            // Get subscription ID for the SIM slot
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subManager == null) return null;

            int[] subIds = null;
            try {
                // Try to get subscription IDs for the slot
                Method getSubIds = SubscriptionManager.class.getMethod("getSubscriptionIds", int.class);
                subIds = (int[]) getSubIds.invoke(subManager, simSlot - 1);
            } catch (Exception e) {
                Log.d(TAG, "getSubscriptionIds not available: " + e.getMessage());
            }

            int targetSubId = -1;
            if (subIds != null && subIds.length > 0) {
                targetSubId = subIds[0];
            }

            // Find the PhoneAccountHandle for this subscription
            for (PhoneAccountHandle handle : telecomManager.getCallCapablePhoneAccounts()) {
                PhoneAccount account = telecomManager.getPhoneAccount(handle);
                if (account == null) continue;

                // Check if this is a SIM account (TelephonyConnectionService)
                ComponentName component = handle.getComponentName();
                if (component != null &&
                    component.getClassName().contains("TelephonyConnectionService")) {

                    // If we have a target subId, match it
                    if (targetSubId != -1) {
                        String accountId = handle.getId();
                        // Account ID format is typically the subscription ID
                        try {
                            int accountSubId = Integer.parseInt(accountId);
                            if (accountSubId == targetSubId) {
                                Log.i(TAG, "Found matching SIM account for slot " + simSlot + ": " + handle);
                                return handle;
                            }
                        } catch (NumberFormatException e) {
                            // Account ID might not be numeric, check other properties
                        }
                    }

                    // Fallback: return first/second TelephonyConnectionService account based on slot
                    // This is less reliable but better than nothing
                }
            }

            // Last resort: get call capable accounts and pick by index
            java.util.List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
            int simIndex = simSlot - 1;
            if (accounts != null && accounts.size() > simIndex) {
                PhoneAccountHandle handle = accounts.get(simIndex);
                Log.i(TAG, "Using call-capable account at index " + simIndex + ": " + handle);
                return handle;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting SIM PhoneAccountHandle: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * Report incoming call to system (for SIP-initiated GSM calls)
     */
    public static void reportIncomingCall(Context context, String phoneNumber, int simSlot) {
        TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) {
            Log.e(TAG, "TelecomManager not available");
            return;
        }

        Bundle extras = new Bundle();
        extras.putString("phone_number", phoneNumber);
        extras.putInt("sim_slot", simSlot);

        PhoneAccountHandle handle = getPhoneAccountHandle(context, simSlot);

        try {
            telecomManager.addNewIncomingCall(handle, extras);
            Log.i(TAG, "Reported incoming call from " + phoneNumber + " on SIM" + simSlot);
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for addNewIncomingCall: " + e.getMessage());
        }
    }
}
