/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nfc.cardemulation;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
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

    static final boolean DBG = false;

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

    final class AidResolveInfo {
        List<ApduServiceInfo> services;
        ApduServiceInfo defaultService;
        String aid;
    }

    final Context mContext;
    final AidRoutingManager mRoutingManager;

    final Object mLock = new Object();
    /**
     * AIDs per category
     */
    public final HashMap<String, Set<String>> mCategoryAids =
            Maps.newHashMap();

    ComponentName mPreferredPaymentService;
    ComponentName mPreferredForegroundService;

    boolean mNfcEnabled = false;

    public RegisteredAidCache(Context context) {
        mContext = context;
        mRoutingManager = new AidRoutingManager();
        mPreferredPaymentService = null;
        mPreferredForegroundService = null;
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

    /**
     * Resolves an AID to a set of services that can handle it.
     * Takes into account conflict resolution modes per category
     * and defaults.
     */
     AidResolveInfo resolveAidLocked(List<ApduServiceInfo> handlingServices, String aid) {
        if (handlingServices == null || handlingServices.size() == 0) {
            if (DBG) Log.d(TAG, "Could not resolve AID " + aid + " to any service.");
            return null;
        }
        if (DBG) Log.d(TAG, "resolveAidLocked: resolving AID " + aid);
        AidResolveInfo resolveInfo = new AidResolveInfo();
        resolveInfo.services = new ArrayList<ApduServiceInfo>();
        resolveInfo.defaultService = null;

        ApduServiceInfo matchedForeground = null;
        ApduServiceInfo matchedPayment = null;
        for (ApduServiceInfo service : handlingServices) {
            boolean serviceClaimsPaymentAid =
                    CardEmulation.CATEGORY_PAYMENT.equals(service.getCategoryForAid(aid));
            if (service.getComponent().equals(mPreferredForegroundService)) {
                resolveInfo.services.add(service);
                matchedForeground = service;
            } else if (service.getComponent().equals(mPreferredPaymentService) &&
                    serviceClaimsPaymentAid) {
                resolveInfo.services.add(service);
                matchedPayment = service;
            } else {
                if (serviceClaimsPaymentAid) {
                    // If this service claims it's a payment AID, don't route it,
                    // because it's not the default. Otherwise, add it to the list
                    // but not as default.
                    if (DBG) Log.d(TAG, "resolveAidLocked: (Ignoring handling service " +
                            service.getComponent() + " because it's not the payment default.)");
                } else {
                    resolveInfo.services.add(service);
                }
            }
        }
        if (matchedForeground != null) {
            // 1st priority: if the foreground app prefers a service,
            // and that service asks for the AID, that service gets it
            if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to foreground preferred " +
                    matchedForeground);
            resolveInfo.defaultService = matchedForeground;
        } else if (matchedPayment != null) {
            // 2nd priority: if there is a preferred payment service,
            // and that service claims this as a payment AID, that service gets it
            if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to payment default " +
                    "default " + matchedPayment);
            resolveInfo.defaultService = matchedPayment;
        } else {
            // If there's only one service left handling the AID, that service gets it by default
            if (resolveInfo.services.size() == 1) {
                if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to single matching " +
                        "service");
                resolveInfo.defaultService = handlingServices.get(0);
            } else {
                // Nothing to do, all services already in list
                if (DBG) Log.d(TAG, "resolveAidLocked: DECISION: routing to all matching " +
                        "services");
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
        updateRoutingLocked();
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

    public void onServicesUpdated(int userId, List<ApduServiceInfo> services) {
        if (DBG) Log.d(TAG, "onServicesUpdated");
        synchronized (mLock) {
            if (ActivityManager.getCurrentUser() == userId) {
                // Rebuild our internal data-structures
                generateAidTreeLocked(services);
                generateAidCategoriesLocked(services);
                generateAidCacheLocked();
            } else {
                if (DBG) Log.d(TAG, "Ignoring update because it's not for the current user.");
            }
        }
    }

    public void onPreferredPaymentServiceChanged(ComponentName service) {
       synchronized (mLock) {
           mPreferredPaymentService = service;
           generateAidCacheLocked();
       }
    }

    public void onPreferredForegroundServiceChanged(ComponentName service) {
        synchronized (mLock) {
            mPreferredForegroundService = service;
            generateAidCacheLocked();
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
        sb.append("    \"" + entry.getKey() + "\"\n");
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
       pw.println("    Service preferred by foreground app: " + mPreferredForegroundService);
       pw.println("    Preferred payment service: " + mPreferredPaymentService);
       pw.println("");
       mRoutingManager.dump(fd, pw, args);
       pw.println("");
    }
}
