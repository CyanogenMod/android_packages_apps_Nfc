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
import com.android.nfc.DeviceHost.DeviceHostListener;
import com.android.nfc.DeviceHost.NfcDepEndpoint;
import com.android.nfc.DeviceHost.TagEndpoint;
import com.android.nfc.nxp.NativeLlcpConnectionlessSocket;
import com.android.nfc.nxp.NativeLlcpServiceSocket;
import com.android.nfc.nxp.NativeLlcpSocket;
import com.android.nfc.nxp.NativeNfcManager;
import com.android.nfc.nxp.NativeNfcSecureElement;
import com.android.nfc3.R;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.nfc.ErrorCodes;
import android.nfc.FormatException;
import android.nfc.ILlcpConnectionlessSocket;
import android.nfc.ILlcpServiceSocket;
import android.nfc.ILlcpSocket;
import android.nfc.INdefPushCallback;
import android.nfc.INfcAdapter;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcTag;
import android.nfc.IP2pInitiator;
import android.nfc.IP2pTarget;
import android.nfc.LlcpPacket;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.TransceiveResult;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

interface P2pStatusListener {
    void onP2pBegin();
    void onP2pEnd();
    void onP2pError();
}

public class NfcService extends Application implements DeviceHostListener, P2pStatusListener {
    private static final String ACTION_MASTER_CLEAR_NOTIFICATION = "android.intent.action.MASTER_CLEAR_NOTIFICATION";

    static final boolean DBG = true;
    static final String TAG = "NfcService";

    private static final String SE_RESET_SCRIPT_FILE_NAME = "/system/etc/se-reset-script";

    public static final String SERVICE_NAME = "nfc";

    private static final String NFC_PERM = android.Manifest.permission.NFC;
    private static final String NFC_PERM_ERROR = "NFC permission required";
    private static final String ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ADMIN_PERM_ERROR = "WRITE_SECURE_SETTINGS permission required";
    private static final String NFCEE_ADMIN_PERM = "com.android.nfc.permission.NFCEE_ADMIN";
    private static final String NFCEE_ADMIN_PERM_ERROR = "NFCEE_ADMIN permission required";

    /*package*/ static final String PREF = "NfcServicePrefs";

    private static final String PREF_NFC_ON = "nfc_on";
    private static final boolean NFC_ON_DEFAULT = true;
    private static final String PREF_ZEROCLICK_ON = "zeroclick_on";
    private static final boolean ZEROCLICK_ON_DEFAULT = true;

    private static final String PREF_FIRST_BOOT = "first_boot";

    static final int MSG_NDEF_TAG = 0;
    static final int MSG_CARD_EMULATION = 1;
    static final int MSG_LLCP_LINK_ACTIVATION = 2;
    static final int MSG_LLCP_LINK_DEACTIVATED = 3;
    static final int MSG_TARGET_DESELECTED = 4;
    static final int MSG_MOCK_NDEF = 7;
    static final int MSG_SE_FIELD_ACTIVATED = 8;
    static final int MSG_SE_FIELD_DEACTIVATED = 9;
    static final int MSG_SE_APDU_RECEIVED = 10;
    static final int MSG_SE_EMV_CARD_REMOVAL = 11;
    static final int MSG_SE_MIFARE_ACCESS = 12;

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

    public static final String ACTION_APDU_RECEIVED =
        "com.android.nfc_extras.action.APDU_RECEIVED";
    public static final String EXTRA_APDU_BYTES =
        "com.android.nfc_extras.extra.APDU_BYTES";

    public static final String ACTION_EMV_CARD_REMOVAL =
        "com.android.nfc_extras.action.EMV_CARD_REMOVAL";

    public static final String ACTION_MIFARE_ACCESS_DETECTED =
        "com.android.nfc_extras.action.MIFARE_ACCESS_DETECTED";
    public static final String EXTRA_MIFARE_BLOCK =
        "com.android.nfc_extras.extra.MIFARE_BLOCK";

    // TODO: none of these appear to be synchronized but are
    // read/written from different threads (notably Binder threads)...
    private int mGeneratedSocketHandle = 0;
    private volatile boolean mIsNfcEnabled = false;
    private boolean mIsDiscoveryOn = false;
    private boolean mZeroClickOn = false;

    // NFC Execution Environment
    // fields below are protected by this
    private NativeNfcSecureElement mSecureElement;
    private OpenSecureElement mOpenEe;  // null when EE closed
    private int mEeRoutingState;  // contactless interface routing

    // fields below must be used only on the UI thread and therefore aren't synchronized
    boolean mP2pStarted = false;

