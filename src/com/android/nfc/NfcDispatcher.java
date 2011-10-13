/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.nfc;

import com.android.nfc.RegisteredComponentCache.ComponentInfo;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.RemoteException;
import android.util.Log;

import java.nio.charset.Charsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dispatch of NFC events to start activities
 */
public class NfcDispatcher {
    private static final boolean DBG = NfcService.DBG;
    private static final String TAG = NfcService.TAG;

    private final Context mContext;
    private final IActivityManager mIActivityManager;
    private final RegisteredComponentCache mTechListFilters;

    private PackageManager mPackageManager;

    // Locked on this
    private PendingIntent mOverrideIntent;
    private IntentFilter[] mOverrideFilters;
    private String[][] mOverrideTechLists;

    public NfcDispatcher(Context context, P2pLinkManager p2pManager) {
        mContext = context;
        mIActivityManager = ActivityManagerNative.getDefault();
        mTechListFilters = new RegisteredComponentCache(mContext,
                NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED);
        mPackageManager = context.getPackageManager();
    }

    public synchronized void setForegroundDispatch(PendingIntent intent,
            IntentFilter[] filters, String[][] techLists) {
        if (DBG) Log.d(TAG, "Set Foreground Dispatch");
        mOverrideIntent = intent;
        mOverrideFilters = filters;
        mOverrideTechLists = techLists;
    }

    /** Returns false if no activities were found to dispatch to */
    public boolean dispatchTag(Tag tag, NdefMessage[] msgs) {
        if (DBG) {
            Log.d(TAG, "Dispatching tag");
            Log.d(TAG, tag.toString());
        }

        IntentFilter[] overrideFilters;
        PendingIntent overrideIntent;
        String[][] overrideTechLists;
        synchronized (this) {
            overrideFilters = mOverrideFilters;
            overrideIntent = mOverrideIntent;
            overrideTechLists = mOverrideTechLists;
        }

        // First look for dispatch overrides
        if (overrideIntent != null) {
            if (DBG) Log.d(TAG, "Attempting to dispatch tag with override");
            try {
                if (dispatchTagInternal(tag, msgs, overrideIntent, overrideFilters,
                        overrideTechLists)) {
                    if (DBG) Log.d(TAG, "Dispatched to override");
                    return true;
                }
                Log.w(TAG, "Dispatch override registered, but no filters matched");
            } catch (CanceledException e) {
                Log.w(TAG, "Dispatch overrides pending intent was canceled");
                synchronized (this) {
                    mOverrideFilters = null;
                    mOverrideIntent = null;
                    mOverrideTechLists = null;
                }
            }
        }

        // Try normal dispatch.
        try {
            return dispatchTagInternal(tag, msgs, null, null, null);
        } catch (CanceledException e) {
            Log.e(TAG, "CanceledException unexpected here", e);
            return false;
        }
    }

