package com.RobinNotBad.BiliClient.util

import java.time.LocalDate

/**
 * 时间银行（v5.2.0 需求 3）：**每日配额硬限制 + 从未来几天预支时间**。
 *
 * # 朴素模型（用户原话：「不需要什么利息什么的，你就直接最朴素的时间银行」）
 * - 每天第一次打开应用先判定今天是「上学日」还是「休息日」，各自有基础配额；
 * - 配额用完之后进不了主界面，只能选「等一段时间关掉本功能一天」/「借时间」/「退出」；
 * - 「借」= 从**明天 ~ 第 7 天**里挑一天，把它那天的配额挪到今天用；那天相应变少；
 * - 同时未消化的预支总量 ≤ `time_bank_borrow_limit_min`（默认 300 分钟 = 5 小时）；
 * - **没有利息、没有复利、不记流水**：借走就是借走，那天过去就自动消化（不再算欠）。
 *
 * # 存储（§1.3，**不用 JSON**）
 * 唯一一个键 `time_bank_borrows`，格式 `date:n,date:n,...`：
 * ```
 * 2026-11-07:30,2026-11-08:60
 * ```
 * 表示「从 11-07 借走 30 分钟、从 11-08 借走 60 分钟」（这两天的可用时间相应减少）。
 * 日期是 `yyyy-MM-dd`（字典序恰好等于时间序），数值是十进制整数分钟。
 *
 * 解析一律容错：任何一段解析不出来就跳过那一段，整段坏掉就当「没有预支」，
 * **绝不抛异常**（脏数据最多让用户多玩几分钟，不能把 App 挡在门外）。
 *
 * # 已知近似（重要，规格 §3.3 明确允许并已注释）
 * 1. **今日借入 = 全部未消化预支**：记录里没有「哪一天借的」字段（§1.3 格式固定为 `date:n`），
 *    所以「今天预支进来的分钟数」只能取「当前未消化预支总量」——
 *    也就是「借了多少，今天就多多少」。用户不接着用的话，这个额度会一直保留到源日期当天才归零。
 *    这是与 §1.3 格式相容的唯一朴素解法；严格控制的是**总量上限**（预支 ≤ 上限），不会无限膨胀。
 * 2. **未来某天用今天类型推断配额**：未来 7 天的上学/休息属性还没判定（要当天才问），
 *    所以 `futureDays()` 按「今天是什么日子，未来就按同一配额」近似 —— 规格 §3.3 明确允许。
 *
 * # 线程与 UI
 * 本类只做「读 / 写 / 判定」，**不持有线程、不持有 Context、不弹任何 UI**。
 * 数据量极小（一条几十字符的字符串），调用方直接在 UI 线程调即可。
 */
object TimeBankManager {

    // ---------------- 键（字符串与 §1.2 逐字一致；值同时登记在 TimeMgmtPrefs） ----------------

    /** 时间银行总开关（Bool，默认 false） */
    const val PREF_ENABLE = "time_bank_enable"

    /** 上学日每日配额（Int 分钟，默认 120） */
    const val PREF_SCHOOL_QUOTA = "time_bank_school_quota_min"

    /** 休息日每日配额（Int 分钟，默认 300） */
    const val PREF_REST_QUOTA = "time_bank_rest_quota_min"

    /** 同时预支总量上限（Int 分钟，默认 300） */
    const val PREF_BORROW_LIMIT = "time_bank_borrow_limit_min"

    /** 超配额后等待多少秒可关闭本功能一天（Int 秒，默认 120） */
    const val PREF_WAIT_CLOSE_SEC = "time_bank_wait_close_sec"

    /** 当天类型：`school` / `rest`，空 = 尚未判定（String） */
    const val PREF_DAY_TYPE = "time_bank_day_type"

    /** `PREF_DAY_TYPE` 属于哪一天（`yyyy-MM-dd`；不等于今天即失效） */
    const val PREF_DAY_KEY = "time_bank_day_key"

    /** 已「关闭一天」的日期（等于今天则今天不限制） */
    const val PREF_DISABLED_DAY = "time_bank_disabled_day"

    /** 预支记录，格式 `date:n,date:n,...` */
    const val PREF_BORROWS = "time_bank_borrows"

    /** 当天类型取值：上学日 */
    const val DAY_SCHOOL = "school"

    /** 当天类型取值：休息日 */
    const val DAY_REST = "rest"

    // ---------------- 默认值与硬约束 ----------------

    /** 上学日默认配额（分钟） */
    const val DEFAULT_SCHOOL_QUOTA_MIN = 120

