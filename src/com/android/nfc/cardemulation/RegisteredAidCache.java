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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Environment;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.google.android.collect.Maps;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

public class RegisteredAidCache {
    static final String TAG = "RegisteredAidCache";
    static final String AID_ACTION = "android.nfc.action.AID_SELECTED"; // TODO move to API
    static final boolean DEBUG = true;

    final Context mContext;
    final AtomicReference<BroadcastReceiver> mReceiver;
    final AtomicFile mAidDefaultsFile;
    final AidRoutingManager mRoutingManager;

    final Object mLock = new Object();
    // All variables below synchronized on mLock

    // mUserServices holds the card emulation services that are running for each user
    final SparseArray<UserServices> mUserServices = new SparseArray<UserServices>();

    // mAidServices is a tree that maps an AID to a list of handling services
    // running on Android. It does *not* include apps that registered AIDs
    // for an eSE/UICC
    final TreeMap<String, ArrayList<CardEmulationService>> mAidToServices =
            new TreeMap<String, ArrayList<CardEmulationService>>();

    public static class CardEmulationService {
        public final ComponentName serviceName;
        public final ArrayList<String> aids;
        public final int route;
        public final boolean isPaymentService;

        CardEmulationService(ComponentName serviceName, ArrayList<String> aids) {
            this.serviceName = serviceName;
            this.aids = aids;
            this.route = 0; // TODO
            this.isPaymentService = false; // TODO
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder("ComponentInfo: ");
            out.append(serviceName);
            out.append(", AIDs: ");
            for (String aid : aids) {
                out.append(aid);
                out.append(", ");
            }
            return out.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CardEmulationService)) return false;
            CardEmulationService thatService = (CardEmulationService) o;

