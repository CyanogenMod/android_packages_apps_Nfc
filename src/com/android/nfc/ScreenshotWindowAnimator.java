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
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/**
 * TODO:
 *   - Performance when over gl surfaces? Ie. Gallery
 *   - what do we say in the Toast? Which icon do we get if the user uses another
 *     type of gallery?
 */
public class ScreenshotWindowAnimator implements AnimatorUpdateListener, Handler.Callback {
    private static final String TAG = "ScreenshotWindowAnimator";

    private static final int ANIMATION_DURATION_MS = 7500;
    private static final float SCREENSHOT_SCALE = 0.5f;

    private static final int MSG_START_ANIMATION = 1;
    private static final int MSG_STOP_ANIMATION = 2;

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
//    Animator mScreenshotAnimator;
    Listener mListener;
    ValueAnimator mAnimator;
    boolean mAttached = false;
    Handler mHandler;

    class Listener extends AnimatorListenerAdapter {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (mAttached) {
                mWindowManager.removeView(mScreenshotLayout);
                mAttached = false;
            }
        }
    }

    /**
     * @param context everything needs a context :(
     */
    public ScreenshotWindowAnimator(Context context) {
        mContext = context;
        mHandler = new Handler(this);
        mLayoutInflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        mListener = new Listener();

        // Create the animation
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.setDuration(ANIMATION_DURATION_MS);
        anim.addUpdateListener(this);
        anim.addListener(mListener);
        mAnimator = anim;
        
        // Inflate the screenshot layout
        mDisplayMetrics = new DisplayMetrics();
        mDisplayMatrix = new Matrix();
        mScreenshotLayout = mLayoutInflater.inflate(R.layout.screenshot, null);
        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.screenshot);
        mScreenshotLayout.setFocusable(true);
/* this doesn't work on the TYPE_SYSTEM_OVERLAY layer
        mScreenshotLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Intercept and ignore all touch events
                return true;
            }
        });
*/

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
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
        // We need to orient the screenshot correctly (and the Surface api seems to take screenshots
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

        // Bail if we couldn't take the screenshot
        if (mScreenBitmap == null) {
            Log.e(TAG, "couldn't get screenshot");
            return;
        }

        // Start the post-screenshot animation
        mScreenshotView.setImageBitmap(mScreenBitmap);
        mScreenshotLayout.requestFocus();

        // Setup the animation with the screenshot just taken
        if (mAnimator != null) {
            mAnimator.end();
        }

        // Add the view for the animation
        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        mAttached = true;
        mHandler.sendEmptyMessage(MSG_START_ANIMATION);
    }

    /**
     * Stops the currently playing animation.
     *
     * Must be called from the UI thread.
     */
    public void stop() {
        mHandler.sendEmptyMessage(MSG_STOP_ANIMATION);
    }

    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
        float t = ((Float) animation.getAnimatedValue()).floatValue();
        float scaleT = SCREENSHOT_SCALE + (1f - t) * SCREENSHOT_SCALE;
        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_START_ANIMATION: {
                mAnimator.start();
                break;
            }

            case MSG_STOP_ANIMATION: {
                if (mAnimator != null) {
                    mAnimator.cancel();
                }
                break;
            }
        }
        return true;
    }
}