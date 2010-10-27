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
import com.android.nfc.mytag.MyTagClient;
import com.android.nfc.mytag.MyTagServer;

import android.app.Application;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.ILlcpConnectionlessSocket;
import android.nfc.ILlcpServiceSocket;
import android.nfc.ILlcpSocket;
import android.nfc.INfcAdapter;
import android.nfc.INfcTag;
import android.nfc.IP2pInitiator;
import android.nfc.IP2pTarget;
import android.nfc.LlcpPacket;
import android.nfc.NdefMessage;
import android.nfc.NdefTag;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;

public class NfcService extends Application {
    static {
        System.loadLibrary("nfc_jni");
    }

    public static final String SERVICE_NAME = "nfc";
    
    private static final String TAG = "NfcService";

    private static final String NFC_PERM = android.Manifest.permission.NFC;
    private static final String NFC_PERM_ERROR = "NFC permission required";
    private static final String ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ADMIN_PERM_ERROR = "WRITE_SECURE_SETTINGS permission required";

    private static final String PREF = "NfcServicePrefs";

    private static final String PREF_NFC_ON = "nfc_on";
    private static final boolean NFC_ON_DEFAULT = true;

    private static final String PREF_SECURE_ELEMENT_ON = "secure_element_on";
    private static final boolean SECURE_ELEMENT_ON_DEFAULT = false;

    private static final String PREF_SECURE_ELEMENT_ID = "secure_element_id";
    private static final int SECURE_ELEMENT_ID_DEFAULT = 0;

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

    /** Card Emulation Discovery mode for enableDiscovery() */
    private static final int DISCOVERY_MODE_CARD_EMULATION = 2;

    private static final int LLCP_SERVICE_SOCKET_TYPE = 0;
    private static final int LLCP_SOCKET_TYPE = 1;
    private static final int LLCP_CONNECTIONLESS_SOCKET_TYPE = 2;
    private static final int LLCP_SOCKET_NB_MAX = 5;  // Maximum number of socket managed
    private static final int LLCP_RW_MAX_VALUE = 15;  // Receive Window

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
    static final int MSG_MOCK_NDEF_TAG = 7;

    // TODO: none of these appear to be synchronized but are
    // read/written from different threads (notably Binder threads)...
    private final LinkedList<RegisteredSocket> mRegisteredSocketList = new LinkedList<RegisteredSocket>();
    private int mLlcpLinkState = NfcAdapter.LLCP_LINK_STATE_DEACTIVATED;
    private int mGeneratedSocketHandle = 0;
    private int mNbSocketCreated = 0;
    private volatile boolean mIsNfcEnabled = false;
    private int mSelectedSeId = 0;
    private boolean mNfcSecureElementState;
    private boolean mOpenPending = false;

    // fields below are used in multiple threads and protected by synchronized(this)
    private final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    private final HashMap<Integer, Object> mSocketMap = new HashMap<Integer, Object>();
    private int mTimeout = 0;
    private boolean mBootComplete = false;
    private boolean mScreenOn;

    // fields below are final after onCreate()
    private Context mContext;
    private NativeNfcManager mManager;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private MyTagServer mMyTagServer; 
    private MyTagClient mMyTagClient;

    private static NfcService sService;

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

        mMyTagServer = new MyTagServer();
        mMyTagClient = new MyTagClient(this);

        mPrefs = mContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();

        mIsNfcEnabled = false;  // real preference read later
        mScreenOn = true;  // assume screen is on during boot

