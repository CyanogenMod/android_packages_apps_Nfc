package com.android.nfc.cardemulation;

import android.util.Log;
import android.util.SparseArray;

import com.android.nfc.NfcService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AidRoutingManager {
    static final String TAG = "AidRoutingManager";

    // TODO locking, but this class will likely
    // need to be called with lock held.

    // mAidRoutingTable contains the current routing table. The index is the route ID.
    // The route can include routes to a eSE/UICC.
    final SparseArray<Set<String>> mAidRoutingTable = new SparseArray<Set<String>>();

    // Easy look-up what the route is for a certain AID
    final HashMap<String, Integer> mRouteForAid = new HashMap<String, Integer>();

    // Whether the routing table is dirty
    boolean mDirty;

    public AidRoutingManager() {

    }

    public boolean aidsRoutedToHost() {
        Set<String> aidsToHost = mAidRoutingTable.get(0);
        return aidsToHost != null && aidsToHost.size() > 0;
    }

    public Set<String> getRoutedAids() {
        // TODO maybe just keep mRoutedAids for speed
        Set<String> routedAids = new HashSet<String>();
        for (Map.Entry<String, Integer> aidEntry : mRouteForAid.entrySet()) {
            routedAids.add(aidEntry.getKey());
        }
        return routedAids;
    }

    public boolean setRouteForAid(String aid, int route) {
        int currentRoute = getRouteForAid(aid);
        if (route == currentRoute) return true;

        if (currentRoute != -1) {
            // Remove current routing
            removeAid(aid);
        }
        Set<String> aids = mAidRoutingTable.get(route);
        if (aids == null) {
           aids = new HashSet<String>();
           mAidRoutingTable.put(route, aids);
        }
        aids.add(aid);
        mRouteForAid.put(aid, route);
        NfcService.getInstance().routeAids(aid, route);

        mDirty = true;
        return true;
    }

    /**
     * This notifies that the AID routing table in the controller
     * has been cleared (usually due to NFC being turned off).
     */
    public void onNfccRoutingTableCleared() {
        // The routing table in the controller was cleared
        // To stay in sync, clear our own tables.
        mAidRoutingTable.clear();
        mRouteForAid.clear();
    }

    public boolean removeAid(String aid) {
        Integer route = mRouteForAid.get(aid);
        if (route == null) {
           Log.e(TAG, "removeAid(): No existing route for " + aid);
           return false;
        }
        Set<String> aids = mAidRoutingTable.get(route);
        if (aids == null) return false;
        aids.remove(aid);
        mRouteForAid.remove(aid);
        NfcService.getInstance().unrouteAids(aid);
        mDirty = true;
        return true;
    }

    public void commitRouting() {
        if (mDirty) {
            NfcService.getInstance().commitRouting();
            mDirty = false;
        } else {
            Log.d(TAG, "Not committing routing because table not dirty.");
        }
    }

    int getRouteForAid(String aid) {
        Integer route = mRouteForAid.get(aid);
        return route == null ? -1 : route;
    }
}
