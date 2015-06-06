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

package com.android.nfc.dhimpl;

import com.android.nfc.DeviceHost;
import com.android.nfc.DeviceHost.TagEndpoint;

import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.nfc.tech.TagTechnology;
import android.os.Bundle;
import android.util.Log;

/**
 * Native interface to the NFC tag functions
 */
public class NativeNfcTag implements TagEndpoint {
    static final boolean DBG = false;

    static final int STATUS_CODE_TARGET_LOST = 146;

    private int[] mTechList;
    private int[] mTechHandles;
    private int[] mTechLibNfcTypes;
    private Bundle[] mTechExtras;
    private byte[][] mTechPollBytes;
    private byte[][] mTechActBytes;
    private byte[] mUid;
    private byte[] mReadData;
    private byte[] mRecvData;
    private int mTagMaxLength;

    // mConnectedHandle stores the *real* libnfc handle
    // that we're connected to.
    private int mConnectedHandle;

    // mConnectedTechIndex stores to which technology
    // the upper layer stack is connected. Note that
    // we may be connected to a libnfchandle without being
    // connected to a technology - technology changes
    // may occur runtime, whereas the underlying handle
    // could stay present. Usually all technologies are on the
    // same handle, with the exception of multi-protocol
    // tags.
    private int mConnectedTechIndex; // Index in mTechHandles

    private final String TAG = "NativeNfcTag";

    private boolean mIsPresent; // Whether the tag is known to be still present

    private PresenceCheckWatchdog mWatchdog;
    class PresenceCheckWatchdog extends Thread {

        private final DeviceHost.TagDisconnectedCallback tagDisconnectedCallback;
        private int watchdogTimeout;

        private boolean isPresent = true;
        private boolean isStopped = false;
        private boolean isPaused = false;
        private boolean doCheck = true;

        public PresenceCheckWatchdog(int presenceCheckDelay, DeviceHost.TagDisconnectedCallback callback) {
            watchdogTimeout = presenceCheckDelay;
            tagDisconnectedCallback = callback;
        }

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

