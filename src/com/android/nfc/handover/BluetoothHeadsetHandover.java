/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.nfc.handover;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Connects / Disconnects from a Bluetooth headset (or any device that
 * might implement BT HSP, HFP or A2DP sink) when touched with NFC.
 *
 * This object is created on an NFC interaction, and determines what
 * sequence of Bluetooth actions to take, and executes them. It is not
 * designed to be re-used after the sequence has completed or timed out.
 * Subsequent NFC interactions should use new objects.
 *
 * TODO: enable Bluetooth without causing auto-connection to *other* devices
 * TOOD: disable Bluetooth when disconnecting if it was enabled for this device
 * TODO: il8n / UI review
 */
public class BluetoothHeadsetHandover {
    static final String TAG = HandoverManager.TAG;
    static final boolean DBG = HandoverManager.DBG;

    static final int TIMEOUT_MS = 20000;

    static final int STATE_INIT = 0;
    static final int STATE_TURNING_ON = 1;
    static final int STATE_BONDING = 2;
    static final int STATE_CONNECTING = 3;
    static final int STATE_DISCONNECTING = 4;
    static final int STATE_COMPLETE = 5;

    static final int RESULT_PENDING = 0;
    static final int RESULT_CONNECTED = 1;
    static final int RESULT_DISCONNECTED = 2;

    static final int ACTION_DISCONNECT = 1;
    static final int ACTION_CONNECT = 2;

    static final int MSG_TIMEOUT = 1;

    final Context mContext;
    final BluetoothDevice mDevice;
    final String mName;
    final BluetoothAdapter mAdapter;
    final BluetoothA2dp mA2dp;
    final BluetoothHeadset mHeadset;
    final Callback mCallback;

    // only used on main thread
    int mAction;
    int mState;
    int mHfpResult;  // used only in STATE_CONNECTING and STATE_DISCONNETING
    int mA2dpResult; // used only in STATE_CONNECTING and STATE_DISCONNETING

    public interface Callback {
        public void onBluetoothHeadsetHandoverComplete();
    }

    public BluetoothHeadsetHandover(Context context, BluetoothDevice device, String name,
            BluetoothAdapter adapter, BluetoothA2dp a2dp, BluetoothHeadset headset,
            Callback callback) {
        checkMainThread();  // mHandler must get get constructed on Main Thread for toasts to work
        mContext = context;
        mDevice = device;
        mName = name;
        mAdapter = adapter;
        mA2dp = a2dp;
        mHeadset = headset;
        mCallback = callback;
        mState = STATE_INIT;
    }