    private Intent buildTagIntent(Tag tag, NdefMessage[] msgs, String action) {
        Intent intent = new Intent(action);
        intent.putExtra(NfcAdapter.EXTRA_TAG, tag);
        intent.putExtra(NfcAdapter.EXTRA_ID, tag.getId());
        intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, msgs);
        return intent;
    }

    /** This method places the launched activity in a (single) NFC
     *  root task. We use NfcRootActivity as the root of the task,
     *  which launches the passed-in intent as soon as it's created.
     */
    private boolean startRootActivity(Intent intent) {
        Intent rootIntent = new Intent(mContext, NfcRootActivity.class);
        rootIntent.putExtra(NfcRootActivity.EXTRA_LAUNCH_INTENT, intent);
        rootIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Ideally we'd have used startActivityForResult() to determine whether the
        // NfcRootActivity was able to launch the intent, but startActivityForResult()
        // is not available on Context. Instead, we query the PackageManager beforehand
        // to determine if there is an Activity to handle this intent, and base the
        // result of off that.
        List<ResolveInfo> activities = mPackageManager.queryIntentActivities(intent, 0);
        // Try to start the activity regardless of the result.
        mContext.startActivity(rootIntent);
        if (activities.size() > 0) {
            return true;
        } else {
            return false;
        }
    }

    // Dispatch to either an override pending intent or a standard startActivity()
    private boolean dispatchTagInternal(Tag tag, NdefMessage[] msgs,
            PendingIntent overrideIntent, IntentFilter[] overrideFilters,
            String[][] overrideTechLists)
            throws CanceledException{
        Intent intent;

        //
        // Try the NDEF content specific dispatch
        //
        if (msgs != null && msgs.length > 0) {
            NdefMessage msg = msgs[0];
            NdefRecord[] records = msg.getRecords();
            if (records.length > 0) {
                // Found valid NDEF data, try to dispatch that first
                NdefRecord record = records[0];

                intent = buildTagIntent(tag, msgs, NfcAdapter.ACTION_NDEF_DISCOVERED);
                if (setTypeOrDataFromNdef(intent, record)) {
                    // The record contains filterable data, try to start a matching activity
                    if (startDispatchActivity(intent, overrideIntent, overrideFilters,
                            overrideTechLists, records)) {
                        // If an activity is found then skip further dispatching
                        return true;
                    } else {
                        if (DBG) Log.d(TAG, "No activities for NDEF handling of " + intent);
                    }
                }
            }
        }

        //
        // Try the technology specific dispatch
        //
        String[] tagTechs = tag.getTechList();
        Arrays.sort(tagTechs);

        if (overrideIntent != null) {
            // There are dispatch overrides in place
            if (overrideTechLists != null) {
                for (String[] filterTechs : overrideTechLists) {
                    if (filterMatch(tagTechs, filterTechs)) {
                        // An override matched, send it to the foreground activity.
                        intent = buildTagIntent(tag, msgs,
                                NfcAdapter.ACTION_TECH_DISCOVERED);
                        overrideIntent.send(mContext, Activity.RESULT_OK, intent);
                        return true;
                    }
                }
            }
        } else {
            // Standard tech dispatch path
            ArrayList<ResolveInfo> matches = new ArrayList<ResolveInfo>();
            ArrayList<ComponentInfo> registered = mTechListFilters.getComponents();

            // Check each registered activity to see if it matches
            for (ComponentInfo info : registered) {
                // Don't allow wild card matching
                if (filterMatch(tagTechs, info.techs) &&
                        isComponentEnabled(mPackageManager, info.resolveInfo)) {
                    // Add the activity as a match if it's not already in the list
                    if (!matches.contains(info.resolveInfo)) {
                        matches.add(info.resolveInfo);
                    }
                }
            }

            if (matches.size() == 1) {
                // Single match, launch directly
                intent = buildTagIntent(tag, msgs, NfcAdapter.ACTION_TECH_DISCOVERED);
                ResolveInfo info = matches.get(0);
                intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                if (startRootActivity(intent)) {
                    return true;
                }
            } else if (matches.size() > 1) {
                // Multiple matches, show a custom activity chooser dialog
                intent = new Intent(mContext, TechListChooserActivity.class);
                intent.putExtra(Intent.EXTRA_INTENT,
                        buildTagIntent(tag, msgs, NfcAdapter.ACTION_TECH_DISCOVERED));
                intent.putParcelableArrayListExtra(TechListChooserActivity.EXTRA_RESOLVE_INFOS,
                        matches);
                if (startRootActivity(intent)) {
                    return true;
                }
            } else {
                // No matches, move on
                if (DBG) Log.w(TAG, "No activities for technology handling");
            }
        }

        //
        // Try the generic intent
        //
        intent = buildTagIntent(tag, msgs, NfcAdapter.ACTION_TAG_DISCOVERED);
        if (startDispatchActivity(intent, overrideIntent, overrideFilters, overrideTechLists,
                null)) {
            return true;
        } else {
            Log.e(TAG, "No tag fallback activity found for " + intent);
            return false;
        }
    }

    /* Starts the package main activity if it's already installed, or takes you to its
     * market page if not.
     * returns whether an activity was started.
     */
    private boolean startActivityOrMarket(String packageName) {
        Intent intent = mPackageManager.getLaunchIntentForPackage(packageName);
        if (intent != null) {
            return (startRootActivity(intent));
        } else {
            // Find the package in Market:
            Intent market = getAppSearchIntent(packageName);
            return(startRootActivity(market));
        }
    }

    private boolean startDispatchActivity(Intent intent, PendingIntent overrideIntent,
            IntentFilter[] overrideFilters, String[][] overrideTechLists, NdefRecord[] records)
            throws CanceledException {
        if (overrideIntent != null) {
            boolean found = false;
            if (overrideFilters == null && overrideTechLists == null) {
                // No filters means to always dispatch regardless of match
                found = true;
            } else if (overrideFilters != null) {
                for (IntentFilter filter : overrideFilters) {
                    if (filter.match(mContext.getContentResolver(), intent, false, TAG) >= 0) {
                        found = true;
                        break;
                    }
                }
            }

            if (found) {
                Log.i(TAG, "Dispatching to override intent " + overrideIntent);
                overrideIntent.send(mContext, Activity.RESULT_OK, intent);
                return true;
            } else {
                return false;
            }
        } else {
            resumeAppSwitches();
            if (records != null) {
                String firstPackage = null;
                for (NdefRecord record : records) {
                    if (record.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE) {
                        if (Arrays.equals(record.getType(), NdefRecord.RTD_ANDROID_APP)) {
                            String pkg = new String(record.getPayload(), Charsets.US_ASCII);
                            if (firstPackage == null) {
                                firstPackage = pkg;
                            }
                            intent.setPackage(pkg);
                            if (startRootActivity(intent)) {
                                return true;
                            }
                        }
                    }
                }
                if (firstPackage != null) {
                    // Found an Android package, but could not handle ndef intent.
                    // If the application is installed, call its main activity,
                    // or otherwise go to Market.
                    if (startActivityOrMarket(firstPackage)) {
                        return true;
                    }
                }
            }
            return(startRootActivity(intent));
        }
    }

    /**
     * Tells the ActivityManager to resume allowing app switches.
     *
     * If the current app called stopAppSwitches() then our startActivity() can
     * be delayed for several seconds. This happens with the default home
     * screen.  As a system service we can override this behavior with
     * resumeAppSwitches().
    */
    void resumeAppSwitches() {
        try {
            mIActivityManager.resumeAppSwitches();
        } catch (RemoteException e) { }
    }

    /** Returns true if the tech list filter matches the techs on the tag */
    private boolean filterMatch(String[] tagTechs, String[] filterTechs) {
        if (filterTechs == null || filterTechs.length == 0) return false;

        for (String tech : filterTechs) {
            if (Arrays.binarySearch(tagTechs, tech) < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean setTypeOrDataFromNdef(Intent intent, NdefRecord record) {
        short tnf = record.getTnf();
        byte[] type = record.getType();
        try {
            switch (tnf) {
                case NdefRecord.TNF_MIME_MEDIA: {
                    intent.setType(new String(type, Charsets.US_ASCII));
                    return true;
                }

                case NdefRecord.TNF_ABSOLUTE_URI: {
                    intent.setData(Uri.parse(new String(type, Charsets.UTF_8)));
                    return true;
                }

                case NdefRecord.TNF_WELL_KNOWN: {
                    byte[] payload = record.getPayload();
                    if (payload == null || payload.length == 0) return false;
                    if (Arrays.equals(type, NdefRecord.RTD_TEXT)) {
                        intent.setType("text/plain");
                        return true;
                    } else if (Arrays.equals(type, NdefRecord.RTD_SMART_POSTER)) {
                        // Parse the smart poster looking for the URI
                        try {
                            NdefMessage msg = new NdefMessage(record.getPayload());
                            for (NdefRecord subRecord : msg.getRecords()) {
                                short subTnf = subRecord.getTnf();
                                if (subTnf == NdefRecord.TNF_WELL_KNOWN
                                        && Arrays.equals(subRecord.getType(),
                                                NdefRecord.RTD_URI)) {
                                    intent.setData(NdefRecord.parseWellKnownUriRecord(subRecord));
                                    return true;
                                } else if (subTnf == NdefRecord.TNF_ABSOLUTE_URI) {
                                    intent.setData(Uri.parse(new String(subRecord.getType(),
                                            Charsets.UTF_8)));
                                    return true;
                                }
                            }
                        } catch (FormatException e) {
                            return false;
                        }
                    } else if (Arrays.equals(type, NdefRecord.RTD_URI)) {
                        intent.setData(NdefRecord.parseWellKnownUriRecord(record));
                        return true;
                    }
                    return false;
                }

                case NdefRecord.TNF_EXTERNAL_TYPE: {
                    intent.setData(Uri.parse("vnd.android.nfc://ext/" +
                            new String(record.getType(), Charsets.US_ASCII)));
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "failed to parse record", e);
            return false;
        }
    }

    /**
     * Returns an intent that can be used to find an application not currently
     * installed on the device.
     */
    private static Intent getAppSearchIntent(String pkg) {
        Intent market = new Intent(Intent.ACTION_VIEW);
        market.setData(Uri.parse("market://details?id=" + pkg));
        return market;
    }

    private static boolean isComponentEnabled(PackageManager pm, ResolveInfo info) {
        boolean enabled = false;
        ComponentName compname = new ComponentName(
                info.activityInfo.packageName, info.activityInfo.name);
        try {
            // Note that getActivityInfo() will internally call
            // isEnabledLP() to determine whether the component
            // enabled. If it's not, null is returned.
            if (pm.getActivityInfo(compname,0) != null) {
                enabled = true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            enabled = false;
        }
        if (!enabled) {
            Log.d(TAG, "Component not enabled: " + compname);
        }
        return enabled;
    }
}
