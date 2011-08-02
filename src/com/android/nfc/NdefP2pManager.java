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
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.nfc.INdefPushCallback;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class NdefP2pManager {
    // TODO dynamically assign SAP values
    static final int NDEFPUSH_SAP = 0x10;

    static final String TAG = "P2PManager";
    static final boolean DBG = true;

    final ScreenshotWindowAnimator mScreenshot;
    final NdefPushServer mNdefPushServer;
    final SnepServer mDefaultSnepServer;
    final ActivityManager mActivityManager;
    final PackageManager mPackageManager;
    final Context mContext;
    final P2pStatusListener mListener;

    // only used on UI Thread
    P2pTask mActiveTask;

    final static Uri mProfileUri = Profile.CONTENT_VCARD_URI.buildUpon().
            appendQueryParameter(Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO, "true").
            build();

    /** Locked on NdefP2pManager.class */
    NdefMessage mForegroundMsg;
    INdefPushCallback mCallback;

    public NdefP2pManager(Context context, P2pStatusListener listener) {
        mNdefPushServer = new NdefPushServer(NDEFPUSH_SAP);
        mDefaultSnepServer = new SnepServer(mDefaultSnepCallback);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = context.getPackageManager();
        mContext = context;
        mScreenshot = new ScreenshotWindowAnimator(context);
        mListener = listener;
    }

    public void enableNdefServer() {
        // Default
        mDefaultSnepServer.start();
        // Legacy
        mNdefPushServer.start();
    }

    public void disableNdefServer() {
        mDefaultSnepServer.stop();
        mNdefPushServer.stop();
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

    public NdefMessage getMeProfile() {
        NdefMessage ndefMsg = null;
        ContentResolver resolver = mContext.getContentResolver();
        ByteArrayOutputStream ndefBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int r;
        try {
            InputStream vcardInputStream = resolver.openInputStream(mProfileUri);
            while ((r = vcardInputStream.read(buffer)) > 0) {
                ndefBytes.write(buffer, 0, r);
            }
            if (ndefBytes.size() > 0) {
                NdefRecord vcardRecord = new NdefRecord(NdefRecord.TNF_MIME_MEDIA,
                        "text/x-vCard".getBytes(), new byte[] {},
                        ndefBytes.toByteArray());
                ndefMsg = new NdefMessage(new NdefRecord[] {vcardRecord});
            } else {
                Log.w(TAG, "Me profile vcard size is empty.");
            }
        } catch (IOException e) {
            Log.w(TAG, "IOException on creating me profile.");
        }

        return ndefMsg;
    }

    public boolean isForegroundPushEnabled() {
        synchronized (this) {
            return mForegroundMsg != null || mCallback != null;
        }
    }

    void llcpActivated() {
        if (DBG) Log.d(TAG, "LLCP connection up and running");

        mListener.onP2pBegin();

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
                        NdefRecord appRecord = NdefRecord.createUri(
                                Uri.parse("http://market.android.com/search?q=pname:" + pkg));
                        foregroundMsg = new NdefMessage(new NdefRecord[] { appRecord });
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Bad package returned from ActivityManager: " + pkg);
                }
            } else {
                Log.d(TAG, "no foreground activity");
            }
        }

        if (mActiveTask != null) {
            mActiveTask.cancel(true);
        }
        mActiveTask = new P2pTask(foregroundMsg);
        mActiveTask.execute();
    }

    void llcpDeactivated() {
        if (DBG) Log.d(TAG, "LLCP deactivated.");

        if (mActiveTask != null) {
            mActiveTask.cancel(true);
            mActiveTask = null;
        }
    }

    final class P2pTask extends AsyncTask<Void, Void, Void> {
        final NdefMessage mMessage;
        boolean mSuccess = false;

        public P2pTask(NdefMessage msg) {
            mMessage = msg;
        }

        @Override
        public void onPreExecute() {
            if (mMessage != null) {
                mScreenshot.start();
            }
        }

        @Override
        public Void doInBackground(Void... args) {
            try {
                if (mMessage != null) {
                    if (DBG) Log.d(TAG, "Sending ndef via SNEP");
                    mSuccess = doSnepProtocol(mMessage);
                }
            } catch (IOException e) {
                Log.d(TAG, "Failed to connect over SNEP, trying NPP");

                if (isCancelled()) {
                    return null;
                }

                mSuccess = new NdefPushClient().push(mMessage);
            }

            INdefPushCallback callback;
            synchronized (NdefP2pManager.this) {
                callback = mCallback;
            }

            if (callback != null) {
                try {
                    callback.onMessagePushed();
                } catch (RemoteException e) {
                    // Ignore
                }
            }

            if (isCancelled()) {
                return null;
            }

            return null;
        }

        @Override
        public void onCancelled() {
            if (mMessage != null) {
                mScreenshot.complete(mSuccess);
                // Call error here since a previous call to onP2pEnd() will have played the success
                // sound and this will be ignored. If no one has called that and the LLCP link
                // is broken we want to play the error sound.
                mListener.onP2pError();
            }
            mActiveTask = null;
        }

        @Override
        public void onPostExecute(Void result) {
            // Make sure to stop the screenshot animation
            if (mMessage != null) {
                mScreenshot.complete(mSuccess);
            }

            // Play the end sound if successful
            if (mMessage != null && mSuccess) {
                mListener.onP2pEnd();
            }

            mActiveTask = null;
        }
    }

    boolean doSnepProtocol(NdefMessage msg) throws IOException {
        SnepClient snepClient = new SnepClient();
        try {
            snepClient.connect();
        } catch (IOException e) {
            // Throw exception to fall back to NPP.
            snepClient.close();
            throw new IOException("SNEP not available.", e);
        }

        try {
            snepClient.put(msg);
            return true;
        } catch (IOException e) {
            // SNEP available but had errors, don't fall back to NPP.
        } finally {
            snepClient.close();
        }
        return false;
    }

    final SnepServer.Callback mDefaultSnepCallback = new SnepServer.Callback() {
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
    };
}
