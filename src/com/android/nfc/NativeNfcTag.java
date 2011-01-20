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

import android.nfc.tech.IsoDep;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;
import android.nfc.NdefMessage;
import android.os.Bundle;
import android.util.Log;

/**
 * Native interface to the NFC tag functions
 */
public class NativeNfcTag {
    static final boolean DBG = false;

    private int[] mTechList;
    private int[] mTechHandles;
    private int[] mTechLibNfcTypes;
    private Bundle[] mTechExtras;
    private byte[][] mTechPollBytes;
    private byte[][] mTechActBytes;
    private byte[] mUid;

    private int mConnectedTechnology; // Index in mTechList

    private final String TAG = "NativeNfcTag";

    private boolean mIsPresent; // Whether the tag is known to be still present

    private PresenceCheckWatchdog mWatchdog;
    class PresenceCheckWatchdog extends Thread {

        private int watchdogTimeout = 125;

        private boolean isPresent = true;
        private boolean isStopped = false;
        private boolean isPaused = false;
        private boolean doCheck = true;

        public synchronized void pause() {
            isPaused = true;
            doCheck = false;
            this.notifyAll();
        }

        public synchronized void doResume() {
            isPaused = false;
            // We don't want to resume presence checking immediately,
            // but go through at least one more wait period.
            doCheck = false;
            this.notifyAll();
        }

        public synchronized void end() {
            isStopped = true;
            doCheck = false;
            this.notifyAll();
        }

        public synchronized void setTimeout(int timeout) {
            watchdogTimeout = timeout;
            doCheck = false; // Do it only after we have waited "timeout" ms again
            this.notifyAll();
        }

        @Override
        public synchronized void run() {
            if (DBG) Log.d(TAG, "Starting background presence check");
            while (isPresent && !isStopped) {
                try {
                    if (!isPaused) {
                        doCheck = true;
                    }
                    this.wait(watchdogTimeout);
                    if (doCheck) {
                        isPresent = doPresenceCheck();
                    } else {
                        // 1) We are paused, waiting for unpause
                        // 2) We just unpaused, do pres check in next iteration
                        //       (after watchdogTimeout ms sleep)
                        // 3) We just set the timeout, wait for this timeout
                        //       to expire once first.
                        // 4) We just stopped, exit loop anyway
                    }
                } catch (InterruptedException e) {
                    // Activity detected, loop
                }
            }
            mIsPresent = false;
            // Restart the polling loop

            Log.d(TAG, "Tag lost, restarting polling loop");
            doDisconnect();
            if (DBG) Log.d(TAG, "Stopping background presence check");
        }
    }

