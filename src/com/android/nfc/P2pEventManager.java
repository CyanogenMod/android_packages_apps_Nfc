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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

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

    public static final boolean TILT_ENABLED = true;
    public static final boolean TAP_ENABLED = true;

    final Context mContext;
    final P2pEventListener.Callback mCallback;
    final SharedPreferences mPrefs;
    final int mStartSound;
    final int mEndSound;
    final int mErrorSound;
    final SoundPool mSoundPool; // playback synchronized on this
    final Vibrator mVibrator;
    final TiltDetector mTiltDetector;
    final NotificationManager mNotificationManager;

    // only used on UI thread
    boolean mPrefsFirstShare;
    boolean mAnimating;

    /** Detect if the screen is facing up or down */
    class TiltDetector implements SensorEventListener {
        /**
         * Percent tilt required before triggering detection.
         * 100 indicates the device must be exactly face-down.
         */
        static final int THRESHOLD_PERCENT = 75;
        final SensorManager mSensorManager;
        final Sensor mSensor;

        // Only used on UI thread
        boolean mSensorEnabled;
        boolean mTriggerEnabled;
        float mLastValue;

        public TiltDetector(Context context) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            mSensorEnabled = false;
            mTriggerEnabled = false;
            mLastValue = Float.MIN_VALUE;
        }
        public void enable() {
            if (mSensorEnabled) {
                return;
            }
            mSensorEnabled = true;
            mLastValue = Float.MIN_VALUE;
            mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_UI);
        }
        public boolean enableTrigger() {
            if (!mSensorEnabled || mTriggerEnabled) {
                return false;
            }
            mTriggerEnabled = true;
            return checkTrigger();
        }
        public void disable() {
            if (!mSensorEnabled) {
                return;
            }
            mSensorManager.unregisterListener(this, mSensor);
            mSensorEnabled = false;
            mTriggerEnabled = false;
        }
        boolean checkTrigger() {
            if (!mTriggerEnabled ||
                    100.0 * mLastValue / SensorManager.GRAVITY_EARTH < THRESHOLD_PERCENT) {
                return false;
            }
            disable();
            mVibrator.vibrate(VIBRATION_PATTERN, -1);
            mCallback.onP2pSendConfirmed();
            return true;
        }
        @Override
        public void onSensorChanged(SensorEvent event) {
            // always called on UI thread
            mLastValue = event.values[2];
            if (DBG) Log.d(TAG, "z=" + mLastValue);
            checkTrigger();
        }
        @Override
        public void onAccuracyChanged(Sensor arg0, int arg1) { }
    };

    public P2pEventManager(Context context, P2pEventListener.Callback callback) {
        mContext = context;
        mCallback = callback;
        mTiltDetector = new TiltDetector(mContext);
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

        P2pAnimationActivity.setCallback(mCallback);
        mAnimating = false;
    }

    @Override
    public void onP2pInRange() {
        if (TILT_ENABLED) {
            mTiltDetector.enable();
        }
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        P2pAnimationActivity.makeScreenshot(mContext);
    }

    @Override
    public void onP2pSendConfirmationRequested() {
        if (TILT_ENABLED && mTiltDetector.enableTrigger()) {
            return;
        }

        mAnimating = true;
        // Start the animation activity
        Intent animIntent = new Intent();
        animIntent.setClass(mContext, P2pAnimationActivity.class);
        animIntent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(animIntent);
        playSound(mStartSound);
    }

    @Override
    public void onP2pSendComplete() {
        playSound(mEndSound);
        checkFirstShare();
        finish(true, false);
    }

    @Override
    public void onP2pReceiveComplete() {
        if (TILT_ENABLED) {
            mTiltDetector.disable();
        }
        finish(false, true);
    }

    @Override
    public void onP2pOutOfRange() {
        if (TILT_ENABLED) {
            mTiltDetector.disable();
        }
        if (mAnimating) {
            playSound(mErrorSound);
        }
        finish(false, false);
    }

    /**
     * Finish up the animation, if running.
     * Must be called on the UI thread.
     */
    void finish(boolean sendSuccess, boolean receiveSuccess) {
        if (!mAnimating) {
            return;
        }
        if (sendSuccess) {
            P2pAnimationActivity.finishWithSend();
        } else if (receiveSuccess) {
            P2pAnimationActivity.finishWithReceive();
        } else {
            P2pAnimationActivity.finishWithFailure();
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
