package com.shreeyash.gateway;

import android.content.Context;
import android.os.Build;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.util.List;

/**
 * Manages dual SIM detection and call routing
 * Maps SIM slots to subscription IDs and phone accounts
 */
public class DualSIMManager {
    private static final String TAG = "DualSIMManager";
    
    private Context context;
    private SubscriptionManager subscriptionManager;
    private TelecomManager telecomManager;
    
    // SIM slot mappings
    private Integer sim1SubId = null;
    private Integer sim2SubId = null;
    private PhoneAccountHandle sim1Account = null;
    private PhoneAccountHandle sim2Account = null;
    
    // SIM slot names/labels
    private String sim1Label = "SIM1";
    private String sim2Label = "SIM2";
    
    public DualSIMManager(Context context) {
        this.context = context;
        this.subscriptionManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        this.telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        
        detectSIMs();
    }
    
    /**
     * Detect and map both SIM cards
     */
    private void detectSIMs() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            Log.e(TAG, "Dual SIM API requires Android 5.1+");
            return;
        }
        
        try {
            List<SubscriptionInfo> subscriptions = subscriptionManager.getActiveSubscriptionInfoList();
            
            if (subscriptions == null || subscriptions.isEmpty()) {
                Log.w(TAG, "No active SIM cards detected");
                return;
            }
            
            Log.i(TAG, "Detected " + subscriptions.size() + " active SIM(s)");
            
            // Map subscriptions to SIM slots
            for (SubscriptionInfo info : subscriptions) {
                int slotIndex = info.getSimSlotIndex();
                int subId = info.getSubscriptionId();
                String displayName = info.getDisplayName().toString();
                String carrier = info.getCarrierName().toString();
                
                Log.i(TAG, String.format("SIM in slot %d: SubID=%d, Name=%s, Carrier=%s", 
                    slotIndex, subId, displayName, carrier));
                
                if (slotIndex == 0) {
                    sim1SubId = subId;
                    sim1Label = displayName.isEmpty() ? carrier : displayName;
                } else if (slotIndex == 1) {
                    sim2SubId = subId;
                    sim2Label = displayName.isEmpty() ? carrier : displayName;
                }
            }
            
            // Get phone account handles
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
                
                for (PhoneAccountHandle account : accounts) {
                    // Match account to subscription
                    // This is tricky - we'll use the account ID which usually contains the subId
                    String accountId = account.getId();
                    
                    if (sim1SubId != null && accountId.contains(String.valueOf(sim1SubId))) {
                        sim1Account = account;
                        Log.i(TAG, "Mapped SIM1 account: " + accountId);
                    } else if (sim2SubId != null && accountId.contains(String.valueOf(sim2SubId))) {
                        sim2Account = account;
                        Log.i(TAG, "Mapped SIM2 account: " + accountId);
                    }
                }
            }
            
            logConfiguration();
            
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for subscription info", e);
        } catch (Exception e) {
            Log.e(TAG, "Error detecting SIMs: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get subscription ID for a given SIM slot (1 or 2)
     */
    public Integer getSubscriptionId(int simSlot) {
        return simSlot == 1 ? sim1SubId : sim2SubId;
    }
    
    /**
     * Get phone account handle for a given SIM slot
     */
    public PhoneAccountHandle getPhoneAccount(int simSlot) {
        return simSlot == 1 ? sim1Account : sim2Account;
    }
    
    /**
     * Determine which SIM a subscription ID belongs to
     * Returns 1 for SIM1, 2 for SIM2, or 0 if unknown
     */
    public int getSimSlotForSubscription(int subscriptionId) {
        if (sim1SubId != null && sim1SubId == subscriptionId) {
            return 1;
        } else if (sim2SubId != null && sim2SubId == subscriptionId) {
            return 2;
        }
        return 0;
    }
    
    /**
     * Get SIM label/name
     */
    public String getSimLabel(int simSlot) {
        return simSlot == 1 ? sim1Label : sim2Label;
    }
    
    /**
     * Check if both SIMs are active
     */
    public boolean isDualSIMActive() {
        return sim1SubId != null && sim2SubId != null;
    }
    
    /**
     * Check if a specific SIM is active
     */
    public boolean isSimActive(int simSlot) {
        return getSubscriptionId(simSlot) != null;
    }
    
    /**
     * Get RTP port for a SIM slot
     */
    public int getRTPPort(int simSlot) {
        return simSlot == 1 ? 5004 : 5006;
    }
    
    /**
     * Get Asterisk context for a SIM slot
     */
    public String getAsteriskContext(int simSlot) {
        return simSlot == 1 ? "from-gsm1" : "from-gsm2";
    }
    
    /**
     * Get Asterisk trunk name for a SIM slot
     */
    public String getAsteriskTrunk(int simSlot) {
        return simSlot == 1 ? "trunk1" : "trunk2";
    }
    
    /**
     * Log current configuration
     */
    private void logConfiguration() {
        Log.i(TAG, "=== Dual SIM Configuration ===");
        Log.i(TAG, String.format("SIM1: SubID=%s, Label=%s, RTP=5004", 
            sim1SubId != null ? sim1SubId : "N/A", sim1Label));
        Log.i(TAG, String.format("SIM2: SubID=%s, Label=%s, RTP=5006", 
            sim2SubId != null ? sim2SubId : "N/A", sim2Label));
        Log.i(TAG, "Dual SIM Active: " + isDualSIMActive());
        Log.i(TAG, "=============================");
    }
    
    /**
     * Refresh SIM detection (call if SIM state changes)
     */
    public void refresh() {
        Log.i(TAG, "Refreshing SIM configuration...");
        sim1SubId = null;
        sim2SubId = null;
        sim1Account = null;
        sim2Account = null;
        detectSIMs();
    }
    
    /**
     * Get configuration summary for UI display
     */
    public String getConfigurationSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Dual SIM Configuration:\n\n");
        
        if (sim1SubId != null) {
            sb.append("✓ SIM1: ").append(sim1Label).append("\n");
            sb.append("  SubID: ").append(sim1SubId).append("\n");
            sb.append("  RTP Port: 5004\n");
            sb.append("  Trunk: trunk1\n\n");
        } else {
            sb.append("✗ SIM1: Not detected\n\n");
        }
        
        if (sim2SubId != null) {
            sb.append("✓ SIM2: ").append(sim2Label).append("\n");
            sb.append("  SubID: ").append(sim2SubId).append("\n");
            sb.append("  RTP Port: 5006\n");
            sb.append("  Trunk: trunk2\n\n");
        } else {
            sb.append("✗ SIM2: Not detected\n\n");
        }
        
        if (!isDualSIMActive()) {
            sb.append("⚠ Warning: Dual SIM not fully active\n");
        }
        
        return sb.toString();
    }
}