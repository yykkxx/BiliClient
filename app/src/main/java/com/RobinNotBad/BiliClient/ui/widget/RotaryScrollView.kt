package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 带表冠滚动支持的 ScrollView（详见 [RotaryScrollHelper]）。
 */
class RotaryScrollView : ScrollView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (handleRotary(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleRotary(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun handleRotary(event: MotionEvent): Boolean {
        if (!RotaryScrollHelper.isSupported() || !RotaryScrollHelper.isRotaryScroll(event)) return false
        val delta = RotaryScrollHelper.scrollDelta(
            context, PREF_ROTARY_SCROLL, event.getAxisValue(MotionEvent.AXIS_SCROLL)
        )
        if (delta == 0) return true
        smoothScrollBy(0, delta)
        return true
    }

    companion object {
        /** 表冠适配灵敏度（Scroll）设置键 */
        const val PREF_ROTARY_SCROLL = "ui_rotatory_scroll"
    }
}