    /** 休息日默认配额（分钟） */
    const val DEFAULT_REST_QUOTA_MIN = 300

    /** 预支总量默认上限（分钟）= 5 小时（需求原文） */
    const val DEFAULT_BORROW_LIMIT_MIN = 300

    /** 超配额后等待的默认秒数 */
    const val DEFAULT_WAIT_CLOSE_SEC = 120

    /** 单次预支上限（分钟）：需求原文「每次 1~60 分钟，可多次」 */
    const val MAX_BORROW_ONCE_MIN = 60

    /** 可预支的天数窗口：明天 ~ 未来第 7 天（含），共 7 天 */
    const val FUTURE_DAYS = 7

    /** 配额上限（分钟）：1440 = 一整天。设置页输入异常（0 / 负数 / 天文数字）时按它夹紧 */
    private const val MAX_QUOTA_MIN = 1440

    /** 预支总量上限的上限（分钟）：7140 = 7 天 × 1440 的绝对上限，防呆用 */
    private const val MAX_BORROW_LIMIT_MIN = 7140

    /** 等待秒数上限：1 小时，防呆用 */
    private const val MAX_WAIT_CLOSE_SEC = 3600

    private const val MS_PER_MINUTE = 60_000L

    /** 7 个中文星期名（下标 = `LocalDate.dayOfWeek.value - 1`，即周一…周日）；静态分配，不在绘制路径上 */
    private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    // ---------------- 日期与当天判定 ----------------

    /** 今天是否已判定上学/休息日（只看「判定过没有」，与功能开关无关） */
    @JvmStatic
    fun hasDayTypeToday(): Boolean = todayDayType() != null

    /** 今天的类型；未判定（或记录属于别的日期）返回 null */
    @JvmStatic
    fun todayDayType(): String? {
        val key = SharedPreferencesUtil.getString(PREF_DAY_KEY, "") ?: ""
        if (key != todayKey()) return null
        val type = SharedPreferencesUtil.getString(PREF_DAY_TYPE, "") ?: ""
        return if (type == DAY_SCHOOL || type == DAY_REST) type else null
    }

    /** 写入今天的类型（只接受 `school` / `rest`，其它值忽略，避免脏值让判定永远失效） */
    @JvmStatic
    fun setDayTypeToday(type: String) {
        if (type != DAY_SCHOOL && type != DAY_REST) return
        SharedPreferencesUtil.putString(PREF_DAY_TYPE, type)
        SharedPreferencesUtil.putString(PREF_DAY_KEY, todayKey())
    }

    /** 今天是否已「关闭功能一天」（用户等待后放行的那条路径） */
    @JvmStatic
    fun isDisabledToday(): Boolean =
        (SharedPreferencesUtil.getString(PREF_DISABLED_DAY, "") ?: "") == todayKey()

    /** 关闭今天（写 `time_bank_disabled_day = 今天`） */
    @JvmStatic
    fun disableToday() {
        SharedPreferencesUtil.putString(PREF_DISABLED_DAY, todayKey())
    }

    // ---------------- 配额 ----------------

    /**
     * 今天的基础配额（分钟）。
     * 尚未判定上学/休息日时按**上学日**算 —— 保证「弹窗那一刻」就有一个可用值，
     * 避免用户在弹窗还没答时看到 0 分钟。
     */
    @JvmStatic
    fun baseQuotaMinToday(): Int {
        val rest = todayDayType() == DAY_REST
        return if (rest) restQuotaMin() else schoolQuotaMin()
    }

    /** 上学日配额（分钟）：已夹到合法区间，设置页输入异常时不会让页面算出负数 */
    @JvmStatic
    fun schoolQuotaMin(): Int =
        SharedPreferencesUtil.getInt(PREF_SCHOOL_QUOTA, DEFAULT_SCHOOL_QUOTA_MIN).coerceIn(0, MAX_QUOTA_MIN)

    /** 休息日配额（分钟）：已夹到合法区间 */
    @JvmStatic
    fun restQuotaMin(): Int =
        SharedPreferencesUtil.getInt(PREF_REST_QUOTA, DEFAULT_REST_QUOTA_MIN).coerceIn(0, MAX_QUOTA_MIN)

    /**
     * 今天从未来借入的总分钟数。
     *
     * 说明见类注释「已知近似 1」：记录里没有「借的日期」字段，所以这里取**全部未消化预支**。
     */
    @JvmStatic
    fun borrowedInTodayMin(): Int = outstandingBorrowMin()

