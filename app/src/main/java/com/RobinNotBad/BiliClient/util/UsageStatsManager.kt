package com.RobinNotBad.BiliClient.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 使用时长统计的**读写与聚合**（v5.2.0 需求 2）。
 *
 * ## 存什么、为什么这样存
 * 两个偏好键（格式见 `notes/spec_time_mgmt_20261105.md` §1.3）：
 * - `usage_page_stats`：`yyyy-MM-dd|页面名:毫秒;页面名:毫秒;...` —— **只保留当天**的分页停留；
 * - `usage_history`：`yyyy-MM-dd:毫秒;...` —— 最近 30 天的每日总时长。
 *
 * 为什么不用 JSON：仓库的 `check_new_code.sh` 硬禁 `org.json.` 新增行（历史审计点名的反模式），
 * 且这两份数据是「扁平的日期/页名 → 一个整数」，用 Gson 或手写 JSON 都属于过度设计。
 * 分列文本可读、可 diff、解析失败也只是退化成空数据，不会有「一处格式错就整段作废」的风险。
 *
 * ## 今日总时长的单一真源
 * 「今天用了多久」**只有** [UsageLimitManager.todayUsedMs] 一个真源（它管跨天重置与计时累加）。
 * 本对象不另记一份今日总数，[todayTotalMs] 只是转发——否则两份数字迟早对不上
 * （历史上账号态就吃过「两个地方各判一次」的亏）。
 *
 * ## 线程与开销
 * 调用方（`BiliTerminal` 生命周期回调）都在主线程，数据量极小（最多 30 条 + 当天若干页），
 * 直接同步读写偏好即可；不引入协程/线程，避免「退后台时异步还没写完进程就被杀」。
 */
object UsageStatsManager {

    /** 使用时长统计总开关（Bool，默认 true） */
    const val PREF_STATS_ENABLE = "usage_stats_enable"

    /** 单日分页停留（String） */
    const val PREF_PAGE_STATS = "usage_page_stats"

    /** 多日总时长历史（String，保留最近 30 天） */
    const val PREF_HISTORY = "usage_history"

    /** 历史保留天数 */
    const val HISTORY_DAYS = 30

    /** 最近 7 天图表的天数 */
    const val RECENT_DAYS = 7

    /** 统计页最多展示的页面排行条数 */
    const val MAX_PAGE_RANK = 8

    private const val PAGE_SEP = ";"
    private const val FIELD_SEP = ":"
    private const val DAY_SEP = "|"
    private const val MS_PER_MINUTE = 60_000L

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // ------------------------------------------------------------------ 开关

    /** 统计是否开启（默认 true；关闭后不再记录**新的**停留，已有数据保留） */
    @JvmStatic
    fun isEnabled(): Boolean = SharedPreferencesUtil.getBoolean(PREF_STATS_ENABLE, true)

    // ------------------------------------------------------------------ 写入

    /**
     * 记一段页面停留。
     *
     * @param pageName 页面名（`BaseActivity.setPageName` 传进来的那个）；
     *   含 `:` `;` `|` 会被替换成 `_`（否则会破坏分列格式）。
     * @param deltaMs 停留毫秒；<=0 忽略。
     *
     * 同一页面重复调用按**累加**处理。跨天时自动把日期切到今天并清掉旧的分页数据。
     */
    @JvmStatic
    fun addPageUsage(pageName: String, deltaMs: Long) {
        if (!isEnabled() || deltaMs <= 0L) return
        val safeName = sanitizeName(pageName)
        if (safeName.isEmpty()) return

        val today = todayKey()
        val storedDay = storedPageDay()
        // 跨天（或首次记录）：丢掉旧的分页数据，从今天重新开始
        val pages = if (storedDay == today) parsePages(pageBody()) else LinkedHashMap()

        pages[safeName] = (pages[safeName] ?: 0L) + deltaMs
        SharedPreferencesUtil.putString(PREF_PAGE_STATS, today + DAY_SEP + encodePages(pages))
    }

