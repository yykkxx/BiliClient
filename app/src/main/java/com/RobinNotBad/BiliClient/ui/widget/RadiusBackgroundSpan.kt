package com.RobinNotBad.BiliClient.ui.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.style.ReplacementSpan
import androidx.annotation.NonNull
import androidx.annotation.Nullable

class RadiusBackgroundSpan : ReplacementSpan {
    private val margin: Int
    private val radius: Int
    private val textColor: Int
    private val bgColor: Int
    private val maxHeight: Int

    constructor(margin: Int, radius: Int, textColor: Int, bgColor: Int) {
        this.margin = margin
        this.radius = radius
        this.textColor = textColor
        this.bgColor = bgColor
        this.maxHeight = Integer.MAX_VALUE
    }

    constructor(margin: Int, radius: Int, textColor: Int, bgColor: Int, maxHeight: Int) {
        this.margin = margin
        this.radius = radius
        this.textColor = textColor
        this.bgColor = bgColor
        this.maxHeight = maxHeight
    }

    override fun getSize(@NonNull paint: Paint, text: CharSequence, start: Int, end: Int, @Nullable fm: Paint.FontMetricsInt?): Int {
        val newPaint = getCustomTextPaint(paint)
        return newPaint.measureText(text, start, end).toInt() + margin * 2
    }

    override fun draw(@NonNull canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int,
                      bottom: Int, @NonNull paint: Paint) {
        val newPaint = getCustomTextPaint(paint)

        val textWidth = newPaint.measureText(text, start, end).toInt()

        val rect = RectF()
        val newTop = if (bottom - top > maxHeight) Math.max(bottom - maxHeight, 0) else top
        rect.top = (newTop + margin).toFloat()
        rect.bottom = (bottom - margin).toFloat()
        rect.left = (x + margin).toInt().toFloat()
        rect.right = rect.left + textWidth + margin
        paint.color = bgColor
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paint)

        newPaint.color = textColor
        val fontMetrics = newPaint.fontMetrics
        val offsetX = ((rect.right - rect.left - textWidth) / 2).toInt() + margin
        val offsetY = ((y + fontMetrics.ascent + y + fontMetrics.descent) / 2 - (newTop + bottom) / 2).toInt()
        canvas.drawText(text, start, end, x + offsetX, (y - offsetY).toFloat(), newPaint)
    }

    private fun getCustomTextPaint(srcPaint: Paint): TextPaint {
        return TextPaint(srcPaint)
    }
}
