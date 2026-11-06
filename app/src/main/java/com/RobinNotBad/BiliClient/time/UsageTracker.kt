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
        // 前台恢复时立刻处理跨天：把「昨天」的累计落进历史，今天的计数从 0 重新开始。
        // 读路径本身也按 USED_DAY 判断（不依赖这里），这一步只是把旧数据落盘，避免丢历史。
        DailyPlan.rotateIfDayChanged()
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
        flush()
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
        if (activeMs <= 0L) return
        creditUsage(activeMs, startWall)
    }

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
     */
    @JvmStatic
    fun reset() {
        foregroundStartElapsed = 0L
        sessionStartWallMs = 0L
        pauseDepth = 0
        pauseStartElapsed = 0L
        pausedMs = 0L
        currentPage = null
        pageStartElapsed = 0L
    }
}