    /**
     * 今天可用总分钟 = 基础配额 + 借入。
     *
     * 上限是「基础配额上限 + 预支上限」而不是单个配额上限 —— 否则用户借了 300 分钟、
     * 基础配额又是 1440（一整天）时，加起来会被夹回去，借的时间等于白借。
     */
    @JvmStatic
    fun availableQuotaMinToday(): Int {
        val sum = baseQuotaMinToday().toLong() + borrowedInTodayMin().toLong()
        return sum.coerceIn(0L, (MAX_QUOTA_MIN + MAX_BORROW_LIMIT_MIN).toLong()).toInt()
    }

    /** 今天已用分钟（唯一真源是 [UsageLimitManager.todayUsedMs]，这里只做换算，不另记一份） */
    @JvmStatic
    fun usedMinToday(): Int = (UsageLimitManager.todayUsedMs() / MS_PER_MINUTE).toInt()

    /** 今天剩余分钟（可为负；负数表示已超出多少分钟） */
    @JvmStatic
    fun remainingMinToday(): Int = availableQuotaMinToday() - usedMinToday()

    /**
     * 今天是否已超配额（剩余 ≤ 0 即「用完了」，含正好用满）。
     *
     * 不看开关：调用方自己判断 `time_bank_enable` —— 这样本函数在任何上下文都能直接问「配额够不够」。
     */
    @JvmStatic
    fun isExhaustedToday(): Boolean = remainingMinToday() <= 0

    // ---------------- 预支 ----------------

