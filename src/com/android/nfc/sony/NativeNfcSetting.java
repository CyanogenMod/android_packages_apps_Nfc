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

package com.android.nfc.sony;

import android.util.Log;

public class NativeNfcSetting {
    private static final String TAG = "NativeNfcSetting";

    private static final byte BIT_0 = 1;
    private static final byte BIT_1 = 2;
    private static final byte BIT_2 = 4;
    private static final byte BIT_3 = 8;
    private static final byte BIT_4 = 16;
    private static final byte BIT_5 = 32;
    private static final byte BIT_ALL = 15;
    private static final byte BIT_OFF = 0;
    private static final boolean DBG = false;
    private static final int INT_DEFAULT = 0;
    private static final int INT_TYPE_A = 1;
    private static final int INT_TYPE_ALL = 4;
    private static final int INT_TYPE_F212 = 2;
    private static final int INT_TYPE_F424 = 3;
    private static final byte RF_REG_6 = 32;
    private static final byte RF_REG_7 = 36;
    private static final byte RF_REG_8 = 52;
    private static final int TGT_DEFAULT = 0;
    private static final int TGT_TYPE_ALL = 3;
    private static final int TGT_TYPE_A_WAIT = 1;
    private static final int TGT_TYPE_F_WAIT = 2;

    private byte mAutoPollOffListen;
    private byte mListenChangeSwitch;
    private int mListenTime;
    private int[] mPollGapListenTime;
    private int[] mPollTimeRfOnToPoll;
    private byte mPolloingChangeSwitch;
    private int mReg6Data;
    private int mReg7Data;
    private int mReg8Data;
    private int mReg14Data;
    private int mReg15Data;
    private byte mRegAddress;
    private byte mSelectPollType;
    private byte mSpdTime;
    private byte mTypeAWait;
    private boolean mTypeBWait;
    private byte mTypeFWait;

    public NativeNfcSetting() {
        initPollingParam();
        initListenParam();
        initRfParam();
    }

    private native int nativeSetListenParameter(byte listenchangeSwitch, int[] pollGapListenTime,
            int listenTime, byte typeAWait, byte typeFWait, boolean typeBWait, byte autoPollOffListen);
    private boolean changeListenParam() {
        int ret = nativeSetListenParameter(mListenChangeSwitch, mPollGapListenTime,
                mListenTime, mTypeAWait, mTypeFWait, mTypeBWait, mAutoPollOffListen);
        if (ret != 0) {
            Log.e("NativeNfcSetting", "Error!!! nativeSetListenParameter() return = " + ret);
            return false;
        }
        return true;
    }

    private native int nativeSetPollParameter(byte polloingChangeSwitch, byte selectPollType, int[] pollTimeRfOnToPoll);
    private boolean changePollParam() {
        int ret = nativeSetPollParameter(this.mPolloingChangeSwitch, this.mSelectPollType, this.mPollTimeRfOnToPoll);
        if (ret != 0) {
            Log.e("NativeNfcSetting", "Error!!! nativeSetPollParameter() return = " + ret);
            return false;
        }
        return true;
    }

    private native int nativeRfParameter(byte[] param);
    private boolean changeRfParam() {
        return nativeRfParameter(concat(new byte[][] {
                createRegData((byte)32, mReg6Data),
                createRegData((byte)36, mReg7Data),
                createRegData((byte)52, mReg8Data),
                createRegData((byte)48, mReg14Data),
                createRegData((byte) 5, mReg15Data), })) == 0;
    }

