/*
 * Copyright (C) 2013 The Android Open Source Project
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

import org.xmlpull.v1.XmlPullParserException;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.ApduServiceInfo.AidGroup;
import android.nfc.cardemulation.CardEmulationManager;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.OffHostApduService;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.collect.Maps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

public class RegisteredServicesCache {
    static final String TAG = "RegisteredAidCache";
    static final boolean DEBUG = true;

    static final String AID_CATEGORY_PAYMENT = CardEmulationManager.CATEGORY_PAYMENT;

    final Context mContext;
    final AtomicReference<BroadcastReceiver> mReceiver;
    final AidRoutingManager mRoutingManager;

    final Object mLock = new Object();
    // All variables below synchronized on mLock

    // mUserServices holds the card emulation services that are running for each user
    final SparseArray<UserServices> mUserServices = new SparseArray<UserServices>();

    // mAidServices is a tree that maps an AID to a list of handling services
    // on Android. It is only valid for the current user.
    final TreeMap<String, ArrayList<ApduServiceInfo>> mAidToServices =
            new TreeMap<String, ArrayList<ApduServiceInfo>>();

    // mAidCache is a lookup table for quickly mapping an AID to one or
    // more services. It differs from mAidServices in the sense that it
    // has already accounted for defaults, and hence its return value
    // is authoritative for the current set of services and defaults.
    // It is only valid for the current user.
    final HashMap<String, ArrayList<ApduServiceInfo>> mAidCache =
            Maps.newHashMap();

    final Handler mHandler = new Handler(Looper.getMainLooper());

    ComponentName mNextTapComponent = null;

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            synchronized (mLock) {
                // Do it just for the current user. If it was in fact
                // a change made for another user, we'll sync it down
                // on user switch.
                int currentUser = ActivityManager.getCurrentUser();
                UserServices userServices = findOrCreateUserLocked(currentUser);
                boolean changed = updateFromSettingsLocked(currentUser);
                if (changed) {
                    generateAidCacheLocked(userServices);
                    updateRoutingLocked(userServices);
                } else {
                    Log.d(TAG, "Not updating aid cache + routing: nothing changed.");
                }
            }
        }
    };

    private static class UserServices {
        /**
         * All services that have registered
         */
        public final HashMap<ComponentName, ApduServiceInfo> services =
                Maps.newHashMap(); // Re-built at run-time

        /**
         * AIDs per category
         */
        public final HashMap<String, Set<String>> categoryAids =
                Maps.newHashMap();

        /**
         * Default component per category
         */
        public final HashMap<String, ComponentName> defaults =
                Maps.newHashMap();

        /**
         * Whether auto-select mode is enabled per category
         */
        public final HashMap<String, Boolean> mode =
                Maps.newHashMap();
    };

    private UserServices findOrCreateUserLocked(int userId) {
        UserServices services = mUserServices.get(userId);
        if (services == null) {
            services = new UserServices();
            mUserServices.put(userId, services);
        }
        return services;
    }

    public RegisteredServicesCache(Context context, AidRoutingManager routingManager) {
        mContext = context;
        mRoutingManager = routingManager;

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                String action = intent.getAction();
                if (uid != -1) {
                    if (DEBUG) Log.d(TAG, "Intent action: " + action);
                    boolean replaced = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
                            (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                             Intent.ACTION_PACKAGE_REMOVED.equals(action));
                    if (!replaced) {
                        int currentUser = ActivityManager.getCurrentUser();
                        if (currentUser == UserHandle.getUserId(uid)) {
                            invalidateCache(UserHandle.getUserId(uid));
                        } else {
                            // Cache will automatically be updated on user switch
                        }
                    } else {
                        if (DEBUG) Log.d(TAG, "Ignoring package intent due to package being replaced.");
                    }
                }
            }
        };

        mReceiver = new AtomicReference<BroadcastReceiver>(receiver);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(receiver, UserHandle.ALL, intentFilter, null, null);

        // Register for events related to sdcard operations
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiverAsUser(receiver, UserHandle.ALL, sdFilter, null, null);

        SettingsObserver observer = new SettingsObserver(mHandler);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT),
                true, observer, UserHandle.USER_ALL);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NFC_PAYMENT_MODE),
                true, observer, UserHandle.USER_ALL);
    }

    public ArrayList<ApduServiceInfo> resolveAidPrefix(String aid) {
        synchronized (mLock) {
            char nextAidChar = (char) (aid.charAt(aid.length() - 1) + 1);
            String nextAid = aid.substring(0, aid.length() - 1) + nextAidChar;
            SortedMap<String, ArrayList<ApduServiceInfo>> matches =
                    mAidToServices.subMap(aid, nextAid);
            // The first match is lexicographically closest to what the reader asked;
            if (matches.isEmpty()) {
                return null;
            } else {
                return mAidCache.get(matches.firstKey());
            }
        }
    }

    /**
     * Resolves an AID to a set of services that can handle it.
     */
     ArrayList<ApduServiceInfo> resolveAidLocked(UserServices userServices, String aid) {
        ArrayList<ApduServiceInfo> resolvedServices = mAidToServices.get(aid);
        if (resolvedServices == null || resolvedServices.size() == 0) {
            Log.d(TAG, "Could not resolve AID " + aid + " to any service.");
            return null;
        }
        Log.e(TAG, "resolveAidLocked: resolving AID " + aid);
        ArrayList<ApduServiceInfo> resolved = new ArrayList<ApduServiceInfo>();

        ComponentName defaultComponent = mNextTapComponent;
        Log.d(TAG, "resolveAidLocked: next tap component is " + defaultComponent);
        Set<String> paymentAids = userServices.categoryAids.get(AID_CATEGORY_PAYMENT);
        if (paymentAids != null && paymentAids.contains(aid)) {
            Log.d(TAG, "resolveAidLocked: AID " + aid + " is a payment AID");
            // This AID has been registered as a payment AID by at least one service.
            // Get default component for payment if no next tap default.
            if (defaultComponent == null) {
                defaultComponent = userServices.defaults.get(AID_CATEGORY_PAYMENT);
            }
            Log.d(TAG, "resolveAidLocked: default payment component is " + defaultComponent);
            if (resolvedServices.size() == 1) {
                ApduServiceInfo resolvedService = resolvedServices.get(0);
                Log.d(TAG, "resolveAidLocked: resolved single service " +
                        resolvedService.getComponent());
                if (resolvedService.equals(defaultComponent)) {
                    Log.d(TAG, "resolveAidLocked: DECISION: routing to (default) " +
                        resolvedService.getComponent());
                    resolvedServices.clear();
                    resolved.add(resolvedService);
                } else {
                    // Route to this one service if uncontended for all AIDs.
                    boolean foundConflict = false;
                    for (String registeredAid : resolvedService.getAids()) {
                        ArrayList<ApduServiceInfo> servicesForAid =
                                mAidToServices.get(registeredAid);
                        if (servicesForAid != null && servicesForAid.size() > 1) {
                            foundConflict = true;
                        }
                    }
                    if (!foundConflict) {
                        Log.d(TAG, "resolveAidLocked: DECISION: routing to " +
                            resolvedService.getComponent());
                        resolved.add(resolvedService);
                    } else {
                        // will drop out below with empty list
                        Log.d(TAG, "resolveAidLocked: DECISION: not routing AID " + aid +
                                " because only part of it's <aid-group> is uncontended");
                    }
                }
            } else if (resolvedServices.size() > 1) {
                // More services have registered. If there's a default and it
                // registered this AID, go with the default. Otherwise, add all.
                Log.d(TAG, "resolveAidLocked: multiple services matched.");
                if (defaultComponent != null) {
                    for (ApduServiceInfo service : resolvedServices) {
                        if (service.getComponent().equals(defaultComponent)) {
                            Log.d(TAG, "resolveAidLocked: DECISION: routing to (default) " +
                                    service.getComponent());
                            resolved.add(service);
                            break;
                        }
                    }
                    if (resolved.size() == 0) {
                        // If we didn't find the default, just add all
                        // This happens when multiple payment apps have registered
                        // for an AID, but the default does not handle it. In this
                        // case, let the user pick, and that app will be the default
                        // for it's aid groups for the next tap.
                        // TODO do we want to show different UI in this case?
                        Log.d(TAG, "resolveAidLocked: DECISION: routing all");
                        resolved.addAll(resolvedServices);
                    }
                } else {
                    Log.d(TAG, "resolveAidLocked: DECISION: no default, routing all");
                    resolved.addAll(resolvedServices);
                }
            } // else -> should not hit, we checked for 0 before.
        } else {
            // This AID is not a payment AID, just return all components
            // that can handle it.
            Log.d(TAG, "resolveAidLocked: DECISION: cat OTHER AID, routing all");
            for (ApduServiceInfo service : resolvedServices) {
                resolved.add(service);
            }
        }
        return resolved;
    }

    void dump(ArrayList<ApduServiceInfo> services) {
        for (ApduServiceInfo service : services) {
            if (DEBUG) Log.d(TAG, service.toString());
        }
    }

    void generateAidCategoriesLocked(UserServices userServices) {
        // Trash existing mapping
        userServices.categoryAids.clear();

        for (ApduServiceInfo service : userServices.services.values()) {
            ArrayList<AidGroup> aidGroups = service.getAidGroups();
            if (aidGroups == null) continue;
            for (AidGroup aidGroup : aidGroups) {
                String groupCategory = aidGroup.getCategory();
                Set<String> categoryAids = userServices.categoryAids.get(groupCategory);
                if (categoryAids == null) {
                    categoryAids = new HashSet<String>();
                }
                categoryAids.addAll(aidGroup.getAids());
                userServices.categoryAids.put(groupCategory, categoryAids);
            }
        }
    }

    void generateAidTreeForUserLocked(UserServices userServices) {
        // Easiest is to just build the entire tree again
        mAidToServices.clear();
        for (ApduServiceInfo service : userServices.services.values()) {
            Log.d(TAG, "generateAidTree component: " + service.getComponent());
            for (String aid : service.getAids()) {
                Log.d(TAG, "generateAidTree AID: " + aid);
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

    void generateAidCacheLocked(UserServices userServices) {
        mAidCache.clear();
        for (Map.Entry<String, ArrayList<ApduServiceInfo>> aidEntry:
            mAidToServices.entrySet()) {
            String aid = aidEntry.getKey();
            Log.e(TAG, "Mapping aid " + aid + "to: " + resolveAidLocked(userServices, aid));
            mAidCache.put(aid, resolveAidLocked(userServices, aid));
        }
    }

    boolean containsServiceLocked(ArrayList<ApduServiceInfo> services, ComponentName serviceName) {
        for (ApduServiceInfo service : services) {
            if (service.getComponent().equals(serviceName)) return true;
        }
        return false;
    }

    boolean updateFromSettingsLocked(int userId) {
        UserServices userServices = findOrCreateUserLocked(userId);
        boolean changed = false;

        // Load current payment default from settings
        String name = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                userId);
        ComponentName newDefault = name != null ? ComponentName.unflattenFromString(name) : null;
        ComponentName oldDefault = userServices.defaults.put(AID_CATEGORY_PAYMENT, newDefault);
        changed |= newDefault != oldDefault;
        Log.e(TAG, "Set default component to: " + (name != null ?
                ComponentName.unflattenFromString(name) : "null"));

        // Load payment mode from settings
        String newModeString = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.NFC_PAYMENT_MODE, userId);
        boolean newMode = !CardEmulationManager.PAYMENT_MODE_MANUAL.equals(newModeString);
        Log.e(TAG, "Setting mode to: " + newMode);
        Boolean oldMode = userServices.mode.put(AID_CATEGORY_PAYMENT, newMode);
        changed |= (oldMode == null) || (newMode != oldMode.booleanValue());

        return changed;
    }

    public String getCategoryForAid(int userId, String aid) {
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            // Optimize this later
            Set<String> paymentAids = userServices.categoryAids.get(AID_CATEGORY_PAYMENT);
            if (paymentAids != null && paymentAids.contains(aid)) {
                return CardEmulationManager.CATEGORY_PAYMENT;
            } else {
                return CardEmulationManager.CATEGORY_OTHER;
            }
        }
    }
    public ApduServiceInfo getDefaultServiceForCategory(int userId, String category) {
        if (!CardEmulationManager.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return null;
        }
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            // Load current payment default from settings
            String name = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                    userId);
            if (name != null) {
                ComponentName serviceComponent = ComponentName.unflattenFromString(name);
                return serviceComponent != null ?
                        userServices.services.get(serviceComponent) : null;
            } else {
                return null;
            }
        }
    }

    public boolean isDefaultServiceForAid(int userId, ComponentName service, String aid) {
        ArrayList<ApduServiceInfo> serviceList = mAidCache.get(aid);
        if (serviceList == null || serviceList.size() == 0) return false;

        ApduServiceInfo serviceInfo = serviceList.get(0);

        return service.equals(serviceInfo);
    }

    public boolean setDefaultServiceForCategory(int userId, ComponentName service,
            String category) {
        if (!CardEmulationManager.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return false;
        }
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            // TODO Not really nice to be writing to Settings.Secure here...
            // ideally we overlay our local changes over whatever is in
            // Settings.Secure
            if (userServices.services.get(service) != null) {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT, service.flattenToString(),
                        userId);
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.NFC_PAYMENT_MODE, CardEmulationManager.PAYMENT_MODE_AUTO,
                        userId);
            } else {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT, null,
                        userId);
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.NFC_PAYMENT_MODE, CardEmulationManager.PAYMENT_MODE_MANUAL,
                        userId);
            }
        }
        return true;
    }

    public boolean isNextTapOverriden() {
        return mNextTapComponent != null;
    }

    public boolean setDefaultForNextTap(int userId, ComponentName service) {
        synchronized (mLock) {
            if (service != null) {
                mNextTapComponent = service;
            } else {
                mNextTapComponent = null;
            }
            UserServices userServices = findOrCreateUserLocked(userId);
            // Update state and routing table
            generateAidCacheLocked(userServices);
            updateRoutingLocked(userServices);
        }
        return true;
    }

    public ArrayList<ApduServiceInfo> getServicesForCategory(int userId, String category) {
        final ArrayList<ApduServiceInfo> enabledServices = new ArrayList<ApduServiceInfo>();
        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);
            for (ApduServiceInfo service : userServices.services.values()) {
                if (service.hasCategory(category)) enabledServices.add(service);
            }
        }
        return enabledServices;
    }

    public void invalidateCache(int userId) {
        // This code consists of a few phases:
        // - Finding all HCE services and making sure mUserServices.services is correct
        // - Rebuilding the UserServices.categoryAids HashMap from userServices
        // - Rebuilding the mAidToServices lookup table from userServices
        // - Rebuilding the mAidCache lookup table from mAidToServices
        // - Rebuilding the AID routing table from mAidToServices lookup table
        // 1. Finding all HCE services and making sure mUserServices is correct
        PackageManager pm;
        try {
            pm = mContext.createPackageContextAsUser("android", 0,
                    new UserHandle(userId)).getPackageManager();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not create user package context");
            return;
        }

        ArrayList<ApduServiceInfo> validServices = new ArrayList<ApduServiceInfo>();

        List<ResolveInfo> resolvedServices = pm.queryIntentServicesAsUser(
                new Intent(HostApduService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA, userId);

        List<ResolveInfo> resolvedOffHostServices = pm.queryIntentServicesAsUser(
                new Intent(OffHostApduService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA, userId);
        resolvedServices.addAll(resolvedOffHostServices);

        for (ResolveInfo resolvedService : resolvedServices) {
            try {
                boolean onHost = !resolvedOffHostServices.contains(resolvedService);
                ServiceInfo si = resolvedService.serviceInfo;
                ComponentName componentName = new ComponentName(si.packageName, si.name);
                if (!android.Manifest.permission.BIND_NFC_SERVICE.equals(
                        si.permission)) {
                    Log.e(TAG, "Skipping APDU service " + componentName +
                            ": it does not require the permission " +
                            android.Manifest.permission.BIND_NFC_SERVICE);
                    continue;
                }
                ApduServiceInfo service = new ApduServiceInfo(pm, resolvedService, onHost);
                if (service != null) {
                    validServices.add(service);
                }
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Unable to load component info " + resolvedService.toString(), e);
            } catch (IOException e) {
                Log.w(TAG, "Unable to load component info " + resolvedService.toString(), e);
            }
        }

        synchronized (mLock) {
            UserServices userServices = findOrCreateUserLocked(userId);

            updateFromSettingsLocked(userId);

            // Deal with defaults; if a default app is removed, notify the user.
            ComponentName defaultForPayment = userServices.defaults.get(AID_CATEGORY_PAYMENT);
            boolean paymentModeAuto = userServices.mode.get(AID_CATEGORY_PAYMENT);

            // Find removed services
            Iterator<Map.Entry<ComponentName, ApduServiceInfo>> it =
                    userServices.services.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ComponentName, ApduServiceInfo> entry =
                        (Map.Entry<ComponentName, ApduServiceInfo>) it.next();
                if (!containsServiceLocked(validServices, entry.getKey())) {
                    Log.d(TAG, "Service removed: " + entry.getKey());
                    if (entry.getKey().equals(defaultForPayment) && paymentModeAuto) {
                        Log.d(TAG, "Clearing as default for payment");
                        notifyDefaultServiceRemoved(entry.getValue().loadLabel(pm));
                    }
                    it.remove();
                }
            }

            int numPaymentApps = 0;
            ApduServiceInfo paymentService = null;
            for (ApduServiceInfo service : validServices) {
                if (DEBUG) Log.d(TAG, "Processing service: " + service.getComponent() +
                        "AIDs: " + service.getAids());
                ApduServiceInfo existingService =
                        userServices.services.put(service.getComponent(), service);
                if (service.hasCategory(AID_CATEGORY_PAYMENT)) {
                    numPaymentApps++;
                    paymentService = service;
                }
                if (existingService != null && existingService.hasCategory(AID_CATEGORY_PAYMENT) &&
                            !service.hasCategory(AID_CATEGORY_PAYMENT)) {
                    // This is a rather special case but we have to deal with it: what if your default
                    // payment app suddenly stops offering payment AIDs.
                    if (service.getComponent().equals(defaultForPayment) && paymentModeAuto) {
                        notifyDefaultServiceRemoved(existingService.loadLabel(pm));
                    }
                }
            }

            if (numPaymentApps == 1) {
                Log.d(TAG, "Only one payment app installed, setting default to: " + paymentService.getComponent());
                setDefaultServiceForCategory(userId, paymentService.getComponent(),
                        AID_CATEGORY_PAYMENT);
            }

            // 2. Generate AID category hashmap
            generateAidCategoriesLocked(userServices);

            // 3. Rebuild mAidToServices
            generateAidTreeForUserLocked(userServices);

            // 4. Generate a quick-lookup AID cache
            generateAidCacheLocked(userServices);

            // 5. Recompute routing table from the AID cache
            updateRoutingLocked(userServices);
        }
        dump(validServices);
    }

    void notifyDefaultServiceRemoved(CharSequence serviceName) {
        Intent intent = new Intent(mContext, DefaultRemovedActivity.class);
        intent.putExtra(DefaultRemovedActivity.EXTRA_DEFAULT_NAME, serviceName);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    void updateRoutingLocked(UserServices userServices) {
        final Set<String> handledAids = new HashSet<String>();
        // For each AID, find interested services
        for (Map.Entry<String, ArrayList<ApduServiceInfo>> aidEntry:
                mAidCache.entrySet()) {
            String aid = aidEntry.getKey();
            ArrayList<ApduServiceInfo> aidServices = aidEntry.getValue();
            if (aidServices.size() == 0) {
                // No interested services, if there is a current routing remove it
                mRoutingManager.removeAid(aid);
            } else if (aidServices.size() == 1) {
                // Only one service, make sure that is the current default route
                ApduServiceInfo service = aidServices.get(0);
                mRoutingManager.setRouteForAid(aid, service.isOnHost());
            } else if (aidServices.size() > 1) {
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
                if (DEBUG) Log.d(TAG, "Removing routing for AID " + aid + ", because " +
                        "there are no no interested services.");
                mRoutingManager.removeAid(aid);
            }
        }
        // And commit the routing
        mRoutingManager.commitRouting();
    }
}
