package com.shreeyash.gateway;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

@CapacitorPlugin(
    name = "Telephony",
    permissions = {
        @Permission(
            alias = "telephony",
            strings = {
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS
            }
        )
    }
)
public class TelephonyPlugin extends Plugin {

    @PluginMethod
    public void getSimInfo(PluginCall call) {
        if (getPermissionState("telephony") != PermissionState.GRANTED) {
            requestPermissions(call);
        } else {
            loadSimInfo(call);
        }
    }

    @PermissionCallback
    private void telephonyPermissionsCallback(PluginCall call) {
        if (getPermissionState("telephony") == PermissionState.GRANTED) {
            loadSimInfo(call);
        } else {
            call.reject("Permission is required to get SIM info");
        }
    }

    private void loadSimInfo(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("Context not available");
            return;
        }

        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager == null) {
                call.reject("TelephonyManager not available");
                return;
            }

            JSONArray simsArray = new JSONArray();
            
            // Get accurate active subscription info list
            List<SubscriptionInfo> activeSubs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                     SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                     if (subManager != null) {
                         activeSubs = subManager.getActiveSubscriptionInfoList();
                     }
                }
            }

            // We iterate exactly 2 slots since dual SIM is the max we care about here
            for (int slot = 0; slot < 2; slot++) {
                JSONObject simInfo = new JSONObject();
                
                // Find if there is an active subscription for this slot index
                SubscriptionInfo subInfo = null;
                if (activeSubs != null) {
                    for (SubscriptionInfo sub : activeSubs) {
                        if (sub.getSimSlotIndex() == slot) {
                            subInfo = sub;
                            break;
                        }
                    }
                }

                if (subInfo == null) {
                    // No active SIM in this slot
                    simInfo.put("carrier", "Empty");
                    simInfo.put("status", "NOT_DETECTED");
                    simInfo.put("phoneNumber", "");
                    simInfo.put("radioSignal", -110);
                    simInfo.put("connectionType", "Tower");
                    simsArray.put(simInfo);
                    continue;
                }

                // If we are here, we have a valid subscription for this slot
                TelephonyManager slotManager = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    slotManager = telephonyManager.createForSubscriptionId(subInfo.getSubscriptionId());
                } else {
                    slotManager = telephonyManager;
                }

                // Get carrier name
                String carrier = "Unknown";
                CharSequence carrierName = subInfo.getCarrierName();
                if (carrierName != null && carrierName.length() > 0) {
                     carrier = carrierName.toString();
                } else if (slotManager != null) {
                     carrier = slotManager.getNetworkOperatorName();
                }
                if (carrier == null || carrier.isEmpty()) carrier = "Unknown";

                // Get SIM state
                // Since we found it in active subscriptions, it is effectively READY or similar
                String status = "READY"; 
                // We could also check slotManager.getSimState() if needed, but active subscription implies presence.

                // Get phone number
                String phoneNumber = "";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    // Try to get number from SubscriptionInfo first (often more reliable/permission-friendly)
                     phoneNumber = subInfo.getNumber();
                }
                
                if ((phoneNumber == null || phoneNumber.isEmpty()) && slotManager != null) {
                    boolean hasPhoneNumbersPerm = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                         hasPhoneNumbersPerm = ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED;
                    }
                    if (hasPhoneNumbersPerm && ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            phoneNumber = slotManager.getLine1Number();
                        } catch (SecurityException e) {
                            // Ignore
                        }
                    }
                }

                // Get signal strength
                int radioSignal = -110;
                if (slotManager != null && ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            SignalStrength signalStrength = slotManager.getSignalStrength();
                            if (signalStrength != null) {
                                // Use reflection to get dBm as it's not always available directly
                                try {
                                    java.lang.reflect.Method getDbmMethod = signalStrength.getClass().getMethod("getDbm");
                                    Object dbmObj = getDbmMethod.invoke(signalStrength);
                                    if (dbmObj instanceof Integer) {
                                        radioSignal = (Integer) dbmObj;
                                    }
                                } catch (Exception e) {
                                    // Fallback: try getLevel and convert
                                    int level = signalStrength.getLevel();
                                    if (level >= 0 && level <= 4) {
                                        // Convert level (0-4) to approximate dBm
                                        radioSignal = -113 + (level * 12);
                                    }
                                }
                            }
                        } else {
                            // Fallback for older Android versions
                        }
                    } catch (Exception e) {
                        // Use default signal strength on error
                    }
                }

                // Determine connection type (VoWiFi vs Cellular)
                String connectionType = "Tower";
                WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null && wifiManager.isWifiEnabled()) {
                    // Check if IMS is registered for WiFi calling
                    // This is a simplified check - in production, check IMS registration status
                    connectionType = "VoWiFi";
                }

                simInfo.put("carrier", carrier);
                simInfo.put("status", status);
                simInfo.put("phoneNumber", phoneNumber != null ? phoneNumber : "");
                simInfo.put("radioSignal", radioSignal);
                simInfo.put("connectionType", connectionType);

                simsArray.put(simInfo);
            }

            JSObject result = new JSObject();
            result.put("sims", simsArray);
            result.put("slotCount", activeSubs != null ? activeSubs.size() : 0);
            call.resolve(result);

        } catch (Exception e) {
            call.reject("Failed to get SIM info: " + e.getMessage());
        }
    }
}
