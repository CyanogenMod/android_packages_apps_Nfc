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
import android.net.Uri;
import android.nfc.INdefPushCallback;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Interface to listen for P2P events.
 * All callbacks are made from the UI thread.
 */
interface P2pEventListener {
    /**
     * Indicates a P2P device is in range.
     * <p>onP2pInRange() and onP2pOutOfRange() will always be called
     * alternately.
     * <p>All other callbacks will only occur while a P2P device is in range.
     */
    public void onP2pInRange();

    /**
     * Called when a NDEF payload is prepared to send, and confirmation is
     * required. Call Callback.onP2pSendConfirmed() to make the confirmation.
     */
    public void onP2pSendConfirmationRequested();

    /**
     * Called to indicate a send was successful.
     */
    public void onP2pSendComplete();

    /**
     * Called to indicate a receive was successful.
     */
    public void onP2pReceiveComplete();

    /**
     * Indicates the P2P device went out of range.
     */
    public void onP2pOutOfRange();

    public interface Callback {
        public void onP2pSendConfirmed();
    }
}

/**
 * Manages sending and receiving NDEF message over LLCP link.
 * Does simple debouncing of the LLCP link - so that even if the link
 * drops and returns the user does not know.
 */
public class P2pLinkManager implements Handler.Callback, P2pEventListener.Callback {
    static final String TAG = "NfcP2pLinkManager";
    static final boolean DBG = true;

    // TODO dynamically assign SAP values
    static final int NDEFPUSH_SAP = 0x10;

    static final int LINK_DEBOUNCE_MS = 750;

    static final int MSG_DEBOUNCE_TIMEOUT = 1;
    static final int MSG_RECEIVE_COMPLETE = 2;
    static final int MSG_SEND_COMPLETE = 3;

    // values for mLinkState
    static final int LINK_STATE_DOWN = 1;
    static final int LINK_STATE_UP = 2;
    static final int LINK_STATE_DEBOUNCE =3;

    // values for mSendState
    static final int SEND_STATE_NOTHING_TO_SEND = 1;
    static final int SEND_STATE_NEED_CONFIRMATION = 2;
    static final int SEND_STATE_SENDING = 3;


    static final Uri PROFILE_URI = Profile.CONTENT_VCARD_URI.buildUpon().
            appendQueryParameter(Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO, "true").
            build();

    final NdefPushServer mNdefPushServer;
    final SnepServer mDefaultSnepServer;
    final ActivityManager mActivityManager;
    final PackageManager mPackageManager;
    final Context mContext;
    final P2pEventListener mEventListener;
    final Handler mHandler;

    // Locked on NdefP2pManager.this
    int mLinkState;
    int mSendState;  // valid during LINK_STATE_UP or LINK_STATE_DEBOUNCE
    boolean mIsSendEnabled;
    boolean mIsReceiveEnabled;
    NdefMessage mMessageToSend;  // valid during SEND_STATE_NEED_CONFIRMATION or SEND_STATE_SENDING
    NdefMessage mStaticNdef;
    INdefPushCallback mCallbackNdef;
    SendTask mSendTask;

    public P2pLinkManager(Context context) {
        mNdefPushServer = new NdefPushServer(NDEFPUSH_SAP, mNppCallback);
        mDefaultSnepServer = new SnepServer(mDefaultSnepCallback);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = context.getPackageManager();
        mContext = context;
        mEventListener = new P2pEventManager(context, this);
        mHandler = new Handler(this);
        mLinkState = LINK_STATE_DOWN;
        mSendState = SEND_STATE_NOTHING_TO_SEND;
        mIsSendEnabled = false;
        mIsReceiveEnabled = false;
     }

    /**
     * May be called from any thread.
     * Assumes that NFC is already on if any parameter is true.
     */
    public void enableDisable(boolean sendEnable, boolean receiveEnable) {
        synchronized (this) {
            if (!mIsReceiveEnabled && receiveEnable) {
                mDefaultSnepServer.start();
                mNdefPushServer.start();
            } else if (mIsReceiveEnabled && !receiveEnable) {
                mDefaultSnepServer.stop();
                mNdefPushServer.stop();
            }
            mIsSendEnabled = sendEnable;
            mIsReceiveEnabled = receiveEnable;
        }
    }

