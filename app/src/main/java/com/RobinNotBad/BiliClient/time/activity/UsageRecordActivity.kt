package com.RobinNotBad.BiliClient.time.activity

import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.core.content.ContextCompat

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.TimeFormat
import com.RobinNotBad.BiliClient.time.UsageLog
import com.RobinNotBad.BiliClient.time.UsageTracker
import com.RobinNotBad.BiliClient.time.ui.PageRankBarView
import com.RobinNotBad.BiliClient.time.ui.UsageBarChartView
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.google.android.material.button.MaterialButton

/**
 * 小栏 3：使用记录（7 日 / 全部柱状图、今日页面停留排行）。
 *
 * # 数据源与一条**必须如实告诉用户**的边界
 * [UsageLog.topPages] 的分页数据在逻辑层**只保留当天**（见 UsageLog 的类注释，
 * 写入时跨天就丢掉旧桶）。所以「页面停留排行」永远是**今天**的数据，
 * 切换「7 天 / 全部」时它不会跟着变。
 *
 * 用户的原始需求写的是「在哪个页面停留最多」，没有要求按区间看。
 * 所以本页的做法是：**图表按区间切换，排行只显示今天并且标题明确写「今天的页面停留」**。
 * 硬写成「最近 7 天页面排行」就是骗用户 —— 那是 Lead 也确认过的口径。
 *
 * # 为什么所有数据现读现算，不做异步
 * 最多 60 条日期 + 当天若干页面，全部是 SharedPreferences 读取 + 字符串解析，
 * 远低于一帧的时间。切后台线程反而要处理「回来时页面已销毁」的竞态，
 * 得不偿失（旧 UsageStatsActivity 也是这个判断，被验证是够快的）。
 *
 * # 清空记录为什么带二次确认
 * 清空是不可逆的。两击确认（第一击提示、第二击执行）与设置主页「清空登录数据」的
 * 既有交互一致，用户不会误点一下丢掉几周数据。
 * 注意：清空**不动今日已用**（[UsageLog.clearAll] 的实现如此），否则用户能靠它白嫖配额。
 */
class UsageRecordActivity : BaseActivity() {

    private var chartView: UsageBarChartView? = null
    private var rankView: PageRankBarView? = null
    private var chartNoneView: TextView? = null
    private var rankNoneView: TextView? = null
    private var todayValueView: TextView? = null
    private var rangeValueView: TextView? = null
    private var maxValueView: TextView? = null
    private var rangeLabelView: TextView? = null
    private var range7Button: MaterialButton? = null
    private var rangeAllButton: MaterialButton? = null

    /** 当前是否「全部」区间（false = 最近 7 天） */
    private var showAll = false

