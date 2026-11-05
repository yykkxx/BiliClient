package com.RobinNotBad.BiliClient.util

import android.content.Context

import com.RobinNotBad.BiliClient.R

/**
 * 「时间管理」套件的**使用时长分档颜色**（需求 1 的颜色规则）。
 *
 * ## 规则（用户需求原文）
 * - 0 ~ 30 分钟 → **绿色**
 * - 30 分钟 ~ 1 小时 → **黄色**
 * - 1 小时以上 → **红色**
 *
 * ## 为什么单独一个工具对象
 * 这套颜色有三处调用方：倒计时独立页（`CountdownActivity`）、使用时长统计页
 * （`UsageStatsActivity` 的柱状图与今日柱）、以及将来可能的其它提示。
 * 如果各自写一份 `if/else`，改阈值或改色值就要改三处、还容易改漏一处。
 * 集中在这里，阈值与色值都是**单一真源**。
 *
 * ## 为什么色值不写进 `colors.xml`
 * 这三档颜色是「语义色」（安全 / 提醒 / 超限），不是主题色：它们**不能**跟随
 * 主题色调（`ThemeUtil`）变化，否则在粉色主题下「红色=超限」可能和「粉色=主题」糊在一起，
 * 用户就看不出告警了。所以把它们作为常量固定在这里，而不是放进可被主题覆盖的资源表。
 * 需要 XML 引用的地方（进度条底色等）用独立的 `colors_countdown.xml`，与主色板隔离。
 */
object UsageColorUtil {

    /** 0 ~ 30 分钟：绿色（安全档） */
    const val GREEN = 0xFF4CAF50.toInt()

    /** 30 分钟 ~ 1 小时：黄色（提醒档） */
    const val YELLOW = 0xFFFFC107.toInt()

    /** 1 小时以上：红色（超限档） */
    const val RED = 0xFFF44336.toInt()

    /** 绿 → 黄 的分界：30 分钟 */
    const val THRESHOLD_30_MIN_MS = 30L * 60L * 1000L

    /** 黄 → 红 的分界：60 分钟 */
    const val THRESHOLD_60_MIN_MS = 60L * 60L * 1000L

    /**
     * 按今日已用毫秒返回三档颜色（ARGB Int）。
     *
     * 边界取向（刻意选择）：
     * - **正好 30 分钟算黄色**（`>= THRESHOLD_30_MIN_MS`）——用户看到「刚好半小时」时，
     *   应该已经在被提醒，而不是还显示「一切安全」；
     * - **正好 60 分钟算红色**（`>= THRESHOLD_60_MIN_MS`）——同理。
     * 负数（脏数据）按 0 处理，返回绿色，不抛异常。
     */
    @JvmStatic
    fun colorForUsed(usedMs: Long): Int = when {
        usedMs >= THRESHOLD_60_MIN_MS -> RED
        usedMs >= THRESHOLD_30_MIN_MS -> YELLOW
        else -> GREEN
    }

    /**
     * 分档的中文语义标签，给统计页/图表说明文字用。
     *
     * 用「不足 30 分钟 / 30 分钟到 1 小时 / 超过 1 小时」而不是「绿/黄/红」，
     * 因为无障碍（TalkBack）与色弱用户读不到颜色，文字必须自己说清楚。
     */
    @JvmStatic
    fun labelForUsed(context: Context, usedMs: Long): String {
        val level = when {
            usedMs >= THRESHOLD_60_MIN_MS -> LEVEL_OVER_HOUR
            usedMs >= THRESHOLD_30_MIN_MS -> LEVEL_HALF_TO_HOUR
            else -> LEVEL_UNDER_HALF
        }
        // 三段文案走 strings（不要硬编码中文字面量）：用户可能换语言，
        // 且同一个 View 里「档位」与「颜色说明」两处措辞必须能一起改。
        return context.getString(labelRes(level))
    }

    /** 档位常量（内部用；与 strings 里的 usage_level_* 一一对应） */
    private const val LEVEL_UNDER_HALF = 0
    private const val LEVEL_HALF_TO_HOUR = 1
    private const val LEVEL_OVER_HOUR = 2

    private fun labelRes(level: Int): Int = when (level) {
        LEVEL_OVER_HOUR -> R.string.usage_level_over_hour
        LEVEL_HALF_TO_HOUR -> R.string.usage_level_half_to_hour
        else -> R.string.usage_level_under_half
    }
}
