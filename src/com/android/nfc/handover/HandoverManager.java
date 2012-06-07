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

import java.io.File;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Notification.Builder;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import com.android.nfc.NfcService;
import com.android.nfc.R;


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

    static final String ACTION_BT_OPP_TRANSFER_PROGRESS =
            "android.btopp.intent.action.BT_OPP_TRANSFER_PROGRESS";

    static final String ACTION_BT_OPP_TRANSFER_DONE =
            "android.btopp.intent.action.BT_OPP_TRANSFER_DONE";

    static final String EXTRA_BT_OPP_TRANSFER_STATUS =
            "android.btopp.intent.extra.BT_OPP_TRANSFER_STATUS";

    static final String EXTRA_BT_OPP_TRANSFER_MIMETYPE =
            "android.btopp.intent.extra.BT_OPP_TRANSFER_MIMETYPE";

    static final String EXTRA_BT_OPP_ADDRESS =
            "android.btopp.intent.extra.BT_OPP_ADDRESS";

    static final int HANDOVER_TRANSFER_STATUS_SUCCESS = 0;

    static final int HANDOVER_TRANSFER_STATUS_FAILURE = 1;

    static final String EXTRA_BT_OPP_TRANSFER_DIRECTION =
            "android.btopp.intent.extra.BT_OPP_TRANSFER_DIRECTION";

    static final int DIRECTION_BLUETOOTH_INCOMING = 0;

    static final int DIRECTION_BLUETOOTH_OUTGOING = 1;

    static final String EXTRA_BT_OPP_TRANSFER_ID =
            "android.btopp.intent.extra.BT_OPP_TRANSFER_ID";

    static final String EXTRA_BT_OPP_TRANSFER_PROGRESS =
            "android.btopp.intent.extra.BT_OPP_TRANSFER_PROGRESS";

    static final String EXTRA_BT_OPP_TRANSFER_URI =
            "android.btopp.intent.extra.BT_OPP_TRANSFER_URI";

    // permission needed to be able to receive handover status requests
    static final String HANDOVER_STATUS_PERMISSION =
            "com.android.permission.HANDOVER_STATUS";

    static final int MSG_HANDOVER_POWER_CHECK = 0;

    // We poll whether we can safely disable BT every POWER_CHECK_MS
    static final int POWER_CHECK_MS = 20000;

    static final String ACTION_WHITELIST_DEVICE =
            "android.btopp.intent.action.WHITELIST_DEVICE";

    static final String ACTION_CANCEL_HANDOVER_TRANSFER =
            "com.android.nfc.handover.action.CANCEL_HANDOVER_TRANSFER";
    static final String EXTRA_SOURCE_ADDRESS =
            "com.android.nfc.handover.extra.SOURCE_ADDRESS";

    static final int SOURCE_BLUETOOTH_INCOMING = 0;

    static final int SOURCE_BLUETOOTH_OUTGOING = 1;

    static final int CARRIER_POWER_STATE_INACTIVE = 0;
    static final int CARRIER_POWER_STATE_ACTIVE = 1;
    static final int CARRIER_POWER_STATE_ACTIVATING = 2;
    static final int CARRIER_POWER_STATE_UNKNOWN = 3;

    final Context mContext;
    final BluetoothAdapter mBluetoothAdapter;
    final NotificationManager mNotificationManager;
    final HandoverPowerManager mHandoverPowerManager;

    // Variables below synchronized on HandoverManager.this
    final HashMap<Pair<String, Boolean>, HandoverTransfer> mTransfers;

    BluetoothHeadset mBluetoothHeadset;
    BluetoothA2dp mBluetoothA2dp;
    BluetoothHeadsetHandover mBluetoothHeadsetHandover;
    boolean mBluetoothHeadsetConnected;

    String mLocalBluetoothAddress;
    int mNotificationId;

    static class BluetoothHandoverData {
        public boolean valid = false;
        public BluetoothDevice device;
        public String name;
        public boolean carrierActivating = false;
    }

    class HandoverPowerManager implements Handler.Callback {
        final Handler handler;
        final Context context;

        public HandoverPowerManager(Context context) {
            this.handler = new Handler(this);
            this.context = context;
        }

        /**
         * Enables Bluetooth and will automatically disable it
         * when there is no Bluetooth activity intitiated by NFC
         * anymore.
         */
        synchronized boolean enableBluetooth() {
            // Enable BT
            boolean result = mBluetoothAdapter.enableNoAutoConnect();

            if (result) {
                // Start polling for BT activity to make sure we eventually disable
                // it again.
                handler.sendEmptyMessageDelayed(MSG_HANDOVER_POWER_CHECK, POWER_CHECK_MS);
            }
            return result;
        }

        synchronized boolean isBluetoothEnabled() {
            return mBluetoothAdapter.isEnabled();
        }

        synchronized void resetTimer() {
            if (handler.hasMessages(MSG_HANDOVER_POWER_CHECK)) {
                handler.removeMessages(MSG_HANDOVER_POWER_CHECK);
                handler.sendEmptyMessageDelayed(MSG_HANDOVER_POWER_CHECK, POWER_CHECK_MS);
            }
        }

        void stopMonitoring() {
            handler.removeMessages(MSG_HANDOVER_POWER_CHECK);
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HANDOVER_POWER_CHECK:
                    // Check for any alive transfers
                    boolean transferAlive = false;
                    synchronized (HandoverManager.this) {
                        for (HandoverTransfer transfer : mTransfers.values()) {
                            if (transfer.isRunning()) {
                                transferAlive = true;
                            }
                        }

                        if (!transferAlive && !mBluetoothHeadsetConnected) {
                            mBluetoothAdapter.disable();
                            handler.removeMessages(MSG_HANDOVER_POWER_CHECK);
                        } else {
                            handler.sendEmptyMessageDelayed(MSG_HANDOVER_POWER_CHECK, POWER_CHECK_MS);
                        }
                    }
                    return true;
            }
            return false;
        }
    }

    /**
     * A HandoverTransfer object represents a set of files
     * that were received through NFC connection handover
     * from the same source address.
     *
     * For Bluetooth, files are received through OPP, and
     * we have no knowledge how many files will be transferred
     * as part of a single transaction.
     * Hence, a transfer has a notion of being "alive": if
     * the last update to a transfer was within WAIT_FOR_NEXT_TRANSFER_MS
     * milliseconds, we consider a new file transfer from the
     * same source address as part of the same transfer.
     * The corresponding URIs will be grouped in a single folder.
     *
     */
    class HandoverTransfer implements Handler.Callback,
            MediaScannerConnection.OnScanCompletedListener {
        // In the states below we still accept new file transfer
        static final int STATE_NEW = 0;
        static final int STATE_IN_PROGRESS = 1;
        static final int STATE_W4_NEXT_TRANSFER = 2;

        // In the states below no new files are accepted.
        static final int STATE_W4_MEDIA_SCANNER = 3;
        static final int STATE_FAILED = 4;
        static final int STATE_SUCCESS = 5;
        static final int STATE_CANCELLED = 6;

        static final int MSG_NEXT_TRANSFER_TIMER = 0;
        static final int MSG_TRANSFER_TIMEOUT = 1;

        // We need to receive an update within this time period
        // to still consider this transfer to be "alive" (ie
        // a reason to keep the handover transport enabled).
        static final int ALIVE_CHECK_MS = 20000;

        // The amount of time to wait for a new transfer
        // once the current one completes.
        static final int WAIT_FOR_NEXT_TRANSFER_MS = 4000;

        static final String BEAM_DIR = "beam";

        final BluetoothDevice device;
        final String sourceAddress;
        final boolean incoming;  // whether this is an incoming transfer
        final int notificationId; // Unique ID of this transfer used for notifications
        final Handler handler;
        final PendingIntent cancelIntent;

        int state;
        Long lastUpdate; // Last time an event occurred for this transfer
        float progress; // Progress in range [0..1]
        ArrayList<Uri> btUris; // Received uris from Bluetooth OPP
        ArrayList<String> btMimeTypes; // Mime-types received from Bluetooth OPP

        ArrayList<String> paths; // Raw paths on the filesystem for Beam-stored files
        HashMap<String, String> mimeTypes; // Mime-types associated with each path
        HashMap<String, Uri> mediaUris; // URIs found by the media scanner for each path
        int urisScanned;

        public HandoverTransfer(String sourceAddress, boolean incoming) {
            synchronized (HandoverManager.this) {
                this.notificationId = mNotificationId++;
            }
            this.lastUpdate = SystemClock.elapsedRealtime();
            this.progress = 0.0f;
            this.state = STATE_NEW;
            this.btUris = new ArrayList<Uri>();
            this.btMimeTypes = new ArrayList<String>();
            this.paths = new ArrayList<String>();
            this.mimeTypes = new HashMap<String, String>();
            this.mediaUris = new HashMap<String, Uri>();
            this.sourceAddress = sourceAddress;
            this.incoming = incoming;
            this.handler = new Handler(mContext.getMainLooper(), this);
            this.cancelIntent = buildCancelIntent();
            this.urisScanned = 0;
            this.device = mBluetoothAdapter.getRemoteDevice(sourceAddress);

            handler.sendEmptyMessageDelayed(MSG_TRANSFER_TIMEOUT, ALIVE_CHECK_MS);
        }

        public synchronized void updateFileProgress(float progress) {
            if (!isRunning()) return; // Ignore when we're no longer running

            handler.removeMessages(MSG_NEXT_TRANSFER_TIMER);

            this.progress = progress;

            // We're still receiving data from this device - keep it in
            // the whitelist for a while longer
            if (incoming) whitelistOppDevice(device);

            updateStateAndNotification(STATE_IN_PROGRESS);
        }

        public synchronized void finishTransfer(boolean success, Uri uri, String mimeType) {
            if (!isRunning()) return; // Ignore when we're no longer running

            if (success && uri != null) {
                if (DBG) Log.d(TAG, "Transfer success, uri " + uri + " mimeType " + mimeType);
                this.progress = 1.0f;
                if (mimeType == null) {
                    mimeType = BluetoothOppHandover.getMimeTypeForUri(mContext, uri);
                }
                if (mimeType != null) {
                    btUris.add(uri);
                    btMimeTypes.add(mimeType);
                } else {
                    if (DBG) Log.d(TAG, "Could not get mimeType for file.");
                }
            } else {
                Log.e(TAG, "Handover transfer failed");
                // Do wait to see if there's another file coming.
            }
            handler.removeMessages(MSG_NEXT_TRANSFER_TIMER);
            handler.sendEmptyMessageDelayed(MSG_NEXT_TRANSFER_TIMER, WAIT_FOR_NEXT_TRANSFER_MS);
            updateStateAndNotification(STATE_W4_NEXT_TRANSFER);
        }

        public synchronized boolean isRunning() {
            if (state != STATE_NEW && state != STATE_IN_PROGRESS && state != STATE_W4_NEXT_TRANSFER) {
                return false;
            } else {
                return true;
            }
        }

        synchronized void cancel() {
            if (!isRunning()) return;

            // Delete all files received so far
            for (Uri uri : btUris) {
                File file = new File(uri.getPath());
                if (file.exists()) file.delete();
            }

            updateStateAndNotification(STATE_CANCELLED);
        }

        synchronized void updateNotification() {
            if (!incoming) return; // No notifications for outgoing transfers

            Builder notBuilder = new Notification.Builder(mContext);

            if (state == STATE_NEW || state == STATE_IN_PROGRESS ||
                    state == STATE_W4_NEXT_TRANSFER || state == STATE_W4_MEDIA_SCANNER) {
                notBuilder.setAutoCancel(false);
                notBuilder.setSmallIcon(android.R.drawable.stat_sys_download);
                notBuilder.setTicker(mContext.getString(R.string.beam_progress));
                notBuilder.setContentTitle(mContext.getString(R.string.beam_progress));
                notBuilder.addAction(R.drawable.ic_menu_cancel_holo_dark,
                        mContext.getString(R.string.cancel), cancelIntent);
                notBuilder.setDeleteIntent(cancelIntent);
                // We do have progress indication on a per-file basis, but in a multi-file
                // transfer we don't know the total progress. So for now, just show an
                // indeterminate progress bar.
                notBuilder.setProgress(100, 0, true);
            } else if (state == STATE_SUCCESS) {
                notBuilder.setAutoCancel(true);
                notBuilder.setSmallIcon(android.R.drawable.stat_sys_download_done);
                notBuilder.setTicker(mContext.getString(R.string.beam_complete));
                notBuilder.setContentTitle(mContext.getString(R.string.beam_complete));
                notBuilder.setContentText(mContext.getString(R.string.beam_touch_to_view));

                Intent viewIntent = buildViewIntent();
                PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, viewIntent, 0);

                notBuilder.setContentIntent(contentIntent);

                // Play Beam success sound
                NfcService.getInstance().playSound(NfcService.SOUND_END);
            } else if (state == STATE_FAILED) {
                notBuilder.setAutoCancel(false);
                notBuilder.setSmallIcon(android.R.drawable.stat_sys_download_done);
                notBuilder.setTicker(mContext.getString(R.string.beam_failed));
                notBuilder.setContentTitle(mContext.getString(R.string.beam_failed));
            } else if (state == STATE_CANCELLED) {
                notBuilder.setAutoCancel(false);
                notBuilder.setSmallIcon(android.R.drawable.stat_sys_download_done);
                notBuilder.setTicker(mContext.getString(R.string.beam_canceled));
                notBuilder.setContentTitle(mContext.getString(R.string.beam_canceled));
            } else {
                return;
            }

            mNotificationManager.notify(mNotificationId, notBuilder.build());
        }

        synchronized void updateStateAndNotification(int newState) {
            this.state = newState;
            this.lastUpdate = SystemClock.elapsedRealtime();

            if (handler.hasMessages(MSG_TRANSFER_TIMEOUT)) {
                // Update timeout timer
                handler.removeMessages(MSG_TRANSFER_TIMEOUT);
                handler.sendEmptyMessageDelayed(MSG_TRANSFER_TIMEOUT, ALIVE_CHECK_MS);
            }
            updateNotification();
        }

        synchronized void processFiles() {
            // Check the amount of files we received in this transfer;
            // If more than one, create a separate directory for it.
            String extRoot = Environment.getExternalStorageDirectory().getPath();
            File beamPath = new File(extRoot + "/" + BEAM_DIR);

            if (!checkMediaStorage(beamPath) || btUris.size() == 0) {
                Log.e(TAG, "Media storage not valid or no uris received.");
                updateStateAndNotification(STATE_FAILED);
                return;
            }

            if (btUris.size() > 1) {
                beamPath = generateMultiplePath(extRoot + "/" + BEAM_DIR + "/");
                if (!beamPath.isDirectory() && !beamPath.mkdir()) {
                    Log.e(TAG, "Failed to create multiple path " + beamPath.toString());
                    updateStateAndNotification(STATE_FAILED);
                    return;
                }
            }

            for (int i = 0; i < btUris.size(); i++) {
                Uri uri = btUris.get(i);
                String mimeType = btMimeTypes.get(i);

                File srcFile = new File(uri.getPath());

                File dstFile = generateUniqueDestination(beamPath.getAbsolutePath(),
                        uri.getLastPathSegment());
                if (!srcFile.renameTo(dstFile)) {
                    if (DBG) Log.d(TAG, "Failed to rename from " + srcFile + " to " + dstFile);
                    srcFile.delete();
                    return;
                } else {
                    paths.add(dstFile.getAbsolutePath());
                    mimeTypes.put(dstFile.getAbsolutePath(), mimeType);
                    if (DBG) Log.d(TAG, "Did successful rename from " + srcFile + " to " + dstFile);
                }
            }

            // We can either add files to the media provider, or provide an ACTION_VIEW
            // intent to the file directly. We base this decision on the mime type
            // of the first file; if it's media the platform can deal with,
            // use the media provider, if it's something else, just launch an ACTION_VIEW
            // on the file.
            String mimeType = mimeTypes.get(paths.get(0));
            if (mimeType.startsWith("image/") || mimeType.startsWith("video/") ||
                    mimeType.startsWith("audio/")) {
                String[] arrayPaths = new String[paths.size()];
                MediaScannerConnection.scanFile(mContext, paths.toArray(arrayPaths), null, this);
                updateStateAndNotification(STATE_W4_MEDIA_SCANNER);
            } else {
                // We're done.
                updateStateAndNotification(STATE_SUCCESS);
            }

        }

        public boolean handleMessage(Message msg) {
            if (msg.what == MSG_NEXT_TRANSFER_TIMER) {
                // We didn't receive a new transfer in time, finalize this one
                if (incoming) {
                    processFiles();
                } else {
                    updateStateAndNotification(STATE_SUCCESS);
                }
                return true;
            } else if (msg.what == MSG_TRANSFER_TIMEOUT) {
                // No update on this transfer for a while, check
                // to see if it's still running, and fail it if it is.
                if (isRunning()) {
                    updateStateAndNotification(STATE_FAILED);
                }
            }
            return false;
        }

        public synchronized void onScanCompleted(String path, Uri uri) {
            if (DBG) Log.d(TAG, "Scan completed, path " + path + " uri " + uri);
            if (uri != null) {
                mediaUris.put(path, uri);
            }
            urisScanned++;
            if (urisScanned == paths.size()) {
                // We're done
                updateStateAndNotification(STATE_SUCCESS);
            }
        }

        boolean checkMediaStorage(File path) {
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                if (!path.isDirectory() && !path.mkdir()) {
                    Log.e(TAG, "Not dir or not mkdir " + path.getAbsolutePath());
                    return false;
                }
                return true;
            } else {
                Log.e(TAG, "External storage not mounted, can't store file.");
                return false;
            }
        }

        synchronized Intent buildViewIntent() {
            if (paths.size() == 0) return null;

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);

            String filePath = paths.get(0);
            Uri mediaUri = mediaUris.get(filePath);
            Uri uri =  mediaUri != null ? mediaUri :
                Uri.parse(ContentResolver.SCHEME_FILE + "://" + filePath);
            viewIntent.setDataAndTypeAndNormalize(uri, mimeTypes.get(filePath));

            return viewIntent;
        }

        PendingIntent buildCancelIntent() {
            Intent intent = new Intent(ACTION_CANCEL_HANDOVER_TRANSFER);
            intent.putExtra(EXTRA_SOURCE_ADDRESS, sourceAddress);
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent, 0);

            return pi;
        }

        synchronized File generateUniqueDestination(String path, String fileName) {
            int dotIndex = fileName.lastIndexOf(".");
            String extension = null;
            String fileNameWithoutExtension = null;
            if (dotIndex < 0) {
                extension = "";
                fileNameWithoutExtension = fileName;
            } else {
                extension = fileName.substring(dotIndex);
                fileNameWithoutExtension = fileName.substring(0, dotIndex);
            }
            File dstFile = new File(path + File.separator + fileName);
            int count = 0;
            while (dstFile.exists()) {
                dstFile = new File(path + File.separator + fileNameWithoutExtension + "-" +
                        Integer.toString(count) + extension);
                count++;
            }
            return dstFile;
        }

        synchronized File generateMultiplePath(String beamRoot) {
            // Generate a unique directory with the date
            String format = "yyyy-MM-dd";
            SimpleDateFormat sdf = new SimpleDateFormat(format);
            String newPath = beamRoot + "beam-" + sdf.format(new Date());
            File newFile = new File(newPath);
            int count = 0;
            while (newFile.exists()) {
                newPath = beamRoot + "beam-" + sdf.format(new Date()) + "-" +
                        Integer.toString(count);
                newFile = new File(newPath);
                count++;
            }

            return newFile;
        }
    }

    synchronized HandoverTransfer getOrCreateHandoverTransfer(String sourceAddress, boolean incoming,
            boolean create) {
        Pair<String, Boolean> key = new Pair<String, Boolean>(sourceAddress, incoming);
        if (mTransfers.containsKey(key)) {
            HandoverTransfer transfer = mTransfers.get(key);
            if (transfer.isRunning()) {
                return transfer;
            } else {
                if (create) mTransfers.remove(key); // new one created below
            }
        }
        if (create) {
            HandoverTransfer transfer = new HandoverTransfer(sourceAddress, incoming);
            mTransfers.put(key, transfer);

            return transfer;
        } else {
            return null;
        }
    }

    public HandoverManager(Context context) {
        mContext = context;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(mContext, this, BluetoothProfile.HEADSET);
            mBluetoothAdapter.getProfileProxy(mContext, this, BluetoothProfile.A2DP);
        }

        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);

        mTransfers = new HashMap<Pair<String, Boolean>, HandoverTransfer>();
        mHandoverPowerManager = new HandoverPowerManager(context);

        IntentFilter filter = new IntentFilter(ACTION_BT_OPP_TRANSFER_DONE);
        filter.addAction(ACTION_BT_OPP_TRANSFER_PROGRESS);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(ACTION_CANCEL_HANDOVER_TRANSFER);
        mContext.registerReceiver(mReceiver, filter, HANDOVER_STATUS_PERMISSION, null);
    }

    synchronized void cleanupTransfers() {
        Iterator<Map.Entry<Pair<String, Boolean>, HandoverTransfer>> it = mTransfers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Pair<String, Boolean>, HandoverTransfer> pair = it.next();
            HandoverTransfer transfer = pair.getValue();
            if (!transfer.isRunning()) {
                it.remove();
            }
        }
    }

    static NdefRecord createCollisionRecord() {
        byte[] random = new byte[2];
        new Random().nextBytes(random);
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_HANDOVER_REQUEST, null, random);
    }

    NdefRecord createBluetoothAlternateCarrierRecord(boolean activating) {
        byte[] payload = new byte[4];
        payload[0] = (byte) (activating ? CARRIER_POWER_STATE_ACTIVATING :
            CARRIER_POWER_STATE_ACTIVE);  // Carrier Power State: Activating or active
        payload[1] = 1;   // length of carrier data reference
        payload[2] = 'b'; // carrier data reference: ID for Bluetooth OOB data record
        payload[3] = 0;  // Auxiliary data reference count
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_ALTERNATIVE_CARRIER, null, payload);
    }

    NdefRecord createBluetoothOobDataRecord() {
        byte[] payload = new byte[8];
        payload[0] = 0;
        payload[1] = (byte)payload.length;

        synchronized (HandoverManager.this) {
            if (mLocalBluetoothAddress == null) {
                mLocalBluetoothAddress = mBluetoothAdapter.getAddress();
            }

            byte[] addressBytes = addressToReverseBytes(mLocalBluetoothAddress);
            System.arraycopy(addressBytes, 0, payload, 2, 6);
        }

        return new NdefRecord(NdefRecord.TNF_MIME_MEDIA, TYPE_BT_OOB, new byte[]{'b'}, payload);
    }

    public boolean isHandoverSupported() {
        return (mBluetoothAdapter != null);
    }

    public NdefMessage createHandoverRequestMessage() {
        if (mBluetoothAdapter == null) return null;

        return new NdefMessage(createHandoverRequestRecord(), createBluetoothOobDataRecord());
    }

    NdefMessage createHandoverSelectMessage(boolean activating) {
        return new NdefMessage(createHandoverSelectRecord(activating), createBluetoothOobDataRecord());
    }

    NdefRecord createHandoverSelectRecord(boolean activating) {
        NdefMessage nestedMessage = new NdefMessage(createBluetoothAlternateCarrierRecord(activating));
        byte[] nestedPayload = nestedMessage.toByteArray();

        ByteBuffer payload = ByteBuffer.allocate(nestedPayload.length + 1);
        payload.put((byte)0x12);  // connection handover v1.2
        payload.put(nestedPayload);

        byte[] payloadBytes = new byte[payload.position()];
        payload.position(0);
        payload.get(payloadBytes);
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_HANDOVER_SELECT, null,
                payloadBytes);

    }

    NdefRecord createHandoverRequestRecord() {
        NdefMessage nestedMessage = new NdefMessage(createCollisionRecord(),
                createBluetoothAlternateCarrierRecord(false));
        byte[] nestedPayload = nestedMessage.toByteArray();

        ByteBuffer payload = ByteBuffer.allocate(nestedPayload.length + 1);
        payload.put((byte)0x12);  // connection handover v1.2
        payload.put(nestedMessage.toByteArray());

        byte[] payloadBytes = new byte[payload.position()];
        payload.position(0);
        payload.get(payloadBytes);
        return new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_HANDOVER_REQUEST, null,
                payloadBytes);
    }

    /**
     * Return null if message is not a Handover Request,
     * return the Handover Select response if it is.
     */
    public NdefMessage tryHandoverRequest(NdefMessage m) {
        if (m == null) return null;
        if (mBluetoothAdapter == null) return null;

        if (DBG) Log.d(TAG, "tryHandoverRequest():" + m.toString());

        NdefRecord r = m.getRecords()[0];
        if (r.getTnf() != NdefRecord.TNF_WELL_KNOWN) return null;
        if (!Arrays.equals(r.getType(), NdefRecord.RTD_HANDOVER_REQUEST)) return null;

        // we have a handover request, look for BT OOB record
        BluetoothHandoverData bluetoothData = null;
        for (NdefRecord oob : m.getRecords()) {
            if (oob.getTnf() == NdefRecord.TNF_MIME_MEDIA &&
                    Arrays.equals(oob.getType(), TYPE_BT_OOB)) {
                bluetoothData = parseBtOob(ByteBuffer.wrap(oob.getPayload()));
                break;
            }
        }
        if (bluetoothData == null) return null;

        boolean bluetoothActivating = false;

        synchronized(HandoverManager.this) {
            if (!mHandoverPowerManager.isBluetoothEnabled()) {
                if (!mHandoverPowerManager.enableBluetooth()) {
                    return null;
                }
                bluetoothActivating = true;
            } else {
                mHandoverPowerManager.resetTimer();
            }

            // Create the initial transfer object
            HandoverTransfer transfer = getOrCreateHandoverTransfer(
                    bluetoothData.device.getAddress(), true, true);
            transfer.updateNotification();
        }

        // BT OOB found, whitelist it for incoming OPP data
        whitelistOppDevice(bluetoothData.device);

        // return BT OOB record so they can perform handover
        return (createHandoverSelectMessage(bluetoothActivating));
    }

    void whitelistOppDevice(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "Whitelisting " + device + " for BT OPP");
        Intent intent = new Intent(ACTION_WHITELIST_DEVICE);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mContext.sendBroadcast(intent);
    }

    public boolean tryHandover(NdefMessage m) {
        if (m == null) return false;
        if (mBluetoothAdapter == null) return false;

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
                    handover.name, mHandoverPowerManager, mBluetoothA2dp, mBluetoothHeadset, this);
            mBluetoothHeadsetHandover.start();
        }
        return true;
    }

    // This starts sending an Uri over BT
    public void doHandoverUri(Uri[] uris, NdefMessage m) {
        if (mBluetoothAdapter == null) return;

        BluetoothHandoverData data = parse(m);
        if (data != null && data.valid) {
            // Register a new handover transfer object
            getOrCreateHandoverTransfer(data.device.getAddress(), false, true);
            BluetoothOppHandover handover = new BluetoothOppHandover(mContext, data.device,
                uris, mHandoverPowerManager, data.carrierActivating);
            handover.start();
        }
    }

    boolean isCarrierActivating(NdefRecord handoverRec, byte[] carrierId) {
        byte[] payload = handoverRec.getPayload();
        if (payload == null || payload.length <= 1) return false;
        // Skip version
        byte[] payloadNdef = new byte[payload.length - 1];
        System.arraycopy(payload, 1, payloadNdef, 0, payload.length - 1);
        NdefMessage msg;
        try {
            msg = new NdefMessage(payloadNdef);
        } catch (FormatException e) {
            return false;
        }

        for (NdefRecord alt : msg.getRecords()) {
            byte[] acPayload = alt.getPayload();
            if (acPayload != null) {
                ByteBuffer buf = ByteBuffer.wrap(acPayload);
                int cps = buf.get() & 0x03; // Carrier Power State is in lower 2 bits
                int carrierRefLength = buf.get() & 0xFF;
                if (carrierRefLength != carrierId.length) return false;

                byte[] carrierRefId = new byte[carrierRefLength];
                buf.get(carrierRefId);
                if (Arrays.equals(carrierRefId, carrierId)) {
                    // Found match, returning whether power state is activating
                    return (cps == CARRIER_POWER_STATE_ACTIVATING);
                }
            }
        }

        return true;
    }

    BluetoothHandoverData parseHandoverSelect(NdefMessage m) {
        // TODO we could parse this a lot more strictly; right now
        // we just search for a BT OOB record, and try to cross-reference
        // the carrier state inside the 'hs' payload.
        for (NdefRecord oob : m.getRecords()) {
            if (oob.getTnf() == NdefRecord.TNF_MIME_MEDIA &&
                    Arrays.equals(oob.getType(), TYPE_BT_OOB)) {
                BluetoothHandoverData data = parseBtOob(ByteBuffer.wrap(oob.getPayload()));
                if (data != null && isCarrierActivating(m.getRecords()[0], oob.getId())) {
                    data.carrierActivating = true;
                }
                return data;
            }
        }

        return null;
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
            return parseHandoverSelect(m);
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
    public void onBluetoothHeadsetHandoverComplete(boolean connected) {
        synchronized (HandoverManager.this) {
            mBluetoothHeadsetHandover = null;
            mBluetoothHeadsetConnected = connected;
        }
    }

    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF) {
                    mHandoverPowerManager.stopMonitoring();
                }

                return;
            } else if (action.equals(ACTION_CANCEL_HANDOVER_TRANSFER)) {
                String sourceAddress = intent.getStringExtra(EXTRA_SOURCE_ADDRESS);
                HandoverTransfer transfer = getOrCreateHandoverTransfer(sourceAddress, true,
                        false);
                if (transfer != null) {
                    transfer.cancel();
                }
            } else if (action.equals(ACTION_BT_OPP_TRANSFER_PROGRESS) ||
                    action.equals(ACTION_BT_OPP_TRANSFER_DONE)) {
                // Clean up old transfers no longer in progress
                cleanupTransfers();

                int direction = intent.getIntExtra(EXTRA_BT_OPP_TRANSFER_DIRECTION, -1);
                int id = intent.getIntExtra(EXTRA_BT_OPP_TRANSFER_ID, -1);
                String sourceAddress = intent.getStringExtra(EXTRA_BT_OPP_ADDRESS);

                if (direction == -1 || id == -1 || sourceAddress == null) return;
                boolean incoming = (direction == DIRECTION_BLUETOOTH_INCOMING);

                HandoverTransfer transfer = getOrCreateHandoverTransfer(sourceAddress, incoming,
                        false);
                if (transfer == null) {
                    // There is no transfer running for this source address; most likely
                    // the transfer was cancelled. We need to tell BT OPP to stop transferring
                    // in case this was an incoming transfer
                    Intent cancelIntent = new Intent("android.btopp.intent.action.STOP_HANDOVER_TRANSFER");
                    cancelIntent.putExtra(EXTRA_BT_OPP_TRANSFER_ID, id);
                    mContext.sendBroadcast(cancelIntent);
                    return;
                }

                if (action.equals(ACTION_BT_OPP_TRANSFER_DONE)) {
                    int handoverStatus = intent.getIntExtra(EXTRA_BT_OPP_TRANSFER_STATUS,
                            HANDOVER_TRANSFER_STATUS_FAILURE);

                    if (handoverStatus == HANDOVER_TRANSFER_STATUS_SUCCESS) {
                        String uriString = intent.getStringExtra(EXTRA_BT_OPP_TRANSFER_URI);
                        String mimeType = intent.getStringExtra(EXTRA_BT_OPP_TRANSFER_MIMETYPE);
                        Uri uri = Uri.parse(uriString);
                        if (uri.getScheme() == null) {
                            uri = Uri.fromFile(new File(uri.getPath()));
                        }
                        transfer.finishTransfer(true, uri, mimeType);
                    } else {
                        transfer.finishTransfer(false, null, null);
                    }
                } else if (action.equals(ACTION_BT_OPP_TRANSFER_PROGRESS)) {
                    float progress = intent.getFloatExtra(EXTRA_BT_OPP_TRANSFER_PROGRESS, 0.0f);
                    transfer.updateFileProgress(progress);
                }
            }
        }
    };

}
