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

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.android.nfc.R;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class HandoverService extends Service implements HandoverTransfer.Callback,
        BluetoothPeripheralHandover.Callback {

    static final String TAG = "HandoverService";
    static final boolean DBG = true;

    static final int MSG_REGISTER_CLIENT = 0;
    static final int MSG_DEREGISTER_CLIENT = 1;
    static final int MSG_START_INCOMING_TRANSFER = 2;
    static final int MSG_START_OUTGOING_TRANSFER = 3;
    static final int MSG_PERIPHERAL_HANDOVER = 4;
    static final int MSG_PAUSE_POLLING = 5;


    static final String BUNDLE_TRANSFER = "transfer";

    static final String EXTRA_PERIPHERAL_DEVICE = "device";
    static final String EXTRA_PERIPHERAL_NAME = "headsetname";
    static final String EXTRA_PERIPHERAL_TRANSPORT = "transporttype";

    public static final String ACTION_CANCEL_HANDOVER_TRANSFER =
            "com.android.nfc.handover.action.CANCEL_HANDOVER_TRANSFER";

    public static final String EXTRA_INCOMING =
            "com.android.nfc.handover.extra.INCOMING";

    public static final String ACTION_HANDOVER_STARTED =
            "android.nfc.handover.intent.action.HANDOVER_STARTED";

    public static final String ACTION_TRANSFER_PROGRESS =
            "android.nfc.handover.intent.action.TRANSFER_PROGRESS";

    public static final String ACTION_TRANSFER_DONE =
            "android.nfc.handover.intent.action.TRANSFER_DONE";

    public static final String EXTRA_TRANSFER_STATUS =
            "android.nfc.handover.intent.extra.TRANSFER_STATUS";

    public static final String EXTRA_TRANSFER_MIMETYPE =
            "android.nfc.handover.intent.extra.TRANSFER_MIME_TYPE";

    public static final String EXTRA_ADDRESS =
            "android.nfc.handover.intent.extra.ADDRESS";

    public static final String EXTRA_TRANSFER_DIRECTION =
            "android.nfc.handover.intent.extra.TRANSFER_DIRECTION";

    public static final String EXTRA_TRANSFER_ID =
            "android.nfc.handover.intent.extra.TRANSFER_ID";

    public static final String EXTRA_TRANSFER_PROGRESS =
            "android.nfc.handover.intent.extra.TRANSFER_PROGRESS";

    public static final String EXTRA_TRANSFER_URI =
            "android.nfc.handover.intent.extra.TRANSFER_URI";

    public static final String EXTRA_OBJECT_COUNT =
            "android.nfc.handover.intent.extra.OBJECT_COUNT";

    public static final String EXTRA_HANDOVER_DEVICE_TYPE =
            "android.nfc.handover.intent.extra.HANDOVER_DEVICE_TYPE";

    public static final int DIRECTION_INCOMING = 0;
    public static final int DIRECTION_OUTGOING = 1;

    public static final int HANDOVER_TRANSFER_STATUS_SUCCESS = 0;
    public static final int HANDOVER_TRANSFER_STATUS_FAILURE = 1;

    // permission needed to be able to receive handover status requests
    public static final String HANDOVER_STATUS_PERMISSION =
            "android.permission.NFC_HANDOVER_STATUS";

    // Amount of time to pause polling when connecting to peripherals
    private static final int PAUSE_POLLING_TIMEOUT_MS = 35000;
    public static final int PAUSE_DELAY_MILLIS = 300;

    // Variables below only accessed on main thread
    final Queue<BluetoothOppHandover> mPendingOutTransfers;
    final HashMap<Pair<String, Boolean>, HandoverTransfer> mBluetoothTransfers;
    final Messenger mMessenger;

    SoundPool mSoundPool;
    int mSuccessSound;

    BluetoothAdapter mBluetoothAdapter;
    NfcAdapter mNfcAdapter;
    Messenger mClient;
    Handler mHandler;
    BluetoothPeripheralHandover mBluetoothPeripheralHandover;
    boolean mBluetoothHeadsetConnected;
    boolean mBluetoothEnabledByNfc;

    private HandoverTransfer mWifiTransfer;

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    mClient = msg.replyTo;
                    // Restore state from previous instance
                    mBluetoothEnabledByNfc = msg.arg1 != 0;
                    mBluetoothHeadsetConnected = msg.arg2 != 0;
                    break;
                case MSG_DEREGISTER_CLIENT:
                    mClient = null;
                    break;
                case MSG_START_INCOMING_TRANSFER:
                    doIncomingTransfer(msg);
                    break;
                case MSG_START_OUTGOING_TRANSFER:
                    doOutgoingTransfer(msg);
                    break;
                case MSG_PERIPHERAL_HANDOVER:
                    doPeripheralHandover(msg);
                    break;
                case MSG_PAUSE_POLLING:
                    mNfcAdapter.pausePolling(PAUSE_POLLING_TIMEOUT_MS);
                    break;
            }
        }

    }

    final BroadcastReceiver mHandoverStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int deviceType = intent.getIntExtra(EXTRA_HANDOVER_DEVICE_TYPE,
                    HandoverTransfer.DEVICE_TYPE_BLUETOOTH);

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                handleBluetoothStateChanged(intent);
            } else if (action.equals(ACTION_CANCEL_HANDOVER_TRANSFER)) {
                handleCancelTransfer(intent, deviceType);
            } else if (action.equals(ACTION_TRANSFER_PROGRESS) ||
                    action.equals(ACTION_TRANSFER_DONE) ||
                    action.equals(ACTION_HANDOVER_STARTED)) {
                handleTransferEvent(intent, deviceType);
            }
        }
    };

    public HandoverService() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mPendingOutTransfers = new LinkedList<BluetoothOppHandover>();
        mBluetoothTransfers = new HashMap<Pair<String, Boolean>, HandoverTransfer>();
        mHandler = new MessageHandler();
        mMessenger = new Messenger(mHandler);
        mBluetoothHeadsetConnected = false;
        mBluetoothEnabledByNfc = false;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mSoundPool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
        mSuccessSound = mSoundPool.load(this, R.raw.end, 1);
        mNfcAdapter = NfcAdapter.getDefaultAdapter(getApplicationContext());

        IntentFilter filter = new IntentFilter(ACTION_TRANSFER_DONE);
        filter.addAction(ACTION_TRANSFER_PROGRESS);
        filter.addAction(ACTION_CANCEL_HANDOVER_TRANSFER);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(ACTION_HANDOVER_STARTED);
        registerReceiver(mHandoverStatusReceiver, filter, HANDOVER_STATUS_PERMISSION, mHandler);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mSoundPool != null) {
            mSoundPool.release();
        }
        unregisterReceiver(mHandoverStatusReceiver);
    }

    void doOutgoingTransfer(Message msg) {
        Bundle msgData = msg.getData();

        msgData.setClassLoader(getClassLoader());
        PendingHandoverTransfer pendingTransfer = msgData.getParcelable(BUNDLE_TRANSFER);
        createHandoverTransfer(pendingTransfer);

        if (pendingTransfer.deviceType == HandoverTransfer.DEVICE_TYPE_BLUETOOTH) {
            // Create the actual bluetooth transfer

            BluetoothOppHandover handover = new BluetoothOppHandover(HandoverService.this,
                    pendingTransfer.remoteDevice, pendingTransfer.uris,
                    pendingTransfer.remoteActivating);
            if (mBluetoothAdapter.isEnabled()) {
                // Start the transfer
                handover.start();
            } else {
                if (!enableBluetooth()) {
                    Log.e(TAG, "Error enabling Bluetooth.");
                    notifyClientTransferComplete(pendingTransfer.id);
                    return;
                }
                if (DBG) Log.d(TAG, "Queueing out transfer " + Integer.toString(pendingTransfer.id));
                mPendingOutTransfers.add(handover);
                // Queue the transfer and enable Bluetooth - when it is enabled
                // the transfer will be started.
            }
        }
    }

    void doIncomingTransfer(Message msg) {
        Bundle msgData = msg.getData();

        msgData.setClassLoader(getClassLoader());
        PendingHandoverTransfer pendingTransfer = msgData.getParcelable(BUNDLE_TRANSFER);
        if (pendingTransfer.deviceType == HandoverTransfer.DEVICE_TYPE_BLUETOOTH &&
                !mBluetoothAdapter.isEnabled() && !enableBluetooth()) {
            Log.e(TAG, "Error enabling Bluetooth.");
            notifyClientTransferComplete(pendingTransfer.id);
            return;
        }
        createHandoverTransfer(pendingTransfer);
        // Remote device will connect and finish the transfer
    }

    void doPeripheralHandover(Message msg) {
        Bundle msgData = msg.getData();
        BluetoothDevice device = msgData.getParcelable(EXTRA_PERIPHERAL_DEVICE);
        String name = msgData.getString(EXTRA_PERIPHERAL_NAME);
        int transport = msgData.getInt(EXTRA_PERIPHERAL_TRANSPORT);
        if (mBluetoothPeripheralHandover != null) {
           Log.d(TAG, "Ignoring pairing request, existing handover in progress.");
           return;
        }
        mBluetoothPeripheralHandover = new BluetoothPeripheralHandover(HandoverService.this,
                device, name, transport, HandoverService.this);
        // TODO: figure out a way to disable polling without deactivating current target
        if (transport == BluetoothDevice.TRANSPORT_LE) {
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MSG_PAUSE_POLLING), PAUSE_DELAY_MILLIS);
        }
        if (mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothPeripheralHandover.start()) {
                mNfcAdapter.resumePolling();
            }
        } else {
            // Once BT is enabled, the headset pairing will be started

            if (!enableBluetooth()) {
                Log.e(TAG, "Error enabling Bluetooth.");
                mBluetoothPeripheralHandover = null;
            }
        }
    }

    void startPendingTransfers() {
        while (!mPendingOutTransfers.isEmpty()) {
             BluetoothOppHandover handover = mPendingOutTransfers.remove();
             handover.start();
        }
    }

    boolean enableBluetooth() {
        if (!mBluetoothAdapter.isEnabled()) {
            mBluetoothEnabledByNfc = true;
            return mBluetoothAdapter.enableNoAutoConnect();
        }
        return true;
    }

    void disableBluetoothIfNeeded() {
        if (!mBluetoothEnabledByNfc) return;

        if (mBluetoothTransfers.size() == 0 && !mBluetoothHeadsetConnected) {
            mBluetoothAdapter.disable();
            mBluetoothEnabledByNfc = false;
        }
    }

    void createHandoverTransfer(PendingHandoverTransfer pendingTransfer) {
        HandoverTransfer transfer;
        String macAddress;

        if (pendingTransfer.deviceType == HandoverTransfer.DEVICE_TYPE_BLUETOOTH) {
            macAddress = pendingTransfer.remoteDevice.getAddress();
            transfer = maybeCreateHandoverTransfer(macAddress,
                    pendingTransfer.incoming, pendingTransfer);
        } else {
            Log.e(TAG, "Invalid device type [" + pendingTransfer.deviceType + "] received.");
            return;
        }

        if (transfer != null) {
            transfer.updateNotification();
        }
    }

    HandoverTransfer maybeCreateHandoverTransfer(String address, boolean incoming,
                                                 PendingHandoverTransfer pendingTransfer) {
        HandoverTransfer transfer;
        Pair<String, Boolean> key = new Pair<String, Boolean>(address, incoming);

        if (mBluetoothTransfers.containsKey(key)) {
            transfer = mBluetoothTransfers.get(key);
            if (!transfer.isRunning()) {
                mBluetoothTransfers.remove(key); // new one created below
            } else {
                // There is already a transfer running to this
                // device - it will automatically get combined
                // with the existing transfer.
                notifyClientTransferComplete(pendingTransfer.id);
                return null;
            }
        } else {
            transfer = new HandoverTransfer(this, this, pendingTransfer);
        }

        mBluetoothTransfers.put(key, transfer);
        return transfer;
    }


    HandoverTransfer findHandoverTransfer(String macAddress, boolean incoming) {
        Pair<String, Boolean> key = new Pair<String, Boolean>(macAddress, incoming);
        if (mBluetoothTransfers.containsKey(key)) {
            HandoverTransfer transfer = mBluetoothTransfers.get(key);
            if (transfer.isRunning()) {
                return transfer;
            }
        }

        return null;
    }

    @Override
    public IBinder onBind(Intent intent) {
       return mMessenger.getBinder();
    }

    private void handleTransferEvent(Intent intent, int deviceType) {
        String action = intent.getAction();
        int direction = intent.getIntExtra(EXTRA_TRANSFER_DIRECTION, -1);
        int id = intent.getIntExtra(EXTRA_TRANSFER_ID, -1);
        if (action.equals(ACTION_HANDOVER_STARTED)) {
            // This is always for incoming transfers
            direction = DIRECTION_INCOMING;
        }
        String sourceAddress = intent.getStringExtra(EXTRA_ADDRESS);

        if (direction == -1 || sourceAddress == null) return;
        boolean incoming = (direction == DIRECTION_INCOMING);

        HandoverTransfer transfer =
                findHandoverTransfer(sourceAddress, incoming);
        if (transfer == null) {
            // There is no transfer running for this source address; most likely
            // the transfer was cancelled. We need to tell BT OPP to stop transferring.
            if (id != -1) {
                if (deviceType == HandoverTransfer.DEVICE_TYPE_BLUETOOTH) {
                    if (DBG) Log.d(TAG, "Didn't find transfer, stopping");
                    Intent cancelIntent = new Intent(
                            "android.btopp.intent.action.STOP_HANDOVER_TRANSFER");
                    cancelIntent.putExtra(EXTRA_TRANSFER_ID, id);
                    sendBroadcast(cancelIntent);
                }
            }
            return;
        }

        transfer.setBluetoothTransferId(id);

        if (action.equals(ACTION_TRANSFER_DONE)) {
            int handoverStatus = intent.getIntExtra(EXTRA_TRANSFER_STATUS,
                    HANDOVER_TRANSFER_STATUS_FAILURE);
            if (handoverStatus == HANDOVER_TRANSFER_STATUS_SUCCESS) {
                String uriString = intent.getStringExtra(EXTRA_TRANSFER_URI);
                String mimeType = intent.getStringExtra(EXTRA_TRANSFER_MIMETYPE);
                Uri uri = Uri.parse(uriString);
                if (uri != null && uri.getScheme() == null) {
                    uri = Uri.fromFile(new File(uri.getPath()));
                }
                transfer.finishTransfer(true, uri, mimeType);
            } else {
                transfer.finishTransfer(false, null, null);
            }
        } else if (action.equals(ACTION_TRANSFER_PROGRESS)) {
            float progress = intent.getFloatExtra(EXTRA_TRANSFER_PROGRESS, 0.0f);
            transfer.updateFileProgress(progress);
        } else if (action.equals(ACTION_HANDOVER_STARTED)) {
            int count = intent.getIntExtra(EXTRA_OBJECT_COUNT, 0);
            if (count > 0) {
                transfer.setObjectCount(count);
            }
        }
    }

    private void handleCancelTransfer(Intent intent, int deviceType) {
        String sourceAddress = intent.getStringExtra(EXTRA_ADDRESS);
        int direction = intent.getIntExtra(EXTRA_INCOMING, -1);

        if (direction == -1) {
            return;
        }

        boolean incoming = direction == DIRECTION_INCOMING;
        HandoverTransfer transfer = findHandoverTransfer(sourceAddress, incoming);

        if (transfer != null) {
            if (DBG) Log.d(TAG, "Cancelling transfer " + Integer.toString(transfer.mTransferId));
            transfer.cancel();
        }
    }

    private void handleBluetoothStateChanged(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR);
        if (state == BluetoothAdapter.STATE_ON) {
            // If there is a pending device pairing, start it
            if (mBluetoothPeripheralHandover != null &&
                    !mBluetoothPeripheralHandover.hasStarted()) {
                if (!mBluetoothPeripheralHandover.start()) {
                    mNfcAdapter.resumePolling();
                }
            }

            // Start any pending file transfers
            startPendingTransfers();
        } else if (state == BluetoothAdapter.STATE_OFF) {
            mBluetoothEnabledByNfc = false;
            mBluetoothHeadsetConnected = false;
        }
    }

    void notifyClientTransferComplete(int transferId) {
        if (mClient != null) {
            Message msg = Message.obtain(null, HandoverManager.MSG_HANDOVER_COMPLETE);
            msg.arg1 = transferId;
            try {
                mClient.send(msg);
            } catch (RemoteException e) {
                // Ignore
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // prevent any future callbacks to the client, no rebind call needed.
        mClient = null;
        return false;
    }

    @Override
    public void onTransferComplete(HandoverTransfer transfer, boolean success) {
        // Called on the main thread

        // First, remove the transfer from our list
        synchronized (this) {
            if (mWifiTransfer == transfer) {
                mWifiTransfer = null;
            }
        }

        if (mWifiTransfer == null) {
            Iterator it = mBluetoothTransfers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry hashPair = (Map.Entry)it.next();
                HandoverTransfer transferEntry = (HandoverTransfer) hashPair.getValue();
                if (transferEntry == transfer) {
                    it.remove();
                }
            }
        }

        // Notify any clients of the service
        notifyClientTransferComplete(transfer.getTransferId());

        // Play success sound
        if (success) {
            mSoundPool.play(mSuccessSound, 1.0f, 1.0f, 0, 0, 1.0f);
        } else {
            if (DBG) Log.d(TAG, "Transfer failed, final state: " +
                    Integer.toString(transfer.mState));
        }
        disableBluetoothIfNeeded();
    }

    @Override
    public void onBluetoothPeripheralHandoverComplete(boolean connected) {
        // Called on the main thread
        int transport = mBluetoothPeripheralHandover.mTransport;
        mBluetoothPeripheralHandover = null;
        mBluetoothHeadsetConnected = connected;

        // <hack> resume polling immediately if the connection failed,
        // otherwise just wait for polling to come back up after the timeout
        // This ensures we don't disconnect if the user left the volantis
        // on the tag after pairing completed, which results in automatic
        // disconnection </hack>
        if (transport == BluetoothDevice.TRANSPORT_LE && !connected) {
            if (mHandler.hasMessages(MSG_PAUSE_POLLING)) {
                mHandler.removeMessages(MSG_PAUSE_POLLING);
            }

            // do this unconditionally as the polling could have been paused as we were removing
            // the message in the handler. It's a no-op if polling is already enabled.
            mNfcAdapter.resumePolling();
        }

        if (mClient != null) {
            Message msg = Message.obtain(null,
                    connected ? HandoverManager.MSG_HEADSET_CONNECTED
                              : HandoverManager.MSG_HEADSET_NOT_CONNECTED);
            msg.arg1 = mBluetoothEnabledByNfc ? 1 : 0;
            try {
                mClient.send(msg);
            } catch (RemoteException e) {
                // Ignore
            }
        }
        disableBluetoothIfNeeded();
    }
}
