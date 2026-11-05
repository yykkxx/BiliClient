package com.RobinNotBad.BiliClient.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

import com.RobinNotBad.BiliClient.util.DayUsage
import com.RobinNotBad.BiliClient.util.UsageColorUtil
import com.RobinNotBad.BiliClient.util.UsageLimitManager

/**
 * 最近 7 日使用时长的柱状图（v5.2.0 需求 2 的「七日内使用时长柱状图」）。
 *
 * ## 为什么自绘而不引图表库
 * 只需要「7 根柱子 + 轴标签 + 今天高亮」这一件事。第三方图表库（MPAndroidChart 等）
 * 动辄几百 KB、还带来自己的主题/字体/动画体系，与这个 App 的小屏 + 圆屏适配、
 * 以及「包体积已经很紧张」的现实都不匹配。自绘 200 行就能覆盖需求。
 *
 * ## 性能约定（写进代码是为了以后改的人不踩）
 * - [barPaint]、[textPaint]、[todayTrackPaint] 等**只在构造/属性变更时创建**，
 *   `onDraw` 里不 `new` 任何对象、不拼装字符串（轴标签在 [setDays] 时预先算好）；
 * - 柱高用 [ValueAnimator] 从 0 长到目标值，只调 [invalidate]，不重建 Path；
 * - 每个标签都缓存在 `labelWidths`，避免每帧 measure。
 *
 * ## 颜色规则
 * 今天那根柱子按 [UsageColorUtil] 的三档色（绿/黄/红），历史日期用中性灰蓝，
 * 这样「今天用得是不是超标」一眼可见，历史柱又不抢视觉。
 */
class UsageChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 7 天数据（下标 0 = 6 天前，6 = 今天） */
    private var days: List<DayUsage> = emptyList()

    /** 柱子目标高度比例（0~1），已考虑最大值归一化 */
    private var targetRatios: FloatArray = FloatArray(0)

    /** 动画进度（0~1），起始时柱高为 0 */
    private var animProgress = 1f

    private var animator: ValueAnimator? = null

    // ---- 画笔：构造时一次性建好，onDraw 里不新建 ----
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_HISTORY_BAR
    }

    private val todayBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = UsageColorUtil.GREEN
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEXT
        textSize = sp2px(11f)
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEXT_STRONG
        textSize = sp2px(10f)
        textAlign = Paint.Align.CENTER
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TRACK
    }

    private val rect = RectF()

    /** 每根柱子下要显示的文本（预计算，onDraw 直接用） */
    private var barLabels: Array<String> = emptyArray()

    /** 每根柱子上要显示的时长文本（预计算） */
    private var barValues: Array<String> = emptyArray()

    init {
        // 高度由布局决定；这里给一个可用的默认最小高度，避免 wrap_content 时量成 0
        minimumHeight = (dp2px(120f)).toInt()
    }

    /**
     * 设置 7 天数据并启动生长动画。
     *
     * @param source 长度应为 [UsageStatsManager.RECENT_DAYS]（7）；不足/超出都按实际长度画，
     *   这样将来改成 10 天也不用改这里。
     */
    fun setDays(source: List<DayUsage>) {
        days = source
        val maxMs = source.maxOfOrNull { it.ms } ?: 0L
        // 归一化：全 0 时全部画成 0 高（不画假数据），否则按最大值取比例
        targetRatios = FloatArray(source.size) { index ->
            if (maxMs <= 0L) 0f else (source[index].ms.toFloat() / maxMs.toFloat())
        }
        barLabels = Array(source.size) { index -> source[index].label }
        barValues = Array(source.size) { index ->
            if (source[index].ms <= 0L) "—" else UsageLimitManager.formatUsed(source[index].ms)
        }
        // 今天（最后一根）的柱子颜色跟着今日用量走
        val todayMs = source.lastOrNull()?.ms ?: 0L
        todayBarPaint.color = UsageColorUtil.colorForUsed(todayMs)
        startGrowAnimation()
    }

    /** 200ms 生长动画：只改进度重绘，不动数据 */
    private fun startGrowAnimation() {
        animator?.cancel()
        animProgress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GROW_DURATION_MS
            addUpdateListener { animation ->
                animProgress = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return

        val paddingLeft = dp2px(4f)
        val paddingRight = dp2px(4f)
        val paddingTop = dp2px(18f)      // 留给柱顶时长文字
        val paddingBottom = dp2px(20f)   // 留给横轴标签
        val usableWidth = width - paddingLeft - paddingRight
        val usableHeight = height - paddingTop - paddingBottom
        if (usableWidth <= 0f || usableHeight <= 0f) return

        val count = days.size
        // 每根柱占的槽宽；柱宽取槽宽的 55%，两侧留白让 7 根柱子不粘在一起
        val slotWidth = usableWidth / count
        val barWidth = slotWidth * BAR_WIDTH_RATIO

        val baseline = paddingTop + usableHeight
        // 基线（横向参考线）
        canvas.drawRect(paddingLeft, baseline - 1f, paddingLeft + usableWidth, baseline, trackPaint)

        for (index in 0 until count) {
            val ratio = targetRatios.getOrElse(index) { 0f } * animProgress
            val barHeight = usableHeight * ratio
            val left = paddingLeft + slotWidth * index + (slotWidth - barWidth) / 2f
            val right = left + barWidth
            val top = baseline - barHeight

            val paint = if (index == count - 1) todayBarPaint else barPaint
            if (barHeight > 0f) {
                // 圆角柱：只圆顶部。RectF 复用，避免每帧分配
                rect.set(left, top, right, baseline)
                canvas.drawRoundRect(rect, BAR_CORNER_RADIUS, BAR_CORNER_RADIUS, paint)
                // 圆角会把底部也倒圆；把底部那一小块用直角补回去，视觉上柱子「插在地上」
                val corner = BAR_CORNER_RADIUS
                if (barHeight > corner) {
                    canvas.drawRect(left, baseline - corner, right, baseline, paint)
                }
            }

            // 横轴标签（预计算，无字符串拼接）
            barLabels.getOrNull(index)?.let { label ->
                canvas.drawText(label, paddingLeft + slotWidth * index + slotWidth / 2f,
                    baseline + dp2px(14f), textPaint)
            }
            // 柱顶时长：柱太矮时压到柱上方一点，避免和标签重叠
            if (barHeight > 0f) {
                barValues.getOrNull(index)?.let { value ->
                    canvas.drawText(value, paddingLeft + slotWidth * index + slotWidth / 2f,
                        (top - dp2px(4f)).coerceAtLeast(paddingTop + sp2px(10f)), valuePaint)
                }
            }
        }
    }

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    private companion object {
        /** 历史柱颜色：中性灰蓝，不与今天的语义色抢眼 */
        const val COLOR_HISTORY_BAR = 0xFF5A6478.toInt()

        /** 文字（轴标签） */
        const val COLOR_TEXT = 0xFF9AA0AE.toInt()

        /** 柱顶时长文字（略亮） */
        const val COLOR_TEXT_STRONG = 0xFFD6DAE3.toInt()

        /** 基线 */
        const val COLOR_TRACK = 0x33FFFFFF

        /** 柱宽占槽宽的比例 */
        const val BAR_WIDTH_RATIO = 0.55f

        /** 柱顶圆角（px，够小才有「柱」的感觉） */
        const val BAR_CORNER_RADIUS = 6f

        /** 生长动画时长 */
        const val GROW_DURATION_MS = 200L
    }
}
