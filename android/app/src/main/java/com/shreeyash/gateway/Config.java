package com.shreeyash.gateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configuration manager for GSM-SIP Gateway
 * Direct SIP registration with PBX (no Asterisk middleware)
 */
public class Config {
    private static final String PREFS_NAME = "gateway_config";

    // Default PBX settings
    public static final int DEFAULT_PBX_PORT = 5060;  // Standard SIP port

    // Local SIP port (Android blocks 5060, so we use a higher port)
    public static final int LOCAL_SIP_PORT = 5080;

    // RTP Ports for each SIM
    public static final int RTP_PORT_SIM1 = 10000;
    public static final int RTP_PORT_SIM2 = 10002;

    // SharedPreferences keys
    private static final String KEY_PBX_HOST = "pbx_host";
    private static final String KEY_PBX_PORT = "pbx_port";
    private static final String KEY_SIM1_USER = "sim1_user";
    private static final String KEY_SIM1_PASS = "sim1_pass";
    private static final String KEY_SIM2_USER = "sim2_user";
    private static final String KEY_SIM2_PASS = "sim2_pass";
    private static final String KEY_LOCAL_IP = "local_ip";

    private SharedPreferences prefs;

    public Config(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get PBX host address
     */
    public String getPBXHost() {
        return prefs.getString(KEY_PBX_HOST, null);
    }

    /**
     * Set PBX host address
     */
    public void setPBXHost(String host) {
        prefs.edit().putString(KEY_PBX_HOST, host).apply();
    }

    /**
     * Get PBX port
     */
    public int getPBXPort() {
        return prefs.getInt(KEY_PBX_PORT, DEFAULT_PBX_PORT);
    }

    /**
     * Set PBX port
     */
    public void setPBXPort(int port) {
        prefs.edit().putInt(KEY_PBX_PORT, port).apply();
    }

    /**
     * Get SIP username for SIM slot
     */
    public String getSIPUsername(int simSlot) {
        String key = (simSlot == 1) ? KEY_SIM1_USER : KEY_SIM2_USER;
        String defaultUser = "gsm" + simSlot;
        return prefs.getString(key, defaultUser);
    }

    /**
     * Set SIP username for SIM slot
     */
    public void setSIPUsername(int simSlot, String username) {
        String key = (simSlot == 1) ? KEY_SIM1_USER : KEY_SIM2_USER;
        prefs.edit().putString(key, username).apply();
    }

    /**
     * Get SIP password for SIM slot
     */
    public String getSIPPassword(int simSlot) {
        String key = (simSlot == 1) ? KEY_SIM1_PASS : KEY_SIM2_PASS;
        return prefs.getString(key, "gsm");  // Default password
    }

    /**
     * Set SIP password for SIM slot
     */
    public void setSIPPassword(int simSlot, String password) {
        String key = (simSlot == 1) ? KEY_SIM1_PASS : KEY_SIM2_PASS;
        prefs.edit().putString(key, password).apply();
    }

    /**
     * Set SIP credentials for a SIM slot
     */
    public void setSIPCredentials(int simSlot, String username, String password) {
        setSIPUsername(simSlot, username);
        setSIPPassword(simSlot, password);
    }

    /**
     * Get local IP address (for SDP)
     */
    public String getLocalIP() {
        return prefs.getString(KEY_LOCAL_IP, null);
    }

    /**
     * Set local IP address
     */
    public void setLocalIP(String ip) {
        prefs.edit().putString(KEY_LOCAL_IP, ip).apply();
    }

    /**
     * Get local SIP port
     */
    public int getLocalSIPPort() {
        return LOCAL_SIP_PORT;
    }

    /**
     * Get RTP port for SIM slot
     */
    public static int getRTPPort(int simSlot) {
        return (simSlot == 1) ? RTP_PORT_SIM1 : RTP_PORT_SIM2;
    }

    /**
     * Check if configuration is complete
     */
    public boolean isConfigured() {
        return getPBXHost() != null &&
               getLocalIP() != null &&
               getSIPPassword(1) != null;
    }

    /**
     * Check if SIM is configured
     */
    public boolean isSimConfigured(int simSlot) {
        return getSIPUsername(simSlot) != null &&
               getSIPPassword(simSlot) != null;
    }

    /**
     * Get configuration summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("GSM-SIP Gateway Configuration:\n\n");
        sb.append("PBX: ").append(getPBXHost() != null ? getPBXHost() : "Not configured");
        sb.append(":").append(getPBXPort()).append("\n");
        sb.append("Local IP: ").append(getLocalIP() != null ? getLocalIP() : "Auto-detect").append("\n");
        sb.append("Local SIP Port: ").append(LOCAL_SIP_PORT).append("\n\n");
        sb.append("SIM1: ").append(getSIPUsername(1)).append(" (RTP: ").append(RTP_PORT_SIM1).append(")\n");
        sb.append("SIM2: ").append(getSIPUsername(2)).append(" (RTP: ").append(RTP_PORT_SIM2).append(")\n\n");
        sb.append("Status: ").append(isConfigured() ? "Ready" : "Incomplete").append("\n");
        return sb.toString();
    }

    /**
     * Apply default configuration for quick testing
     * Call this to set up defaults if not configured
     */
    public void applyDefaults(String pbxHost, String localIp) {
        if (getPBXHost() == null) {
            setPBXHost(pbxHost);
        }
        if (getLocalIP() == null) {
            setLocalIP(localIp);
        }
        // Default credentials
        if (prefs.getString(KEY_SIM1_USER, null) == null) {
            setSIPCredentials(1, "gsm1", "gsm");
        }
        if (prefs.getString(KEY_SIM2_USER, null) == null) {
            setSIPCredentials(2, "gsm2", "gsm");
        }
    }
}
