package com.RobinNotBad.BiliClient.time

import android.os.SystemClock

/**
 * 前台计时 + 页面停留统计（规格 §5.3「关键实现约束」第 1 条）。
 *
 * # 它负责什么
 * 1. **前台时长**：由外部（Lead 在 `BiliTerminal` 里接 `registerActivityLifecycleCallbacks`）
 *    在 startedCount 的 **0→1 边沿**调 [onForeground]、**1→0 边沿**调 [onBackground]。
 *    这样「推荐↔详情↔播放」来回切不会被记成多段，只有真正离开 App 才结算。
 * 2. **页面停留**：`BaseActivity.setPageName` 调 [setCurrentPage]，切换时结算上一页。
 * 3. **暂停闸门**：倒计时页 / 配额拦截页 / 日子询问页的停留时间**不算使用时长**，
 *    也不算页面停留，由这些页面在 `onResume` 调 [pause]、`onPause` 调 [resume]。
 *
 * # 两种时间基准的分工（全包统一，别混用）
 * - **时长** 用 [SystemClock.elapsedRealtime]：不受用户改系统时间影响。
 *   用户把时钟往前拨 3 小时，不应该凭空多出 3 小时使用时长（那是时长，不是时刻）。
 * - **日期边界** 用 [System.currentTimeMillis] 派生的本地日历（见 [TimePrefs.todayKey]）：
 *   用户改时间时我们**必须**跟着变——「今天」在他眼里就是手机上显示的那一天，
 *   跨天重置、21:30 判断都得跟随。两套坐标只在 [creditUsage] 里通过
 *   [TimePrefs.startOfTodayWall] 做一次换算（用于把一段跨午夜的时长切开）。
 *
 * # 为什么用计数器而不是布尔
 * 「拦截页 → 倒计时页」会叠加两层暂停（点「去借时间」跳到倒计时页时，拦截页还没 resume）。
 * 布尔会在**内层** resume 时就把暂停解除，外层剩余时间被错记成使用时长。
 * 计数器（[pauseDepth]）只有归零才真正解除，成对调用天然正确。
 *
 * # 为什么暂停时长在内部结算，而不是让调用方「记得扣」
 * v5.2.0 的写法是「1→0 时调用方必须调 consumePausedMs 并自己相减」，plan.md 记着这条契约
 * 很脆（漏调就多算）。现在 [onForeground]/[onBackground] 都在本对象里，扣减在被唯一
 * 结算点 [settleUsageInternal] 内部完成，调用方不需要、也没机会搞错。
 * [consumePausedMs] 保留为诊断/外部兜底入口（见它的注释）。
 *
 * # 线程
 * 由生命周期回调（主线程）驱动，字段用 `@Volatile` 只为跨线程读安全；
 * 都是单次 Long/Int 读写 + 偏好 apply，不做加锁（不存在复合竞态需要保护）。
 */
object UsageTracker {

    /** 当前前台会话的起点（elapsedRealtime）；0 = 不在前台 */
    @Volatile
    private var foregroundStartElapsed = 0L

    /**
     * 当前已结算到的**墙钟**起点（`System.currentTimeMillis()`）；0 = 不在前台。
     *
     * 它只用于一件事：判断这一段时长有没有跨过午夜。跨过就按午夜切成两段，
     * 前一段归档到旧日期，避免「开着 App 过了零点」把昨天的时间全算到今天。
     */
    @Volatile
    private var sessionStartWallMs = 0L

    /** 暂停层数；>0 表示当前处于「不计入使用时长」的页面 */
    @Volatile
    private var pauseDepth = 0

    /** 本轮暂停段的起点（elapsedRealtime）；0 = 当前没有正在走的暂停段 */
    @Volatile
    private var pauseStartElapsed = 0L

    /** 已累计但尚未扣减的暂停毫秒 */
    @Volatile
    private var pausedMs = 0L

    /** 当前页面名；null = 不知道该算给谁 */
    @Volatile
    private var currentPage: String? = null

    /** 当前页停留起点（elapsedRealtime）；0 = 没在给页面计时 */
    @Volatile
    private var pageStartElapsed = 0L