    private byte[] concat(byte[]... arrays) {
        int len = 0;
        for (int i = 0; i < arrays.length; i++) {
            len += arrays[i].length;
        }
        byte[] result = new byte[len];
        int pos = 0;
        for (int i = 0; i < arrays.length; i++) {
            byte[] array = arrays[i];
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }

    private byte[] createRegData(byte address, int value) {
        Log.i(TAG, "call: createRegData address=" + address + ", value=" + value);
        byte[] regData = new byte[6];
        regData[0] = 0;
        regData[1] = address;
        regData[2] = ((byte)(0xFF & value >>> 24));
        regData[3] = ((byte)(0xFF & value >>> 16));
        regData[4] = ((byte)(0xFF & value >>> 8));
        regData[5] = ((byte)(0xFF & value >>> 0));
        return regData;
    }

    private void initListenParam() {
        Log.i(TAG, "call: initListenParam");
        mListenChangeSwitch = 0;
        mPollGapListenTime = new int[3];
        mPollGapListenTime[0] = 10;
        mPollGapListenTime[1] = 10;
        mPollGapListenTime[2] = 10;
        mListenTime = 15;
        mTypeAWait = 4;
        mTypeFWait = 0;
        mTypeBWait = true;
        mAutoPollOffListen = 7;
    }
  
    private void initPollingParam() {
        Log.i(TAG, "call: initPollingParam");
        mPolloingChangeSwitch = 0;
        mSelectPollType = 15;
        mPollTimeRfOnToPoll = new int[4];
        mPollTimeRfOnToPoll[0] = 6;
        mPollTimeRfOnToPoll[1] = 6;
        mPollTimeRfOnToPoll[2] = 21;
        mPollTimeRfOnToPoll[3] = 21;
    }

    private void initRfParam() {
        Log.i(TAG, "call: initRfParam");
        mRegAddress = 0;
        mReg6Data = 0x40c043f;
        mReg7Data = 0x143f133f;
        mReg8Data = 0x60000000;
        mReg14Data = 0x1f280000;
        mReg15Data = 0x28080000;
    }

    private native boolean nativeClearCIDSupport();

    private native boolean nativeSetCIDSupport();

    private boolean setListenSetting(byte switchBit, int index, int value) {
        Log.i(TAG, "call: setListenSetting switchBit=" + switchBit + ", index=" + index + ", value=" + value);
        mListenChangeSwitch = ((byte)(switchBit | mListenChangeSwitch));
        switch (index) {
        case 8: 
            mPollGapListenTime[0] = value;
            return true;
        case 9: 
            mPollGapListenTime[1] = value;
            return true;
        case 10: 
            mPollGapListenTime[2] = value;
            return true;
        case 11: 
            mListenTime = value;
            return true;
        case 12: 
            mTypeAWait = ((byte)value);
            return true;
        case 13: 
            mTypeFWait = ((byte)value);
            return true;
        case 14: 
            if (value != 0) {
                mTypeBWait = true;
                return true;
            }
            mTypeBWait = false;
            return true;
        default: 
            mAutoPollOffListen = ((byte)value);
            return true;
        }
    }

    private boolean setPollSetting(byte switchBit, int index, int value) {
        Log.i(TAG, "call: setPollSetting switchBit=" + switchBit + ", index=" + index + ", value=" + value);
        mPolloingChangeSwitch = ((byte)(switchBit | mPolloingChangeSwitch));
        switch (index) {
        case 0: 
            if (value != 0) {
                this.mSelectPollType = ((byte)(0x1 | this.mSelectPollType));
                return true;
            }
            this.mSelectPollType = ((byte)(0xFFFFFFFE & this.mSelectPollType));
            return true;
       case 1: 
            if (value != 0) {
                this.mSelectPollType = ((byte)(0x2 | this.mSelectPollType));
                return true;
            }
            this.mSelectPollType = ((byte)(0xFFFFFFFD & this.mSelectPollType));
            return true;
       case 2: 
           if (value != 0) {
               this.mSelectPollType = ((byte)(0x4 | this.mSelectPollType));
               return true;
           }
           this.mSelectPollType = ((byte)(0xFFFFFFFB & this.mSelectPollType));
           return true;
       case 3: 
            if (value != 0) {
                this.mSelectPollType = ((byte)(0x8 | this.mSelectPollType));
                return true;
            }
            this.mSelectPollType = ((byte)(0xFFFFFFF7 & this.mSelectPollType));
            return true;
        case 4: 
            this.mPollTimeRfOnToPoll[0] = value;
            return true;
        case 5: 
            this.mPollTimeRfOnToPoll[1] = value;
            return true;
        case 6: 
            this.mPollTimeRfOnToPoll[2] = value;
            return true;
        default:
            mPollTimeRfOnToPoll[3] = value;
            return true;
        }
    }
  
    private boolean setRfSetting(byte address, int index, int value) {
        Log.i(TAG, "call: setRfSetting address=" + address + ", index=" + index + " value=" + value);
        // TODO
        return true;
    }
  
    public boolean changeParameter(int target) {
        Log.i(TAG, "call: changeParameter target=" + target);
        if (target == 1) {
            return changePollParam();
        } else if (target == 2) {
            return changeListenParam();
        } else if (target == 3) {
            return changeRfParam();
        }
        return false;
    }
  
    public void setP2pModes(int initiatorModes, int targetModes) {
        Log.i(TAG, "call: setP2pModes initiatorModes=" + initiatorModes + ", targetModes=" + targetModes);
        initPollingParam();
        initListenParam();
    }
  
    public boolean setParameter(int index, int value) {
        Log.i(TAG, "call: setParameter index=" + index + ", value=" + value);
        // TODO
        setPollSetting((byte)1, index, value);
        setPollSetting((byte)2, index, value);
        setListenSetting((byte)1, index, value);
        setListenSetting((byte)2, index, value);
        setListenSetting((byte)4, index, value);
        setListenSetting((byte)8, index, value);
        setListenSetting((byte)16, index, value);
        setListenSetting((byte)32, index, value);
        setRfSetting((byte)32, index, value);
        setRfSetting((byte)36, index, value);
        setRfSetting((byte)52, index, value);
        setRfSetting((byte)40, index, value);
        setRfSetting((byte)48, index, value);
        return true;
    }
}

