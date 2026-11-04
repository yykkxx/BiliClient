package com.RobinNotBad.BiliClient.util

import java.time.LocalDate

/**
 * 实验室「每日使用时长限制」：全 App **唯一**的计时与判定入口。
 *
 * # 规则（用户需求原文）
 * - 每次打开应用都要显示「今日已使用时长」（无论本开关是否开启，显示逻辑在启动页）；
 * - 今日累计达到 30 分钟 → 进主界面前多等 5 秒；达到 60 分钟 → 多等 10 秒。
 *
 * # 存储
 * 三个**全新命名**的偏好键，默认全关，升级/降级都零行为变化：
 * - [PREF_LAB_USAGE_LIMIT]（Boolean，默认 false）：实验室开关；
 * - [PREF_USAGE_LIMIT_DAY]（String，`yyyy-MM-dd` 本地日期）：这条累计属于哪一天；
 * - [PREF_USAGE_LIMIT_MS]（Long）：当天累计的前台毫秒数。
 *
 * 跨天不需要任何定时任务：只要「记录的日期 != 今天」，[todayUsedMs] 就返回 0，
 * 下一次 [addUsage] 会把日期与累计一起重写成今天的。
 *
 * 计时由 `BiliTerminal` 的 `registerActivityLifecycleCallbacks` 驱动（见那里注释），
 * 本类只做「读/写/判定/格式化」，不持有任何线程或 Handler。
 */
object UsageLimitManager {

    /** 实验室开关：每日使用时长限制（默认关闭，保证升级零行为变化） */
    const val PREF_LAB_USAGE_LIMIT = "lab_usage_limit"

    /** 累计归属日期（本地时区 `yyyy-MM-dd`） */
    const val PREF_USAGE_LIMIT_DAY = "usage_limit_day"

    /** 当天累计前台毫秒数 */
    const val PREF_USAGE_LIMIT_MS = "usage_limit_ms"

    /** 30 分钟：达到即延时 5 秒 */
    const val LIMIT_30_MIN_MS = 30 * 60 * 1000L

    /** 60 分钟：达到即延时 10 秒 */
    const val LIMIT_60_MIN_MS = 60 * 60 * 1000L

    /** 达到 30 分钟档位的启动延时（秒） */
    const val DELAY_5S = 5

    /** 达到 60 分钟档位的启动延时（秒） */
    const val DELAY_10S = 10

    /** 关闭实验室开关时必须原地等待的秒数（用户要求：关闭要等 30 秒） */
    const val DISABLE_GUARD_SECONDS = 30

    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 60 * 60L

    /** 开关是否开启；未开启时启动页照常显示已用时长，只是不做延时处罚 */
    @JvmStatic
    fun isEnabled(): Boolean = SharedPreferencesUtil.getBoolean(PREF_LAB_USAGE_LIMIT, false)

    /**
     * 今天的累计前台毫秒。记录日期不是今天（含从未记录过）时返回 0 —— 跨天自然重置。
     */
    @JvmStatic
    fun todayUsedMs(): Long {
        val savedDay = SharedPreferencesUtil.getString(PREF_USAGE_LIMIT_DAY, "") ?: ""
        if (savedDay != todayKey()) return 0L
        return SharedPreferencesUtil.getLong(PREF_USAGE_LIMIT_MS, 0L).coerceAtLeast(0L)
    }

    /**
     * 累加一段前台时长。负数/0 直接忽略；跨天则先把累计清零再加。
     * 由 Application 的 Activity 生命周期回调调用，只做偏好读写（apply 语义）。
     */
    @JvmStatic
    fun addUsage(deltaMs: Long) {
        if (deltaMs <= 0L) return
        val today = todayKey()
        // todayUsedMs() 在跨天时已经是 0，等价于「先清零再加」
        val next = todayUsedMs() + deltaMs
        SharedPreferencesUtil.putString(PREF_USAGE_LIMIT_DAY, today)
        SharedPreferencesUtil.putLong(PREF_USAGE_LIMIT_MS, next)
    }

    /** 按已用时长算启动延时：`<30min → 0`，`[30min, 60min) → 5`，`>=60min → 10` */
    @JvmStatic
    fun delaySecondsForUsed(usedMs: Long): Int = when {
        usedMs >= LIMIT_60_MIN_MS -> DELAY_10S
        usedMs >= LIMIT_30_MIN_MS -> DELAY_5S
        else -> 0
    }

    /** 今天的启动延时秒数（未开启开关时由调用方自己决定是否使用） */
    @JvmStatic
    fun todayDelaySeconds(): Int = delaySecondsForUsed(todayUsedMs())

    /**
     * 把毫秒格式化成人看的中文时长，**固定带两级单位**（与需求文案一致）：
     * - 满 1 小时 → 「X 小时 Y 分」；
     * - 不足 1 小时 → 「Y 分 Z 秒」。
     *
     * 秒级向下取整（不足 1 秒的部分不显示）；负数按 0 处理。
     */
    @JvmStatic
    fun formatUsed(ms: Long): String {
        val totalSeconds = if (ms > 0L) ms / MS_PER_SECOND else 0L
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0L) {
            "${hours} 小时 ${minutes} 分"
        } else {
            "${minutes} 分 ${seconds} 秒"
        }
    }

    /** 本地日期键，格式固定 `yyyy-MM-dd`（设备时区变化即视为换了一天，符合直觉） */
    private fun todayKey(): String = LocalDate.now().toString()
}