        @Override
        public void run() {
            if (DBG) Log.d(TAG, "Starting background presence check");
            synchronized (this) {
                while (isPresent && !isStopped) {
                    try {
                        if (!isPaused) {
                            doCheck = true;
                        }
                        this.wait(watchdogTimeout);
                        if (doCheck) {
                            isPresent = nativePresenceCheck();
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
            }

            synchronized (NativeNfcTag.this) {
                mIsPresent = false;
            }
            // Restart the polling loop

            Log.d(TAG, "Tag lost, restarting polling loop");
            if (tagDisconnectedCallback != null) {
                tagDisconnectedCallback.onTagDisconnected(mConnectedHandle);
            }
            if (DBG) Log.d(TAG, "Stopping background presence check");
        }
    }

    private native boolean nativeConnectTech();
    public synchronized int connectWithStatus(int technology) {
        Log.i(TAG, "call: connectWithStatus technology=" + technology);
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        int status = -1;
        for (int i = 0; i < mTechList.length; i++) {
            if (mTechList[i] == technology) {
                boolean ret = nativeConnectTech();
                if (ret) {
                    mConnectedHandle = mTechHandles[i];
                    mConnectedTechIndex = i;
                    status = 0;
                }
                break;
            }
        }
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return status;
    }

    @Override
    public synchronized boolean connect(int technology) {
        Log.i(TAG, "call: connect technology=" + technology);
        return connectWithStatus(technology) == 0;
    }

    @Override
    public synchronized void startPresenceChecking(int presenceCheckDelay,
                                                   DeviceHost.TagDisconnectedCallback callback) {
        Log.i(TAG, "call: startPresenceChecking presenceCheckDelay=" + presenceCheckDelay);
        // Once we start presence checking, we allow the upper layers
        // to know the tag is in the field.
        mIsPresent = true;
        if (mConnectedTechIndex == -1 && mTechList.length > 0) {
            connect(mTechList[0]);
        }
        if (mWatchdog == null) {
            mWatchdog = new PresenceCheckWatchdog(presenceCheckDelay, callback);
            mWatchdog.start();
        }
    }

    @Override
    public synchronized boolean isPresent() {
        // Returns whether the tag is still in the field to the best
        // of our knowledge.
        return mIsPresent;
    }

    @Override
    public synchronized boolean disconnect() {
        Log.i(TAG, "call: disconnect");
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
            result = true;
        }

        mConnectedTechIndex = -1;
        mConnectedHandle = -1;
        return result;
    }

    public synchronized int reconnectWithStatus() {
        Log.i(TAG, "call: reconnectWithStatus");
        return 0;
    }

    @Override
    public synchronized boolean reconnect() {
        Log.i(TAG, "call: reconnect");
        return reconnectWithStatus() == 0;
    }

    public synchronized int reconnectWithStatus(int handle) {
        Log.i(TAG, "call: reconnectWithStatus handle=" + handle);
        return 0;
    }

    private native int nativeGetTransceiveRcvMaxSize(int tech);
    private native int nativeTransceiveData(byte tech, byte[] data, int len);
    @Override
    public synchronized byte[] transceive(byte[] data, boolean raw, int[] returnCode) {
        Log.i(TAG, "call: transceive data=" + data + ", raw=" + raw + " returnCode=" + returnCode);
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        int recvLength = nativeGetTransceiveRcvMaxSize(mTechList[mConnectedTechIndex]);
        mRecvData = new byte[recvLength];
        nativeTransceiveData((byte)mTechList[mConnectedTechIndex], data, data.length);
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return mRecvData;
    }

    private native int nativeCheckNdefData(int[] ndefinfo);
    private synchronized int checkNdefWithStatus(int[] ndefinfo) {
        Log.i(TAG, "call: checkNdefWithStatus ndefinfo=" + ndefinfo);
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        int ret = nativeCheckNdefData(ndefinfo);
        mTagMaxLength = ndefinfo[0];
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return ret;
    }

    @Override
    public synchronized boolean checkNdef(int[] ndefinfo) {
        Log.i(TAG, "call: checkNdef ndefinfo=" + ndefinfo);
        return checkNdefWithStatus(ndefinfo) == 0;
    }

    private native int nativeReadNdefData();
    @Override
    public synchronized byte[] readNdef() {
        Log.i(TAG, "call: readNdef");
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        mReadData = new byte[mTagMaxLength];
        nativeReadNdefData();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return mReadData;
    }

    private native int nativeWriteNdefData(byte[] buf, int bufLen);
    @Override
    public synchronized boolean writeNdef(byte[] buf) {
        Log.i(TAG, "call: writeNdef buf=" + buf);
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = nativeWriteNdefData(buf, buf.length) == 0;
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean nativePresenceCheck();
    @Override
    public synchronized boolean presenceCheck() {
        Log.i(TAG, "call: presenceCheck");
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = nativePresenceCheck();
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native int nativeFormatable();
    @Override
    public synchronized boolean formatNdef(byte[] key) {
        Log.i(TAG, "call: formatNdef key=" + key);
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = nativeFormatable() == 0;
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native int nativeSetNdefReadOnly();
    @Override
    public synchronized boolean makeReadOnly() {
        Log.i(TAG, "call: makeReadOnly");
        if (mWatchdog != null) {
            mWatchdog.pause();
        }
        boolean result = nativeSetNdefReadOnly() == 0;
        if (mWatchdog != null) {
            mWatchdog.doResume();
        }
        return result;
    }

    native boolean nativeIsFormatable();
    @Override
    public synchronized boolean isNdefFormatable() {
        Log.i(TAG, "call: isNdefFormatable");
        return nativeIsFormatable();
    }

    @Override
    public int getHandle() {
        // This is just a handle for the clients; it can simply use the first
        // technology handle we have.
        if (mTechHandles.length > 0) {
            return mTechHandles[0];
        } else {
            return 0;
        }
    }

    @Override
    public byte[] getUid() {
        return mUid;
    }

    @Override
    public int[] getTechList() {
        return mTechList;
    }

    private int getConnectedHandle() {
        return mConnectedHandle;
    }

    private int getConnectedLibNfcType() {
        if (mConnectedTechIndex != -1 && mConnectedTechIndex < mTechLibNfcTypes.length) {
            return mTechLibNfcTypes[mConnectedTechIndex];
        } else {
            return 0;
        }
    }

    @Override
    public int getConnectedTechnology() {
        if (mConnectedTechIndex != -1 && mConnectedTechIndex < mTechList.length) {
            return mTechList[mConnectedTechIndex];
        } else {
            return 0;
        }
    }

    private int getNdefType(int libnfctype, int javatype) {
        Log.i(TAG, "call: getNdefType libnfctype=" + libnfctype + " javatype=" + javatype);
        return mTechLibNfcTypes[mConnectedTechIndex];
    }

    private void addTechnology(int tech, int handle, int libnfctype) {
        Log.i(TAG, "call: getConnectedTechnology tech=" + tech + ", handle=" + handle + " libnfctype=" + libnfctype);
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

    @Override
    public void removeTechnology(int tech) {
        Log.i(TAG, "call: removeTechnology tech=" + tech);
        synchronized (this) {
            int techIndex = getTechIndex(tech);
            if (techIndex != -1) {
                int[] mNewTechList = new int[mTechList.length - 1];
                System.arraycopy(mTechList, 0, mNewTechList, 0, techIndex);
                System.arraycopy(mTechList, techIndex + 1, mNewTechList, techIndex,
                        mTechList.length - techIndex - 1);
                mTechList = mNewTechList;

                int[] mNewHandleList = new int[mTechHandles.length - 1];
                System.arraycopy(mTechHandles, 0, mNewHandleList, 0, techIndex);
                System.arraycopy(mTechHandles, techIndex + 1, mNewTechList, techIndex,
                        mTechHandles.length - techIndex - 1);
                mTechHandles = mNewHandleList;

                int[] mNewTypeList = new int[mTechLibNfcTypes.length - 1];
                System.arraycopy(mTechLibNfcTypes, 0, mNewTypeList, 0, techIndex);
                System.arraycopy(mTechLibNfcTypes, techIndex + 1, mNewTypeList, techIndex,
                        mTechLibNfcTypes.length - techIndex - 1);
                mTechLibNfcTypes = mNewTypeList;
            }
        }
    }

    public void addNdefFormatableTechnology(int handle, int libnfcType) {
        Log.i(TAG, "call: addNdefFormatableTechnology handle=" + handle + ", libnfcType=" + libnfcType);
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
        Log.i(TAG, "call: addNdefTechnology msg=" + msg + ", handle=" + handle + ", libnfcType=" + libnfcType + " javaType=" + javaType + " maxLength=" + maxLength + ", cardState=" + cardState);
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
        Log.i(TAG, "call: getTechIndex tech=" + tech);
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
        Log.i(TAG, "call: hasTech tech=" + tech);
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
        Log.i(TAG, "call: hasTechOnHandle tech=" + tech + ", handle=" + handle);
        boolean hasTech = false;
        for (int i = 0; i < mTechList.length; i++) {
            if (mTechList[i] == tech && mTechHandles[i] == handle) {
                hasTech = true;
                break;
            }
        }
        return hasTech;
    }

    private boolean isUltralightC() {
        Log.i(TAG, "call: isUltralightC");
        /* Make a best-effort attempt at classifying ULTRALIGHT
         * vs ULTRALIGHT-C (based on NXP's public AN1303).
         * The memory layout is as follows:
         *   Page # BYTE1  BYTE2  BYTE3  BYTE4
         *   2      INT1   INT2   LOCK   LOCK
         *   3      OTP    OTP    OTP    OTP  (NDEF CC if NDEF-formatted)
         *   4      DATA   DATA   DATA   DATA (version info if factory-state)
         *
         * Read four blocks from page 2, which will get us both
         * the lock page, the OTP page and the version info.
         */
        boolean isUltralightC = false;
        byte[] readCmd = { 0x30, 0x02 };
        int[] retCode = new int[2];
        byte[] respData = transceive(readCmd, false, retCode);
        if (respData != null && respData.length == 16) {
            // Check the lock bits (last 2 bytes in page2)
            // and the OTP bytes (entire page 3)
            if (respData[2] == 0 && respData[3] == 0 && respData[4] == 0 &&
                respData[5] == 0 && respData[6] == 0 && respData[7] == 0) {
                // Very likely to be a blank card, look at version info
                // in page 4.
                if ((respData[8] == (byte)0x02) && respData[9] == (byte)0x00) {
                    // This is Ultralight-C
                    isUltralightC = true;
                } else {
                    // 0xFF 0xFF would indicate Ultralight, but we also use Ultralight
                    // as a fallback if it's anything else
                    isUltralightC = false;
                }
            } else {
                // See if we can find the NDEF CC in the OTP page and if it's
                // smaller than major version two
                if (respData[4] == (byte)0xE1 && ((respData[5] & 0xff) < 0x20)) {
                    // OK, got NDEF. Technically we'd have to search for the
                    // NDEF TLV as well. However, this would add too much
                    // time for discovery and we can make already make a good guess
                    // with the data we have here. Byte 2 of the OTP page
                    // indicates the size of the tag - 0x06 is UL, anything
                    // above indicates UL-C.
                    if ((respData[6] & 0xff) > 0x06) {
                        isUltralightC = true;
                    }
                } else {
                    // Fall back to ultralight
                    isUltralightC = false;
                }
            }
        }
        return isUltralightC;
    }

    @Override
    public Bundle[] getTechExtras() {
        Log.i(TAG, "call: getTechExtras");
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

                    case TagTechnology.MIFARE_ULTRALIGHT: {
                        boolean isUlc = isUltralightC();
                        extras.putBoolean(MifareUltralight.EXTRA_IS_UL_C, isUlc);
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

    @Override
    public NdefMessage findAndReadNdef() {
        Log.i(TAG, "call: findAndReadNdef");
        // Try to find NDEF on any of the technologies.
        int[] technologies = getTechList();
        int[] handles = mTechHandles;
        NdefMessage ndefMsg = null;
        boolean foundFormattable = false;
        int formattableHandle = 0;
        int formattableLibNfcType = 0;
        int status;

        for (int techIndex = 0; techIndex < technologies.length; techIndex++) {
            // have we seen this handle before?
            for (int i = 0; i < techIndex; i++) {
                if (handles[i] == handles[techIndex]) {
                    continue;  // don't check duplicate handles
                }
            }

            status = connectWithStatus(technologies[techIndex]);
            if (status != 0) {
                Log.d(TAG, "Connect Failed - status = "+ status);
                if (status == STATUS_CODE_TARGET_LOST) {
                    break;
                }
                continue;  // try next handle
            }
            // Check if this type is NDEF formatable
            if (!foundFormattable) {
                if (isNdefFormatable()) {
                    foundFormattable = true;
                    formattableHandle = getConnectedHandle();
                    formattableLibNfcType = getConnectedLibNfcType();
                    // We'll only add formattable tech if no ndef is
                    // found - this is because libNFC refuses to format
                    // an already NDEF formatted tag.
                }
                reconnect();
            }

            int[] ndefinfo = new int[2];
            status = checkNdefWithStatus(ndefinfo);
            if (status != 0) {
                Log.d(TAG, "Check NDEF Failed - status = " + status);
                if (status == STATUS_CODE_TARGET_LOST) {
                    break;
                }
                continue;  // try next handle
            }

            // found our NDEF handle
            boolean generateEmptyNdef = false;

            int supportedNdefLength = ndefinfo[0];
            int cardState = ndefinfo[1];
            byte[] buff = readNdef();
            if (buff != null) {
                try {
                    ndefMsg = new NdefMessage(buff);
                    addNdefTechnology(ndefMsg,
                            getConnectedHandle(),
                            getConnectedLibNfcType(),
                            getConnectedTechnology(),
                            supportedNdefLength, cardState);
                    reconnect();
                } catch (FormatException e) {
                   // Create an intent anyway, without NDEF messages
                   generateEmptyNdef = true;
                }
            } else {
                generateEmptyNdef = true;
            }

            if (generateEmptyNdef) {
                ndefMsg = null;
                addNdefTechnology(null,
                        getConnectedHandle(),
                        getConnectedLibNfcType(),
                        getConnectedTechnology(),
                        supportedNdefLength, cardState);
                foundFormattable = false;
                reconnect();
            }
            break;
        }

        if (ndefMsg == null && foundFormattable) {
            // Tag is not NDEF yet, and found a formattable target,
            // so add formattable tech to tech list.
            addNdefFormatableTechnology(
                    formattableHandle,
                    formattableLibNfcType);
        }

        return ndefMsg;
    }

    native boolean nativeTagDisconnect();
}
