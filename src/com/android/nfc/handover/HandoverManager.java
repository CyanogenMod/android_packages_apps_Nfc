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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Random;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.util.Log;

/**
 * Manages handover of NFC to other technologies.
 */
public class HandoverManager implements BluetoothProfile.ServiceListener,
        BluetoothHeadsetHandover.Callback {
    static final String TAG = "NfcHandover";
    static final boolean DBG = true;

    static final byte[] TYPE_NOKIA = "nokia.com:bt".getBytes(Charset.forName("US_ASCII"));
    static final byte[] TYPE_BT_OOB = "application/vnd.bluetooth.ep.oob".
            getBytes(Charset.forName("US_ASCII"));

    final Context mContext;
    final BluetoothAdapter mBluetoothAdapter;

    // synchronized on HandoverManager.this
    BluetoothHeadset mBluetoothHeadset;
    BluetoothA2dp mBluetoothA2dp;
    BluetoothHeadsetHandover mBluetoothHeadsetHandover;

    static class BluetoothHandoverData {
        public boolean valid = false;
        public BluetoothDevice device;
        public String name;
    }

    public HandoverManager(Context context) {
        mContext = context;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothAdapter.getProfileProxy(mContext, this, BluetoothProfile.HEADSET);
        mBluetoothAdapter.getProfileProxy(mContext, this, BluetoothProfile.A2DP);
    }

    static NdefRecord createCollisionRecord() {
        byte[] random = new byte[2];
        new Random().nextBytes(random);
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, new byte[] {0x48, 0x72}, null, random);
    }

    NdefRecord createBluetoothAlternateCarrierRecord() {
        byte[] payload = new byte[4];
        //TODO: Encode 'activating' if BT is not on yet
        payload[0] = 0x01;  // Carrier Power State: Active
        payload[1] = 1;   // length of carrier data reference
        payload[2] = 'b'; // carrier data reference: ID for Bluetooth OOB data record
        payload[3] = 0;  // Auxiliary data reference count
        // 0x61, 0x63 is "ac"
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, new byte[] {0x61, 0x63}, null, payload);
    }

    NdefRecord createBluetoothOobDataRecord() {
        byte[] payload = new byte[8];
        payload[0] = 0;
        payload[1] = (byte)payload.length;

        //TODO: check getAddress() works with BT off
        String address = mBluetoothAdapter.getAddress();
        byte[] addressBytes = addressToReverseBytes(address);
        System.arraycopy(addressBytes, 0, payload, 2, 6);
        return new NdefRecord(NdefRecord.TNF_MIME_MEDIA, TYPE_BT_OOB, new byte[]{'b'}, payload);
    }

    public NdefMessage createHandoverRequestMessage() {
        return new NdefMessage(createHandoverRequestRecord(), createBluetoothOobDataRecord());
    }

    NdefRecord createHandoverRequestRecord() {
        ByteBuffer payload = ByteBuffer.allocate(100);  //TODO figure out size
        payload.put((byte)0x12);  // connection handover v1.2

        //TODO: spec is not clear if we encode each nested NdefRecord as a
        // a stand-alone message (with MB and ME flags set), or as a combined
        // message (MB only set on first, ME only set on last). Current
        // implementation assumes the later.
        NdefMessage nestedMessage = new NdefMessage(createCollisionRecord(),
                createBluetoothAlternateCarrierRecord());
        payload.put(nestedMessage.toByteArray());

        byte[] payloadBytes = new byte[payload.position()];
        payload.position(0);
        payload.get(payloadBytes);
        // 0x48, 0x72 is "Hr"
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, new byte[]{0x48, 0x72}, null, payloadBytes);
    }

    /**
     * Return null if message is not a Handover Request,
     * return the Handover Select response if it is.
     */
    public NdefMessage tryHandoverRequest(NdefMessage m) {
        if (m == null) return null;
        if (DBG) Log.d(TAG, "tryHandoverRequest():" + m.toString());

        NdefRecord r = m.getRecords()[0];
        if (r.getTnf() != NdefRecord.TNF_WELL_KNOWN) return null;
        if (!Arrays.equals(r.getType(), new byte[]{0x48, 0x72})) return null;

        // we have a handover select, look for BT OOB record
        BluetoothHandoverData bluetoothData = null;
        for (NdefRecord oob : m.getRecords()) {
            if (oob.getTnf() == NdefRecord.TNF_MIME_MEDIA &&
                    Arrays.equals(oob.getType(), TYPE_BT_OOB)) {
                bluetoothData = parseBtOob(ByteBuffer.wrap(oob.getPayload()));
                break;
            }
        }
        if (bluetoothData == null) return null;

        // BT OOB found, whitelist it for incoming OPP data
        whitelistOppDevice(bluetoothData.device);

        // return BT OOB record so they can perform handover
        //TODO: Should use full Carrier Select form with power state to handle BT enabling...
        return new NdefMessage(createBluetoothOobDataRecord());
    }

    void whitelistOppDevice(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "Whitelisting " + device + " for BT OPP");
        Intent intent = new Intent("todo-whitelist");
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mContext.sendBroadcast(intent);
    }

    public boolean tryHandover(NdefMessage m) {
        if (m == null) return false;
        if (DBG) Log.d(TAG, "tryHandover(): " + m.toString());

        BluetoothHandoverData handover = parse(m);
        if (handover == null) return false;
        if (!handover.valid) return true;

        synchronized (HandoverManager.this) {
            if (mBluetoothAdapter == null ||
                    mBluetoothA2dp == null ||
                    mBluetoothHeadset == null) {
                if (DBG) Log.d(TAG, "BT handover, but BT not available");
                return true;
            }
            if (mBluetoothHeadsetHandover != null) {
                if (DBG) Log.d(TAG, "BT handover already in progress, ignoring");
                return true;
            }
            mBluetoothHeadsetHandover = new BluetoothHeadsetHandover(mContext, handover.device,
                    handover.name, mBluetoothAdapter, mBluetoothA2dp, mBluetoothHeadset, this);
            mBluetoothHeadsetHandover.start();
        }
        return true;
    }

    // This starts sending an Uri over BT
    public void doHandoverUri(Uri[] uris, NdefMessage m) {
        BluetoothHandoverData data = parse(m);
        BluetoothOppHandover handover = new BluetoothOppHandover(mContext, data.device,
                uris);
        handover.start();
    }

    BluetoothHandoverData parse(NdefMessage m) {
        NdefRecord r = m.getRecords()[0];
        short tnf = r.getTnf();
        byte[] type = r.getType();

        // Check for BT OOB record
        if (r.getTnf() == NdefRecord.TNF_MIME_MEDIA && Arrays.equals(r.getType(), TYPE_BT_OOB)) {
            return parseBtOob(ByteBuffer.wrap(r.getPayload()));
        }

        // Check for Handover Select, followed by a BT OOB record
        if (tnf == NdefRecord.TNF_WELL_KNOWN &&
                Arrays.equals(type, NdefRecord.RTD_HANDOVER_SELECT)) {
            for (NdefRecord oob : m.getRecords()) {
                if (oob.getTnf() == NdefRecord.TNF_MIME_MEDIA &&
                        Arrays.equals(oob.getType(), TYPE_BT_OOB)) {
                    return parseBtOob(ByteBuffer.wrap(oob.getPayload()));
                }
            }
        }

        // Check for Nokia BT record, found on some Nokia BH-505 Headsets
        if (tnf == NdefRecord.TNF_EXTERNAL_TYPE && Arrays.equals(type, TYPE_NOKIA)) {
            return parseNokia(ByteBuffer.wrap(r.getPayload()));
        }

        return null;
    }

    BluetoothHandoverData parseNokia(ByteBuffer payload) {
        BluetoothHandoverData result = new BluetoothHandoverData();
        result.valid = false;

        try {
            payload.position(1);
            byte[] address = new byte[6];
            payload.get(address);
            result.device = mBluetoothAdapter.getRemoteDevice(address);
            result.valid = true;
            payload.position(14);
            int nameLength = payload.get();
            byte[] nameBytes = new byte[nameLength];
            payload.get(nameBytes);
            result.name = new String(nameBytes, Charset.forName("UTF-8"));
        } catch (IllegalArgumentException e) {
            Log.i(TAG, "nokia: invalid BT address");
        } catch (BufferUnderflowException e) {
            Log.i(TAG, "nokia: payload shorter than expected");
        }
        if (result.valid && result.name == null) result.name = "";
        return result;
    }

    BluetoothHandoverData parseBtOob(ByteBuffer payload) {
        BluetoothHandoverData result = new BluetoothHandoverData();
        result.valid = false;

        try {
            payload.position(2);
            byte[] address = new byte[6];
            payload.get(address);
            // ByteBuffer.order(LITTLE_ENDIAN) doesn't work for
            // ByteBuffer.get(byte[]), so manually swap order
            for (int i = 0; i < 3; i++) {
                byte temp = address[i];
                address[i] = address[5 - i];
                address[5 - i] = temp;
            }
            result.device = mBluetoothAdapter.getRemoteDevice(address);
            result.valid = true;

            while (payload.remaining() > 0) {
                byte[] nameBytes;
                int len = payload.get();
                int type = payload.get();
                switch (type) {
                    case 0x08:  // short local name
                        nameBytes = new byte[len - 1];
                        payload.get(nameBytes);
                        result.name = new String(nameBytes, Charset.forName("UTF-8"));
                        break;
                    case 0x09:  // long local name
                        if (result.name != null) break;  // prefer short name
                        nameBytes = new byte[len - 1];
                        payload.get(nameBytes);
                        result.name = new String(nameBytes, Charset.forName("UTF-8"));
                        break;
                    default:
                        payload.position(payload.position() + len - 1);
                        break;
                }
            }
        } catch (IllegalArgumentException e) {
            Log.i(TAG, "BT OOB: invalid BT address");
        } catch (BufferUnderflowException e) {
            Log.i(TAG, "BT OOB: payload shorter than expected");
        }
        if (result.valid && result.name == null) result.name = "";
        return result;
    }

    static byte[] addressToReverseBytes(String address) {
        String[] split = address.split(":");
        byte[] result = new byte[split.length];

        for (int i = 0; i < split.length; i++) {
            // need to parse as int because parseByte() expects a signed byte
            result[split.length - 1 - i] = (byte)Integer.parseInt(split[i], 16);
        }

        return result;
    }

    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
        synchronized (HandoverManager.this) {
            switch (profile) {
                case BluetoothProfile.HEADSET:
                    mBluetoothHeadset = (BluetoothHeadset) proxy;
                    break;
                case BluetoothProfile.A2DP:
                    mBluetoothA2dp = (BluetoothA2dp) proxy;
                    break;
            }
        }
    }

    @Override
    public void onServiceDisconnected(int profile) {
        synchronized (HandoverManager.this) {
            switch (profile) {
                case BluetoothProfile.HEADSET:
                    mBluetoothHeadset = null;
                    break;
                case BluetoothProfile.A2DP:
                    mBluetoothA2dp = null;
                    break;
            }
        }
    }

    @Override
    public void onBluetoothHeadsetHandoverComplete() {
        synchronized (HandoverManager.this) {
            mBluetoothHeadsetHandover = null;
        }
    }
}