    /**
     * Set NDEF message or callback for sending.
     * May be called from any thread.
     * NDEF messages or callbacks may be set at any time (even if NFC is
     * currently off or P2P send is currently off). They will become
     * active as soon as P2P send is enabled.
     */
    public void setNdefToSend(NdefMessage staticNdef, INdefPushCallback callbackNdef) {
        synchronized (this) {
            mStaticNdef = staticNdef;
            mCallbackNdef = callbackNdef;
        }
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpActivated() {
        Log.i(TAG, "LLCP activated");

        synchronized (P2pLinkManager.this) {
            switch (mLinkState) {
                case LINK_STATE_DOWN:
                    mLinkState = LINK_STATE_UP;
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pInRange()");
                    mEventListener.onP2pInRange();

                    prepareMessageToSend();
                    if (mMessageToSend != null) {
                        mSendState = SEND_STATE_NEED_CONFIRMATION;
                        if (DBG) Log.d(TAG, "onP2pSendConfirmationRequested()");
                        mEventListener.onP2pSendConfirmationRequested();
                    }
                    break;
                case LINK_STATE_UP:
                    if (DBG) Log.d(TAG, "Duplicate onLlcpActivated()");
                    return;
                case LINK_STATE_DEBOUNCE:
                    mLinkState = LINK_STATE_UP;
                    mHandler.removeMessages(MSG_DEBOUNCE_TIMEOUT);

                    if (mSendState == SEND_STATE_SENDING) {
                        Log.i(TAG, "Retry send...");
                        sendNdefMessage();
                    }
                    break;
            }
        }
    }

    void prepareMessageToSend() {
        synchronized (P2pLinkManager.this) {
            if (!mIsSendEnabled) {
                mMessageToSend = null;
                return;
            }

            NdefMessage messageToSend = mStaticNdef;
            INdefPushCallback callback = mCallbackNdef;

            if (callback != null) {
                try {
                    messageToSend = callback.createMessage();
                } catch (RemoteException e) {
                    // Ignore
                }
            }

            if (messageToSend == null) {
                messageToSend = createDefaultNdef();
            }
            mMessageToSend = messageToSend;
        }
    }

    NdefMessage createDefaultNdef() {
        List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
        if (tasks.size() > 0) {
            String pkg = tasks.get(0).baseActivity.getPackageName();
            try {
                ApplicationInfo appInfo = mPackageManager.getApplicationInfo(pkg, 0);
                if (0 == (appInfo.flags & ApplicationInfo.FLAG_SYSTEM)) {
                    NdefRecord appUri = NdefRecord.createUri(
                            Uri.parse("http://market.android.com/search?q=pname:" + pkg));
                    NdefRecord appRecord = NdefRecord.createApplicationRecord(pkg);
                    return new NdefMessage(new NdefRecord[] { appUri, appRecord });
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Bad package returned from ActivityManager: " + pkg);
            }
        } else {
            Log.d(TAG, "no foreground activity");
        }
        return null;
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpDeactivated() {
        Log.i(TAG, "LLCP deactivated.");
        synchronized (this) {
            switch (mLinkState) {
                case LINK_STATE_DOWN:
                case LINK_STATE_DEBOUNCE:
                    Log.i(TAG, "Duplicate onLlcpDectivated()");
                    break;
                case LINK_STATE_UP:
                    // Debounce
                    mLinkState = LINK_STATE_DEBOUNCE;
                    mHandler.sendEmptyMessageDelayed(MSG_DEBOUNCE_TIMEOUT, LINK_DEBOUNCE_MS);
                    cancelSendNdefMessage();
                    break;
            }
         }
     }

    void onSendComplete() {
        // Make callbacks on UI thread
        mHandler.sendEmptyMessage(MSG_SEND_COMPLETE);
    }

    void sendNdefMessage() {
        synchronized (this) {
            cancelSendNdefMessage();
            mSendTask = new SendTask();
            mSendTask.execute();
        }
    }

    void cancelSendNdefMessage() {
        synchronized (P2pLinkManager.this) {
            if (mSendTask != null) {
                mSendTask.cancel(true);
            }
        }
    }

    final class SendTask extends AsyncTask<Void, Void, Void> {
        @Override
        public Void doInBackground(Void... args) {
            NdefMessage m;
            boolean result;

            synchronized (P2pLinkManager.this) {
                if (mLinkState != LINK_STATE_UP || mSendState != SEND_STATE_SENDING) {
                    return null;
                }
                m = mMessageToSend;
            }

            try {
                if (DBG) Log.d(TAG, "Sending ndef via SNEP");
                result = doSnepProtocol(m);
            } catch (IOException e) {
                Log.i(TAG, "Failed to connect over SNEP, trying NPP");

                if (isCancelled()) {
                    return null;
                }

                result = new NdefPushClient().push(m);
            }
            if (DBG) Log.d(TAG, "SendTask result=" + result);
            if (result) {
                onSendComplete();
            }
            return null;
        }
    }

    static boolean doSnepProtocol(NdefMessage msg) throws IOException {
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
            onReceiveComplete(msg);
        }
    };

    final SnepServer.Callback mDefaultSnepCallback = new SnepServer.Callback() {
        @Override
        public SnepMessage doPut(NdefMessage msg) {
            onReceiveComplete(msg);
            return SnepMessage.getMessage(SnepMessage.RESPONSE_SUCCESS);
        }

        @Override
        public SnepMessage doGet(int acceptableLength, NdefMessage msg) {
            if (DBG) Log.d(TAG, "GET not supported.");
            return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_IMPLEMENTED);
        }
    };

    void onReceiveComplete(NdefMessage msg) {
        // Make callbacks on UI thread
        mHandler.obtainMessage(MSG_RECEIVE_COMPLETE, msg).sendToTarget();
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_DEBOUNCE_TIMEOUT:
                synchronized (this) {
                    if (mLinkState != LINK_STATE_DEBOUNCE) {
                        break;
                    }
                    if (DBG) Log.d(TAG, "Debounce timeout");
                    mLinkState = LINK_STATE_DOWN;
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    mMessageToSend = null;
                    if (DBG) Log.d(TAG, "onP2pOutOfRange()");
                    mEventListener.onP2pOutOfRange();
                }
                break;
            case MSG_RECEIVE_COMPLETE:
                NdefMessage m = (NdefMessage) msg.obj;
                synchronized (this) {
                    if (mLinkState == LINK_STATE_DOWN) {
                        break;
                    }
                    if (mSendState == SEND_STATE_SENDING) {
                        cancelSendNdefMessage();
                    }
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pReceiveComplete()");
                    mEventListener.onP2pReceiveComplete();
                    NfcService.getInstance().sendMockNdefTag(m);
                }
                break;
            case MSG_SEND_COMPLETE:
                synchronized (P2pLinkManager.this) {
                    mSendTask = null;

                    if (mLinkState == LINK_STATE_DOWN || mSendState != SEND_STATE_SENDING) {
                        break;
                    }
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pSendComplete()");
                    mEventListener.onP2pSendComplete();
                    if (mCallbackNdef != null) {
                        try {
                            mCallbackNdef.onNdefPushComplete();
                        } catch (RemoteException e) { }
                    }
                    mSendTask = null;
                }
                break;
        }
        return true;
    }