    /**
     * Main entry point. This method is usually called after construction,
     * to begin the BT sequence. Must be called on Main thread.
     */
    public void start() {
        checkMainThread();
        if (mState != STATE_INIT) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        if (mA2dp.getConnectedDevices().contains(mDevice) ||
                mHeadset.getConnectedDevices().contains(mDevice)) {
            Log.i(TAG, "ACTION_DISCONNECT addr=" + mDevice + " name=" + mName);
            mAction = ACTION_DISCONNECT;
        } else {
            Log.i(TAG, "ACTION_CONNECT addr=" + mDevice + " name=" + mName);
            mAction = ACTION_CONNECT;
        }
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_TIMEOUT), TIMEOUT_MS);
        nextStep();
    }

    /**
     * Called to execute next step in state machine
     */
    void nextStep() {
        if (mAction == ACTION_CONNECT) {
            nextStepConnect();
        } else {
            nextStepDisconnect();
        }
    }

    void nextStepDisconnect() {
        switch (mState) {
            case STATE_INIT:
                mState = STATE_DISCONNECTING;
                if (mHeadset.getConnectionState(mDevice) != BluetoothProfile.STATE_DISCONNECTED) {
                    mHfpResult = RESULT_PENDING;
                    mHeadset.disconnect(mDevice);
                } else {
                    mHfpResult = RESULT_DISCONNECTED;
                }
                if (mA2dp.getConnectionState(mDevice) != BluetoothProfile.STATE_DISCONNECTED) {
                    mA2dpResult = RESULT_PENDING;
                    mA2dp.disconnect(mDevice);
                } else {
                    mA2dpResult = RESULT_DISCONNECTED;
                }
                if (mA2dpResult == RESULT_PENDING || mHfpResult == RESULT_PENDING) {
                    toast("Disconnecting " + mName + "...");
                    break;
                }
                // fall-through
            case STATE_DISCONNECTING:
                if (mA2dpResult == RESULT_PENDING || mHfpResult == RESULT_PENDING) {
                    // still disconnecting
                    break;
                }
                if (mA2dpResult == RESULT_DISCONNECTED && mHfpResult == RESULT_DISCONNECTED) {
                    toast("Disconnected " + mName);
                }
                complete();
                break;
        }
    }

    void nextStepConnect() {
        switch (mState) {
            case STATE_INIT:
                if (!mAdapter.isEnabled()) {
                    startEnabling();
                    break;
                }
                // fall-through
            case STATE_TURNING_ON:
                if (mDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                    startBonding();
                    break;
                }
                // fall-through
            case STATE_BONDING:
                // Bluetooth Profile service will correctly serialize
                // HFP then A2DP connect
                mState = STATE_CONNECTING;
                if (mHeadset.getConnectionState(mDevice) != BluetoothProfile.STATE_CONNECTED) {
                    mHfpResult = RESULT_PENDING;
                    mHeadset.connect(mDevice);
                } else {
                    mHfpResult = RESULT_CONNECTED;
                }
                if (mA2dp.getConnectionState(mDevice) != BluetoothProfile.STATE_CONNECTED) {
                    mA2dpResult = RESULT_PENDING;
                    mA2dp.connect(mDevice);
                } else {
                    mA2dpResult = RESULT_CONNECTED;
                }
                if (mA2dpResult == RESULT_PENDING || mHfpResult == RESULT_PENDING) {
                    toast("Connecting " + mName + "...");
                    break;
                }
                // fall-through
            case STATE_CONNECTING:
                if (mA2dpResult == RESULT_PENDING || mHfpResult == RESULT_PENDING) {
                    // another connection type still pending
                    break;
                }
                if (mA2dpResult == RESULT_CONNECTED || mHfpResult == RESULT_CONNECTED) {
                    // we'll take either as success
                    toast("Connected " + mName);
                    if (mA2dpResult == RESULT_CONNECTED) startTheMusic();
                } else {
                    toast ("Failed to connect " + mName);
                }
                complete();
                break;
        }
    }

    void startEnabling() {
        mState = STATE_TURNING_ON;
        toast("Enabling Bluetooth...");
        if (!mAdapter.enable()) {
            toast("Failed to enable Bluetooth");
            complete();
        }
    }

    void startBonding() {
        mState = STATE_BONDING;
        toast("Pairing " + mName + "...");
        if (!mDevice.createBond()) {
            toast("Failed to pair " + mName);
            complete();
        }
    }

    void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) && mState == STATE_TURNING_ON) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                nextStepConnect();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                toast("Failed to enable Bluetooth");
                complete();
            }
            return;
        }

        // Everything else requires the device to match...
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (!mDevice.equals(device)) return;

        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) && mState == STATE_BONDING) {
            int bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothAdapter.ERROR);
            if (bond == BluetoothDevice.BOND_BONDED) {
                nextStepConnect();
            } else if (bond == BluetoothDevice.BOND_NONE) {
                toast("Failed to pair " + mName);
                complete();
            }
        } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action) &&
                (mState == STATE_CONNECTING || mState == STATE_DISCONNECTING)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                mHfpResult = RESULT_CONNECTED;
                nextStep();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                mHfpResult = RESULT_DISCONNECTED;
                nextStep();
            }
        } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action) &&
                (mState == STATE_CONNECTING || mState == STATE_DISCONNECTING)) {
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                mA2dpResult = RESULT_CONNECTED;
                nextStep();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                mA2dpResult = RESULT_DISCONNECTED;
                nextStep();
            }
        }
    }

    void complete() {
        if (DBG) Log.d(TAG, "complete()");
        mState = STATE_COMPLETE;
        mContext.unregisterReceiver(mReceiver);
        mHandler.removeMessages(MSG_TIMEOUT);
        mCallback.onBluetoothHeadsetHandoverComplete();
    }

    void toast(CharSequence text) {
        Toast.makeText(mContext,  text, Toast.LENGTH_SHORT).show();
    }

    void startTheMusic() {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY));
        mContext.sendOrderedBroadcast(intent, null);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY));
        mContext.sendOrderedBroadcast(intent, null);
    }

    final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TIMEOUT:
                    if (mState == STATE_COMPLETE) return;
                    Log.i(TAG, "Timeout completing BT handover");
                    complete();
                    break;
            }
        }
    };

    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleIntent(intent);
        }
    };

    static void checkMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalThreadStateException("must be called on main thread");
        }
    }
}
