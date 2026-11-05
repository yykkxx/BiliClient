package com.RobinNotBad.BiliClient.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

import com.RobinNotBad.BiliClient.util.DayQuota
import com.RobinNotBad.BiliClient.util.ThemeUtil

/**
 * 时间银行的两个自绘控件（同一个文件 —— 本代理的写域只到 `ui/widget/TimeBankChartView.kt`，
 * 环形进度与柱状图都属于「时间银行的图」，放一起便于统一维护）：
 *
 * 1. [TimeBankRingView]：顶部环形进度（今天已用 / 今天可用），圈中间是「剩余 N 分钟」或
 *    「已超出 X 分钟」（超出时数字与圆环变红）—— 规格 §3.4-1「环形/横向进度」；
 * 2. [TimeBankChartView]：中部「未来 7 天」横向柱状图，柱高 = 该天可用分钟，
 *    柱顶叠加「已被借走」的灰色段，点柱子选中那天（选中态描边）。
 *
 * # 性能约定（规格 §7）
 * - `Paint` / `RectF` / 绘制用的字符串**全部在初始化或 `setXxx()` 里准备好**，
 *   `onDraw` 里**不 new 对象、不拼接字符串、不读 SharedPreferences**（数据由 Activity 预取传入）；
 * - 生长动画用 `ValueAnimator`（200ms），只改一个 `Float` 字段 + `invalidate()`；
 * - 主题色在 `setXxx()` 里取一次并存成字段（`ThemeUtil.getColor()` 要读偏好，不能进 `onDraw`）。
 */
class TimeBankRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 圆环轨道与进度画笔（初始化一次，之后只改 `color`） */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 中间大字与副标题画笔 */
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 复用矩形，避免 onDraw 分配（每帧只改 left/top/right/bottom） */
    private val ringRect = RectF()

    /** 进度 0..1（已用 / 可用，超出时画满） */
    private var progress = 0f

    /** 是否已超出配额（决定颜色与文案） */
    private var exceeded = false

    /** 中间大字（例如 "95" / "12"）—— 由 [updateData] 预先算好，onDraw 不再拼接 */
    private var valueText = ""

    /** 大字下面的说明（"剩余分钟" / "已超出分钟"）；文案由页面用字符串资源注入，避免在 View 里写死中文 */
    private var labelText = ""
    private var remainingLabel = DEFAULT_REMAINING_LABEL
    private var exceededLabel = DEFAULT_EXCEEDED_LABEL

    private val warnColor = 0xFFF44336.toInt()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    init {
        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeWidth = STROKE_DP * density
        trackPaint.color = TRACK_COLOR

        progressPaint.style = Paint.Style.STROKE
        progressPaint.strokeWidth = STROKE_DP * density
        progressPaint.strokeCap = Paint.Cap.ROUND

        valuePaint.textAlign = Paint.Align.CENTER
        valuePaint.textSize = VALUE_TEXT_SP * scaledDensity
        valuePaint.isFakeBoldText = true

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = LABEL_TEXT_SP * scaledDensity
        labelPaint.color = LABEL_COLOR
    }

    /**
     * 注入「剩余 / 已超出」两个说明文案（由页面用字符串资源传入，View 里不写死中文）。
     *
     * 只在页面初始化时调用一次；之后的 `updateData` 只改数字，不再分配字符串。
     */
    fun bindLabels(remaining: String, exceeded: String) {
        remainingLabel = remaining
        exceededLabel = exceeded
        invalidate()
    }

    /**
     * 更新环形进度。
     *
     * @param usedMin 今天已用分钟
     * @param availableMin 今天可用分钟（含借入；为 0 时按「已超出已用分钟」处理，**绝不除以 0**）
     */
    fun updateData(usedMin: Int, availableMin: Int) {
        val accent = ThemeUtil.getColor()
        val available = availableMin.coerceAtLeast(0)
        val used = usedMin.coerceAtLeast(0)
        exceeded = used > available
        progress = when {
            // 还没用过（配额被设成 0 的极端情况）：画空环，避免「0 已用却满格」自相矛盾
            used <= 0 -> 0f
            exceeded -> 1f
            available <= 0 -> 1f
            else -> (used.toFloat() / available.toFloat()).coerceIn(0f, 1f)
        }
        if (exceeded) {
            valueText = (used - available).toString()
            labelText = exceededLabel
            progressPaint.color = warnColor
            valuePaint.color = warnColor
        } else {
            valueText = (available - used).toString()
            labelText = remainingLabel
            progressPaint.color = accent
            valuePaint.color = Color.WHITE
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height)
        if (size <= 0) return
        val stroke = progressPaint.strokeWidth
        val radius = (size.toFloat() - stroke) / 2f
        if (radius <= 0f) return
        val centerX = width / 2f
        val centerY = height / 2f

        ringRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(ringRect, START_ANGLE, SWEEP_FULL, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(ringRect, START_ANGLE, SWEEP_FULL * progress, false, progressPaint)
        }

        // 大字与说明：字符串已预取，这里只算基线并绘制
        val valueBaseline = centerY + valuePaint.textSize / 3f
        canvas.drawText(valueText, centerX, valueBaseline, valuePaint)
        // 页面还没注入文案时（理论上不会发生）宁可不画，也不在界面上露英文兜底
        if (labelText.isNotEmpty()) {
            canvas.drawText(labelText, centerX, valueBaseline + labelPaint.textSize + LABEL_GAP_DP * density, labelPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 圆形：取宽高较小值，避免圆屏/窄屏被拉成椭圆
        val size = minOf(measuredWidth, measuredHeight)
        if (size > 0) {
            setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
        }
    }

    private companion object {
        /** 圆环起始角度：正上方（-90°） */
        const val START_ANGLE = -90f

        /** 一整圈 */
        const val SWEEP_FULL = 360f

        /** 环线宽（dp） */
        const val STROKE_DP = 10f

        /** 中间大字字号（sp） */
        const val VALUE_TEXT_SP = 30f

        /** 说明字号（sp） */
        const val LABEL_TEXT_SP = 12f

        /** 说明与大字间距（dp） */
        const val LABEL_GAP_DP = 4f

        /** 轨道颜色（浅色 20% 白） */
        const val TRACK_COLOR = 0x33EBE0E2.toInt()

        /** 说明文字颜色（70% 白） */
        const val LABEL_COLOR = 0xB3EBE0E2.toInt()

        /** 页面没注入文案时的兜底：空串 = 不画说明（正常路径一定会被 [bindLabels] 覆盖） */
        const val DEFAULT_REMAINING_LABEL = ""

        /** 超限时说明的兜底（同上，空串不画） */
        const val DEFAULT_EXCEEDED_LABEL = ""
    }
}

/**
 * 时间银行「未来 7 天」柱状图（纯自绘，无第三方图表库）。
 *
 * 每根柱子：总高 = 该天基础配额；下半段是**还能用的**（主题色），上半段是**被预支走的**（灰色）；
 * 柱顶标该天可用分钟；柱子下面是日期短标签（明天 / 周三…）。
 * 点击柱子 → 选中该天（选中态画描边），通过 [onDaySelected] 回调给页面。
 */
class TimeBankChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 柱子（可用段）填充画笔 */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 被预支走的叠加段（灰/暗） */
    private val borrowedPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 选中态描边 */
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 柱顶分钟数与日期标签 */
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 复用矩形 */
    private val barRect = RectF()

    /** 7 天的数据（Activity 传入，onDraw 只读这个字段） */
    private var days: List<DayQuota> = emptyList()

    /** 每天**预取好**的绘制字符串：可用分钟、日期短标签（onDraw 不再 toString()） */
    private val valueTexts = arrayOfNulls<String>(MAX_DAYS)
    private val labelTexts = arrayOfNulls<String>(MAX_DAYS)

    /** 选中下标（-1 = 没有选中） */
    private var selectedIndex = -1

    /** 生长动画进度 0..1 */
    private var growth = 1f

    /** 柱子的纵向基准：所有未来天共用同一个基础配额，这里取它们的峰值（至少 1，避免除以 0） */
    private var maxBase = 1

    /** 选中回调（参数是下标，0 = 明天） */
    private var daySelected: ((Int) -> Unit)? = null

    private var accentColor = ThemeUtil.DEFAULT_COLOR

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private var growAnimator: ValueAnimator? = null

    init {
        barPaint.style = Paint.Style.FILL

        borrowedPaint.style = Paint.Style.FILL
        borrowedPaint.color = BORROWED_COLOR

        selectPaint.style = Paint.Style.STROKE
        selectPaint.strokeWidth = 2f * density

        valuePaint.textAlign = Paint.Align.CENTER
        valuePaint.textSize = VALUE_TEXT_SP * scaledDensity
        valuePaint.color = VALUE_COLOR

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = LABEL_TEXT_SP * scaledDensity
        labelPaint.color = LABEL_COLOR
    }

    /**
     * 设置数据并指定选中项（[newDays] 长度应为 7；超出 [MAX_DAYS] 的部分忽略，缺的当没有）。
     *
     * 绘制用的字符串在此**一次性**生成，`onDraw` 只读字段 —— 满足「onDraw 不分配」的约定。
     */
    fun setDays(newDays: List<DayQuota>, selected: Int) {
        days = if (newDays.size > MAX_DAYS) newDays.subList(0, MAX_DAYS) else newDays
        accentColor = ThemeUtil.getColor()
        barPaint.color = accentColor
        selectPaint.color = accentColor
        selectedIndex = selected
        maxBase = days.maxOfOrNull { it.baseMin }?.coerceAtLeast(1) ?: 1
        for (index in 0 until MAX_DAYS) {
            val day = days.getOrNull(index)
            valueTexts[index] = day?.availableMin?.toString()
            labelTexts[index] = day?.label
        }
        invalidate()
    }

    /** 只改选中项（不重算字符串、不重取主题色） */
    fun setSelectedIndex(index: Int) {
        if (index == selectedIndex) return
        selectedIndex = index
        invalidate()
    }

    /** 选中回调：参数是下标（0 = 明天） */
    fun onDaySelected(listener: (Int) -> Unit) {
        daySelected = listener
    }

    /** 尺寸/数据是否足以绘制（拆成函数：避免 onDraw 里堆四个 && 触发 ComplexCondition） */
    private fun canDraw(count: Int): Boolean {
        if (count <= 0) return false
        if (width <= 0) return false
        if (height <= 0) return false
        return maxBase > 0
    }

    /** 200ms 生长动画：进页面 / 借成功重绘时柱子从 0 长到目标高度 */
    fun playGrowAnimation() {
        growAnimator?.cancel()
        growAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GROW_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                growth = animator.animatedValue as? Float ?: 1f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        growAnimator?.cancel()
        growAnimator = null
        // 动画中途被销毁时停在半截高度；重置成满高度，重新 attach 后不会画出「半根柱子」
        growth = 1f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = days.size
        if (!canDraw(count)) return

        val padTop = PAD_TOP_DP * density
        val padBottom = PAD_BOTTOM_DP * density
        val plotHeight = height.toFloat() - padTop - padBottom
        if (plotHeight <= 0f) return

        barPaint.color = accentColor
        val slot = width.toFloat() / count
        val barWidth = slot * BAR_WIDTH_RATIO
        val corner = CORNER_RADIUS_DP * density
        val bottom = height.toFloat() - padBottom

        for (index in 0 until count) {
            val day = days[index]
            val available = day.availableMin.coerceAtLeast(0)
            // 某天可能被借超过本身配额（借的时间比那天配额还多）：视觉上按「整根柱子都被借走」画，
            // 不再按 300 分钟的真实值撑出柱子（否则柱顶会画到视图外面）
            val borrowed = day.borrowedOutMin.coerceIn(0, day.baseMin.coerceAtLeast(0))

            val left = slot * index + (slot - barWidth) / 2f
            val right = left + barWidth

            // 可用段高度（growth 让它从 0 长起来）
            val usableHeight = plotHeight * available.toFloat() / maxBase.toFloat() * growth
            // 被借走段高度：与可用段同一个纵向基准，视觉上「借走多少就多高」
            val borrowedHeight = plotHeight * borrowed.toFloat() / maxBase.toFloat() * growth

            barRect.set(left, bottom - usableHeight, right, bottom)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)

            if (borrowedHeight > 0f) {
                barRect.set(left, bottom - usableHeight - borrowedHeight, right, bottom - usableHeight)
                canvas.drawRoundRect(barRect, corner, corner, borrowedPaint)
            }

            val topY = bottom - usableHeight - borrowedHeight

            // 柱顶分钟数（字符串已预取）
            val text = valueTexts[index]
            if (text != null) {
                canvas.drawText(text, left + barWidth / 2f, topY - TEXT_GAP_DP * density, valuePaint)
            }

            // 日期短标签
            val label = labelTexts[index]
            if (label != null) {
                canvas.drawText(label, left + barWidth / 2f, height.toFloat() - LABEL_BOTTOM_DP * density, labelPaint)
            }

            // 选中态描边（框住整根柱子的可视范围）
            if (index == selectedIndex) {
                barRect.set(left - 2f * density, topY - 2f * density, right + 2f * density, bottom + density)
                canvas.drawRoundRect(barRect, corner, corner, selectPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val count = days.size
                if (count > 0 && width > 0) {
                    val index = (event.x / (width.toFloat() / count)).toInt().coerceIn(0, count - 1)
                    if (index != selectedIndex) {
                        selectedIndex = index
                        invalidate()
                    }
                    daySelected?.invoke(index)
                }
                performClick()
                return true
            }
            else -> return super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        /** 一个文件里最多画几根柱子（未来 7 天） */
        const val MAX_DAYS = 7

        /** 柱子宽度占每个槽位的比例（留出间距） */
        const val BAR_WIDTH_RATIO = 0.58f

        /** 圆角（dp） */
        const val CORNER_RADIUS_DP = 4f

        /** 顶部留白（dp）：给柱顶分钟数 */
        const val PAD_TOP_DP = 18f

        /** 底部留白（dp）：给日期短标签 */
        const val PAD_BOTTOM_DP = 20f

        /** 柱顶数字与柱子的间距（dp） */
        const val TEXT_GAP_DP = 4f

        /** 日期标签基线距底部的距离（dp） */
        const val LABEL_BOTTOM_DP = 4f

        /** 生长动画时长（毫秒，规格 §7 要求 200ms） */
        const val GROW_DURATION_MS = 200L

        /** 被借走段颜色（40% 白，压暗） */
        const val BORROWED_COLOR = 0x66EBE0E2.toInt()

        /** 柱顶数字颜色 */
        const val VALUE_COLOR = 0xFFEBE0E2.toInt()

        /** 日期标签颜色（70% 白） */
        const val LABEL_COLOR = 0xB3EBE0E2.toInt()

        /** 柱顶数字字号（sp） */
        const val VALUE_TEXT_SP = 11f

        /** 日期标签字号（sp） */
        const val LABEL_TEXT_SP = 12f
    }
}
