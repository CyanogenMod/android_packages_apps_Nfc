/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.nfc3.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charsets;
import java.util.Arrays;
import java.util.UUID;

public class BluetoothDropbox extends BroadcastReceiver {
    private static final String TAG = "btdropbox";
    private static final boolean DBG = true;

    public static final String MIME_TYPE = "vnd.android.com/oob.ndef";

    private static final String SERVICE_NAME = "BtDropbox";
    private static final String PREF = "BluetoothDropboxPrefs";
    private static final String PREF_BLUETOOTH_ADDRESS = "bluetooth_address";
    private static final UUID SERVICE_UUID = UUID.randomUUID();
    private static final byte PROTOCOL_VERSION = 0x017;
    private static final int ACCESS_WINDOW_MS = 45*1000;
    private static final int BLUETOOTH_STARTUP_DELAY_MS = 7000;
    private static final int BLUETOOTH_CHANNEL = 13;

    private static final int NOTIFICATION_INBOUND_ME_PROFILE = 1;
    private static final int NOTIFICATION_OUTBOUND_ME_PROFILE = 2;

    final Context mContext;
    final BluetoothAdapter mBluetoothAdapter;
    final SharedPreferences mPrefs;

    // these fields protected by BluetoothDropbox.this
    String mLastAccessAddress;
    long mLastAccessTime;
    DropboxAcceptThread mAcceptThread;
    int mBluetoothState;
    int mBluetoothCount;
    boolean mBluetoothTransient;
    NotificationManager mNotificationManager;
    String mBluetoothAddress;

