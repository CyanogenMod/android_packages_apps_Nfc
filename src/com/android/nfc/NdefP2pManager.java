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
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.Vibrator;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
import android.util.Log;
import android.view.OrientationEventListener;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.List;

public class NdefP2pManager implements Handler.Callback, ScreenshotWindowAnimator.Callback {
    // TODO dynamically assign SAP values
    static final int NDEFPUSH_SAP = 0x10;

    static final int RECEIVE_TIMEOUT_MS = 250;

    static final int STATE_WAITING = 0;
    static final int STATE_SUCCESS = 1;
    static final int STATE_FAILURE = 2;

    static final int MSG_RECEIVE_SUCCESS = 0;
    static final int MSG_TIMEOUT = 1;
    static final int MSG_ENABLE = 2;
    static final int MSG_DISABLE = 3;

    static final long[] mVibPattern = {0, 100, 100, 150, 100, 250, 100, 10000};
    static final String TAG = "P2PManager";
    static final boolean DBG = true;

    final ScreenshotWindowAnimator mScreenshot;
    final NdefPushServer mNdefPushServer;
    final SnepServer mDefaultSnepServer;
    final ActivityManager mActivityManager;
    final PackageManager mPackageManager;
    final Context mContext;
    final P2pStatusListener mListener;
    final Vibrator mVibrator;

    // Used only from the UI thread
    PushTask mPushTask;
    Handler mHandler;
    int mSendState;
    int mReceiveState;
    boolean mAnimating;
    boolean mSendStarted;
    boolean mIsP2pEnabled;

    final static Uri mProfileUri = Profile.CONTENT_VCARD_URI.buildUpon().
            appendQueryParameter(Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO, "true").
            build();

    NdefMessage mPushMsg;

    // Locked on NdefP2pManager.this
    NdefMessage mForegroundMsg;
    INdefPushCallback mCallback;

    OrientationEventListener mOrientationListener;

