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

import android.nfc.technology.NfcA;
import android.nfc.technology.NfcB;
import android.nfc.technology.NfcF;
import android.nfc.technology.TagTechnology;
import android.os.Bundle;
import android.util.Log;

/**
 * Native interface to the NFC tag functions
 */
public class NativeNfcTag {
    static final boolean DBG = false;

    private int mHandle;

    private int[] mTechList;
    private Bundle[] mTechExtras;
    private byte[] mPollBytes;
    private byte[] mActivationBytes;
    private byte[] mUid;

    private final String TAG = "NativeNfcTag";

    private PresenceCheckWatchdog mWatchdog;
    class PresenceCheckWatchdog extends Thread {

        private boolean isPresent = true;
        private boolean isRunning = true;

        public void reset() {
            this.interrupt();
        }

        public void end() {
            isRunning = false;
            this.interrupt();
        }

        @Override
        public void run() {
            if (DBG) Log.d(TAG, "Starting background presence check");
            while (isPresent && isRunning) {
                try {
                    Thread.sleep(1000);
                    isPresent = doPresenceCheck();
                } catch (InterruptedException e) {
                    // Activity detected, loop
                }
            }
            // Restart the polling loop if the tag is not here any more
            if (!isPresent) {
                Log.d(TAG, "Tag lost, restarting polling loop");
                doDisconnect();
            }
            if (DBG) Log.d(TAG, "Stopping background presence check");
        }
    }

    private native boolean doConnect();
    public synchronized boolean connect() {
        boolean isSuccess = doConnect();
        if (isSuccess) {
            mWatchdog = new PresenceCheckWatchdog();
            mWatchdog.start();
        }
        return isSuccess;
    }

    native boolean doDisconnect();
    public synchronized boolean disconnect() {
        if (mWatchdog != null) {
            mWatchdog.end();
        }
        return doDisconnect();
    }

    private native byte[] doTransceive(byte[] data);
    public synchronized byte[] transceive(byte[] data) {
        if (mWatchdog != null) {
            mWatchdog.reset();
        }
        return doTransceive(data);
    }

    private native boolean doCheckNdef();
    public synchronized boolean checkNdef() {
        if (mWatchdog != null) {
            mWatchdog.reset();
        }
        return doCheckNdef();
    }

    private native byte[] doRead();
    public synchronized byte[] read() {
        if (mWatchdog != null) {
            mWatchdog.reset();
        }
        return doRead();
    }

    private native boolean doWrite(byte[] buf);
    public synchronized boolean write(byte[] buf) {
        if (mWatchdog != null) {
            mWatchdog.reset();
        }
        return doWrite(buf);
    }

    native boolean doPresenceCheck();
    public synchronized boolean presenceCheck() {
        if (mWatchdog != null) {
            mWatchdog.reset();
        }
        return doPresenceCheck();
    }

    private NativeNfcTag() {
    }

    public int getHandle() {
        return mHandle;
    }

    public byte[] getUid() {
        return mUid;
    }

    public int[] getTechList() {
        return mTechList;
    }

    public Bundle[] getTechExtras() {
        synchronized (this) {
            if (mTechExtras != null) return mTechExtras;
            mTechExtras = new Bundle[mTechList.length];
            for (int i = 0; i < mTechList.length; i++) {
                Bundle extras = new Bundle();
                switch (mTechList[i]) {
                    case TagTechnology.NFC_A: {
                        byte[] actBytes = mActivationBytes;
                        if ((actBytes != null) && (actBytes.length > 0)) {
                            extras.putShort(NfcA.EXTRA_SAK, (short) (actBytes[0] & (short) 0xFF));
                        } else {
                            throw new IllegalStateException("missing activation bytes");
                        }
                        extras.putByteArray(NfcA.EXTRA_ATQA, mPollBytes);
                        break;
                    }

                    case TagTechnology.NFC_B: {
                        extras.putByteArray(NfcB.EXTRA_ATQB, mPollBytes);
                        break;
                    }
                    case TagTechnology.NFC_F: {
                        byte[] pmm = new byte[8];
                        byte[] sc = new byte[2];
                        if (mPollBytes.length >= 8) {
                            // At least pmm is present
                            System.arraycopy(mPollBytes, 0, pmm, 0, 8);
                            extras.putByteArray(NfcF.EXTRA_PMM, pmm);
                        }
                        if (mPollBytes.length == 10) {
                            System.arraycopy(mPollBytes, 8, sc, 0, 2);
                            extras.putByteArray(NfcF.EXTRA_SC, sc);
                        }
                        break;
                    }

                    default: {
                        // Leave the entry in the array null
                        continue;
                    }
                }
                mTechExtras[i] = extras;
            }
            return mTechExtras;
        }
    }
}