    /**
     * 本轮前台会话里**已经真正在用**的毫秒（暂停闸门那段时间已扣除）。
     *
     * 与 [pausedMs] 的分工：`pausedMs` 是「攒着还没开票的暂停时长」，本字段是
     * 「已经结算并开票过的净使用时长」。每次 [settleUsageInternal] 把两者的差
     * 累加进来，所以**即使前台中途调了 [flush]**（settle 会把 [foregroundStartElapsed]
     * 推到当前时刻），本字段也保持单调递增，不会被清零。
     *
     * 存在的唯一理由：[UsageReminder] 要问「这轮前台到底用了多久」（读法是
     * [activeMsInSession]，**不是**本私有字段）。
     * 不能用 `now - foregroundStartElapsed`（那是含暂停的毛时长，且会被 flush 重置），
     * 也不能用 [DailyPlan.usedMsToday]（它只在结算点落盘，前台期间不涨）。
     *
     * 只在 [onForeground] 与 [reset] 里归零。
     */
    @Volatile
    private var activeMsSinceForeground = 0L

    // ==========================================================================================
    // 前台边沿
    // ==========================================================================================

    /** 当前是否处于前台（供 UI/诊断查询） */
    @JvmStatic
    fun isForeground(): Boolean = foregroundStartElapsed > 0L

    /** 当前是否处于暂停（供 UI/诊断查询） */
    @JvmStatic
    fun isPaused(): Boolean = pauseDepth > 0

    /** 当前页面名（使用记录页可以显示「正在看什么」） */
    @JvmStatic
    fun currentPageName(): String? = currentPage

    /**
     * 本轮前台会话里**已经真正用**了多少毫秒（暂停闸门期间不算）。
     *
     * 返回值 = 已结算并落盘的净时长 + **此刻正在走、还没结算的那一截**。
     * 后者要单独补上，否则前台连续使用期间两次 [flush]（或只在退后台才结算）之间，
     * 调用方看到的永远是 0 —— 15 分钟提醒正是靠它判断「到点了没」，漏这一段就永远不提醒。
     *
     * 「正在走的那一截」的算法与 [settleUsageInternal] 保持同一口径：先扣 [pausedMs]，
     * 再扣当前这一段暂停段（`pauseStartElapsed` 到此刻），否则在倒计时页待着也会把周期推满。
     */
    @JvmStatic
    fun activeMsInSession(): Long {
        val base = activeMsSinceForeground
        val start = foregroundStartElapsed
        if (start <= 0L) return base
        val now = SystemClock.elapsedRealtime()
        val totalMs = (now - start).coerceAtLeast(0L)
        val pendingPause = pauseStartElapsed.takeIf { it > 0L }
            ?.let { (now - it).coerceAtLeast(0L) }
            ?: 0L
        val pendingActive = (totalMs - pausedMs - pendingPause).coerceAtLeast(0L)
        return saturatingAddSession(base, pendingActive)
    }

    /**
     * App 真正回到前台（startedCount 0→1）。
     *
     * 幂等：已经在前台时重复调用直接返回，不会把起点重置（否则每次 Activity 切换都重开一段）。
     */
    @JvmStatic
    fun onForeground() {
        if (foregroundStartElapsed > 0L) return
        val now = SystemClock.elapsedRealtime()
        foregroundStartElapsed = now
        sessionStartWallMs = System.currentTimeMillis()
        // 新的一轮前台会话：15 分钟提醒的周期从这里重新起算（见 UsageReminder 的「回前台重置」）
        activeMsSinceForeground = 0L
        // 前台恢复时立刻处理跨天：把「昨天」的累计落进历史，今天的计数从 0 重新开始。
        // 读路径本身也按 USED_DAY 判断（不依赖这里），这一步只是把旧数据落盘，避免丢历史。
        // v5.3.4：这一句要碰偏好 + 历史解析，是**本函数里唯一可能抛异常**的调用。
        // 它由 Application 的 ActivityLifecycleCallbacks 调用（BiliTerminal），
        // 在那里抛出 = 直接打穿主线程、连一个 Activity 都接不住 → 整个 App 闪退。
        // 而「跨天归档」纯属历史图表的锦上添花：失败最多丢一格历史，绝不能让它带走进程。
        runSafely("rotateIfDayChanged") { DailyPlan.rotateIfDayChanged() }
        if (pauseDepth > 0) {
            // 回前台时仍在暂停态（例如息屏时正停在倒计时页）：暂停段的计时从此刻续上，
            // 后台那段时间不会被累计（需求：切后台/息屏时不继续倒计时，也不继续计时）。
            if (pauseStartElapsed == 0L) pauseStartElapsed = now
        } else {
            pageStartElapsed = now
        }
    }

