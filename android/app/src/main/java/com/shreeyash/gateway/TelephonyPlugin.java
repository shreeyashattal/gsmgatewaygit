package com.shreeyash.gateway;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;
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
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.ACCESS_NETWORK_STATE
            }
        )
    }
)
public class TelephonyPlugin extends Plugin {
    private static final String TAG = "TelephonyPlugin";

    @PluginMethod
    public void getSimInfo(PluginCall call) {
        if (getPermissionState("telephony") != PermissionState.GRANTED) {
            requestPermissionForAlias("telephony", call, "telephonyPermissionsCallback");
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
            int activeSimCount = 0;

            // Get subscription list
            List<SubscriptionInfo> activeSubs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                    if (subManager != null) {
                        activeSubs = subManager.getActiveSubscriptionInfoList();
                        if (activeSubs != null) {
                            activeSimCount = activeSubs.size();
                        }
                    }
                }
            }

            Log.d(TAG, "Active SIM count: " + activeSimCount);

            // Iterate through slots
            for (int slot = 0; slot < 2; slot++) {
                JSONObject simInfo = new JSONObject();

                // Find subscription for this slot
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
                    // No SIM in this slot
                    simInfo.put("carrier", slot == 0 ? "No SIM" : "Empty");
                    simInfo.put("status", "NOT_DETECTED");
                    simInfo.put("phoneNumber", "");
                    simInfo.put("radioSignal", -110);
                    simInfo.put("connectionType", "Tower");
                    simInfo.put("networkType", "");
                    simsArray.put(simInfo);
                    continue;
                }

                // Get TelephonyManager for this subscription
                TelephonyManager slotManager = telephonyManager;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    slotManager = telephonyManager.createForSubscriptionId(subInfo.getSubscriptionId());
                }

                // Carrier name
                String carrier = "Unknown";
                CharSequence carrierName = subInfo.getCarrierName();
                if (carrierName != null && carrierName.length() > 0) {
                    carrier = carrierName.toString();
                }

                // Status
                String status = "READY";

                // Phone number - try multiple methods
                String phoneNumber = getPhoneNumber(context, subInfo, slotManager);

                // Signal strength
                int radioSignal = getSignalStrength(context, slotManager, slot);

                // Network type (LTE, 5G, etc.)
                String networkType = getNetworkType(slotManager);

                // Connection type (Tower vs VoWiFi)
                String connectionType = getConnectionType(context, slotManager);

                simInfo.put("carrier", carrier);
                simInfo.put("status", status);
                simInfo.put("phoneNumber", phoneNumber);
                simInfo.put("radioSignal", radioSignal);
                simInfo.put("connectionType", connectionType);
                simInfo.put("networkType", networkType);

                Log.d(TAG, "Slot " + slot + ": carrier=" + carrier + ", phone=" + phoneNumber +
                      ", signal=" + radioSignal + ", network=" + networkType + ", conn=" + connectionType);

                simsArray.put(simInfo);
            }

            JSObject result = new JSObject();
            result.put("sims", simsArray);
            result.put("slotCount", activeSimCount > 0 ? activeSimCount : 1);
            call.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to get SIM info", e);
            call.reject("Failed to get SIM info: " + e.getMessage());
        }
    }

    private String getPhoneNumber(Context context, SubscriptionInfo subInfo, TelephonyManager slotManager) {
        String phoneNumber = "";

        try {
            // Method 1: From SubscriptionInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ - need READ_PHONE_NUMBERS permission
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED) {
                    phoneNumber = subInfo.getNumber();
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                phoneNumber = subInfo.getNumber();
            }

            // Method 2: From TelephonyManager if still empty
            if ((phoneNumber == null || phoneNumber.isEmpty()) && slotManager != null) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED) {
                            phoneNumber = slotManager.getLine1Number();
                        }
                    } else {
                        phoneNumber = slotManager.getLine1Number();
                    }
                }
            }

        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read phone number: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Error getting phone number: " + e.getMessage());
        }

        return phoneNumber != null ? phoneNumber : "";
    }

    private int getSignalStrength(Context context, TelephonyManager slotManager, int slot) {
        int radioSignal = -110;

        try {
            if (slotManager != null && ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.telephony.SignalStrength signalStrength = slotManager.getSignalStrength();
                    if (signalStrength != null) {
                        // Get level (0-4) and convert to approximate dBm
                        int level = signalStrength.getLevel();
                        // Level 0 = -110, Level 4 = -50
                        radioSignal = -110 + (level * 15);

                        // Try to get actual dBm from cell info
                        List<CellInfo> cellInfoList = slotManager.getAllCellInfo();
                        if (cellInfoList != null) {
                            for (CellInfo cellInfo : cellInfoList) {
                                if (!cellInfo.isRegistered()) continue;

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo instanceof CellInfoNr) {
                                    CellSignalStrengthNr nr = (CellSignalStrengthNr) ((CellInfoNr) cellInfo).getCellSignalStrength();
                                    int dbm = nr.getDbm();
                                    if (dbm != Integer.MAX_VALUE && dbm != CellInfo.UNAVAILABLE) {
                                        radioSignal = dbm;
                                    }
                                } else if (cellInfo instanceof CellInfoLte) {
                                    CellSignalStrengthLte lte = ((CellInfoLte) cellInfo).getCellSignalStrength();
                                    int dbm = lte.getDbm();
                                    if (dbm != Integer.MAX_VALUE && dbm != CellInfo.UNAVAILABLE) {
                                        radioSignal = dbm;
                                    }
                                }
                                break; // Use first registered cell
                            }
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read signal strength: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Error getting signal strength: " + e.getMessage());
        }

        return radioSignal;
    }

    private String getNetworkType(TelephonyManager slotManager) {
        if (slotManager == null) return "";

        try {
            int networkType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkType = slotManager.getDataNetworkType();
            } else {
                networkType = slotManager.getNetworkType();
            }

            switch (networkType) {
                case TelephonyManager.NETWORK_TYPE_NR:
                    return "5G";
                case TelephonyManager.NETWORK_TYPE_LTE:
                    return "LTE";
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                    return "3G+";
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                    return "3G";
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                    return "2G";
                case TelephonyManager.NETWORK_TYPE_IWLAN:
                    return "WiFi";
                default:
                    return "Unknown";
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting network type: " + e.getMessage());
            return "";
        }
    }

    private String getConnectionType(Context context, TelephonyManager slotManager) {
        try {
            // Check network type - if IWLAN it means VoWiFi
            if (slotManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                int networkType = slotManager.getDataNetworkType();
                if (networkType == TelephonyManager.NETWORK_TYPE_IWLAN) {
                    return "VoWiFi";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking connection type: " + e.getMessage());
        }
        return "Tower";
    }
}
