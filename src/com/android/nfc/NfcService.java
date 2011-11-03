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

import com.android.nfc.DeviceHost.DeviceHostListener;
import com.android.nfc.DeviceHost.LlcpServerSocket;
import com.android.nfc.DeviceHost.LlcpSocket;
import com.android.nfc.DeviceHost.NfcDepEndpoint;
import com.android.nfc.DeviceHost.TagEndpoint;
import com.android.nfc.nxp.NativeNfcManager;
import com.android.nfc.nxp.NativeNfcSecureElement;
import com.android.nfc3.R;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
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
import android.nfc.INdefPushCallback;
import android.nfc.INfcAdapter;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcTag;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.TransceiveResult;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutionException;

public class NfcService extends Application implements DeviceHostListener {
    private static final String ACTION_MASTER_CLEAR_NOTIFICATION = "android.intent.action.MASTER_CLEAR_NOTIFICATION";

    static final boolean DBG = true;
    static final String TAG = "NfcService";

    public static final String SERVICE_NAME = "nfc";

    private static final String NFC_PERM = android.Manifest.permission.NFC;
    private static final String NFC_PERM_ERROR = "NFC permission required";
    private static final String ADMIN_PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ADMIN_PERM_ERROR = "WRITE_SECURE_SETTINGS permission required";
    private static final String NFCEE_ADMIN_PERM = "com.android.nfc.permission.NFCEE_ADMIN";
    private static final String NFCEE_ADMIN_PERM_ERROR = "NFCEE_ADMIN permission required";

    public static final String PREF = "NfcServicePrefs";

    private static final String PREF_NFC_ON = "nfc_on";
    private static final boolean NFC_ON_DEFAULT = true;
    private static final String PREF_NDEF_PUSH_ON = "ndef_push_on";
    private static final boolean NDEF_PUSH_ON_DEFAULT = true;

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

    static final int TASK_ENABLE = 1;
    static final int TASK_DISABLE = 2;
    static final int TASK_BOOT = 3;
    static final int TASK_EE_WIPE = 4;

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

    //TODO: dont hardcode this
    private static final byte[][] EE_WIPE_APDUS = {
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x07, (byte)0xa0, (byte)0x00,
                (byte)0x00, (byte)0x04, (byte)0x76, (byte)0x20, (byte)0x10, (byte)0x00},
        {(byte)0x80, (byte)0xe2, (byte)0x01, (byte)0x03, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x07, (byte)0xa0, (byte)0x00,
                (byte)0x00, (byte)0x04, (byte)0x76, (byte)0x30, (byte)0x30, (byte)0x00},
        {(byte)0x80, (byte)0xb4, (byte)0x00, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
    };

    // NFC Execution Environment
    // fields below are protected by this
    private NativeNfcSecureElement mSecureElement;
    private OpenSecureElement mOpenEe;  // null when EE closed
    private int mEeRoutingState;  // contactless interface routing

    // fields below must be used only on the UI thread and therefore aren't synchronized
    boolean mP2pStarted = false;

    // fields below are used in multiple threads and protected by synchronized(this)
    private final HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();
    private HashSet<String> mSePackages = new HashSet<String>();
    private boolean mIsScreenUnlocked;
    private boolean mIsNdefPushEnabled;

    // mState is protected by this, however it is only modified in onCreate()
    // and the default AsyncTask thread so it is read unprotected from that
    // thread
    int mState;  // one of NfcAdapter.STATE_ON, STATE_TURNING_ON, etc

    // fields below are final after onCreate()
    Context mContext;
    private DeviceHost mDeviceHost;
    private SharedPreferences mPrefs;
    private SharedPreferences.Editor mPrefsEditor;
    private PowerManager.WakeLock mWakeLock;
    int mStartSound;
    int mEndSound;
    int mErrorSound;
    SoundPool mSoundPool; // playback synchronized on this
    P2pLinkManager mP2pLinkManager;
    TagService mNfcTagService;
    NfcAdapterService mNfcAdapter;
    NfcAdapterExtrasService mExtrasService;
    boolean mIsAirplaneSensitive;
    boolean mIsAirplaneToggleable;

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