    public NdefP2pManager(Context context, P2pStatusListener listener) {
        mNdefPushServer = new NdefPushServer(NDEFPUSH_SAP, mNppCallback);
        mDefaultSnepServer = new SnepServer(mDefaultSnepCallback);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = context.getPackageManager();
        mContext = context;
        mScreenshot = new ScreenshotWindowAnimator(context, this);
        mListener = listener;
        mAnimating = false;
        mHandler = new Handler(this);
        mSendState = STATE_WAITING;
        mReceiveState = STATE_WAITING;
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mSendStarted = false;
        mIsP2pEnabled = false;

        mOrientationListener = new OrientationEventListener(context) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (mAnimating &&  (orientation > 50 && orientation < 310)) {
                    onConfirmSend();
                }
            }
        };
    }

    /**
     * Enable P2P features (both send and receive).
     * May be called from any thread.
     */
    public void enableP2p() {
        // Handle this on the UI thread, so that we can more easily handle
        // serialization if there is an LLCP link in progress
        mHandler.sendEmptyMessage(MSG_ENABLE);
    }

    /**
     * Disable P2P features (both send and receive).
     * May be called from any thread.
     */
    public void disableP2p() {
        // Handle this on the UI thread, so that we can more easily handle
        // serialization if there is an LLCP link in progress
        mHandler.sendEmptyMessage(MSG_DISABLE);
    }

    /**
     * Set static foreground message. May be called from any thread.
     * Allow the message to be set even if P2P is disabled,
     * so that it is ready to go if P2P is enabled.
     */
    public boolean setForegroundMessage(NdefMessage msg) {
        synchronized (this) {
            boolean set = mForegroundMsg != null;
            mForegroundMsg = msg;
            return set;
        }
    }

    /**
     * Set callback for NDEF message. May be called from any thread.
     * Allow the callback to be set even if P2P is disabled,
     * so that it is ready to go if P2P is enabled.
     */
    public boolean setForegroundCallback(INdefPushCallback callback) {
        synchronized (this) {
            boolean set = mCallback != null;
            mCallback = callback;
            return set;
        }
    }

    /**
     * Must be called on UI thread.
     */
    public void onLlcpActivated() {
        if (DBG) Log.d(TAG, "LLCP connection up and running");

        if (!mIsP2pEnabled) {
            if (DBG) Log.d(TAG, "P2P disabled, ignoring");
            return;
        }

        mOrientationListener.enable();
        mVibrator.vibrate(mVibPattern, 6);
        mSendState = STATE_WAITING;
        mReceiveState = STATE_WAITING;
        mHandler.removeMessages(MSG_TIMEOUT);

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
                        NdefRecord appUri = NdefRecord.createUri(
                                Uri.parse("http://market.android.com/search?q=pname:" + pkg));
                        NdefRecord appRecord = NdefRecord.createApplicationRecord(pkg);
                        foregroundMsg = new NdefMessage(new NdefRecord[] { appUri, appRecord });
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Bad package returned from ActivityManager: " + pkg);
                }
            } else {
                Log.d(TAG, "no foreground activity");
            }
        }

        // If an animation is still running, we don't restart it to avoid
        // nasty effects if at the edge of RF field.
        if (foregroundMsg != null && !mAnimating) {
            mScreenshot.stop();
            mScreenshot.start();
            mAnimating = true;
            mSendStarted = false;
            mListener.onP2pBegin();
        }

        mPushMsg = foregroundMsg;
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpDeactivated() {
        if (DBG) Log.d(TAG, "LLCP deactivated.");

        mOrientationListener.disable();
        mVibrator.cancel();
        if (mPushTask != null) {
            mPushTask.cancel(true);
            mPushTask = null;
        }
        // Don't cancel the animation immediately - retain it
        // up to one second.
        mHandler.sendEmptyMessageDelayed(MSG_TIMEOUT, 1000);
    }

    /**
     * To be called whenever we have successfully sent an NdefMessage
     * over SNEP or NPP.
     * Must be called from the UI thread.
     */
    void onSendComplete(boolean success) {
        mSendState = success ? STATE_SUCCESS : STATE_FAILURE;
        finish(success, mReceiveState == STATE_SUCCESS);
    }

    /**
     * To be called whenever we have successfully received an NdefMessage
     * over SNEP or NPP.
     * May be called from any thread.
     */
    void onReceiveComplete() {
        mHandler.sendEmptyMessage(MSG_RECEIVE_SUCCESS);
    }

    final class PushTask extends AsyncTask<Void, Void, Void> {
        final NdefMessage mMessage;
        boolean mSendSuccess = false;
        public PushTask(NdefMessage msg) {
            mMessage = msg;
        }

        @Override
        public Void doInBackground(Void... args) {
            try {
                if (mMessage != null) {
                    if (DBG) Log.d(TAG, "Sending ndef via SNEP");
                    mSendSuccess = doSnepProtocol(mMessage);
                }
            } catch (IOException e) {
                Log.d(TAG, "Failed to connect over SNEP, trying NPP");

                if (isCancelled()) {
                    return null;
                }

                mSendSuccess = new NdefPushClient().push(mMessage);
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

            return null;
        }

        @Override
        public void onCancelled() {
            onSendComplete(mSendSuccess);
            mPushTask = null;
        }

        @Override
        public void onPostExecute(Void result) {
            onSendComplete(mSendSuccess);
            mPushTask = null;
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

    final NdefPushServer.Callback mNppCallback = new NdefPushServer.Callback() {
        @Override
        public void onMessageReceived(NdefMessage msg) {
            onReceiveComplete();
            NfcService.getInstance().sendMockNdefTag(msg);
        }
    };

    final SnepServer.Callback mDefaultSnepCallback = new SnepServer.Callback() {
        @Override
        public SnepMessage doPut(NdefMessage msg) {
            onReceiveComplete();
            NfcService.getInstance().sendMockNdefTag(msg);
            return SnepMessage.getMessage(SnepMessage.RESPONSE_SUCCESS);
        }

        @Override
        public SnepMessage doGet(int acceptableLength, NdefMessage msg) {
            if (DBG) Log.d(TAG, "GET not supported.");
            return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_IMPLEMENTED);
        }
    };

    /**
     * Finish up the animation, if running, and play ending sounds.
     * Must be called on the UI thread.
     */
    void finish(boolean sendSuccess, boolean receiveSuccess) {

        if (mSendStarted && sendSuccess) {
            if (mAnimating) {
                mScreenshot.finishWithSendReceive();
            }
            mListener.onP2pEnd();
        }
        else if (mSendStarted && !sendSuccess) {
            if (mAnimating) {
                mScreenshot.finishWithFailure();
            }
            mListener.onP2pError();
        } else {
            if (mAnimating) {
                mScreenshot.finishWithFailure();
            }
            mListener.onP2pError();
        }
        // Remove all queued messages - until we start again
        mHandler.removeMessages(MSG_TIMEOUT);
        mHandler.removeMessages(MSG_RECEIVE_SUCCESS);

        mVibrator.cancel();
        mAnimating = false;
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_RECEIVE_SUCCESS:
                mReceiveState = STATE_SUCCESS;
                break;
            case MSG_TIMEOUT:
                finish(mSendState == STATE_SUCCESS, mReceiveState == STATE_SUCCESS);
                break;
            case MSG_DISABLE:
                if (!mIsP2pEnabled) {
                    break;
                }
                mIsP2pEnabled = false;
                mDefaultSnepServer.stop();
                mNdefPushServer.stop();
                break;
            case MSG_ENABLE:
                if (mIsP2pEnabled) {
                    break;
                }
                mIsP2pEnabled = true;
                mDefaultSnepServer.start();
                mNdefPushServer.start();
                break;
        }
        return true;
    }

    @Override
    public void onConfirmSend() {
        if (!mSendStarted) {
            // Start sending
            if (mPushTask != null) {
                mPushTask.cancel(true);
            }
            mSendStarted = true;
            mPushTask = new PushTask(mPushMsg);
            mPushTask.execute();
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            pw.println("mIsP2pEnabled=" + mIsP2pEnabled);
            pw.println("mForegroundMsg=" + mForegroundMsg);
            pw.println("mCallback=" + mCallback);
        }
    }
}
