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
public class P2pEventManager implements P2pEventListener, SendUi.Callback {
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
    final TiltDetector mTiltDetector;
    final NotificationManager mNotificationManager;
    final HoldingItWrongUi mHoldingItWrongUi;
    final SendUi mSendUi;

    // only used on UI thread
    boolean mPrefsFirstShare;
    boolean mSending;

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
        float mLastValue;

        public TiltDetector(Context context) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mSensorEnabled = false;
        }
        public void enable() {
            if (mSensorEnabled) {
                return;
            }
            mSensorEnabled = true;
            mLastValue = Float.MIN_VALUE;
            mSensorManager.registerListener(this, mSensor, SensorManager.SENSOR_DELAY_UI);
        }
        public void disable() {
            if (!mSensorEnabled) {
                return;
            }
            mSensorManager.unregisterListener(this, mSensor);
            mSensorEnabled = false;
        }
        @Override
        public void onSensorChanged(SensorEvent event) {
            // always called on UI thread
            if (!mSensorEnabled) {
                return;
            }
            final float z = event.values[2];
            final boolean triggered = 100.0 * z / SensorManager.GRAVITY_EARTH > THRESHOLD_PERCENT;
            //TODO: apply a low pass filter so we get something closer to real gravity
            if (DBG) Log.d(TAG, "z=" + z + (triggered ? " TRIGGERED" : ""));
            if (mLastValue == Float.MIN_VALUE && !triggered) {
                // Received first value, and you're holding it wrong
                mHoldingItWrongUi.show(mContext);
            }
            mLastValue = z;
            if (triggered) {
                disable();
                onSendConfirmed();
            }
            return;
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

        mSending = false;
        mHoldingItWrongUi = new HoldingItWrongUi();
        mSendUi = new SendUi(context, this);
    }

    @Override
    public void onP2pInRange() {
        mSendUi.takeScreenshot();
    }

    @Override
    public void onP2pSendConfirmationRequested() {
        mTiltDetector.enable();
    }

    @Override
    public void onP2pSendComplete() {
        checkFirstShare();
        playSound(mEndSound);
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        mSendUi.showPostSend();
        mSending = false;
    }

    @Override
    public void onP2pReceiveComplete() {
        mHoldingItWrongUi.dismiss();
        mTiltDetector.disable();
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        playSound(mEndSound);
    }

    @Override
    public void onP2pOutOfRange() {
        mHoldingItWrongUi.dismiss();
        mTiltDetector.disable();
        if (mSending) {
            playSound(mErrorSound);
            mSendUi.dismiss();
            mSending = false;
        }
        mSendUi.releaseScreenshot();
    }

    void onSendConfirmed() {
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        playSound(mStartSound);
        mHoldingItWrongUi.dismiss();
        mSending = true;
        mSendUi.showPreSend();
    }

    @Override
    public void onPreFinished() {
        mCallback.onP2pSendConfirmed();
    }

    /** If first time, display a notification */
    void checkFirstShare() {
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

    void playSound(int sound) {
        mSoundPool.play(sound, 1.0f, 1.0f, 0, 0, 1.0f);
    }
}