    private native boolean doConnect(int handle);
    public synchronized boolean connect(int technology) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean isSuccess = false;
        for (int i = 0; i < mTechList.length; i++) {
            if (mTechList[i] == technology) {
                // Get the handle and connect, if not already connected
                if (mConnectedTechnology != i) {
                    // We're not yet connected, there are a few scenario's
                    // here:
                    // 1) We are not connected to anything yet - allow
                    // 2) We are connected to a technology which has
                    //    a different handle (multi-protocol tag); we support
                    //    switching to that.
                    // 3) We are connected to a technology which has the same
                    //    handle; we do not support connecting at a different
                    //    level (libnfc auto-activates to the max level on
                    //    any handle).
                    // 4) We are connecting to the ndef technology - always
                    //    allowed.
                    if (mConnectedTechnology == -1) {
                        // Not connected yet
                        isSuccess = doConnect(mTechHandles[i]);
                    }
                    else if ((mConnectedTechnology != -1) &&
                            (mTechHandles[mConnectedTechnology] != mTechHandles[i])) {
                        // Connect to a tech with a different handle
                        isSuccess = reconnect(mTechHandles[i]);
                    }
                    else {
                        // Already connected to a tech with the same handle
                        // Only allow Ndef / NdefFormatable techs to return
                        // success
                        if ((technology == TagTechnology.NDEF) ||
                                (technology == TagTechnology.NDEF_FORMATABLE)) {
                            isSuccess = true;
                        } else {
                            if ((technology != TagTechnology.ISO_DEP) &&
                                (hasTechOnHandle(TagTechnology.ISO_DEP, mTechHandles[i]))) {
                                // Don't allow to connect a -4 tag at a different level
                                // than IsoDep, as this is not supported by
                                // libNFC.
                                isSuccess = false;
                            } else {
                                isSuccess = true;
                            }
                        }
                    }
                    if (isSuccess) {
                        mConnectedTechnology = i;
                    }
                } else {
                    isSuccess = true; // Already connect to this tech
                }
                break;
            }
        }
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return isSuccess;
    }

    public synchronized void startPresenceChecking() {
        // Once we start presence checking, we allow the upper layers
        // to know the tag is in the field.
        mIsPresent = true;
        if (mWatchdog == null) {
            mWatchdog = new PresenceCheckWatchdog();
            mWatchdog.start();
        }
    }

    public synchronized boolean isPresent() {
        // Returns whether the tag is still in the field to the best
        // of our knowledge.
        return mIsPresent;
    }

    native boolean doDisconnect();
    public synchronized boolean disconnect() {
        boolean result = false;

        mIsPresent = false;
        if (mWatchdog != null) {
            // Watchdog has already disconnected or will do it
            mWatchdog.end();
            try {
                mWatchdog.join();
            } catch (InterruptedException e) {
                // Should never happen.
            }
            mWatchdog = null;
            result = true;
        } else {
            result = doDisconnect();
        }

        mConnectedTechnology = -1;
        return result;
    }

    native boolean doReconnect();
    public synchronized boolean reconnect() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doReconnect();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doHandleReconnect(int handle);
    public synchronized boolean reconnect(int handle) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doHandleReconnect(handle);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    private native byte[] doTransceive(byte[] data, boolean raw, int[] returnCode);
    public synchronized byte[] transceive(byte[] data, boolean raw, int[] returnCode) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        byte[] result = doTransceive(data, raw, returnCode);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    private native boolean doCheckNdef(int[] ndefinfo);
    public synchronized boolean checkNdef(int[] ndefinfo) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doCheckNdef(ndefinfo);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    private native byte[] doRead();
    public synchronized byte[] read() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        byte[] result = doRead();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    private native boolean doWrite(byte[] buf);
    public synchronized boolean write(byte[] buf) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doWrite(buf);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doPresenceCheck();
    public synchronized boolean presenceCheck() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doPresenceCheck();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doNdefFormat(byte[] key);
    public synchronized boolean formatNdef(byte[] key) {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doNdefFormat(key);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doMakeReadonly();
    public synchronized boolean makeReadonly() {
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = doMakeReadonly();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean doIsNdefFormatable(int libnfctype, byte[] uid, byte[] poll, byte[] act);
    public synchronized boolean isNdefFormatable() {
        // Call native code to determine at lower level if format
        // is possible. It will need poll/activation time bytes for this.
        int nfcaTechIndex = getTechIndex(TagTechnology.NFC_A);
        int nfcvTechIndex = getTechIndex(TagTechnology.NFC_V);
        if (nfcaTechIndex != -1) {
            return doIsNdefFormatable(mTechLibNfcTypes[nfcaTechIndex], mUid,
                    mTechPollBytes[nfcaTechIndex], mTechActBytes[nfcaTechIndex]);
        } else if (nfcvTechIndex != -1) {
            return doIsNdefFormatable(mTechLibNfcTypes[nfcvTechIndex], mUid,
                    mTechPollBytes[nfcvTechIndex], mTechActBytes[nfcvTechIndex]);
        } else {
            // Formatting not supported by libNFC
            return false;
        }
    }

    private NativeNfcTag() {
    }

    public int getHandle() {
        // This is just a handle for the clients; it can simply use the first
        // technology handle we have.
        if (mTechHandles.length > 0) {
            return mTechHandles[0];
        } else {
            return 0;
        }
    }

    public byte[] getUid() {
        return mUid;
    }

    public int[] getTechList() {
        return mTechList;
    }

    public int[] getHandleList() {
        return mTechHandles;
    }

    public int getConnectedHandle() {
        if (mConnectedTechnology != -1 && mConnectedTechnology < mTechHandles.length) {
            return mTechHandles[mConnectedTechnology];
        } else {
            return 0;
        }
    }

    public int getConnectedLibNfcType() {
        if (mConnectedTechnology != -1 && mConnectedTechnology < mTechLibNfcTypes.length) {
            return mTechLibNfcTypes[mConnectedTechnology];
        } else {
            return 0;
        }
    }

    public int getConnectedTechnology() {
        if (mConnectedTechnology != -1 && mConnectedTechnology < mTechList.length) {
            return mTechList[mConnectedTechnology];
        } else {
            return 0;
        }
    }
    native int doGetNdefType(int libnfctype, int javatype);
    private int getNdefType(int libnfctype, int javatype) {
        return doGetNdefType(libnfctype, javatype);
    }

    private void addTechnology(int tech, int handle, int libnfctype) {
            int[] mNewTechList = new int[mTechList.length + 1];
            System.arraycopy(mTechList, 0, mNewTechList, 0, mTechList.length);
            mNewTechList[mTechList.length] = tech;
            mTechList = mNewTechList;

            int[] mNewHandleList = new int[mTechHandles.length + 1];
            System.arraycopy(mTechHandles, 0, mNewHandleList, 0, mTechHandles.length);
            mNewHandleList[mTechHandles.length] = handle;
            mTechHandles = mNewHandleList;

            int[] mNewTypeList = new int[mTechLibNfcTypes.length + 1];
            System.arraycopy(mTechLibNfcTypes, 0, mNewTypeList, 0, mTechLibNfcTypes.length);
            mNewTypeList[mTechLibNfcTypes.length] = libnfctype;
            mTechLibNfcTypes = mNewTypeList;
    }

    public void addNdefFormatableTechnology(int handle, int libnfcType) {
        synchronized (this) {
            addTechnology(TagTechnology.NDEF_FORMATABLE, handle, libnfcType);
        }
    }

    // This method exists to "patch in" the ndef technologies,
    // which is done inside Java instead of the native JNI code.
    // To not create some nasty dependencies on the order on which things
    // are called (most notably getTechExtras()), it needs some additional
    // checking.
    public void addNdefTechnology(NdefMessage msg, int handle, int libnfcType,
            int javaType, int maxLength, int cardState) {
        synchronized (this) {
            addTechnology(TagTechnology.NDEF, handle, libnfcType);

            Bundle extras = new Bundle();
            extras.putParcelable(Ndef.EXTRA_NDEF_MSG, msg);
            extras.putInt(Ndef.EXTRA_NDEF_MAXLENGTH, maxLength);
            extras.putInt(Ndef.EXTRA_NDEF_CARDSTATE, cardState);
            extras.putInt(Ndef.EXTRA_NDEF_TYPE, getNdefType(libnfcType, javaType));

            if (mTechExtras == null) {
                // This will build the tech extra's for the first time,
                // including a NULL ref for the NDEF tech we generated above.
                Bundle[] builtTechExtras = getTechExtras();
                builtTechExtras[builtTechExtras.length - 1] = extras;
            }
            else {
                // Tech extras were built before, patch the NDEF one in
                Bundle[] oldTechExtras = getTechExtras();
                Bundle[] newTechExtras = new Bundle[oldTechExtras.length + 1];
                System.arraycopy(oldTechExtras, 0, newTechExtras, 0, oldTechExtras.length);
                newTechExtras[oldTechExtras.length] = extras;
                mTechExtras = newTechExtras;
            }


        }
    }

    private int getTechIndex(int tech) {
      int techIndex = -1;
      for (int i = 0; i < mTechList.length; i++) {
          if (mTechList[i] == tech) {
              techIndex = i;
              break;
          }
      }
      return techIndex;
    }

    private boolean hasTech(int tech) {
      boolean hasTech = false;
      for (int i = 0; i < mTechList.length; i++) {
          if (mTechList[i] == tech) {
              hasTech = true;
              break;
          }
      }
      return hasTech;
    }

    private boolean hasTechOnHandle(int tech, int handle) {
      boolean hasTech = false;
      for (int i = 0; i < mTechList.length; i++) {
          if (mTechList[i] == tech && mTechHandles[i] == handle) {
              hasTech = true;
              break;
          }
      }
      return hasTech;

    }

    public Bundle[] getTechExtras() {
        synchronized (this) {
            if (mTechExtras != null) return mTechExtras;
            mTechExtras = new Bundle[mTechList.length];
            for (int i = 0; i < mTechList.length; i++) {
                Bundle extras = new Bundle();
                switch (mTechList[i]) {
                    case TagTechnology.NFC_A: {
                        byte[] actBytes = mTechActBytes[i];
                        if ((actBytes != null) && (actBytes.length > 0)) {
                            extras.putShort(NfcA.EXTRA_SAK, (short) (actBytes[0] & (short) 0xFF));
                        } else {
                            // Unfortunately Jewel doesn't have act bytes,
                            // ignore this case.
                        }
                        extras.putByteArray(NfcA.EXTRA_ATQA, mTechPollBytes[i]);
                        break;
                    }

                    case TagTechnology.NFC_B: {
                        // What's returned from the PN544 is actually:
                        // 4 bytes app data
                        // 3 bytes prot info
                        byte[] appData = new byte[4];
                        byte[] protInfo = new byte[3];
                        if (mTechPollBytes[i].length >= 7) {
                            System.arraycopy(mTechPollBytes[i], 0, appData, 0, 4);
                            System.arraycopy(mTechPollBytes[i], 4, protInfo, 0, 3);

                            extras.putByteArray(NfcB.EXTRA_APPDATA, appData);
                            extras.putByteArray(NfcB.EXTRA_PROTINFO, protInfo);
                        }
                        break;
                    }
                    case TagTechnology.NFC_F: {
                        byte[] pmm = new byte[8];
                        byte[] sc = new byte[2];
                        if (mTechPollBytes[i].length >= 8) {
                            // At least pmm is present
                            System.arraycopy(mTechPollBytes[i], 0, pmm, 0, 8);
                            extras.putByteArray(NfcF.EXTRA_PMM, pmm);
                        }
                        if (mTechPollBytes[i].length == 10) {
                            System.arraycopy(mTechPollBytes[i], 8, sc, 0, 2);
                            extras.putByteArray(NfcF.EXTRA_SC, sc);
                        }
                        break;
                    }
                    case TagTechnology.ISO_DEP: {

                        if (hasTech(TagTechnology.NFC_A)) {
                            extras.putByteArray(IsoDep.EXTRA_HIST_BYTES, mTechActBytes[i]);
                        }
                        else {
                            extras.putByteArray(IsoDep.EXTRA_HI_LAYER_RESP, mTechActBytes[i]);
                        }
                        break;
                    }
                    case TagTechnology.NFC_V: {
                        // First byte response flags, second byte DSFID
                        if (mTechPollBytes[i] != null && mTechPollBytes[i].length >= 2) {
                            extras.putByte(NfcV.EXTRA_RESP_FLAGS, mTechPollBytes[i][0]);
                            extras.putByte(NfcV.EXTRA_DSFID, mTechPollBytes[i][1]);
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