    /** 当前未消化预支总量（分钟）：只累加日期 > 今天的记录（日期 ≤ 今天视为那天已过去、不再欠） */
    @JvmStatic
    fun outstandingBorrowMin(): Int {
        val total = parseBorrows(rawBorrows()).values.sumOf { it.toLong() }
        return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** 还能再借多少分钟 = 上限 − 未消化预支（不小于 0） */
    @JvmStatic
    fun remainingBorrowCapacityMin(): Int =
        (borrowLimitMin().toLong() - outstandingBorrowMin().toLong()).coerceAtLeast(0L).toInt()

    /** 同时预支总量上限（分钟） */
    @JvmStatic
    fun borrowLimitMin(): Int =
        SharedPreferencesUtil.getInt(PREF_BORROW_LIMIT, DEFAULT_BORROW_LIMIT_MIN).coerceIn(1, MAX_BORROW_LIMIT_MIN)

    /** 超配额后的等待秒数（`CountdownActivity` 用它启动倒计时） */
    @JvmStatic
    fun waitCloseSec(): Int =
        SharedPreferencesUtil.getInt(PREF_WAIT_CLOSE_SEC, DEFAULT_WAIT_CLOSE_SEC).coerceIn(1, MAX_WAIT_CLOSE_SEC)

    /**
     * 未来 7 天每条的可用配额 —— 供时间银行图表画柱子（下标 0 = 明天，6 = 第 7 天）。
     *
     * `availableMin = baseMin − 该天被预支走的分钟数`（非负；脏数据预支超过基础配额时按 0 显示，
     * 避免出现「负高度柱子」）。`baseMin` 用今天类型推断（见类注释「已知近似 2」）。
     */
    @JvmStatic
    fun futureDays(): List<DayQuota> {
        val today = LocalDate.now()
        val borrows = parseBorrows(rawBorrows())
        val base = baseQuotaMinToday()
        val list = ArrayList<DayQuota>(FUTURE_DAYS)
        for (offset in 1..FUTURE_DAYS) {
            val date = today.plusDays(offset.toLong())
            val key = date.toString()
            val borrowedOut = borrows[key] ?: 0
            val available = (base - borrowedOut).coerceAtLeast(0)
            list.add(DayQuota(key, labelOf(date, today), base, borrowedOut, available))
        }
        return list
    }

    /**
     * 从 [date]（`yyyy-MM-dd`）预支 [minutes] 分钟；成功写回并返回 true。
     *
     * 失败一律返回 false 且**不写入**（先算后写，避免「写进去了才发现超限」）：
     * - 分钟数不在 `1..60`（需求：每次 1~60 分钟）；
     * - 日期不合法、不在「明天 ~ 未来第 7 天」；
     * - 累加后未消化总量会超过上限。
     */
    @JvmStatic
    fun borrow(date: String, minutes: Int): Boolean {
        if (minutes <= 0 || minutes > MAX_BORROW_ONCE_MIN) return false
        val today = LocalDate.now()
        val target = parseDate(date) ?: return false
        val offset = target.toEpochDay() - today.toEpochDay()
        if (offset < 1L || offset > FUTURE_DAYS.toLong()) return false

        val borrows = parseBorrows(rawBorrows())
        val outstanding = borrows.values.sumOf { it.toLong() }
        if (outstanding + minutes.toLong() > borrowLimitMin().toLong()) return false

        borrows[date] = ((borrows[date] ?: 0).toLong() + minutes.toLong())
            .coerceIn(0L, MAX_BORROW_LIMIT_MIN.toLong()).toInt()
        SharedPreferencesUtil.putString(PREF_BORROWS, serializeBorrows(borrows))
        return true
    }

    /** 清除全部预支记录（设置页「重置时间银行」用） */
    @JvmStatic
    fun resetBorrows() {
        SharedPreferencesUtil.putString(PREF_BORROWS, "")
    }

    // ---------------- 解析 / 序列化（纯函数，可单测；解析失败一律降级，不抛异常） ----------------

    /**
     * `date:n,date:n,...` → 有序 `Map<日期, 分钟>`。
     *
     * 容错规则（任一命中就跳过该条，不影响其它条目）：
     * - 空串 / 空段；
     * - 没有 `:` 或 `:` 在首/尾；
     * - 日期不是合法 `yyyy-MM-dd`；
     * - 分钟数不是正整数；
     * - 日期 ≤ 今天（那天已经过去 → 自动消化，§3.1「跨天自动消化」）。
     *
     * 同一天出现多条时按累加合并（防御历史脏数据）。
     */
    private fun parseBorrows(raw: String): LinkedHashMap<String, Int> {
        val result = LinkedHashMap<String, Int>()
        val today = LocalDate.now().toString()
        for (item in raw.split(',')) {
            val entry = parseBorrowEntry(item, today) ?: continue
            result[entry.first] = (result[entry.first]?.toLong() ?: 0L).plus(entry.second.toLong())
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        return result
    }

    /**
     * 解析单条 `date:n`；任何一条不合法都返回 null（由 [parseBorrows] 跳过）。
     *
     * 拆成独立函数是为了让「哪几种坏数据要跳过」一眼能看完，也避免主循环里堆一串 continue。
     */
    private fun parseBorrowEntry(item: String, today: String): Pair<String, Int>? {
        val entry = item.trim()
        if (entry.isEmpty()) return null
        val colon = entry.indexOf(':')
        if (colon <= 0 || colon >= entry.length - 1) return null
        val date = entry.substring(0, colon).trim()
        if (parseDate(date) == null) return null
        // 字典序与时间序一致（定长 yyyy-MM-dd），所以直接比字符串即可
        if (date <= today) return null
        val minutes = entry.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (minutes <= 0) return null
        return date to minutes
    }

    /** `Map<日期, 分钟>` → `date:n,date:n,...`（按日期升序，便于阅读与稳定 diff） */
    private fun serializeBorrows(borrows: Map<String, Int>): String {
        if (borrows.isEmpty()) return ""
        return borrows.entries.sortedBy { it.key }.joinToString(",") { entry -> "${entry.key}:${entry.value}" }
    }

    /** 严格解析 `yyyy-MM-dd`；不合法返回 null（不抛异常） */
    private fun parseDate(text: String): LocalDate? {
        if (text.length != 10 || text[4] != '-' || text[7] != '-') return null
        for (index in 0..9) {
            if (index == 4 || index == 7) continue
            if (!text[index].isDigit()) return null
        }
        return try {
            LocalDate.parse(text)
        } catch (ignored: Exception) {
            // 形如 2026-02-31 的日期：当成坏数据跳过（这里不能抛异常）
            null
        }
    }

    /** 柱子下方的短标签：明天用「明天」，其余用「周X」 */
    private fun labelOf(date: LocalDate, today: LocalDate): String {
        if (date.toEpochDay() - today.toEpochDay() == 1L) return "明天"
        return WEEKDAY_NAMES[date.dayOfWeek.value - 1]
    }

    private fun rawBorrows(): String = SharedPreferencesUtil.getString(PREF_BORROWS, "") ?: ""

    private fun todayKey(): String = LocalDate.now().toString()
}

/**
 * 未来某一天的时间银行配额（[TimeBankManager.futureDays] 的返回值，图表直接用它画柱子）。
 *
 * @param date 日期 `yyyy-MM-dd`
 * @param label 柱子下方短标签（「明天」/「周三」）
 * @param baseMin 该天基础配额（分钟）
 * @param borrowedOutMin 该天被预支走的分钟数
 * @param availableMin 该天剩余可用分钟（`baseMin - borrowedOutMin`，非负）
 */
data class DayQuota(
    val date: String,
    val label: String,
    val baseMin: Int,
    val borrowedOutMin: Int,
    val availableMin: Int
)
