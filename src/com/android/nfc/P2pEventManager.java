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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.OrientationEventListener;

import com.android.nfc3.R;

/**
 * Manages vibration, sound and animation for P2P events.
 */
public class P2pEventManager implements P2pEventListener {
    static final String TAG = "NfcP2pEventManager";
    static final boolean DBG = true;

    static final String PREF_FIRST_SHARE = "first_share";
    static final int NOTIFICATION_FIRST_SHARE = 0;

    static final long[] VIBRATION_PATTERN = {0, 100, 10000};

    final Context mContext;
    final P2pEventListener.Callback mCallback;
    final SharedPreferences mPrefs;
    final int mStartSound;
    final int mEndSound;
    final int mErrorSound;
    final SoundPool mSoundPool; // playback synchronized on this
    final Vibrator mVibrator;
    final RotationDetector mRotationDetector;
    final NotificationManager mNotificationManager;

    boolean mAnimating;
    boolean mPrefsFirstShare;

    class RotationDetector extends OrientationEventListener {
        static final int THRESHOLD_DEGREES = 35;
        int mStartOrientation;
        boolean mRotateDetectionEnabled;

        public RotationDetector(Context context) {
            super(context);
        }
        public void start() {
            synchronized (RotationDetector.this) {
                mRotateDetectionEnabled = true;
                mStartOrientation = -1;
                super.enable();
            }
        }
        public void cancel() {
            synchronized (RotationDetector.this) {
                mRotateDetectionEnabled = false;
                super.disable();
            }
        }
        @Override
        public void onOrientationChanged(int orientation) {
            synchronized (RotationDetector.this) {
                if (!mRotateDetectionEnabled) {
                    return;
                }
                if (mStartOrientation < 0) {
                    mStartOrientation = orientation;
                }
                int diff = Math.abs(mStartOrientation - orientation);
                if (diff > THRESHOLD_DEGREES && diff < (360-THRESHOLD_DEGREES)) {
                    cancel();
                    mVibrator.vibrate(VIBRATION_PATTERN, -1);
                    mCallback.onP2pSendConfirmed();
                }
            }
        }
    }

    public P2pEventManager(Context context, P2pEventListener.Callback callback) {
        mContext = context;
        mCallback = callback;
        mRotationDetector = new RotationDetector(mContext);
        mSoundPool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
        mStartSound = mSoundPool.load(mContext, R.raw.start, 1);
        mEndSound = mSoundPool.load(mContext, R.raw.end, 1);
        mErrorSound = mSoundPool.load(mContext, R.raw.error, 1);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);

        SharedPreferences prefs = mContext.getSharedPreferences(NfcService.PREF,
                Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_FIRST_SHARE, true)) {
            mPrefs = prefs;
            mPrefsFirstShare = true;
        } else {
            // don't need to check pref again
            mPrefs = null;
            mPrefsFirstShare = false;
        }

        mAnimating = false;
    }

    @Override
    public void onP2pInRange() {
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        playSound(mStartSound);
    }

    @Override
    public void onP2pSendConfirmationRequested() {
        mRotationDetector.start();
        P2pAnimationActivity.makeScreenshot(mContext);
        P2pAnimationActivity.setCallback(mCallback);
        mAnimating = true;
        // Start the animation activity
        Intent animIntent = new Intent();
        animIntent.setClass(mContext, P2pAnimationActivity.class);
        animIntent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(animIntent);
    }

    @Override
    public void onP2pSendComplete(boolean result) {
        if (!result) {
            playSound(mErrorSound);
        } else {
            playSound(mEndSound);
            checkFirstShare();
        }
        finish(result, false);
    }

    @Override
    public void onP2pReceiveComplete() {
        mRotationDetector.cancel();
        finish(false, true);
    }

    @Override
    public void onP2pOutOfRange() {
        mRotationDetector.cancel();
        if (mAnimating) {
            finish(false, false);
            playSound(mErrorSound);
        }
    }

    /**
     * Finish up the animation, if running, and play ending sounds.
     * Must be called on the UI thread.
     */
    void finish(boolean sendSuccess, boolean receiveSuccess) {
        if (sendSuccess) {
            if (mAnimating) {
                P2pAnimationActivity.finishWithSend();
            }
        } else if (receiveSuccess) {
            if (mAnimating) {
                P2pAnimationActivity.finishWithReceive();
            }
        } else {
            if (mAnimating) {
                P2pAnimationActivity.finishWithFailure();
            }
        }
        mAnimating = false;
    }

    /** If first time, display up a notification */
    void checkFirstShare() {
        synchronized (this) {
            if (mPrefsFirstShare) {
                mPrefsFirstShare = false;
                SharedPreferences.Editor editor = mPrefs.edit();
                editor.putBoolean(PREF_FIRST_SHARE, false);
                editor.apply();

                Intent intent = new Intent(Settings.ACTION_NFCSHARING_SETTINGS);
                PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                Notification notification = new Notification.Builder(mContext)
                        .setContentTitle(mContext.getString(R.string.first_share_title))
                        .setContentText(mContext.getString(R.string.first_share_text))
                        .setContentIntent(pi)
                        .setSmallIcon(R.drawable.stat_sys_nfc)
                        .setAutoCancel(true)
                        .getNotification();
                mNotificationManager.notify(NOTIFICATION_FIRST_SHARE, notification);
            }
        }
    }

    void playSound(int sound) {
        synchronized (this) {
            mSoundPool.play(sound, 1.0f, 1.0f, 0, 0, 1.0f);
        }
    }
}