    /**
     * 把今日总时长写进历史（由 `BiliTerminal` 在 1→0 结算完 `UsageLimitManager.addUsage` 后调用）。
     *
     * 与分页统计不同，历史**不受** [isEnabled] 约束：关掉统计只表示「不要新数据」，
     * 而「今天总共用了多久」是 [UsageLimitManager] 已经在维护的事实，
     * 不写进历史会让 7 日图表出现空洞，反而更难理解。清空用 [clear]。
     */
    @JvmStatic
    fun recordDayTotal(todayTotalMs: Long) {
        if (todayTotalMs <= 0L) return
        val today = todayKey()
        val history = parseHistory(rawHistory())
        history[today] = todayTotalMs
        // 只保留最近 30 天，按日期升序裁剪
        val trimmed = history.entries
            .sortedBy { it.key }
            .takeLast(HISTORY_DAYS)
            .associate { it.key to it.value }
        SharedPreferencesUtil.putString(PREF_HISTORY, encodeHistory(trimmed))
    }

    // ------------------------------------------------------------------ 读取

    /** 今天累计（转发 [UsageLimitManager.todayUsedMs]，保持单一真源） */
    @JvmStatic
    fun todayTotalMs(): Long = UsageLimitManager.todayUsedMs()

    /** 今天各页面的停留毫秒，按毫秒降序；`percent` 是占今日总时长的比例 */
    @JvmStatic
    fun todayPages(): List<PageUsage> {
        val today = todayKey()
        if (storedPageDay() != today) return emptyList()
        val pages = parsePages(pageBody())
        val total = pages.values.sum().coerceAtLeast(1L)
        return pages.entries
            .sortedByDescending { it.value }
            .map { PageUsage(it.key, it.value, it.value.toFloat() / total) }
    }

    /**
     * 最近 7 天（含今天）的每日总毫秒，**下标 0 = 6 天前，6 = 今天**。
     *
     * 今天这一格用 [todayTotalMs] 覆盖历史里的旧值——历史是在退后台时才写的，
     * 用户正开着 App 看统计页时，历史里今天的数还停在「上次退后台」的值。
     */
    @JvmStatic
    fun last7Days(): List<DayUsage> {
        val history = parseHistory(rawHistory())
        val today = LocalDate.now()
        return (RECENT_DAYS - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val key = date.format(DATE_FMT)
            val ms = if (offset == 0) todayTotalMs() else (history[key] ?: 0L)
            DayUsage(key, dayLabel(date), ms)
        }
    }

    /** 今天停留最久的页面名；没有数据返回 null */
    @JvmStatic
    fun topPageToday(): String? = todayPages().firstOrNull()?.name

    /** 今天停留最久的页面毫秒；没有数据返回 0 */
    @JvmStatic
    fun topPageTodayMs(): Long = todayPages().firstOrNull()?.ms ?: 0L

    /** 最近 30 天（含今天）的总毫秒（多日总时长） */
    @JvmStatic
    fun totalLast30DaysMs(): Long {
        val history = parseHistory(rawHistory())
        val today = LocalDate.now()
        var sum = 0L
        for (offset in 0 until HISTORY_DAYS) {
            val key = today.minusDays(offset.toLong()).format(DATE_FMT)
            sum += if (offset == 0) todayTotalMs() else (history[key] ?: 0L)
        }
        return sum
    }

    /** 历史里最久的那一天（今天也算）；没有任何数据返回 null */
    @JvmStatic
    fun busiestDay(): DayUsage? {
        val history = parseHistory(rawHistory())
        val today = LocalDate.now()
        val todayKey = today.format(DATE_FMT)
        val candidates = history.filterKeys { it <= todayKey }.map { (key, ms) ->
            val value = if (key == todayKey) maxOf(ms, todayTotalMs()) else ms
            DayUsage(key, dayLabelOf(key), value)
        }
        return candidates.maxByOrNull { it.ms }?.takeIf { it.ms > 0L }
    }

    /** 今天各页面停留里最长的一页的占比（统计页文案用）；无数据返回 0 */
    @JvmStatic
    fun topPageTodayPercent(): Float = todayPages().firstOrNull()?.percent ?: 0f

    // ------------------------------------------------------------------ 清空

    /** 清空统计（设置页「清空使用时长统计」）：只清分页与历史，不动今日总时长 */
    @JvmStatic
    fun clear() {
        SharedPreferencesUtil.removeValue(PREF_PAGE_STATS)
        SharedPreferencesUtil.removeValue(PREF_HISTORY)
        TimeTracking.reset()
    }

    // ------------------------------------------------------------------ 编码/解码

