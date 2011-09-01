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
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * All methods must be called on UI thread
 */
public class SendUi implements Animator.AnimatorListener, View.OnTouchListener {
    private static final String LOG_TAG = "SendUI";

    static final float INTERMEDIATE_SCALE = 0.6f;

    static final float[] PRE_SCREENSHOT_SCALE = {1.0f, INTERMEDIATE_SCALE};
    static final int PRE_DURATION_MS = 300;

    static final float[] CLONE_SCREENSHOT_SCALE = {INTERMEDIATE_SCALE, 0.0f};
    static final int SLOW_CLONE_DURATION_MS = 3000; // Stretch out sending over 3s
    static final int FAST_CLONE_DURATION_MS = 200;

    static final float[] SCALE_UP_SCREENSHOT_SCALE = {INTERMEDIATE_SCALE, 1.0f};
    static final int SCALE_UP_DURATION_MS = 300;

    static final int SLIDE_OUT_DURATION_MS = 300;

    static final float[] TEXT_HINT_ALPHA_RANGE = {0.0f, 1.0f};
    static final int TEXT_HINT_ALPHA_DURATION_MS = 500;

    static final int FINISH_SCALE_UP = 0;
    static final int FINISH_SLIDE_OUT = 1;

    // all members are only used on UI thread
    final WindowManager mWindowManager;
    final Context mContext;
    final Display mDisplay;
    final DisplayMetrics mDisplayMetrics;
    final Matrix mDisplayMatrix;
    final WindowManager.LayoutParams mWindowLayoutParams;
    final LayoutInflater mLayoutInflater;
    final StatusBarManager mStatusBarManager;
    final View mScreenshotLayout;
    final ImageView mScreenshotView;
    final ImageView mCloneView;
    final TextView mTextHint;
    final Callback mCallback;
    final ObjectAnimator mPreAnimator;
    final ObjectAnimator mSlowCloneAnimator;
    final ObjectAnimator mFastCloneAnimator;
    final ObjectAnimator mScaleUpAnimator;
    final ObjectAnimator mHintAnimator;
    final AnimatorSet mSuccessAnimatorSet;
    final boolean mHardwareAccelerated;

    Bitmap mScreenshotBitmap;
    ObjectAnimator mSlideoutAnimator;

    boolean mAttached;

    interface Callback {
        public void onSendConfirmed();
    }

    public SendUi(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;

        mDisplayMetrics = new DisplayMetrics();
        mDisplayMatrix = new Matrix();
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mStatusBarManager = (StatusBarManager) context.getSystemService(Context.STATUS_BAR_SERVICE);

        mDisplay = mWindowManager.getDefaultDisplay();

        mLayoutInflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mScreenshotLayout = mLayoutInflater.inflate(R.layout.screenshot, null);
        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.screenshot);
        mScreenshotLayout.setFocusable(true);

        mCloneView = (ImageView) mScreenshotLayout.findViewById(R.id.clone);

        mTextHint = (TextView) mScreenshotLayout.findViewById(R.id.calltoaction);

        // We're only allowed to use hardware acceleration if
        // isHighEndGfx() returns true - otherwise, we're too limited
        // on resources to do it.
        mHardwareAccelerated = ActivityManager.isHighEndGfx(mDisplay);
        int hwAccelerationFlags = mHardwareAccelerated ?
                (WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                 | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED_SYSTEM) : 0;

        mWindowLayoutParams = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                | hwAccelerationFlags
                | WindowManager.LayoutParams.FLAG_KEEP_SURFACE_WHILE_ANIMATING
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        mWindowLayoutParams.token = new Binder();

        PropertyValuesHolder preX = PropertyValuesHolder.ofFloat("scaleX", PRE_SCREENSHOT_SCALE);
        PropertyValuesHolder preY = PropertyValuesHolder.ofFloat("scaleY", PRE_SCREENSHOT_SCALE);
        mPreAnimator = ObjectAnimator.ofPropertyValuesHolder(mScreenshotView, preX, preY);
        mPreAnimator.setInterpolator(new DecelerateInterpolator());
        mPreAnimator.setDuration(PRE_DURATION_MS);
        mPreAnimator.addListener(this);