        mNfcTagService = new TagService();
        mNfcAdapter = new NfcAdapterService();
        mExtrasService = new NfcAdapterExtrasService();

        Log.i(TAG, "Starting NFC service");

        sService = this;

        mSoundPool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
        mStartSound = mSoundPool.load(this, R.raw.start, 1);
        mEndSound = mSoundPool.load(this, R.raw.end, 1);
        mErrorSound = mSoundPool.load(this, R.raw.error, 1);

        mContext = this;
        mDeviceHost = new NativeNfcManager(this, this);

        mP2pLinkManager = new P2pLinkManager(mContext);
        mNfcDispatcher = new NfcDispatcher(this, mP2pLinkManager);

        mSecureElement = new NativeNfcSecureElement();
        mEeRoutingState = ROUTE_OFF;

        mPrefs = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mPrefsEditor = mPrefs.edit();

        mState = NfcAdapter.STATE_OFF;
        mIsNdefPushEnabled = mPrefs.getBoolean(PREF_NDEF_PUSH_ON, NDEF_PUSH_ON_DEFAULT);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NfcService");
        mKeyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        mIsScreenUnlocked = pm.isScreenOn() && !mKeyguard.isKeyguardLocked();

        ServiceManager.addService(SERVICE_NAME, mNfcAdapter);

        IntentFilter filter = new IntentFilter(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ACTION_MASTER_CLEAR_NOTIFICATION);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerForAirplaneMode(filter);
        registerReceiver(mReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");

        registerReceiver(mReceiver, filter);

        new EnableDisableTask().execute(TASK_BOOT);  // do blocking boot tasks
    }

