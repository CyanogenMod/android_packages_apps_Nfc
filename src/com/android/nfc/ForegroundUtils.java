/*
 * Copyright (C) 2014 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.List;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

public class ForegroundUtils extends IProcessObserver.Stub {
    private final String TAG = "ForegroundUtils";
    private final IActivityManager mIActivityManager;

    private final Object mLock = new Object();
    private final SparseArray<Boolean> mForegroundUids = new SparseArray<Boolean>(1);
    private final SparseArray<List<Callback>> mBackgroundCallbacks =
            new SparseArray<List<Callback>>();

    private static class Singleton {
        private static final ForegroundUtils INSTANCE = new ForegroundUtils();
    }

    private ForegroundUtils() {
        mIActivityManager = ActivityManagerNative.getDefault();
        try {
            mIActivityManager.registerProcessObserver(this);
        } catch (RemoteException e) {
            // Should not happen!
            Log.e(TAG, "ForegroundUtils: could not get IActivityManager");
        }
    }

    public interface Callback {
        void onUidToBackground(int uid);
    }

    public static ForegroundUtils getInstance() {
        return Singleton.INSTANCE;
    }

    /**
     * Checks whether the specified UID has any activities running in the foreground,
     * and if it does, registers a callback for when that UID no longer has any foreground
     * activities. This is done atomically, so callers can be ensured that they will
     * get a callback if this method returns true.
     *
     * @param callback Callback to be called
     * @param uid The UID to be checked
     * @return true when the UID has an Activity in the foreground and the callback
     * , false otherwise
     */
    public boolean registerUidToBackgroundCallback(Callback callback, int uid) {
        synchronized (mLock) {
            if (!isInForegroundLocked(uid)) {
                return false;
            }
            // This uid is in the foreground; register callback for when it moves
            // into the background.
            List<Callback> callbacks = mBackgroundCallbacks.get(uid);
            if (callbacks == null) {
                callbacks = new ArrayList<Callback>();
                mBackgroundCallbacks.put(uid, callbacks);
            }
            callbacks.add(callback);
            return true;
        }
    }

    /**
     * @param uid The UID to be checked
     * @return whether the UID has any activities running in the foreground
     */
    public boolean isInForeground(int uid) {
        synchronized (mLock) {
            return isInForegroundLocked(uid);
        }
    }

    boolean isInForegroundLocked(int uid) {
        Boolean inForeground = mForegroundUids.get(uid);
        return inForeground != null ? inForeground.booleanValue() : false;
    }

    void handleUidToBackground(int uid) {
        ArrayList<Callback> pendingCallbacks = null;
        synchronized (mLock) {
            List<Callback> callbacks = mBackgroundCallbacks.get(uid);
            if (callbacks != null) {
                pendingCallbacks = new ArrayList<Callback>(callbacks);
                // Only call them once
                mBackgroundCallbacks.remove(uid);
            }
        }
        // Release lock for callbacks
        if (pendingCallbacks != null) {
            for (Callback callback : pendingCallbacks) {
                callback.onUidToBackground(uid);
            }
        }
    }

    @Override
    public void onForegroundActivitiesChanged(int pid, int uid,
            boolean foregroundActivities) throws RemoteException {
        synchronized (mLock) {
            mForegroundUids.put(uid, foregroundActivities);
        }
        if (!foregroundActivities) {
            handleUidToBackground(uid);
        }
    }


    @Override
    public void onProcessDied(int pid, int uid) throws RemoteException {
        synchronized (mLock) {
            mForegroundUids.remove(uid);
        }
        handleUidToBackground(uid);
    }

    @Override
    public void onProcessStateChanged(int pid, int uid, int procState)
            throws RemoteException {
        // Don't care
    }
}
