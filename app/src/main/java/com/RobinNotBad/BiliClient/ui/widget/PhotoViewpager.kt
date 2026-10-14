package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

//copy自腾讯云（bushi
//用于解决缩放图片时触控越界导致崩溃的问题

class PhotoViewpager : ViewPager {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private var mIsDisallowIntercept = false

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        mIsDisallowIntercept = disallowIntercept
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return if (ev.pointerCount > 1 && mIsDisallowIntercept) {
            requestDisallowInterceptTouchEvent(false)
            val handled = super.dispatchTouchEvent(ev)
            requestDisallowInterceptTouchEvent(true)
            handled
        } else {
            super.dispatchTouchEvent(ev)
        }
    }

}