        ServiceManager.addService(SERVICE_NAME, mNfcAdapter);

        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED);
        filter.addAction(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
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
        // TODO read this from permanent storage at boot time
        NdefMessage mLocalMessage = null;
        
        public boolean enable() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            boolean isSuccess = false;
            boolean previouslyEnabled = isEnabled();
            if (!previouslyEnabled) {
                reset();
                isSuccess = _enable(previouslyEnabled);
            }
            return isSuccess;
        }

        public boolean disable() throws RemoteException {
            boolean isSuccess = false;
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);
            boolean previouslyEnabled = isEnabled();
            Log.d(TAG, "Disabling NFC.  previous=" + previouslyEnabled);

            if (previouslyEnabled) {
                isSuccess = mManager.deinitialize();
                Log.d(TAG, "NFC success of deinitialize = " + isSuccess);
                if (isSuccess) {
                    mIsNfcEnabled = false;
                }
            }

            updateNfcOnSetting(previouslyEnabled);

            return isSuccess;
        }

        public int createLlcpConnectionlessSocket(int sap) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* Check SAP is not already used */

            /* Check nb socket created */
            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
                /* Store the socket handle */
                int sockeHandle = mGeneratedSocketHandle;

                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    NativeLlcpConnectionlessSocket socket;

                    socket = mManager.doCreateLlcpConnectionlessSocket(sap);
                    if (socket != null) {
                        synchronized(NfcService.this) {
                            /* Update the number of socket created */
                            mNbSocketCreated++;

                            /* Add the socket into the socket map */
                            mSocketMap.put(sockeHandle, socket);
                        }
                        return sockeHandle;
                    } else {
                        /*
                         * socket creation error - update the socket handle
                         * generation
                         */
                        mGeneratedSocketHandle -= 1;

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
                } else {
                    /* Check SAP is not already used */
                    if (!CheckSocketSap(sap)) {
                        return ErrorCodes.ERROR_SAP_USED;
                    }

                    NativeLlcpConnectionlessSocket socket = new NativeLlcpConnectionlessSocket(sap);

                    synchronized(NfcService.this) {
                        /* Add the socket into the socket map */
                        mSocketMap.put(sockeHandle, socket);

                        /* Update the number of socket created */
                        mNbSocketCreated++;
                    }
                    /* Create new registered socket */
                    RegisteredSocket registeredSocket = new RegisteredSocket(
                            LLCP_CONNECTIONLESS_SOCKET_TYPE, sockeHandle, sap);

                    /* Put this socket into a list of registered socket */
                    mRegisteredSocketList.add(registeredSocket);
                }

                /* update socket handle generation */
                mGeneratedSocketHandle++;

                return sockeHandle;

            } else {
                /* No socket available */
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }

        }

        public int createLlcpServiceSocket(int sap, String sn, int miu, int rw, int linearBufferLength)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
                int sockeHandle = mGeneratedSocketHandle;

                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    NativeLlcpServiceSocket socket;

                    socket = mManager.doCreateLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
                    if (socket != null) {
                        synchronized(NfcService.this) {
                            /* Update the number of socket created */
                            mNbSocketCreated++;
                            /* Add the socket into the socket map */
                            mSocketMap.put(sockeHandle, socket);
                        }
                    } else {
                        /* socket creation error - update the socket handle counter */
                        mGeneratedSocketHandle -= 1;

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
                } else {

                    /* Check SAP is not already used */
                    if (!CheckSocketSap(sap)) {
                        return ErrorCodes.ERROR_SAP_USED;
                    }

                    /* Service Name */
                    if (!CheckSocketServiceName(sn)) {
                        return ErrorCodes.ERROR_SERVICE_NAME_USED;
                    }

                    /* Check socket options */
                    if (!CheckSocketOptions(miu, rw, linearBufferLength)) {
                        return ErrorCodes.ERROR_SOCKET_OPTIONS;
                    }

                    NativeLlcpServiceSocket socket = new NativeLlcpServiceSocket(sn);
                    synchronized(NfcService.this) {
                        /* Add the socket into the socket map */
                        mSocketMap.put(sockeHandle, socket);

                        /* Update the number of socket created */
                        mNbSocketCreated++;
                    }
                    /* Create new registered socket */
                    RegisteredSocket registeredSocket = new RegisteredSocket(LLCP_SERVICE_SOCKET_TYPE,
                            sockeHandle, sap, sn, miu, rw, linearBufferLength);

                    /* Put this socket into a list of registered socket */
                    mRegisteredSocketList.add(registeredSocket);
                }

                /* update socket handle generation */
                mGeneratedSocketHandle += 1;

                Log.d(TAG, "Llcp Service Socket Handle =" + sockeHandle);
                return sockeHandle;
            } else {
                /* No socket available */
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }
        }

        public int createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {

                int sockeHandle = mGeneratedSocketHandle;

                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    NativeLlcpSocket socket;

                    socket = mManager.doCreateLlcpSocket(sap, miu, rw, linearBufferLength);

                    if (socket != null) {
                        synchronized(NfcService.this) {
                            /* Update the number of socket created */
                            mNbSocketCreated++;
                            /* Add the socket into the socket map */
                            mSocketMap.put(sockeHandle, socket);
                        }
                    } else {
                        /*
                         * socket creation error - update the socket handle
                         * generation
                         */
                        mGeneratedSocketHandle -= 1;

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
                } else {

                    /* Check SAP is not already used */
                    if (!CheckSocketSap(sap)) {
                        return ErrorCodes.ERROR_SAP_USED;
                    }

                    /* Check Socket options */
                    if (!CheckSocketOptions(miu, rw, linearBufferLength)) {
                        return ErrorCodes.ERROR_SOCKET_OPTIONS;
                    }

                    NativeLlcpSocket socket = new NativeLlcpSocket(sap, miu, rw);
                    synchronized(NfcService.this) {
                        /* Add the socket into the socket map */
                        mSocketMap.put(sockeHandle, socket);

                        /* Update the number of socket created */
                        mNbSocketCreated++;
                    }
                    /* Create new registered socket */
                    RegisteredSocket registeredSocket = new RegisteredSocket(LLCP_SOCKET_TYPE,
                            sockeHandle, sap, miu, rw, linearBufferLength);

                    /* Put this socket into a list of registered socket */
                    mRegisteredSocketList.add(registeredSocket);
                }

                /* update socket handle generation */
                mGeneratedSocketHandle++;

                return sockeHandle;
            } else {
                /* No socket available */
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }
        }

        public int deselectSecureElement() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mSelectedSeId == 0) {
                return ErrorCodes.ERROR_NO_SE_CONNECTED;
            }

            mManager.doDeselectSecureElement(mSelectedSeId);
            mNfcSecureElementState = false;
            mSelectedSeId = 0;

            /* store preference */
            mPrefsEditor.putBoolean(PREF_SECURE_ELEMENT_ON, false);
            mPrefsEditor.putInt(PREF_SECURE_ELEMENT_ID, 0);
            mPrefsEditor.apply();

            return ErrorCodes.SUCCESS;
        }

        public ILlcpConnectionlessSocket getLlcpConnectionlessInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpConnectionlessSocketService;
        }

        public ILlcpSocket getLlcpInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpSocket;
        }

        public ILlcpServiceSocket getLlcpServiceInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mLlcpServerSocketService;
        }

        public INfcTag getNfcTagInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mNfcTagService;
        }

        public synchronized int getOpenTimeout() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mTimeout;
        }

        public IP2pInitiator getP2pInitiatorInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mP2pInitiatorService;
        }

        public IP2pTarget getP2pTargetInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mP2pTargetService;
        }

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

        public int[] getSecureElementList() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            int[] list = null;
            if (mIsNfcEnabled == true) {
                list = mManager.doGetSecureElementList();
            }
            return list;
        }

        public int getSelectedSecureElement() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            return mSelectedSeId;
        }

        public boolean isEnabled() throws RemoteException {
            return mIsNfcEnabled;
        }

        public void openTagConnection(Tag tag) throws RemoteException {
            // TODO: Remove obsolete code
        }

        public int selectSecureElement(int seId) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mSelectedSeId == seId) {
                return ErrorCodes.ERROR_SE_ALREADY_SELECTED;
            }

            if (mSelectedSeId != 0) {
                return ErrorCodes.ERROR_SE_CONNECTED;
            }

            mSelectedSeId = seId;
            mManager.doSelectSecureElement(mSelectedSeId);

            /* store */
            mPrefsEditor.putBoolean(PREF_SECURE_ELEMENT_ON, true);
            mPrefsEditor.putInt(PREF_SECURE_ELEMENT_ID, mSelectedSeId);
            mPrefsEditor.apply();

            mNfcSecureElementState = true;

            return ErrorCodes.SUCCESS;

        }

        public synchronized void setOpenTimeout(int timeout) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            mTimeout = timeout;
        }

        public int setProperties(String param, String value) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

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
            mContext.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);

            synchronized (this) {
                mLocalMessage = message;
                // Send a message to the UI thread to show or hide the icon so the requests are
                // serialized and the icon can't get out of sync with reality.
                if (message != null) {
                    sendMessage(MSG_SHOW_MY_TAG_ICON, null);
                } else {
                    sendMessage(MSG_HIDE_MY_TAG_ICON, null);
                }
            }
        }
    };

    private final ILlcpSocket mLlcpSocket = new ILlcpSocket.Stub() {

        private final int CONNECT_FLAG = 0x01;
        private final int CLOSE_FLAG   = 0x02;
        private final int RECV_FLAG    = 0x04;
        private final int SEND_FLAG    = 0x08;

        private int concurrencyFlags;
        private Object sync;

        public int close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                        return ErrorCodes.SUCCESS;
                    } else {
                        return ErrorCodes.ERROR_IO;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;

                    return ErrorCodes.SUCCESS;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        public int connect(int nativeHandle, int sap) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnect(sap, socket.getConnectTimeout());
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        public int connectByName(int nativeHandle, String sn) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnectBy(sn, socket.getConnectTimeout());
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        public int getConnectTimeout(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getConnectTimeout();
            } else {
                return 0;
            }
        }

        public int getLocalSap(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        public int getLocalSocketMiu(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getMiu();
            } else {
                return 0;
            }
        }

        public int getLocalSocketRw(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getRw();
            } else {
                return 0;
            }
        }

        public int getRemoteSocketMiu(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
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

        public int getRemoteSocketRw(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
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

        public int receive(int nativeHandle, byte[] receiveBuffer) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            int receiveLength = 0;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                receiveLength = socket.doReceive(receiveBuffer);
                if (receiveLength != 0) {
                    return receiveLength;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        public int send(int nativeHandle, byte[] data) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
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

        public void setConnectTimeout(int nativeHandle, int timeout) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpSocket socket = null;

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                socket.setConnectTimeout(timeout);
            }
        }

    };

    private final ILlcpServiceSocket mLlcpServerSocketService = new ILlcpServiceSocket.Stub() {

        public int accept(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;
            NativeLlcpSocket clientSocket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
                /* find the socket in the hmap */
                socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
                if (socket != null) {
                    clientSocket = socket.doAccept(socket.getAcceptTimeout(), socket.getMiu(),
                            socket.getRw(), socket.getLinearBufferLength());
                    if (clientSocket != null) {
                        /* Add the socket into the socket map */
                        synchronized(this) {
                            mSocketMap.put(clientSocket.getHandle(), clientSocket);
                            mNbSocketCreated++;
                        }
                        return clientSocket.getHandle();
                    } else {
                        return ErrorCodes.ERROR_IO;
                    }
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }

        }

        public void close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;
                }
            }
        }

        public int getAcceptTimeout(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getAcceptTimeout();
            } else {
                return 0;
            }
        }

        public void setAcceptTimeout(int nativeHandle, int timeout) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpServiceSocket socket = null;

            /* find the socket in the hmap */
            socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
            if (socket != null) {
                socket.setAcceptTimeout(timeout);
            }
        }
    };

    private final ILlcpConnectionlessSocket mLlcpConnectionlessSocketService = new ILlcpConnectionlessSocket.Stub() {

        public void close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;
                }
            }
        }

        public int getSap(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        public LlcpPacket receiveFrom(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            LlcpPacket packet;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
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

        public int sendTo(int nativeHandle, LlcpPacket packet) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeLlcpConnectionlessSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
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
                tag.asyncDisconnect();
                return ErrorCodes.SUCCESS;
            }
            /* Restart polling loop for notification */
            maybeEnableDiscovery();
            mOpenPending = false;
            return ErrorCodes.ERROR_DISCONNECT;
        }

        public int connect(int nativeHandle) throws RemoteException {
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
            // TODO: register the tag as being locked rather than really connect
            return ErrorCodes.SUCCESS;
        }

        public String getType(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NativeNfcTag tag = null;
            String type;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                type = tag.getType();
                return type;
            }
            return null;
        }

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

            return tag.presenceCheck();
        }

        public boolean isNdef(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                isSuccess = tag.checkNdef();
            }
            return isSuccess;
        }

        public byte[] transceive(int nativeHandle, byte[] data) throws RemoteException {
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
                response = tag.transceive(data);
                return response;
            }
            return null;
        }

        public NdefMessage read(int nativeHandle) throws RemoteException {
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

        public int write(int nativeHandle, NdefMessage msg) throws RemoteException {
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

        public int getLastError(int nativeHandle) throws RemoteException {
            // TODO Auto-generated method stub
            return 0;
        }

        public int getModeHint(int nativeHandle) throws RemoteException {
            // TODO Auto-generated method stub
            return 0;
        }

        public int makeReadOnly(int nativeHandle) throws RemoteException {
            // TODO Auto-generated method stub
            return 0;
        }


    };

    private final IP2pInitiator mP2pInitiatorService = new IP2pInitiator.Stub() {

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
            maybeEnableDiscovery();
            mOpenPending = false;
            return null;
        }

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
                    mOpenPending = false;
                    /* remove the device from the hmap */
                    unregisterObject(nativeHandle);
                    /* Restart polling loop for notification */
                    maybeEnableDiscovery();
                }
            }
            return isSuccess;

        }

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

        public byte[] transceive(int nativeHandle, byte[] data) throws RemoteException {
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

    private boolean _enable(boolean oldEnabledState) {
        boolean isSuccess = mManager.initialize();
        if (isSuccess) {
            applyProperties();

            /* Check Secure Element setting */
            mNfcSecureElementState = mPrefs.getBoolean(PREF_SECURE_ELEMENT_ON,
                    SECURE_ELEMENT_ON_DEFAULT);

            if (mNfcSecureElementState) {
                int secureElementId = mPrefs.getInt(PREF_SECURE_ELEMENT_ID,
                        SECURE_ELEMENT_ID_DEFAULT);
                int[] Se_list = mManager.doGetSecureElementList();
                if (Se_list != null) {
                    for (int i = 0; i < Se_list.length; i++) {
                        if (Se_list[i] == secureElementId) {
                            mManager.doSelectSecureElement(Se_list[i]);
                            mSelectedSeId = Se_list[i];
                            break;
                        }
                    }
                }
            }

            mIsNfcEnabled = true;

            /* Start polling loop */
            maybeEnableDiscovery();

        } else {
            mIsNfcEnabled = false;
        }

        updateNfcOnSetting(oldEnabledState);

        return isSuccess;
    }

    /** Enable active tag discovery if screen is on and NFC is enabled */
    private synchronized void maybeEnableDiscovery() {
        if (mScreenOn && mIsNfcEnabled) {
            mManager.enableDiscovery(DISCOVERY_MODE_READER);
//            mMyTagServer.start();
        }
    }

    /** Disable active tag discovery if necessary */
    private synchronized void maybeDisableDiscovery() {
        if (mIsNfcEnabled) {
            mManager.disableDiscovery();
//            mMyTagServer.stop();
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
        int state;

        mPrefsEditor.putBoolean(PREF_NFC_ON, mIsNfcEnabled);
        mPrefsEditor.apply();

        synchronized(this) {
            if (mBootComplete && oldEnabledState != mIsNfcEnabled) {
                Intent intent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGE);
                intent.putExtra(NfcAdapter.EXTRA_NEW_BOOLEAN_STATE, mIsNfcEnabled);
                mContext.sendBroadcast(intent);
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
        mRegisteredSocketList.clear();

        // Reset variables
        mLlcpLinkState = NfcAdapter.LLCP_LINK_STATE_DEACTIVATED;
        mNbSocketCreated = 0;
        mIsNfcEnabled = false;
        mSelectedSeId = 0;
        mTimeout = 0;
        mOpenPending = false;
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
        Object socket = null;

        socket = mSocketMap.get(key);

        return socket;
    }

    private void RemoveSocket(int key) {
        mSocketMap.remove(key);
    }

    private boolean CheckSocketSap(int sap) {
        /* List of sockets registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            if (sap == registeredSocket.mSap) {
                /* SAP already used */
                return false;
            }
        }
        return true;
    }

    private boolean CheckSocketOptions(int miu, int rw, int linearBufferlength) {

        if (rw > LLCP_RW_MAX_VALUE || miu < LLCP_MIU_DEFAULT || linearBufferlength < miu) {
            return false;
        }
        return true;
    }

    private boolean CheckSocketServiceName(String sn) {

        /* List of sockets registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            if (sn.equals(registeredSocket.mServiceName)) {
                /* Service Name already used */
                return false;
            }
        }
        return true;
    }

    private void RemoveRegisteredSocket(int nativeHandle) {
        /* check if sockets are registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();
            if (registeredSocket.mHandle == nativeHandle) {
                /* remove the registered socket from the list */
                it.remove();
                Log.d(TAG, "socket removed");
            }
        }
    }

    /*
     * RegisteredSocket class to store the creation request of socket until the
     * LLCP link in not activated
     */
    private class RegisteredSocket {
        private final int mType;

        private final int mHandle;

        private final int mSap;

        private int mMiu;

        private int mRw;

        private String mServiceName;

        private int mlinearBufferLength;

        RegisteredSocket(int type, int handle, int sap, String sn, int miu, int rw,
                int linearBufferLength) {
            mType = type;
            mHandle = handle;
            mSap = sap;
            mServiceName = sn;
            mRw = rw;
            mMiu = miu;
            mlinearBufferLength = linearBufferLength;
        }

        RegisteredSocket(int type, int handle, int sap, int miu, int rw, int linearBufferLength) {
            mType = type;
            mHandle = handle;
            mSap = sap;
            mRw = rw;
            mMiu = miu;
            mlinearBufferLength = linearBufferLength;
        }

        RegisteredSocket(int type, int handle, int sap) {
            mType = type;
            mHandle = handle;
            mSap = sap;
        }
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
        /* check if sockets are registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        Log.d(TAG, "Nb socket resgistered = " + mRegisteredSocketList.size());

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            switch (registeredSocket.mType) {
            case LLCP_SERVICE_SOCKET_TYPE:
                Log.d(TAG, "Registered Llcp Service Socket");
                NativeLlcpServiceSocket serviceSocket;

                serviceSocket = mManager.doCreateLlcpServiceSocket(
                        registeredSocket.mSap, registeredSocket.mServiceName,
                        registeredSocket.mMiu, registeredSocket.mRw,
                        registeredSocket.mlinearBufferLength);

                if (serviceSocket != null) {
                    /* Add the socket into the socket map */
                    synchronized(NfcService.this) {
                        mSocketMap.put(registeredSocket.mHandle, serviceSocket);
                    }
                } else {
                    /* socket creation error - update the socket
                     * handle counter */
                    mGeneratedSocketHandle -= 1;
                }
                break;

            case LLCP_SOCKET_TYPE:
                Log.d(TAG, "Registered Llcp Socket");
                NativeLlcpSocket clientSocket;
                clientSocket = mManager.doCreateLlcpSocket(registeredSocket.mSap,
                        registeredSocket.mMiu, registeredSocket.mRw,
                        registeredSocket.mlinearBufferLength);
                if (clientSocket != null) {
                    /* Add the socket into the socket map */
                    synchronized(NfcService.this) {
                        mSocketMap.put(registeredSocket.mHandle, clientSocket);
                    }
                } else {
                    /* socket creation error - update the socket
                     * handle counter */
                    mGeneratedSocketHandle -= 1;
                }
                break;

            case LLCP_CONNECTIONLESS_SOCKET_TYPE:
                Log.d(TAG, "Registered Llcp Connectionless Socket");
                NativeLlcpConnectionlessSocket connectionlessSocket;
                connectionlessSocket = mManager.doCreateLlcpConnectionlessSocket(
                        registeredSocket.mSap);
                if (connectionlessSocket != null) {
                    /* Add the socket into the socket map */
                    synchronized(NfcService.this) {
                        mSocketMap.put(registeredSocket.mHandle, connectionlessSocket);
                    }
                } else {
                    /* socket creation error - update the socket
                     * handle counter */
                    mGeneratedSocketHandle -= 1;
                }
                break;
            }
        }

        /* Remove all registered socket from the list */
        mRegisteredSocketList.clear();

        /* Broadcast Intent Link LLCP activated */
        Intent LlcpLinkIntent = new Intent();
        LlcpLinkIntent.setAction(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED);

        LlcpLinkIntent.putExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                NfcAdapter.LLCP_LINK_STATE_ACTIVATED);

        Log.d(TAG, "Broadcasting LLCP activation");
        mContext.sendOrderedBroadcast(LlcpLinkIntent, NFC_PERM);
    }

    public void sendMockNdefTag(NdefMessage msg) {
        NdefTag tag = NdefTag.createMockNdefTag(new byte[] { 0x00 },
                new String[] { Tag.TARGET_OTHER },
                null, null, new String[] { NdefTag.TARGET_OTHER },
                new NdefMessage[][] { new NdefMessage[] { msg } });
        sendMessage(MSG_MOCK_NDEF_TAG, tag);
    }

    void sendMessage(int what, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
           switch (msg.what) {
           case MSG_MOCK_NDEF_TAG: {
               NdefTag tag = (NdefTag) msg.obj;
               Intent intent = buildNdefTagIntent(tag);
               Log.d(TAG, "mock NDEF tag, starting corresponding activity");
               Log.d(TAG, tag.toString());
               try {
                   mContext.startActivity(intent);
               } catch (ActivityNotFoundException e) {
                   Log.w(TAG, "No activity found for mock tag");
               }
           }

           case MSG_NDEF_TAG:
               Log.d(TAG, "Tag detected, notifying applications");
               NativeNfcTag nativeTag = (NativeNfcTag) msg.obj;
               if (nativeTag.connect()) {
                   if (nativeTag.checkNdef()) {
                       byte[] buff = nativeTag.read();
                       if (buff != null) {
                           NdefMessage[] msgNdef = new NdefMessage[1];
                           try {
                               msgNdef[0] = new NdefMessage(buff);
                               NdefTag tag = new NdefTag(nativeTag.getUid(),
                                       TagTarget.internalTypeToRawTargets(nativeTag.getType()),
                                       null, null, nativeTag.getHandle(),
                                       TagTarget.internalTypeToNdefTargets(nativeTag.getType()),
                                       new NdefMessage[][] {msgNdef});
                               Intent intent = buildNdefTagIntent(tag);
                               Log.d(TAG, "NDEF tag found, starting corresponding activity");
                               Log.d(TAG, tag.toString());
                               try {
                                   mContext.startActivity(intent);
                                   registerTagObject(nativeTag);
                               } catch (ActivityNotFoundException e) {
                                   Log.w(TAG, "No activity found, disconnecting");
                                   nativeTag.asyncDisconnect();
                               }
                           } catch (FormatException e) {
                               Log.w(TAG, "Unable to create NDEF message object (tag empty or not well formated)");
                               nativeTag.asyncDisconnect();
                           }
                       } else {
                           Log.w(TAG, "Unable to read NDEF message (tag empty or not well formated)");
                           nativeTag.asyncDisconnect();
                       }
                   } else {
                       Intent intent = new Intent();
                       Tag tag = new Tag(nativeTag.getUid(), false,
                               TagTarget.internalTypeToRawTargets(nativeTag.getType()),
                               null, null, nativeTag.getHandle());
                       intent.setAction(NfcAdapter.ACTION_TAG_DISCOVERED);
                       intent.putExtra(NfcAdapter.EXTRA_TAG, tag);
                       intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                       Log.d(TAG, "Non-NDEF tag found, starting corresponding activity");
                       Log.d(TAG, tag.toString());
                       try {
                           mContext.startActivity(intent);
                           registerTagObject(nativeTag);
                       } catch (ActivityNotFoundException e) {
                           Log.w(TAG, "No activity found, disconnecting");
                           nativeTag.asyncDisconnect();
                       }
                   }
               } else {
                   Log.w(TAG, "Failed to connect to tag");
                   nativeTag.asyncDisconnect();
               }
               break;
           case MSG_CARD_EMULATION:
               Log.d(TAG, "Card Emulation message");
               byte[] aid = (byte[]) msg.obj;
               /* Send broadcast ordered */
               Intent TransactionIntent = new Intent();
               TransactionIntent.setAction(NfcAdapter.ACTION_TRANSACTION_DETECTED);
               TransactionIntent.putExtra(NfcAdapter.EXTRA_AID, aid);
               Log.d(TAG, "Broadcasting Card Emulation event");
               mContext.sendOrderedBroadcast(TransactionIntent, NFC_PERM);
               break;

           case MSG_LLCP_LINK_ACTIVATION:
               NativeP2pDevice device = (NativeP2pDevice) msg.obj;

               Log.d(TAG, "LLCP Activation message");

               if (device.getMode() == NativeP2pDevice.MODE_P2P_TARGET) {
                   if (device.doConnect()) {
                       /* Check Llcp compliancy */
                       if (mManager.doCheckLlcp()) {
                           /* Activate Llcp Link */
                           if (mManager.doActivateLlcp()) {
                               Log.d(TAG, "Initiator Activate LLCP OK");
                               /* Broadcast Intent Link LLCP activated */
                               Intent LlcpLinkIntent = new Intent();
                               LlcpLinkIntent
                                       .setAction(mManager.INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION);
                               LlcpLinkIntent.putExtra(
                                       mManager.INTERNAL_LLCP_LINK_STATE_CHANGED_EXTRA,
                                       NfcAdapter.LLCP_LINK_STATE_ACTIVATED);
                               Log.d(TAG, "Broadcasting internal LLCP activation");
                               mContext.sendBroadcast(LlcpLinkIntent);
                           }

                       } else {
                           device.doDisconnect();
                       }

                   }

               } else if (device.getMode() == NativeP2pDevice.MODE_P2P_INITIATOR) {
                   /* Check Llcp compliancy */
                   if (mManager.doCheckLlcp()) {
                       /* Activate Llcp Link */
                       if (mManager.doActivateLlcp()) {
                           Log.d(TAG, "Target Activate LLCP OK");
                           /* Broadcast Intent Link LLCP activated */
                           Intent LlcpLinkIntent = new Intent();
                           LlcpLinkIntent
                                   .setAction(mManager.INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION);
                           LlcpLinkIntent.putExtra(mManager.INTERNAL_LLCP_LINK_STATE_CHANGED_EXTRA,
                                   NfcAdapter.LLCP_LINK_STATE_ACTIVATED);
                           Log.d(TAG, "Broadcasting internal LLCP activation");
                           mContext.sendBroadcast(LlcpLinkIntent);
                       }
                   }
               }
               break;

           case MSG_LLCP_LINK_DEACTIVATED:
               /* Broadcast Intent Link LLCP activated */
               Log.d(TAG, "LLCP Link Deactivated message");
               Intent LlcpLinkIntent = new Intent();
               LlcpLinkIntent.setAction(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED);
               LlcpLinkIntent.putExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                       NfcAdapter.LLCP_LINK_STATE_DEACTIVATED);
               Log.d(TAG, "Broadcasting LLCP deactivation");
               mContext.sendOrderedBroadcast(LlcpLinkIntent, NFC_PERM);
               break;

           case MSG_TARGET_DESELECTED:
               /* Broadcast Intent Target Deselected */
               Log.d(TAG, "Target Deselected");
               Intent TargetDeselectedIntent = new Intent();
               TargetDeselectedIntent.setAction(mManager.INTERNAL_TARGET_DESELECTED_ACTION);
               Log.d(TAG, "Broadcasting Intent");
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

           default:
               Log.e(TAG, "Unknown message received");
               break;
           }
        }

        private Intent buildNdefTagIntent(NdefTag tag) {
            Intent intent = new Intent();
               intent.setAction(NfcAdapter.ACTION_NDEF_TAG_DISCOVERED);
               intent.putExtra(NfcAdapter.EXTRA_TAG, tag);
               intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }
    };

    private class EnableDisableDiscoveryTask extends AsyncTask<Boolean, Void, Void> {
        protected Void doInBackground(Boolean... enable) {
            if (enable != null && enable.length > 0 && enable[0]) {
                synchronized (NfcService.this) {
                    mScreenOn = true;
                    maybeEnableDiscovery();
                }
            } else {
                synchronized (NfcService.this) {
                    mScreenOn = false;
                    maybeDisableDiscovery();
                }
            }
            return null;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED)) {
                Log.d(TAG, "LLCP_LINK_STATE_CHANGED");

                mLlcpLinkState = intent.getIntExtra(
                        NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                        NfcAdapter.LLCP_LINK_STATE_DEACTIVATED);

                if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_DEACTIVATED) {
                    /* restart polling loop */
                    maybeEnableDiscovery();
                } else if (mLlcpLinkState == NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
                    activateLlcpLink();
                }
            } else if (intent.getAction().equals(
                    NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION)) {
                Log.d(TAG, "INERNAL_TARGET_DESELECTED_ACTION");

                mOpenPending = false;
                /* Restart polling loop for notification */
                maybeEnableDiscovery();

            } else if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
                Log.i(TAG, "Boot complete");

                synchronized(this) {
                    mBootComplete = true;

                    // now its safe to send the NFC state
                    Intent sendIntent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGE);
                    sendIntent.putExtra(NfcAdapter.EXTRA_NEW_BOOLEAN_STATE, mIsNfcEnabled);
                    mContext.sendBroadcast(sendIntent);
                }
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
            }
        }
    };
}
