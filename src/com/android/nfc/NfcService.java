/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.internal.nfc.LlcpServiceSocket;
import com.android.internal.nfc.LlcpSocket;
import com.android.nfc.RegisteredComponentCache.ComponentInfo;
import com.android.nfc.ndefpush.NdefPushClient;
import com.android.nfc.ndefpush.NdefPushServer;
import com.android.nfc3.R;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.Application;
import android.app.IActivityManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.nfc.ApduList;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.ILlcpConnectionlessSocket;
import android.nfc.ILlcpServiceSocket;
import android.nfc.ILlcpSocket;
import android.nfc.INfcAdapter;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcTag;
import android.nfc.IP2pInitiator;
import android.nfc.IP2pTarget;
import android.nfc.LlcpPacket;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.TransceiveResult;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class NfcService extends Application {
    private static final String ACTION_MASTER_CLEAR_NOTIFICATION = "android.intent.action.MASTER_CLEAR_NOTIFICATION";

    static final boolean DBG = false;

    private static final String MY_TAG_FILE_NAME = "mytag";
    private static final String TEAR_DOWN_SCRIPTS_FILE_NAME = "teardowns";

    static {
        System.loadLibrary("nfc_jni");
    }

    /**
     * NFC Forum "URI Record Type Definition"
     *
     * This is a mapping of "URI Identifier Codes" to URI string prefixes,
     * per section 3.2.2 of the NFC Forum URI Record Type Definition document.
     */
    private static final String[] URI_PREFIX_MAP = new String[] {
            "", // 0x00
            "http://www.", // 0x01
            "https://www.", // 0x02
            "http://", // 0x03
            "https://", // 0x04
            "tel:", // 0x05
            "mailto:", // 0x06
            "ftp://anonymous:anonymous@", // 0x07
            "ftp://ftp.", // 0x08
            "ftps://", // 0x09
            "sftp://", // 0x0A
            "smb://", // 0x0B
            "nfs://", // 0x0C
            "ftp://", // 0x0D
            "dav://", // 0x0E
            "news:", // 0x0F
            "telnet://", // 0x10
            "imap:", // 0x11
            "rtsp://", // 0x12
            "urn:", // 0x13
            "pop:", // 0x14
            "sip:", // 0x15
            "sips:", // 0x16
            "tftp:", // 0x17
            "btspp://", // 0x18
            "btl2cap://", // 0x19
            "btgoep://", // 0x1A
            "tcpobex://", // 0x1B
            "irdaobex://", // 0x1C
            "file://", // 0x1D
            "urn:epc:id:", // 0x1E
            "urn:epc:tag:", // 0x1F
            "urn:epc:pat:", // 0x20
            "urn:epc:raw:", // 0x21
            "urn:epc:", // 0x22
    };

    public static final String SERVICE_NAME = "nfc";

    private static final String TAG = "NfcService";

    private static final String NFC_PERM = android.Manifest.permission.NFC;
    private static final String NFC_PERM_ERROR = "NFC permission required";
    private static final String ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ADMIN_PERM_ERROR = "WRITE_SECURE_SETTINGS permission required";
    // STOPSHIP: This needs to be updated to the line below
//    private static final String NFCEE_ADMIN_PERM = "com.android.nfc.permission.NFCEE_ADMIN";
    private static final String NFCEE_ADMIN_PERM = NFC_PERM;
    private static final String NFCEE_ADMIN_PERM_ERROR = "NFCEE_ADMIN permission required";

    private static final String PREF = "NfcServicePrefs";

    private static final String PREF_NFC_ON = "nfc_on";
    private static final boolean NFC_ON_DEFAULT = true;

    private static final String PREF_LLCP_LTO = "llcp_lto";
    private static final int LLCP_LTO_DEFAULT = 150;
    private static final int LLCP_LTO_MAX = 255;

    /** Maximum Information Unit */
    private static final String PREF_LLCP_MIU = "llcp_miu";
    private static final int LLCP_MIU_DEFAULT = 128;
    private static final int LLCP_MIU_MAX = 2176;

    /** Well Known Service List */
    private static final String PREF_LLCP_WKS = "llcp_wks";
    private static final int LLCP_WKS_DEFAULT = 1;
    private static final int LLCP_WKS_MAX = 15;

    private static final String PREF_LLCP_OPT = "llcp_opt";
    private static final int LLCP_OPT_DEFAULT = 0;
    private static final int LLCP_OPT_MAX = 3;

    private static final String PREF_DISCOVERY_A = "discovery_a";
    private static final boolean DISCOVERY_A_DEFAULT = true;

    private static final String PREF_DISCOVERY_B = "discovery_b";
    private static final boolean DISCOVERY_B_DEFAULT = true;

    private static final String PREF_DISCOVERY_F = "discovery_f";
    private static final boolean DISCOVERY_F_DEFAULT = true;

    private static final String PREF_DISCOVERY_15693 = "discovery_15693";
    private static final boolean DISCOVERY_15693_DEFAULT = true;

    private static final String PREF_DISCOVERY_NFCIP = "discovery_nfcip";
    private static final boolean DISCOVERY_NFCIP_DEFAULT = true;

    /** NFC Reader Discovery mode for enableDiscovery() */
    private static final int DISCOVERY_MODE_READER = 0;

    private static final int PROPERTY_LLCP_LTO = 0;
    private static final String PROPERTY_LLCP_LTO_VALUE = "llcp.lto";
    private static final int PROPERTY_LLCP_MIU = 1;
    private static final String PROPERTY_LLCP_MIU_VALUE = "llcp.miu";
    private static final int PROPERTY_LLCP_WKS = 2;
    private static final String PROPERTY_LLCP_WKS_VALUE = "llcp.wks";
    private static final int PROPERTY_LLCP_OPT = 3;
    private static final String PROPERTY_LLCP_OPT_VALUE = "llcp.opt";
    private static final int PROPERTY_NFC_DISCOVERY_A = 4;
    private static final String PROPERTY_NFC_DISCOVERY_A_VALUE = "discovery.iso14443A";
    private static final int PROPERTY_NFC_DISCOVERY_B = 5;
    private static final String PROPERTY_NFC_DISCOVERY_B_VALUE = "discovery.iso14443B";
    private static final int PROPERTY_NFC_DISCOVERY_F = 6;
    private static final String PROPERTY_NFC_DISCOVERY_F_VALUE = "discovery.felica";
    private static final int PROPERTY_NFC_DISCOVERY_15693 = 7;
    private static final String PROPERTY_NFC_DISCOVERY_15693_VALUE = "discovery.iso15693";
    private static final int PROPERTY_NFC_DISCOVERY_NFCIP = 8;
    private static final String PROPERTY_NFC_DISCOVERY_NFCIP_VALUE = "discovery.nfcip";

    static final int MSG_NDEF_TAG = 0;
    static final int MSG_CARD_EMULATION = 1;
    static final int MSG_LLCP_LINK_ACTIVATION = 2;
    static final int MSG_LLCP_LINK_DEACTIVATED = 3;
    static final int MSG_TARGET_DESELECTED = 4;
    static final int MSG_SHOW_MY_TAG_ICON = 5;
    static final int MSG_HIDE_MY_TAG_ICON = 6;
    static final int MSG_MOCK_NDEF = 7;
    static final int MSG_SE_FIELD_ACTIVATED = 8;
    static final int MSG_SE_FIELD_DEACTIVATED = 9;

    // Copied from com.android.nfc_extras to avoid library dependency
    // Must keep in sync with com.android.nfc_extras
    static final int ROUTE_OFF = 1;
    static final int ROUTE_ON_WHEN_SCREEN_ON = 2;
    public static final String ACTION_RF_FIELD_ON_DETECTED =
        "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED";
    public static final String ACTION_RF_FIELD_OFF_DETECTED =
        "com.android.nfc_extras.action.RF_FIELD_OFF_DETECTED";
    public static final String ACTION_AID_SELECTED =
        "com.android.nfc_extras.action.AID_SELECTED";
    public static final String EXTRA_AID = "com.android.nfc_extras.extra.AID";

    // Locked on mNfcAdapter
    PendingIntent mDispatchOverrideIntent;
    IntentFilter[] mDispatchOverrideFilters;
    String[][] mDispatchOverrideTechLists;

    // TODO: none of these appear to be synchronized but are
    // read/written from different threads (notably Binder threads)...
    private int mGeneratedSocketHandle = 0;
    private volatile boolean mIsNfcEnabled = false;
    private boolean mIsDiscoveryOn = false;

    // NFC Execution Environment
    // fields below are protected by this
    private static final int SECURE_ELEMENT_ID = 11259375;  //TODO: remove hard-coded value
    private NativeNfcSecureElement mSecureElement;
    private OpenSecureElement mOpenEe;  // null when EE closed
    private int mEeRoutingState;  // contactless interface routing

    // fields below are used in multiple threads and protected by synchronized(this)
    private final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    private final HashMap<Integer, Object> mSocketMap = new HashMap<Integer, Object>();
    private boolean mScreenOn;

    // fields below are final after onCreate()
    Context mContext;
    private NativeNfcManager mManager;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private PowerManager.WakeLock mWakeLock;
    private IActivityManager mIActivityManager;
    NdefPushClient mNdefPushClient;
    NdefPushServer mNdefPushServer;
    RegisteredComponentCache mTechListFilters;

    private static NfcService sService;

    private HashMap<String, ApduList> mTearDownApdus = new HashMap<String, ApduList>();

    public static void enforceAdminPerm(Context context) {
        int admin = context.checkCallingOrSelfPermission(ADMIN_PERM);
        int nfcee = context.checkCallingOrSelfPermission(NFCEE_ADMIN_PERM);
        if (admin != PackageManager.PERMISSION_GRANTED
                && nfcee != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(ADMIN_PERM_ERROR);
        }
    }

    public static void enforceNfceeAdminPerm(Context context) {
        context.enforceCallingOrSelfPermission(NFCEE_ADMIN_PERM, NFCEE_ADMIN_PERM_ERROR);
    }

    public static NfcService getInstance() {
        return sService;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Starting NFC service");

        sService = this;

        mContext = this;
        mManager = new NativeNfcManager(mContext, this);
        mManager.initializeNativeStructure();

        mNdefPushClient = new NdefPushClient(this);
        mNdefPushServer = new NdefPushServer();

        mTechListFilters = new RegisteredComponentCache(this,
                NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED);

        mSecureElement = new NativeNfcSecureElement();

        mPrefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();

        mIsNfcEnabled = false;  // real preference read later

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mScreenOn = pm.isScreenOn();
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcService");

        mIActivityManager = ActivityManagerNative.getDefault();

        readTearDownApdus();

        ServiceManager.addService(SERVICE_NAME, mNfcAdapter);

        IntentFilter filter = new IntentFilter(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ACTION_MASTER_CLEAR_NOTIFICATION);
        mContext.registerReceiver(mReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");

        mContext.registerReceiver(mReceiver, filter);

        Thread t = new Thread() {
            @Override
            public void run() {
                boolean nfc_on = mPrefs.getBoolean(PREF_NFC_ON, NFC_ON_DEFAULT);
                if (nfc_on) {
                    _enable(false);
                }
            }
        };
        t.start();
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // NFC application is persistent, it should not be destroyed by framework
        Log.wtf(TAG, "NFC service is under attack!");
    }

    private final INfcAdapter.Stub mNfcAdapter = new INfcAdapter.Stub() {
        /** Protected by "this" */
        NdefMessage mLocalMessage = null;

        @Override
        public boolean enable() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);

            boolean isSuccess = false;
            boolean previouslyEnabled = isEnabled();
            if (!previouslyEnabled) {
                reset();
                isSuccess = _enable(previouslyEnabled);
            }
            return isSuccess;
        }

        @Override
        public boolean disable() throws RemoteException {
            boolean isSuccess = false;
            NfcService.enforceAdminPerm(mContext);
            boolean previouslyEnabled = isEnabled();
            if (DBG) Log.d(TAG, "Disabling NFC.  previous=" + previouslyEnabled);

            if (previouslyEnabled) {
                /* tear down the my tag server */
                mNdefPushServer.stop();

                // Stop watchdog if tag present
                // A convenient way to stop the watchdog properly consists of
                // disconnecting the tag. The polling loop shall be stopped before
                // to avoid the tag being discovered again.
                mIsDiscoveryOn = false;
                applyRouting();
                maybeDisconnectTarget();

                isSuccess = mManager.deinitialize();
                if (DBG) Log.d(TAG, "NFC success of deinitialize = " + isSuccess);
                if (isSuccess) {
                    mIsNfcEnabled = false;
                    // Clear out any old dispatch overrides and NDEF push message
                    synchronized (this) {
                        mDispatchOverrideFilters = null;
                        mDispatchOverrideIntent = null;
                    }
                    mNdefPushClient.setForegroundMessage(null);
                }
            }

            updateNfcOnSetting(previouslyEnabled);

            return isSuccess;
        }

        @Override
        public void enableForegroundDispatch(ComponentName activity, PendingIntent intent,
                IntentFilter[] filters, TechListParcel techListsParcel) {
            // Permission check
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Argument validation
            if (activity == null || intent == null) {
                throw new IllegalArgumentException();
            }

            // Validate the IntentFilters
            if (filters != null) {
                if (filters.length == 0) {
                    filters = null;
                } else {
                    for (IntentFilter filter : filters) {
                        if (filter == null) {
                            throw new IllegalArgumentException("null IntentFilter");
                        }
                    }
                }
            }

            // Validate the tech lists
            String[][] techLists = null;
            if (techListsParcel != null) {
                techLists = techListsParcel.getTechLists();
            }

            synchronized (this) {
                if (mDispatchOverrideIntent != null) {
                    Log.e(TAG, "Replacing active dispatch overrides");
                }
                mDispatchOverrideIntent = intent;
                mDispatchOverrideFilters = filters;
                mDispatchOverrideTechLists = techLists;
            }
        }

        @Override
        public void disableForegroundDispatch(ComponentName activity) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            synchronized (this) {
                if (mDispatchOverrideIntent == null) {
                    Log.e(TAG, "No active foreground dispatching");
                }
                mDispatchOverrideIntent = null;
                mDispatchOverrideFilters = null;
            }
        }

        @Override
        public void enableForegroundNdefPush(ComponentName activity, NdefMessage msg) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            if (activity == null || msg == null) {
                throw new IllegalArgumentException();
            }
            if (mNdefPushClient.setForegroundMessage(msg)) {
                Log.e(TAG, "Replacing active NDEF push message");
            }
        }

        @Override
        public void disableForegroundNdefPush(ComponentName activity) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            if (!mNdefPushClient.setForegroundMessage(null)) {
                Log.e(TAG, "No active foreground NDEF push message");
            }
        }

        @Override
        public int createLlcpConnectionlessSocket(int sap) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* Check SAP is not already used */

            /* Store the socket handle */
            int sockeHandle = mGeneratedSocketHandle;
            NativeLlcpConnectionlessSocket socket;

            socket = mManager.doCreateLlcpConnectionlessSocket(sap);
            if (socket != null) {
                synchronized(NfcService.this) {
                    /* update socket handle generation */
                    mGeneratedSocketHandle++;

                    /* Add the socket into the socket map */
                    mSocketMap.put(mGeneratedSocketHandle, socket);
                   return mGeneratedSocketHandle;
                }
            } else {
                /* Get Error Status */
                int errorStatus = mManager.doGetLastError();

                switch (errorStatus) {
                    case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                        return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                    case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                        return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                    default:
                        return ErrorCodes.ERROR_SOCKET_CREATION;
                }
            }
        }

        @Override
        public int createLlcpServiceSocket(int sap, String sn, int miu, int rw, int linearBufferLength)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            NativeLlcpServiceSocket socket;

            socket = mManager.doCreateLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
            if (socket != null) {
                synchronized(NfcService.this) {
                    /* update socket handle generation */
                    mGeneratedSocketHandle++;

                    /* Add the socket into the socket map */
                    mSocketMap.put(mGeneratedSocketHandle, socket);
                   return mGeneratedSocketHandle;
                }
            } else {
                /* Get Error Status */
                int errorStatus = mManager.doGetLastError();

                switch (errorStatus) {
                    case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                        return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                    case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                        return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                    default:
                        return ErrorCodes.ERROR_SOCKET_CREATION;
                }
            }
        }

        @Override
        public int createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (DBG) Log.d(TAG, "creating llcp socket");
            NativeLlcpSocket socket;

            socket = mManager.doCreateLlcpSocket(sap, miu, rw, linearBufferLength);

            if (socket != null) {
                synchronized(NfcService.this) {
                    /* update socket handle generation */
                    mGeneratedSocketHandle++;

                    /* Add the socket into the socket map */
                    mSocketMap.put(mGeneratedSocketHandle, socket);
                   return mGeneratedSocketHandle;
                }
            } else {
                /* Get Error Status */
                int errorStatus = mManager.doGetLastError();

                Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(errorStatus));

                switch (errorStatus) {
                    case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                        return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                    case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                        return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                    default:
                        return ErrorCodes.ERROR_SOCKET_CREATION;
                }
            }
        }

        @Override
        public ILlcpConnectionlessSocket getLlcpConnectionlessInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpConnectionlessSocketService;
        }

        @Override
        public ILlcpSocket getLlcpInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpSocket;
        }

        @Override
        public ILlcpServiceSocket getLlcpServiceInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpServerSocketService;
        }

        @Override
        public INfcTag getNfcTagInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mNfcTagService;
        }

        @Override
        public IP2pInitiator getP2pInitiatorInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mP2pInitiatorService;
        }

        @Override
        public IP2pTarget getP2pTargetInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mP2pTargetService;
        }

        @Override
        public INfcAdapterExtras getNfcAdapterExtrasInterface() {
            NfcService.enforceNfceeAdminPerm(mContext);
            return mExtrasService;
        }

        @Override
        public String getProperties(String param) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            if (param == null) {
                return null;
            }

            if (param.equals(PROPERTY_LLCP_LTO_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_LTO, LLCP_LTO_DEFAULT));
            } else if (param.equals(PROPERTY_LLCP_MIU_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_MIU, LLCP_MIU_DEFAULT));
            } else if (param.equals(PROPERTY_LLCP_WKS_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_WKS, LLCP_WKS_DEFAULT));
            } else if (param.equals(PROPERTY_LLCP_OPT_VALUE)) {
                return Integer.toString(mPrefs.getInt(PREF_LLCP_OPT, LLCP_OPT_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_A_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_A, DISCOVERY_A_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_B_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_B, DISCOVERY_B_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_F_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_F, DISCOVERY_F_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_NFCIP_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_NFCIP, DISCOVERY_NFCIP_DEFAULT));
            } else if (param.equals(PROPERTY_NFC_DISCOVERY_15693_VALUE)) {
                return Boolean.toString(mPrefs.getBoolean(PREF_DISCOVERY_15693, DISCOVERY_15693_DEFAULT));
            } else {
                return "Unknown property";
            }
        }

        @Override
        public boolean isEnabled() throws RemoteException {
            return mIsNfcEnabled;
        }

        @Override
        public int setProperties(String param, String value) throws RemoteException {
            NfcService.enforceAdminPerm(mContext);

            if (isEnabled()) {
                return ErrorCodes.ERROR_NFC_ON;
            }

            int val;

            /* Check params validity */
            if (param == null || value == null) {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            if (param.equals(PROPERTY_LLCP_LTO_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if (val > LLCP_LTO_MAX)
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_LTO, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_LTO, val);

            } else if (param.equals(PROPERTY_LLCP_MIU_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if ((val < LLCP_MIU_DEFAULT) || (val > LLCP_MIU_MAX))
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_MIU, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_MIU, val);

            } else if (param.equals(PROPERTY_LLCP_WKS_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if (val > LLCP_WKS_MAX)
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_WKS, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_WKS, val);

            } else if (param.equals(PROPERTY_LLCP_OPT_VALUE)) {
                val = Integer.parseInt(value);

                /* Check params */
                if (val > LLCP_OPT_MAX)
                    return ErrorCodes.ERROR_INVALID_PARAM;

                /* Store value */
                mPrefsEditor.putInt(PREF_LLCP_OPT, val);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_LLCP_OPT, val);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_A_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_A, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_A, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_B_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_B, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_B, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_F_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_F, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_F, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_15693_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_15693, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_15693, b ? 1 : 0);

            } else if (param.equals(PROPERTY_NFC_DISCOVERY_NFCIP_VALUE)) {
                boolean b = Boolean.parseBoolean(value);

                /* Store value */
                mPrefsEditor.putBoolean(PREF_DISCOVERY_NFCIP, b);
                mPrefsEditor.apply();

                /* Update JNI */
                mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_NFCIP, b ? 1 : 0);

            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            return ErrorCodes.SUCCESS;
        }

        @Override
        public NdefMessage localGet() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            synchronized (this) {
                return mLocalMessage;
            }
        }

        @Override
        public void localSet(NdefMessage message) throws RemoteException {
            NfcService.enforceAdminPerm(mContext);

            synchronized (this) {
                mLocalMessage = message;
                Context context = NfcService.this.getApplicationContext();

                // Send a message to the UI thread to show or hide the icon so the requests are
                // serialized and the icon can't get out of sync with reality.
                if (message != null) {
                    FileOutputStream out = null;

                    try {
                        out = context.openFileOutput(MY_TAG_FILE_NAME, Context.MODE_PRIVATE);
                        byte[] bytes = message.toByteArray();
                        if (bytes.length == 0) {
                            Log.w(TAG, "Setting a empty mytag");
                        }

                        out.write(bytes);
                    } catch (IOException e) {
                        Log.e(TAG, "Could not write mytag file", e);
                    } finally {
                        try {
                            if (out != null) {
                                out.flush();
                                out.close();
                            }
                        } catch (IOException e) {
                            // Ignore
                        }
                    }

                    // Only show the icon if NFC is enabled.
                    if (mIsNfcEnabled) {
                        sendMessage(MSG_SHOW_MY_TAG_ICON, null);
                    }
                } else {
                    context.deleteFile(MY_TAG_FILE_NAME);
                    sendMessage(MSG_HIDE_MY_TAG_ICON, null);
                }
            }
        }
    };

    private final ILlcpSocket mLlcpSocket = new ILlcpSocket.Stub() {

        private NativeLlcpSocket findSocket(int nativeHandle) {
            Object socket = NfcService.this.findSocket(nativeHandle);
            if (!(socket instanceof NativeLlcpSocket)) {
                return null;
            }
            return (NativeLlcpSocket) socket;
        }

        @Override
        public int close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                socket.doClose();
                /* Remove the socket closed from the hmap */
                RemoveSocket(nativeHandle);
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int connect(int nativeHandle, int sap) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnect(sap);
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int connectByName(int nativeHandle, String sn) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnectBy(sn);
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int getLocalSap(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        @Override
        public int getLocalSocketMiu(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                return socket.getMiu();
            } else {
                return 0;
            }
        }

        @Override
        public int getLocalSocketRw(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                return socket.getRw();
            } else {
                return 0;
            }
        }

        @Override
        public int getRemoteSocketMiu(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                if (socket.doGetRemoteSocketMiu() != 0) {
                    return socket.doGetRemoteSocketMiu();
                } else {
                    return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
                }
            } else {
                return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
            }
        }

        @Override
        public int getRemoteSocketRw(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                if (socket.doGetRemoteSocketRw() != 0) {
                    return socket.doGetRemoteSocketRw();
                } else {
                    return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
                }
            } else {
                return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
            }
        }

        @Override
        public int receive(int nativeHandle, byte[] receiveBuffer) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                return socket.doReceive(receiveBuffer);
            } else {
                return 0;
            }
        }

        @Override
        public int send(int nativeHandle, byte[] data) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doSend(data);
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }
    };

    private final ILlcpServiceSocket mLlcpServerSocketService = new ILlcpServiceSocket.Stub() {

        private NativeLlcpServiceSocket findSocket(int nativeHandle) {
            Object socket = NfcService.this.findSocket(nativeHandle);
            if (!(socket instanceof NativeLlcpServiceSocket)) {
                return null;
            }
            return (NativeLlcpServiceSocket) socket;
        }

        @Override
        public int accept(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;
            NativeLlcpSocket clientSocket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

                /* find the socket in the hmap */
                socket = findSocket(nativeHandle);
                if (socket != null) {
                    clientSocket = socket.doAccept(socket.getMiu(),
                            socket.getRw(), socket.getLinearBufferLength());
                    if (clientSocket != null) {
                        /* Add the socket into the socket map */
                        synchronized(this) {
                            mGeneratedSocketHandle++;
                            mSocketMap.put(mGeneratedSocketHandle, clientSocket);
                            return mGeneratedSocketHandle;
                        }
                    } else {
                        return ErrorCodes.ERROR_IO;
                    }
                } else {
                    return ErrorCodes.ERROR_IO;
                }
        }

        @Override
        public void close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                socket.doClose();
                synchronized (this) {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);
                }
            }
        }
    };

    private final ILlcpConnectionlessSocket mLlcpConnectionlessSocketService = new ILlcpConnectionlessSocket.Stub() {

        private NativeLlcpConnectionlessSocket findSocket(int nativeHandle) {
            Object socket = NfcService.this.findSocket(nativeHandle);
            if (!(socket instanceof NativeLlcpConnectionlessSocket)) {
                return null;
            }
            return (NativeLlcpConnectionlessSocket) socket;
        }

        @Override
        public void close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                socket.doClose();
                /* Remove the socket closed from the hmap */
                RemoveSocket(nativeHandle);
            }
        }

        @Override
        public int getSap(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        @Override
        public LlcpPacket receiveFrom(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            LlcpPacket packet;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                packet = socket.doReceiveFrom(socket.getLinkMiu());
                if (packet != null) {
                    return packet;
                }
                return null;
            } else {
                return null;
            }
        }

        @Override
        public int sendTo(int nativeHandle, LlcpPacket packet) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doSendTo(packet.getRemoteSap(), packet.getDataBuffer());
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }
    };

    private final INfcTag mNfcTagService = new INfcTag.Stub() {

        @Override
        public int close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                /* Remove the device from the hmap */
                unregisterObject(nativeHandle);
                tag.disconnect();
                return ErrorCodes.SUCCESS;
            }
            /* Restart polling loop for notification */
            applyRouting();
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int connect(int nativeHandle, int technology) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_DISCONNECT;
            }

            // Note that on most tags, all technologies are behind a single
            // handle. This means that the connect at the lower levels
            // will do nothing, as the tag is already connected to that handle.
            if (tag.connect(technology)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_DISCONNECT;
            }
        }

        @Override
        public int reconnect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                if (tag.reconnect()) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_DISCONNECT;
                }
            }
            return ErrorCodes.ERROR_DISCONNECT;
        }

        @Override
        public int[] getTechList(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            NativeNfcTag tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                return tag.getTechList();
            }
            return null;
        }

        @Override
        public byte[] getUid(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            byte[] uid;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                uid = tag.getUid();
                return uid;
            }
            return null;
        }

        @Override
        public boolean isPresent(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return false;
            }

            return tag.isPresent();
        }

        @Override
        public boolean isNdef(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            int[] ndefInfo = new int[2];
            if (tag != null) {
                isSuccess = tag.checkNdef(ndefInfo);
            }
            return isSuccess;
        }

        @Override
        public TransceiveResult transceive(int nativeHandle, byte[] data, boolean raw)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                int[] targetLost = new int[1];
                response = tag.transceive(data, raw, targetLost);
                TransceiveResult transResult = new TransceiveResult(
                        (response != null) ? true : false,
                        (targetLost[0] == 1) ? true : false,
                        response);
                return transResult;
            }
            return null;
        }

        @Override
        public NdefMessage ndefRead(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                byte[] buf = tag.read();
                if (buf == null)
                    return null;

                /* Create an NdefMessage */
                try {
                    return new NdefMessage(buf);
                } catch (FormatException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public int ndefWrite(int nativeHandle, NdefMessage msg) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.write(msg.toByteArray())) {
                return ErrorCodes.SUCCESS;
            }
            else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int getLastError(int nativeHandle) throws RemoteException {
            return(mManager.doGetLastError());
        }

        @Override
        public boolean ndefIsWritable(int nativeHandle) throws RemoteException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int ndefMakeReadOnly(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.makeReadonly()) {
                return ErrorCodes.SUCCESS;
            }
            else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int formatNdef(int nativeHandle, byte[] key) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.formatNdef(key)) {
                return ErrorCodes.SUCCESS;
            }
            else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public void setIsoDepTimeout(int timeout) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            mManager.setIsoDepTimeout(timeout);
        }

        @Override
        public void resetIsoDepTimeout() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            mManager.resetIsoDepTimeout();
        }
    };

    private final IP2pInitiator mP2pInitiatorService = new IP2pInitiator.Stub() {

        @Override
        public byte[] getGeneralBytes(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.getGeneralBytes();
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }

        @Override
        public int getMode(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        @Override
        public byte[] receive(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.doReceive();
                if (buff == null)
                    return null;
                return buff;
            }
            /* Restart polling loop for notification */
            applyRouting();
            return null;
        }

        @Override
        public boolean send(int nativeHandle, byte[] data) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                isSuccess = device.doSend(data);
            }
            return isSuccess;
        }
    };

    private final IP2pTarget mP2pTargetService = new IP2pTarget.Stub() {

        @Override
        public int connect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                if (device.doConnect()) {
                    return ErrorCodes.SUCCESS;
                }
            }
            return ErrorCodes.ERROR_CONNECT;
        }

        @Override
        public boolean disconnect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                if (isSuccess = device.doDisconnect()) {
                    /* remove the device from the hmap */
                    unregisterObject(nativeHandle);
                    /* Restart polling loop for notification */
                    applyRouting();
                }
            }
            return isSuccess;

        }

        @Override
        public byte[] getGeneralBytes(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.getGeneralBytes();
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }

        @Override
        public int getMode(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        @Override
        public byte[] transceive(int nativeHandle, byte[] data)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.doTransceive(data);
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }
    };

    private INfcAdapterExtras mExtrasService = new INfcAdapterExtras.Stub() {
        private Bundle writeNoException() {
            Bundle p = new Bundle();
            p.putInt("e", 0);
            return p;
        }
        private Bundle writeIoException(IOException e) {
            Bundle p = new Bundle();
            p.putInt("e", -1);
            p.putString("m", e.getMessage());
            return p;
        }

        @Override
        public Bundle open(IBinder b) throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);

            Bundle result;
            try {
                _open(b);
                result = writeNoException();
            } catch (IOException e) {
                result = writeIoException(e);
            }
            return result;
        }

        private void _open(IBinder b) throws IOException, RemoteException {
            synchronized(NfcService.this) {
                if (!mIsNfcEnabled) {
                    throw new IOException("NFC adapter is disabled");
                }
                if (mOpenEe != null) {
                    throw new IOException("NFC EE already open");
                }

                int handle = mSecureElement.doOpenSecureElementConnection();
                if (handle == 0) {
                    throw new IOException("NFC EE failed to open");
                }
                mManager.doSetIsoDepTimeout(10000);

                mOpenEe = new OpenSecureElement(getCallingPid(), handle);
                try {
                    b.linkToDeath(mOpenEe, 0);
                } catch (RemoteException e) {
                    mOpenEe.binderDied();
                }
           }
        }

        @Override
        public Bundle close() throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);

            Bundle result;
            try {
                _close();
                result = writeNoException();
            } catch (IOException e) {
                result = writeIoException(e);
            }
            return result;
        }

        void _close() throws IOException, RemoteException {
            // Blocks until a pending open() or transceive() times out.
            //TODO: This is incorrect behavior - the close should interrupt pending
            // operations. However this is not supported by current hardware.

            synchronized(NfcService.this) {
                if (!mIsNfcEnabled) {
                    throw new IOException("NFC adapter is disabled");
                }
                if (mOpenEe == null) {
                    throw new IOException("NFC EE closed");
                }
                if (mOpenEe.pid != -1 && getCallingPid() != mOpenEe.pid) {
                    throw new SecurityException("Wrong PID");
                }

                mManager.doResetIsoDepTimeout();
                mSecureElement.doDisconnect(mOpenEe.handle);
                mOpenEe = null;

                applyRouting();
            }
        }

        @Override
        public Bundle transceive(byte[] in) throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);

            Bundle result;
            byte[] out;
            try {
                out = _transceive(in);
                result = writeNoException();
                result.putByteArray("out", out);
            } catch (IOException e) {
                result = writeIoException(e);
            }
            return result;
        }

        private byte[] _transceive(byte[] data) throws IOException, RemoteException {
            synchronized(NfcService.this) {
                if (!mIsNfcEnabled) {
                    throw new IOException("NFC is not enabled");
                }
                if (mOpenEe == null){
                    throw new IOException("NFC EE is not open");
                }
                if (getCallingPid() != mOpenEe.pid) {
                    throw new SecurityException("Wrong PID");
                }
            }

            return mSecureElement.doTransceive(mOpenEe.handle, data);
        }

        @Override
        public int getCardEmulationRoute() throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);
            return mEeRoutingState;
        }

        @Override
        public void setCardEmulationRoute(int route) throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);
            mEeRoutingState = route;
            applyRouting();
        }

        @Override
        public void registerTearDownApdus(String packageName, ApduList apdu) throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);
            synchronized(NfcService.this) {
                mTearDownApdus.put(packageName, apdu);
                writeTearDownApdusLocked();
            }
        }

        @Override
        public void unregisterTearDownApdus(String packageName) throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);
            synchronized(NfcService.this) {
                mTearDownApdus.remove(packageName);
                writeTearDownApdusLocked();
            }
        }
    };

    /** resources kept while secure element is open */
    private class OpenSecureElement implements IBinder.DeathRecipient {
        public int pid;  // pid that opened SE
        public int handle; // low-level handle
        public OpenSecureElement(int pid, int handle) {
            this.pid = pid;
            this.handle = handle;
        }
        @Override
        public void binderDied() {
            synchronized (NfcService.this) {
                if (DBG) Log.d(TAG, "Tracked app " + pid + " died");
                pid = -1;
                try {
                    mExtrasService.close();
                } catch (RemoteException e) { /* local call never fails */ }
            }
        }
    }

    private boolean _enable(boolean oldEnabledState) {
        applyProperties();

        boolean isSuccess = mManager.initialize();
        if (isSuccess) {
            mIsNfcEnabled = true;
            mIsDiscoveryOn = true;

            /* Start polling loop */
            applyRouting();

            /* bring up the my tag server */
            mNdefPushServer.start();

        } else {
            mIsNfcEnabled = false;
        }

        updateNfcOnSetting(oldEnabledState);

        return isSuccess;
    }

    /** apply NFC discovery and EE routing */
    private synchronized void applyRouting() {
        if (mIsNfcEnabled && mOpenEe == null) {
            if (mScreenOn) {
                if (mEeRoutingState == ROUTE_ON_WHEN_SCREEN_ON) {
                    Log.d(TAG, "NFC-EE routing ON");
                    mManager.doSelectSecureElement(SECURE_ELEMENT_ID);
                } else {
                    Log.d(TAG, "NFC-EE routing OFF");
                    mManager.doDeselectSecureElement(SECURE_ELEMENT_ID);
                }
                if (mIsDiscoveryOn) {
                    Log.d(TAG, "NFC-C discovery ON");
                    mManager.enableDiscovery(DISCOVERY_MODE_READER);
                } else {
                    Log.d(TAG, "NFC-C discovery OFF");
                    mManager.disableDiscovery();
                }
            } else {
                Log.d(TAG, "NFC-EE routing OFF");
                mManager.doDeselectSecureElement(SECURE_ELEMENT_ID);
                Log.d(TAG, "NFC-C discovery OFF");
                mManager.disableDiscovery();
            }
        }
    }

    /** Disconnect any target if present */
    private synchronized void maybeDisconnectTarget() {
        if (mIsNfcEnabled) {
            Iterator<?> iterator = mObjectMap.values().iterator();
            while(iterator.hasNext()) {
                Object object = iterator.next();
                if(object instanceof NativeNfcTag) {
                    // Disconnect from tags
                    NativeNfcTag tag = (NativeNfcTag) object;
                    tag.disconnect();
                }
                else if(object instanceof NativeP2pDevice) {
                    // Disconnect from P2P devices
                    NativeP2pDevice device = (NativeP2pDevice) object;
                    if (device.getMode() == NativeP2pDevice.MODE_P2P_TARGET) {
                        // Remote peer is target, request disconnection
                        device.doDisconnect();
                    }
                    else {
                        // Remote peer is initiator, we cannot disconnect
                        // Just wait for field removal
                    }
                }
                iterator.remove();
            }
        }
    }

    private void readTearDownApdus() {
        FileInputStream input = null;

        try {
            input = openFileInput(TEAR_DOWN_SCRIPTS_FILE_NAME);
            DataInputStream stream = new DataInputStream(input);

            int packagesSize = stream.readInt();

            for (int i = 0 ; i < packagesSize ; i++) {
                String packageName = stream.readUTF();
                ApduList apdu = new ApduList();

                int commandsSize = stream.readInt();

                for (int j = 0 ; j < commandsSize ; j++) {
                    int length = stream.readInt();

                    byte[] cmd = new byte[length];

                    stream.read(cmd);
                    apdu.add(cmd);
                }

                mTearDownApdus.put(packageName, apdu);
            }
        } catch (FileNotFoundException e) {
            // Ignore.
        } catch (IOException e) {
            Log.e(TAG, "Could not read tear down scripts file: ", e);
            deleteFile(TEAR_DOWN_SCRIPTS_FILE_NAME);
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private void writeTearDownApdusLocked() {
        FileOutputStream output = null;
        DataOutputStream stream = null;

        try {
            output = openFileOutput(TEAR_DOWN_SCRIPTS_FILE_NAME, Context.MODE_PRIVATE);
            stream = new DataOutputStream(output);

            stream.writeInt(mTearDownApdus.size());

            for (String packageName : mTearDownApdus.keySet()) {
                stream.writeUTF(packageName);

                List<byte[]> commands = mTearDownApdus.get(packageName).get();
                stream.writeInt(commands.size());

                for (byte[] cmd : commands) {
                    stream.writeInt(cmd.length);
                    stream.write(cmd, 0, cmd.length);
                }
            }

        } catch (IOException e) {
        } finally {
            try {
                if (output != null) {
                    stream.flush();
                    stream.close();
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private void applyProperties() {
        mManager.doSetProperties(PROPERTY_LLCP_LTO, mPrefs.getInt(PREF_LLCP_LTO, LLCP_LTO_DEFAULT));
        mManager.doSetProperties(PROPERTY_LLCP_MIU, mPrefs.getInt(PREF_LLCP_MIU, LLCP_MIU_DEFAULT));
        mManager.doSetProperties(PROPERTY_LLCP_WKS, mPrefs.getInt(PREF_LLCP_WKS, LLCP_WKS_DEFAULT));
        mManager.doSetProperties(PROPERTY_LLCP_OPT, mPrefs.getInt(PREF_LLCP_OPT, LLCP_OPT_DEFAULT));
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_A,
                mPrefs.getBoolean(PREF_DISCOVERY_A, DISCOVERY_A_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_B,
                mPrefs.getBoolean(PREF_DISCOVERY_B, DISCOVERY_B_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_F,
                mPrefs.getBoolean(PREF_DISCOVERY_F, DISCOVERY_F_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_15693,
                mPrefs.getBoolean(PREF_DISCOVERY_15693, DISCOVERY_15693_DEFAULT) ? 1 : 0);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_NFCIP,
                mPrefs.getBoolean(PREF_DISCOVERY_NFCIP, DISCOVERY_NFCIP_DEFAULT) ? 1 : 0);
     }

    private void updateNfcOnSetting(boolean oldEnabledState) {
        mPrefsEditor.putBoolean(PREF_NFC_ON, mIsNfcEnabled);
        mPrefsEditor.apply();

        synchronized(this) {
            if (oldEnabledState != mIsNfcEnabled) {
                Intent intent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGE);
                intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(NfcAdapter.EXTRA_NEW_BOOLEAN_STATE, mIsNfcEnabled);
                mContext.sendBroadcast(intent);
            }

            if (mIsNfcEnabled) {

                Context context = getApplicationContext();

                // Set this to null by default. If there isn't a tag on disk
                // or if there was an error reading the tag then this will cause
                // the status bar icon to be removed.
                NdefMessage myTag = null;

                FileInputStream input = null;

                try {
                    input = context.openFileInput(MY_TAG_FILE_NAME);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                    byte[] buffer = new byte[4096];
                    int read = 0;
                    while ((read = input.read(buffer)) > 0) {
                        bytes.write(buffer, 0, read);
                    }

                    myTag = new NdefMessage(bytes.toByteArray());
                } catch (FileNotFoundException e) {
                    // Ignore.
                } catch (IOException e) {
                    Log.e(TAG, "Could not read mytag file: ", e);
                    context.deleteFile(MY_TAG_FILE_NAME);
                } catch (FormatException e) {
                    Log.e(TAG, "Invalid NdefMessage for mytag", e);
                    context.deleteFile(MY_TAG_FILE_NAME);
                } finally {
                    try {
                        if (input != null) {
                            input.close();
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                }

                try {
                    mNfcAdapter.localSet(myTag);
                } catch (RemoteException e) {
                    // Ignore
                }
            } else {
                sendMessage(MSG_HIDE_MY_TAG_ICON, null);
            }
        }
    }

    // Reset all internals
    private synchronized void reset() {
        // TODO: none of these appear to be synchronized but are
        // read/written from different threads (notably Binder threads)...

        // Clear tables
        mObjectMap.clear();
        mSocketMap.clear();

        // Reset variables
        mIsNfcEnabled = false;
    }

    private synchronized Object findObject(int key) {
        Object device = null;

        device = mObjectMap.get(key);
        if (device == null) {
            Log.w(TAG, "Handle not found !");
        }

        return device;
    }

    synchronized void registerTagObject(NativeNfcTag nativeTag) {
        mObjectMap.put(nativeTag.getHandle(), nativeTag);
    }

    synchronized void unregisterObject(int handle) {
        mObjectMap.remove(handle);
    }

    private synchronized Object findSocket(int key) {
        if (mSocketMap == null) {
            return null;
        }
        return mSocketMap.get(key);
    }

    private void RemoveSocket(int key) {
        mSocketMap.remove(key);
    }

    /** For use by code in this process */
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw, int linearBufferLength) {
        try {
            int handle = mNfcAdapter.createLlcpSocket(sap, miu, rw, linearBufferLength);
            if (ErrorCodes.isError(handle)) {
                Log.e(TAG, "unable to create socket: " + ErrorCodes.asString(handle));
                return null;
            }
            return new LlcpSocket(mLlcpSocket, handle);
        } catch (RemoteException e) {
            // This will never happen since the code is calling into it's own process
            throw new IllegalStateException("unable to talk to myself", e);
        }
    }

    /** For use by code in this process */
    public LlcpServiceSocket createLlcpServiceSocket(int sap, String sn, int miu, int rw,
            int linearBufferLength) {
        try {
            int handle = mNfcAdapter.createLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
            if (ErrorCodes.isError(handle)) {
                Log.e(TAG, "unable to create socket: " + ErrorCodes.asString(handle));
                return null;
            }
            return new LlcpServiceSocket(mLlcpServerSocketService, mLlcpSocket, handle);
        } catch (RemoteException e) {
            // This will never happen since the code is calling into it's own process
            throw new IllegalStateException("unable to talk to myself", e);
        }
    }

    private void activateLlcpLink() {
        /* Broadcast Intent Link LLCP activated */
        Intent LlcpLinkIntent = new Intent();
        LlcpLinkIntent.setAction(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED);

        LlcpLinkIntent.putExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                NfcAdapter.LLCP_LINK_STATE_ACTIVATED);

        if (DBG) Log.d(TAG, "Broadcasting LLCP activation");
        mContext.sendOrderedBroadcast(LlcpLinkIntent, NFC_PERM);
    }

    public void sendMockNdefTag(NdefMessage msg) {
        sendMessage(MSG_MOCK_NDEF, msg);
    }

    void sendMessage(int what, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    final class NfcServiceHandler extends Handler {

        public NdefMessage[] findAndReadNdef(NativeNfcTag nativeTag) {
            // Try to find NDEF on any of the technologies.
            int[] technologies = nativeTag.getTechList();
            int[] handles = nativeTag.getHandleList();
            int techIndex = 0;
            int lastHandleScanned = 0;
            boolean ndefFoundAndConnected = false;
            NdefMessage[] ndefMsgs = null;
            boolean foundFormattable = false;
            int formattableHandle = 0;
            int formattableTechnology = 0;

            while ((!ndefFoundAndConnected) && (techIndex < technologies.length)) {
                if (handles[techIndex] != lastHandleScanned) {
                    // We haven't seen this handle yet, connect and checkndef
                    if (nativeTag.connect(technologies[techIndex])) {
                        // Check if this type is NDEF formatable
                        if (!foundFormattable) {
                            if (nativeTag.isNdefFormatable()) {
                                foundFormattable = true;
                                formattableHandle = nativeTag.getConnectedHandle();
                                formattableTechnology = nativeTag.getConnectedTechnology();
                                // We'll only add formattable tech if no ndef is
                                // found - this is because libNFC refuses to format
                                // an already NDEF formatted tag.
                            }
                            nativeTag.reconnect();
                        } // else, already found formattable technology

                        int[] ndefinfo = new int[2];
                        if (nativeTag.checkNdef(ndefinfo)) {
                            ndefFoundAndConnected = true;
                            boolean generateEmptyNdef = false;

                            int supportedNdefLength = ndefinfo[0];
                            int cardState = ndefinfo[1];
                            byte[] buff = nativeTag.read();
                            if (buff != null) {
                                ndefMsgs = new NdefMessage[1];
                                try {
                                    ndefMsgs[0] = new NdefMessage(buff);
                                    nativeTag.addNdefTechnology(ndefMsgs[0],
                                            nativeTag.getConnectedHandle(),
                                            nativeTag.getConnectedLibNfcType(),
                                            nativeTag.getConnectedTechnology(),
                                            supportedNdefLength, cardState);
                                    nativeTag.reconnect();
                                } catch (FormatException e) {
                                   // Create an intent anyway, without NDEF messages
                                   generateEmptyNdef = true;
                                }
                            } else {
                                generateEmptyNdef = true;
                            }

                           if (generateEmptyNdef) {
                               ndefMsgs = new NdefMessage[] { };
                               nativeTag.addNdefTechnology(null,
                                       nativeTag.getConnectedHandle(),
                                       nativeTag.getConnectedLibNfcType(),
                                       nativeTag.getConnectedTechnology(),
                                       supportedNdefLength, cardState);
                               nativeTag.reconnect();
                           }
                        } // else, no NDEF on this tech, continue loop
                    } else {
                        // Connect failed, tag maybe lost. Try next handle
                        // anyway.
                    }
                }
                lastHandleScanned = handles[techIndex];
                techIndex++;
            }
            if (ndefMsgs == null && foundFormattable) {
                // Tag is not NDEF yet, and found a formattable target,
                // so add formattable tech to tech list.
                nativeTag.addNdefFormatableTechnology(
                        formattableHandle,
                        formattableTechnology);
            }

            return ndefMsgs;
        }

        @Override
        public void handleMessage(Message msg) {
           switch (msg.what) {
           case MSG_MOCK_NDEF: {
               NdefMessage ndefMsg = (NdefMessage) msg.obj;
               Tag tag = Tag.createMockTag(new byte[] { 0x00 },
                       new int[] { },
                       new Bundle[] { });
               Log.d(TAG, "mock NDEF tag, starting corresponding activity");
               Log.d(TAG, tag.toString());
               dispatchTag(tag, new NdefMessage[] { ndefMsg });
               break;
           }

           case MSG_NDEF_TAG:
               if (DBG) Log.d(TAG, "Tag detected, notifying applications");
               NativeNfcTag nativeTag = (NativeNfcTag) msg.obj;
               NdefMessage[] ndefMsgs = findAndReadNdef(nativeTag);

               if (ndefMsgs != null) {
                   nativeTag.startPresenceChecking();
                   dispatchNativeTag(nativeTag, ndefMsgs);
               } else {
                   // No ndef found or connect failed, just try to reconnect and dispatch
                   if (nativeTag.reconnect()) {
                       nativeTag.startPresenceChecking();
                       dispatchNativeTag(nativeTag, null);
                   } else {
                       Log.w(TAG, "Failed to connect to tag");
                       nativeTag.disconnect();
                   }
               }
               break;

           case MSG_CARD_EMULATION:
               if (DBG) Log.d(TAG, "Card Emulation message");
               byte[] aid = (byte[]) msg.obj;
               /* Send broadcast */
               Intent aidIntent = new Intent();
               aidIntent.setAction(ACTION_AID_SELECTED);
               aidIntent.putExtra(EXTRA_AID, aid);
               if (DBG) Log.d(TAG, "Broadcasting ACTION_AID_SELECTED");
               mContext.sendBroadcast(aidIntent, NFCEE_ADMIN_PERM);
               break;

           case MSG_LLCP_LINK_ACTIVATION:
               NativeP2pDevice device = (NativeP2pDevice) msg.obj;

               Log.d(TAG, "LLCP Activation message");

               if (device.getMode() == NativeP2pDevice.MODE_P2P_TARGET) {
                   if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_TARGET");
                   if (device.doConnect()) {
                       /* Check Llcp compliancy */
                       if (mManager.doCheckLlcp()) {
                           /* Activate Llcp Link */
                           if (mManager.doActivateLlcp()) {
                               if (DBG) Log.d(TAG, "Initiator Activate LLCP OK");
                               // Register P2P device
                               mObjectMap.put(device.getHandle(), device);
                               activateLlcpLink();
                           } else {
                               /* should not happen */
                               Log.w(TAG, "Initiator Activate LLCP NOK. Disconnect.");
                               device.doDisconnect();
                           }

                       } else {
                           if (DBG) Log.d(TAG, "Remote Target does not support LLCP. Disconnect.");
                           device.doDisconnect();
                       }
                   } else {
                       if (DBG) Log.d(TAG, "Cannot connect remote Target. Polling loop restarted...");
                       /* The polling loop should have been restarted in failing doConnect */
                   }

               } else if (device.getMode() == NativeP2pDevice.MODE_P2P_INITIATOR) {
                   if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_INITIATOR");
                   /* Check Llcp compliancy */
                   if (mManager.doCheckLlcp()) {
                       /* Activate Llcp Link */
                       if (mManager.doActivateLlcp()) {
                           if (DBG) Log.d(TAG, "Target Activate LLCP OK");
                           // Register P2P device
                           mObjectMap.put(device.getHandle(), device);
                           activateLlcpLink();
                      }
                   } else {
                       Log.w(TAG, "checkLlcp failed");
                   }
               }
               break;

           case MSG_LLCP_LINK_DEACTIVATED:
               device = (NativeP2pDevice) msg.obj;

               Log.d(TAG, "LLCP Link Deactivated message. Restart polling loop.");
               synchronized (NfcService.this) {
                   /* Check if the device has been already unregistered */
                   if (mObjectMap.remove(device.getHandle()) != null) {
                       /* Disconnect if we are initiator */
                       if (device.getMode() == NativeP2pDevice.MODE_P2P_TARGET) {
                           if (DBG) Log.d(TAG, "disconnecting from target");
                           /* Restart polling loop */
                           device.doDisconnect();
                       } else {
                           if (DBG) Log.d(TAG, "not disconnecting from initiator");
                       }
                   }
               }

               /* Broadcast Intent Link LLCP activated */
               Intent LlcpLinkIntent = new Intent();
               LlcpLinkIntent.setAction(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED);
               LlcpLinkIntent.putExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                       NfcAdapter.LLCP_LINK_STATE_DEACTIVATED);
               if (DBG) Log.d(TAG, "Broadcasting LLCP deactivation");
               mContext.sendOrderedBroadcast(LlcpLinkIntent, NFC_PERM);
               break;

           case MSG_TARGET_DESELECTED:
               /* Broadcast Intent Target Deselected */
               if (DBG) Log.d(TAG, "Target Deselected");
               Intent TargetDeselectedIntent = new Intent();
               TargetDeselectedIntent.setAction(mManager.INTERNAL_TARGET_DESELECTED_ACTION);
               if (DBG) Log.d(TAG, "Broadcasting Intent");
               mContext.sendOrderedBroadcast(TargetDeselectedIntent, NFC_PERM);
               break;

           case MSG_SHOW_MY_TAG_ICON: {
               StatusBarManager sb = (StatusBarManager) getSystemService(
                       Context.STATUS_BAR_SERVICE);
               sb.setIcon("nfc", R.drawable.stat_sys_nfc, 0);
               break;
           }

           case MSG_HIDE_MY_TAG_ICON: {
               StatusBarManager sb = (StatusBarManager) getSystemService(
                       Context.STATUS_BAR_SERVICE);
               sb.removeIcon("nfc");
               break;
           }

           case MSG_SE_FIELD_ACTIVATED:{
               if (DBG) Log.d(TAG, "SE FIELD ACTIVATED");
               Intent eventFieldOnIntent = new Intent();
               eventFieldOnIntent.setAction(ACTION_RF_FIELD_ON_DETECTED);
               if (DBG) Log.d(TAG, "Broadcasting Intent");
               mContext.sendBroadcast(eventFieldOnIntent, NFCEE_ADMIN_PERM);
               break;
           }

           case MSG_SE_FIELD_DEACTIVATED:{
               if (DBG) Log.d(TAG, "SE FIELD DEACTIVATED");
               Intent eventFieldOffIntent = new Intent();
               eventFieldOffIntent.setAction(ACTION_RF_FIELD_OFF_DETECTED);
               if (DBG) Log.d(TAG, "Broadcasting Intent");
               mContext.sendBroadcast(eventFieldOffIntent, NFCEE_ADMIN_PERM);
               break;
           }

           default:
               Log.e(TAG, "Unknown message received");
               break;
           }
        }

        private Intent buildTagIntent(Tag tag, NdefMessage[] msgs, String action) {
            Intent intent = new Intent(action);
            intent.putExtra(NfcAdapter.EXTRA_TAG, tag);
            intent.putExtra(NfcAdapter.EXTRA_ID, tag.getId());
            intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, msgs);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        private void dispatchNativeTag(NativeNfcTag nativeTag, NdefMessage[] msgs) {
            Tag tag = new Tag(nativeTag.getUid(), nativeTag.getTechList(),
                    nativeTag.getTechExtras(), nativeTag.getHandle(), mNfcTagService);
            registerTagObject(nativeTag);
            if (!dispatchTag(tag, msgs)) {
                unregisterObject(nativeTag.getHandle());
                nativeTag.disconnect();
            }
        }

        public byte[] concat(byte[]... arrays) {
            int length = 0;
            for (byte[] array : arrays) {
                length += array.length;
            }
            byte[] result = new byte[length];
            int pos = 0;
            for (byte[] array : arrays) {
                System.arraycopy(array, 0, result, pos, array.length);
                pos += array.length;
            }
            return result;
        }

        private Uri parseWellKnownUriRecord(NdefRecord record) {
            byte[] payload = record.getPayload();

            /*
             * payload[0] contains the URI Identifier Code, per the
             * NFC Forum "URI Record Type Definition" section 3.2.2.
             *
             * payload[1]...payload[payload.length - 1] contains the rest of
             * the URI.
             */
            String prefix = URI_PREFIX_MAP[(payload[0] & 0xff)];
            byte[] fullUri = concat(prefix.getBytes(Charsets.UTF_8),
                    Arrays.copyOfRange(payload, 1, payload.length));
            return Uri.parse(new String(fullUri, Charsets.UTF_8));
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
                        intent.setData(Uri.parse(new String(record.getPayload(), Charsets.UTF_8)));
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
                                        intent.setData(parseWellKnownUriRecord(subRecord));
                                        return true;
                                    } else if (subTnf == NdefRecord.TNF_ABSOLUTE_URI) {
                                        intent.setData(Uri.parse(new String(subRecord.getPayload(),
                                                Charsets.UTF_8)));
                                        return true;
                                    }
                                }
                            } catch (FormatException e) {
                                return false;
                            }
                        } else if (Arrays.equals(type, NdefRecord.RTD_URI)) {
                            intent.setData(parseWellKnownUriRecord(record));
                            return true;
                        }
                        return false;
                    }
                }
                return false;
            } catch (Exception e) {
                Log.e(TAG, "failed to parse record", e);
                return false;
            }
        }

        /** Returns false if no activities were found to dispatch to */
        private boolean dispatchTag(Tag tag, NdefMessage[] msgs) {
            if (DBG) {
                Log.d(TAG, "Dispatching tag");
                Log.d(TAG, tag.toString());
            }

            IntentFilter[] overrideFilters;
            PendingIntent overrideIntent;
            String[][] overrideTechLists;
            boolean foregroundNdefPush = mNdefPushClient.getForegroundMessage() != null;
            synchronized (mNfcAdapter) {
                overrideFilters = mDispatchOverrideFilters;
                overrideIntent = mDispatchOverrideIntent;
                overrideTechLists = mDispatchOverrideTechLists;
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
                    synchronized (mNfcAdapter) {
                        mDispatchOverrideFilters = null;
                        mDispatchOverrideIntent = null;
                        mDispatchOverrideTechLists = null;
                    }
                }
            }

            // If there is not foreground NDEF push setup try a normal dispatch.
            //
            // This is avoided when disabled in the NDEF push case to avoid the situation where each
            // user has a different app in the foreground, causing each to launch itself on the
            // remote device and the apps swapping which is in the foreground on each phone.
            if (!foregroundNdefPush) {
                try {
                    return dispatchTagInternal(tag, msgs, null, null, null);
                } catch (CanceledException e) {
                    Log.e(TAG, "CanceledException unexpected here", e);
                    return false;
                }
            }

            return false;
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
                                overrideTechLists)) {
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
                    if (filterMatch(tagTechs, info.techs)) {
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
                    try {
                        mContext.startActivity(intent);
                        return true;
                    } catch (ActivityNotFoundException e) {
                        if (DBG) Log.w(TAG, "No activities for technology handling of " + intent);
                    }
                } else if (matches.size() > 1) {
                    // Multiple matches, show a custom activity chooser dialog
                    intent = new Intent(mContext, TechListChooserActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra(Intent.EXTRA_INTENT,
                            buildTagIntent(tag, msgs, NfcAdapter.ACTION_TECH_DISCOVERED));
                    intent.putParcelableArrayListExtra(TechListChooserActivity.EXTRA_RESOLVE_INFOS,
                            matches);
                    try {
                        mContext.startActivity(intent);
                        return true;
                    } catch (ActivityNotFoundException e) {
                        if (DBG) Log.w(TAG, "No activities for technology handling of " + intent);
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
            if (startDispatchActivity(intent, overrideIntent, overrideFilters, overrideTechLists)) {
                return true;
            } else {
                Log.e(TAG, "No tag fallback activity found for " + intent);
                return false;
            }
        }

        private boolean startDispatchActivity(Intent intent, PendingIntent overrideIntent,
                IntentFilter[] overrideFilters, String[][] overrideTechLists)
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
                try {
                    // If the current app called stopAppSwitches() then our startActivity()
                    // can be delayed for several seconds. This happens with the default home
                    // screen. As a system service we can override this behavior with
                    // resumeAppSwitches()
                    mIActivityManager.resumeAppSwitches();
                } catch (RemoteException e) { }
                try {
                    mContext.startActivity(intent);
                    return true;
                } catch (ActivityNotFoundException e) {
                    return false;
                }
            }
        }
    }

    private NfcServiceHandler mHandler = new NfcServiceHandler();

    private class EnableDisableDiscoveryTask extends AsyncTask<Boolean, Void, Void> {
        @Override
        protected Void doInBackground(Boolean... enable) {
            if (enable != null && enable.length > 0 && enable[0]) {
                synchronized (NfcService.this) {
                    mScreenOn = true;
                    applyRouting();
                }
            } else {
                mWakeLock.acquire();
                synchronized (NfcService.this) {
                    mScreenOn = false;
                    applyRouting();
                    maybeDisconnectTarget();
                }
                mWakeLock.release();
            }
            return null;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(
                    NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION)) {
                if (DBG) Log.d(TAG, "INERNAL_TARGET_DESELECTED_ACTION");

                /* Restart polling loop for notification */
                applyRouting();

            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                // Perform discovery enable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(new Boolean(true));
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                // Perform discovery disable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(new Boolean(false));
            } else if (intent.getAction().equals(ACTION_MASTER_CLEAR_NOTIFICATION)) {
                int handle = mSecureElement.doOpenSecureElementConnection();
                if (handle == 0) {
                    Log.e(TAG, "Could not open the secure element!");
                    return;
                }

                synchronized (NfcService.this) {
                    for (String packageName : mTearDownApdus.keySet()) {
                        for (byte[] cmd : mTearDownApdus.get(packageName).get()) {
                            mSecureElement.doTransceive(handle, cmd);
                        }
                    }
                }

                mSecureElement.doDisconnect(handle);
            } else if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)) {
                Uri data = intent.getData();
                if (data == null) return;
                String packageName = data.getSchemeSpecificPart();
                ApduList apdus = null;

                synchronized (NfcService.this) {
                    apdus = mTearDownApdus.remove(packageName);
                    if (apdus == null) {
                        return;
                    }

                    writeTearDownApdusLocked();
                }

                int handle = mSecureElement.doOpenSecureElementConnection();
                if (handle == 0) {
                    Log.e(TAG, "Could not open the secure element!");
                    return;
                }

                try {
                    for (byte[] cmd : apdus.get()) {
                        mSecureElement.doTransceive(handle, cmd);
                    }
                } finally {
                    mSecureElement.doDisconnect(handle);
                }
            }
        }
    };
}