            return thatService.serviceName.equals(this.serviceName);
        }

        @Override
        public int hashCode() {
            return serviceName.hashCode();
        }
    }

    private static class UserServices {
        public final HashMap<ComponentName, CardEmulationService> services =
                Maps.newHashMap();
        public final HashMap<String, ComponentName> defaults =
                Maps.newHashMap(); // Persisted on file-system
    };

    private UserServices findOrCreateUserLocked(int userId) {
        UserServices services = mUserServices.get(userId);
        if (services == null) {
            services = new UserServices();
            mUserServices.put(userId, services);
        }
        return services;
    }

    public RegisteredAidCache(Context context, AidRoutingManager routingManager) {
        mContext = context;
        mRoutingManager = routingManager;

        File dataDir = Environment.getDataDirectory();
        File nfcDir = new File(dataDir, "nfc");
        mAidDefaultsFile = new AtomicFile(new File(nfcDir, "aid_defaults"));

        // Load defaults from file
        readDefaultsFromPersistentFile();

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
                        generateServicesForUser(UserHandle.getUserId(uid));
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
    }

    public ArrayList<CardEmulationService> resolveAidPrefix(String aid) {
        if (aid == null || aid.length() == 0) return null;

        ArrayList<CardEmulationService> matchedServices = new ArrayList<CardEmulationService>();
        synchronized (mLock) {
            char nextAidChar = (char) (aid.charAt(aid.length() - 1) + 1);
            String nextAid = aid.substring(0, aid.length() - 1) + nextAidChar;
            SortedMap<String, ArrayList<CardEmulationService>> matches = mAidToServices.subMap(aid, nextAid);
            // Iterate over all matches
            for (Map.Entry<String, ArrayList<CardEmulationService>> match : matches.entrySet()) {
                dump(match.getValue());
                matchedServices.addAll(match.getValue());
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Matched services: ");
            dump(matchedServices);
        }
        return matchedServices;
    }

    void dump(ArrayList<CardEmulationService> services) {
        for (CardEmulationService service : services) {
            if (DEBUG) Log.d(TAG, service.toString());
        }
    }

    void generateAidTreeForUserLocked(UserServices userServices) {
        Log.d(TAG, "generateAidTree");
        // Easiest is to just build the entire tree again
        mAidToServices.clear();
        for (CardEmulationService service : userServices.services.values()) {
            Log.d(TAG, "generateAidTree component: " + service.serviceName);
            for (String aid : service.aids) {
                Log.d(TAG, "generateAidTree AID: " + aid);
                // Check if a mapping exists for this AID
                if (mAidToServices.containsKey(aid)) {
                    final ArrayList<CardEmulationService> aidServices = mAidToServices.get(aid);
                    aidServices.add(service);
                } else {
                    final ArrayList<CardEmulationService> aidServices =
                            new ArrayList<CardEmulationService>();
                    aidServices.add(service);
                    mAidToServices.put(aid, aidServices);
                }
            }
        }
    }

    void addAidsForServiceLocked(CardEmulationService service, ArrayList<String> aids) {
        service.aids.addAll(aids);
    }

    void removeAidsForServiceLocked(UserServices services, CardEmulationService service,
            ArrayList<String> aids) {
        for (String aid : aids) {
            if (services.defaults.containsKey(aid) &&
                    services.defaults.get(aid).equals(service.serviceName)) {
                // Remove default
                if (DEBUG) Log.d(TAG, "Removing default service for AID: " + aid);
                services.defaults.remove(aid);
            }
            service.aids.remove(aid);
        }
    }

    boolean containsServiceLocked(ArrayList<CardEmulationService> services, ComponentName serviceName) {
        for (CardEmulationService service : services) {
            if (service.serviceName.equals(serviceName)) return true;
        }
        return false;
    }

    public void generateServicesForUser(int userId) {
        // This code consists of 3 main phases:
        // - Finding all HCE services and making sure mUserServices is correct
        // - Rebuilding the mAidToServices lookup table from userServices
        // - Rebuilding the AID routing table from mAidToServices lookup table

        // 1. Finding all HCE services and making sure mUserServices is correct
        PackageManager pm;
        try {
            UserHandle currentUser = new UserHandle(ActivityManager.getCurrentUser());
            pm = mContext.createPackageContextAsUser("android", 0,
                    currentUser).getPackageManager();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not create user package context");
            return;
        }

        ArrayList<CardEmulationService> validServices = new ArrayList<CardEmulationService>();

        List<ResolveInfo> resolvedServices = pm.queryIntentServicesAsUser(new Intent(AID_ACTION),
                PackageManager.GET_META_DATA, ActivityManager.getCurrentUser());

        for (ResolveInfo resolvedService : resolvedServices) {
            try {
                CardEmulationService service = parseServiceInfo(pm, resolvedService);
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

            // Find removed services
            Iterator<Map.Entry<ComponentName, CardEmulationService>> it =
                    userServices.services.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ComponentName, CardEmulationService> entry =
                        (Map.Entry<ComponentName, CardEmulationService>) it.next();
                if (!containsServiceLocked(validServices, entry.getKey())) {
                    Log.d(TAG, "Service removed: " + entry.getKey());
                    it.remove();
                }
            }
            for (CardEmulationService service : validServices) {
                if (DEBUG) Log.d(TAG, "Processing service: " + service.serviceName +
                        "AIDs: " + service.aids);
                // TODO this is O(N^2) and not very nice
                if (userServices.services.containsKey(service.serviceName)) {
                    ArrayList<String> addedAids = new ArrayList<String>();
                    ArrayList<String> removedAids = new ArrayList<String>();
                    CardEmulationService existingService = userServices.services.get(service.serviceName);
                    for (String aid : existingService.aids) {
                        if (!service.aids.contains(aid)) removedAids.add(aid);
                    }
                    for (String aid : service.aids) {
                        if (!existingService.aids.contains(aid)) addedAids.add(aid);
                    }
                    addAidsForServiceLocked(existingService, addedAids);
                    removeAidsForServiceLocked(userServices, existingService, removedAids);
                } else {
                    // New service, just store it
                    userServices.services.put(service.serviceName, service);
                }
            }
            // 2. Rebuild mAidToServices
            generateAidTreeForUserLocked(userServices);

            // 3. Recompute routing table
            updateRoutingLocked(userServices);
        }
        dump(validServices);
    }

    void updateRoutingLocked(UserServices userServices) {
        final Set<String> handledAids = new HashSet<String>();
        // For each AID, find interested services
        for (Map.Entry<String, ArrayList<CardEmulationService>> aidEntry :
                mAidToServices.entrySet()) {
            String aid = aidEntry.getKey();
            ArrayList<CardEmulationService> aidServices = aidEntry.getValue();
            // Find the current routing for this AID
            int currentRoute = mRoutingManager.getRouteForAid(aid);
            if (DEBUG) Log.d(TAG, "updateRouting: AID " + aid + " registered service count: " +
                    Integer.toString(aidServices.size()) + " current route: " +
                    Integer.toString(currentRoute));
            if (aidServices.size() == 0) {
                // No interested services, if there is a current routing remove it
                mRoutingManager.removeAid(aid);
            } else if (aidServices.size() == 1) {
                // Only one service, make sure that is the current default route
                CardEmulationService service = aidServices.get(0);
                mRoutingManager.setRouteForAid(aid, service.route);
            } else if (aidServices.size() > 1) {
                // Multiple services, check for default, if no default,
                // route to host to ask for default.
                if (userServices.defaults.get(aid) == null) {
                    if (currentRoute != 0) { // TODO constant for host
                        mRoutingManager.setRouteForAid(aid, 0);
                    }
                } else {
                    // Default is set, make sure the current route points to it
                    ComponentName defaultServiceName = userServices.defaults.get(aid);
                    CardEmulationService defaultService =
                            userServices.services.get(defaultServiceName);
                    if (defaultService != null && currentRoute != defaultService.route) {
                        mRoutingManager.setRouteForAid(aid, defaultService.route);
                    }
                }
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

    CardEmulationService parseServiceInfo(PackageManager pm, ResolveInfo info) throws XmlPullParserException, IOException {
        ServiceInfo si = info.serviceInfo;

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, AID_ACTION);
            if (parser == null) {
                throw new XmlPullParserException("No " + AID_ACTION + " meta-data");
            }

            return parseAidList(pm.getResourcesForApplication(si.applicationInfo), si.packageName,
                    parser, info);
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException("Unable to load resources for " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }
    }

    CardEmulationService parseAidList(Resources res, String packageName, XmlPullParser parser,
            ResolveInfo resolveInfo)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.START_TAG) {
            eventType = parser.next();
        }

        ArrayList<String> items = new ArrayList<String>();
        String tagName;
        eventType = parser.next();
        do {
            tagName = parser.getName();
            if (eventType == XmlPullParser.START_TAG && "aid".equals(tagName)) {
                items.add(parser.nextText());
            } else if (eventType == XmlPullParser.END_TAG && "aid-list".equals(tagName)) {
                int size = items.size();
                if (size > 0) {
                    final ArrayList<String> aids = new ArrayList<String>();
                    for (String aid : items) {
                        if (isValidAid(aid)) aids.add(aid);
                    }
                    if (aids.size() > 0)
                        return new CardEmulationService(new ComponentName(resolveInfo.serviceInfo.packageName,
                                resolveInfo.serviceInfo.name), aids);
                    else
                        items.clear();
                }
            }
            eventType = parser.next();
        } while (eventType != XmlPullParser.END_DOCUMENT);

        return null;
    }

    /**
     * Parses whether a String contains a valid AID. Rules:
     * - AID may not be null or empty
     * - Number of characters is even (ie 0123 is valid, 123 is not)
     * - TODO min prefix length?
     */
    boolean isValidAid(String aid) {
        if (aid == null)
            return false;

        int aidLength = aid.length();
        if (aidLength == 0 || (aidLength % 2) != 0) {
            Log.e(TAG, "AID " + aid + " is not correctly formatted.");
            return false;
        }
        return true;
    }

    void readDefaultsFromPersistentFile() {
        FileInputStream fis = null;
        try {
            if (!mAidDefaultsFile.getBaseFile().exists()) {
                if (DEBUG) Log.d(TAG, "defaults file did not exist");
                return;
            }
            fis = mAidDefaultsFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            String tagName = parser.getName();
            UserServices userServices = null;
            synchronized (mLock) {
                do {
                    if (eventType == XmlPullParser.START_TAG && "services".equals(tagName)) {
                        int currentUserId = Integer.parseInt(parser.getAttributeValue(0));
                        userServices = findOrCreateUserLocked(currentUserId);
                    }

                    if (eventType == XmlPullParser.START_TAG && "aid".equals(tagName) &&
                            userServices != null) {
                        String aid = parser.getAttributeValue(0);
                        String component = parser.getAttributeValue(1);

                        ComponentName componentName = ComponentName.unflattenFromString(component);
                        if (componentName != null) {
                            userServices.defaults.put(aid, componentName);
                        }
                        else {
                            Log.e(TAG, "Could not unflatten component string " + component);
                        }
                    }
                    if (eventType == XmlPullParser.END_TAG && "services".equals(tagName)) {
                        // Done with current map
                        userServices = null;
                    }
                    eventType = parser.next();
                    tagName = parser.getName();
                } while (eventType != XmlPullParser.END_DOCUMENT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading AID defaults", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {
                }
            }
        }
    }

    void writeDefaultsToPersistentFile() {
        FileOutputStream fos = null;
        try {
            fos = mAidDefaultsFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            synchronized (mLock) {
                for (int i = 0; i < mUserServices.size(); i++) {
                    final UserServices userServices = mUserServices.valueAt(i);
                    out.startTag(null, "services");
                    out.attribute(null, "userId", Integer.toString(mUserServices.keyAt(i)));
                    for (Map.Entry<String, ComponentName> aidDefault : userServices.defaults.entrySet()) {
                        out.startTag(null, "aid");
                        out.attribute(null, "value", aidDefault.getKey());
                        out.attribute(null, "defaultComponent",
                                aidDefault.getValue().flattenToShortString());
                        out.endTag(null, "aid");
                    }
                    out.endTag(null, "services");
                }
            }
            out.endDocument();
            mAidDefaultsFile.finishWrite(fos);
        } catch (java.io.IOException e) {
           if (fos != null) {
               mAidDefaultsFile.failWrite(fos);
           }
        }
    }
}
