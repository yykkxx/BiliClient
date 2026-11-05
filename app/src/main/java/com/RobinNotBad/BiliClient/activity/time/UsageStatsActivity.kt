package com.RobinNotBad.BiliClient.activity.time

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.ui.widget.PageUsageBarView
import com.RobinNotBad.BiliClient.ui.widget.UsageChartView
import com.RobinNotBad.BiliClient.util.UsageColorUtil
import com.RobinNotBad.BiliClient.util.UsageLimitManager
import com.RobinNotBad.BiliClient.util.UsageStatsManager

/**
 * 「使用时长统计」页（v5.2.0 需求 2）。
 *
 * ## 这一页要回答的用户问题
 * 用户原话：「包括多日总时长以及七日内使用时长柱状图等，包括在哪个页面停留最久等一系列的
 * 所有使用时长数据」。所以一页里按「摘要 → 趋势 → 明细」由粗到细排：
 * 1. 三个数字卡（今日 / 近 7 天 / 近 30 天）—— 一句话看懂量级；
 * 2. 7 日柱状图 —— 看出趋势与「哪几天超标」；
 * 3. 今日各页面停留排行 —— 看出「时间花在哪」；
 * 4. 两句结论（停留最久页面 / 记录以来最久那天）—— 不用自己算；
 * 5. 口径说明（倒计时等待不计入）—— 避免用户拿这里的数字和系统「屏幕使用时间」对不上时困惑。
 *
 * ## 数据全部现算
 * 没有缓存、没有异步加载：数据量最多 30 条 + 当天若干页面，直接从偏好读出、
 * 当场算完就画。这样「刚看完视频回到统计页」也能立刻看到最新数字，
 * 不需要任何刷新按钮或下拉刷新。
 *
 * ## 开关关闭时的行为
 * 「记录使用时长」关掉后，本页仍可打开（历史数据还在，删数据才叫清空），
 * 但顶部会提示「当前未在记录新的停留」。这比直接藏掉入口更合理：
 * 用户关掉开关后往往还想看看既有数据。
 */
class UsageStatsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage_stats)
        setPageName(getString(R.string.usage_stats_page_name))
        refreshAll()
    }

    /**
     * 一次性把全部数据读出来画上去。
     *
     * 读数只有几次 SharedPreferences 读取 + 几十条文本解析，主线程足够快（远低于 1 帧），
     * 所以不切后台线程——切线程反而要处理「回来时页面已销毁」的竞态。
     */
    private fun refreshAll() {
        showSummaryCards()
        showChart()
        showRank()
        showConclusions()
        showDisabledHint()
    }

    /** 三个数字卡 + 今日分档颜色 */
    private fun showSummaryCards() {
        val todayMs = UsageStatsManager.todayTotalMs()
        val todayValue = findViewById<TextView>(R.id.stat_today_value)
        todayValue.text = UsageLimitManager.formatUsed(todayMs)
        // 今日数字直接用三档色，用户扫一眼就知道当前处于哪一档
        todayValue.setTextColor(UsageColorUtil.colorForUsed(todayMs))

        val weekMs = UsageStatsManager.last7Days().sumOf { it.ms }
        findViewById<TextView>(R.id.stat_week_value).text = UsageLimitManager.formatUsed(weekMs)
        findViewById<TextView>(R.id.stat_month_value).text =
            UsageLimitManager.formatUsed(UsageStatsManager.totalLast30DaysMs())

        val levelHint = findViewById<TextView>(R.id.stat_level_hint)
        levelHint.text = getString(R.string.usage_stats_level_hint) + "（当前：" +
            UsageColorUtil.labelForUsed(this, todayMs) + "）"
        levelHint.setTextColor(UsageColorUtil.colorForUsed(todayMs))
    }

    /** 7 日柱状图（数据在、无数据都画；全 0 时柱子高度为 0，轴标签仍在，不会是一片空白） */
    private fun showChart() {
        val chart = findViewById<UsageChartView>(R.id.stat_chart)
        chart.setDays(UsageStatsManager.last7Days())
    }

    /** 今日页面排行：最多前 8 条；无数据显示空态文案（由 View 自己画） */
    private fun showRank() {
        val rank = findViewById<PageUsageBarView>(R.id.stat_rank)
        val pages = UsageStatsManager.todayPages()
        rank.setItems(if (pages.size > UsageStatsManager.MAX_PAGE_RANK) {
            pages.subList(0, UsageStatsManager.MAX_PAGE_RANK)
        } else {
            pages
        })
    }

    /** 两句结论：今天最久的页面、记录以来最久的一天 */
    private fun showConclusions() {
        val topPageView = findViewById<TextView>(R.id.stat_top_page)
        val topPage = UsageStatsManager.topPageToday()
        if (topPage == null) {
            topPageView.text = getString(R.string.usage_stats_top_page_none)
        } else {
            topPageView.text = getString(
                R.string.usage_stats_top_page,
                topPage,
                UsageLimitManager.formatUsed(UsageStatsManager.topPageTodayMs())
            )
        }

        val busiestView = findViewById<TextView>(R.id.stat_busiest_day)
        val busiest = UsageStatsManager.busiestDay()
        busiestView.text = if (busiest == null) {
            getString(R.string.usage_stats_busiest_day_none)
        } else {
            getString(
                R.string.usage_stats_busiest_day,
                busiest.label,
                UsageLimitManager.formatUsed(busiest.ms)
            )
        }
    }

    /**
     * 开关关闭时在结论行上方追加一行提示。
     *
     * 实现方式：复用 `stat_top_page` 的容器不方便（它是 TextView），
     * 所以直接把提示挂到分档说明那行的颜色/文案上——不新增 View，
     * 避免多一个 id 与布局层级。
     */
    private fun showDisabledHint() {
        if (UsageStatsManager.isEnabled()) return
        val levelHint = findViewById<TextView>(R.id.stat_level_hint)
        levelHint.text = "未开启「记录使用时长」：以下为既有数据，不会记录新的页面停留。\n" + levelHint.text
        levelHint.setTextColor(Color.GRAY)
    }

    override fun onResume() {
        super.onResume()
        // 从别的页面回来（或刚在设置页改了开关）时重算一遍，保证数字不陈旧
        refreshAll()
    }
}