    /** 清空记录的二次确认计数：第一击提示，第二击真正清空 */
    private var clearClick = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_record)
        setPageName(getString(R.string.tm2_page_record))

        chartView = findViewById(R.id.tm2_record_chart)
        rankView = findViewById(R.id.tm2_record_rank)
        chartNoneView = findViewById(R.id.tm2_record_chart_none)
        rankNoneView = findViewById(R.id.tm2_record_rank_none)
        todayValueView = findViewById(R.id.tm2_record_today_value)
        rangeValueView = findViewById(R.id.tm2_record_range_value)
        maxValueView = findViewById(R.id.tm2_record_max_value)
        rangeLabelView = findViewById(R.id.tm2_record_range_label)
        range7Button = findViewById(R.id.tm2_record_range_7)
        rangeAllButton = findViewById(R.id.tm2_record_range_all)

        range7Button?.setOnClickListener { switchRange(false) }
        rangeAllButton?.setOnClickListener { switchRange(true) }
        findViewById<View>(R.id.tm2_record_clear)?.setOnClickListener { onClearClicked() }

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 使用时长一直在走（本页停留本身也在计），回前台重读一遍，数字才不会停在旧值
        refreshAll()
    }

    // ==========================================================================================
    // 区间切换
    // ==========================================================================================

    private fun switchRange(all: Boolean) {
        if (showAll == all) return
        showAll = all
        refreshAll()
    }

    /**
     * 两个区间按钮的「选中/未选中」视觉：选中的那个用强调色、未选中的降透明度。
     *
     * 注意这里的绿色**不是**三档语义的一档（三档讲的是「今天用了多久」），
     * 只是本 App 既有的强调色（`tm2_level_green` 同时也是主题的可用强调色）。
     * 真正需要三档语义的地方（柱状图今天那根、今日已用数字）走 TimeFormat，见 renderSummary。
     */
    private fun applyRangeStyle() {
        val activeColor = ContextCompat.getColor(this, R.color.tm2_level_green)
        val inactiveColor = ContextCompat.getColor(this, R.color.tm2_text_secondary)
        range7Button?.setTextColor(if (showAll) inactiveColor else activeColor)
        rangeAllButton?.setTextColor(if (showAll) activeColor else inactiveColor)
        range7Button?.alpha = if (showAll) HALF_ALPHA else 1f
        rangeAllButton?.alpha = if (showAll) 1f else HALF_ALPHA
    }

    // ==========================================================================================
    // 刷新
    // ==========================================================================================

    private fun refreshAll() {
        applyRangeStyle()
        val source = if (showAll) UsageLog.allDays() else UsageLog.last7Days()
        renderChart(source)
        renderSummary(source)
        renderRank()
    }

    /**
     * 柱状图。
     *
     * 轴标签口径：7 天视图用 `MM-dd`，全部视图（可能 60 根柱子）用 `dd` 只留日 ——
     * 60 根柱子挤在手表宽度里，`MM-dd` 会全部重叠成一团黑。选中具体某天时用户不需要
     * 从图上精确读日期（他没有点选行为），只需要看趋势形状，所以短标签是正确取舍。
     */
    private fun renderChart(source: List<Pair<String, Long>>) {
        val chart = chartView ?: return
        chartNoneView?.visibility = if (source.isEmpty()) View.VISIBLE else View.GONE
        chart.setData(source, { date, _ -> labelFor(date) }, highlightToday = !showAll)
    }

    /** 轴标签：7 天视图 `MM-dd`，全部视图 `dd`（理由见 [renderChart]） */
    private fun labelFor(date: String): String {
        return if (showAll) date.takeLast(2) else date.takeLast(5)
    }

    /**
     * 三个摘要：今日已用（带三档色）/ 区间合计 / 单日最高。
     *
     * 「今日已用」的取数走 [UsageLog.todayTotalMs]（= [DailyPlan.usedMsToday]，
     * 逻辑层保证单一真源），而不是求和 source —— 全部视图里今天可能不在最后，
     * 求和会把历史某天的数字混进来。
     */
    private fun renderSummary(source: List<Pair<String, Long>>) {
        val todayMs = DailyPlan.usedMsToday()
        todayValueView?.text = TimeFormat.duration(todayMs)
        todayValueView?.setTextColor(TimeFormat.levelColor(this, DailyPlan.levelOf(todayMs)))

        val totalMs = source.sumOf { it.second }
        rangeValueView?.text = if (source.isEmpty()) getString(R.string.tm2_unknown) else TimeFormat.duration(totalMs)
        rangeLabelView?.text = if (showAll) {
            getString(R.string.tm2_record_summary_range) + "（全部）"
        } else {
            getString(R.string.tm2_record_summary_range) + "（7 天）"
        }

        val maxMs = source.maxOfOrNull { it.second } ?: 0L
        maxValueView?.text = if (maxMs <= 0L) getString(R.string.tm2_unknown) else TimeFormat.duration(maxMs)
    }

    /**
     * 页面停留排行。
     *
     * 数据源固定是 [UsageLog.topPages]（只保留当天，见类注释的边界说明），
     * 取 [UsageLog.DEFAULT_PAGE_LIMIT] 条。没有记录时把 View 藏起来并显示空态文案，
     * 而不是让自绘 View 画一句英文/空白。
     */
    private fun renderRank() {
        val pages = UsageLog.topPages(UsageLog.RECENT_DAYS, UsageLog.DEFAULT_PAGE_LIMIT)
        val rank = rankView ?: return
        if (pages.isEmpty()) {
            rank.visibility = View.GONE
            rankNoneView?.visibility = View.VISIBLE
            return
        }
        rank.visibility = View.VISIBLE
        rankNoneView?.visibility = View.GONE
        rank.setItems(pages)
    }

    // ==========================================================================================
    // 清空记录（两击确认）
    // ==========================================================================================

    /**
     * 清空使用记录。
     *
     * 两击确认的理由见类注释。清空只影响**历史与页面停留**，
     * [UsageLog.clearAll] 刻意不动 `USED_MS`（今日已用是配额判定的输入，不是「记录」）。
     * 清完之后本页刷新一次，让用户立刻看到图表变空 —— 比弹个 toast 更有说服力。
     */
    private fun onClearClicked() {
        if (clearClick == 0) {
            clearClick = 1
            MsgUtil.showMsg(getString(R.string.tm2_record_clear_confirm))
            return
        }
        clearClick = 0
        UsageLog.clearAll()
        MsgUtil.showMsg(getString(R.string.tm2_record_cleared))
        refreshAll()
    }

    override fun onDestroy() {
        // 结算一次：本页停留也属于使用时长，离开时把它落进当天累计（再读出来才准）
        UsageTracker.flush()
        super.onDestroy()
    }

    private companion object {
        /** 未选中按钮的透明度 */
        const val HALF_ALPHA = 0.55f
    }
}
