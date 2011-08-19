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
    final NotificationManager mNotificationManager;
    final SendUi mSendUi;

    // only used on UI thread
    boolean mPrefsFirstShare;
    boolean mSending;

    public P2pEventManager(Context context, P2pEventListener.Callback callback) {
        mContext = context;
        mCallback = callback;
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
        mSendUi = new SendUi(context, this);
    }

    @Override
    public void onP2pInRange() {
        mSendUi.takeScreenshot();
    }

    @Override
    public void onP2pSendConfirmationRequested() {
        mSendUi.showPreSend();
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
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        playSound(mEndSound);
    }

    @Override
    public void onP2pOutOfRange() {
        if (mSending) {
            playSound(mErrorSound);
            mSending = false;
        }
        mSendUi.dismiss();
        mSendUi.releaseScreenshot();
    }

    @Override
    public void onSendConfirmed() {
        mSending = true;
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