    /**
     * App 真正退到后台（startedCount 1→0）。
     *
     * 顺序：先 [flush] 把「到目前为止」的时长与页面停留结算掉（幂等），
     * 再清掉前台状态。这样即使调用方先调了 [flush] 再调本函数，也不会重复记账。
     */
    @JvmStatic
    fun onBackground() {
        if (foregroundStartElapsed <= 0L) return
        // v5.3.4：本函数同样由 Application 的生命周期回调驱动，异常会直接打穿主线程。
        // 结算失败最多丢一段统计（下次结算按新起点继续），绝不能带走进程。
        runSafely("flush-on-background") { flush() }
        foregroundStartElapsed = 0L
        sessionStartWallMs = 0L
        pageStartElapsed = 0L
        // 暂停段的计时在后台不继续走，这里把已经开始的那一截封口
        closePauseInterval()
    }

    /**
     * 结算到此刻（幂等）。退后台前调用；也可在「想立刻落盘」的时机调用（如设置页打开时）。
     *
     * 结算后**仍然处于前台**：起点推进到此刻，会话继续。所以连着调两次第二次约等于 0。
     */
    @JvmStatic
    fun flush() {
        if (foregroundStartElapsed <= 0L) return
        settlePageInternal()
        settleUsageInternal()
    }

    // ==========================================================================================
    // 页面停留
    // ==========================================================================================

    /**
     * 记录当前页面名（由 `BaseActivity.setPageName` 调）。
     *
     * 这里**就结算上一页**（而不是等 onPause）：`setPageName` 是页面切换时唯一必然发生的调用，
     * 且有页面名可归；用 onResume/onPause 配对反而要被接两处、还会在个别机型上顺序不定。
     * 传 null 表示「当前页面没法归类」，上一页照样结算，之后的停留会被丢弃（记了也是脏数据）。
     */
    @JvmStatic
    fun setCurrentPage(name: String?) {
        settlePageInternal()
        currentPage = name
        // settlePageInternal 已经替「当前页」重启了计时（前台且未暂停时），这里不用再动
    }

    /**
     * 结算当前页停留并把它记进 [UsageLog]。
     *
     * 结算后自动为「新的当前页」重启计时 —— 所以本函数可以安全地当作「刷新」反复调用。
     * 幂等性来自 [pageStartElapsed] 的单次读取与清零：重复调用时起点为 0，不会再记账。
     */
    private fun settlePageInternal() {
        val start = pageStartElapsed
        pageStartElapsed = 0L
        val now = SystemClock.elapsedRealtime()
        // 暂停中（倒计时/拦截/询问页）的停留不属于任何业务页面，直接丢弃
        if (start > 0L && pauseDepth == 0) {
            val page = currentPage
            val delta = (now - start).coerceAtLeast(0L)
            if (page != null && delta > 0L) {
                UsageLog.addPageUsage(page, delta)
            }
        }
        // 仍在前台且没暂停 → 接着给当前页计时（页面切换后无缝衔接，不需要调用方再做什么）
        if (foregroundStartElapsed > 0L && pauseDepth == 0) pageStartElapsed = now
    }

    // ==========================================================================================
    // 暂停闸门（计数器）
    // ==========================================================================================

    /**
     * 进入「不计入使用时长」的页面（倒计时页 / 配额拦截页 / 日子询问页）的 `onResume`。
     *
     * **必须与 [resume] 成对调用**（`onPause` 里调 resume）。漏一次 resume 会让计时永久暂停，
     * 表现为「使用时长再也不涨」——这是本设计唯一的失效模式，接线时要成对检查。
     */
    @JvmStatic
    fun pause() {
        if (pauseDepth == 0) {
            // 先把暂停前那一段算给当前页（此时 pauseDepth 还是 0，settlePageInternal 会正常记账），
            // 再把页面计时停掉：暂停期间不属于任何业务页面。
            settlePageInternal()
            pageStartElapsed = 0L
            if (foregroundStartElapsed > 0L) pauseStartElapsed = SystemClock.elapsedRealtime()
        }
        pauseDepth += 1
    }

