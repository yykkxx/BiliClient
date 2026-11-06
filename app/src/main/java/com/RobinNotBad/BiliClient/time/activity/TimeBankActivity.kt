// 本文件对外暴露两个类型：页面 TimeBankActivity 与只服务本页的环图 BorrowRingView。
// detekt 的 MatchingDeclarationName 要求「文件名 = 唯一顶层类名」，两者对不上，故在此豁免
// （写法与本仓库历史上 TimeBankDayTypeDialog.kt 的豁免一致）。
@file:Suppress("MatchingDeclarationName")

package com.RobinNotBad.BiliClient.time.activity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.TextView

import androidx.core.content.ContextCompat

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.TimeBank
import com.RobinNotBad.BiliClient.time.TimePrefs
import com.RobinNotBad.BiliClient.time.ui.UsageBarChartView
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 小栏 2：时间银行（总开关 / 未来 7 天柱子 / 借时间）。
 *
 * # 规则（用户口径，逻辑层已全部实现，本页只负责表达）
 * - **只能向未来 [TimePrefs.bankMaxDays] 天（默认 7）借给今天**，不含今天；
 * - **不能还、没有利息、没有复利**；
 * - **任意时刻总借入 ≤ [TimePrefs.bankTotalLimitMin]（默认 300 = 5 小时）**；
 * - 借了哪天，那天的配额就相应减少（可能变成 0 甚至负数 → 那天直接不放行）；
 * - **只有在当前没有任何借入时才允许关闭**（[TimeBank.setEnabled] 返回 false 就是拒绝）。
 *
 * # 三个界面决策（都是从「怎么让用户最舒服」出发的）
 *
 * 1. **关闭银行不做 120 秒倒计时**，只做「有借入就拒绝」。
 *    用户原话对总开关的倒计时要求只针对**使用控制**（「这个开关关闭的时候需要进行倒计时 120s」），
 *    时间银行的约束是「只有在当前没有借的时候才可以关闭」。凭空给它加 120 秒属于加戏：
 *    借入已经是实际扣减了，多等两分钟也不能撤销它。
 *
 * 2. **不做默认预选**：进来先让用户点一根柱子，按钮文案是「选择要借的分钟数」。
 *    默认替用户选「明天」很危险 —— 他可能根本没注意到借的是哪天，
 *    而借完那天就真的少了。强制点一下，借的动作永远有明确前提。
 *
 * 3. **预设 15/30/60 + 加减 5 分钟**，不用滑杆：
 *    手表屏宽只有 3~4cm，滑杆拇指命中区太小、也不显示精确值；
 *    而这里真实需求就是「常用档 + 微调」，点一下就到位的按钮比滑杆准得多。
 *
 * # 「今天已借总量环」的数据源（一处对逻辑层的修正，已验证）
 * [TimeBank.borrowedInTodayMinutes] 的实现是 `borrowedFor(todayKey())`，
 * 而本模型**只能从未来借给今天**，今天永远不是借出方 → 那个值**恒为 0**。
 * 真正该显示的是 `borrowedTotalMinutes()`（= 尚未消化的借入总量，也就是今天真正多出来的额度）。
 * 已上报 Lead 并确认采纳。
 */
class TimeBankActivity : BaseActivity() {

    private var switchView: SwitchMaterial? = null
    private var switchDescView: TextView? = null
    private var closeTipView: TextView? = null
    private var ringView: BorrowRingView? = null
    private var ringTextView: TextView? = null
    private var ringHintView: TextView? = null
    private var futureDescView: TextView? = null
    private var chartView: UsageBarChartView? = null
    private var selectedView: TextView? = null
    private var amountView: TextView? = null
    private var borrowButton: MaterialButton? = null
    private var actionTipView: TextView? = null

    /** 程序性 setChecked 期间的屏蔽标志（理由见 UsageControlActivity 的类注释「致命坑」） */
    private var suppressChange = false

    /** 未来可借的日期（升序）；每次刷新重算，数据量最多 7 条 */
    private var futureDates: List<String> = emptyList()

    /** 选中下标；[NO_SELECTION] = 还没选 */
    private var selectedIndex = NO_SELECTION

    /** 本次要借的分钟数 */
    private var amountMinutes = DEFAULT_AMOUNT_MIN

