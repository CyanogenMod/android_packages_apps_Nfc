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

import android.util.Log;

/**
 * Native interface to the NFC tag functions
 */
public class NativeNfcTag {
    private int mHandle;

    private String mType;

    private byte[] mUid;

    private final String TAG = "NativeNfcTag";

    private PresenceCheckWatchdog mWatchdog;
    private class PresenceCheckWatchdog extends Thread {

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
            Log.d(TAG, "Starting background presence check");
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
                doAsyncDisconnect();
            }
            Log.d(TAG, "Stopping background presence check");
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

    private native boolean doDisconnect();
    public synchronized boolean disconnect() {
        mWatchdog.end();
        return doDisconnect();
    }

    private native void doAsyncDisconnect();
    public synchronized void asyncDisconnect() {
        mWatchdog.end();
        doAsyncDisconnect();
    }

    private native byte[] doTransceive(byte[] data);
    public synchronized byte[] transceive(byte[] data) {
        mWatchdog.reset();
        return doTransceive(data);
    }

    private native boolean doCheckNdef();
    public synchronized boolean checkNdef() {
        mWatchdog.reset();
        return doCheckNdef();
    }

    private native byte[] doRead();
    public synchronized byte[] read() {
        mWatchdog.reset();
        return doRead();
    }

    private native boolean doWrite(byte[] buf);
    public synchronized boolean write(byte[] buf) {
        mWatchdog.reset();
        return doWrite(buf);
    }

    private native boolean doPresenceCheck();
    public synchronized boolean presenceCheck() {
        mWatchdog.reset();
        return doPresenceCheck();
    }

    private NativeNfcTag() {
    }

    public int getHandle() {
        return mHandle;
    }

    public String getType() {
        return mType;
    }

    public byte[] getUid() {
        return mUid;
    }
}
