package com.RobinNotBad.BiliClient.time

import java.util.Calendar

/**
 * 「今天是什么日子」+ 今日配额 + 今日已用 + 颜色分档 + 等待秒数 + 21:30 判断。
 *
 * # 为什么它同时承担「日子类型」和「已用时长」
 * 这两件事必须一起算才说得通：配额取决于日子类型，而「配额够不够」取决于配额与已用。
 * 拆成两个对象就会出现「两个地方各判一次今天是不是上学日」的历史坑
 * （plan.md 第二十七轮记着账号态正是这么错过的）。
 *
 * # 单一真源
 * - 「今天已用多少毫秒」**只有** [TimePrefs.USED_MS] 一份，[usedMsToday] 是唯一读法；
 *   [UsageLog.todayTotalMs] 与 [UsageTracker] 都转发到这里，**不另记一份**。
 * - 「今天是哪天」只有 [TimePrefs.todayKey] 一个实现。
 *
 * # 时间基准
 * 日期边界一律用 `System.currentTimeMillis()` 派生的本地日历（[TimePrefs]），
 * 不用 UTC：中国用户 UTC+8，用 UTC 判断「今天」会在早上 8 点前算成昨天，
 * 跨天重置与 21:30 判断会整体错位 8 小时。
 *
 * # 跨天
 * 所有读取都先比 `*_DAY == today`，不等就当 0 / 未选择（规格 §5.3「关键实现约束」最后一条）。
 * 不需要任何定时任务：进程被杀、手机放一夜，第二天读出来天然就是 0。
 * [rotateIfDayChanged] 只负责把「昨天的那份数」在切天的那一刻补偿性地归档进
 * [UsageLog]（历史图表需要它），它幂等，重复调用不会重复计数。
 */
object DailyPlan {

    /** 一天有多少毫秒（换算用） */
    const val MS_PER_MINUTE = 60_000L

    /** 日子类型：上学日（有配额） */
    const val DAY_SCHOOL = "school"

    /** 日子类型：休息日（无限制） */
    const val DAY_REST = "rest"

    /** 未知/未选择 */
    const val DAY_NONE = ""

    /**
     * 休息日的配额哨兵值 = [Int.MAX_VALUE] 分钟。
     *
     * 用「极大值」而不是一个具体分钟数（比如 1440）的理由：用户口径是**无限制**，
     * 而 1440 只有 24 小时，遇到跨天没结算的脏数据就会算成「超配额被拦」——
     * 休息日被拦住是最刺眼的 bug。用 Int.MAX_VALUE 后 [quotaMsToday] 约 1.28e14 毫秒
     * （约 4085 年），任何真实使用时长都不可能耗尽它，同时仍是 Long 能装下的值。
     */
    const val QUOTA_UNLIMITED = Int.MAX_VALUE

    /** 内存在上次判定时看到的日期，用于发现「切天了」 */
    @Volatile
    private var lastKnownDay: String = ""

    /** 红色档起点（毫秒）：60 分钟。颜色分档的**上界**固定，不随偏好变化。 */
    private const val RED_FROM_MS = 60L * MS_PER_MINUTE

    // ==========================================================================================
    // 开关 / 日子类型
    // ==========================================================================================

    /** 使用控制总开关（转发 [TimePrefs.isEnabled]，UI 用它可以只依赖本对象） */
    @JvmStatic
    fun isEnabled(): Boolean = TimePrefs.isEnabled()

    /**
     * 今天是否已经选过上学日/休息日。
     *
     * 只看「选过没有」，与总开关无关 —— 这样询问页在任何上下文都能直接问。
     */
    @JvmStatic
    fun hasDayTypeToday(): Boolean = dayType() != DAY_NONE

    /**
     * 今天的日子类型：`"school"` / `"rest"` / `""`（未选择）。
     *
     * 记录日期不是今天就返回 `""` —— 这是「每天重新问一次」的实现方式，
     * 不需要清键、不需要定时器。
     */
    @JvmStatic
    fun dayType(): String {
        if (TimePrefs.getText(TimePrefs.DAY_KEY, "") != TimePrefs.todayKey()) return DAY_NONE
        val type = TimePrefs.getText(TimePrefs.DAY_TYPE, "")
        // 只认两个合法值：脏值（历史手改、旧版本残留）当作「没选」而不是「选了但算不出配额」
        return if (type == DAY_SCHOOL || type == DAY_REST) type else DAY_NONE
    }

