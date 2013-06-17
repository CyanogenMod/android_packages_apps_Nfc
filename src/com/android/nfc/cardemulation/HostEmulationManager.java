/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.nfc.cardemulation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import com.android.nfc.NfcService;
import com.android.nfc.cardemulation.RegisteredAidCache.CardEmulationService;

import java.util.ArrayList;

public class HostEmulationManager {
    static final String TAG = "HostEmulationManager";

    static final int STATE_IDLE = 0;
    static final int STATE_W4_SELECT = 1;
    static final int STATE_W4_SERVICE = 2;
    static final int STATE_XFER = 3;

    final Context mContext;
    final RegisteredAidCache mAidCache;
    final Messenger mMessenger = new Messenger (new MessageHandler());

    final Object mLock;

    // Variables below protected by mLock
    boolean mBound;
    Messenger mService;
    int mState;
    byte[] mSelectApdu;

    public HostEmulationManager(Context context, RegisteredAidCache aidCache) {
        mContext = context;
        mLock = new Object();
        mAidCache = aidCache;
        mState = STATE_IDLE;
    }

    public void notifyHostEmulationActivated() {
        Log.d(TAG, "notifyHostEmulationActivated");
        synchronized (mLock) {
            if (mState != STATE_IDLE) {
                Log.e(TAG, "Got activation event in non-idle state");
                if (mState >= STATE_W4_SERVICE) {
                    unbindServiceLocked();
                }
            }
            mState = STATE_W4_SELECT;
        }
    }

    public void notifyHostEmulationData(byte[] data) {
        Log.d(TAG, "notifyHostEmulationData");
        String selectAid = findSelectAid(data);
        synchronized (mLock) {
            switch (mState) {
            case STATE_IDLE:
                Log.e(TAG, "Got data in idle state");
                break;
            case STATE_W4_SELECT:
                if (selectAid != null) {
                    mSelectApdu = data;
                    if (dispatchAidLocked(selectAid))
                        mState = STATE_W4_SERVICE;
                } else {
                    Log.d(TAG, "Dropping non-select APDU in STATE_W4_SELECT");
                }
                break;
            case STATE_W4_SERVICE:
                if (selectAid != null) {
                    // This should normally not happen, but deal with it gracefully
                    unbindServiceLocked();
                    mSelectApdu = data;
                    if (dispatchAidLocked(selectAid))
                        mState = STATE_W4_SERVICE;
                } else {
                    Log.d(TAG, "Unexpected APDU in STATE_W4_SERVICE");
                }
                break;
            case STATE_XFER:
                // TODO if we get another select we may need to resolve
                // it to a different service
                if (mService != null) {
                    Message msg = Message.obtain(null, 1);
                    Bundle dataBundle = new Bundle();
                    dataBundle.putByteArray("data", data);
                    msg.setData(dataBundle);
                    msg.replyTo = mMessenger;
                    try {
                        Log.d(TAG, "Sending data to service");
                        mService.send(msg);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote service has died, dropping APDU");
                    }
                } else {
                    Log.d(TAG, "Service no longer bound, dropping APDU");
                }
                break;
            }
        }
    }

    public void notifyNostEmulationDeactivated() {
        Log.d(TAG, "notifyHostEmulationDeactivated");
        synchronized (mLock) {
            if (mState == STATE_IDLE) {
                Log.e(TAG, "Got deactivation event while in idle state");
                return;
            }
            if (mService != null) {
                // Send a MSG_DEACTIVATED
                Message msg = Message.obtain(null, 2);
                try {
                    mService.send(msg);
                } catch (RemoteException e) {
                    // Don't care
                }
            }
            unbindServiceLocked();
            mState = STATE_IDLE;
        }
    }

    void unbindServiceLocked() {
        mContext.unbindService(mConnection);
        synchronized (mLock) {
            mBound = false;
            mService = null;
        }
    }

    String findSelectAid(byte[] data) {
        if (data == null || data.length < 6) {
            Log.d(TAG, "Data size too small for SELECT APDU");
        }
        // TODO we'll support only logical channel 0 in CLA?
        // TODO support chaining bits in CLA?
        // TODO what about selection using DF/EF identifiers and/or path?
        // TODO what about P2?
        // TODO what about the default selection status?
        if (data[0] == 0x00 && data[1] == (byte)0xA4 && data[2] == 0x04) {
            int aidLength = data[4];
            if (data.length < 5 + aidLength) {
                return null;
            }
            return bytesToString(data, 5, aidLength);
        }
        return null;
    }

    boolean dispatchAidLocked(String aid) {
        Log.d(TAG, "dispatchAidLocked");
        ArrayList<CardEmulationService> matchingServices = mAidCache.resolveAidPrefix(aid);
        if (matchingServices.size() == 0) {
            Log.e(TAG, "Could not find matching services for AID " + aid);
        } else if (matchingServices.size() == 1) {
            CardEmulationService service = matchingServices.get(0);
            Intent aidIntent = new Intent("android.nfc.action.AID_SELECTED");
            aidIntent.setComponent(service.serviceName);
            if (mContext.bindService(aidIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                return true;
            } else {
                Log.d(TAG, "Failed to dispatch AID to service");
            }
        } else {
            Log.e(TAG, "Multiple services matched; TODO, conflict resolution UX");
        }

        return false;
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mService = new Messenger(service);
                mBound = true;
                Log.d(TAG, "Service bound");
                mState = STATE_XFER;
                // Send pending select APDU
                if (mSelectApdu != null) {
                    Message msg = Message.obtain(null, 0);
                    Bundle dataBundle = new Bundle();
                    dataBundle.putByteArray("data", mSelectApdu);
                    msg.setData(dataBundle);
                    msg.replyTo = mMessenger;
                    try {
                        mService.send(msg);
                    } catch (RemoteException e) {
                        Log.d(TAG, "Service gone!");
                    }
                    mSelectApdu = null;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Log.d(TAG, "Service unbound");
                mService = null;
                mBound = false;
            }
        }
    };

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle dataBundle = msg.getData();
            if (dataBundle == null) {
                return;
            }
            byte[] data = dataBundle.getByteArray("data");
            if (data == null || data.length == 0) {
                Log.e(TAG, "Dropping empty R-APDU");
                return;
            }
            int state;
            synchronized(mLock) {
                state = mState;
            }
            if (state == STATE_XFER) {
                Log.d(TAG, "Sending data");
                NfcService.getInstance().sendData(data);
            } else {
                Log.d(TAG, "Dropping data, wrong state " + Integer.toString(state));
            }
        }
    }

    static String bytesToString(byte[] bytes, int offset, int length) {
        final char[] hexChars = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] chars = new char[length * 2];
        int byteValue;
        for (int j = 0; j < length; j++) {
            byteValue = bytes[offset + j] & 0xFF;
            chars[j * 2] = hexChars[byteValue >>> 4];
            chars[j * 2 + 1] = hexChars[byteValue & 0x0F];
        }
        return new String(chars);
    }
}
