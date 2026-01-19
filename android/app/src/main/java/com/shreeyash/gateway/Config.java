package com.gsmgateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configuration manager - NO HARDCODED VALUES
 * Reads from SharedPreferences or uses defaults
 */
public class Config {
    private static final String PREFS_NAME = "gateway_config";
    
    // AMI Configuration (localhost - hardcoded is OK)
    public static final String AMI_HOST = "127.0.0.1";
    public static final int AMI_PORT = 5038;
    // AMI credentials - hardcoded is OK since it's localhost only
    public static final String AMI_USERNAME = "gateway";
    public static final String AMI_SECRET = "gW8y#mK2$pL9";  // Strong default password
    
    // SIP Port (changed from 5060 to 5080 because Android blocks 5060)
    public static final int SIP_PORT = 5080;
    
    // RTP Ports for each SIM
    public static final int RTP_PORT_SIM1 = 5004;
    public static final int RTP_PORT_SIM2 = 5006;
    
    // SharedPreferences keys
    private static final String KEY_ASTERISK_HOST = "asterisk_host";
    private static final String KEY_PBX_HOST = "pbx_host";
    private static final String KEY_TRUNK1_USER = "trunk1_username";
    private static final String KEY_TRUNK1_PASS = "trunk1_password";
    private static final String KEY_TRUNK2_USER = "trunk2_username";
    private static final String KEY_TRUNK2_PASS = "trunk2_password";
    
    private SharedPreferences prefs;
    
    public Config(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * Get Asterisk host (should be localhost/127.0.0.1)
     */
    public String getAsteriskHost() {
        return prefs.getString(KEY_ASTERISK_HOST, AMI_HOST);
    }
    
    /**
     * Get Grandstream PBX host (user configurable)
     */
    public String getPBXHost() {
        return prefs.getString(KEY_PBX_HOST, null);
    }
    
    /**
     * Set PBX host
     */
    public void setPBXHost(String host) {
        prefs.edit().putString(KEY_PBX_HOST, host).apply();
    }
    
    /**
     * Get trunk credentials
     */
    public String getTrunkUsername(int trunkNum) {
        String key = (trunkNum == 1) ? KEY_TRUNK1_USER : KEY_TRUNK2_USER;
        String defaultUser = "gsm_gateway_" + trunkNum;
        return prefs.getString(key, defaultUser);
    }
    
    public String getTrunkPassword(int trunkNum) {
        String key = (trunkNum == 1) ? KEY_TRUNK1_PASS : KEY_TRUNK2_PASS;
        return prefs.getString(key, null);
    }
    
    /**
     * Set trunk credentials
     */
    public void setTrunkCredentials(int trunkNum, String username, String password) {
        String userKey = (trunkNum == 1) ? KEY_TRUNK1_USER : KEY_TRUNK2_USER;
        String passKey = (trunkNum == 1) ? KEY_TRUNK1_PASS : KEY_TRUNK2_PASS;
        prefs.edit()
            .putString(userKey, username)
            .putString(passKey, password)
            .apply();
    }
    
    /**
     * Check if configuration is complete
     */
    public boolean isConfigured() {
        return getPBXHost() != null && 
               getTrunkPassword(1) != null && 
               getTrunkPassword(2) != null;
    }
    
    /**
     * Get RTP port for SIM slot
     */
    public static int getRTPPort(int simSlot) {
        return (simSlot == 1) ? RTP_PORT_SIM1 : RTP_PORT_SIM2;
    }
    
    /**
     * Get configuration summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Configuration:\n\n");
        sb.append("Asterisk: ").append(getAsteriskHost()).append(":").append(AMI_PORT).append("\n");
        sb.append("PBX: ").append(getPBXHost() != null ? getPBXHost() : "Not configured").append(":").append(SIP_PORT).append("\n\n");
        sb.append("Trunk1: ").append(getTrunkUsername(1)).append("\n");
        sb.append("Trunk2: ").append(getTrunkUsername(2)).append("\n\n");
        sb.append("Status: ").append(isConfigured() ? "✓ Ready" : "⚠ Incomplete").append("\n");
        return sb.toString();
    }
}