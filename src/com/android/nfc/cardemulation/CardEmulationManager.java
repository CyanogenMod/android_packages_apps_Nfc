package com.android.nfc.cardemulation;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.nfc.INfcCardEmulation;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.nfc.NfcPermissions;
import com.android.nfc.cardemulation.RegisteredServicesCache.Callback;
import com.google.android.collect.Maps;

/**
 * CardEmulationManager is the central entity
 * responsible for delegating to individual components
 * implementing card emulation:
 * - RegisteredServicesCache keeping track of HCE and SE services on the device
 * - RegisteredAidCache keeping track of AIDs registered by those services and manages
 *   the routing table in the NFCC.
 * - HostEmulationManager handles incoming APDUs for the host and forwards to HCE
 *   services as necessary.
 */
public class CardEmulationManager implements Callback {
    static final String TAG = "CardEmulationManager";
    static final boolean DBG = true;

    final RegisteredAidCache mAidCache;
    final RegisteredServicesCache mServiceCache;
    final HostEmulationManager mHostEmulationManager;
    final SettingsObserver mSettingsObserver;
    final Context mContext;
    final CardEmulationInterface mCardEmulationInterface;
    final Handler mHandler = new Handler(Looper.getMainLooper());

    final Object mLock = new Object();
    // Variables below protected by mLock
    final HashMap<String, ComponentName> mCategoryDefaults =
            Maps.newHashMap();


    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            // Do it just for the current user. If it was in fact
            // a change made for another user, we'll sync it down
            // on user switch.
            int currentUser = ActivityManager.getCurrentUser();
            loadDefaultsFromSettings(currentUser, false);
        }
    };

    void loadDefaultsFromSettings(int userId, boolean alwaysNotify) {
        // Load current payment default from settings
        String name = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                userId);
        ComponentName newDefault = name != null ? ComponentName.unflattenFromString(name) : null;
        ComponentName oldDefault;
        synchronized (mLock) {
            oldDefault = mCategoryDefaults.put(CardEmulation.CATEGORY_PAYMENT,
                    newDefault);
        }
        Log.d(TAG, "Updating default component to: " + (name != null ?
                ComponentName.unflattenFromString(name) : "null"));
        if (newDefault != oldDefault || alwaysNotify) {
            mAidCache.onDefaultChanged(CardEmulation.CATEGORY_PAYMENT, newDefault);
            mHostEmulationManager.onDefaultChanged(CardEmulation.CATEGORY_PAYMENT, newDefault);
        }
    }

    public CardEmulationManager(Context context) {
        mContext = context;
        mCardEmulationInterface = new CardEmulationInterface();
        mAidCache = new RegisteredAidCache(context);
        mHostEmulationManager = new HostEmulationManager(context, mAidCache);
        mServiceCache = new RegisteredServicesCache(context, this);

        mSettingsObserver = new SettingsObserver(mHandler);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT),
                true, mSettingsObserver, UserHandle.USER_ALL);

        // Load defaults
        loadDefaultsFromSettings(ActivityManager.getCurrentUser(), true);
        mServiceCache.initialize();
    }

    public INfcCardEmulation getNfcCardEmulationInterface() {
        return mCardEmulationInterface;
    }

    public void onHostCardEmulationActivated() {
        mHostEmulationManager.onHostEmulationActivated();
    }

    public void onHostCardEmulationData(byte[] data) {
        mHostEmulationManager.onHostEmulationData(data);
    }

    public void onHostCardEmulationDeactivated() {
        mHostEmulationManager.onHostEmulationDeactivated();
    }

    public void onOffHostAidSelected() {
        mHostEmulationManager.onOffHostAidSelected();
    }

    public void onUserSwitched(int userId) {
        mServiceCache.invalidateCache(userId);
        loadDefaultsFromSettings(userId, true);
    }

    public void onNfcEnabled() {
        mAidCache.onNfcEnabled();
    }

    public void onNfcDisabled() {
        mAidCache.onNfcDisabled();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mServiceCache.dump(fd, pw, args);
        mAidCache.dump(fd, pw, args);
    }

    @Override
    public void onServicesUpdated(int userId, List<ApduServiceInfo> services) {
        // Verify defaults are still sane
        verifyDefaults(userId, services);
        // Update the AID cache
        mAidCache.onServicesUpdated(userId, services);
    }

    void verifyDefaults(int userId, List<ApduServiceInfo> services) {
        ComponentName defaultPaymentService =
                getDefaultServiceForCategory(userId, CardEmulation.CATEGORY_PAYMENT, false);
        if (DBG) Log.d(TAG, "Current default: " + defaultPaymentService);
        if (defaultPaymentService != null) {
            // Validate the default is still installed and handling payment
            ApduServiceInfo serviceInfo = mServiceCache.getService(userId, defaultPaymentService);
            if (serviceInfo == null || !serviceInfo.hasCategory(CardEmulation.CATEGORY_PAYMENT)) {
                if (serviceInfo == null) {
                    Log.e(TAG, "Default payment service unexpectedly removed.");
                } else if (!serviceInfo.hasCategory(CardEmulation.CATEGORY_PAYMENT)) {
                    if (DBG) Log.d(TAG, "Default payment service had payment category removed");
                }
                int numPaymentServices = 0;
                ComponentName lastFoundPaymentService = null;
                for (ApduServiceInfo service : services) {
                    if (service.hasCategory(CardEmulation.CATEGORY_PAYMENT))  {
                        numPaymentServices++;
                        lastFoundPaymentService = service.getComponent();
                    }
                }
                if (DBG) Log.d(TAG, "Number of payment services is " +
                        Integer.toString(numPaymentServices));
                if (numPaymentServices == 0) {
                    if (DBG) Log.d(TAG, "Default removed, no services left.");
                    // No payment services left, unset default and don't ask the user
                    setDefaultServiceForCategoryChecked(userId, null, CardEmulation.CATEGORY_PAYMENT);
                } else if (numPaymentServices == 1) {
                    // Only one left, automatically make it the default
                    if (DBG) Log.d(TAG, "Default removed, making remaining service default.");
                    setDefaultServiceForCategoryChecked(userId, lastFoundPaymentService,
                            CardEmulation.CATEGORY_PAYMENT);
                } else if (numPaymentServices > 1) {
                    // More than one left, unset default and ask the user if he wants
                    // to set a new one
                    if (DBG) Log.d(TAG, "Default removed, asking user to pick.");
                    setDefaultServiceForCategoryChecked(userId, null,
                            CardEmulation.CATEGORY_PAYMENT);
                    Intent intent = new Intent(mContext, DefaultRemovedActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                }
            } else {
                // Default still exists and handles the category, nothing do
                if (DBG) Log.d(TAG, "Default payment service still ok.");
            }
        } else {
            // A payment service may have been removed, leaving only one;
            // in that case, automatically set that app as default.
            int numPaymentServices = 0;
            ComponentName lastFoundPaymentService = null;
            for (ApduServiceInfo service : services) {
                if (service.hasCategory(CardEmulation.CATEGORY_PAYMENT))  {
                    numPaymentServices++;
                    lastFoundPaymentService = service.getComponent();
                }
            }
            if (numPaymentServices > 1) {
                // More than one service left, leave default unset
                if (DBG) Log.d(TAG, "No default set, more than one service left.");
            } else if (numPaymentServices == 1) {
                // Make single found payment service the default
                if (DBG) Log.d(TAG, "No default set, making single service default.");
                setDefaultServiceForCategoryChecked(userId, lastFoundPaymentService,
                        CardEmulation.CATEGORY_PAYMENT);
            } else {
                // No payment services left, leave default at null
                if (DBG) Log.d(TAG, "No default set, last payment service removed.");
            }
        }
    }

    ComponentName getDefaultServiceForCategory(int userId, String category,
             boolean validateInstalled) {
        if (!CardEmulation.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return null;
        }
        // Load current payment default from settings
        String name = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                userId);
        if (name != null) {
            ComponentName service = ComponentName.unflattenFromString(name);
            if (!validateInstalled || service == null) {
                return service;
            } else {
                return mServiceCache.hasService(userId, service) ? service : null;
             }
        } else {
            return null;
        }
    }

    boolean setDefaultServiceForCategoryChecked(int userId, ComponentName service,
            String category) {
        if (!CardEmulation.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "Not allowing defaults for category " + category);
            return false;
        }
        // TODO Not really nice to be writing to Settings.Secure here...
        // ideally we overlay our local changes over whatever is in
        // Settings.Secure
        if (service == null || mServiceCache.hasService(userId, service)) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                    service != null ? service.flattenToString() : null, userId);
        } else {
            Log.e(TAG, "Could not find default service to make default: " + service);
        }
        return true;
    }

    boolean isServiceRegistered(int userId, ComponentName service) {
        boolean serviceFound = mServiceCache.hasService(userId, service);
        if (!serviceFound) {
            // If we don't know about this service yet, it may have just been enabled
            // using PackageManager.setComponentEnabledSetting(). The PackageManager
            // broadcasts are delayed by 10 seconds in that scenario, which causes
            // calls to our APIs referencing that service to fail.
            // Hence, update the cache in case we don't know about the service.
            if (DBG) Log.d(TAG, "Didn't find passed in service, invalidating cache.");
            mServiceCache.invalidateCache(userId);
        }
        return mServiceCache.hasService(userId, service);
    }

    /**
     * This class implements the application-facing APIs
     * and are called from binder. All calls must be
     * permission-checked.
     *
     */
    final class CardEmulationInterface extends INfcCardEmulation.Stub {
        @Override
        public boolean isDefaultServiceForCategory(int userId, ComponentName service,
                String category) {
            NfcPermissions.enforceUserPermissions(mContext);
            NfcPermissions.validateUserId(userId);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            ComponentName defaultService =
                    getDefaultServiceForCategory(userId, category, true);
            return (defaultService != null && defaultService.equals(service));
        }

        @Override
        public boolean isDefaultServiceForAid(int userId,
                ComponentName service, String aid) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return mAidCache.isDefaultServiceForAid(userId, service, aid);
        }

        @Override
        public boolean setDefaultServiceForCategory(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return setDefaultServiceForCategoryChecked(userId, service, category);
        }

        @Override
        public boolean setDefaultForNextTap(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            mHostEmulationManager.setDefaultForNextTap(service);
            return mAidCache.setDefaultForNextTap(userId, service);
        }

        @Override
        public boolean registerAidGroupForService(int userId,
                ComponentName service, AidGroup aidGroup) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return mServiceCache.registerAidGroupForService(userId, Binder.getCallingUid(), service,
                    aidGroup);
        }

        @Override
        public AidGroup getAidGroupForService(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return null;
            }
            return mServiceCache.getAidGroupForService(userId, Binder.getCallingUid(), service,
                    category);
        }

        @Override
        public boolean removeAidGroupForService(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return mServiceCache.removeAidGroupForService(userId, Binder.getCallingUid(), service,
                    category);
        }

        @Override
        public List<ApduServiceInfo> getServices(int userId, String category)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            return mServiceCache.getServicesForCategory(userId, category);
        }
    };
}