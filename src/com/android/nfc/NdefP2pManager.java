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

import com.android.nfc.ndefpush.NdefPushClient;
import com.android.nfc.ndefpush.NdefPushServer;
import com.android.nfc.snep.SnepClient;
import com.android.nfc.snep.SnepMessage;
import com.android.nfc.snep.SnepServer;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.nfc.INdefPushCallback;
import android.nfc.INfcAdapter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.util.Log;


import java.io.IOException;
import java.util.List;

public class NdefP2pManager implements SnepServer.Callback {
    private static final String TAG = "P2PManager";
    private static final boolean DBG = false;
    private final INfcAdapter.Stub mNfcAdapter;
    private final NdefPushServer mNdefPushServer;
    private final SnepServer mSnepServer;
    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;

    /** Locked on NdefP2pManager.class */
    NdefMessage mForegroundMsg;
    INdefPushCallback mCallback;

    public NdefP2pManager(Context context, INfcAdapter.Stub adapter) {
        mNfcAdapter = adapter;
        mNdefPushServer = new NdefPushServer();
        mSnepServer = new SnepServer(this);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = context.getPackageManager();
    }

    public void enablePushServer() {
        mSnepServer.start();
        mNdefPushServer.start();
    }

    public void disablePushServer() {
        mSnepServer.stop();
        mNdefPushServer.stop();
    }

    @Override
    public SnepMessage doPut(NdefMessage msg) {
        NfcService.getInstance().sendMockNdefTag(msg);
        return SnepMessage.getMessage(SnepMessage.RESPONSE_SUCCESS);
    }

    @Override
    public SnepMessage doGet(int acceptableLength, NdefMessage msg) {
        if (DBG) Log.d(TAG, "GET not supported.");
        return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_IMPLEMENTED);
    }

    public boolean setForegroundMessage(NdefMessage msg) {
        synchronized (this) {
            boolean set = mForegroundMsg != null;
            mForegroundMsg = msg;
            return set;
        }
    }

    public boolean setForegroundCallback(INdefPushCallback callback) {
        synchronized (this) {
            boolean set = mCallback != null;
            mCallback = callback;
            return set;
        }
    }

    public NdefMessage getForegroundMessage() {
        synchronized (this) {
            return mForegroundMsg;
        }
    }

    /*package*/ void llcpActivated() {
        if (DBG) Log.d(TAG, "LLCP connection up and running");

        NdefMessage foregroundMsg;
        synchronized (this) {
            foregroundMsg = mForegroundMsg;
        }

        INdefPushCallback callback;
        synchronized (this) {
            callback = mCallback;
        }

        if (callback != null) {
            try {
                foregroundMsg = callback.onConnect();
            } catch (RemoteException e) {
                // Ignore
            }
        }

        if (foregroundMsg == null) {
            List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
            if (tasks.size() > 0) {
                String pkg = tasks.get(0).baseActivity.getPackageName();
                try {
                    ApplicationInfo appInfo = mPackageManager.getApplicationInfo(pkg, 0);
                    if (0 == (appInfo.flags & ApplicationInfo.FLAG_SYSTEM)) {
                        byte[] appUri = ("market.android.com/search?q=pname:" + pkg).getBytes();
                        byte[] recordUri = new byte[appUri.length + 1];
                        recordUri[0] = 0x03; // "http://"
                        System.arraycopy(appUri, 0, recordUri, 1, appUri.length);
                        NdefRecord appRecord = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
                                NdefRecord.RTD_URI, new byte[0], recordUri);
                        foregroundMsg = new NdefMessage(new NdefRecord[] { appRecord });
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Bad package returned from ActivityManager: " + pkg);
                }
            } else {
                Log.d(TAG, "no foreground activity");
            }
        }

        NdefMessage myTag = null;
        try {
            myTag = mNfcAdapter.localGet();
        } catch (RemoteException e) {
            // Ignore
        }

        if (foregroundMsg != null && myTag != null) {
            if (DBG) Log.d(TAG, "sending foreground and my tag");
            new SendAsync().execute(foregroundMsg, myTag);
        } else if (myTag != null) {
            if (DBG) Log.d(TAG, "sending my tag");
            new SendAsync().execute(myTag);
        } else if (foregroundMsg != null) {
            if (DBG) Log.d(TAG, "sending foreground");
            new SendAsync().execute(foregroundMsg);
        } else {
            if (DBG) Log.d(TAG, "no tags set, bailing");
            return;
        }
    }

    /*package*/ void llcpDeactivated() {
        if (DBG) Log.d(TAG, "LLCP deactivated.");
    }

    final class SendAsync extends AsyncTask<NdefMessage, Void, Void> {
        @Override
        public Void doInBackground(NdefMessage... msgs) {
            try {
                doSnepProtocol(msgs);
            } catch (IOException e) {
                if (DBG) Log.d(TAG, "Failed to connect over SNEP, trying NPP");
                new NdefPushClient().push(msgs);
            }

            INdefPushCallback callback;
            synchronized (this) {
                callback = mCallback;
            }

            if (callback != null) {
                try {
                    callback.onMessagePushed();
                } catch (RemoteException e) {
                    // Ignore
                }
            }

            return null;
        }
    }

    private void doSnepProtocol(NdefMessage[] msgs) throws IOException {
        SnepClient snepClient = new SnepClient();
        try {
            snepClient.connect();
        } catch (IOException e) {
            // Throw exception to fall back to NPP.
            snepClient.close();
            throw new IOException("SNEP not available.", e);
        }

        try {
            snepClient.put(msgs[0]);
        } catch (IOException e) {
            // SNEP available but had errors, don't fall back to NPP.
        } finally {
            snepClient.close();
        }
    }
}
