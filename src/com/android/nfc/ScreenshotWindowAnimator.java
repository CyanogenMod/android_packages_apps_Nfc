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

import com.android.nfc3.R;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * TODO:
 *   - Performance when over gl surfaces? Ie. Gallery
 *   - what do we say in the Toast? Which icon do we get if the user uses another
 *     type of gallery?
 */
public class ScreenshotWindowAnimator implements Handler.Callback, AnimatorUpdateListener,
        View.OnTouchListener {
    private static final String TAG = "ScreenshotWindowAnimator";

    private static final float INITIAL_SCREENSHOT_SCALE = 0.7f;
    private static final float FINAL_SCREENSHOT_SCALE = 0.0f;

    private static final int MSG_START_ANIMATION = 1;
    private static final int MSG_START_SEND_ANIMATION = 2;
    private static final int MSG_START_SEND_RECV_ANIMATION = 3;
    private static final int MSG_START_FAIL_ANIMATION = 4;
    private static final int MSG_STOP_ANIMATIONS = 5;

    private static final int RESULT_WAITING = 0;
    private static final int RESULT_FAILURE = 1;
    private static final int RESULT_SEND = 2;
    private static final int RESULT_SEND_RECV = 3;

    private static int mResult;

    Context mContext;
    LayoutInflater mLayoutInflater;
    WindowManager mWindowManager;
    WindowManager.LayoutParams mWindowLayoutParams;
    Display mDisplay;
    DisplayMetrics mDisplayMetrics;
    Matrix mDisplayMatrix;
    Bitmap mScreenBitmap;
    View mScreenshotLayout;
    ImageView mScreenshotView;
    ImageView mClonedView;
    int mScreenshotWidth;

    StartAnimationListener mStartListener;
    EndAnimationListener mEndListener;

    // Start animator, always played
    ValueAnimator mStartAnimator;

    // Send only animation
    AnimatorSet mSendRecvAnimatorSet;
    ValueAnimator mScaleDownAnimator;
    ValueAnimator mScaleUpAnimator;

    // Send/receive animation
    ValueAnimator mFadeToBlackAnimator;

    // Failure animation
    AnimatorSet mFailureAnimatorSet;
    ValueAnimator mCenterToLeftAnimator;
    ValueAnimator mLeftToRightAnimator;
    ValueAnimator mRightToCenterAnimator;

    ValueAnimator mFailureAnimator;
    // Down interpolators
    DecelerateInterpolator mScaleDownInterpolator;
    DecelerateInterpolator mAlphaDownInterpolator;
    DecelerateInterpolator mOffsetInterpolator;

    // Up interpolators
    AccelerateInterpolator mScaleUpInterpolator;
    AccelerateInterpolator mAlphaUpInterpolator;
    AccelerateInterpolator mCloneScaleDownInterpolator;


    // These are all read/written on the UI thread, so no
    // need to synchronize these.
    // TODO state var could clean this up a lot.
    boolean mAttached = false;
    boolean mWaitingForResult = true;
    boolean mStartAnimDone = false;
    boolean mEndRequested = false;

    final Handler mHandler;
    final Callback mCallback;

    /* Interface to be used whenever the user confirms
     * the send action.
     */
    interface Callback {
        public void onConfirmSend();
    }

    class StartAnimationListener extends AnimatorListenerAdapter {
        @Override
        // Note that this will be called on the UI thread!
        public void onAnimationEnd(Animator animation) {
            if (mEndRequested) {
                // Ended on request, don't start follow-up anim
                // and get rid of the view
                if (mAttached) {
                    mWindowManager.removeView(mScreenshotLayout);
                    mAttached = false;
                }
            } else {
                mStartAnimDone = true;
                if (!mWaitingForResult) { // Result already in
                    playEndAnimation(mResult);
                } //  else, wait for it
            }
        }
    }

    class EndAnimationListener extends AnimatorListenerAdapter {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (mAttached) {
                mWindowManager.removeView(mScreenshotLayout);
                mAttached = false;
            }
        }
    }

    void playEndAnimation(int result) {
        switch (result) {
            case RESULT_SEND:
                mHandler.sendEmptyMessage(MSG_START_SEND_ANIMATION);
                break;
            case RESULT_SEND_RECV:
                mHandler.sendEmptyMessage(MSG_START_SEND_RECV_ANIMATION);
                break;
            case RESULT_FAILURE:
                mHandler.sendEmptyMessage(MSG_START_FAIL_ANIMATION);
                break;
        }
    }

    ValueAnimator getFloatAnimation(int duration, AnimatorUpdateListener updateListener,
            AnimatorListener listener) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(null);
        anim.setDuration(duration);
        if (updateListener != null) {
            anim.addUpdateListener(updateListener);
        }
        if (listener != null) {
            anim.addListener(listener);
        }

        return anim;
    }
    void createAnimators() {
        mStartListener = new StartAnimationListener();
        mEndListener = new EndAnimationListener();

        mStartAnimator = getFloatAnimation(500, this, mStartListener);
        mFadeToBlackAnimator = getFloatAnimation(500, this, mEndListener);

        mScaleDownAnimator = getFloatAnimation(500, this, null);
        mScaleUpAnimator = getFloatAnimation(500, this, null);
        // Combine the two in a set
        mSendRecvAnimatorSet = new AnimatorSet();
        List<Animator> animList = new ArrayList<Animator>();
        animList.add(mScaleDownAnimator);
        animList.add(mScaleUpAnimator);
        mSendRecvAnimatorSet.playSequentially(animList);
        mSendRecvAnimatorSet.addListener(mEndListener);

        mCenterToLeftAnimator = getFloatAnimation(80, this, null);
        mLeftToRightAnimator = getFloatAnimation(80, this, null);
        mLeftToRightAnimator.setRepeatCount(4);
        mLeftToRightAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mRightToCenterAnimator = getFloatAnimation(80, this, null);
        mFailureAnimator = getFloatAnimation(500, this, null);

        // Combine them into a set
        mFailureAnimatorSet = new AnimatorSet();
        animList.clear();

        /*
        animList.add(mCenterToLeftAnimator);
        animList.add(mLeftToRightAnimator);
        animList.add(mRightToCenterAnimator);
        */
        animList.add(mFailureAnimator);
        mFailureAnimatorSet.playSequentially(animList);
        mFailureAnimatorSet.addListener(mEndListener);

        mScaleDownInterpolator = new DecelerateInterpolator(1.5f);
        mAlphaDownInterpolator = new DecelerateInterpolator(1f);
        mOffsetInterpolator = new DecelerateInterpolator(1.5f);

        mScaleUpInterpolator = new AccelerateInterpolator(1.5f);
        mAlphaUpInterpolator = new AccelerateInterpolator(1.5f);
        mCloneScaleDownInterpolator = new AccelerateInterpolator(1.0f);

    }

    /**
     * @param context everything needs a context :(
     */
    public ScreenshotWindowAnimator(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
        mHandler = new Handler(this);
        mLayoutInflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        createAnimators();

        // Inflate the screenshot layout
        mDisplayMetrics = new DisplayMetrics();
        mDisplayMatrix = new Matrix();
        mScreenshotLayout = mLayoutInflater.inflate(R.layout.screenshot, null);

        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.screenshot);
        mClonedView = (ImageView) mScreenshotLayout.findViewById(R.id.clone);
        mScreenshotLayout.setFocusable(true);
        /*
         * this doesn't work on the TYPE_SYSTEM_OVERLAY layer
         * mScreenshotLayout.setOnTouchListener(new View.OnTouchListener() {
         *
         * @Override public boolean onTouch(View v, MotionEvent event) { //
         * Intercept and ignore all touch events return true; } });
         */

        // Setup the window that we are going to use
        // TODO Figure out how to do OnTouch using TYPE_SYSTEM_OVERLAY
        // and re-add TYPE_SYSTEM_OVERLAY to layout params below.
        mWindowLayoutParams =
                new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                0,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED_SYSTEM
                    | WindowManager.LayoutParams.FLAG_KEEP_SURFACE_WHILE_ANIMATING
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        mWindowLayoutParams.token = new Binder();
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();

        mScreenshotView.setOnTouchListener(this);
        mClonedView.setOnTouchListener(this);



    }

    /**
     * @return the current display rotation in degrees
     */
    private float getDegreesForRotation(int value) {
        switch (value) {
        case Surface.ROTATION_90:
            return 90f;
        case Surface.ROTATION_180:
            return 180f;
        case Surface.ROTATION_270:
            return 270f;
        }
        return 0f;
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     *
     * Must be called from the UI thread.
     */
    public void start() {
        // We need to orient the screenshot correctly (and the Surface api seems to
        // take screenshots
        // only in the natural orientation of the device :!)

        // Make sure any existing animations are ended
        endAnimations();

        mDisplay.getRealMetrics(mDisplayMetrics);
        float[] dims = {mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels};
        float degrees = getDegreesForRotation(mDisplay.getRotation());
        boolean requiresRotation = (degrees > 0);
        if (requiresRotation) {
            // Get the dimensions of the device in its native orientation
            mDisplayMatrix.reset();
            mDisplayMatrix.preRotate(-degrees);
            mDisplayMatrix.mapPoints(dims);
            dims[0] = Math.abs(dims[0]);
            dims[1] = Math.abs(dims[1]);
        }

        mScreenBitmap = Surface.screenshot((int) dims[0], (int) dims[1]);
        // Bail if we couldn't take the screenshot
        if (mScreenBitmap == null) {
            Log.e(TAG, "couldn't get screenshot");
            return;
        }

        if (requiresRotation) {
            // Rotate the screenshot to the current orientation
            Bitmap ss = Bitmap.createBitmap(mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(ss);
            c.translate(ss.getWidth() / 2, ss.getHeight() / 2);
            c.rotate(360f - degrees);
            c.translate(-dims[0] / 2, -dims[1] / 2);
            c.drawBitmap(mScreenBitmap, 0, 0, null);

            mScreenBitmap = ss;
        }

        // The clone is hidden at the beginning
        mClonedView.setImageBitmap(mScreenBitmap);
        mClonedView.setVisibility(View.GONE);

        // Start the post-screenshot animation

        mScreenshotView.setImageBitmap(mScreenBitmap);

        mScreenshotLayout.requestFocus();

        mScreenshotWidth = mScreenBitmap.getWidth();

        // At this point no anims are running, no need to sync these
        mResult = RESULT_WAITING;
        mWaitingForResult = true;
        mStartAnimDone = false;
        mEndRequested = false;

        // Add the view for the animation
        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);

        mAttached = true;
        mHandler.sendEmptyMessage(MSG_START_ANIMATION);
    }

    private void endAnimations() {
        mEndRequested = true;

        if (mStartAnimator != null) {
            mStartAnimator.end();
        }
        if (mSendRecvAnimatorSet != null) {
            mSendRecvAnimatorSet.end();
        }
        if (mFadeToBlackAnimator != null) {
            mFadeToBlackAnimator.end();
        }
        if (mFailureAnimatorSet != null) {
            mFailureAnimatorSet.end();
        }

        if (mAttached) {
            mWindowManager.removeView(mScreenshotLayout);
            mAttached = false;
        }
    }

    private void postResult(int result) {
        mResult = result;
        mWaitingForResult = false;
        if (mStartAnimDone) {
            playEndAnimation(mResult);
        } // else end animation will play when the start anim is done
    }

    /**
     * Finalizes the running animation with a failure animation.
     * Must be called from the UI thread.
     */
    public void finishWithFailure() {
        postResult(RESULT_FAILURE);
    }

    /**
     * Finalizes the running animation with the send/recv animation.
     * Must be called from the UI thread.
     */
    public void finishWithSendReceive() {
        postResult(RESULT_SEND_RECV);
    }

    /**
     * Finalizes the running animation with the send-only animation.
     * Must be called from the UI thread.
     */
    public void finishWithSend() {
        postResult(RESULT_SEND);
    }

    /**
     * Stops the currently playing animation.
     *
     * Must be called from the UI thread.
     */
    public void stop() {
        endAnimations();
    }

    private void onStartAnimationUpdate(ValueAnimator animation) {
        // Just scale the screenshot down
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE + (1f - scale) *
                (1 - INITIAL_SCREENSHOT_SCALE);

        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);
    }

    private void onSuccessCloneAnimationUpdate(ValueAnimator animation) {
        // Clone the screenshot
        if (mClonedView.getVisibility() != View.VISIBLE) {
            // Scale clone to same size
            mClonedView.setScaleX(mScreenshotView.getScaleX());
            mClonedView.setScaleY(mScreenshotView.getScaleY());
            mClonedView.setVisibility(View.VISIBLE);

            mScreenshotView.setAlpha(0.5f);
        }

        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE - (scale *
                (INITIAL_SCREENSHOT_SCALE - FINAL_SCREENSHOT_SCALE));

        mClonedView.setScaleX(scaleT);
        mClonedView.setScaleY(scaleT);
    }

    private void onSuccessUpUpdate(ValueAnimator animation) {
        // Scale the screenshot all the way back to the front,
        // scale the clone down to zero.
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE +
                (scale * (1.0f - INITIAL_SCREENSHOT_SCALE));
        float alpha = 0.5f + (0.5f * mAlphaDownInterpolator.getInterpolation(t));
        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);
        mScreenshotView.setAlpha(alpha);
    }

    private void onCenterToLeftUpdate(ValueAnimator animation) {
        // scale the clone down to zero.
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);

        mScreenshotView.setX(scale * -mScreenshotWidth / 4);
    }

    private void onLeftToRightUpdate(ValueAnimator animation) {
        float t = ((Float) animation.getAnimatedValue()).floatValue();

        mScreenshotView.setX(-mScreenshotWidth / 4 +
                t * mScreenshotWidth / 2);
    }

    private void onRightToCenterUpdate(ValueAnimator animation) {
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        mScreenshotView.setX((1 - scale) * mScreenshotWidth / 4);
    }

    private void onFailureUpdate(ValueAnimator animation) {
        // Scale back from initial scale to normal scale
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE + (scale *
                (1.0f - INITIAL_SCREENSHOT_SCALE));

        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);

    }

    private void onFadeToBlackUpdate(ValueAnimator animation) {
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE - (INITIAL_SCREENSHOT_SCALE * scale);

        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);
    }

    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
        if (animation == mStartAnimator) {
            onStartAnimationUpdate(animation);
        } else if (animation == mScaleDownAnimator) {
            onSuccessCloneAnimationUpdate(animation);
        } else if (animation == mScaleUpAnimator) {
            onSuccessUpUpdate(animation);
        } else if (animation == mFailureAnimator) {
            onFailureUpdate(animation);
        } else if (animation == mFadeToBlackAnimator) {
            onFadeToBlackUpdate(animation);
        } else if (animation == mCenterToLeftAnimator) {
            onCenterToLeftUpdate(animation);
        } else if (animation == mLeftToRightAnimator) {
            onLeftToRightUpdate(animation);
        } else if (animation == mRightToCenterAnimator) {
            onRightToCenterUpdate(animation);
        } else if (animation == mFailureAnimator) {
            onFailureUpdate(animation);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_START_ANIMATION: {
                mStartAnimator.start();
                break;
            }
            case MSG_START_SEND_ANIMATION: {
                mSendRecvAnimatorSet.start();
                break;
            }
            case MSG_START_SEND_RECV_ANIMATION: {
                mFadeToBlackAnimator.start();
                break;
            }
            case MSG_START_FAIL_ANIMATION: {
                mFailureAnimatorSet.start();
                break;
            }
            case MSG_STOP_ANIMATIONS: {
                endAnimations();
                break;
            }
        }
        return true;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        mCallback.onConfirmSend();
        return true;
    }
}
