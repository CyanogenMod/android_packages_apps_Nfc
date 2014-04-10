package com.android.nfc.cardemulation;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.AidGroup;
import android.util.Log;

import com.google.android.collect.Maps;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class RegisteredAidCache {
    static final String TAG = "RegisteredAidCache";

    static final boolean DBG = true;

    // mAidServices is a tree that maps an AID to a list of handling services
    // on Android. It is only valid for the current user.
    final TreeMap<String, ArrayList<ApduServiceInfo>> mAidToServices =
            new TreeMap<String, ArrayList<ApduServiceInfo>>();

    // mAidCache is a lookup table for quickly mapping an AID to one or
    // more services. It differs from mAidServices in the sense that it
    // has already accounted for defaults, and hence its return value
    // is authoritative for the current set of services and defaults.
    // It is only valid for the current user.
    final HashMap<String, AidResolveInfo> mAidCache =
            Maps.newHashMap();

    final HashMap<String, ComponentName> mCategoryDefaults =
            Maps.newHashMap();

    final class AidResolveInfo {
        List<ApduServiceInfo> services;
        ApduServiceInfo defaultService;
        String aid;
    }

    /**
     * AIDs per category
     */
    public final HashMap<String, Set<String>> mCategoryAids =
            Maps.newHashMap();

    final Object mLock = new Object();
    final Context mContext;
    final AidRoutingManager mRoutingManager;

    ComponentName mNextTapComponent = null;
    boolean mNfcEnabled = false;

    public RegisteredAidCache(Context context) {
        mContext = context;
        mRoutingManager = new AidRoutingManager();
    }

    public boolean isNextTapOverriden() {
        synchronized (mLock) {
            return mNextTapComponent != null;
        }
    }

    public AidResolveInfo resolveAidPrefix(String aid) {
        synchronized (mLock) {
            char nextAidChar = (char) (aid.charAt(aid.length() - 1) + 1);
            String nextAid = aid.substring(0, aid.length() - 1) + nextAidChar;
            SortedMap<String, ArrayList<ApduServiceInfo>> matches =
                    mAidToServices.subMap(aid, nextAid);
            // The first match is lexicographically closest to what the reader asked;
            if (matches.isEmpty()) {
                return null;
            } else {
                AidResolveInfo resolveInfo = mAidCache.get(matches.firstKey());
                // Let the caller know which AID got selected
                resolveInfo.aid = matches.firstKey();
                return resolveInfo;
            }
        }
    }

    public String getCategoryForAid(String aid) {
        synchronized (mLock) {
            Set<String> paymentAids = mCategoryAids.get(CardEmulation.CATEGORY_PAYMENT);
            if (paymentAids != null && paymentAids.contains(aid)) {
                return CardEmulation.CATEGORY_PAYMENT;
            } else {
                return CardEmulation.CATEGORY_OTHER;
            }
        }
    }

    public boolean isDefaultServiceForAid(int userId, ComponentName service, String aid) {
        AidResolveInfo resolveInfo = null;
        synchronized (mLock) {
            resolveInfo = mAidCache.get(aid);
        }
        if (resolveInfo == null || resolveInfo.services == null ||
                resolveInfo.services.size() == 0) {
            return false;
        }

        if (resolveInfo.defaultService != null) {
            return service.equals(resolveInfo.defaultService.getComponent());
        } else if (resolveInfo.services.size() == 1) {
            return service.equals(resolveInfo.services.get(0).getComponent());
        } else {
            // More than one service, not the default
            return false;
        }
    }

    public boolean setDefaultForNextTap(int userId, ComponentName service) {
        synchronized (mLock) {
            if (service != null) {
                mNextTapComponent = service;
            } else {
                mNextTapComponent = null;
            }
            // Update cache and routing table
            generateAidCacheLocked();
            updateRoutingLocked();
        }
        return true;
    }

    /**
     * Resolves an AID to a set of services that can handle it.
     */
     AidResolveInfo resolveAidLocked(List<ApduServiceInfo> resolvedServices, String aid) {
        if (resolvedServices == null || resolvedServices.size() == 0) {
            if (DBG) Log.d(TAG, "Could not resolve AID " + aid + " to any service.");
            return null;
        }
        AidResolveInfo resolveInfo = new AidResolveInfo();
        if (DBG) Log.d(TAG, "resolveAidLocked: resolving AID " + aid);
        resolveInfo.services = new ArrayList<ApduServiceInfo>();
        resolveInfo.services.addAll(resolvedServices);
        resolveInfo.defaultService = null;

        ComponentName defaultComponent = mNextTapComponent;
        if (DBG) Log.d(TAG, "resolveAidLocked: next tap component is " + defaultComponent);
        Set<String> paymentAids = mCategoryAids.get(CardEmulation.CATEGORY_PAYMENT);
        if (paymentAids != null && paymentAids.contains(aid)) {
            if (DBG) Log.d(TAG, "resolveAidLocked: AID " + aid + " is a payment AID");
            // This AID has been registered as a payment AID by at least one service.
            // Get default component for payment if no next tap default.
            if (defaultComponent == null) {
                defaultComponent = mCategoryDefaults.get(CardEmulation.CATEGORY_PAYMENT);
            }
            if (DBG) Log.d(TAG, "resolveAidLocked: default payment component is "
                    + defaultComponent);
            if (resolvedServices.size() == 1) {
                ApduServiceInfo resolvedService = resolvedServices.get(0);
                if (DBG) Log.d(TAG, "resolveAidLocked: resolved single service " +
                        resolvedService.getComponent());
                if (defaultComponent != null &&
                        defaultComponent.equals(resolvedService.getComponent())) {
                    if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to (default) " +
                        resolvedService.getComponent());
                    resolveInfo.defaultService = resolvedService;
                } else {
                    // Don't allow any AIDs of non-default payment apps to be routed
                    if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: not routing because " +
                            "not default payment service.");
                    resolveInfo.services.clear();
                }
            } else if (resolvedServices.size() > 1) {
                // More services have registered. If there's a default and it
                // registered this AID, go with the default. Otherwise, add all.
                if (DBG) Log.d(TAG, "resolveAidLocked: multiple services matched.");
                if (defaultComponent != null) {
                    for (ApduServiceInfo service : resolvedServices) {
                        if (service.getComponent().equals(defaultComponent)) {
                            if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to (default) "
                                    + service.getComponent());
                            resolveInfo.defaultService = service;
                            break;
                        }
                    }
                    if (resolveInfo.defaultService == null) {
                        if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: not routing because " +
                                "not default payment service.");
                        resolveInfo.services.clear();
                    }
                }
            } // else -> should not hit, we checked for 0 before.
        } else {
            // This AID is not a payment AID, just return all components
            // that can handle it, but be mindful of (next tap) defaults.
            for (ApduServiceInfo service : resolvedServices) {
                if (service.getComponent().equals(defaultComponent)) {
                    if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: cat OTHER AID, " +
                            "routing to (default) " + service.getComponent());
                    resolveInfo.defaultService = service;
                    break;
                }
            }
            if (resolveInfo.defaultService == null) {
                // If we didn't find the default, mark the first as default
                // if there is only one.
                if (resolveInfo.services.size() == 1) {
                    resolveInfo.defaultService = resolveInfo.services.get(0);
                    if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: cat OTHER AID, " +
                            "routing to (default) " + resolveInfo.defaultService.getComponent());
                } else {
                    if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: cat OTHER AID, routing all");
                }
            }
        }
        return resolveInfo;
    }

    void generateAidTreeLocked(List<ApduServiceInfo> services) {
        // Easiest is to just build the entire tree again
        mAidToServices.clear();
        for (ApduServiceInfo service : services) {
            if (DBG) Log.d(TAG, "generateAidTree component: " + service.getComponent());
            for (String aid : service.getAids()) {
                if (DBG) Log.d(TAG, "generateAidTree AID: " + aid);
                // Check if a mapping exists for this AID
                if (mAidToServices.containsKey(aid)) {
                    final ArrayList<ApduServiceInfo> aidServices = mAidToServices.get(aid);
                    aidServices.add(service);
                } else {
                    final ArrayList<ApduServiceInfo> aidServices =
                            new ArrayList<ApduServiceInfo>();
                    aidServices.add(service);
                    mAidToServices.put(aid, aidServices);
                }
            }
        }
    }

    void generateAidCategoriesLocked(List<ApduServiceInfo> services) {
        // Trash existing mapping
        mCategoryAids.clear();

        for (ApduServiceInfo service : services) {
            ArrayList<AidGroup> aidGroups = service.getAidGroups();
            if (aidGroups == null) continue;
            for (AidGroup aidGroup : aidGroups) {
                String groupCategory = aidGroup.getCategory();
                Set<String> categoryAids = mCategoryAids.get(groupCategory);
                if (categoryAids == null) {
                    categoryAids = new HashSet<String>();
                }
                categoryAids.addAll(aidGroup.getAids());
                mCategoryAids.put(groupCategory, categoryAids);
            }
        }
    }


    void generateAidCacheLocked() {
        mAidCache.clear();
        for (Map.Entry<String, ArrayList<ApduServiceInfo>> aidEntry:
                    mAidToServices.entrySet()) {
            String aid = aidEntry.getKey();
            if (!mAidCache.containsKey(aid)) {
                mAidCache.put(aid, resolveAidLocked(aidEntry.getValue(), aid));
            }
        }
    }

    void updateRoutingLocked() {
        if (!mNfcEnabled) {
            if (DBG) Log.d(TAG, "Not updating routing table because NFC is off.");
            return;
        }
        final Set<String> handledAids = new HashSet<String>();
        // For each AID, find interested services
        for (Map.Entry<String, AidResolveInfo> aidEntry:
                mAidCache.entrySet()) {
            String aid = aidEntry.getKey();
            AidResolveInfo resolveInfo = aidEntry.getValue();
            if (resolveInfo.services.size() == 0) {
                // No interested services, if there is a current routing remove it
                mRoutingManager.removeAid(aid);
            } else if (resolveInfo.defaultService != null) {
                // There is a default service set, route to that service
                mRoutingManager.setRouteForAid(aid, resolveInfo.defaultService.isOnHost());
            } else if (resolveInfo.services.size() == 1) {
                // Only one service, but not the default, must route to host
                // to ask the user to confirm.
                mRoutingManager.setRouteForAid(aid, true);
            } else if (resolveInfo.services.size() > 1) {
                // Multiple services, need to route to host to ask
                mRoutingManager.setRouteForAid(aid, true);
            }
            handledAids.add(aid);
        }
        // Now, find AIDs in the routing table that are no longer routed to
        // and remove them.
        Set<String> routedAids = mRoutingManager.getRoutedAids();
        for (String aid : routedAids) {
            if (!handledAids.contains(aid)) {
                if (DBG) Log.d(TAG, "Removing routing for AID " + aid + ", because " +
                        "there are no no interested services.");
                mRoutingManager.removeAid(aid);
            }
        }
        // And commit the routing
        mRoutingManager.commitRouting();
    }


    public void onDefaultChanged(String category, ComponentName defaultComponent) {
        if (DBG) Log.d(TAG, "notifyDefaultChanged");
        synchronized (mLock) {
            mCategoryDefaults.put(category, defaultComponent);
            generateAidCacheLocked();
            updateRoutingLocked();
        }
    }

    public void onServicesUpdated(int userId, List<ApduServiceInfo> services) {
        if (DBG) Log.d(TAG, "onServicesUpdated");
        synchronized (mLock) {
            if (ActivityManager.getCurrentUser() == userId) {
                // Rebuild our internal data-structures
                generateAidTreeLocked(services);
                generateAidCategoriesLocked(services);
                generateAidCacheLocked();
                updateRoutingLocked();
            } else {
                if (DBG) Log.d(TAG, "Ignoring update because it's not for the current user.");
            }
        }
    }

    public void onNfcDisabled() {
        synchronized (mLock) {
            mNfcEnabled = false;
        }
        mRoutingManager.onNfccRoutingTableCleared();
    }

    public void onNfcEnabled() {
        synchronized (mLock) {
            mNfcEnabled = true;
            updateRoutingLocked();
        }
    }

    String dumpEntry(Map.Entry<String, AidResolveInfo> entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("    \"" + entry.getKey() + "\" (Category: " + getCategoryForAid(entry.getKey())
                + ")\n");
        ApduServiceInfo defaultService = entry.getValue().defaultService;
        ComponentName defaultComponent = defaultService != null ?
                defaultService.getComponent() : null;

        for (ApduServiceInfo service : entry.getValue().services) {
            sb.append("        ");
            if (service.getComponent().equals(defaultComponent)) {
                sb.append("*DEFAULT* ");
            }
            sb.append(service.getComponent() +
                    " (Description: " + service.getDescription() + ")\n");
        }
        return sb.toString();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
       pw.println("AID cache entries: ");
       for (Map.Entry<String, AidResolveInfo> entry : mAidCache.entrySet()) {
           pw.println(dumpEntry(entry));
       }
       pw.println("Category defaults: ");
       for (Map.Entry<String, ComponentName> entry : mCategoryDefaults.entrySet()) {
           pw.println("    " + entry.getKey() + "->" + entry.getValue());
       }
       pw.println("");
       mRoutingManager.dump(fd, pw, args);
       pw.println("");
    }

}