        PropertyValuesHolder postX = PropertyValuesHolder.ofFloat("scaleX", CLONE_SCREENSHOT_SCALE);
        PropertyValuesHolder postY = PropertyValuesHolder.ofFloat("scaleY", CLONE_SCREENSHOT_SCALE);
        mSlowCloneAnimator = ObjectAnimator.ofPropertyValuesHolder(mCloneView, postX, postY);
        mSlowCloneAnimator.setInterpolator(null); // linear
        mSlowCloneAnimator.setDuration(SLOW_CLONE_DURATION_MS);

        mFastCloneAnimator = ObjectAnimator.ofPropertyValuesHolder(mCloneView, postX, postY);
        mFastCloneAnimator.setInterpolator(null); // linear
        mFastCloneAnimator.setDuration(FAST_CLONE_DURATION_MS);

        PropertyValuesHolder scaleUpX = PropertyValuesHolder.ofFloat("scaleX", SCALE_UP_SCREENSHOT_SCALE);
        PropertyValuesHolder scaleUpY = PropertyValuesHolder.ofFloat("scaleY", SCALE_UP_SCREENSHOT_SCALE);
        mScaleUpAnimator = ObjectAnimator.ofPropertyValuesHolder(mScreenshotView, scaleUpX, scaleUpY);
        mScaleUpAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mScaleUpAnimator.setDuration(SCALE_UP_DURATION_MS);
        mScaleUpAnimator.addListener(this);

        PropertyValuesHolder alphaUp = PropertyValuesHolder.ofFloat("alpha", TEXT_HINT_ALPHA_RANGE);
        mHintAnimator = ObjectAnimator.ofPropertyValuesHolder(mTextHint, alphaUp);
        mHintAnimator.setInterpolator(null);
        mHintAnimator.setDuration(TEXT_HINT_ALPHA_DURATION_MS);

        mSuccessAnimatorSet = new AnimatorSet();
        mSuccessAnimatorSet.playSequentially(mFastCloneAnimator, mScaleUpAnimator);