    /**
     * 离开「不计入使用时长」的页面。只有计数归零才真正解除暂停。
     *
     * 多调一次不会把计数打成负数（<=0 直接返回），避免接线多处时出现「负层数」。
     */
    @JvmStatic
    fun resume() {
        if (pauseDepth <= 0) return
        pauseDepth -= 1
        if (pauseDepth > 0) return
        closePauseInterval()
        if (foregroundStartElapsed > 0L) pageStartElapsed = SystemClock.elapsedRealtime()
    }

    /** 把正在进行的暂停段累进 [pausedMs] 并封口（后台/解除暂停/结算前都要调） */
    private fun closePauseInterval() {
        val start = pauseStartElapsed
        pauseStartElapsed = 0L
        if (start <= 0L) return
        val delta = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        if (delta > 0L) pausedMs += delta
    }

    /**
     * 取出并清零累计的暂停毫秒。
     *
     * **正常情况下不需要外部调用** —— 扣减已在 [settleUsageInternal] 内部完成。
     * 保留它是因为「诊断/自检」需要读一个数。
     *
     * 注意一个容易写错的点：如果此刻**仍在暂停中**（`pauseDepth > 0`），
     * 把正在走的那一段封口取走之后，必须**重新起一段**——否则后续的暂停时长
     * 会因为 `pauseStartElapsed` 变 0 而全部漏记，表现为「在拦截页待久了反而算使用时长」。
     */
    @JvmStatic
    fun consumePausedMs(): Long {
        val stillPaused = pauseDepth > 0
        if (stillPaused) closePauseInterval()
        val value = pausedMs
        pausedMs = 0L
        // 封口后若仍在暂停，重新开始计这一轮暂停段（见上面的注意）
        if (stillPaused && foregroundStartElapsed > 0L) pauseStartElapsed = SystemClock.elapsedRealtime()
        return value
    }

    // ==========================================================================================
    // 时长结算（唯一的记账点）
    // ==========================================================================================

