package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.annotation.Nullable

import com.RobinNotBad.BiliClient.util.ThemeUtil

class HighEnergyProgressBar : androidx.appcompat.widget.AppCompatSeekBar {
    private var highEnergyData: FloatArray? = null
    private var linePaint: Paint = Paint()
    private var fillPaint: Paint = Paint()
    private var stepSec = 10
    private var showHighEnergy = true

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        // 高能进度条跟随主题色调（#8）
        val accent = ThemeUtil.getColor()
        linePaint = Paint()
        linePaint.isAntiAlias = true
        linePaint.color = ThemeUtil.withAlpha(accent, 0xA8)
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 2f * context.resources.displayMetrics.density // 2dp线宽
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND

        fillPaint = Paint()
        fillPaint.isAntiAlias = true
        fillPaint.color = ThemeUtil.withAlpha(accent, 0x33)
        fillPaint.style = Paint.Style.FILL
    }

    /**
     * 设置高能数据
     *
     * @param data    高能数据数组，每个值表示该时间段的弹幕密度
     * @param stepSec 采样间隔（秒）
     */
    fun setHighEnergyData(data: FloatArray?, stepSec: Int) {
        this.highEnergyData = data
        this.stepSec = stepSec
        invalidate()
    }

    /**
     * 设置是否显示高能进度条
     */
    fun setShowHighEnergy(show: Boolean) {
        this.showHighEnergy = show
        invalidate()
    }

    /**
     * 清除高能数据
     */
    fun clearHighEnergyData() {
        this.highEnergyData = null
        invalidate()
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        val data = highEnergyData
        if (showHighEnergy && data != null && data.size > 0) {
            drawHighEnergy(canvas)
        }

        super.onDraw(canvas)
    }

    private fun drawHighEnergy(canvas: Canvas) {
        val data = highEnergyData ?: return
        val barWidth = getWidth() - getPaddingLeft() - getPaddingRight()
        val barHeight = getHeight() - getPaddingTop() - getPaddingBottom()
        val maxProgress = getMax()

        if (maxProgress <= 0 || barWidth <= 0 || barHeight <= 0) {
            return
        }

        var maxValue = 0f
        for (value in data) {
            if (value > maxValue) {
                maxValue = value
            }
        }

        if (maxValue <= 0) {
            return
        }

        val startX = getPaddingLeft().toFloat()
        val baselineY = (getPaddingTop() + barHeight).toFloat()
        val maxWaveHeight = barHeight * 0.8f

        val linePath = Path()
        val fillPath = Path()
        var pathStarted = false

        for (i in data.indices) {
            val time = i * stepSec * 1000
            if (time > maxProgress)
                break

            val barX = startX + time.toFloat() / maxProgress * barWidth

            var density = data[i] / maxValue
            density = Math.pow(density.toDouble(), 0.7).toFloat()
            val barY = baselineY - maxWaveHeight * density

            if (!pathStarted) {
                linePath.moveTo(barX, barY)
                fillPath.moveTo(barX, baselineY)
                fillPath.lineTo(barX, barY)
                pathStarted = true
            } else {
                linePath.lineTo(barX, barY)
                fillPath.lineTo(barX, barY)
            }
        }

        if (pathStarted) {
            val lastIndex = Math.min(data.size - 1, (maxProgress / (stepSec * 1000f)).toInt())
            val lastX = startX + Math.min(lastIndex * stepSec * 1000, maxProgress).toFloat() / maxProgress * barWidth
            fillPath.lineTo(lastX, baselineY)
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)
        }
    }
}
