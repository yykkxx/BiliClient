/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.RobinNotBad.BiliClient.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.widget.TextView
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat

/**
 * 为了兼容安卓4.0，必须自己实现TextClock
 * 参考了安卓官方TextClock和DigitalClock的写法
 */
@SuppressLint("AppCompatCustomView")
class TextClock : TextView {

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        init()
    }

    private var stopped = false

    fun init() {
        setTypeface(Typeface.DEFAULT_BOLD)
    }

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            removeCallbacks(this)
            if (stopped) return

            val now = System.currentTimeMillis()
            setText(dateFormat.format(now))

            val next = 60000 - now % 60000
            postDelayed(this, next)
            //Log.i("debug-clock-tick","now:" + SystemClock.uptimeMillis() + " | next:" + next);
            //再次修改，原先的handler容易被系统杀…
        }
    }

    fun startTick() {
        stopped = false
        ticker.run()
    }

    fun stopTick() {
        stopped = true
        removeCallbacks(ticker)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTick()
    }

    override fun onScreenStateChanged(screenState: Int) {
        super.onScreenStateChanged(screenState)

        if (screenState == SCREEN_STATE_ON) startTick()
        else stopTick()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)

        if (isVisible) startTick()
        else stopTick()
    }

    companion object {
        @SuppressLint("SimpleDateFormat")
        private val dateFormat = SimpleDateFormat("HH:mm")
    }
}