    /**
     * 把「上次结算到此刻」的前台时长记进 [DailyPlan]。
     *
     * 三件事：
     * 1. 扣掉暂停时长（倒计时/拦截页不算使用时长）；
     * 2. 跨午夜切分（前一段归档到旧日期，避免整夜开着 App 把时间算到今天）；
     * 3. 起点推进到此刻 —— 这就是幂等与「分段结算」的来源。
     */
    private fun settleUsageInternal() {
        val start = foregroundStartElapsed
        if (start <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val totalMs = (now - start).coerceAtLeast(0L)
        val paused = consumePausedMs()
        val activeMs = (totalMs - paused).coerceAtLeast(0L)
        val startWall = sessionStartWallMs
        foregroundStartElapsed = now
        // 下一段从这个墙钟时刻开始算，所以跨午夜只会在「第一段」里发生，不需要额外状态
        sessionStartWallMs = System.currentTimeMillis()
        // 净使用时长先累进会话计数（**在 `activeMs <= 0` 提前返回之前**）：
        // 15 分钟提醒读的是这个值，它必须与「真的在用的时间」严格同步。
        // 放在 creditUsage 之后也行，但那样 0 毫秒的分支会把它漏掉。
        activeMsSinceForeground = saturatingAddSession(activeMsSinceForeground, activeMs)
        if (activeMs <= 0L) return
        creditUsage(activeMs, startWall)
    }

    /** 会话内累计的饱和加法（上界同 [DailyPlan] 的量级，防止时钟异常把它撑爆） */
    private fun saturatingAddSession(used: Long, add: Long): Long =
        (used + add).coerceIn(0L, MAX_SESSION_MS)

    /**
     * 跑一段可能失败的计算，失败只打日志。
     *
     * 为什么这里需要它：本对象的前台/后台边沿由
     * `BiliTerminal.ForegroundUsageTracker`（Application 的 ActivityLifecycleCallbacks）驱动，
     * 那是一个**没有任何上层 try/catch 的入口** —— 在里面抛异常会直接
     * `Thread.uncaughtException` → `ErrorCatch` → killProcess，用户看到的就是「打开就闪退」。
     * 而计时统计纯属附属功能：它失败最多让今天的数字少几秒，**绝不能**把 App 带走。
     *
     * 只在「入口方法」用它（边沿、结算），内部纯内存运算不包 —— 不要为了省事到处套。
     */
    private inline fun runSafely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "usage tracking step failed (ignored): $what", e)
        }
    }

    /** 会话内累计的上界：100 年，与 [DailyPlan] 的日累计上界同量级 */
    private const val MAX_SESSION_MS = 100L * 365L * 24L * 60L * 60_000L

    /** 日志标签（[runSafely] 兜住的失败靠它过滤） */
    private const val TAG = "usage-tracker"

    /**
     * 把 [activeMs] 按午夜切成两段：午夜前的部分归档到那一天的日总数，午夜后的记给今天。
     *
     * 只有「上一段起点早于今天 00:00」时才切。因为 [settleUsageInternal] 每次结算都会把
     * 起点推进到当前时刻，所以最多只有一段会跨午夜。
     *
     * 已知近似：elapsedRealtime 与墙钟是两个坐标系，这里用墙钟起点做边界判断。
     * 若用户在会话中途改了系统时间，切分点会偏 —— 但方向是安全的
     * （最多把时间多算给「今天」，不会凭空造出时间）。
     */
    private fun creditUsage(activeMs: Long, startWall: Long) {
        val todayStartWall = TimePrefs.startOfTodayWall()
        val crossesMidnight = startWall > 0L && startWall < todayStartWall
        val beforeMidnightMs = if (crossesMidnight) {
            (todayStartWall - startWall).coerceIn(0L, activeMs)
        } else {
            0L
        }
        if (beforeMidnightMs > 0L) {
            // 必须用 accumulate（累加）而不是 addDayTotal（覆盖）：手上只有「午夜前那一截」，
            // 而存储里是那一整天截至上次结算的总数
            UsageLog.accumulateDayTotal(TimePrefs.dateKeyOfWall(startWall), beforeMidnightMs)
        }
        val afterMidnightMs = activeMs - beforeMidnightMs
        if (afterMidnightMs > 0L) DailyPlan.addUsage(afterMidnightMs)
    }

    /**
     * 丢弃全部内存态（「清空使用记录」或自检后调用）。
     *
     * **不结算**未落盘的这段时长（清空语义就是「不要了」）；也不碰偏好，
     * 偏好数据由 [UsageLog.clearAll] 负责。
     *
     * ## 前台中调用必须「重置后立刻续上」（v5.3.0 修的历史 bug）
     * 旧实现无条件把 [foregroundStartElapsed] 置 0，而它同时是「是否在前台」的判据。
     * 「清空使用记录」是设置页里的按钮，**用户点它时 App 正在前台**：置 0 之后
     * [isForeground] 变 false，[flush] 直接返回，`UsageLog.clearAll` 之后再也没有
     * 任何一次结算 —— 从那一刻起「今日已用」永久冻结，直到用户退后台再回来
     * （而 `onForeground` 的幂等判据也是这个字段，所以前台期间根本不会被调用）。
     * 表现出来就是「清空了一次记录之后，时间管理再也不记账了」。
     *
     * 所以这里记住「清空前是否在前台」，是的话以此刻为起点重开一段会话：
     * 已累计的时长确实丢了（那是清空语义），但**从下一秒起继续正常累计**。
     * 暂停层数也保留（清空记录发生在普通页面，不该影响闸门页的暂停状态）。
     */
    @JvmStatic
    fun reset() {
        val wasForeground = foregroundStartElapsed > 0L
        val stillPaused = pauseDepth > 0
        foregroundStartElapsed = 0L
        sessionStartWallMs = 0L
        pauseStartElapsed = 0L
        pausedMs = 0L
        pageStartElapsed = 0L
        activeMsSinceForeground = 0L
        if (!wasForeground) {
            // 本来就在后台：保持后台态（各字段归零即可，等下一次 0→1 边沿重新开始）
            pauseDepth = 0
            currentPage = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        foregroundStartElapsed = now
        sessionStartWallMs = System.currentTimeMillis()
        if (stillPaused) {
            // 暂停中清空（理论上不会发生，但保持状态自洽）：从此刻续上暂停段，
            // 页面计时保持停止（暂停期间不属于任何业务页面，与 pause() 的口径一致）
            pauseStartElapsed = now
            return
        }
        pauseDepth = 0
        // 页面名**保留**：清空记录发生在当前页面上，那个页面并没有变，
        // 保留它才能让「清空之后继续待在这一页」的停留照常归类（否则这段时长会被丢弃）。
        pageStartElapsed = now
    }
}
