package com.shreeyash.gateway;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import android.content.pm.PackageManager;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.JSObject;

@CapacitorPlugin(
    name = "GsmBridge",
    permissions = {
        @Permission(
            alias = "phone",
            strings = {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE
            }
        )
    }
)
public class GsmBridgePlugin extends Plugin {
    
    private static final String TAG = "GsmBridge";

    /**
     * Place an outbound GSM call on specified SIM slot
     */
    @PluginMethod
    public void placeCall(PluginCall call) {
        String phoneNumber = call.getString("phoneNumber");
        int slot = call.getInt("slot", 0);
        
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            call.reject("Phone number is required");
            return;
        }
        
        Log.i(TAG, "Placing GSM call to " + phoneNumber + " on slot " + slot);
        
        if (getPermissionState("phone") != PermissionState.GRANTED) {
            requestPermissions(call);
            return;
        }
        
        performPlaceCall(call, phoneNumber, slot);
    }
    
    @PermissionCallback
    private void phonePermissionsCallback(PluginCall call) {
        if (getPermissionState("phone") == PermissionState.GRANTED) {
            String phoneNumber = call.getString("phoneNumber");
            int slot = call.getInt("slot", 0);
            performPlaceCall(call, phoneNumber, slot);
        } else {
            call.reject("Phone permission required to place calls");
        }
    }
    
    private void performPlaceCall(PluginCall call, String phoneNumber, int slot) {
        Context context = getContext();
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Modern approach using TelecomManager for dual-SIM
                TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
                
                if (telecomManager != null && ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    
                    // Get subscription ID for the slot
                    int subscriptionId = getSubscriptionIdForSlot(slot);
                    
                    if (subscriptionId != -1) {
                        // Create intent with specific SIM
                        Intent intent = new Intent(Intent.ACTION_CALL);
                        intent.setData(Uri.parse("tel:" + phoneNumber));
                        intent.putExtra("com.android.phone.extra.slot", slot);
                        intent.putExtra("Subscription", subscriptionId);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        
                        Log.i(TAG, "Placing call via TelecomManager: phone=" + phoneNumber + " slot=" + slot + " subId=" + subscriptionId);
                        context.startActivity(intent);
                        
                        JSObject ret = new JSObject();
                        ret.put("success", true);
                        ret.put("phoneNumber", phoneNumber);
                        ret.put("slot", slot);
                        call.resolve(ret);
                    } else {
                        call.reject("Could not find subscription for slot " + slot);
                    }
                } else {
                    call.reject("TelecomManager not available or permission denied");
                }
            } else {
                // Fallback for older Android versions
                Intent intent = new Intent(Intent.ACTION_CALL);
                intent.setData(Uri.parse("tel:" + phoneNumber));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("phoneNumber", phoneNumber);
                call.resolve(ret);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to place call", e);
            call.reject("Failed to place call: " + e.getMessage());
        }
    }
    
    /**
     * Get subscription ID for a given SIM slot
     */
    private int getSubscriptionIdForSlot(int slotIndex) {
        Context context = getContext();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                
                if (subManager != null) {
                    java.util.List<SubscriptionInfo> subInfoList = subManager.getActiveSubscriptionInfoList();
                    
                    if (subInfoList != null) {
                        for (SubscriptionInfo subInfo : subInfoList) {
                            if (subInfo.getSimSlotIndex() == slotIndex) {
                                return subInfo.getSubscriptionId();
                            }
                        }
                    }
                }
            }
        }
        
        return -1;
    }
    
    /**
     * Get current call state
     */
    @PluginMethod
    public void getCallState(PluginCall call) {
        // TODO: Implement call state monitoring
        // This would require registering a PhoneStateListener
        JSObject ret = new JSObject();
        ret.put("state", "idle");
        call.resolve(ret);
    }
}