    void registerForAirplaneMode(IntentFilter filter) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String airplaneModeRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_RADIOS);
        final String toggleableRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS);

        mIsAirplaneSensitive = airplaneModeRadios == null ? true :
                airplaneModeRadios.contains(Settings.System.RADIO_NFC);
        mIsAirplaneToggleable = toggleableRadios == null ? false :
            toggleableRadios.contains(Settings.System.RADIO_NFC);

        if (mIsAirplaneSensitive) {
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        }
    }

    /**
     * Manages tasks that involve turning on/off the NFC controller.
     *
     * <p>All work that might turn the NFC adapter on or off must be done
     * through this task, to keep the handling of mState simple.
     * In other words, mState is only modified in these tasks (and we
     * don't need a lock to read it in these tasks).
     *
     * <p>These tasks are all done on the same AsyncTask background
     * thread, so they are serialized. Each task may temporarily transition
     * mState to STATE_TURNING_OFF or STATE_TURNING_ON, but must exit in
     * either STATE_ON or STATE_OFF. This way each task can be guaranteed
     * of starting in either STATE_OFF or STATE_ON, without needing to hold
     * NfcService.this for the entire task.
     *
     * <p>AsyncTask's are also implicitly queued. This is useful for corner
     * cases like turning airplane mode on while TASK_ENABLE is in progress.
     * The TASK_DISABLE triggered by airplane mode will be correctly executed
     * immediately after TASK_ENABLE is complete. This seems like the most sane
     * way to deal with these situations.
     *
     * <p>{@link #TASK_ENABLE} enables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_DISABLE} disables the NFC adapter, without changing
     * preferences
     * <p>{@link #TASK_BOOT} does first boot work and may enable NFC
     * <p>{@link #TASK_EE_WIPE} wipes the Execution Environment, and in the
     * process may temporarily enable the NFC adapter
     */
    class EnableDisableTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            // Sanity check mState
            switch (mState) {
                case NfcAdapter.STATE_TURNING_OFF:
                case NfcAdapter.STATE_TURNING_ON:
                    Log.e(TAG, "Processing EnableDisable task " + params[0] + " from bad state " +
                            mState);
                    return null;
            }

            /* AsyncTask sets this thread to THREAD_PRIORITY_BACKGROUND,
             * override with the default. THREAD_PRIORITY_BACKGROUND causes
             * us to service software I2C too slow for firmware download
             * with the NXP PN544.
             * TODO: move this to the DAL I2C layer in libnfc-nxp, since this
             * problem only occurs on I2C platforms using PN544
             */
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);

            switch (params[0].intValue()) {
                case TASK_ENABLE:
                    enableInternal();
                    break;
                case TASK_DISABLE:
                    disableInternal();
                    break;
                case TASK_BOOT:
                    Log.d(TAG,"checking on firmware download");
                    if (mPrefs.getBoolean(PREF_NFC_ON, NFC_ON_DEFAULT) &&
                            !(mIsAirplaneSensitive && isAirplaneModeOn())) {
                        Log.d(TAG,"NFC is on. Doing normal stuff");
                        enableInternal();
                    } else {
                        Log.d(TAG,"NFC is off.  Checking firmware version");
                        mDeviceHost.checkFirmware();
                    }
                    if (mPrefs.getBoolean(PREF_FIRST_BOOT, true)) {
                        Log.i(TAG, "First Boot");
                        mPrefsEditor.putBoolean(PREF_FIRST_BOOT, false);
                        mPrefsEditor.apply();
                        executeEeWipe();
                    }
                    break;
                case TASK_EE_WIPE:
                    executeEeWipe();
                    break;
            }

            // Restore default AsyncTask priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return null;
        }

        /**
         * Enable NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean enableInternal() {
            if (mState == NfcAdapter.STATE_ON) {
                return true;
            }
            Log.i(TAG, "Enabling NFC");
            updateState(NfcAdapter.STATE_TURNING_ON);

            if (!mDeviceHost.initialize()) {
                Log.w(TAG, "Error enabling NFC");
                updateState(NfcAdapter.STATE_OFF);
                return false;
            }

            synchronized(NfcService.this) {
                mObjectMap.clear();

                mP2pLinkManager.enableDisable(mIsNdefPushEnabled, true);
                updateState(NfcAdapter.STATE_ON);
            }

            /* Start polling loop */
            applyRouting();
            return true;
        }

        /**
         * Disable all NFC adapter functions.
         * Does not toggle preferences.
         */
        boolean disableInternal() {
            if (mState == NfcAdapter.STATE_OFF) {
                return true;
            }
            Log.i(TAG, "Disabling NFC");
            updateState(NfcAdapter.STATE_TURNING_OFF);

            /* Sometimes mDeviceHost.deinitialize() hangs, use a watch-dog.
             * Implemented with a new thread (instead of a Handler or AsyncTask),
             * because the UI Thread and AsyncTask thread-pools can also get hung
             * when the NFC controller stops responding */
            WatchDogThread watchDog = new WatchDogThread();
            watchDog.start();

            mP2pLinkManager.enableDisable(false, false);

            // Stop watchdog if tag present
            // A convenient way to stop the watchdog properly consists of
            // disconnecting the tag. The polling loop shall be stopped before
            // to avoid the tag being discovered again.
            applyRouting();
            maybeDisconnectTarget();

            mNfcDispatcher.setForegroundDispatch(null, null, null);

            boolean result = mDeviceHost.deinitialize();
            if (DBG) Log.d(TAG, "mDeviceHost.deinitialize() = " + result);

            watchDog.cancel();

            updateState(NfcAdapter.STATE_OFF);

            return result;
        }

        void executeEeWipe() {
            // TODO: read SE reset list from /system/etc
            byte[][]apdus = EE_WIPE_APDUS;

            boolean tempEnable = mState == NfcAdapter.STATE_OFF;
            if (tempEnable) {
                if (!enableInternal()) {
                    Log.w(TAG, "Could not enable NFC to wipe NFC-EE");
                    return;
                }
            }
            Log.i(TAG, "Executing SE wipe");
            int handle = mSecureElement.doOpenSecureElementConnection();
            if (handle == 0) {
                Log.w(TAG, "Could not open the secure element");
                if (tempEnable) {
                    disableInternal();
                }
                return;
            }

            mDeviceHost.setTimeout(TagTechnology.ISO_DEP, 10000);

            for (byte[] cmd : apdus) {
                byte[] resp = mSecureElement.doTransceive(handle, cmd);
                if (resp == null) {
                    Log.w(TAG, "Transceive failed, could not wipe NFC-EE");
                    break;
                }
            }

            mDeviceHost.resetTimeouts();
            mSecureElement.doDisconnect(handle);

            if (tempEnable) {
                disableInternal();
            }
        }

        void updateState(int newState) {
            synchronized (this) {
                if (newState == mState) {
                    return;
                }
                mState = newState;
                Intent intent = new Intent(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
                intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(NfcAdapter.EXTRA_ADAPTER_STATE, mState);
                mContext.sendBroadcast(intent);
            }
        }
    }

    void saveNfcOnSetting(boolean on) {
        synchronized (NfcService.this) {
            mPrefsEditor.putBoolean(PREF_NFC_ON, on);
            mPrefsEditor.apply();
        }
    }

    void playSound(int sound) {
        synchronized (this) {
            mSoundPool.play(sound, 1.0f, 1.0f, 0, 0, 1.0f);
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // NFC application is persistent, it should not be destroyed by framework
        Log.wtf(TAG, "NFC service is under attack!");
    }

    final class NfcAdapterService extends INfcAdapter.Stub {
        @Override
        public boolean enable() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);

            saveNfcOnSetting(true);
            if (mIsAirplaneSensitive && isAirplaneModeOn() && !mIsAirplaneToggleable) {
                Log.i(TAG, "denying enable() request (airplane mode)");
                return false;
            }
            new EnableDisableTask().execute(TASK_ENABLE);

            return true;
        }

        @Override
        public boolean disable() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);

            saveNfcOnSetting(false);
            new EnableDisableTask().execute(TASK_DISABLE);

            return true;
        }

        @Override
        public boolean isNdefPushEnabled() throws RemoteException {
            synchronized (NfcService.this) {
                return mIsNdefPushEnabled;
            }
        }

        @Override
        public boolean enableNdefPush() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);
            synchronized(NfcService.this) {
                if (mIsNdefPushEnabled) {
                    return true;
                }
                Log.i(TAG, "enabling NDEF Push");
                mPrefsEditor.putBoolean(PREF_NDEF_PUSH_ON, true);
                mPrefsEditor.apply();
                mIsNdefPushEnabled = true;
                if (isNfcEnabled()) {
                    mP2pLinkManager.enableDisable(true, true);
                }
            }
            return true;
        }

        @Override
        public boolean disableNdefPush() throws RemoteException {
            NfcService.enforceAdminPerm(mContext);
            synchronized(NfcService.this) {
                if (!mIsNdefPushEnabled) {
                    return true;
                }
                Log.i(TAG, "disabling NDEF Push");
                mPrefsEditor.putBoolean(PREF_NDEF_PUSH_ON, false);
                mPrefsEditor.apply();
                mIsNdefPushEnabled = false;
                if (isNfcEnabled()) {
                    mP2pLinkManager.enableDisable(false, true);
                }
            }
            return true;
        }

        @Override
        public void setForegroundDispatch(PendingIntent intent,
                IntentFilter[] filters, TechListParcel techListsParcel) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            // Short-cut the disable path
            if (intent == null && filters == null && techListsParcel == null) {
                mNfcDispatcher.setForegroundDispatch(null, null, null);
                return;
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

            mNfcDispatcher.setForegroundDispatch(intent, filters, techLists);
        }

        @Override
        public void setForegroundNdefPush(NdefMessage msg, INdefPushCallback callback) {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            mP2pLinkManager.setNdefToSend(msg, callback);
        }

        @Override
        public INfcTag getNfcTagInterface() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);
            return mNfcTagService;
        }

        @Override
        public INfcAdapterExtras getNfcAdapterExtrasInterface() {
            NfcService.enforceNfceeAdminPerm(mContext);
            return mExtrasService;
        }

        @Override
        public int getState() throws RemoteException {
            synchronized (NfcService.this) {
                return mState;
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            NfcService.this.dump(fd, pw, args);
        }
    };

    final class TagService extends INfcTag.Stub {
        @Override
        public int close(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;

            if (!isNfcEnabled()) {
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

            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return false;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            int[] ndefInfo = new int[2];
            if (tag == null) {
                return false;
            }
            return tag.checkNdef(ndefInfo);
        }

        @Override
        public TransceiveResult transceive(int nativeHandle, byte[] data, boolean raw)
                throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (TagEndpoint) findObject(nativeHandle);
            if (tag != null) {
                // Check if length is within limits
                if (data.length > getMaxTransceiveLength(tag.getConnectedTechnology())) {
                    return new TransceiveResult(TransceiveResult.RESULT_EXCEEDED_LENGTH, null);
                }
                int[] targetLost = new int[1];
                response = tag.transceive(data, raw, targetLost);
                int result;
                if (response != null) {
                    result = TransceiveResult.RESULT_SUCCESS;
                } else if (targetLost[0] == 1) {
                    result = TransceiveResult.RESULT_TAGLOST;
                } else {
                    result = TransceiveResult.RESULT_FAILURE;
                }
                return new TransceiveResult(result, response);
            }
            return null;
        }

        @Override
        public NdefMessage ndefRead(int nativeHandle) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            TagEndpoint tag;

            // Check if NFC is enabled
            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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
            if (!isNfcEnabled()) {
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
                        tag.getTechExtras(), tag.getHandle(), this);
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

        @Override
        public boolean canMakeReadOnly(int ndefType) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            return mDeviceHost.canMakeReadOnly(ndefType);
        }

        @Override
        public int getMaxTransceiveLength(int tech) throws RemoteException {
            mContext.enforceCallingOrSelfPermission(NFC_PERM, NFC_PERM_ERROR);

            return mDeviceHost.getMaxTransceiveLength(tech);
        }
    };

    private void _nfcEeClose(boolean checkPid, int callingPid) throws IOException {
        // Blocks until a pending open() or transceive() times out.
        //TODO: This is incorrect behavior - the close should interrupt pending
        // operations. However this is not supported by current hardware.

        synchronized(NfcService.this) {
            if (!isNfcEnabled()) {
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

    final class NfcAdapterExtrasService extends INfcAdapterExtras.Stub {
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
                if (!isNfcEnabled()) {
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
                if (!isNfcEnabled()) {
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

    boolean isNfcEnabled() {
        synchronized (this) {
            return mState == NfcAdapter.STATE_ON;
        }
    }

    class WatchDogThread extends Thread {
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
    void applyRouting() {
        synchronized (this) {
            if (!isNfcEnabled() || mOpenEe != null) {
                return;
            }
            if (mIsScreenUnlocked) {
                if (mEeRoutingState == ROUTE_ON_WHEN_SCREEN_ON) {
                    Log.d(TAG, "NFC-EE routing ON");
                    mDeviceHost.doSelectSecureElement();
                } else {
                    Log.d(TAG, "NFC-EE routing OFF");
                    mDeviceHost.doDeselectSecureElement();
                }
                Log.d(TAG, "NFC-C polling ON");
                mDeviceHost.enableDiscovery();
            } else {
                Log.d(TAG, "NFC-EE routing OFF");
                mDeviceHost.doDeselectSecureElement();
                Log.d(TAG, "NFC-C polling OFF");
                mDeviceHost.disableDiscovery();
            }
        }
    }

    /** Disconnect any target if present */
    void maybeDisconnectTarget() {
        if (!isNfcEnabled()) {
            return;
        }
        Object[] objectsToDisconnect;
        synchronized (this) {
            Object[] objectValues = mObjectMap.values().toArray();
            // Copy the array before we clear mObjectMap,
            // just in case the HashMap values are backed by the same array
            objectsToDisconnect = Arrays.copyOf(objectValues, objectValues.length);
            mObjectMap.clear();
        }
        for (Object o : objectsToDisconnect) {
            if (DBG) Log.d(TAG, "disconnecting " + o.getClass().getName());
            if (o instanceof TagEndpoint) {
                // Disconnect from tags
                TagEndpoint tag = (TagEndpoint) o;
                tag.disconnect();
            } else if (o instanceof NfcDepEndpoint) {
                // Disconnect from P2P devices
                NfcDepEndpoint device = (NfcDepEndpoint) o;
                if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                    // Remote peer is target, request disconnection
                    device.disconnect();
                } else {
                    // Remote peer is initiator, we cannot disconnect
                    // Just wait for field removal
                }
            }
        }
    }

    Object findObject(int key) {
        synchronized (this) {
            Object device = mObjectMap.get(key);
            if (device == null) {
                Log.w(TAG, "Handle not found");
            }
            return device;
        }
    }

    void registerTagObject(TagEndpoint tag) {
        synchronized (this) {
            mObjectMap.put(tag.getHandle(), tag);
        }
    }

    void unregisterObject(int handle) {
        synchronized (this) {
            mObjectMap.remove(handle);
        }
    }

    /** For use by code in this process */
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
            throws IOException, LlcpException {
        return mDeviceHost.createLlcpSocket(sap, miu, rw, linearBufferLength);
    }

    /** For use by code in this process */
    public LlcpServerSocket createLlcpServerSocket(int sap, String sn, int miu, int rw,
            int linearBufferLength) throws IOException, LlcpException {
        return mDeviceHost.createLlcpServerSocket(sap, sn, miu, rw, linearBufferLength);
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
                    boolean delivered = mNfcDispatcher.dispatchTag(tag,
                            new NdefMessage[] { ndefMsg });
                    if (delivered) {
                        playSound(mEndSound);
                    } else {
                        playSound(mErrorSound);
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
                        if (tag.reconnect()) {
                            tag.startPresenceChecking();
                            dispatchTagEndpoint(tag, null);
                        } else {
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
                    sendSeBroadcast(aidIntent);
                    break;

                case MSG_SE_EMV_CARD_REMOVAL:
                    if (DBG) Log.d(TAG, "Card Removal message");
                    /* Send broadcast */
                    Intent cardRemovalIntent = new Intent();
                    cardRemovalIntent.setAction(ACTION_EMV_CARD_REMOVAL);
                    if (DBG) Log.d(TAG, "Broadcasting " + ACTION_EMV_CARD_REMOVAL);
                    sendSeBroadcast(cardRemovalIntent);
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
                    sendSeBroadcast(apduReceivedIntent);
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
                    sendSeBroadcast(mifareAccessIntent);
                    break;

                case MSG_LLCP_LINK_ACTIVATION:
                    llcpActivated((NfcDepEndpoint) msg.obj);
                    break;

                case MSG_LLCP_LINK_DEACTIVATED:
                    NfcDepEndpoint device = (NfcDepEndpoint) msg.obj;
                    boolean needsDisconnect = false;

                    Log.d(TAG, "LLCP Link Deactivated message. Restart polling loop.");
                    synchronized (NfcService.this) {
                        /* Check if the device has been already unregistered */
                        if (mObjectMap.remove(device.getHandle()) != null) {
                            /* Disconnect if we are initiator */
                            if (device.getMode() == NfcDepEndpoint.MODE_P2P_TARGET) {
                                if (DBG) Log.d(TAG, "disconnecting from target");
                                needsDisconnect = true;
                            } else {
                                if (DBG) Log.d(TAG, "not disconnecting from initiator");
                            }
                        }
                    }
                    if (needsDisconnect) {
                        device.disconnect();  // restarts polling loop
                    }

                    mP2pLinkManager.onLlcpDeactivated();
                    break;

                case MSG_TARGET_DESELECTED:
                    /* Broadcast Intent Target Deselected */
                    if (DBG) Log.d(TAG, "Target Deselected");
                    Intent intent = new Intent();
                    intent.setAction(NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION);
                    if (DBG) Log.d(TAG, "Broadcasting Intent");
                    mContext.sendOrderedBroadcast(intent, NFC_PERM);
                    break;

                case MSG_SE_FIELD_ACTIVATED: {
                    if (DBG) Log.d(TAG, "SE FIELD ACTIVATED");
                    Intent eventFieldOnIntent = new Intent();
                    eventFieldOnIntent.setAction(ACTION_RF_FIELD_ON_DETECTED);
                    sendSeBroadcast(eventFieldOnIntent);
                    break;
                }

                case MSG_SE_FIELD_DEACTIVATED: {
                    if (DBG) Log.d(TAG, "SE FIELD DEACTIVATED");
                    Intent eventFieldOffIntent = new Intent();
                    eventFieldOffIntent.setAction(ACTION_RF_FIELD_OFF_DETECTED);
                    sendSeBroadcast(eventFieldOffIntent);
                    break;
                }

                default:
                    Log.e(TAG, "Unknown message received");
                    break;
            }
        }

        private void sendSeBroadcast(Intent intent) {
            mNfcDispatcher.resumeAppSwitches();
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            mContext.sendBroadcast(intent, NFCEE_ADMIN_PERM);
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
                            boolean isZeroClickOn;
                            synchronized (NfcService.this) {
                                // Register P2P device
                                mObjectMap.put(device.getHandle(), device);
                            }
                            mP2pLinkManager.onLlcpActivated();
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
                        boolean isZeroClickOn;
                        synchronized (NfcService.this) {
                            // Register P2P device
                            mObjectMap.put(device.getHandle(), device);
                        }
                        mP2pLinkManager.onLlcpActivated();
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

    class EnableDisableDiscoveryTask extends AsyncTask<Boolean, Void, Void> {
        @Override
        protected Void doInBackground(Boolean... params) {
            if (DBG) Log.d(TAG, "EnableDisableDiscoveryTask: enable = " + params[0]);

            if (params != null && params.length > 0 && params[0]) {
                synchronized (NfcService.this) {
                    if (!mIsScreenUnlocked) {
                        mIsScreenUnlocked = true;
                        applyRouting();
                    } else {
                        if (DBG) Log.d(TAG, "Ignoring enable request");
                    }
                }
            } else {
                mWakeLock.acquire();
                synchronized (NfcService.this) {
                    if (mIsScreenUnlocked) {
                        mIsScreenUnlocked = false;
//                        applyRouting();
                        /*
                         * TODO undo this after the LLCP stack is fixed.
                         * This is done locally here since the LLCP stack is still using
                         * globals without holding any locks, and if we attempt to change
                         * the NFCEE routing while the target is still connected (and it's
                         * a P2P target) the async LLCP callbacks will crash since the routing
                         * manipulation code is overwriting globals it relies on. This hack should
                         * be removed when the LLCP stack is fixed.
                         */
                        if (isNfcEnabled() && mOpenEe == null) {
                            Log.d(TAG, "NFC-C polling OFF");
                            mDeviceHost.disableDiscovery();
                            maybeDisconnectTarget();
                            Log.d(TAG, "NFC-EE routing OFF");
                            mDeviceHost.doDeselectSecureElement();
                        }
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
            String action = intent.getAction();
            if (action.equals(
                    NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION)) {
                if (DBG) Log.d(TAG, "INERNAL_TARGET_DESELECTED_ACTION");

                /* Restart polling loop for notification */
                applyRouting();

            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // Only enable if the screen is unlocked. If the screen is locked
                // Intent.ACTION_USER_PRESENT will be broadcast when the screen is
                // unlocked.
                boolean enable = !mKeyguard.isKeyguardLocked();

                // Perform discovery enable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(enable);
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                // Perform discovery disable in thread to protect against ANR when the
                // NFC stack wedges. This is *not* the correct way to fix this issue -
                // configuration of the local NFC adapter should be very quick and should
                // be safe on the main thread, and the NFC stack should not wedge.
                new EnableDisableDiscoveryTask().execute(Boolean.FALSE);
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                // The user has unlocked the screen. Enabled!
                new EnableDisableDiscoveryTask().execute(Boolean.TRUE);
            } else if (action.equals(ACTION_MASTER_CLEAR_NOTIFICATION)) {
                EnableDisableTask eeWipeTask = new EnableDisableTask();
                eeWipeTask.execute(TASK_EE_WIPE);
                try {
                    eeWipeTask.get();  // blocks until EE wipe is complete
                } catch (ExecutionException e) {
                    Log.w(TAG, "failed to wipe NFC-EE");
                } catch (InterruptedException e) {
                    Log.w(TAG, "failed to wipe NFC-EE");
                }
            } else if (action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                boolean dataRemoved = intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false);
                if (dataRemoved) {
                    Uri data = intent.getData();
                    if (data == null) return;
                    String packageName = data.getSchemeSpecificPart();

                    synchronized (NfcService.this) {
                        if (mSePackages.contains(packageName)) {
                            new EnableDisableTask().execute(TASK_EE_WIPE);
                            mSePackages.remove(packageName);
                        }
                    }
                }
            } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                boolean isAirplaneModeOn = intent.getBooleanExtra("state", false);
                // Query the airplane mode from Settings.System just to make sure that
                // some random app is not sending this intent
                if (isAirplaneModeOn != isAirplaneModeOn()) {
                    return;
                }
                if (!mIsAirplaneSensitive) {
                    return;
                }
                if (isAirplaneModeOn) {
                    new EnableDisableTask().execute(TASK_DISABLE);
                } else if (!isAirplaneModeOn && mPrefs.getBoolean(PREF_NFC_ON, NFC_ON_DEFAULT)) {
                    new EnableDisableTask().execute(TASK_ENABLE);
                }
            }
        }
    };

    /** Returns true if airplane mode is currently on */
    boolean isAirplaneModeOn() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    /** for debugging only - no il8n */
    static String stateToString(int state) {
        switch (state) {
            case NfcAdapter.STATE_OFF:
                return "off";
            case NfcAdapter.STATE_TURNING_ON:
                return "turning on";
            case NfcAdapter.STATE_ON:
                return "on";
            case NfcAdapter.STATE_TURNING_OFF:
                return "turning off";
            default:
                return "<error>";
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump nfc from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }

        synchronized (this) {
            pw.println("mState=" + stateToString(mState));
            pw.println("mIsZeroClickRequested=" + mIsNdefPushEnabled);
            pw.println("mIsScreenUnlocked=" + mIsScreenUnlocked);
            pw.println("mIsAirplaneSensitive=" + mIsAirplaneSensitive);
            pw.println("mIsAirplaneToggleable=" + mIsAirplaneToggleable);
            mP2pLinkManager.dump(fd, pw, args);
            pw.println(mDeviceHost.dump());
        }
    }
}
