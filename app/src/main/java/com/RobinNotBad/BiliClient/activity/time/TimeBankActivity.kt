package com.RobinNotBad.BiliClient.activity.time

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.ui.widget.TimeBankChartView
import com.RobinNotBad.BiliClient.ui.widget.TimeBankRingView
import com.RobinNotBad.BiliClient.util.DayQuota
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.TimeBankManager
import com.google.android.material.button.MaterialButton

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 时间银行（v5.2.0 需求 3）：**图形化的预支界面**。
 *
 * 页面三段（自上而下，见 `res/layout/activity_time_bank.xml`）：
 * 1. 顶部环形进度：今天已用 / 今天可用，圈中间大字「剩余 N 分钟」；超出后变红显示「已超出 X 分钟」；
 * 2. 中部未来 7 天柱状图（[TimeBankChartView]，纯自绘）：柱高 = 该天可用，灰色叠加段 = 已被借走，
 *    点柱子选中该天（描边）；
 * 3. 底部操作区：选中日期 + −10 / +10 + 「借 N 分钟」+「当前预支 X / 上限 Y」。
 *
 * # 设计判断（规格未写死的 UI 细节，按「用户会觉得最直观」定）
 * - **默认不预选任何一天**：一进来就替用户选好「明天」并允许直接点借，用户很可能没意识到借的是哪天。
 *   强制先点柱子再看按钮，借的动作永远有明确的「从哪天」前提；未选时按钮文案退化为「借时间」并禁用式提示。
 *  - 步长固定 10 分钟，但**受当天剩余可借量限制**：加号不会越过「那天还能借多少」，
 *    避免用户加到 60 却发现借不出来（比先加后报错更减少无效操作）。
 * - 借成功后**保留选中**并刷新图表 + 播放 200ms 生长动画，用户能直观看到那根柱子变矮；
 *   连续借同一天只需再点按钮，不用重新选。
 * - 环形中间的单位用「分钟」而不是格式化成「X 小时 Y 分」：圈里字号大，分钟数最容易一眼比较
 *   （已用/可用本来就是分钟量级）。
 *
 * 本页从设置页「时间银行」与拦截页「借时间」两处进入；它**不负责拦截判定**（那是
 * [TimeBankBlockActivity] 与启动页的事），只负责展示与预支。
 */
class TimeBankActivity : BaseActivity() {

    /** 环形进度（今天已用 / 可用） */
    private var ringView: TimeBankRingView? = null

    /** 未来 7 天柱状图 */
    private var chartView: TimeBankChartView? = null

    private var summaryView: TextView? = null
    private var exhaustedHintView: TextView? = null
    private var selectedDayView: TextView? = null
    private var minutesView: TextView? = null
    private var borrowButton: MaterialButton? = null
    private var capacityView: TextView? = null

    /** 未来 7 天的数据（每次刷新都重新算，数据量极小） */
    private var days: List<DayQuota> = emptyList()

    /** 选中下标（[NO_SELECTION] = 尚未选择） */
    private var selectedIndex = NO_SELECTION

    /** 本次要借的分钟数（10 的倍数，1..60） */
    private var borrowMinutes = DEFAULT_BORROW_MINUTES

