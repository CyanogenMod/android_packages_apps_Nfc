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
public class ScreenshotWindowAnimator implements Handler.Callback, AnimatorUpdateListener {
    private static final String TAG = "ScreenshotWindowAnimator";

    private static final float INITIAL_SCREENSHOT_SCALE = 0.7f;
    private static final float FINAL_SCREENSHOT_SCALE = 0.3f;

    private static final int MSG_START_ANIMATION = 1;
    private static final int MSG_START_SUCCESS_ANIMATION = 2;
    private static final int MSG_START_FAIL_ANIMATION = 3;
    private static final int MSG_STOP_ANIMATIONS = 4;

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

    AnimatorSet mSuccessAnimatorSet;

    ValueAnimator mStartAnimator;
    ValueAnimator mScaleDownAnimator;
    ValueAnimator mScaleUpAnimator;
    ValueAnimator mFailureAnimator;

    // Down animation
    DecelerateInterpolator mScaleDownInterpolator;
    DecelerateInterpolator mAlphaDownInterpolator;
    DecelerateInterpolator mOffsetInterpolator;

    // Up animation
    AccelerateInterpolator mScaleUpInterpolator;
    AccelerateInterpolator mAlphaUpInterpolator;
    AccelerateInterpolator mCloneScaleDownInterpolator;


    // These are all read/written on the UI thread, so no
    // need to synchronize these.
    // TODO state var could clean this up a lot.
    boolean mAttached = false;
    boolean mWaitingForResult = true;
    boolean mSuccess = false;
    boolean mStartAnimDone = false;
    boolean mEndRequested = false;

    Handler mHandler;

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
                    endAnimation(mSuccess);
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

    void endAnimation(boolean success) {
        mHandler.sendEmptyMessage(success ? MSG_START_SUCCESS_ANIMATION :
                MSG_START_FAIL_ANIMATION);
    }

    void createAnimators() {
        mStartListener = new StartAnimationListener();
        mEndListener = new EndAnimationListener();

        // Create the starting scale down animation
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(null); // Linear time interpolation
        anim.setDuration(500); // 500 ms to scale down
        anim.addUpdateListener(this);
        anim.addListener(mStartListener);
        mStartAnimator = anim;

        // Create the cloned scale down animation
        // (First part of animation when successfully sending)
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(null);
        anim.setDuration(500);
        anim.addUpdateListener(this);
        mScaleDownAnimator = anim;

        // Create the scale up animation
        // (Second part of animation when successfully sending)
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(null);
        anim.setDuration(500);
        anim.addUpdateListener(this);
        mScaleUpAnimator = anim;

        // Combine the two in a set
        mSuccessAnimatorSet = new AnimatorSet();
        List<Animator> animList = new ArrayList<Animator>();
        animList.add(mScaleDownAnimator);
        animList.add(mScaleUpAnimator);
        mSuccessAnimatorSet.playSequentially(animList);
        mSuccessAnimatorSet.addListener(mEndListener);

        // Create the failure animator
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(null);
        anim.setDuration(500);
        anim.addUpdateListener(this);
        anim.addListener(mEndListener);
        mFailureAnimator = anim;

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
    public ScreenshotWindowAnimator(Context context) {
        mContext = context;
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
        mWindowLayoutParams =
                new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
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

        // Make sure any existing animations are ended
        endAnimations();

        // At this point no anims are running, no need to sync these
        mSuccess = false;
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
        if (mSuccessAnimatorSet != null) {
            mSuccessAnimatorSet.end();
        }
        if (mFailureAnimator != null) {
            mFailureAnimator.end();
        }
    }

    /**
     * Finalizes the running animation with either a success or a failure
     * animation.
     * Must be called from the UI thread.
     */
    public void complete(boolean result) {
        mSuccess = result;
        mWaitingForResult = false;
        if (mStartAnimDone) {
            endAnimation(result);
        } // else will show result anim when start anim is done
    }

    /**
     * Stops the currently playing animation.
     *
     * Must be called from the UI thread.
     */
    public void stop() {
        mHandler.sendEmptyMessage(MSG_STOP_ANIMATIONS);
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
        // Clone the screenshot, split the two and scale them down further
        if (mClonedView.getVisibility() != View.VISIBLE) {
            mClonedView.setVisibility(View.VISIBLE);
        }

        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE - (scale *
                (INITIAL_SCREENSHOT_SCALE - FINAL_SCREENSHOT_SCALE));

        float cloneAlpha = mAlphaDownInterpolator.getInterpolation(t) * 0.5f;

        float offset = mOffsetInterpolator.getInterpolation(t);
        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);

        mClonedView.setScaleX(scaleT);
        mClonedView.setScaleY(scaleT);
        mClonedView.setAlpha(cloneAlpha);

        mScreenshotView.setX((int) (offset * mScreenshotWidth / 4));
        mClonedView.setX((int) (offset * -mScreenshotWidth / 4));
    }

    private void onSuccessUpUpdate(ValueAnimator animation) {
        // Scale the screenshot all the way back to the front,
        // scale the clone down to zero.
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = FINAL_SCREENSHOT_SCALE +
                (scale * (1.0f - FINAL_SCREENSHOT_SCALE));
        float scaleClone = FINAL_SCREENSHOT_SCALE -
                (scale * FINAL_SCREENSHOT_SCALE);

        float cloneAlpha = 0.5f + 0.5f * mAlphaDownInterpolator.getInterpolation(t);

        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);

        mClonedView.setScaleX(scaleClone);
        mClonedView.setScaleY(scaleClone);
        mClonedView.setAlpha(cloneAlpha);

        float offset = 1 - mOffsetInterpolator.getInterpolation(t);
        mScreenshotView.setX((int) (offset * mScreenshotWidth / 4));
    }

    private void onFailureUpdate(ValueAnimator animation) {
        // Scale back from initial scale to normal scale
        // TODO add some shaking
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scale = mScaleDownInterpolator.getInterpolation(t);
        float scaleT = INITIAL_SCREENSHOT_SCALE + (scale *
                (1.0f - INITIAL_SCREENSHOT_SCALE));

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
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_START_ANIMATION: {
                mStartAnimator.start();
                break;
            }
            case MSG_START_SUCCESS_ANIMATION: {
                mSuccessAnimatorSet.start();
                break;
            }
            case MSG_START_FAIL_ANIMATION: {
                mFailureAnimator.start();
                break;
            }
            case MSG_STOP_ANIMATIONS: {
                endAnimations();
                break;
            }
        }
        return true;
    }
}
