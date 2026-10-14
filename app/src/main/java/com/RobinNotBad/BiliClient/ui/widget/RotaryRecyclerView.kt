package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.ViewConfigurationCompat
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            rotaryEnabled = SharedPreferencesUtil.getBoolean("ui_rotatory_enable", false)
            scrollMultiple = SharedPreferencesUtil.getFloat("ui_rotatory_recycler", 0f)
            
            if (rotaryEnabled && scrollMultiple > 0) {
                setOnGenericMotionListener { v, ev ->
                    if (ev.getAction() == MotionEvent.ACTION_SCROLL &&
                        ev.getSource() == InputDevice.SOURCE_ROTARY_ENCODER) {
                        val delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL) *
                            ViewConfigurationCompat.getScaledVerticalScrollFactor(
                                ViewConfiguration.get(getContext()), getContext()) * 2
                        smoothScrollBy(0, Math.round(delta * scrollMultiple))
                        requestFocus()
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
}
