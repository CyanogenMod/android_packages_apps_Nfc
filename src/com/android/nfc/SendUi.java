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
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * All methods must be called on UI thread
 */
public class SendUi implements Animator.AnimatorListener {

    static final float[] PRE_SCREENSHOT_SCALE = {1.0f, 0.6f};
    static final int PRE_DURATION_MS = 50;

    static final float[] POST_SCREENSHOT_SCALE = {0.6f, 0.0f};
    static final int POST_DURATION_MS = 200;

    // all members are only used on UI thread
    final WindowManager mWindowManager;
    final Context mContext;
    final Display mDisplay;
    final DisplayMetrics mDisplayMetrics;
    final Matrix mDisplayMatrix;
    final WindowManager.LayoutParams mWindowLayoutParams;
    final LayoutInflater mLayoutInflater;
    final View mScreenshotLayout;
    final ImageView mScreenshotView;
    final Callback mCallback;
    final ObjectAnimator mPreAnimator;
    final ObjectAnimator mPostAnimator;

    Bitmap mScreenshotBitmap;
    boolean mAttached;

    interface Callback {
        public void onPreFinished();
    }

    public SendUi(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;

        mDisplayMetrics = new DisplayMetrics();
        mDisplayMatrix = new Matrix();
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();

        mLayoutInflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mScreenshotLayout = mLayoutInflater.inflate(R.layout.screenshot, null);
        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.screenshot);
        mScreenshotLayout.setFocusable(true);

        mWindowLayoutParams = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0, 0,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED_SYSTEM
                | WindowManager.LayoutParams.FLAG_KEEP_SURFACE_WHILE_ANIMATING
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                PixelFormat.OPAQUE);
        mWindowLayoutParams.token = new Binder();

        PropertyValuesHolder preX = PropertyValuesHolder.ofFloat("scaleX", PRE_SCREENSHOT_SCALE);
        PropertyValuesHolder preY = PropertyValuesHolder.ofFloat("scaleY", PRE_SCREENSHOT_SCALE);
        mPreAnimator = ObjectAnimator.ofPropertyValuesHolder(mScreenshotView, preX, preY);
        mPreAnimator.setInterpolator(null);  // linear
        mPreAnimator.setDuration(PRE_DURATION_MS);
        mPreAnimator.addListener(this);

        PropertyValuesHolder postX = PropertyValuesHolder.ofFloat("scaleX", POST_SCREENSHOT_SCALE);
        PropertyValuesHolder postY = PropertyValuesHolder.ofFloat("scaleY", POST_SCREENSHOT_SCALE);
        mPostAnimator = ObjectAnimator.ofPropertyValuesHolder(mScreenshotView, postX, postY);
        mPostAnimator.setInterpolator(null);  // linear
        mPostAnimator.setDuration(POST_DURATION_MS);
        mPostAnimator.addListener(this);

        mAttached = false;
    }

    public void takeScreenshot() {
        mScreenshotBitmap = createScreenshot();
    }

    /** Show pre-send animation, calls onPreFinished() when complete */
    public void showPreSend() {
        if (mScreenshotBitmap == null || mAttached) {
            return;
        }
        mScreenshotView.setImageBitmap(mScreenshotBitmap);
        mScreenshotLayout.requestFocus();
        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        mAttached = true;
        mPreAnimator.start();

        //TODO: Lock rotation
//        final int orientation = getResources().getConfiguration().orientation;
//        setRequestedOrientation(orientation);
    }

    /** Show post-send animation */
    public void showPostSend() {
        if (!mAttached) {
            return;
        }
        mPostAnimator.start();
    }

    public void dismiss() {
        if (!mAttached) {
            return;
        }
        mPreAnimator.cancel();
        mPostAnimator.cancel();
        mWindowManager.removeView(mScreenshotLayout);
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
        if (animation == mPreAnimator) {
            mCallback.onPreFinished();
        } else if (animation == mPostAnimator) {
            dismiss();
        }
    }

    @Override
    public void onAnimationCancel(Animator animation) {  }

    @Override
    public void onAnimationRepeat(Animator animation) {  }
}
