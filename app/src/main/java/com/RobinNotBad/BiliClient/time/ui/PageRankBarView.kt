package com.RobinNotBad.BiliClient.time.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

import androidx.core.content.ContextCompat

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.time.TimeFormat

import java.util.Locale

/**
 * 「页面停留排行」横向条形图（自绘）。
 *
 * # 为什么用横向条而不是饼图
 * 用户要回答的是「时间都花在哪些页面」。页面名是中文短语（「视频详情」「评论区」「推荐」），
 * 横向排版可以把名字直接写在左边；饼图要拉引线 + 图例，小屏/圆屏上根本放不下。
 * 条形长度按**该页占本列表合计**的百分比算，右侧再补绝对时长：长度看相对、文字看绝对。
 *
 * # onDraw 零分配的落实方式（这一条最容易写成假的）
 * - 画笔全部构造时建好，颜色从资源取一次；
 * - 条形只复用 [rect] 一个 RectF；
 * - 页面名、数值文本、**以及超宽时的省略截断**都在 [setItems] / [onSizeChanged] 里算好，
 *   `onDraw` 只做 `drawText`。**不在 onDraw 里 measureText / substring** ——
 *   那些操作每次重绘都会分配对象，低端手表上是看得见的卡顿。
 *
 * # 圆屏适配
 * - 左右各留 [PAD_H_RATIO] 宽度，条形不压边；
 * - 行高按 sp 算（跟着字号走），整块高度 = 行数 × 行高，`wrap_content` 时不会挤成一条；
 * - 名字列宽占 [NAME_COLUMN_RATIO]，圆屏上仍能放 3~4 个汉字，超长名字截断加省略号。
 */
class PageRankBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 原始页面名（宽度变化时用来重新算截断） */
    private var rawNames: Array<String> = emptyArray()

    /** 实际绘制的页面名（已截断，onDraw 直接用） */
    private var names: Array<String> = emptyArray()

    /** 每行的「时长 · 百分比」文本（预格式化） */
    private var valueTexts: Array<String> = emptyArray()

    /** 每行比例（0~1），按列表最大值归一化 */
    private var ratios: FloatArray = FloatArray(0)

    // ---- 画笔：构造时一次性建好 ----
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.RIGHT
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    init {
        namePaint.textSize = sp2px(NAME_TEXT_SP)
        valuePaint.textSize = sp2px(VALUE_TEXT_SP)
        emptyPaint.textSize = sp2px(EMPTY_TEXT_SP)
        barPaint.color = ContextCompat.getColor(context, R.color.tm2_level_green)
        trackPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_bar_empty)
        namePaint.color = ContextCompat.getColor(context, R.color.tm2_text_secondary)
        valuePaint.color = ContextCompat.getColor(context, R.color.tm2_chart_axis)
        emptyPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_axis)
    }

    /**
     * 设置排行数据。
     *
     * 调用方应已按时长**降序**（[com.RobinNotBad.BiliClient.time.UsageLog.topPages] 已排好），
     * 这里不再排一遍：最多 8 条，多排一次纯浪费。
     *
     * @param source 页名 → 毫秒
     */
    fun setItems(source: List<Pair<String, Long>>) {
        val count = source.size
        rawNames = Array(count) { index -> source[index].first }
        val max = source.maxOfOrNull { it.second } ?: 0L
        // 用**最大值**归一化：最久的那页占满一整条，其余按比例缩，
        // 这样「第一名甩开第二名多少」一眼可见（用总和做分母会让所有条都很短）。
        ratios = FloatArray(count) { index ->
            if (max <= 0L) 0f else (source[index].second.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        }
        val total = source.sumOf { it.second }
        valueTexts = Array(count) { index -> buildValueText(source[index].second, total) }
        names = rawNames
        rebuildTruncatedNames()
        requestLayout()
        invalidate()
    }

    /** 数值文本：「12 分钟 · 43%」 */
    private fun buildValueText(ms: Long, totalMs: Long): String {
        val duration = TimeFormat.duration(ms)
        if (ms <= 0L || totalMs <= 0L) return duration
        val percent = ms.toFloat() / totalMs.toFloat() * 100f
        val percentText = if (percent < 1f) "<1%" else String.format(Locale.US, "%.0f%%", percent)
        return duration + " · " + percentText
    }

    /**
     * 按当前控件宽度重新算「省略后的页面名」。
     *
     * 只在 [setItems] 与 [onSizeChanged] 里调（**不在 onDraw**），
     * 这样 onDraw 拿到的永远是已经截好的字符串。
     */
    private fun rebuildTruncatedNames() {
        val maxWidth = width * NAME_COLUMN_RATIO - dp2px(NAME_GAP_DP)
        if (maxWidth <= 0f || rawNames.isEmpty()) {
            // 还没 measure 完：先直接显示原名，等 onSizeChanged 再截
            names = rawNames
            return
        }
        names = Array(rawNames.size) { index -> ellipsize(rawNames[index], maxWidth) }
    }

    /** 超宽则逐字回退加省略号（这里分配是允许的：不在每帧的 onDraw 里） */
    private fun ellipsize(text: String, maxWidth: Float): String {
        if (namePaint.measureText(text) <= maxWidth) return text
        var end = text.length - 1
        while (end > 0) {
            val candidate = text.substring(0, end) + "…"
            if (namePaint.measureText(candidate) <= maxWidth) return candidate
            end -= 1
        }
        return ""
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 宽度变了，名字的可用宽度也变了：重算一次截断（不在 onDraw 里做）
        rebuildTruncatedNames()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 高度随条目数走：每行固定行高；空态也留一行高度装提示
        val rows = if (rawNames.isEmpty()) 1 else rawNames.size
        val desired = (rows * rowHeight()).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desired, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rawNames.isEmpty()) {
            canvas.drawText(EMPTY_HINT, width / 2f, height * 0.5f + emptyPaint.textSize / 3f, emptyPaint)
            return
        }

        val rowH = rowHeight()
        val padH = width * PAD_H_RATIO
        val nameWidth = width * NAME_COLUMN_RATIO
        val valueWidth = width * VALUE_COLUMN_RATIO
        val trackLeft = padH + nameWidth
        val trackRight = width - padH - valueWidth
        if (trackRight <= trackLeft) return

        for (index in names.indices) {
            val centerY = rowH * index + rowH * 0.5f
            // 文本基线：让文字在行内垂直居中（用字号/3 近似，避免每帧 FontMetrics 分配）
            val baseline = centerY + namePaint.textSize / 3f

            canvas.drawText(names.getOrElse(index) { "" }, padH, baseline, namePaint)

            val barTop = centerY - BAR_HEIGHT / 2f
            val barBottom = centerY + BAR_HEIGHT / 2f
            rect.set(trackLeft, barTop, trackRight, barBottom)
            canvas.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, trackPaint)

            val ratio = ratios.getOrElse(index) { 0f }.coerceIn(0f, 1f)
            if (ratio > 0f) {
                rect.set(trackLeft, barTop, trackLeft + (trackRight - trackLeft) * ratio, barBottom)
                canvas.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, barPaint)
            }

            canvas.drawText(valueTexts.getOrElse(index) { "" }, width - padH, baseline, valuePaint)
        }
    }

    private fun rowHeight(): Float = sp2px(ROW_HEIGHT_SP)

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    private companion object {
        /** 行高（sp） */
        const val ROW_HEIGHT_SP = 26f

        /** 页面名字号 */
        const val NAME_TEXT_SP = 11f

        /** 右侧数值字号 */
        const val VALUE_TEXT_SP = 10f

        /** 空态字号 */
        const val EMPTY_TEXT_SP = 11f

        /** 名字列宽占整体宽度的比例 */
        const val NAME_COLUMN_RATIO = 0.32f

        /** 右侧数值列宽占整体宽度的比例（要能放下「1 小时 5 分钟 · 43%」） */
        const val VALUE_COLUMN_RATIO = 0.32f

        /** 左右留白占宽度的比例（圆屏不压边） */
        const val PAD_H_RATIO = 0.02f

        /** 名字与条形之间的间距（dp） */
        const val NAME_GAP_DP = 4f

        /** 条形高度与圆角（px） */
        const val BAR_HEIGHT = 10f
        const val BAR_RADIUS = 5f

        /** 空态文案（不走 strings.xml：本 View 不该依赖页面的资源 id） */
        const val EMPTY_HINT = "今天还没有页面停留记录"
    }
}
