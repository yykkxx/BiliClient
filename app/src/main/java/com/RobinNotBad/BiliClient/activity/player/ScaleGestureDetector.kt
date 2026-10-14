/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.RobinNotBad.BiliClient.activity.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration


class ScaleGestureDetector(context: Context, listener: OnScaleGestureListener, handler: Handler? = null) {

    interface OnScaleGestureListener {
        fun onScale(detector: ScaleGestureDetector): Boolean

        fun onScaleBegin(detector: ScaleGestureDetector): Boolean

        fun onScaleEnd(detector: ScaleGestureDetector)
    }

    open class SimpleOnScaleGestureListener : OnScaleGestureListener {

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return false
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
        }
    }

    private val mContext: Context = context
    private val mListener: OnScaleGestureListener = listener

    @JvmField
    var mFocusX: Float = 0f

    @JvmField
    var mFocusY: Float = 0f

    private var mQuickScaleEnabled: Boolean = false

    @JvmField
    var mCurrSpan: Float = 0f

    @JvmField
    var mPrevSpan: Float = 0f

    @JvmField
    var mInitialSpan: Float = 0f

    @JvmField
    var mCurrSpanX: Float = 0f

    @JvmField
    var mCurrSpanY: Float = 0f

    @JvmField
    var mPrevSpanX: Float = 0f

    @JvmField
    var mPrevSpanY: Float = 0f

    @JvmField
    var mCurrTime: Long = 0

    @JvmField
    var mPrevTime: Long = 0

    @JvmField
    var mInProgress: Boolean = false

    @JvmField
    val mSpanSlop: Int

    @JvmField
    val mMinSpan: Int

    private val mHandler: Handler? = handler

    private var mAnchoredScaleStartX: Float = 0f
    private var mAnchoredScaleStartY: Float = 0f
    private var mAnchoredScaleMode: Int = ANCHORED_SCALE_MODE_NONE

    companion object {
        private const val SCALE_FACTOR = .5f
        private const val ANCHORED_SCALE_MODE_NONE = 0
        private const val ANCHORED_SCALE_MODE_DOUBLE_TAP = 1
        private const val ANCHORED_SCALE_MODE_STYLUS = 2
    }

    private var mGestureDetector: GestureDetector? = null

    private var mEventBeforeOrAboveStartingGestureEvent: Boolean = false

    init {
        val viewConfiguration = ViewConfiguration.get(context)
        mSpanSlop = viewConfiguration.scaledTouchSlop * 2
        mMinSpan = 0

        val targetSdkVersion = context.applicationInfo.targetSdkVersion
        if (targetSdkVersion > Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mQuickScaleEnabled = true
        }
    }

    fun onTouchEvent(event: MotionEvent) {

        mCurrTime = event.eventTime.toLong()

        val action = event.actionMasked

        // Forward the event to check for double tap gesture
        if (mQuickScaleEnabled) {
            mGestureDetector!!.onTouchEvent(event)
        }

        val count = event.pointerCount

        val anchoredScaleCancelled = mAnchoredScaleMode == ANCHORED_SCALE_MODE_STYLUS
        val streamComplete = action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_CANCEL || anchoredScaleCancelled

        if (action == MotionEvent.ACTION_DOWN || streamComplete) {
            // Reset any scale in progress with the listener.
            // If it's an ACTION_DOWN we're beginning a new event stream.
            // This means the app probably didn't give us all the events. Shame on it.
            if (mInProgress) {
                mListener.onScaleEnd(this)
                mInProgress = false
                mInitialSpan = 0f
                mAnchoredScaleMode = ANCHORED_SCALE_MODE_NONE
            } else if (inAnchoredScaleMode() && streamComplete) {
                mInitialSpan = 0f
                mAnchoredScaleMode = ANCHORED_SCALE_MODE_NONE
            }

            if (streamComplete) {
                return
            }
        }

        val configChanged = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_POINTER_DOWN

        val pointerUp = action == MotionEvent.ACTION_POINTER_UP
        val skipIndex = if (pointerUp) event.actionIndex else -1

        // Determine focal point
        var sumX: Float = 0f
        var sumY: Float = 0f
        val div = if (pointerUp) count - 1 else count
        val focusX: Float
        val focusY: Float
        if (inAnchoredScaleMode()) {
            // In anchored scale mode, the focal pt is always where the double tap
            // or button down gesture started
            focusX = mAnchoredScaleStartX
            focusY = mAnchoredScaleStartY
            mEventBeforeOrAboveStartingGestureEvent = event.y < focusY
        } else {
            for (i in 0 until count) {
                if (skipIndex == i) continue
                sumX += event.getX(i)
                sumY += event.getY(i)
            }

            focusX = sumX / div
            focusY = sumY / div
        }

        // Determine average deviation from focal point
        var devSumX: Float = 0f
        var devSumY: Float = 0f
        for (i in 0 until count) {
            if (skipIndex == i) continue

            // Convert the resulting diameter into a radius.
            devSumX += Math.abs(event.getX(i) - focusX)
            devSumY += Math.abs(event.getY(i) - focusY)
        }
        val devX = devSumX / div
        val devY = devSumY / div

        // Span is the average distance between touch points through the focal point;
        // i.e. the diameter of the circle with a radius of the average deviation from
        // the focal point.
        val spanX = devX * 2
        val spanY = devY * 2
        val span: Float
        if (inAnchoredScaleMode()) {
            span = spanY
        } else {
            span = Math.hypot(spanX.toDouble(), spanY.toDouble()).toFloat()
        }

        // Dispatch begin/end events as needed.
        // If the configuration changes, notify the app to reset its current state by beginning
        // a fresh scale event stream.
        val wasInProgress = mInProgress
        mFocusX = focusX
        mFocusY = focusY
        if (!inAnchoredScaleMode() && mInProgress && (span < mMinSpan || configChanged)) {
            mListener.onScaleEnd(this)
            mInProgress = false
            mInitialSpan = span
        }
        if (configChanged) {
            mPrevSpanX = spanX.also { mCurrSpanX = it }
            mPrevSpanY = spanY.also { mCurrSpanY = it }
            mPrevSpan = span.also { mCurrSpan = it }
            mInitialSpan = mPrevSpan
        }

        val minSpan = if (inAnchoredScaleMode()) mSpanSlop else mMinSpan
        if (!mInProgress && span >= minSpan &&
                (wasInProgress || Math.abs(span - mInitialSpan) > mSpanSlop)) {
            mPrevSpanX = spanX.also { mCurrSpanX = it }
            mPrevSpanY = spanY.also { mCurrSpanY = it }
            mPrevSpan = span.also { mCurrSpan = it }
            mPrevTime = mCurrTime
            mInProgress = mListener.onScaleBegin(this)
        }

        // Handle motion; focal point and span/scale factor are changing.
        if (action == MotionEvent.ACTION_MOVE) {
            mCurrSpanX = spanX
            mCurrSpanY = spanY
            mCurrSpan = span

            var updatePrev = true

            if (mInProgress) {
                updatePrev = mListener.onScale(this)
            }

            if (updatePrev) {
                mPrevSpanX = mCurrSpanX
                mPrevSpanY = mCurrSpanY
                mPrevSpan = mCurrSpan
                mPrevTime = mCurrTime
            }
        }

    }

    private fun inAnchoredScaleMode(): Boolean {
        return mAnchoredScaleMode != ANCHORED_SCALE_MODE_NONE
    }

    var isQuickScaleEnabled: Boolean
        get() = mQuickScaleEnabled
        set(scales) {
            mQuickScaleEnabled = scales
            if (mQuickScaleEnabled && mGestureDetector == null) {
                val gestureListener: GestureDetector.SimpleOnGestureListener = object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        // Double tap: start watching for a swipe
                        mAnchoredScaleStartX = e.x
                        mAnchoredScaleStartY = e.y
                        mAnchoredScaleMode = ANCHORED_SCALE_MODE_DOUBLE_TAP
                        return true
                    }
                }
                mGestureDetector = GestureDetector(mContext, gestureListener, mHandler!!)
            }
        }

    val scaleFactor: Float
        get() {
            if (inAnchoredScaleMode()) {
                val scaleUp =
                        (mEventBeforeOrAboveStartingGestureEvent && (mCurrSpan < mPrevSpan)) ||
                                (!mEventBeforeOrAboveStartingGestureEvent && (mCurrSpan > mPrevSpan))
                val spanDiff = (Math.abs(1 - (mCurrSpan / mPrevSpan)) * SCALE_FACTOR).toFloat()
                return if (mPrevSpan <= mSpanSlop) 1f else if (scaleUp) (1 + spanDiff) else (1 - spanDiff)
            }
            return if (mPrevSpan > 0) mCurrSpan / mPrevSpan else 1f
        }

}