    @Override
    public void onP2pSendConfirmed() {
        if (DBG) Log.d(TAG, "onP2pSendConfirmed()");
        synchronized (this) {
            if (mLinkState == LINK_STATE_DOWN || mSendState != SEND_STATE_NEED_CONFIRMATION) {
                return;
            }
            mSendState = SEND_STATE_SENDING;
            if (mLinkState == LINK_STATE_UP) {
                sendNdefMessage();
            }
        }
    }

    static String sendStateToString(int state) {
        switch (state) {
            case SEND_STATE_NOTHING_TO_SEND:
                return "SEND_STATE_NOTHING_TO_SEND";
            case SEND_STATE_NEED_CONFIRMATION:
                return "SEND_STATE_NEED_CONFIRMATION";
            case SEND_STATE_SENDING:
                return "SEND_STATE_SENDING";
            default:
                return "<error>";
        }
    }

    static String linkStateToString(int state) {
        switch (state) {
            case LINK_STATE_DOWN:
                return "LINK_STATE_DOWN";
            case LINK_STATE_DEBOUNCE:
                return "LINK_STATE_DEBOUNCE";
            case LINK_STATE_UP:
                return "LINK_STATE_UP";
            default:
                return "<error>";
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            pw.println("mIsSendEnabled=" + mIsSendEnabled);
            pw.println("mIsReceiveEnabled=" + mIsReceiveEnabled);
            pw.println("mLinkState=" + linkStateToString(mLinkState));
            pw.println("mSendState=" + sendStateToString(mSendState));

            pw.println("mStaticNdef=" + mStaticNdef);
            pw.println("mCallbackNdef=" + mCallbackNdef);
            pw.println("mMessageToSend=" + mMessageToSend);
        }
    }
}
