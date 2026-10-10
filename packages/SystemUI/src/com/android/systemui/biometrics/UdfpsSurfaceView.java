/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntrySection;
import com.android.systemui.res.R;

/**
 * Surface View for providing the Global High-Brightness Mode (GHBM) illumination for UDFPS.
 */
public class UdfpsSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "UdfpsSurfaceView";

    /**
     * A30s: per-side inset (fraction of the sensor rect) applied to the green illumination dot
     * so it matches the lock-screen button. Derived from the same knob that sizes the button,
     * so the two cannot drift apart.
     */
    private static final float ILLUMINATION_INSET_FRACTION =
            (1f - DefaultDeviceEntrySection.UDFPS_ICON_SIZE_FACTOR) / 2f;

    /**
     * Notifies {@link UdfpsView} when to enable GHBM illumination.
     */
    public interface GhbmIlluminationListener {
        /**
         * @param surface the surface for which GHBM should be enabled.
         * @param onDisplayConfigured a runnable that should be run after GHBM is enabled.
         */
        void enableGhbm(@NonNull Surface surface, @Nullable Runnable onDisplayConfigured);
    }

    @NonNull private final SurfaceHolder mHolder;
    @NonNull private final Paint mSensorPaint;

    @Nullable private GhbmIlluminationListener mGhbmIlluminationListener;
    @Nullable private Runnable mOnDisplayConfigured;
    boolean mAwaitingSurfaceToStartIllumination;
    boolean mHasValidSurface;

    private Drawable mUdfpsIconPressed;

    public UdfpsSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Make this SurfaceView draw on top of everything else in this window. This allows us to
        // 1) Always show the HBM circle on top of everything else, and
        // 2) Properly composite this view with any other animations in the same window no matter
        //    what contents are added in which order to this view hierarchy.
        setZOrderOnTop(true);

        mHolder = getHolder();
        mHolder.addCallback(this);
        mHolder.setFormat(PixelFormat.RGBA_8888);

        mSensorPaint = new Paint(0 /* flags */);
        mSensorPaint.setAntiAlias(true);
        mSensorPaint.setColor(context.getColor(R.color.config_udfpsColor));
        mSensorPaint.setStyle(Paint.Style.FILL);

        mUdfpsIconPressed = context.getDrawable(R.drawable.udfps_icon_pressed);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        mHasValidSurface = true;
        if (mAwaitingSurfaceToStartIllumination) {
            doIlluminate(mOnDisplayConfigured);
            mOnDisplayConfigured = null;
            mAwaitingSurfaceToStartIllumination = false;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Unused.
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        mHasValidSurface = false;
    }

    public void setGhbmIlluminationListener(@Nullable GhbmIlluminationListener listener) {
        mGhbmIlluminationListener = listener;
    }

    /**
     * Note: there is no corresponding method to stop GHBM illumination. It is expected that
     * {@link UdfpsView} will hide this view, which would destroy the surface and remove the
     * illumination dot.
     */
    public void startGhbmIllumination(@Nullable Runnable onDisplayConfigured) {
        if (mGhbmIlluminationListener == null) {
            Log.e(TAG, "startIllumination | mGhbmIlluminationListener is null");
            return;
        }

        if (mHasValidSurface) {
            doIlluminate(onDisplayConfigured);
        } else {
            mAwaitingSurfaceToStartIllumination = true;
            mOnDisplayConfigured = onDisplayConfigured;
        }
    }

    private void doIlluminate(@Nullable Runnable onDisplayConfigured) {
        if (mGhbmIlluminationListener == null) {
            Log.e(TAG, "doIlluminate | mGhbmIlluminationListener is null");
            return;
        }

        mGhbmIlluminationListener.enableGhbm(mHolder.getSurface(), onDisplayConfigured);
    }

    /**
     * Immediately draws the illumination dot on this SurfaceView's surface.
     */
    public void drawIlluminationDot(@NonNull RectF sensorRect) {
        if (!mHasValidSurface) {
            Log.e(TAG, "drawIlluminationDot | the surface is destroyed or was never created.");
            return;
        }
        // A30s: the dot is handed the raw sensor bounds, so it stayed sensor-sized even after
        // the button was halved. Shrink it to the button; a copy is used so the caller's rect is
        // never mutated. Both the oval and the pressed icon shrink so they stay aligned.
        final RectF dotRect = new RectF(sensorRect);
        final float inset = dotRect.width() * ILLUMINATION_INSET_FRACTION;
        dotRect.inset(inset, inset);
        Canvas canvas = null;
        try {
            canvas = mHolder.lockCanvas();
            mUdfpsIconPressed.setBounds(
                    Math.round(dotRect.left),
                    Math.round(dotRect.top),
                    Math.round(dotRect.right),
                    Math.round(dotRect.bottom)
            );
            mUdfpsIconPressed.draw(canvas);
            canvas.drawOval(dotRect, mSensorPaint);
        } finally {
            // Make sure the surface is never left in a bad state.
            if (canvas != null) {
                mHolder.unlockCanvasAndPost(canvas);
            }
        }
    }
}
