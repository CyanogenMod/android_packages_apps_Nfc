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

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.Vibrator;
import android.util.Log;

/**
 * Manages vibration, sound and animation for P2P events.
 */
public class P2pEventManager implements P2pEventListener {
    static final String TAG = "NfcP2pEventManager";
    static final boolean DBG = true;

    static final long[] VIBRATION_PATTERN = {0, 100, 10000};
    private static final int RETRY_DELAY_MILLIS = 100;
    private static final long SERVICE_WAIT_TIMEOUT_MILLIS = 10000;

    final Context mContext;
    final NfcService mNfcService;
    final P2pEventListener.Callback mCallback;
    final Vibrator mVibrator;
    final NotificationManager mNotificationManager;
    Messenger mService;

    boolean mBinding;

    // only used on UI thread
    boolean mSending;
    boolean mNdefSent;
    boolean mNdefReceived;
    boolean mInDebounce;

    static final int MSG_ON_BEAM_CANCELLED = 1;
    static final int MSG_ON_SEND_CONFIRMED = 2;
    static final int MSG_RETRY_SEND_MESSAGE = 3;

    private final Messenger mMessenger;
    private final Handler mHandler = new MessageHandler();

    class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_SEND_CONFIRMED:
                    onSendConfirmed();
                    break;
                case MSG_ON_BEAM_CANCELLED:
                    onBeamCanceled();
                    break;
                case MSG_RETRY_SEND_MESSAGE:
                    trySendMessage((Message) msg.obj, false);
                    break;
                default:
                    Log.e(TAG, "unhandled message: " + msg.what);
                    break;
            }
        }
    }

    private Object mLock = new Object();
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mBinding = false;
                mService = new Messenger(service);
                Log.d(TAG, "Service bound");

                // Register client
                Message msg = Message.obtain(null, SendUiService.MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                try {
                    mService.send(msg);
                    mLock.notifyAll();
                } catch (RemoteException ex) {
                    Log.e(TAG, "unable to communicate with SendUiService", ex);
                    mService = null;
                    mContext.unbindService(this);
                    bindServiceLocked();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Log.v(TAG, "service disconnected");
                mService = null;
                mBinding = true; // service crashed, will re-bind automatically
            }
        }
    };

    public P2pEventManager(Context context, P2pEventListener.Callback callback) {
        mMessenger = new Messenger(mHandler);
        mNfcService = NfcService.getInstance();
        mContext = context;
        mCallback = callback;
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);

        mSending = false;
        bindServiceLocked(); // don't need to acquire the lock as we're in the constructor
    }

    private boolean bindServiceLocked() {
        if (mService != null || mBinding) return true;

        final int uiModeType = mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK;
        if (uiModeType == Configuration.UI_MODE_TYPE_APPLIANCE) {
            return false;
        } else {
            mBinding = true;
            Log.v(TAG, "Binding service...");
            mContext.bindService(new Intent(mContext, SendUiService.class), mConnection,
                    Context.BIND_SHOWING_UI | Context.BIND_AUTO_CREATE);
            return true;
        }
    }

    @Override
    public void onP2pInRange() {
        mNfcService.playSound(NfcService.SOUND_START);
        mNdefSent = false;
        mNdefReceived = false;
        mInDebounce = false;

        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        trySendMessage(Message.obtain(null, SendUiService.MSG_TAKE_SCREENSHOT), true);
    }

    @Override
    public void onP2pNfcTapRequested() {
        mNfcService.playSound(NfcService.SOUND_START);
        mNdefSent = false;
        mNdefReceived = false;
        mInDebounce = false;

        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        trySendMessage(Message.obtain(null, SendUiService.MSG_TAKE_SCREENSHOT), true);
        trySendMessage(Message.obtain(null,
                SendUiService.MSG_SHOW_PRESEND, /* promptToTap */ 1, 0), true);
    }

    @Override
    public void onP2pTimeoutWaitingForLink() {
        trySendMessage(Message.obtain(
                null, SendUiService.MSG_FINISH, SendUiService.FINISH_SCALE_UP, 0), true);
    }

    @Override
    public void onP2pSendConfirmationRequested() {
        if (!trySendMessage(Message.obtain(null, SendUiService.MSG_SHOW_PRESEND), true)) {
            mCallback.onP2pSendConfirmed();
        }
    }

    @Override
    public void onP2pSendComplete() {
        mNfcService.playSound(NfcService.SOUND_END);
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        trySendMessage(Message.obtain(null,
                SendUiService.MSG_FINISH, SendUiService.FINISH_SEND_SUCCESS, 0), true);
        mSending = false;
        mNdefSent = true;
    }

    @Override
    public void onP2pHandoverNotSupported() {
        mNfcService.playSound(NfcService.SOUND_ERROR);
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        Message msg = Message.obtain(
                null, SendUiService.MSG_FINISH, SendUiService.FINISH_SCALE_UP, 0);
        Bundle bundle = new Bundle();
        bundle.putString(SendUiService.EXTRA_TOAST_MESSAGE,
                mContext.getString(R.string.beam_handover_not_supported));
        msg.setData(bundle);
        trySendMessage(msg, true);
        mSending = false;
        mNdefSent = false;
    }

    @Override
    public void onP2pReceiveComplete(boolean playSound) {
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        if (playSound) mNfcService.playSound(NfcService.SOUND_END);
        // TODO we still don't have a nice receive solution
        // The sanest solution right now is just to scale back up what we had
        // and start the new activity. It is not perfect, but at least it is
        // consistent behavior. All other variants involve making the old
        // activity screenshot disappear, and then removing the animation
        // window hoping the new activity has started by then. This just goes
        // wrong too often and can look weird.
        trySendMessage(Message.obtain(
                null, SendUiService.MSG_FINISH, SendUiService.FINISH_SCALE_UP, 0), true);
        mNdefReceived = true;
    }

    @Override
    public void onP2pOutOfRange() {
        if (mSending) {
            mNfcService.playSound(NfcService.SOUND_ERROR);
            mSending = false;
        }
        if (!mNdefSent && !mNdefReceived) {
            trySendMessage(Message.obtain(
                    null, SendUiService.MSG_FINISH, SendUiService.FINISH_SCALE_UP, 0), true);
        }
        mInDebounce = false;
    }

    public void onSendConfirmed() {
        if (!mSending) {
            trySendMessage(Message.obtain(null, SendUiService.MSG_SHOW_START_SEND), true);
            mCallback.onP2pSendConfirmed();
        }
        mSending = true;

    }

    public void onBeamCanceled() {
        trySendMessage(Message.obtain(null, SendUiService.MSG_FINISH,
                SendUiService.FINISH_SCALE_UP, 0), true);
        mCallback.onP2pCanceled();
    }

    @Override
    public void onP2pSendDebounce() {
        mInDebounce = true;
        mNfcService.playSound(NfcService.SOUND_ERROR);
        trySendMessage(Message.obtain(null, SendUiService.MSG_SHOW_SEND_HINT), true);
    }

    @Override
    public void onP2pResumeSend() {
        if (mInDebounce) {
            mVibrator.vibrate(VIBRATION_PATTERN, -1);
            mNfcService.playSound(NfcService.SOUND_START);
            trySendMessage(Message.obtain(null, SendUiService.MSG_SHOW_START_SEND), true);
        }
        mInDebounce = false;
    }

    private boolean trySendMessage(Message message, boolean retry) {
        synchronized (mLock) {
            // service should already be bound, but make sure anyway
            if (bindServiceLocked()) {
                while (mBinding) {
                    try {
                        mLock.wait(SERVICE_WAIT_TIMEOUT_MILLIS);
                        if (mService == null) {
                            // bail out if waited for timeout but service never came up
                            return false;
                        }
                    } catch (InterruptedException e) {}
                }

                try {
                    mService.send(message);
                } catch (RemoteException e) {
                    Log.e(TAG, "unable to communicate with SendUiService", e);
                    if (retry) {
                        Message msg = Message.obtain(null, MSG_RETRY_SEND_MESSAGE);
                        msg.obj = message;
                        mHandler.sendMessageDelayed(msg, RETRY_DELAY_MILLIS);
                    } else {
                        mService = null;
                        bindServiceLocked();
                    }
                }

                return true;
            } else {
                return false;
            }
        }
    }

}
