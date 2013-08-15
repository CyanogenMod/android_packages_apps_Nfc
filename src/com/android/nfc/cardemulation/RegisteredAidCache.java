package com.android.nfc.cardemulation;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.ApduServiceInfo.AidGroup;
import android.nfc.cardemulation.CardEmulationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.google.android.collect.Maps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class RegisteredAidCache implements RegisteredServicesCache.Callback {
    static final String TAG = "RegisteredAidCache";

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

    final HashMap<String, ComponentName> mCurrentDefaults =
            Maps.newHashMap();

    final HashMap<String, Boolean> mCurrentMode =
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

    final Handler mHandler = new Handler(Looper.getMainLooper());
    final RegisteredServicesCache mServiceCache;

    final Object mLock = new Object();
    final Context mContext;
    final AidRoutingManager mRoutingManager;

    ComponentName mNextTapComponent = null;
    List<ApduServiceInfo> mCurrentServices = null;

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
                boolean changed = updateFromSettingsLocked(currentUser);
                if (changed) {
                    generateAidCacheLocked();
                    updateRoutingLocked();
                } else {
                    Log.d(TAG, "Not updating aid cache + routing: nothing changed.");
                }
            }
        }
    };

    public RegisteredAidCache(Context context) {
        SettingsObserver observer = new SettingsObserver(mHandler);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT),
                true, observer, UserHandle.USER_ALL);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NFC_PAYMENT_MODE),
                true, observer, UserHandle.USER_ALL);
        mContext = context;
        mServiceCache = new RegisteredServicesCache(context, this);
        mRoutingManager = new AidRoutingManager();

        updateFromSettingsLocked(ActivityManager.getCurrentUser());
    }

    public boolean isNextTapOverriden() {
        return mNextTapComponent != null;
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
            Set<String> paymentAids = mCategoryAids.get(CardEmulationManager.CATEGORY_PAYMENT);
            if (paymentAids != null && paymentAids.contains(aid)) {
                return CardEmulationManager.CATEGORY_PAYMENT;
            } else {
                return CardEmulationManager.CATEGORY_OTHER;
            }
        }
    }

    public boolean isDefaultServiceForAid(int userId, ComponentName service, String aid) {
        AidResolveInfo resolveInfo = mAidCache.get(aid);

        if (resolveInfo.services == null || resolveInfo.services.size() == 0
                || resolveInfo.services.size() > 1) return false;
        ApduServiceInfo serviceInfo = resolveInfo.services.get(0);

        return service.equals(serviceInfo);
    }

    public boolean setDefaultServiceForCategory(int userId, ComponentName service,
            String category) {
        if (!CardEmulationManager.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return false;
        }
        synchronized (mLock) {
            // TODO Not really nice to be writing to Settings.Secure here...
            // ideally we overlay our local changes over whatever is in
            // Settings.Secure
            if (mServiceCache.hasService(userId, service)) {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT, service.flattenToString(),
                        userId);
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.NFC_PAYMENT_MODE, CardEmulationManager.PAYMENT_MODE_AUTO,
                        userId);
            } else {
                Log.e(TAG, "Could not find default service to make default: " + service);
            }
        }
        return true;
    }

    public ComponentName getDefaultServiceForCategory(int userId, String category) {
        if (!CardEmulationManager.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return null;
        }
        synchronized (mLock) {
            // Load current payment default from settings
            String name = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                    userId);
            if (name != null) {
                ComponentName service = ComponentName.unflattenFromString(name);
                return ((service != null && mServiceCache.hasService(userId, service)) ?
                        service : null);
            } else {
                return null;
            }
        }
    }

    public List<ApduServiceInfo> getServicesForCategory(int userId, String category) {
        return mServiceCache.getServicesForCategory(userId, category);
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
            Log.d(TAG, "Could not resolve AID " + aid + " to any service.");
            return null;
        }
        AidResolveInfo resolveInfo = new AidResolveInfo();
        Log.e(TAG, "resolveAidLocked: resolving AID " + aid);
        resolveInfo.services = new ArrayList<ApduServiceInfo>();
        resolveInfo.defaultService = null;

        ComponentName defaultComponent = mNextTapComponent;
        Log.d(TAG, "resolveAidLocked: next tap component is " + defaultComponent);
        Set<String> paymentAids = mCategoryAids.get(CardEmulationManager.CATEGORY_PAYMENT);
        if (paymentAids != null && paymentAids.contains(aid)) {
            Log.d(TAG, "resolveAidLocked: AID " + aid + " is a payment AID");
            // This AID has been registered as a payment AID by at least one service.
            // Get default component for payment if no next tap default.
            if (defaultComponent == null) {
                defaultComponent = mCurrentDefaults.get(CardEmulationManager.CATEGORY_PAYMENT);
            }
            Log.d(TAG, "resolveAidLocked: default payment component is " + defaultComponent);
            if (resolvedServices.size() == 1) {
                ApduServiceInfo resolvedService = resolvedServices.get(0);
                Log.d(TAG, "resolveAidLocked: resolved single service " +
                        resolvedService.getComponent());
                if (resolvedService.equals(defaultComponent)) {
                    Log.d(TAG, "resolveAidLocked: DECISION: routing to (default) " +
                        resolvedService.getComponent());
                    resolveInfo.services.add(resolvedService);
                    resolveInfo.defaultService = resolvedService;
                } else {
                    // So..since we resolved to only one service, and this AID
                    // is a payment AID, we know that this service is the only
                    // service that has registered for this AID and in fact claimed
                    // it was a payment AID.
                    // There's two cases:
                    // 1. All other AIDs in the payment group are uncontended:
                    //    in this case, just route to this app. It won't get
                    //    in the way of other apps, and is likely to interact
                    //    with different terminal infrastructure anyway.
                    // 2. At least one AID in the payment group is contended:
                    //    in this case, we should ask the user to confirm,
                    //    since it is likely to contend with other apps, even
                    //    when touching the same terminal.
                    boolean foundConflict = false;
                    for (AidGroup aidGroup : resolvedService.getAidGroups()) {
                        if (aidGroup.getCategory().equals(CardEmulationManager.CATEGORY_PAYMENT)) {
                            for (String registeredAid : aidGroup.getAids()) {
                                ArrayList<ApduServiceInfo> servicesForAid =
                                        mAidToServices.get(registeredAid);
                                if (servicesForAid != null && servicesForAid.size() > 1) {
                                    foundConflict = true;
                                }
                            }
                        }
                    }
                    if (!foundConflict) {
                        Log.d(TAG, "resolveAidLocked: DECISION: routing to " +
                            resolvedService.getComponent());
                        // Treat this as if it's the default for this AID
                        resolveInfo.services.add(resolvedService);
                        resolveInfo.defaultService = resolvedService;
                    } else {
                        // Allow this service to handle, but don't set as default
                        resolveInfo.services.add(resolvedService);
                        Log.d(TAG, "resolveAidLocked: DECISION: routing AID " + aid +
                                " to " + resolvedService.getComponent() +
                                ", but will ask confirmation because its AID group is contended.");
                    }
                }
            } else if (resolvedServices.size() > 1) {
                // More services have registered. If there's a default and it
                // registered this AID, go with the default. Otherwise, add all.
                Log.d(TAG, "resolveAidLocked: multiple services matched.");
                resolveInfo.services.addAll(resolvedServices);
                if (defaultComponent != null) {
                    for (ApduServiceInfo service : resolvedServices) {
                        if (service.getComponent().equals(defaultComponent)) {
                            Log.d(TAG, "resolveAidLocked: DECISION: routing to (default) " +
                                    service.getComponent());
                            resolveInfo.defaultService = service;
                            break;
                        }
                    }
                    if (resolveInfo.defaultService == null) {
                        Log.d(TAG, "resolveAidLocked: DECISION: routing to all services");
                    }
                }
            } // else -> should not hit, we checked for 0 before.
        } else {
            // This AID is not a payment AID, just return all components
            // that can handle it, but be mindful of (next tap) defaults.
            resolveInfo.services.addAll(resolvedServices);
            for (ApduServiceInfo service : resolvedServices) {
                if (service.getComponent().equals(defaultComponent)) {
                    Log.d(TAG, "resolveAidLocked: DECISION: cat OTHER AID, routing to (default) " +
                            service.getComponent());
                    resolveInfo.defaultService = service;
                    break;
                }
            }
            if (resolveInfo.defaultService == null) {
                // If we didn't find the default, mark the first as default
                // if there is only one.
                if (resolveInfo.services.size() == 1) {
                    resolveInfo.defaultService = resolveInfo.services.get(0);
                    Log.d(TAG, "resolveAidLocked: DECISION: cat OTHER AID, routing to (default) " +
                            resolveInfo.defaultService.getComponent());
                } else {
                    Log.d(TAG, "resolveAidLocked: DECISION: cat OTHER AID, routing all");
                }
            }
        }
        return resolveInfo;
    }

    void notifyDefaultServiceRemoved(CharSequence serviceName) {
        Intent intent = new Intent(mContext, DefaultRemovedActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(DefaultRemovedActivity.EXTRA_DEFAULT_NAME, serviceName);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    void generateAidTreeLocked() {
        // Easiest is to just build the entire tree again
        mAidToServices.clear();
        for (ApduServiceInfo service : mCurrentServices) {
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

    void generateAidCategoriesLocked() {
        // Trash existing mapping
        mCategoryAids.clear();

        for (ApduServiceInfo service : mCurrentServices) {
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

    boolean updateFromSettingsLocked(int userId) {
        boolean changed = false;

        // Load current payment default from settings
        String name = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                userId);
        ComponentName newDefault = name != null ? ComponentName.unflattenFromString(name) : null;
        ComponentName oldDefault = mCurrentDefaults.put(CardEmulationManager.CATEGORY_PAYMENT,
                newDefault);
        changed |= newDefault != oldDefault;
        Log.e(TAG, "Set default component to: " + (name != null ?
                ComponentName.unflattenFromString(name) : "null"));

        // Load payment mode from settings
        String newModeString = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.NFC_PAYMENT_MODE, userId);
        boolean newMode = !CardEmulationManager.PAYMENT_MODE_MANUAL.equals(newModeString);
        Log.e(TAG, "Setting mode to: " + newMode);
        Boolean oldMode = mCurrentMode.put(CardEmulationManager.CATEGORY_PAYMENT, newMode);
        changed |= (oldMode == null) || (newMode != oldMode.booleanValue());

        return changed;
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
                Log.d(TAG, "Removing routing for AID " + aid + ", because " +
                        "there are no no interested services.");
                mRoutingManager.removeAid(aid);
            }
        }
        // And commit the routing
        mRoutingManager.commitRouting();
    }

    @Override
    public void onServiceRemoved(ComponentName service) {
        // Can no longer load the label, since the app
        // is likely gone from the filesystem.
        notifyDefaultServiceRemoved("");
    }

    @Override
    public void onServiceCategoryRemoved(ApduServiceInfo service, String category) {
        PackageManager pm;
        try {
            pm = mContext.createPackageContextAsUser("android", 0,
                    UserHandle.CURRENT).getPackageManager();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not create user package context");
            return;
        }
        notifyDefaultServiceRemoved(service.loadLabel(pm));
    }

    @Override
    public void onServicesUpdated(int userId) {
        synchronized (mLock) {
            if (ActivityManager.getCurrentUser() == userId) {
                mCurrentServices = mServiceCache.getServices(userId);
                // Rebuild our internal data-structure
                generateAidTreeLocked();
                generateAidCategoriesLocked();
                generateAidCacheLocked();
                updateRoutingLocked();
            } else {
                Log.e(TAG, "Ignoring service update because it is not for the current user.");
            }
        }
    }

    public void invalidateCache(int currentUser) {
        mServiceCache.invalidateCache(currentUser);
    }
}