        mAttached = false;
    }

    public void takeScreenshot() {
        mScreenshotBitmap = createScreenshot();
    }

    /** Fades in the textual hint */
    public void fadeInHint() {
        mTextHint.setAlpha(0.0f);
        mTextHint.setVisibility(View.VISIBLE);
        mHintAnimator.start();
    }

    /** Show pre-send animation */
    public void showPreSend(boolean showHint) {
        if (mScreenshotBitmap == null || mAttached) {
            return;
        }
        mScreenshotView.setOnTouchListener(this);
        mScreenshotView.setImageBitmap(mScreenshotBitmap);
        mScreenshotView.setTranslationX(0f);
        mScreenshotView.setAlpha(1.0f);
        mScreenshotLayout.requestFocus();

        mCloneView.setImageBitmap(mScreenshotBitmap);
        mCloneView.setVisibility(View.GONE);
        mCloneView.setScaleX(INTERMEDIATE_SCALE);
        mCloneView.setScaleY(INTERMEDIATE_SCALE);


        mTextHint.setVisibility(showHint ? View.VISIBLE : View.GONE);
        mTextHint.setAlpha(1.0f);

        // Lock the orientation.
        // The orientation from the configuration does not specify whether
        // the orientation is reverse or not (ie landscape or reverse landscape).
        // So we have to use SENSOR_LANDSCAPE or SENSOR_PORTRAIT to make sure
        // we lock in portrait / landscape and have the sensor determine
        // which way is up.
        int orientation = mContext.getResources().getConfiguration().orientation;

        switch (orientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                mWindowLayoutParams.screenOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case Configuration.ORIENTATION_PORTRAIT:
                mWindowLayoutParams.screenOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            default:
                mWindowLayoutParams.screenOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
        }

        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        // Disable statusbar pull-down
        mStatusBarManager.disable(StatusBarManager.DISABLE_EXPAND);

        mAttached = true;
        mPreAnimator.start();
    }

    /** Show starting send animation */
    public void showStartSend() {
        if (!mAttached) {
            return;
        }
        mScreenshotView.setAlpha(0.7f);
        mCloneView.setScaleX(INTERMEDIATE_SCALE);
        mCloneView.setScaleY(INTERMEDIATE_SCALE);
        mCloneView.setVisibility(View.VISIBLE);
        mSlowCloneAnimator.start();
    }

    /** Show post-send animation */
    public void showPostSend() {
        if (!mAttached) {
            return;
        }

        mSlowCloneAnimator.cancel();
        mTextHint.setVisibility(View.GONE);
        // Modify the fast clone parameters to match the current scale
        float currentScale = mCloneView.getScaleX();
        currentScale = mCloneView.getScaleX();

        PropertyValuesHolder postX = PropertyValuesHolder.ofFloat("scaleX", new float[] {currentScale, 0.0f});
        PropertyValuesHolder postY = PropertyValuesHolder.ofFloat("scaleY", new float[] {currentScale, 0.0f});
        mFastCloneAnimator.setValues(postX, postY);

        mSuccessAnimatorSet.start();
    }

    /** Return to initial state */
    public void finish(int finishMode) {
        if (!mAttached) {
            return;
        }
        mTextHint.setVisibility(View.GONE);
        if (finishMode == FINISH_SLIDE_OUT) {
            PropertyValuesHolder slideX = PropertyValuesHolder.ofFloat("translationX",
                    new float[]{0.0f, mScreenshotView.getWidth()});
            mSlideoutAnimator = ObjectAnimator.ofPropertyValuesHolder(mScreenshotView, slideX);
            mSlideoutAnimator.setInterpolator(new AccelerateInterpolator());
            mSlideoutAnimator.setDuration(SLIDE_OUT_DURATION_MS);
            mSlideoutAnimator.addListener(this);
            mSlideoutAnimator.start();
        } else {
            mScaleUpAnimator.start();
        }
    }

    public void dismiss() {
        if (!mAttached) {
            return;
        }
        mPreAnimator.cancel();
        mSlowCloneAnimator.cancel();
        mFastCloneAnimator.cancel();
        mSuccessAnimatorSet.cancel();
        mScaleUpAnimator.cancel();
        mWindowManager.removeView(mScreenshotLayout);
        mStatusBarManager.disable(StatusBarManager.DISABLE_NONE);
        mAttached = false;
        releaseScreenshot();
    }

    public void releaseScreenshot() {
        mScreenshotBitmap = null;
    }

    /**
     * @return the current display rotation in degrees
     */
    static float getDegreesForRotation(int value) {
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
     * Returns a screenshot of the current display contents.
     * @param context Context.
     * @return
     */
    Bitmap createScreenshot() {
        // We need to orient the screenshot correctly (and the Surface api seems to
        // take screenshots only in the natural orientation of the device :!)

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

        Bitmap bitmap = Surface.screenshot((int) dims[0], (int) dims[1]);
        // Bail if we couldn't take the screenshot
        if (bitmap == null) {
            return null;
        }

        if (requiresRotation) {
            // Rotate the screenshot to the current orientation
            Bitmap ss = Bitmap.createBitmap(mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(ss);
            c.translate(ss.getWidth() / 2, ss.getHeight() / 2);
            c.rotate(360f - degrees);
            c.translate(-dims[0] / 2, -dims[1] / 2);
            c.drawBitmap(bitmap, 0, 0, null);

            bitmap = ss;
        }

        return bitmap;
    }

    @Override
    public void onAnimationStart(Animator animation) {  }

    @Override
    public void onAnimationEnd(Animator animation) {
        if (animation == mScaleUpAnimator || animation == mSuccessAnimatorSet ||
            animation == mSlideoutAnimator) {
            dismiss();
        }
    }

    @Override
    public void onAnimationCancel(Animator animation) {  }

    @Override
    public void onAnimationRepeat(Animator animation) {  }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (!mAttached) {
            return false;
        }
        // Ignore future touches
        mScreenshotView.setOnTouchListener(null);

        mPreAnimator.end();
        mCallback.onSendConfirmed();
        return true;
    }
}