    /**
     * 写入今天的日子类型。
     *
     * 只接受 [DAY_SCHOOL] / [DAY_REST]，其它值忽略 —— 写成脏值会让 [dayType] 永远返回空，
     * 于是每次启动都问一遍、且配额按上学日算，用户会觉得「选了没用」。
     *
     * [DAY_KEY] 与 [DAY_TYPE] 在同一个 Editor 里提交，避免「键写了、值没写」的半截状态。
     */
    @JvmStatic
    fun setDayType(type: String) {
        if (type != DAY_SCHOOL && type != DAY_REST) return
        TimePrefs.putDayType(TimePrefs.todayKey(), type)
        lastKnownDay = TimePrefs.todayKey()
    }

    /** 今天是不是休息日（未选择时不算休息日 —— 保守按上学日处理，见 [quotaMinutesToday]） */
    @JvmStatic
    fun isRestDay(): Boolean = dayType() == DAY_REST

    // ==========================================================================================
    // 配额
    // ==========================================================================================

    /**
     * 今天还能用多少分钟。
     *
     * - **休息日** → [QUOTA_UNLIMITED]（无限制）
     * - **上学日** → `SCHOOL_QUOTA_MIN + 今天借入的时间`
     * - **还没选日子** → 按上学日算
     *
     * ## ⚠️ 这里与规格书 §5.5 的字面公式不同（有意为之，理由如下）
     * 规格写「上学日 = 基准 − 今天被借走」。但本套模型里**只能从未来借给今天**，
     * 「今天被借走」按 `TimeBank.borrowedFor` 的定义（某天被**借走**多少）**恒为 0**
     * —— 今天从来不是借出的来源。于是字面公式化简成「恒等于基准」，
     * 借时间就完全帮不到今天的额度，整个时间银行失去意义。
     *
     * 正确的方向是**加**：借入是把未来的额度挪到今天，今天应当变**多**。
     * 「那天变少」发生在**被借的那一天**，由 [TimeBank.capacityMinutes] 负责。
     * 两个方向各算各的，不能拧在一起。
     *
     * ## 为什么「今天借入」取的是「全部未消化的借入」
     * 存储格式 `date:min` 只记**来源日期**，没有「哪天借的」字段，所以无法反推
     * 「具体哪些分钟进了今天」。唯一与格式相容的朴素解法就是：未消化的借入总量
     * = 今天额外获得的额度。v5.2.0 的旧实现也是这个口径（`borrowedInTodayMin`），
     * 且已注释说明这是格式决定的近似。上限由 [TimePrefs.bankTotalLimitMin] 严格兜住，
     * 不存在无限膨胀。
     *
     * ## 为什么「没选日子」按上学日
     * 这是唯一不会放行过多的方向。若按休息日算，用户在询问页上永远不点就能无限用；
     * 按上学日算最坏情况是「多拦一次」，而 [Gate.decide] 本来就会先把他引导去选。
     */
    @JvmStatic
    fun quotaMinutesToday(): Int {
        if (isRestDay()) return QUOTA_UNLIMITED
        val base = TimePrefs.schoolQuotaMin().toLong()
        val borrowedIn = TimeBank.borrowedTotalMinutes().toLong()
        return (base + borrowedIn).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 今天配额的毫秒表示。
     *
     * [QUOTA_UNLIMITED] × 60000 仍在 Long 范围内（约 1.28e14），不会溢出；
     * 负数配额（被借成负）照原样返回，调用方据此判「一打开就该拦」。
     */
    @JvmStatic
    fun quotaMsToday(): Long = quotaMinutesToday().toLong() * MS_PER_MINUTE

    /** 今天的基础配额（不含借出），用于界面展示「基准 120 分钟 / 已借出 30 分钟」 */
    @JvmStatic
    fun baseQuotaMinutesToday(): Int = if (isRestDay()) QUOTA_UNLIMITED else TimePrefs.schoolQuotaMin()

    // ==========================================================================================
    // 已用时长
    // ==========================================================================================

    /**
     * 今天已用的前台毫秒。
     *
     * `USED_DAY != today` 时返回 0：这就是跨天重置。**不要在别处再写一遍这个判断**。
     */
    @JvmStatic
    fun usedMsToday(): Long {
        if (TimePrefs.getText(TimePrefs.USED_DAY, "") != TimePrefs.todayKey()) return 0L
        return TimePrefs.getLong(TimePrefs.USED_MS, 0L).coerceAtLeast(0L)
    }

    /**
     * 累加一段前台时长（[UsageTracker] 是唯一调用方）。
     *
     * 三件事在一个 Editor 里提交：日期、累计、历史日总数 —— 避免出现
     * 「USED_MS 更新了但 LOG_DAYS 还是旧值」的半截状态（7 日图会显示错数）。
     *
     * 为什么不在这里做 [rotateIfDayChanged]：读取路径已经按 `USED_DAY != today` 归零，
     * 写路径只需要「先看今天是多少（可能已是 0）再加」。归档交给 [onForeground]。
     */
    @JvmStatic
    fun addUsage(ms: Long) {
        if (ms <= 0L) return
        val today = TimePrefs.todayKey()
        val next = saturatingAdd(usedMsToday(), ms)
        TimePrefs.putUsage(today, next)
        // 日总数历史与今日累计保持同一个值：历史的作用是「过去几天的柱子」，
        // 今天这一格必须与实时值一致，否则用户开着统计页看到的今天会比头部的数字小。
        UsageLog.addDayTotal(today, next)
    }

    /** 今日累计的上界：约 100 年。防止脏数据/时钟异常把它撑到溢出。 */
    private const val MAX_USED_MS = 100L * 365L * 24L * 60L * MS_PER_MINUTE

    /** 饱和加法：`used + add` 超过 [MAX_USED_MS] 就夹到上界，负数按 0 处理 */
    private fun saturatingAdd(used: Long, add: Long): Long =
        (used + add).coerceIn(0L, MAX_USED_MS)

    /** 今天还剩多少毫秒；配额已用尽时为 0 或负数（负数 = 超出多少） */
    @JvmStatic
    fun remainingMsToday(): Long = quotaMsToday() - usedMsToday()

    /**
     * 今天配额是否已用尽（`剩余 <= 0` 即算用尽，**含正好用满**）。
     *
     * 不看总开关：调用方自己判 —— 这样本函数在任何上下文都能直接回答「配额够不够」。
     * 也不看 bypass：那是「今天放行」的旁路，属于 [Gate] 的职责，不该混进配额计算。
     */
    @JvmStatic
    fun isExhaustedToday(): Boolean = remainingMsToday() <= 0L

    // ==========================================================================================
    // 长按 45 秒解除今天限制
    // ==========================================================================================

    /** 今天是否已被「长按解除」放行 */
    @JvmStatic
    fun isBypassedToday(): Boolean = TimePrefs.getText(TimePrefs.BYPASS_DAY, "") == TimePrefs.todayKey()

    /**
     * 解除今天限制（长按 [TimePrefs.longPressReleaseSec] 秒成功后调用）。
     *
     * 只写日期，不改配额、不清已用时长 —— 语义是「今天不再判」，而不是「今天重新开始」。
     * 次日 [isBypassedToday] 自然返回 false（比的是日期），不需要任何清理逻辑。
     */
    @JvmStatic
    fun bypassToday() = TimePrefs.putText(TimePrefs.BYPASS_DAY, TimePrefs.todayKey())

    // ==========================================================================================
    // 分档 / 等待
    // ==========================================================================================

    /**
     * 使用时长分档：`0` 绿 / `1` 黄 / `2` 红。**边界取等号进下一档**（正好 30 分钟算黄）。
     *
     * 分界：`< noWaitMin` 绿；`[noWaitMin, 60min)` 黄；`>= 60min` 红。
     *
     * 为什么绿/黄分界读偏好 [TimePrefs.noWaitMin] 而红档固定 60 分钟：
     * 「不等待」的上界与「绿色」的上界在用户口径里是**同一条线**（0~30 分钟既不等也不变色），
     * 所以允许那个偏好把它们一起挪动；而红色从 60 分钟起是用户明确给的固定值，
     * 没有对应偏好键，也不该随手改。默认 noWaitMin = 30 时本函数与规格书
     * `0=绿(<30m) 1=黄(30~60m) 2=红(>=60m)` 逐字一致。
     *
     * 偏好的上界夹到 60（见 [TimePrefs.noWaitMin]），所以黄档最多被压成空区间，
     * 不会出现「红档起点小于黄档起点」这种反序。
     */
    @JvmStatic
    fun levelOf(usedMs: Long): Int {
        val used = usedMs.coerceAtLeast(0L)
        if (used >= RED_FROM_MS) return LEVEL_RED
        if (used >= TimePrefs.noWaitMin().toLong() * MS_PER_MINUTE) return LEVEL_YELLOW
        return LEVEL_GREEN
    }

    /** 档位：绿（安全档） */
    const val LEVEL_GREEN = 0

    /** 档位：黄（提醒档） */
    const val LEVEL_YELLOW = 1

    /** 档位：红（超限档） */
    const val LEVEL_RED = 2

    /**
     * 该时长对应的倒计时等待秒数：绿 `0` / 黄 [TimePrefs.midWaitSec]（默认 5）/
     * 红 [TimePrefs.highWaitSec]（默认 10）。
     *
     * 用 [levelOf] 派生而不是另写一套阈值：两者必须永远同档，分开写迟早会
     * 「颜色变黄了但没等待」这种自相矛盾的组合。
     */
    @JvmStatic
    fun waitSecondsFor(usedMs: Long): Int = when (levelOf(usedMs)) {
        LEVEL_RED -> TimePrefs.highWaitSec()
        LEVEL_YELLOW -> TimePrefs.midWaitSec()
        else -> 0
    }

    /**
     * 现在是否已经进入「不做倒计时等待」的时段（默认 21:30 起，含 21:30）。
     *
     * 用 [Calendar] 取**本地时区**的时与分：这是「墙上的钟」，必须跟用户手机显示一致。
     * 用 UTC 会在东八区整体偏 8 小时（21:30 变成次日 05:30），这条判断就完全失效。
     *
     * 只影响「等待」，**不影响配额拦截**：晚上配额用完了照样要被拦（规格 §5.3 规则 4/5）。
     * 理由：等待是为了增加摩擦、少玩一会儿；夜里已经该睡觉了，再加 10 秒毫无意义；
     * 但「配额用完不让进」是用户要的硬约束，与时段无关。
     */
    @JvmStatic
    fun isInNoCountdownWindow(): Boolean {
        val calendar = Calendar.getInstance()
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return minuteOfDay >= TimePrefs.noCountdownAfterMin()
    }

    // ==========================================================================================
    // 跨天归档
    // ==========================================================================================

    /**
     * 判断是否切天，切了就**把昨天那份累计归档进历史**。
     *
     * 由 [UsageTracker.onForeground] 在 App 回前台时调用（那是唯一必然跨天的时机）。
     *
     * ## 为什么还需要它
     * [addUsage] 每次都会同步覆盖写 `LOG_DAYS[today]`，所以正常路径下历史本来就是对的。
     * 但有一种漏格：用户昨天用完后**没有退到后台就被系统杀掉进程**（或手机直接关机），
     * 昨天最后一次 `addUsage` 之后没有新的结算，`LOG_DAYS[昨天]` 是当时的旧值 ——
     * 比 `USED_MS` 里记的少一点。本函数在下次回前台把那一格补成真实值。
     *
     * ## 为什么用覆盖（addDayTotal）而不是累加
     * `USED_MS` 存的是**昨天那一整天的最终总数**，所以写进历史应当是「把这个数覆盖上去」。
     * 累加会把昨天已经写过的那部分再加一遍（翻倍）。这也是为什么本函数用
     * `addDayTotal`、而 [UsageTracker] 的跨午夜切分用 `accumulateDayTotal`：
     * 前者手上是**整天的最终值**，后者手上只是**午夜前的那一截**。
     *
     * 幂等：覆盖语义，补多少次都是同一个值。用内存里的 [lastKnownDay] 做快速短路，
     * 避免每次回前台都读偏好。
     */
    @JvmStatic
    fun rotateIfDayChanged() {
        val today = TimePrefs.todayKey()
        if (today == lastKnownDay) return
        lastKnownDay = today
        val storedDay = TimePrefs.getText(TimePrefs.USED_DAY, "")
        // 从未记录过（或日期是脏值）就没什么可归档的
        if (storedDay.isEmpty() || storedDay == today) return
        val storedMs = TimePrefs.getLong(TimePrefs.USED_MS, 0L)
        if (storedMs > 0L) UsageLog.addDayTotal(storedDay, storedMs)
    }

    /**
     * 把今天的累计清零（**「重置今天」这类明确入口专用**，界面上该带二次确认）。
     *
     * 只清 `USED_MS`、保留 `USED_DAY`：置 0 而不是删键，这样 [UsageLog] 里今天那一格
     * 也会跟着归零，图表与头部数字保持一致。
     *
     * ⚠️ **不要把它挂在「清空使用记录」按钮上** —— 清空记录是清历史，若连今日累计一起清，
     * 用户就能靠那个按钮白嫖掉今天的配额，等于给了个绕开限制的后门。
     */
    @JvmStatic
    fun resetTodayUsage() {
        val today = TimePrefs.todayKey()
        TimePrefs.putUsage(today, 0L)
        UsageLog.addDayTotal(today, 0L)
    }
}
