package com.RobinNotBad.BiliClient.time.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

import androidx.core.content.ContextCompat

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.TimeFormat

/**
 * 时间管理的柱状图（自绘）：使用记录页的「7 日 / 全部」图表、时间银行页的「未来 7 天」图表共用。
 *
 * # 为什么自绘而不引图表库
 * 需求只要「一组柱子 + 轴标签 + 数值 + 可点选」这一件事。MPAndroidChart 之类的库动辄几百 KB，
 * 自带主题/字体/动画体系，还要跟本 App 的圆屏适配和「包体积已经很紧张」的现实较劲。
 * 自绘 200 多行就够，而且完全可控。
 *
 * # onDraw 零分配（硬要求）
 * - 全部画笔构造时建好，颜色也从资源取一次；
 * - 柱子只复用 [rect] 一个 RectF，不在循环里 `new`；
 * - **轴标签与柱顶数值在 [setData] 时预先算成 String**，`onDraw` 里不拼字符串
 *   （每帧 `String.format` 都会分配对象，低端手表上是看得见的 GC 抖动）。
 *
 * # 圆屏适配（用户点名要求）
 * 三处留白全按**控件自身尺寸的比例**算，而不是硬编码 dp：
 * 顶部 [PAD_TOP_RATIO] 给柱顶数字、底部 [PAD_BOTTOM_RATIO] 给轴标签、
 * 左右 [PAD_H_RATIO] 让柱子不压到圆屏边缘被裁掉。
 * [onMeasure] 再给高度兜底，`wrap_content` 时不会量成 0（圆屏高度本来就小，
 * 量成 0 的表现就是「整块图表消失」）。
 */
class UsageBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 轴标签（预格式化，onDraw 直接用） */
    private var labels: Array<String> = emptyArray()

    /** 柱顶数值（预格式化） */
    private var values: Array<String> = emptyArray()

    /** 归一化柱高比例（0~1）；全 0 时全是 0（不画假数据） */
    private var ratios: FloatArray = FloatArray(0)

    /** 最后一根是否按「今天」的三档语义色高亮（时间银行页不是今天，传 false） */
    private var highlightLast = true

    /** 当前选中下标（[NO_SELECTION] = 未选） */
    private var selectedIndex = NO_SELECTION

    /** 点击柱子的回调（null = 只读图表，不消费触摸事件） */
    private var onBarSelected: ((Int) -> Unit)? = null

    /**
     * onDraw 顺手记下的槽位几何，供 [onTouchEvent] 把 x 换算成下标。
     *
     * 为什么要记：如果在触摸里把留白公式再抄一遍，两处迟早会漂移
     * （改了绘制留白、忘了改点击判定 → 点柱子点不中，这种 bug 极难查）。
     * 记三个标量不分配对象，不违反零分配约定。
     */
    private var geomPadH = 0f
    private var geomSlotWidth = 0f
    private var geomCount = 0

    // ---- 画笔：构造时一次性建好 ----
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lastBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emptyBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    /** 唯一的矩形复用对象 */
    private val rect = RectF()

    init {
        labelPaint.textSize = sp2px(LABEL_TEXT_SP)
        valuePaint.textSize = sp2px(VALUE_TEXT_SP)

        barPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_bar)
        emptyBarPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_bar_empty)
        axisPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_axis)
        labelPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_axis)
        valuePaint.color = ContextCompat.getColor(context, R.color.tm2_text_secondary)
        lastBarPaint.color = ContextCompat.getColor(context, R.color.tm2_level_green)
        selectedPaint.color = ContextCompat.getColor(context, R.color.tm2_level_green)

        // 高度兜底（理由见类注释）
        minimumHeight = dp2pxInt(MIN_HEIGHT_DP)
    }

    /**
     * 设置一组「键 + 毫秒」数据。
     *
     * @param source 例如 [com.RobinNotBad.BiliClient.time.UsageLog.last7Days] 的结果；
     *   **长度任意**（不写死 7），时间银行页复用同一套绘制。
     * @param labelBuilder 由调用方把原始键转成轴标签。图表**不自己发明日期格式**：
     *   使用记录页要「11-01」，时间银行页要「明天」，口径不同。
     * @param highlightToday 是否把最后一根用三档语义色高亮。
     */
    fun setData(
        source: List<Pair<String, Long>>,
        labelBuilder: (String, Int) -> String,
        highlightToday: Boolean = true
    ) {
        val count = source.size
        labels = Array(count) { index -> labelBuilder(source[index].first, index) }
        values = Array(count) { index -> formatValue(source[index].second) }
        val max = source.maxOfOrNull { it.second } ?: 0L
        ratios = FloatArray(count) { index ->
            if (max <= 0L) 0f else (source[index].second.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        }
        highlightLast = highlightToday
        // 「今天」那一格的语义色由**它真实的毫秒数**决定档位（不能拿别的格子顶替）
        val lastMs = source.lastOrNull()?.second ?: 0L
        lastBarPaint.color = TimeFormat.levelColor(context, DailyPlan.levelOf(lastMs))
        invalidate()
    }

    /** 设置选中项（只影响描边，不改数据）；[NO_SELECTION] 取消选中 */
    fun setSelectedIndex(index: Int) {
        if (selectedIndex == index) return
        selectedIndex = index
        invalidate()
    }

    /** 让图表可点；传 null 变回只读（不消费触摸，外层 ScrollView 正常滚动） */
    fun setOnBarSelectedListener(listener: ((Int) -> Unit)?) {
        onBarSelected = listener
    }

    /**
     * 数值文案：0 显示破折号。
     *
     * 理由：「0 分钟」在一排柱子里太吵，而且分不清「这格没数据」与「这格真的是 0」。
     * 破折号 + 底部浅色短条（见 onDraw）能同时表达「这格存在，但没有时长」。
     */
    private fun formatValue(ms: Long): String = if (ms <= 0L) "—" else TimeFormat.duration(ms)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val callback = onBarSelected ?: return false
        if (event.action == MotionEvent.ACTION_UP) {
            val index = hitIndex(event.x)
            if (index >= 0) {
                selectedIndex = index
                invalidate()
                callback(index)
            }
        }
        // 必须返回 true：返回 false 时父容器会把整个手势当滑动，ACTION_UP 不会回到这里
        return true
    }

    /** x 坐标 → 柱子下标。点到留白/缝隙时落到最近一格（手感更宽容） */
    private fun hitIndex(x: Float): Int {
        if (geomCount <= 0 || geomSlotWidth <= 0f) return NO_SELECTION
        val relative = x - geomPadH
        if (relative < 0f) return 0
        return (relative / geomSlotWidth).toInt().coerceIn(0, geomCount - 1)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredHeight <= 0) {
            setMeasuredDimension(measuredWidth, dp2pxInt(MIN_HEIGHT_DP))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (labels.isEmpty()) {
            // 空态也要有内容：直接空白会让用户以为页面没加载出来
            canvas.drawText(EMPTY_HINT, width / 2f, height / 2f, labelPaint)
            return
        }

        // 留白按控件尺寸的比例算（圆屏适配的关键，见类注释）
        val padTop = height * PAD_TOP_RATIO
        val padBottom = height * PAD_BOTTOM_RATIO
        val padH = width * PAD_H_RATIO
        val usableWidth = width - padH * 2f
        val usableHeight = height - padTop - padBottom
        if (usableWidth <= 0f || usableHeight <= 0f) return

        val count = labels.size
        val slotWidth = usableWidth / count
        val barWidth = slotWidth * BAR_WIDTH_RATIO
        val baseline = padTop + usableHeight

        // 记下几何供触摸换算（三个标量赋值，不分配对象）
        geomPadH = padH
        geomSlotWidth = slotWidth
        geomCount = count

        canvas.drawLine(padH, baseline, width - padH, baseline, axisPaint)

        for (index in 0 until count) {
            val ratio = ratios.getOrElse(index) { 0f }
            val barHeight = usableHeight * ratio
            val left = padH + slotWidth * index + (slotWidth - barWidth) / 2f
            val right = left + barWidth
            val centerX = left + barWidth / 2f

            if (barHeight > 0f) {
                val top = baseline - barHeight
                rect.set(left, top, right, baseline)
                val paint = if (highlightLast && index == count - 1) lastBarPaint else barPaint
                canvas.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, paint)
                // 圆角把底边也倒圆了，补一块直角让柱子「插在地上」
                if (barHeight > BAR_RADIUS) {
                    canvas.drawRect(left, baseline - BAR_RADIUS, right, baseline, paint)
                }
                // 柱顶数值：矮柱时抬到上方，但不越过顶部留白（否则会被裁掉半行字）
                val valueBaseline = (top - dp2px(VALUE_GAP_DP)).coerceAtLeast(labelPaint.textSize)
                canvas.drawText(values.getOrElse(index) { "" }, centerX, valueBaseline, valuePaint)
            } else {
                // 无数据格：画一条 2dp 浅色短条，表示「这一格存在但为 0」
                rect.set(left, baseline - EMPTY_BAR_HEIGHT, right, baseline)
                canvas.drawRect(rect, emptyBarPaint)
            }

            // 选中描边：套在柱子外侧，不改变数据也不遮住柱子本身
            if (index == selectedIndex) {
                val outline = slotWidth * 0.5f
                val outlineHeight = (barHeight + dp2px(SELECT_PAD_DP)).coerceAtLeast(dp2px(MIN_SELECT_HEIGHT_DP))
                rect.set(
                    centerX - outline,
                    baseline - outlineHeight,
                    centerX + outline,
                    baseline
                )
                canvas.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, selectedPaint)
            }

            canvas.drawText(
                labels.getOrElse(index) { "" },
                centerX,
                baseline + padBottom * LABEL_BASELINE_RATIO,
                labelPaint
            )
        }
    }

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun dp2pxInt(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    private companion object {
        /** 未选中 */
        const val NO_SELECTION = -1

        /** 顶部留白占高度的比例（给柱顶数字） */
        const val PAD_TOP_RATIO = 0.20f

        /** 底部留白占高度的比例（给轴标签） */
        const val PAD_BOTTOM_RATIO = 0.16f

        /** 左右留白占宽度的比例（圆屏不压边） */
        const val PAD_H_RATIO = 0.045f

        /** 柱宽占槽宽的比例：留出间距，多根柱子才不粘连 */
        const val BAR_WIDTH_RATIO = 0.58f

        /** 柱顶圆角（px） */
        const val BAR_RADIUS = 5f

        /** 柱顶数值与柱体的间距（dp） */
        const val VALUE_GAP_DP = 3f

        /** 轴标签基线位置占底部留白的比例 */
        const val LABEL_BASELINE_RATIO = 0.74f

        /** 轴标签字号 */
        const val LABEL_TEXT_SP = 9f

        /** 柱顶数值字号 */
        const val VALUE_TEXT_SP = 9f

        /** 无数据格的浅条高度（px） */
        const val EMPTY_BAR_HEIGHT = 2f

        /** 选中描边比柱宽多出的余量（dp） */
        const val SELECT_PAD_DP = 3f

        /** 选中框最小高度（dp）：0 值柱也要能看出被选中 */
        const val MIN_SELECT_HEIGHT_DP = 8f

        /** 高度兜底（dp） */
        const val MIN_HEIGHT_DP = 130

        /** 空态文案（不走 strings.xml：本 View 不该依赖页面的资源 id） */
        const val EMPTY_HINT = "还没有数据"
    }
}
