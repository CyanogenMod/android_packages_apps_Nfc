package com.android.nfc;

import android.nfc.INfcUnlockHandler;
import android.nfc.Tag;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;

/**
 * Singleton for handling NFC Unlock related logic and state.
 */
class NfcUnlockManager {

    private static final String TAG = "NfcUnlockManager";
    private final HashMap<INfcUnlockHandler, Integer> mUnlockHandlers;

    private int mLockscreenPollMask;

    public static NfcUnlockManager getInstance() {
        return Singleton.INSTANCE;
    }

    synchronized int addUnlockHandler(INfcUnlockHandler unlockHandler, int pollMask) {
        if (mUnlockHandlers.containsKey(unlockHandler)) {
            return mLockscreenPollMask;
        }

        mUnlockHandlers.put(unlockHandler, pollMask);
        return (mLockscreenPollMask |= pollMask);
    }

    synchronized int removeUnlockHandler(IBinder unlockHandler) {
        if (mUnlockHandlers.containsKey(unlockHandler)) {
            mUnlockHandlers.remove(unlockHandler);
            mLockscreenPollMask = recomputePollMask();
        }

        return mLockscreenPollMask;
    }

    synchronized boolean tryUnlock(Tag tag) {
        for (INfcUnlockHandler handler : mUnlockHandlers.keySet()) {
            try {
                if (handler.onUnlockAttempted(tag)) {
                    return true;
                }
            } catch (RemoteException e) {
                // remove handler here? keep a counter of failed attempts and remove at Nth?
                Log.e(TAG, "failed to communicate with unlock handler, skipping", e);
            }
        }

        return false;
    }

    private int recomputePollMask() {
        int pollMask = 0;
        for (int mask : mUnlockHandlers.values()) {
            pollMask |= mask;
        }
        return pollMask;
    }

    synchronized int getLockscreenPollMask() {
        return mLockscreenPollMask;
    }

    synchronized boolean isLockscreenPollingEnabled() {
        return mLockscreenPollMask != 0;
    }

    private static class Singleton {
        private static final NfcUnlockManager INSTANCE = new NfcUnlockManager();
    }

    private NfcUnlockManager() {
        mUnlockHandlers = new HashMap<INfcUnlockHandler, Integer>();
        mLockscreenPollMask = 0;
    }
}