    /** 生成「从 11 月 7 日（周六）借」这种中文日期用的格式化器（类加载时建一次） */
    private val dayFormatter = DateTimeFormatter.ofPattern("M 月 d 日")

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_bank)
        setPageName(getString(R.string.time_bank_title))
        setTopbarExit()

        ringView = findViewById(R.id.time_bank_ring)
        chartView = findViewById(R.id.time_bank_chart)
        summaryView = findViewById(R.id.time_bank_today_summary)
        exhaustedHintView = findViewById(R.id.time_bank_exhausted_hint)
        selectedDayView = findViewById(R.id.time_bank_selected_day)
        minutesView = findViewById(R.id.time_bank_borrow_minutes)
        borrowButton = findViewById(R.id.time_bank_borrow_btn)
        capacityView = findViewById(R.id.time_bank_capacity)

        ringView?.bindLabels(
            getString(R.string.time_bank_ring_remaining),
            getString(R.string.time_bank_ring_exceeded)
        )
        chartView?.onDaySelected { index -> onDaySelected(index) }
        findViewById<View>(R.id.time_bank_minus).setOnClickListener { stepMinutes(-STEP_MINUTES) }
        findViewById<View>(R.id.time_bank_plus).setOnClickListener { stepMinutes(STEP_MINUTES) }
        borrowButton?.setOnClickListener { onBorrowClicked() }

        refreshAll(playAnimation = true)
    }

    override fun onResume() {
        super.onResume()
        // 使用时长可能在本页打开期间继续走（比如从拦截页进来时刚结算过），回来时重新对齐一次
        refreshAll(playAnimation = false)
    }

    // ---------------------------------------------------------------- 刷新

    /**
     * 全量刷新：环形 + 柱状图 + 底部操作区。
     *
     * @param playAnimation 是否播放柱子生长动画（进页面/借成功后播，onResume 的轻刷新不播，避免闪）
     */
    private fun refreshAll(playAnimation: Boolean) {
        val used = TimeBankManager.usedMinToday()
        val available = TimeBankManager.availableQuotaMinToday()

        ringView?.updateData(used, available)
        summaryView?.text = getString(R.string.time_bank_today_summary, used, available)
        exhaustedHintView?.visibility =
            if (TimeBankManager.isExhaustedToday()) View.VISIBLE else View.GONE

        days = TimeBankManager.futureDays()
        // 选中项若还落在范围内就保留（借成功后的连续操作体验），否则清掉
        if (selectedIndex >= days.size) selectedIndex = NO_SELECTION
        chartView?.setDays(days, selectedIndex)
        if (playAnimation) chartView?.playGrowAnimation()

        refreshBorrowArea()
    }

    /** 刷新底部：选中日期文案、分钟数、按钮文案、预支总量 */
    private fun refreshBorrowArea() {
        val day = days.getOrNull(selectedIndex)
        selectedDayView?.text = if (day == null) {
            getString(R.string.time_bank_selected_day_none)
        } else {
            getString(R.string.time_bank_selected_day, dayText(day))
        }
        minutesView?.text = getString(R.string.time_bank_minutes_value, borrowMinutes)
        borrowButton?.text = getString(R.string.time_bank_borrow_action, borrowMinutes)
        capacityView?.text = getString(
            R.string.time_bank_capacity,
            TimeBankManager.outstandingBorrowMin(),
            TimeBankManager.borrowLimitMin()
        )
    }

    /** 柱子的完整中文标签：`11 月 7 日（周六）` */
    private fun dayText(day: DayQuota): String {
        val date = try {
            LocalDate.parse(day.date)
        } catch (ignored: Exception) {
            null
        }
        if (date == null) return day.label
        return getString(R.string.time_bank_day_full, date.format(dayFormatter), day.label)
    }

    // ---------------------------------------------------------------- 交互

    /** 点柱子：选中并刷新底部（选中态由 View 自己画，这里只同步数据） */
    private fun onDaySelected(index: Int) {
        selectedIndex = index
        // 换了目标日期时把分钟数夹回「那天还能借多少」，避免留着上次的 60 却借不出来
        clampMinutesToDay()
        refreshBorrowArea()
    }

    /** −10 / +10：受「1..60」与「当天剩余可借」双重约束 */
    private fun stepMinutes(delta: Int) {
        val target = borrowMinutes + delta
        borrowMinutes = target.coerceIn(MIN_BORROW_MINUTES, MAX_BORROW_MINUTES)
        clampMinutesToDay()
        refreshBorrowArea()
    }

    /**
     * 把 [borrowMinutes] 夹到「选中那天还能借的分钟数」。
     * 那天已经借光（可用 0）时保持 1（交给借出逻辑给出明确失败提示，而不是把按钮变成 0 分钟）。
     */
    private fun clampMinutesToDay() {
        val day = days.getOrNull(selectedIndex) ?: return
        val capacityOfDay = day.availableMin
        val ceiling = minOf(MAX_BORROW_MINUTES, maxOf(MIN_BORROW_MINUTES, capacityOfDay))
        borrowMinutes = borrowMinutes.coerceIn(MIN_BORROW_MINUTES, ceiling)
    }

    /** 点「借 N 分钟」：校验 → 写偏好 → 刷新 → Toast */
    private fun onBorrowClicked() {
        val day = days.getOrNull(selectedIndex)
        if (day == null) {
            MsgUtil.toast(getString(R.string.time_bank_borrow_fail_nodate))
            return
        }
        if (day.borrowedOutMin >= day.baseMin) {
            MsgUtil.toast(getString(R.string.time_bank_borrow_fail_day_empty, day.label))
            return
        }
        if (borrowMinutes > day.availableMin) {
            MsgUtil.toast(getString(R.string.time_bank_borrow_fail_day_capacity, day.label, day.availableMin))
            return
        }
        if (!TimeBankManager.borrow(day.date, borrowMinutes)) {
            toastBorrowFailure()
            return
        }
        MsgUtil.toast(getString(R.string.time_bank_borrow_ok, dayText(day), borrowMinutes))
        // 借完立刻刷新：环形（今天可用变多）+ 柱子（被借那天变矮）+ 总量，并播生长动画
        refreshAll(playAnimation = true)
    }

    /**
     * 借出失败的原因说明。
     *
     * `TimeBankManager.borrow` 是「先算后写」的单一入口，返回 false 的可能只有三类：
     * 分钟数越界（本页已夹在 1..60，理论上到不了）、日期越界（同样不会）、超出预支上限。
     * 所以这里按**最可能**的那个给用户解释，并带上具体数字，方便用户自己决定借多少。
     */
    private fun toastBorrowFailure() {
        val limit = TimeBankManager.borrowLimitMin()
        val outstanding = TimeBankManager.outstandingBorrowMin()
        if (TimeBankManager.remainingBorrowCapacityMin() <= 0) {
            MsgUtil.toast(getString(R.string.time_bank_borrow_fail_capacity, limit))
        } else {
            MsgUtil.toast(getString(R.string.time_bank_borrow_fail_limit, limit, outstanding))
        }
    }

    private companion object {
        /** 没有选中任何一天 */
        const val NO_SELECTION = -1

        /** 步长（分钟）：需求原文「−10 / +10 分钟」 */
        const val STEP_MINUTES = 10

        /** 单次可借下限（分钟） */
        const val MIN_BORROW_MINUTES = 1

        /** 单次可借上限（分钟）：需求原文「每次 1~60 分钟」 */
        const val MAX_BORROW_MINUTES = 60

        /** 默认要借的分钟数：与步长一致（先给 10 分钟，想多借按 +10 往上加） */
        const val DEFAULT_BORROW_MINUTES = 10
    }
}
