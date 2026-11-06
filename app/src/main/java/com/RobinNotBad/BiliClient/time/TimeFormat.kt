package com.RobinNotBad.BiliClient.time

import android.content.Context
import androidx.core.content.ContextCompat
import com.RobinNotBad.BiliClient.R
import java.util.Locale

/**
 * 时长/时间的**唯一格式化入口**（v5.2.1 时间管理重写）。
 *
 * # 为什么要单独一个文件
 * 时间管理有 7 个页面要显示「今天已用多少」「还剩多少」「某天用了多少」，
 * 每处各写一遍 `String.format` 必然会出现「1小时5分钟」「1 小时 05 分」「65 分钟」三种口径，
 * 用户看到同一个数字在不同页面长得不一样，会怀疑数据本身有问题。
 * 所以全部格式化只在这里做，页面只负责把数字传进来。
 *
 * # 口径（从用户角度定的，改动前先想清楚）
 * - **不足 1 分钟**：显示「不到 1 分钟」而不是「0 分钟」——用户刚打开就看到 0 会觉得没在记。
 * - **1 分钟 ~ 1 小时**：`23 分钟`（不补零，不显示秒）。
 * - **1 小时以上**：`2 小时 5 分钟`；整小时只显示 `2 小时`（不显示「0 分钟」这种冗余）。
 * - **超过 99 小时**：只可能出现在「全部时间」统计里，显示 `123 小时 40 分钟`（不缩写，
 *   因为这是用户自己攒出来的累计值，缩写成「123h」反而不像给人看的）。
 *
 * 负数一律按 0 处理：配额被借成负数时页面上不该出现「-3 小时」这种吓人的东西，
 * 「还剩 0 分钟」才是正确表达（[remainingText] 会额外区分「已用完」文案）。
 */
object TimeFormat {

    private const val MS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L

    /**
     * 把毫秒格式化成给人看的时长。规则见类注释。
     *
     * @param ms 时长（负数按 0 处理）
     * @return 如「不到 1 分钟」「23 分钟」「2 小时」「2 小时 5 分钟」
     */
    @JvmStatic
    fun duration(ms: Long): String {
        val safeMs = if (ms < 0L) 0L else ms
        val totalMinutes = safeMs / MS_PER_MINUTE
        if (totalMinutes <= 0L) return "不到 1 分钟"
        if (totalMinutes < MINUTES_PER_HOUR) return "$totalMinutes 分钟"
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        return if (minutes == 0L) "$hours 小时" else "$hours 小时 $minutes 分钟"
    }

    /**
     * 秒数格式化成 `mm:ss`（倒计时页专用）。
     *
     * 倒计时页是整个需求里唯一「用户盯着数字变小」的地方，所以这里必须**两位补零**：
     * `01:05` 比 `1:5` 更像个倒计时，也不会因为位数变化让居中数字左右跳。
     * 超过 1 小时的情况（`toggleOffWaitSec` 上限 3600 秒）退化为 `60:00`，
     * 不引入 `hh:` 段——本功能的倒计时最长只有几分钟，多一段反而占宽度。
     */
    @JvmStatic
    fun countdown(seconds: Int): String {
        val safe = if (seconds < 0) 0 else seconds
        return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    /**
     * 三档颜色（绿 / 黄 / 红）的资源 id。
     *
     * 色值**不跟随主题**：这三档是**语义色**（绿=安心、红=该停了），
     * 如果跟着主题变成「粉色警告」就失去意义了。所以这里返回的是
     * `colors_time2.xml` 里固定的语义色，而不是 `?attr/colorPrimary`。
     *
     * @param level [DailyPlan.levelOf] 的结果（0/1/2）
     */
    @JvmStatic
    fun levelColorRes(level: Int): Int = when (level) {
        DailyPlan.LEVEL_RED -> R.color.tm2_level_red
        DailyPlan.LEVEL_YELLOW -> R.color.tm2_level_yellow
        else -> R.color.tm2_level_green
    }

    /** 三档颜色直接取色值（`TextView.setTextColor` 用这个）。 */
    @JvmStatic
    fun levelColor(context: Context, level: Int): Int =
        ContextCompat.getColor(context, levelColorRes(level))

    /** 三档的中文说法（设置页的图例、统计页的图注共用）。 */
    @JvmStatic
    fun levelName(level: Int): String = when (level) {
        DailyPlan.LEVEL_RED -> "红色（1 小时以上）"
        DailyPlan.LEVEL_YELLOW -> "黄色（30~60 分钟）"
        else -> "绿色（30 分钟以内）"
    }

    /**
     * 剩余配额的文案（启动页与拦截页共用）。
     *
     * 三种情形分开，因为它们传达的信息完全不同：
     * - 休息日：`休息日 · 不限时长`（告诉用户"别担心，今天随便用"）
     * - 已用完：`今日配额已用完`（这是拦截页的入口语气）
     * - 还有剩余：`今日还剩 1 小时 20 分钟`
     *
     * 不在这里读 Context 的字符串资源，是为了让文案集中在一个文件里好维护；
     * 页面拿到 String 后直接 setText 即可。
     */
    @JvmStatic
    fun remainingText(quotaMinutes: Int, remainingMs: Long): String {
        if (quotaMinutes == DailyPlan.QUOTA_UNLIMITED) return "休息日 · 不限时长"
        if (remainingMs <= 0L) return "今日配额已用完"
        return "今日还剩 " + duration(remainingMs)
    }
}
