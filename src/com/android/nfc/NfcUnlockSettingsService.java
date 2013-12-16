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

import android.content.Context;
import android.content.Intent;
import android.nfc.INfcUnlockSettings;
import android.nfc.NfcUnlock;
import android.nfc.Tag;
import android.os.RemoteException;

final class NfcUnlockSettingsService extends INfcUnlockSettings.Stub {

    interface NfcUnlockSettingsEventCallback {

        /**
         * Called when NFC Unlock is turned on (enabled=true) or off (enabled=false).
         */
        void onNfcUnlockEnabledChange(boolean enabled);
    }


    private static final String TAG = "NfcUnlockSettingsService";

    private final NfcUnlockManager mUnlockManager;
    private final Context mContext;
    private final DeviceHost mDeviceHost;
    private NfcUnlockSettingsEventCallback mCallback;

    NfcUnlockSettingsService(Context context, DeviceHost deviceHost,
                             NfcUnlockSettingsEventCallback callback) {
        this.mContext = context;
        this.mUnlockManager = new NfcUnlockManager(mContext);
        this.mDeviceHost = deviceHost;
        this.mCallback = callback;
    }

    @Override
    public boolean tryUnlock(int userId, Tag tag) {
        NfcPermissions.enforceAdminPermissions(mContext);
        if (mUnlockManager.canUnlock(userId, tag) && mUnlockManager.getNfcUnlockEnabled(userId)) {
            mContext.sendBroadcast(new Intent().setAction(NfcUnlock.ACTION_NFC_UNLOCK));
            return true;
        }

        return false;
    }

    @Override
    public boolean registerTag(int userId, Tag tag) {
        NfcPermissions.enforceAdminPermissions(mContext);
        return mUnlockManager.registerTag(userId, tag);
    }

    @Override
    public boolean deregisterTag(int userId, long timestamp) {
        NfcPermissions.enforceAdminPermissions(mContext);
        return mUnlockManager.deregisterTag(userId, timestamp);
    }

    @Override
    public long[] getTagRegistryTimes(int userId) {
        NfcPermissions.enforceAdminPermissions(mContext);
        return mUnlockManager.getTagRegistryTimes(userId);
    }

    @Override
    public boolean getNfcUnlockEnabled(int userId) {
        NfcPermissions.enforceUserPermissions(mContext);
        return mUnlockManager.getNfcUnlockEnabled(userId);
    }

    @Override
    public void setNfcUnlockEnabled(int userId, boolean enabled) throws RemoteException {
        NfcPermissions.enforceAdminPermissions(mContext);

        if (enabled) {
            mDeviceHost.enableScreenOffSuspend();
        } else {
            mDeviceHost.disableScreenOffSuspend();
        }

        mCallback.onNfcUnlockEnabledChange(enabled);
        mUnlockManager.setNfcUnlockEnabled(userId, enabled);

    }

    int getRegisteredTechMask(int userId) {
        return mUnlockManager.getRegisteredTechMask(userId);
    }

}