    /** 中文日期格式化（「11 月 7 日」）；类加载时建一次，不在刷新里 new */
    private val dateFormatter = DateTimeFormatter.ofPattern("M 月 d 日")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_bank2)
        setPageName(getString(R.string.tm2_page_bank))

        switchView = findViewById(R.id.tm2_bank_switch)
        switchDescView = findViewById(R.id.tm2_bank_switch_desc)
        closeTipView = findViewById(R.id.tm2_bank_close_tip)
        ringView = findViewById(R.id.tm2_bank_ring)
        ringTextView = findViewById(R.id.tm2_bank_ring_text)
        ringHintView = findViewById(R.id.tm2_bank_ring_hint)
        futureDescView = findViewById(R.id.tm2_bank_future_desc)
        chartView = findViewById(R.id.tm2_bank_chart)
        selectedView = findViewById(R.id.tm2_bank_selected)
        amountView = findViewById(R.id.tm2_bank_amount)
        borrowButton = findViewById(R.id.tm2_bank_borrow)
        actionTipView = findViewById(R.id.tm2_bank_action_tip)

        bindSwitch()
        bindChart()
        bindAmountControls()
        bindBorrowButton()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 可能刚在「使用控制」页改过配额（配额变了，未来各天的可借量也跟着变）
        refreshAll()
    }

    // ==========================================================================================
    // 总开关
    // ==========================================================================================

    private fun bindSwitch() {
        val switch = switchView ?: return
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressChange) return@setOnCheckedChangeListener
            onSwitchToggled(isChecked)
        }
    }

    /**
     * 用户拨了开关。
     *
     * 打开方向永远成功（没有前置条件）。
     * 关闭方向交给 [TimeBank.setEnabled]：有未消化的借入时它返回 false 并**不写偏好**，
     * 本页把开关顶回「开」并把 [TimeBank.disableBlockedReason] 的原文显示出来 ——
     * 点了没反应是最难受的交互，必须告诉用户为什么。
     */
    private fun onSwitchToggled(isChecked: Boolean) {
        val accepted = TimeBank.setEnabled(isChecked)
        if (!accepted) {
            // 被拒绝：开关必须顶回「开」，否则界面会显示「已关闭」而实际银行还开着
            setSwitchProgrammatically(true)
            val reason = TimeBank.disableBlockedReason()
            closeTipView?.visibility = View.VISIBLE
            closeTipView?.text = reason.ifEmpty { getString(R.string.tm2_bank_close_blocked, "") }
            MsgUtil.showMsg(reason)
            return
        }
        // 成功（打开 / 关闭）：收起提示并刷新整页（关闭后按钮与图表的可用状态都要变）
        closeTipView?.visibility = View.GONE
        refreshAll()
    }

    /** 程序性改开关：必须屏蔽监听器（否则「顶回开」会被当成用户点击） */
    private fun setSwitchProgrammatically(checked: Boolean) {
        val switch = switchView ?: return
        suppressChange = true
        switch.setChecked(checked)
        suppressChange = false
    }

    // ==========================================================================================
    // 图表（未来 7 天）
    // ==========================================================================================

    private fun bindChart() {
        val chart = chartView ?: return
        chart.setOnBarSelectedListener { index ->
            selectedIndex = index
            prepareAmountForSelection()
            renderSelected()
            renderAmount()
        }
    }

    /**
     * 刷新图表数据：每根柱子的高度 = 那天**还能再借走多少分钟**（[TimeBank.availableFor]）。
     *
     * 为什么用 availableFor 而不是 capacityMinutes：
     * 用户看这根柱子想知道的是「从这里能借多少」，而不是「那天总共多少分钟」。
     * availableFor 已经同时考虑了「那天自己的剩余」与「总上限余额」两条约束（逻辑层注释有推导），
     * 所以柱子高度天然就是「可借量」，点进去也不会借失败。
     */
    private fun refreshChart() {
        val chart = chartView ?: return
        futureDates = TimeBank.futureDates()
        if (futureDates.isEmpty()) {
            chart.setData(emptyList(), { key, _ -> key }, highlightToday = false)
            return
        }
        val data = futureDates.map { date ->
            date to TimeBank.availableFor(date).toLong() * DailyPlan.MS_PER_MINUTE
        }
        chart.setData(data, { date, index -> labelFor(date, index) }, highlightToday = false)
        // 选中项若还在范围内就保留（借完一次还想接着借同一天是很自然的连续操作）
        val keep = selectedIndex in futureDates.indices
        chart.setSelectedIndex(if (keep) selectedIndex else NO_SELECTION)
        if (!keep) selectedIndex = NO_SELECTION
    }

    /**
     * 柱子轴标签：第一根「明天」，其余「周X」。
     *
     * 为什么不用完整日期：7 根柱子挤在手表宽度里，`11-07` 这种 5 字符已经很窄，
     * 再带上年份必然重叠。选中后下方 [selectedView] 会用完整中文日期补全信息。
     */
    private fun labelFor(date: String, index: Int): String {
        if (index == 0) return "明天"
        return runCatching {
            val localDate = LocalDate.parse(date)
            WEEK_LABELS[localDate.dayOfWeek.value - 1]
        }.getOrDefault(date.takeLast(5))
    }

    // ==========================================================================================
    // 借入金额与按钮
    // ==========================================================================================

    private fun bindAmountControls() {
        findViewById<View>(R.id.tm2_bank_preset_15)?.setOnClickListener { setAmount(PRESET_15) }
        findViewById<View>(R.id.tm2_bank_preset_30)?.setOnClickListener { setAmount(PRESET_30) }
        findViewById<View>(R.id.tm2_bank_preset_60)?.setOnClickListener { setAmount(PRESET_60) }
        findViewById<View>(R.id.tm2_bank_minus)?.setOnClickListener { stepAmount(-AMOUNT_STEP_MIN) }
        findViewById<View>(R.id.tm2_bank_plus)?.setOnClickListener { stepAmount(AMOUNT_STEP_MIN) }
    }

    private fun setAmount(minutes: Int) {
        amountMinutes = minutes.coerceIn(MIN_AMOUNT_MIN, MAX_AMOUNT_MIN)
        renderAmount()
        renderBorrowButton()
    }

    private fun stepAmount(delta: Int) = setAmount(amountMinutes + delta)

    /**
     * 选了某一天之后，把金额自动收进「那天还能借多少」的范围内。
     *
     * 理由：用户点到一根只剩 10 分钟的柱子时，金额还停在 30 会让他以为能借 30，
     * 点下去才发现失败。提前夹进来，按钮永远只在**必定成功**或**明确告诉原因**时可用。
     */
    private fun prepareAmountForSelection() {
        if (selectedIndex !in futureDates.indices) return
        val available = TimeBank.availableFor(futureDates[selectedIndex])
        if (available <= 0) {
            // 那天已经借不出任何时间（配额被借穿）：金额保留，按钮会显示拒绝原因
            return
        }
        amountMinutes = amountMinutes.coerceAtMost(available)
        if (amountMinutes < MIN_AMOUNT_MIN) amountMinutes = MIN_AMOUNT_MIN
    }

    private fun bindBorrowButton() {
        borrowButton?.setOnClickListener { onBorrowClicked() }
    }

    /**
     * 点「借 N 分钟」。
     *
     * 用 [TimeBank.borrow] 一步完成校验 + 写入：它内部先调 [TimeBank.canBorrowFrom]，
     * 失败**不落盘**并把可读理由返回。所以本页不需要再自己校验一遍
     * （再校验一遍就有「校验与写入不同源」的风险，那种 bug 极难复现）。
     */
    private fun onBorrowClicked() {
        val dates = futureDates
        val index = selectedIndex
        if (index !in dates.indices) {
            // 没选日期：提示而不是静默无视（用户会以为按钮坏了）
            showTip(getString(R.string.tm2_bank_not_selected), false)
            return
        }
        val targetDate = dates[index]
        val result = TimeBank.borrow(targetDate, amountMinutes)
        if (result.first) {
            MsgUtil.showMsg(getString(R.string.tm2_bank_borrow_done, labelForFull(targetDate), amountMinutes))
            showTip(getString(R.string.tm2_bank_borrow_failed, result.second), true)
            refreshAll()
        } else {
            // 拒绝原因原样展示（逻辑层已经把人话写好了，UI 不要自己再拼一遍）
            showTip(getString(R.string.tm2_bank_borrow_failed, result.second), false)
        }
    }

    /** 完整中文日期（「11 月 7 日」）；解析失败就退回原字符串，不让格式化把功能带崩 */
    private fun labelForFull(date: String): String = runCatching {
        dateFormatter.format(LocalDate.parse(date))
    }.getOrDefault(date)

    // ==========================================================================================
    // 渲染
    // ==========================================================================================

    private fun refreshAll() {
        renderSwitch()
        renderRing()
        refreshChart()
        renderSelected()
        renderAmount()
        renderBorrowButton()
        renderRuleTexts()
    }

    private fun renderSwitch() {
        val enabled = TimeBank.isEnabled()
        setSwitchProgrammatically(enabled)
        switchDescView?.text = if (enabled) {
            getString(
                R.string.tm2_bank_switch_desc_on,
                TimeBank.borrowedTotalMinutes(),
                TimePrefs.bankTotalLimitMin()
            )
        } else {
            getString(R.string.tm2_bank_switch_desc_off)
        }
    }

    /** 今天已借总量环：已借 / 上限；上限文字单独一行（只有环看不出上限是多少） */
    private fun renderRing() {
        val borrowed = TimeBank.borrowedTotalMinutes()
        val limit = TimePrefs.bankTotalLimitMin()
        ringView?.update(borrowed, limit)
        ringTextView?.text = getString(R.string.tm2_bank_ring_value, borrowed, limit)
        ringHintView?.text = getString(
            R.string.tm2_bank_ring_hint,
            limit,
            limit / 60
        )
    }

    private fun renderSelected() {
        val dates = futureDates
        val index = selectedIndex
        val date = dates.getOrNull(index)
        if (date == null) {
            selectedView?.text = getString(R.string.tm2_bank_not_selected)
            return
        }
        val available = TimeBank.availableFor(date)
        selectedView?.text = getString(R.string.tm2_bank_selected, labelForFull(date), available)
    }

    private fun renderAmount() {
        amountView?.text = getString(R.string.tm2_bank_borrow_amount, amountMinutes)
    }

    /**
     * 借入按钮文案 + 可用性。
     *
     * 三态：
     * - 银行未开 / 没选日期 → 禁用，文案说明差什么；
     * - 已选且在范围内 → 「借 N 分钟给今天」。
     * 不禁用而是让用户点了报错的原因：禁用态没有任何解释，用户只会觉得「点不动」。
     */
    private fun renderBorrowButton() {
        val button = borrowButton ?: return
        val hasSelection = selectedIndex in futureDates.indices
        val bankOn = TimeBank.isEnabled()
        button.isEnabled = hasSelection && bankOn && amountMinutes > 0
        button.text = if (hasSelection && bankOn) {
            getString(R.string.tm2_bank_borrow_action, amountMinutes)
        } else {
            getString(R.string.tm2_bank_borrow_action_empty)
        }
    }

    private fun renderRuleTexts() {
        futureDescView?.text = getString(
            R.string.tm2_bank_future_desc,
            TimeBank.borrowedTotalMinutes()
        )
        findViewById<TextView>(R.id.tm2_bank_rule_desc)?.text = getString(
            R.string.tm2_bank_rule_desc,
            TimePrefs.bankMaxDays()
        )
        // 银行没开时把「未开启」提示常驻在开关下面（比让用户点按钮才发现要好）
        if (!TimeBank.isEnabled() && closeTipView?.visibility != View.VISIBLE) {
            closeTipView?.visibility = View.VISIBLE
            closeTipView?.text = getString(R.string.tm2_bank_disabled_hint)
        } else if (TimeBank.isEnabled() && closeTipView?.text == getString(R.string.tm2_bank_disabled_hint)) {
            closeTipView?.visibility = View.GONE
        }
    }

    private fun showTip(text: String, positive: Boolean) {
        actionTipView?.visibility = View.VISIBLE
        actionTipView?.text = text
        val colorRes = if (positive) R.color.tm2_level_green else R.color.tm2_level_yellow
        actionTipView?.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private companion object {
        /** 未选中 */
        const val NO_SELECTION = -1

        /** 默认借入量（分钟）：一次 30 分钟是最常见的「再多玩一节课」的量 */
        const val DEFAULT_AMOUNT_MIN = 30

        /** 预设档位 */
        const val PRESET_15 = 15
        const val PRESET_30 = 30
        const val PRESET_60 = 60

        /** 微调步长（分钟） */
        const val AMOUNT_STEP_MIN = 5

        /** 金额下界（分钟）：0 分钟没有意义 */
        const val MIN_AMOUNT_MIN = 5

        /** 金额上界（分钟）：与逻辑层总上限的默认值一致，再大也必然被拒 */
        const val MAX_AMOUNT_MIN = 300

        /** 周几标签（`DayOfWeek.value` 是 1=周一 … 7=周日） */
        @JvmField
        val WEEK_LABELS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

/**
 * 「今天已借 / 上限」的圆环（自绘）。
 *
 * # 为什么不放进 time/ui
 * 本代理的写域在 time/ui 只允许两个通用图表（柱状图、页面排行条）。
 * 这个环只服务时间银行一页、语义极窄（已借 / 上限），放在页面文件里刚好，
 * 不必新增一个只有一页在用的公共 View 文件。
 *
 * # onDraw 零分配
 * 画笔构造时建好；只有一个复用 [rect]；中间数字文案在 [update] 时算好（不在 onDraw 里拼）。
 */
class BorrowRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 中间显示的两行文字（预格式化） */
    private var valueText = ""
    private var labelText = ""

    /** 已借 / 上限（分钟） */
    private var borrowed = 0
    private var limit = 0

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    init {
        val density = resources.displayMetrics.density
        val stroke = RING_STROKE_DP * density
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        valuePaint.textSize = RING_VALUE_TEXT_SP * resources.displayMetrics.scaledDensity
        labelPaint.textSize = RING_LABEL_TEXT_SP * resources.displayMetrics.scaledDensity
        trackPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_bar_empty)
        arcPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_bar)
        valuePaint.color = ContextCompat.getColor(context, R.color.tm2_level_green)
        labelPaint.color = ContextCompat.getColor(context, R.color.tm2_text_secondary)
    }

    /**
     * 更新数据。
     *
     * @param borrowedMinutes 已借（分钟，负数按 0）
     * @param limitMinutes 上限（分钟，<=0 时环按满处理，避免除零）
     */
    fun update(borrowedMinutes: Int, limitMinutes: Int) {
        borrowed = borrowedMinutes.coerceAtLeast(0)
        limit = limitMinutes.coerceAtLeast(1)
        valueText = "$borrowed"
        labelText = "上限 $limit 分钟"
        // 环的颜色跟「用了多少上限」走：超过 80% 转黄色提醒（不等到 100% 才变色）
        val ratio = borrowed.toFloat() / limit.toFloat()
        arcPaint.color = ContextCompat.getColor(
            context,
            when {
                ratio >= 1f -> R.color.tm2_level_red
                ratio >= WARN_RATIO -> R.color.tm2_level_yellow
                else -> R.color.tm2_chart_bar
            }
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val pad = RING_STROKE_DP * resources.displayMetrics.density / 2f + RING_PAD_DP * resources.displayMetrics.density
        rect.set(pad, pad, size - pad, size - pad)

        canvas.drawArc(rect, START_ANGLE, FULL_SWEEP, false, trackPaint)
        val ratio = (borrowed.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
        if (ratio > 0f) {
            canvas.drawArc(rect, START_ANGLE, FULL_SWEEP * ratio, false, arcPaint)
        }

        // 中间两行：大数字 + 上限小字。基线用字号近似居中，避免每帧 new FontMetrics
        val centerX = size / 2f
        val centerY = size / 2f
        canvas.drawText(valueText, centerX, centerY + valuePaint.textSize * 0.25f, valuePaint)
        canvas.drawText(labelText, centerX, centerY + valuePaint.textSize * 0.25f + labelPaint.textSize * 1.4f, labelPaint)
    }

    private companion object {
        /** 环的描边宽度（dp） */
        const val RING_STROKE_DP = 8f

        /** 环与控件边缘的额外留白（dp） */
        const val RING_PAD_DP = 4f

        /** 中间数字字号 */
        const val RING_VALUE_TEXT_SP = 20f

        /** 中间说明字号 */
        const val RING_LABEL_TEXT_SP = 10f

        /** 从 12 点方向开始画（-90°） */
        const val START_ANGLE = -90f

        /** 一整圈 */
        const val FULL_SWEEP = 360f

        /** 超过这个比例就把环变黄（提前提醒，不等借满才变色） */
        const val WARN_RATIO = 0.8f
    }
}
