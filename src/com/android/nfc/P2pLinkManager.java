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

import com.android.nfc.echoserver.EchoServer;
import com.android.nfc.handover.HandoverManager;
import com.android.nfc.ndefpush.NdefPushClient;
import com.android.nfc.ndefpush.NdefPushServer;
import com.android.nfc.snep.SnepClient;
import com.android.nfc.snep.SnepMessage;
import com.android.nfc.snep.SnepServer;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.nfc.INdefPushCallback;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charsets;
import java.util.Arrays;
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
     * Called to indicate the remote device does not support connection handover
     */
    public void onP2pHandoverNotSupported();

    /**
     * Called to indicate a receive was successful.
     */
    public void onP2pReceiveComplete(boolean playSound);

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

    /** Include this constant as a meta-data entry in the manifest
     *  of an application to disable beaming the market/AAR link, like this:
     *  <pre>{@code
     *  <application ...>
     *      <meta-data android:name="android.nfc.disable_beam_default"
     *          android:value="true" />
     *  </application>
     *  }</pre>
     */
    static final String DISABLE_BEAM_DEFAULT = "android.nfc.disable_beam_default";

    /** Enables the LLCP EchoServer, which can be used to test the android
     * LLCP stack against nfcpy.
     */
    static final boolean ECHOSERVER_ENABLED = false;

    // TODO dynamically assign SAP values
    static final int NDEFPUSH_SAP = 0x10;

    static final int LINK_DEBOUNCE_MS = 750;

    static final int MSG_DEBOUNCE_TIMEOUT = 1;
    static final int MSG_RECEIVE_COMPLETE = 2;
    static final int MSG_RECEIVE_HANDOVER = 3;
    static final int MSG_SEND_COMPLETE = 4;
    static final int MSG_START_ECHOSERVER = 5;
    static final int MSG_STOP_ECHOSERVER = 6;
    static final int MSG_HANDOVER_NOT_SUPPORTED = 7;

    // values for mLinkState
    static final int LINK_STATE_DOWN = 1;
    static final int LINK_STATE_UP = 2;
    static final int LINK_STATE_DEBOUNCE =3;

    // values for mSendState
    static final int SEND_STATE_NOTHING_TO_SEND = 1;
    static final int SEND_STATE_NEED_CONFIRMATION = 2;
    static final int SEND_STATE_SENDING = 3;

    // return values for doSnepProtocol
    static final int SNEP_SUCCESS = 0;
    static final int SNEP_FAILURE = 1;
    static final int SNEP_HANDOVER_UNSUPPORTED = 2;

    static final Uri PROFILE_URI = Profile.CONTENT_VCARD_URI.buildUpon().
            appendQueryParameter(Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO, "true").
            build();

    final NdefPushServer mNdefPushServer;
    final SnepServer mDefaultSnepServer;
    final EchoServer mEchoServer;
    final ActivityManager mActivityManager;
    final PackageManager mPackageManager;
    final Context mContext;
    final P2pEventListener mEventListener;
    final Handler mHandler;
    final HandoverManager mHandoverManager;

    // Locked on NdefP2pManager.this
    int mLinkState;
    int mSendState;  // valid during LINK_STATE_UP or LINK_STATE_DEBOUNCE
    boolean mIsSendEnabled;
    boolean mIsReceiveEnabled;
    NdefMessage mMessageToSend;  // valid during SEND_STATE_NEED_CONFIRMATION or SEND_STATE_SENDING
    Uri[] mUrisToSend;  // valid during SEND_STATE_NEED_CONFIRMATION or SEND_STATE_SENDING
    INdefPushCallback mCallbackNdef;
    SendTask mSendTask;
    SharedPreferences mPrefs;
    boolean mFirstBeam;

    public P2pLinkManager(Context context, HandoverManager handoverManager) {
        mNdefPushServer = new NdefPushServer(NDEFPUSH_SAP, mNppCallback);
        mDefaultSnepServer = new SnepServer(mDefaultSnepCallback);
        if (ECHOSERVER_ENABLED) {
            mEchoServer = new EchoServer();
        } else {
            mEchoServer = null;
        }
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = context.getPackageManager();
        mContext = context;
        mEventListener = new P2pEventManager(context, this);
        mHandler = new Handler(this);
        mLinkState = LINK_STATE_DOWN;
        mSendState = SEND_STATE_NOTHING_TO_SEND;
        mIsSendEnabled = false;
        mIsReceiveEnabled = false;
        mPrefs = context.getSharedPreferences(NfcService.PREF, Context.MODE_PRIVATE);
        mFirstBeam = mPrefs.getBoolean(NfcService.PREF_FIRST_BEAM, true);
        mHandoverManager = handoverManager;
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
                if (mEchoServer != null) {
                    mHandler.sendEmptyMessage(MSG_START_ECHOSERVER);
                }
            } else if (mIsReceiveEnabled && !receiveEnable) {
                mDefaultSnepServer.stop();
                mNdefPushServer.stop();
                if (mEchoServer != null) {
                    mHandler.sendEmptyMessage(MSG_STOP_ECHOSERVER);
                }
            }
            mIsSendEnabled = sendEnable;
            mIsReceiveEnabled = receiveEnable;
        }
    }

    /**
     * Set NDEF callback for sending.
     * May be called from any thread.
     * NDEF callbacks may be set at any time (even if NFC is
     * currently off or P2P send is currently off). They will become
     * active as soon as P2P send is enabled.
     */
    public void setNdefCallback(INdefPushCallback callbackNdef) {
        synchronized (this) {
            mCallbackNdef = callbackNdef;
        }
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpActivated() {
        Log.i(TAG, "LLCP activated");

        synchronized (P2pLinkManager.this) {
            if (mEchoServer != null) {
                mEchoServer.onLlcpActivated();
            }

            switch (mLinkState) {
                case LINK_STATE_DOWN:
                    mLinkState = LINK_STATE_UP;
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pInRange()");
                    mEventListener.onP2pInRange();

                    prepareMessageToSend();
                    if (mMessageToSend != null ||
                            (mUrisToSend != null && mHandoverManager.isHandoverSupported())) {
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
                mUrisToSend = null;
                return;
            }

            // Try application callback first
            //TODO: Check that mCallbackNdef refers to the foreground activity
            if (mCallbackNdef != null) {
                try {
                    mMessageToSend = mCallbackNdef.createMessage();
                    mUrisToSend = mCallbackNdef.getUris();
                    return;
                } catch (RemoteException e) {
                    // Ignore
                }
            }

            // fall back to default NDEF for this activity, unless the
            // application disabled this explicitly in their manifest.
            List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
            if (tasks.size() > 0) {
                String pkg = tasks.get(0).baseActivity.getPackageName();
                if (beamDefaultDisabled(pkg)) {
                    Log.d(TAG, "Disabling default Beam behavior");
                    mMessageToSend = null;
                } else {
                    mMessageToSend = createDefaultNdef(pkg);
                }
            } else {
                mMessageToSend = null;
            }
            if (DBG) Log.d(TAG, "mMessageToSend = " + mMessageToSend);
            if (DBG) Log.d(TAG, "mUrisToSend = " + mUrisToSend);
        }
    }

    boolean beamDefaultDisabled(String pkgName) {
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(pkgName,
                    PackageManager.GET_META_DATA);
            if (ai == null || ai.metaData == null) {
                return false;
            }
            return ai.metaData.getBoolean(DISABLE_BEAM_DEFAULT);
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    NdefMessage createDefaultNdef(String pkgName) {
        NdefRecord appUri = NdefRecord.createUri(Uri.parse(
                "http://play.google.com/store/apps/details?id=" + pkgName + "&feature=beam"));
        NdefRecord appRecord = NdefRecord.createApplicationRecord(pkgName);
        return new NdefMessage(new NdefRecord[] { appUri, appRecord });
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpDeactivated() {
        Log.i(TAG, "LLCP deactivated.");
        synchronized (this) {
            if (mEchoServer != null) {
                mEchoServer.onLlcpDeactivated();
            }

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

    void onHandoverUnsupported() {
        mHandler.sendEmptyMessage(MSG_HANDOVER_NOT_SUPPORTED);
    }

    void onSendComplete(NdefMessage msg, long elapsedRealtime) {
        if (mFirstBeam) {
            EventLogTags.writeNfcFirstShare();
            mPrefs.edit().putBoolean(NfcService.PREF_FIRST_BEAM, false).apply();
            mFirstBeam = false;
        }
        EventLogTags.writeNfcShare(getMessageSize(msg), getMessageTnf(msg), getMessageType(msg),
                getMessageAarPresent(msg), (int) elapsedRealtime);
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
            Uri[] uris;
            boolean result;

            synchronized (P2pLinkManager.this) {
                if (mLinkState != LINK_STATE_UP || mSendState != SEND_STATE_SENDING) {
                    return null;
                }
                m = mMessageToSend;
                uris = mUrisToSend;
            }

            long time = SystemClock.elapsedRealtime();


            try {
                if (DBG) Log.d(TAG, "Sending ndef via SNEP");

                int snepResult = doSnepProtocol(mHandoverManager, m, uris);

                switch (snepResult) {
                    case SNEP_HANDOVER_UNSUPPORTED:
                        onHandoverUnsupported();
                        return null;
                    case SNEP_SUCCESS:
                        result = true;
                        break;
                    case SNEP_FAILURE:
                        result = false;
                        break;
                    default:
                        result = false;
                }
            } catch (IOException e) {
                Log.i(TAG, "Failed to connect over SNEP, trying NPP");

                if (isCancelled()) {
                    return null;
                }

                if (m != null) {
                    result = new NdefPushClient().push(m);
                } else {
                    result = false;
                }
            }
            time = SystemClock.elapsedRealtime() - time;

            if (DBG) Log.d(TAG, "SendTask result=" + result + ", time ms=" + time);

            if (result) {
                onSendComplete(m, time);
            }
            return null;
        }
    }

    static int doSnepProtocol(HandoverManager handoverManager,
            NdefMessage msg, Uri[] uris) throws IOException {
        SnepClient snepClient = new SnepClient();
        try {
            snepClient.connect();
        } catch (IOException e) {
            // Throw exception to fall back to NPP.
            snepClient.close();
            throw new IOException("SNEP not available.", e);
        }

        try {
            if (uris != null) {
                NdefMessage response = null;
                NdefMessage request = handoverManager.createHandoverRequestMessage();
                if (request != null) {
                    SnepMessage snepResponse = snepClient.get(request);
                    response = snepResponse.getNdefMessage();
                } // else, handover not supported
                if (response != null) {
                    handoverManager.doHandoverUri(uris, response);
                } else if (msg != null) {
                    // For backwards-compatibility to pre-J devices,
                    // try to push an NDEF message (if any) if the handover GET
                    // does not work.
                    snepClient.put(msg);
                } else {
                    // We had a failed handover and no alternate message to
                    // send; indicate remote device doesn't support handover.
                    return SNEP_HANDOVER_UNSUPPORTED;
                }
            } else if (msg != null) {
                snepClient.put(msg);
            }
            return SNEP_SUCCESS;
        } catch (IOException e) {
            // SNEP available but had errors, don't fall back to NPP.
        } finally {
            snepClient.close();
        }
        return SNEP_FAILURE;
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
            NdefMessage response = mHandoverManager.tryHandoverRequest(msg);

            if (response != null) {
                onReceiveHandover();
                return SnepMessage.getSuccessResponse(response);
            } else {
                return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_FOUND);
            }
        }
    };

    void onReceiveHandover() {
        mHandler.obtainMessage(MSG_RECEIVE_HANDOVER).sendToTarget();
    }

    void onReceiveComplete(NdefMessage msg) {
        EventLogTags.writeNfcNdefReceived(getMessageSize(msg), getMessageTnf(msg),
                getMessageType(msg), getMessageAarPresent(msg));
        // Make callbacks on UI thread
        mHandler.obtainMessage(MSG_RECEIVE_COMPLETE, msg).sendToTarget();
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_START_ECHOSERVER:
                synchronized (this) {
                    mEchoServer.start();
                    break;
                }
            case MSG_STOP_ECHOSERVER:
                synchronized (this) {
                    mEchoServer.stop();
                    break;
                }
            case MSG_DEBOUNCE_TIMEOUT:
                synchronized (this) {
                    if (mLinkState != LINK_STATE_DEBOUNCE) {
                        break;
                    }
                    if (mSendState == SEND_STATE_SENDING) {
                        EventLogTags.writeNfcShareFail(getMessageSize(mMessageToSend),
                                getMessageTnf(mMessageToSend), getMessageType(mMessageToSend),
                                getMessageAarPresent(mMessageToSend));
                    }
                    if (DBG) Log.d(TAG, "Debounce timeout");
                    mLinkState = LINK_STATE_DOWN;
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    mMessageToSend = null;
                    mUrisToSend = null;
                    if (DBG) Log.d(TAG, "onP2pOutOfRange()");
                    mEventListener.onP2pOutOfRange();
                }
                break;
            case MSG_RECEIVE_HANDOVER:
                // We're going to do a handover request
                synchronized (this) {
                    if (mLinkState == LINK_STATE_DOWN) {
                        break;
                    }
                    if (mSendState == SEND_STATE_SENDING) {
                        cancelSendNdefMessage();
                    }
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pReceiveComplete()");
                    mEventListener.onP2pReceiveComplete(false);
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
                    mEventListener.onP2pReceiveComplete(true);
                    NfcService.getInstance().sendMockNdefTag(m);
                }
                break;
            case MSG_HANDOVER_NOT_SUPPORTED:
                synchronized (P2pLinkManager.this) {
                    mSendTask = null;

                    if (mLinkState == LINK_STATE_DOWN || mSendState != SEND_STATE_SENDING) {
                        break;
                    }
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pHandoverNotSupported()");
                    mEventListener.onP2pHandoverNotSupported();
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
                }
                break;
        }
        return true;
    }

    int getMessageSize(NdefMessage msg) {
        if (msg != null) {
            return msg.toByteArray().length;
        } else {
            return 0;
        }
    }

    int getMessageTnf(NdefMessage msg) {
        if (msg == null) {
            return NdefRecord.TNF_EMPTY;
        }
        NdefRecord records[] = msg.getRecords();
        if (records == null || records.length == 0) {
            return NdefRecord.TNF_EMPTY;
        }
        return records[0].getTnf();
    }

    String getMessageType(NdefMessage msg) {
        if (msg == null) {
            return "null";
        }
        NdefRecord records[] = msg.getRecords();
        if (records == null || records.length == 0) {
            return "null";
        }
        NdefRecord record = records[0];
        switch (record.getTnf()) {
            case NdefRecord.TNF_ABSOLUTE_URI:
                // The actual URI is in the type field, don't log it
                return "uri";
            case NdefRecord.TNF_EXTERNAL_TYPE:
            case NdefRecord.TNF_MIME_MEDIA:
            case NdefRecord.TNF_WELL_KNOWN:
                return new String(record.getType(), Charsets.UTF_8);
            default:
                return "unknown";
        }
    }

    int getMessageAarPresent(NdefMessage msg) {
        if (msg == null) {
            return 0;
        }
        NdefRecord records[] = msg.getRecords();
        if (records == null) {
            return 0;
        }
        for (NdefRecord record : records) {
            if (record.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE &&
                    Arrays.equals(NdefRecord.RTD_ANDROID_APP, record.getType())) {
                return 1;
            }
        }
        return 0;
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

            pw.println("mCallbackNdef=" + mCallbackNdef);
            pw.println("mMessageToSend=" + mMessageToSend);
            pw.println("mUrisToSend=" + mUrisToSend);
        }
    }
}
