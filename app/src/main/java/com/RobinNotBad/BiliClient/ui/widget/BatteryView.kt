package com.RobinNotBad.BiliClient.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Nullable

class BatteryView : View {
    private var mPower = 100
    private var mCharging = false

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val power_percent = mPower / 100.0f

        val padding = (width * 0.05f).toInt()

        val battery_width = (width * 0.9f).toInt()
        val battery_height = height - padding

        val stroke_width = (width * 0.05f).toInt()

        val head_width = (width * 0.08f).toInt()
        val head_height = (height * 0.4f).toInt()
        val head_top = (battery_height - head_height + padding) / 2
        val head_right = battery_width + head_width
        val head_bottom = head_top + head_height

        val inside_padding = (width * 0.08f).toInt()

        val fill_left = padding + inside_padding
        val fill_top = padding + inside_padding
        val fill_right = ((battery_width - inside_padding) * power_percent).toInt()
        val fill_bottom = battery_height - inside_padding

        //边框
        val paint = Paint()
        paint.color = Color.WHITE
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke_width.toFloat()

        val stroke = Rect(padding, padding,
                battery_width, battery_height)
        canvas.drawRect(stroke, paint)

        //电池头
        paint.style = Paint.Style.FILL
        val head = Rect(battery_width, head_top, head_right, head_bottom)
        canvas.drawRect(head, paint)

        //画电量
        paint.strokeWidth = 0f
        paint.color = if (mCharging) Color.GREEN
        else (if (mPower <= 20) Color.RED else Color.WHITE)

        if (power_percent != 0f) {
            val fill = Rect(fill_left, fill_top, fill_right, fill_bottom)
            canvas.drawRect(fill, paint)
        }
    }

    fun setPower(power: Int) {
        mPower = power
        if (mPower < 0) {
            mPower = 0
        }
        invalidate()
    }

    fun setCharging(charging: Boolean) {
        mCharging = charging
        invalidate()
    }
}
