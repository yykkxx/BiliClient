package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

/**
 * 带表冠滚动支持的 NestedScrollView（详见 [RotaryScrollHelper]）。
 *
 * 关键点：在 dispatchTouchEvent 阶段就消费表冠事件，让它不再被系统当成
 * 方向键去做焦点搜索，从而避免“划一下直接跳到底部”。
 */
class RotaryNestedScrollView : NestedScrollView {
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

    /** 消费表冠滚动事件并做平滑滚动；非表冠事件返回 false 交回系统。 */
    private fun handleRotary(event: MotionEvent): Boolean {
        if (!RotaryScrollHelper.isSupported() || !RotaryScrollHelper.isRotaryScroll(event)) return false
        val delta = RotaryScrollHelper.scrollDelta(
            context, PREF_ROTARY_SCROLL, event.getAxisValue(MotionEvent.AXIS_SCROLL)
        )
        if (delta == 0) return true // 消费掉但不动，避免继续走焦点搜索
        smoothScrollBy(0, delta)
        return true
    }

    companion object {
        /** 表冠适配灵敏度（Scroll）设置键 */
        const val PREF_ROTARY_SCROLL = "ui_rotatory_scroll"
    }
}