    // fields below are used in multiple threads and protected by synchronized(this)
    private final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    private final HashMap<Integer, Object> mSocketMap = new HashMap<Integer, Object>();
    private boolean mScreenUnlocked;
    private HashSet<String> mSePackages = new HashSet<String>();

    // fields below are final after onCreate()
    Context mContext;
    private NativeNfcManager mDeviceHost;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private PowerManager.WakeLock mWakeLock;
    NdefP2pManager mP2pManager;
    int mStartSound;
    int mEndSound;
    int mErrorSound;
    SoundPool mSoundPool; // playback synchronized on this

    private NfcDispatcher mNfcDispatcher;
    private KeyguardManager mKeyguard;

    private static NfcService sService;

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
    public void onRemoteEndpointDiscovered(TagEndpoint tag) {
        sendMessage(NfcService.MSG_NDEF_TAG, tag);
    }

    /**
     * Notifies transaction
     */
    @Override
    public void onCardEmulationDeselected() {
        sendMessage(NfcService.MSG_TARGET_DESELECTED, null);
    }

    /**
     * Notifies transaction
     */
    @Override
    public void onCardEmulationAidSelected(byte[] aid) {
        sendMessage(NfcService.MSG_CARD_EMULATION, aid);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    @Override
    public void onLlcpLinkActivated(NfcDepEndpoint device) {
        sendMessage(NfcService.MSG_LLCP_LINK_ACTIVATION, device);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    @Override
    public void onLlcpLinkDeactivated(NfcDepEndpoint device) {
        sendMessage(NfcService.MSG_LLCP_LINK_DEACTIVATED, device);
    }

    @Override
    public void onRemoteFieldActivated() {
        sendMessage(NfcService.MSG_SE_FIELD_ACTIVATED, null);
    }

    @Override
    public void onRemoteFieldDeactivated() {
        sendMessage(NfcService.MSG_SE_FIELD_DEACTIVATED, null);
    }

    @Override
    public void onSeApduReceived(byte[] apdu) {
        sendMessage(NfcService.MSG_SE_APDU_RECEIVED, apdu);
    }

    @Override
    public void onSeEmvCardRemoval() {
        sendMessage(NfcService.MSG_SE_EMV_CARD_REMOVAL, null);
    }

    @Override
    public void onSeMifareAccess(byte[] block) {
        sendMessage(NfcService.MSG_SE_MIFARE_ACCESS, block);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Starting NFC service");

        sService = this;

        mSoundPool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
        mStartSound = mSoundPool.load(this, R.raw.start, 1);
        mEndSound = mSoundPool.load(this, R.raw.end, 1);
        mErrorSound = mSoundPool.load(this, R.raw.error, 1);

        mContext = this;
        mDeviceHost = new NativeNfcManager(this, this);
        mDeviceHost.initializeNativeStructure();

        mP2pManager = new NdefP2pManager(this, this);
        mNfcDispatcher = new NfcDispatcher(this, mP2pManager);

        mSecureElement = new NativeNfcSecureElement();
        mEeRoutingState = ROUTE_OFF;

        mPrefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();

        mIsNfcEnabled = false;  // load from preferences later

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcService");
        mKeyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        mScreenUnlocked = !mKeyguard.isKeyguardLocked() && !mKeyguard.isKeyguardSecure();

        ServiceManager.addService(SERVICE_NAME, mNfcAdapter);

        IntentFilter filter = new IntentFilter(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ACTION_MASTER_CLEAR_NOTIFICATION);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");

        registerReceiver(mReceiver, filter);

        Thread t = new Thread() {
            @Override
            public void run() {
                boolean nfc_on = mPrefs.getBoolean(PREF_NFC_ON, NFC_ON_DEFAULT);
                if (nfc_on) {
                    _enable(false, true);
                }
                resetSeOnFirstBoot();
            }
        };
        t.start();
    }

    private void playSound(int sound) {
        synchronized (this) {
            mSoundPool.play(sound, 1.0f, 1.0f, 0, 0, 1.0f);
        }
    }

    @Override
    public void onP2pBegin() {
        if (!mP2pStarted) {
            playSound(mStartSound);
            mP2pStarted = true;
        }
    }

    @Override
    public void onP2pEnd() {
        if (mP2pStarted) {
            playSound(mEndSound);
            mP2pStarted = false;
        }
    }

    @Override
    public void onP2pError() {
        if (mP2pStarted) {
            playSound(mErrorSound);
            mP2pStarted = false;
        }
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
                isSuccess = _enable(previouslyEnabled, true);
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
                isSuccess = _disable(previouslyEnabled, true);
            }
            return isSuccess;
        }

        @Override
        public boolean zeroClickEnabled() throws RemoteException {
            return mZeroClickOn;
        }

        @Override
        public boolean enableZeroClick() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);
            synchronized(NfcService.this) {
                if (!mZeroClickOn) {
                    Log.e(TAG, "ENABLING 0-CLICK");
                    mPrefsEditor.putBoolean(PREF_ZEROCLICK_ON, true);
                    mPrefsEditor.apply();
                    mP2pManager.enableNdefServer();
                    mZeroClickOn = true;
                }
            }
            return true;
        }

        @Override
        public boolean disableZeroClick() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);
            synchronized(NfcService.this) {
                if (mZeroClickOn) {
                    Log.e(TAG, "DISABLING 0-CLICK");
                    mPrefsEditor.putBoolean(PREF_ZEROCLICK_ON, false);
                    mPrefsEditor.apply();
                    mP2pManager.disableNdefServer();
                    mZeroClickOn = false;
                }
            }
            return true;
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

            mNfcDispatcher.enableForegroundDispatch(intent, filters, techLists);
        }

        @Override
        public void disableForegroundDispatch(ComponentName activity) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            mNfcDispatcher.disableForegroundDispatch();
        }

        @Override
        public void enableForegroundNdefPush(ComponentName activity, NdefMessage msg) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            if (activity == null || msg == null) {
                throw new IllegalArgumentException();
            }
            if (mP2pManager.setForegroundMessage(msg)) {
                Log.e(TAG, "Replacing active NDEF push message");
            }
        }

        @Override
        public void enableForegroundNdefPushWithCallback(ComponentName activity,
                INdefPushCallback callback) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            if (activity == null || callback == null) {
                throw new IllegalArgumentException();
            }
            if (mP2pManager.setForegroundCallback(callback)) {
                Log.e(TAG, "Replacing active NDEF push message");
            }
        }

        @Override
        public void disableForegroundNdefPush(ComponentName activity) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            boolean hadMsg = mP2pManager.setForegroundMessage(null);
            boolean hadCallback = mP2pManager.setForegroundCallback(null);
            if (!hadMsg || !hadCallback) {
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

            socket = mDeviceHost.doCreateLlcpConnectionlessSocket(sap);
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
                int errorStatus = mDeviceHost.doGetLastError();

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

            socket = mDeviceHost.doCreateLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
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
                int errorStatus = mDeviceHost.doGetLastError();

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

            socket = mDeviceHost.doCreateLlcpSocket(sap, miu, rw, linearBufferLength);

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
                int errorStatus = mDeviceHost.doGetLastError();

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
        public boolean isEnabled() throws RemoteException {
            return mIsNfcEnabled;
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
                removeSocket(nativeHandle);
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
                    removeSocket(nativeHandle);
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
                removeSocket(nativeHandle);
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

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
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

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_DISCONNECT;
            }

            if (technology == TagTechnology.NFC_B) {
                return ErrorCodes.ERROR_NOT_SUPPORTED;
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

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
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
            TagEndpoint tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                return tag.getTechList();
            }
            return null;
        }

        @Override
        public byte[] getUid(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;
            byte[] uid;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                uid = tag.getUid();
                return uid;
            }
            return null;
        }

        @Override
        public boolean isPresent(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return false;
            }

            return tag.isPresent();
        }

        @Override
        public boolean isNdef(int nativeHandle) throws RemoteException {
            TagEndpoint tag = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
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

            TagEndpoint tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
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

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                byte[] buf = tag.readNdef();
                if (buf == null) {
                    return null;
                }

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

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.writeNdef(msg.toByteArray())) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        @Override
        public int getLastError(int nativeHandle) throws RemoteException {
            return(mDeviceHost.doGetLastError());
        }

        @Override
        public boolean ndefIsWritable(int nativeHandle) throws RemoteException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int ndefMakeReadOnly(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.makeReadOnly()) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public int formatNdef(int nativeHandle, byte[] key) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag == null) {
                return ErrorCodes.ERROR_IO;
            }

            if (tag.formatNdef(key)) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        @Override
        public Tag rediscover(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // For now the prime usecase for rediscover() is to be able
                // to access the NDEF technology after formatting without
                // having to remove the tag from the field, or similar
                // to have access to NdefFormatable in case low-level commands
                // were used to remove NDEF. So instead of doing a full stack
                // rediscover (which is poorly supported at the moment anyway),
                // we simply remove these two technologies and detect them
                // again.
                tag.removeTechnology(TagTechnology.NDEF);
                tag.removeTechnology(TagTechnology.NDEF_FORMATABLE);
                NdefMessage[] msgs = tag.findAndReadNdef();
                // Build a new Tag object to return
                Tag newTag = new Tag(tag.getUid(), tag.getTechList(),
                        tag.getTechExtras(), tag.getHandle(), mNfcTagService);
                return newTag;
            }
            return null;
        }

        @Override
        public int setTimeout(int tech, int timeout) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            boolean success = mDeviceHost.setTimeout(tech, timeout);
            if (success) {
                return ErrorCodes.SUCCESS;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }
        }

        @Override
        public int getTimeout(int tech) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            return mDeviceHost.getTimeout(tech);
        }

        @Override
        public void resetTimeouts() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            mDeviceHost.resetTimeouts();
        }
    };

    private final IP2pInitiator mP2pInitiatorService = new IP2pInitiator.Stub() {

        @Override
        public byte[] getGeneralBytes(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NfcDepEndpoint device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
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

            NfcDepEndpoint device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        @Override
        public byte[] receive(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NfcDepEndpoint device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.receive();
                if (buff == null) {
                    return null;
                }
                return buff;
            }
            /* Restart polling loop for notification */
            applyRouting();
            return null;
        }

        @Override
        public boolean send(int nativeHandle, byte[] data) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NfcDepEndpoint device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
            if (device != null) {
                isSuccess = device.send(data);
            }
            return isSuccess;
        }
    };

    private final IP2pTarget mP2pTargetService = new IP2pTarget.Stub() {

        @Override
        public int connect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NfcDepEndpoint device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
            if (device != null) {
                if (device.connect()) {
                    return ErrorCodes.SUCCESS;
                }
            }
            return ErrorCodes.ERROR_CONNECT;
        }

        @Override
        public boolean disconnect(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NfcDepEndpoint device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
            if (device != null) {
                if (isSuccess = device.disconnect()) {
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

            NfcDepEndpoint device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
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

            NfcDepEndpoint device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        @Override
        public byte[] transceive(int nativeHandle, byte[] data)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            NfcDepEndpoint device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NfcDepEndpoint) findObject(nativeHandle);
            if (device != null) {
                return device.transceive(data);
            }
            return null;
        }
    };

    private void _nfcEeClose(boolean checkPid, int callingPid) throws IOException {
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
            if (checkPid && mOpenEe.pid != -1 && callingPid != mOpenEe.pid) {
                throw new SecurityException("Wrong PID");
            }

            mDeviceHost.resetTimeouts();
            mSecureElement.doDisconnect(mOpenEe.handle);
            mOpenEe = null;

            applyRouting();
        }
    }

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
                mDeviceHost.setTimeout(TagTechnology.ISO_DEP, 10000);

                mOpenEe = new OpenSecureElement(getCallingPid(), handle);
                try {
                    b.linkToDeath(mOpenEe, 0);
                } catch (RemoteException e) {
                    mOpenEe.binderDied();
                }

                // Add the calling package to the list of packages that have accessed
                // the secure element.
                for (String packageName : getPackageManager().getPackagesForUid(getCallingUid())) {
                    mSePackages.add(packageName);
                }
           }
        }

        @Override
        public Bundle close() throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);

            Bundle result;
            try {
                _nfcEeClose(true, getCallingPid());
                result = writeNoException();
            } catch (IOException e) {
                result = writeIoException(e);
            }
            return result;
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
        public void authenticate(byte[] token) throws RemoteException {
            NfcService.enforceNfceeAdminPerm(mContext);
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
                    _nfcEeClose(false, -1);
                } catch (IOException e) { /* already closed */ }
            }
        }
    }

    private boolean _enable(boolean oldEnabledState, boolean savePref) {
        boolean isSuccess = mDeviceHost.initialize();
        if (isSuccess) {
            mIsNfcEnabled = true;
            mIsDiscoveryOn = true;

            /* Start polling loop */
            applyRouting();
            synchronized(NfcService.this) {
                boolean zeroclick_on = mPrefs.getBoolean(PREF_ZEROCLICK_ON,
                        ZEROCLICK_ON_DEFAULT);
                if (zeroclick_on) {
                    /* bring up p2p ndef servers */
                    mP2pManager.enableNdefServer();
                    mZeroClickOn = true;
                }
            }
        } else {
            Log.w(TAG, "Error enabling NFC");
            mIsNfcEnabled = false;
        }

        if (savePref) {
            updateNfcOnSetting(oldEnabledState);
        }

        return isSuccess;
    }

    private boolean _disable(boolean oldEnabledState, boolean savePref) {
        /* sometimes mDeviceHost.deinitialize() hangs, watch-dog it */
        WatchDogThread watchDog = new WatchDogThread();
        watchDog.start();

        boolean isSuccess;

        /* tear down the p2p server */
        synchronized(NfcService.this) {
            if (mZeroClickOn) {
                mP2pManager.disableNdefServer();
                mZeroClickOn = false;
            }
        }
        // Stop watchdog if tag present
        // A convenient way to stop the watchdog properly consists of
        // disconnecting the tag. The polling loop shall be stopped before
        // to avoid the tag being discovered again.
        mIsDiscoveryOn = false;
        applyRouting();
        maybeDisconnectTarget();

        isSuccess = mDeviceHost.deinitialize();
        if (DBG) Log.d(TAG, "NFC success of deinitialize = " + isSuccess);
        if (isSuccess) {
            mIsNfcEnabled = false;
            mNfcDispatcher.disableForegroundDispatch();
            mP2pManager.setForegroundMessage(null);
        }

        if (savePref) {
            updateNfcOnSetting(oldEnabledState);
        }

        watchDog.cancel();
        return isSuccess;
    }

    private class WatchDogThread extends Thread {
        boolean mWatchDogCanceled = false;
        @Override
        public void run() {
            boolean slept = false;
            while (!slept) {
                try {
                    Thread.sleep(10000);
                    slept = true;
                } catch (InterruptedException e) { }
            }
            synchronized (this) {
                if (!mWatchDogCanceled) {
                    // Trigger watch-dog
                    Log.e(TAG, "Watch dog triggered");
                    mDeviceHost.doAbort();
                }
            }
        }
        public synchronized void cancel() {
            mWatchDogCanceled = true;
        }
    }

    /** apply NFC discovery and EE routing */
    private synchronized void applyRouting() {
        if (mIsNfcEnabled && mOpenEe == null) {
            if (mScreenUnlocked) {
                if (mEeRoutingState == ROUTE_ON_WHEN_SCREEN_ON) {
                    Log.d(TAG, "NFC-EE routing ON");
                    mDeviceHost.doSelectSecureElement();
                } else {
                    Log.d(TAG, "NFC-EE routing OFF");
                    mDeviceHost.doDeselectSecureElement();
                }
                if (mIsDiscoveryOn) {
                    Log.d(TAG, "NFC-C discovery ON");
                    mDeviceHost.enableDiscovery();
                } else {
                    Log.d(TAG, "NFC-C discovery OFF");
                    mDeviceHost.disableDiscovery();
                }
            } else {
                Log.d(TAG, "NFC-EE routing OFF");
                mDeviceHost.doDeselectSecureElement();
                Log.d(TAG, "NFC-C discovery OFF");
                mDeviceHost.disableDiscovery();
            }
        }
    }

    /** Disconnect any target if present */
    private synchronized void maybeDisconnectTarget() {
        if (mIsNfcEnabled) {
            Iterator<?> iterator = mObjectMap.values().iterator();
            while(iterator.hasNext()) {
                Object object = iterator.next();
                if (object instanceof TagEndpoint) {
                    // Disconnect from tags
                    TagEndpoint tag = (TagEndpoint) object;
                    tag.disconnect();
                } else if(object instanceof NfcDepEndpoint) {
                    // Disconnect from P2P devices
                    NfcDepEndpoint device = (NfcDepEndpoint) object;
                    if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                        // Remote peer is target, request disconnection
                        device.disconnect();
                    } else {
                        // Remote peer is initiator, we cannot disconnect
                        // Just wait for field removal
                    }
                }
                iterator.remove();
            }
        }
    }

    //TODO: dont hardcode this
    private static final byte[][] SE_RESET_APDUS = {
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x07, (byte)0xa0, (byte)0x00, (byte)0x00, (byte)0x04, (byte)0x76, (byte)0x20, (byte)0x10, (byte)0x00},
        {(byte)0x80, (byte)0xe2, (byte)0x01, (byte)0x03, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x07, (byte)0xa0, (byte)0x00, (byte)0x00, (byte)0x04, (byte)0x76, (byte)0x30, (byte)0x30, (byte)0x00},
        {(byte)0x80, (byte)0xb4, (byte)0x00, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
    };

    private void resetSeOnFirstBoot() {
        if (mPrefs.getBoolean(PREF_FIRST_BOOT, true)) {
            Log.i(TAG, "First Boot");
            mPrefsEditor.putBoolean(PREF_FIRST_BOOT, false);
            mPrefsEditor.apply();
            executeSeReset();
        }
    }

    private synchronized void executeSeReset() {
        // TODO: read SE reset list from /system/etc
        //List<byte[]> apdus = readSeResetApdus();
        byte[][]apdus = SE_RESET_APDUS;
        if (apdus == null) {
            return;
        }

        boolean tempEnable = !mIsNfcEnabled;
        if (tempEnable) {
            if (!_enable(false, false)) {
                Log.w(TAG, "Could not enable NFC to reset EE!");
                return;
            }
        }

        Log.i(TAG, "Executing SE Reset Script");
        int handle = mSecureElement.doOpenSecureElementConnection();
        if (handle == 0) {
            Log.e(TAG, "Could not open the secure element!");
            if (tempEnable) {
                _disable(true, false);
            }
            return;
        }

        mDeviceHost.setTimeout(TagTechnology.ISO_DEP, 10000);

        for (byte[] cmd : apdus) {
            mSecureElement.doTransceive(handle, cmd);
        }

        mDeviceHost.resetTimeouts();

        mSecureElement.doDisconnect(handle);

        if (tempEnable) {
            _disable(true, false);
        }
    }

    private List<byte[]> readSeResetApdus() {
        FileInputStream input = null;
        List<byte[]> apdus = null;

        try {
            input = openFileInput(SE_RESET_SCRIPT_FILE_NAME);
            DataInputStream stream = new DataInputStream(input);

            int commandsSize = stream.readInt();
            apdus = new ArrayList<byte[]>(commandsSize);

            for (int i = 0 ; i < commandsSize ; i++) {
                int length = stream.readInt();

                byte[] cmd = new byte[length];

                stream.read(cmd);
                apdus.add(cmd);
            }

            return apdus;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "SE Reset Script not found: " + SE_RESET_SCRIPT_FILE_NAME);
        } catch (IOException e) {
            Log.e(TAG, "SE Reset Script corrupt: ", e);
            apdus = null;
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException e) {
                // Ignore
            }
        }
        return apdus;
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

    synchronized void registerTagObject(TagEndpoint tag) {
        mObjectMap.put(tag.getHandle(), tag);
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

    private void removeSocket(int key) {
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

    public void sendMockNdefTag(NdefMessage msg) {
        sendMessage(MSG_MOCK_NDEF, msg);
    }

    public void sendMeProfile(NdefMessage target, NdefMessage profile) {
        mP2pManager.sendMeProfile(target, profile);
    }

    void sendMessage(int what, Object obj) {
        Message msg = mHandler.obtainMessage();
        msg.what = what;
        msg.obj = obj;
        mHandler.sendMessage(msg);
    }

    final class NfcServiceHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
           switch (msg.what) {
           case MSG_MOCK_NDEF: {
               NdefMessage ndefMsg = (NdefMessage) msg.obj;
               Bundle extras = new Bundle();
               extras.putParcelable(Ndef.EXTRA_NDEF_MSG, ndefMsg);
               extras.putInt(Ndef.EXTRA_NDEF_MAXLENGTH, 0);
               extras.putInt(Ndef.EXTRA_NDEF_CARDSTATE, Ndef.NDEF_MODE_READ_ONLY);
               extras.putInt(Ndef.EXTRA_NDEF_TYPE, Ndef.TYPE_OTHER);
               Tag tag = Tag.createMockTag(new byte[] { 0x00 },
                       new int[] { TagTechnology.NDEF },
                       new Bundle[] { extras });
               Log.d(TAG, "mock NDEF tag, starting corresponding activity");
               Log.d(TAG, tag.toString());
               boolean delivered = mNfcDispatcher.dispatchTag(tag, new NdefMessage[] { ndefMsg });
               if (delivered) {
                   onP2pEnd();
               }
               break;
           }

           case MSG_NDEF_TAG:
               if (DBG) Log.d(TAG, "Tag detected, notifying applications");
               TagEndpoint tag = (TagEndpoint) msg.obj;
               playSound(mStartSound);
               NdefMessage[] ndefMsgs = tag.findAndReadNdef();

               if (ndefMsgs != null) {
                   tag.startPresenceChecking();
                   dispatchTagEndpoint(tag, ndefMsgs);
               } else {
                   // No ndef found or connect failed, just try to reconnect and dispatch
                   if (tag.reconnect()) {
                       tag.startPresenceChecking();
                       dispatchTagEndpoint(tag, null);
                   } else {
                       Log.w(TAG, "Failed to connect to tag");
                       tag.disconnect();
                       playSound(mErrorSound);
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
               if (DBG) Log.d(TAG, "Broadcasting " + ACTION_AID_SELECTED);
               mContext.sendBroadcast(aidIntent, NFCEE_ADMIN_PERM);
               break;

           case MSG_SE_EMV_CARD_REMOVAL:
               if (DBG) Log.d(TAG, "Card Removal message");
               /* Send broadcast */
               Intent cardRemovalIntent = new Intent();
               cardRemovalIntent.setAction(ACTION_EMV_CARD_REMOVAL);
               if (DBG) Log.d(TAG, "Broadcasting " + ACTION_EMV_CARD_REMOVAL);
               mContext.sendBroadcast(cardRemovalIntent, NFCEE_ADMIN_PERM);
               break;

           case MSG_SE_APDU_RECEIVED:
               if (DBG) Log.d(TAG, "APDU Received message");
               byte[] apduBytes = (byte[]) msg.obj;
               /* Send broadcast */
               Intent apduReceivedIntent = new Intent();
               apduReceivedIntent.setAction(ACTION_APDU_RECEIVED);
               if (apduBytes != null && apduBytes.length > 0) {
                 apduReceivedIntent.putExtra(EXTRA_APDU_BYTES, apduBytes);
               }
               if (DBG) Log.d(TAG, "Broadcasting " + ACTION_APDU_RECEIVED);
               mContext.sendBroadcast(apduReceivedIntent, NFCEE_ADMIN_PERM);
               break;

           case MSG_SE_MIFARE_ACCESS:
               if (DBG) Log.d(TAG, "MIFARE access message");
               /* Send broadcast */
               byte[] mifareCmd = (byte[]) msg.obj;
               Intent mifareAccessIntent = new Intent();
               mifareAccessIntent.setAction(ACTION_MIFARE_ACCESS_DETECTED);
               if (mifareCmd != null && mifareCmd.length > 1) {
                 int mifareBlock = mifareCmd[1] & 0xff;
                 if (DBG) Log.d(TAG, "Mifare Block=" + mifareBlock);
                 mifareAccessIntent.putExtra(EXTRA_MIFARE_BLOCK, mifareBlock);
               }
               if (DBG) Log.d(TAG, "Broadcasting " + ACTION_MIFARE_ACCESS_DETECTED);
               mContext.sendBroadcast(mifareAccessIntent, NFCEE_ADMIN_PERM);
               break;

           case MSG_LLCP_LINK_ACTIVATION:
               llcpActivated((NfcDepEndpoint) msg.obj);
               break;

           case MSG_LLCP_LINK_DEACTIVATED:
               NfcDepEndpoint device = (NfcDepEndpoint) msg.obj;

               Log.d(TAG, "LLCP Link Deactivated message. Restart polling loop.");
               synchronized (NfcService.this) {
                   /* Check if the device has been already unregistered */
                   if (mObjectMap.remove(device.getHandle()) != null) {
                       /* Disconnect if we are initiator */
                       if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                           if (DBG) Log.d(TAG, "disconnecting from target");
                           /* Restart polling loop */
                           device.disconnect();
                       } else {
                           if (DBG) Log.d(TAG, "not disconnecting from initiator");
                       }
                   }
               }

               mP2pManager.llcpDeactivated();
               break;

           case MSG_TARGET_DESELECTED:
               /* Broadcast Intent Target Deselected */
               if (DBG) Log.d(TAG, "Target Deselected");
               Intent intent = new Intent();
               intent.setAction(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
               if (DBG) Log.d(TAG, "Broadcasting Intent");
               mContext.sendOrderedBroadcast(intent, NFC_PERM);
               break;

           case MSG_SE_FIELD_ACTIVATED:{
               if (DBG) Log.d(TAG, "SE FIELD ACTIVATED");
               Intent eventFieldOnIntent = new Intent();
               eventFieldOnIntent.setAction(ACTION_RF_FIELD_ON_DETECTED);
               mContext.sendBroadcast(eventFieldOnIntent, NFCEE_ADMIN_PERM);
               break;
           }

           case MSG_SE_FIELD_DEACTIVATED:{
               if (DBG) Log.d(TAG, "SE FIELD DEACTIVATED");
               Intent eventFieldOffIntent = new Intent();
               eventFieldOffIntent.setAction(ACTION_RF_FIELD_OFF_DETECTED);
               mContext.sendBroadcast(eventFieldOffIntent, NFCEE_ADMIN_PERM);
               break;
           }

           default:
               Log.e(TAG, "Unknown message received");
               break;
           }
        }

        private boolean llcpActivated(NfcDepEndpoint device) {
            Log.d(TAG, "LLCP Activation message");

            if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_TARGET");
                if (device.connect()) {
                    /* Check LLCP compliancy */
                    if (mDeviceHost.doCheckLlcp()) {
                        /* Activate LLCP Link */
                        if (mDeviceHost.doActivateLlcp()) {
                            if (DBG) Log.d(TAG, "Initiator Activate LLCP OK");
                            // Register P2P device
                            mObjectMap.put(device.getHandle(), device);
                            // TODO this should be done decently instead, not depend on the bool
                            if (mZeroClickOn) {
                                mP2pManager.llcpActivated();
                            }
                            return true;
                        } else {
                            /* should not happen */
                            Log.w(TAG, "Initiator LLCP activation failed. Disconnect.");
                            device.disconnect();
                        }
                    } else {
                        if (DBG) Log.d(TAG, "Remote Target does not support LLCP. Disconnect.");
                        device.disconnect();
                    }
                } else {
                    if (DBG) Log.d(TAG, "Cannot connect remote Target. Polling loop restarted.");
                    /*
                     * The polling loop should have been restarted in failing
                     * doConnect
                     */
                }
            } else if (device.getMode() == NfcDepEndpoint.MODE_P2P_INITIATOR) {
                if (DBG) Log.d(TAG, "NativeP2pDevice.MODE_P2P_INITIATOR");
                /* Check LLCP compliancy */
                if (mDeviceHost.doCheckLlcp()) {
                    /* Activate LLCP Link */
                    if (mDeviceHost.doActivateLlcp()) {
                        if (DBG) Log.d(TAG, "Target Activate LLCP OK");
                        // Register P2P device
                        mObjectMap.put(device.getHandle(), device);
                        // TODO this should be done decently instead, not depend on the bool
                        if (mZeroClickOn) {
                            mP2pManager.llcpActivated();
                        }
                        return true;
                    }
                } else {
                    Log.w(TAG, "checkLlcp failed");
                }
            }

            return false;
        }

        private void dispatchTagEndpoint(TagEndpoint tagEndpoint, NdefMessage[] msgs) {
            Tag tag = new Tag(tagEndpoint.getUid(), tagEndpoint.getTechList(),
                    tagEndpoint.getTechExtras(), tagEndpoint.getHandle(), mNfcTagService);
            registerTagObject(tagEndpoint);
            if (!mNfcDispatcher.dispatchTag(tag, msgs)) {
                unregisterObject(tagEndpoint.getHandle());
                playSound(mErrorSound);
            } else {
                playSound(mEndSound);
            }
        }
    }

    private NfcServiceHandler mHandler = new NfcServiceHandler();

    private class EnableDisableDiscoveryTask extends AsyncTask<Boolean, Void, Void> {
        @Override
        protected Void doInBackground(Boolean... params) {
            if (DBG) Log.d(TAG, "EnableDisableDiscoveryTask: enable = " + params[0]);

            if (params != null && params.length > 0 && params[0]) {
                synchronized (NfcService.this) {
                    if (!mScreenUnlocked) {
                        mScreenUnlocked = true;
                        applyRouting();
                    } else {
                        if (DBG) Log.d(TAG, "Ignoring enable request");
                    }
                }
            } else {
                mWakeLock.acquire();
                synchronized (NfcService.this) {
                    if (mScreenUnlocked) {
                        mScreenUnlocked = false;
                        applyRouting();
                        maybeDisconnectTarget();
                    } else {
                        if (DBG) Log.d(TAG, "Ignoring disable request");
                    }
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
                // Only enable if the screen is unlocked. If the screen is locked
                // Intent.ACTION_USER_PRESENT will be broadcast when the screen is
                // unlocked.
                boolean enable = !mKeyguard.isKeyguardSecure() || !mKeyguard.isKeyguardLocked();

                // Perform discovery enable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(enable);
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                // Perform discovery disable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(Boolean.FALSE);
            } else if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                // The user has unlocked the screen. Enabled!
                new EnableDisableDiscoveryTask().execute(Boolean.TRUE);
            } else if (intent.getAction().equals(ACTION_MASTER_CLEAR_NOTIFICATION)) {
                executeSeReset();
            } else if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)) {
                boolean dataRemoved = intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false);
                if (dataRemoved) {
                    Uri data = intent.getData();
                    if (data == null) return;
                    String packageName = data.getSchemeSpecificPart();

                    synchronized (NfcService.this) {
                        if (mSePackages.contains(packageName)) {
                            executeSeReset();
                            mSePackages.remove(packageName);
                        }
                    }
                }
            }
        }
    };
}