    public BluetoothDropbox(Context context) {
        mContext = context;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Cannot start btdropbox, bluetooth not available");
            throw new RuntimeException("Cannot start btdropbox, bluetooth not available");
        }
        mBluetoothCount = 0;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(this, filter);
        mBluetoothState = mBluetoothAdapter.getState();
        mPrefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        mBluetoothAddress = mPrefs.getString(PREF_BLUETOOTH_ADDRESS, null);
        if (mBluetoothAddress == null && mBluetoothState == BluetoothAdapter.STATE_ON) {
            mBluetoothAddress = mBluetoothAdapter.getAddress();
            mPrefs.edit().putString(PREF_BLUETOOTH_ADDRESS, mBluetoothAddress).apply();
        }
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
    }

    public void start() {
        boolean needAddress = false;
        synchronized (this) {
            needAddress = mBluetoothAddress == null;
        }
        if (needAddress) {
            // cycle Bluetooth to pick up mBluetoothAddress
            requestBluetooth();
            mHandler.sendMessageDelayed(mHandler.obtainMessage(), 1000);
        }
    }

    public synchronized void startListening() {
        if (DBG) Log.d(TAG, "Starting btdropbox service");
        if (mAcceptThread != null) {
            if (DBG) Log.d(TAG, "btdropbox already started");
            return;
        }
        mAcceptThread = new DropboxAcceptThread(SERVICE_NAME, SERVICE_UUID);
        mAcceptThread.start();
    }

    public void stop() { }
    public synchronized void stopListening() {
        if (mAcceptThread != null) {
            if (DBG) Log.d(TAG, "Stopping btdropbox service");
            mAcceptThread.cancel();
            mAcceptThread = null;
        }
    }

    /**
     * Grants exclusive access to this dropbox to a remote device, for
     * ACCESS_WINDOW_MS milliseconds.
     */
    public void grantAccess(NdefMessage sender) {
        requestBluetooth();
        mHandler.sendMessageDelayed(mHandler.obtainMessage(), ACCESS_WINDOW_MS);

        if (sender == null || sender.getRecords().length == 0) {
            return;
        }
        NdefRecord firstRecord = sender.getRecords()[0];
        if (firstRecord.getTnf() != NdefRecord.TNF_MIME_MEDIA ||
                !Arrays.equals(firstRecord.getType(), MIME_TYPE.getBytes())) {
            return;
        }
        Uri senderUri = Uri.parse(new String(firstRecord.getPayload()));
        if (DBG) Log.d(TAG, "Granting dropbox access to " + senderUri.getAuthority());
        synchronized (this) {
            mLastAccessAddress = senderUri.getAuthority();
            mLastAccessTime = System.currentTimeMillis();
        }
    }

    /**
     * Returns an ndef message with the information required to connect
     * to this dropbox.
     */
    public synchronized NdefMessage getDropboxAddressNdef() {
//        if (mAcceptThread == null || mAcceptThread.mServerSocket == null) return null;

        if (mBluetoothAddress == null) {
            Log.w(TAG, "No bluetooth address");
            return null;
        }
        byte[] payload = ("btdb://" + mBluetoothAddress + "/" + SERVICE_UUID
                + "?channel=" + BLUETOOTH_CHANNEL).getBytes();
        NdefRecord record = new NdefRecord(NdefRecord.TNF_MIME_MEDIA, MIME_TYPE.getBytes(),
                new byte[0], payload);
        return new NdefMessage(new NdefRecord[] { record });
    }

    /** Enable Bluetooth, ref-counted, blocking */
    void requestBluetooth() {
        synchronized (this) {
            if (mBluetoothCount == 0) {
                if (mBluetoothState != BluetoothAdapter.STATE_ON) {
                    mBluetoothTransient = true;
                    mBluetoothAdapter.enable();
                } else {
                    mBluetoothTransient = false;
                }
            }
            mBluetoothCount++;
            if (DBG) Log.d(TAG, "requestBluetooth(), count=" + mBluetoothCount);
        }

        // TODO: don't poll
        for (int i=0; i<60; i++) {
            synchronized (this) {
                if (mBluetoothState == BluetoothAdapter.STATE_ON) {
                    break;
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) { }
        }
    }

    /** Disable Bluetooth, ref-counted, NOT blocking*/
    void releaseBluetooth() {
        synchronized (this) {
            if (mBluetoothCount <= 0) {
                Log.w(TAG, "Unbalanced releaseBluetooth()");
                return;
            }
            mBluetoothCount--;
            if (DBG) Log.d(TAG, "releaseBluetooth(), count=" + mBluetoothCount);
            if (mBluetoothCount == 0 && mBluetoothTransient) {
                mBluetoothAdapter.disable();
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
            synchronized (this) {
                mBluetoothState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.STATE_OFF);
                switch (mBluetoothState) {
                    case BluetoothAdapter.STATE_OFF:
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        stopListening();
                        break;
                    case BluetoothAdapter.STATE_ON:
                        if (mBluetoothAddress == null) {
                            mBluetoothAddress = mBluetoothAdapter.getAddress();
                            mPrefs.edit().putString(PREF_BLUETOOTH_ADDRESS, mBluetoothAddress).apply();
                            if (DBG) Log.d(TAG, "Got BT address " + mBluetoothAddress);
                        }
                        startListening();
                        break;
                    default:
                }
            }
        }
    }

    public void sendContent(NdefMessage target, NdefMessage content) throws IOException {
         new DropboxSendThread(target, content).start();
    }

    public void handleOutboundMeProfile(NdefMessage target, NdefMessage profile) throws IOException {
        Intent intent = new Intent();
        intent.setClass(mContext, BluetoothMeProfileReceiver.class);
        intent.putExtra(BluetoothMeProfileReceiver.EXTRA_TARGET, target);
        intent.putExtra(BluetoothMeProfileReceiver.EXTRA_PROFILE, profile);
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(mContext)
                .setContentTitle(mContext.getString(R.string.outbound_me_profile_title))
                .setContentText(mContext.getString(R.string.outbound_me_profile_text))
                .setContentIntent(pi)
                .setSmallIcon(R.drawable.ic_tab_selected_contacts)
                .setAutoCancel(true)
                .getNotification();
        mNotificationManager.notify(NOTIFICATION_OUTBOUND_ME_PROFILE, notification);
    }

    private class DropboxReceiveThread extends Thread {
        private final BluetoothSocket mmSocket;

        public DropboxReceiveThread(BluetoothSocket socket) {
            mmSocket = socket;
        }

        @Override
        public void run() {
            setName("DropboxReceiveThread");

            try {
                if (DBG) Log.d(TAG, "Receiver reading content");
                InputStream in = mmSocket.getInputStream();
                byte version = (byte)in.read();
                if (version != PROTOCOL_VERSION) {
                    throw new IOException("Invalid protocol version " + version);
                }
                byte[] lengthBytes = new byte[4];
                int r = in.read(lengthBytes);
                if (r < lengthBytes.length) {
                    throw new IOException("Error reading data length");
                }
                ByteBuffer buffer = ByteBuffer.wrap(lengthBytes);
                int length = buffer.getInt();

                byte[] ndefBytes = new byte[length];
                int total = 0;
                while (total < length) {
                    int read = in.read(ndefBytes, total, length - total);
                    if (read == -1) {
                        throw new IOException("End of stream while reading data");
                    }
                    total += read;
                }
                NdefMessage ndef;
                try {
                    ndef = new NdefMessage(ndefBytes);
                } catch (FormatException e) {
                    throw new IOException("Error reading ndef", e);
                }
                if (DBG) Log.d(TAG, "Received ndef " + new String(ndef.getRecords()[0].getType()));

                handleInboundMeProfile(ndef);
            } catch (IOException e) {
                Log.e(TAG, "Failed to receive data", e);
            }
        }
    }

    boolean handleInboundMeProfile(NdefMessage msg) {
        NdefRecord records[] = msg.getRecords();
        if (records == null || records.length < 1) {
            Log.d(TAG, "invalid me profile");
            return false;
        }
        NdefRecord vcard = records[0];
        if (vcard.getTnf() != NdefRecord.TNF_MIME_MEDIA) {
            Log.d(TAG, "invalid TNF for me profile: " + vcard.getTnf());
            return false;
        }
        String type = new String(vcard.getType(), Charsets.US_ASCII);
        if (!"text/x-vCard".equalsIgnoreCase(type)) {
            Log.d(TAG, "invalid me profile MIME type: " + type);
            return false;
        }

        Intent intent = new Intent(NfcAdapter.ACTION_NDEF_DISCOVERED);
        intent.setType("text/x-vCard");
        intent.putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, new NdefMessage[] { msg });
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(mContext)
                .setContentTitle(mContext.getString(R.string.inbound_me_profile_title))
                .setContentText(mContext.getString(R.string.inbound_me_profile_text))
                .setContentIntent(pi)
                .setSmallIcon(R.drawable.ic_tab_selected_contacts)
                .setAutoCancel(true)
                .getNotification();
        mNotificationManager.notify(NOTIFICATION_INBOUND_ME_PROFILE, notification);
        return true;
    }

    private class DropboxSendThread extends Thread {
        private final NdefMessage mmTarget;
        private final NdefMessage mmOutboundNdef;

        public DropboxSendThread(NdefMessage target, NdefMessage outboundNdef) {
            mmTarget = target;
            mmOutboundNdef = outboundNdef;
        }

        @Override
        public void run() {
            setName("DropboxSendThread");
            long t1 = System.currentTimeMillis();

            BluetoothDevice device;
            BluetoothSocket socket;
            Uri dbUri = Uri.parse(new String(mmTarget.getRecords()[0].getPayload()));
            Log.d(TAG, "Opening outbound dropbox connection for " + dbUri);
            requestBluetooth();
            synchronized (BluetoothDropbox.this) {
                device = mBluetoothAdapter.getRemoteDevice(dbUri.getAuthority());
            }
            String channel = dbUri.getQueryParameter("channel");
            int chan = Integer.parseInt(channel);
            long delta = BLUETOOTH_STARTUP_DELAY_MS - (System.currentTimeMillis() - t1);
            if (delta > 0) {
                try {
                    Log.d(TAG, "Waiting " + delta + " ms");
                    Thread.sleep(BLUETOOTH_STARTUP_DELAY_MS);
                } catch (InterruptedException e) { }
            }
            try {
                socket = device.createInsecureRfcommSocket(chan);
            } catch (IOException e) {
                Log.w(TAG, "Could not create outbound socket to " + device + " on channel " + chan);
                releaseBluetooth();
                return;
            }

            if (DBG) Log.d(TAG, "Sending content to dropbox");
            OutputStream out;
            try {
                socket.connect();
                if (DBG) Log.d(TAG, "Dropbox client connected");
                out = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "failed to connect to bluetooth socket", e);
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                releaseBluetooth();
                return;
            }

            try {
                if (DBG) Log.d(TAG, "Sending ndef content " + mmOutboundNdef);
                byte[] outboundBytes = mmOutboundNdef.toByteArray();
                ByteBuffer buffer = ByteBuffer.allocate(4);
                buffer.putInt(outboundBytes.length);
                out.write(PROTOCOL_VERSION);
                out.write(buffer.array());
                out.write(outboundBytes);
                out.close();
                if (DBG) Log.d(TAG, "Done sending, closing socket.");
            } catch (IOException e) {
                Log.e(TAG, "Failed to send data", e);
            }
            releaseBluetooth();
        }
    }

    private class DropboxAcceptThread extends Thread {
        final BluetoothServerSocket mServerSocket;
        boolean mRunning;

        DropboxAcceptThread(String serviceName, UUID serviceUuid) {
            BluetoothServerSocket tmp = null;
            try {
                tmp = mBluetoothAdapter.listenUsingInsecureRfcommOn(BLUETOOTH_CHANNEL);
            } catch (IOException e) {
                Log.e(TAG, "listen() failed", e);
            }
            mServerSocket = tmp;
        }

        @Override
        public void run() {
            setName("DropboxAcceptThread");
            BluetoothSocket socket = null;
            mRunning = true;

            while (mRunning) {
                try {
                    if (DBG) Log.d(TAG, "Dropbox server waiting...");
                    socket = mServerSocket.accept();
                    if (DBG) Log.d(TAG, "Dropbox server connected!");
                } catch (IOException e) {
                    continue;
                }

                if (socket == null) {
                    continue;
                }

                synchronized (this) {
                    try {
                        String senderAddress = socket.getRemoteDevice().getAddress();
                        if (mLastAccessAddress == null ||
                                !mLastAccessAddress.equals(senderAddress)) {
                            if (DBG) Log.w(TAG, "Invalid access to dropbox.");
                            return;
                        }
                        if (mLastAccessTime + ACCESS_WINDOW_MS < System.currentTimeMillis()) {
                            if (DBG) Log.w(TAG, "Client waited to long to send to dropbox.");
                            return;
                        }
                    } finally {
                        mLastAccessAddress = null;
                    }
                }

                new DropboxReceiveThread(socket).start();
            }
        }

        public void cancel() {
            try {
                mRunning = false;
                mServerSocket.close();
            } catch (IOException e) { }
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            releaseBluetooth();
        }
    };
}