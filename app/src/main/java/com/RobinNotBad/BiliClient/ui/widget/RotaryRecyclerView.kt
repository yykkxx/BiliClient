package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * 带表冠滚动支持的 RecyclerView。
 *
 * 列表本身已能消费表冠滚动，这里在统一入口再做一次兜底处理，
 * 主要是让灵敏度设置（Recycler）在所有布局管理器下都稳定生效。
 */
class RotaryRecyclerView : RecyclerView {
    private var scrollMultiple = 0f
    private var rotaryEnabled = false

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initRotaryScroll()
    }

    private fun initRotaryScroll() {
        if (!RotaryScrollHelper.isSupported()) return
        rotaryEnabled = com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
            .getBoolean("ui_rotatory_enable", false)
        scrollMultiple = com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
            .getFloat(PREF_ROTARY_RECYCLER, 0f)

        if (rotaryEnabled && scrollMultiple > 0) {
            setOnGenericMotionListener { _, ev ->
                if (RotaryScrollHelper.isRotaryScroll(ev)) {
                    val delta = RotaryScrollHelper.scrollDelta(
                        context, PREF_ROTARY_RECYCLER, ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                    )
                    if (delta != 0) {
                        smoothScrollBy(0, delta)
                        requestFocus()
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    companion object {
        /** 表冠适配灵敏度（Recycler）设置键 */
        const val PREF_ROTARY_RECYCLER = "ui_rotatory_recycler"
    }
}