    /** 页面名里的分隔符要替换掉，否则会把一条记录拆成两条 */
    private fun sanitizeName(name: String): String =
        name.replace(DAY_SEP, "_").replace(PAGE_SEP, "_").replace(FIELD_SEP, "_").trim()

    /** 当天键：`yyyy-MM-dd`，设备时区 */
    private fun todayKey(): String = LocalDate.now().format(DATE_FMT)

    /** 读原始分页串，取出其中「日期」部分 */
    private fun storedPageDay(): String {
        val raw = pageBody()
        val idx = raw.indexOf(DAY_SEP)
        return if (idx < 0) "" else raw.substring(0, idx)
    }

    /** 读原始分页串，取出其中「页名:毫秒」部分 */
    private fun pageBody(): String =
        SharedPreferencesUtil.getString(PREF_PAGE_STATS, "") ?: ""

    private fun rawHistory(): String =
        SharedPreferencesUtil.getString(PREF_HISTORY, "") ?: ""

    /**
     * 解析 `页名:毫秒;页名:毫秒`。
     *
     * 容错取向：**任何一条坏数据只丢那一条**，绝不抛异常——统计是锦上添花的功能，
     * 不能因为它让用户进不去 App。坏条目的典型形态是「没有冒号」「毫秒不是数字」「负数」。
     */
    private fun parsePages(body: String): LinkedHashMap<String, Long> {
        val result = LinkedHashMap<String, Long>()
        if (body.isEmpty()) return result
        for (entry in body.split(PAGE_SEP)) {
            parsePageEntry(entry)?.let { (name, value) ->
                result[name] = (result[name] ?: 0L) + value
            }
        }
        return result
    }

    /** 解析单条 `页名:毫秒`；非法（无冒号/毫秒不是数字/负数/名为空）返回 null —— 坏一条丢一条 */
    private fun parsePageEntry(entry: String): Pair<String, Long>? {
        val idx = entry.lastIndexOf(FIELD_SEP)
        if (idx <= 0) return null
        val name = entry.substring(0, idx).trim()
        val value = entry.substring(idx + 1).trim().toLongOrNull()
        if (name.isEmpty() || value == null || value <= 0L) return null
        return name to value
    }

    private fun encodePages(pages: Map<String, Long>): String =
        pages.entries.joinToString(PAGE_SEP) { it.key + FIELD_SEP + it.value }

    /** 解析 `yyyy-MM-dd:毫秒;...`，同样「坏一条丢一条」 */
    private fun parseHistory(raw: String): LinkedHashMap<String, Long> {
        val result = LinkedHashMap<String, Long>()
        if (raw.isEmpty()) return result
        for (entry in raw.split(PAGE_SEP)) {
            parseHistoryEntry(entry)?.let { (date, value) -> result[date] = value }
        }
        return result
    }

    /** 解析单条 `yyyy-MM-dd:毫秒`；非法返回 null */
    private fun parseHistoryEntry(entry: String): Pair<String, Long>? {
        val idx = entry.lastIndexOf(FIELD_SEP)
        if (idx <= 0) return null
        val date = entry.substring(0, idx).trim()
        val value = entry.substring(idx + 1).trim().toLongOrNull()
        if (date.isEmpty() || value == null || value <= 0L) return null
        return date to value
    }

    private fun encodeHistory(history: Map<String, Long>): String =
        history.entries.joinToString(PAGE_SEP) { it.key + FIELD_SEP + it.value }

    // ------------------------------------------------------------------ 标签

    /** 7 日图表的横轴标签：今天显示「今天」，其余显示「周X」 */
    private fun dayLabel(date: LocalDate): String =
        if (date == LocalDate.now()) "今天" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)

    /** 由 `yyyy-MM-dd` 反推标签（历史里最久那天用） */
    private fun dayLabelOf(key: String): String {
        val date = runCatching { LocalDate.parse(key, DATE_FMT) }.getOrNull() ?: return key
        return dayLabel(date)
    }

    /** 分钟数（统计页文案用） */
    @JvmStatic
    fun minutesOf(ms: Long): Long = ms / MS_PER_MINUTE
}

/** 单页停留：页面名、毫秒、占今日总停留的比例（0~1） */
data class PageUsage(val name: String, val ms: Long, val percent: Float)

/** 单日总时长：日期键、展示标签（今天/周X）、毫秒 */
data class DayUsage(val date: String, val label: String, val ms: Long)
