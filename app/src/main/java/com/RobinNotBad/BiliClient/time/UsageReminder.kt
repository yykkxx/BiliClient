package com.RobinNotBad.BiliClient.time

import android.os.Handler
import android.os.Looper

import com.RobinNotBad.BiliClient.util.MsgUtil

/**
 * 「每 15 分钟提醒一次今日已用时长」（v5.3.0 用户需求 6）。
 *
 * # 它解决什么
 * 用户口径：「App 在**前台使用**期间，每隔 15 分钟弹一次 Toast，报今天已用多少」。
 * 所以它统计的不是「App 开着多久」，而是「**真的在用**多久」——后台不算，
 * 停在倒计时页 / 配额拦截页 / 日子询问页的那段也不算（那三页已经调了
 * [UsageTracker.pause]，详见那里的暂停闸门）。
 *
 * # 为什么计时基准是 [UsageTracker.activeMsInSession]，而不是一个 postDelayed 定死 15 分钟
 * 如果只是「前台时 postDelayed(15 分钟)」，那么用户在配额拦截页待了 10 分钟、
 * 再回来用 5 分钟就会挨一次提醒——可那 10 分钟按设计**不算使用时长**，
 * 提醒却说「你用了 15 分钟」，与「今日已用」的数字直接打架。
 * 所以周期推进看的是**净使用毫秒**：每次 tick 都问一次「这轮前台真正用了多久」，
 * 没到点就按差额重新排期。暂停期间净时长不前进 → 周期自然也不前进，
 * 不需要在暂停/恢复的每一处手动加减（少一处接线就少一个会漏的地方）。
 *
 * # 为什么用 Handler.postDelayed 而不是定时线程 / 协程 delay
 * 1. 提醒要弹 Toast，最终必须在主线程；`Handler(Looper.getMainLooper())` 直接对齐。
 * 2. 不需要在后台跑：`onBackground` 就把回调摘掉，进程里不留任何活动定时器
 *    （协程方案要么持作用域、要么依赖进程级 scope，退后台还得多写一处 cancel，容易漏）。
 * 3. 本仓库门禁硬禁 `Thread.sleep(`，也不该为了一个 15 分钟节拍开线程。
 *
 * # 「回前台重新计 15 分钟」而不是「接着上次剩余」（本实现的取舍）
 * 用户体感是「**每用 15 分钟提醒一次**」。若是接着剩余：退后台 14 分钟前刚用过 14 分钟，
 * 回来 1 分钟后就被提醒，用户会觉得「怎么又要提醒、我明明刚看过」；
 * 而且「剩余」这个量跨会话根本没有可解释的语义（后台那段时间不算使用，也不该被计入周期）。
 * 重置的另一个好处是实现上不需要任何跨会话状态：每轮前台都是全新的 15 分钟。
 *
 * # 「功能没开也弹」——这是核对过代码后的结论，不是偷懒
 * - **已用时长本身与限制无关**：[UsageTracker] 的记账路径**不检查** [DailyPlan.isEnabled]，
 *   所以总开关关着的时候今日已用照样在涨；启动页 `LauncherActivity.showTodayUsage` 也是
 *   「无论功能是否开启都显示」（源码注释原话）。既然时长一直在记，提醒它就不是「多管闲事」。
 * - **但剩余配额必须不显示**：功能没开时 `quotaMinutesToday()` 会退回「上学日 120 分钟」兜底，
 *   算出来的「剩余」是个没人会同意的数（因为根本没有东西在限制）。
 *   同理「今天还没选过日子」时配额也是保守兜底值，一样不显示——这与启动页
 *   `!isEnabled() || !hasDayTypeToday()` 那条判断逐字一致。
 * 若日后决定「没开功能就不提醒」，在 [onForeground] 开头加一句
 * `if (!DailyPlan.isEnabled()) return` 即可（只有那一处）。
 *
 * # 状态
 * 两个字段（[running] / [targetSessionMs]），由 [onForeground] / [onBackground] 驱动，
 * 全部在主线程读写（生命周期回调本来就都在主线程），不需要加锁。
 */
object UsageReminder {

    /**
     * 提醒周期：15 分钟。
     *
     * ⚠️ **真机临时验证**时把它改成 `1L * 60_000L`（1 分钟）即可，改的就是这一行；
     * 别的都不用动。验证完记得改回来。
     */
    private const val PERIOD_MS = 15L * 60_000L

    /**
     * 到点后立刻重排的最小延迟。
     *
     * `postDelayed(0)` 在同一个 Looper 轮次里执行，连弹多条时容易糊在一起；
     * 250ms 足够让「这次 tick」先结束，又慢到用户察觉不到。
     */
    private const val MIN_DELAY_MS = 250L

    /**
     * 暂停闸门里「已经到点却还没弹」时的复查间隔。
     *
     * 这个分支存在的理由：到点时如果用户正停在倒计时/拦截/询问页，我们**不弹**
     * （理由见 [onTick]），但阈值也不推进，于是 `剩余 <= 0` 会一直成立。
     * 若那时还按 [MIN_DELAY_MS] 排期，就会变成「每 250ms 醒一次」的忙轮询 ——
     * 用户把手机搁在拦截页上一整晚的话，那是十几万次无意义唤醒。
     * 5 秒一次既能让恢复后最多 5 秒内补上提醒，又不会成为耗电源。
     */
    private const val PAUSED_RETRY_MS = 5_000L

    /** 一分钟的毫秒数（「已超」不足 1 分钟时换个说法，避免「已超 不到 1 分钟」） */
    private const val MS_PER_MINUTE = 60_000L

    /** 主线程 Handler；只在真正开始计时时才创建（退后台后不再持有 Looper 引用以外的任何东西） */
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** tick 回调；复用同一个 Runnable，[onBackground] 时能精确摘除 */
    private val tickRunnable = Runnable { onTick() }

    /** 是否处于「前台且要提醒」的状态；false 时回调已摘除，不会再被执行 */
    @Volatile
    private var running = false

    /** 本轮前台的提醒阈值（[UsageTracker.activeMsInSession] 累计到它时弹一次） */
    private var targetSessionMs = PERIOD_MS

    // ==========================================================================================
    // 生命周期（由 BiliTerminal.ForegroundUsageTracker 在 0→1 / 1→0 边沿调用）
    // ==========================================================================================

    /**
     * App 回到前台：开始一轮新的 15 分钟周期。
     *
     * **必须在 [UsageTracker.onForeground] 之后调**：那一句会把会话净时长归零，
     * 先归零再定阈值，周期才是「从现在起 15 分钟」。顺序在 `ForegroundUsageTracker`
     * 里是写死的（同一个回调里相邻两行），别调换。
     */
    @JvmStatic
    fun onForeground() {
        running = true
        // 每轮前台重新起算（理由见类注释「回前台重新计 15 分钟」）
        targetSessionMs = PERIOD_MS
        scheduleNext()
    }

    /**
     * App 退到后台：立刻停表。
     *
     * 「后台不计、不弹」是需求的硬要求，所以这里不仅置位，还**必须 removeCallbacks**
     * ——否则已经排上的那一次 postDelayed 还会照样触发，用户在别的 App 里收到一条
     * 「今日已用」，既吓人又说不通。
     */
    @JvmStatic
    fun onBackground() {
        running = false
        handler.removeCallbacks(tickRunnable)
    }

    // ==========================================================================================
    // 内部：节拍
    // ==========================================================================================

    private fun onTick() {
        if (!running) return
        // 先把「跑到此刻」的净时长结算进 DailyPlan.usedMsToday（它是唯一真源），
        // 否则提醒里的数字会停在「上次退后台时」的旧值 —— 前台连续使用时它根本不涨。
        // flush 幂等、只做内存运算 + 偏好 apply，放在主线程没有压力。
        UsageTracker.flush()
        val activeMs = UsageTracker.activeMsInSession()
        // 暂停闸门里不弹（`isPaused()`）：那三页（倒计时 / 配额拦截 / 日子询问）本身的语境
        // 就是「还没开始用」或「已经用超了」，再叠一条「今日已用」既打断页面语义，
        // 也和拦截页上那个更大的数字重复。**提醒不会因此丢失**：阈值不推进，
        // 恢复后的第一次 tick 会发现已到点并立刻补上（见 scheduleNext 的暂停分支）。
        if (activeMs >= targetSessionMs && !UsageTracker.isPaused()) {
            MsgUtil.toast(buildText())
            // 一次 tick 只弹一条：万一因为休眠/长时间暂停漏掉好几个周期，也只提醒「现在」一次。
            // 阈值用整除一次推到下一个未到的周期（**不要写 while 累加**：
            // 万一时钟异常把净时长撑成天文数字，while 会变成一个长耗时循环）。
            val skipped = (activeMs - targetSessionMs) / PERIOD_MS + 1L
            targetSessionMs += skipped * PERIOD_MS
        }
        scheduleNext()
    }

    /**
     * 排下一次 tick。
     *
     * 延迟 = 阈值 − 已用净时长，所以周期严格按「净使用时长」推进，与暂停了多久无关。
     *
     * ## 为什么暂停/恢复不需要额外接线（这是本设计能这么短的原因）
     * 每次 tick 都**重算**一次剩余，所以一个「按旧剩余排下、却在上次暂停里提前触发」的
     * 回调是无害的：它只会发现没到点、再按新的剩余重排一次。
     * 举例：进前台排 15 分钟 → 1 分钟时进倒计时页暂停 10 分钟 →
     * 第 15 分钟（墙钟）那次 tick 发现净时长只有 5 分钟，重排 10 分钟 → 净时长满 15 分钟时弹。
     * 也就是说暂停期间「多跑的那几次 tick」只是读了几个偏好再重排，既不会误弹也不会延后
     * ——所以不需要在 [UsageTracker.pause] / [UsageTracker.resume] 里回调本对象，
     * 那一层不必知道提醒的存在（少一条反向依赖，也少一处会漏掉的接线）。
     *
     * - 已到点则用 [MIN_DELAY_MS] 立刻补一次（`postDelayed(0)` 会挤在同一轮里）。
     */
    private fun scheduleNext() {
        handler.removeCallbacks(tickRunnable)
        if (!running) return
        val remainingMs = targetSessionMs - UsageTracker.activeMsInSession()
        val delayMs = when {
            remainingMs > 0L -> remainingMs
            // 已到点却在暂停里：不忙轮询，低频探到恢复为止（理由见 PAUSED_RETRY_MS）
            UsageTracker.isPaused() -> PAUSED_RETRY_MS
            else -> MIN_DELAY_MS
        }
        handler.postDelayed(tickRunnable, delayMs)
    }

    // ==========================================================================================
    // 内部：文案
    // ==========================================================================================

    /**
     * 组装 Toast 文案。
     *
     * 数字一律交给 [TimeFormat.duration]（全 App 唯一的时长格式化入口）：提醒里的
     * 「1 小时 23 分钟」必须和启动页 / 统计页/ 倒计时页**逐字一致**，否则同一份数据
     * 在四处长得不一样，用户会怀疑数据本身有问题。这里只加连接词与括号，不新增任何
     * 「取整 / 进位 / 缩写」规则。
     *
     * 四种情形：
     * - 功能没开 / 今天还没选日子 → `今日已用 1 小时 23 分钟`（不显示剩余，理由见类注释）
     * - 休息日 → 同上（配额是 `Int.MAX_VALUE`，算出来的「剩余」是天文学数字）
     * - 还有剩余 → `今日已用 1 小时 23 分钟（剩余 36 分钟）`
     * - 已超额 → `今日已用 2 小时 5 分钟（已超 5 分钟）`
     */
    private fun buildText(): String {
        // 数字一律走 TimeFormat.duration，所以文案是「1 小时 23 分钟」而不是更短的
        // 「1 小时 23 分」——统一优先于省两个字（时间管理 7 个页面共用同一套说法）。
        val usedText = "今日已用 " + TimeFormat.duration(DailyPlan.usedMsToday())
        val quotaText = buildQuotaText()
        return if (quotaText.isEmpty()) usedText else usedText + "（" + quotaText + "）"
    }

    /**
     * 括号里的剩余/超额部分；返回空串表示「不显示」。
     *
     * 两道判空各挡一个坑（顺序无所谓，任一命中都是「不显示」）：
     * 1. **功能没开 / 今天还没选日子**：此时 `quotaMinutesToday()` 会退回「上学日」兜底值，
     *    剩余是个没人会同意的数（因为根本没有东西在限制）。
     * 2. **休息日**：配额是 `Int.MAX_VALUE`，`remainingMsToday()` 约 1.28e14 毫秒，
     *    直接格式化会输出「剩余 1491091 天」这种天文数字（v5.2.0 踩过；
     *    启动页 `showTodayUsage` 有同款判断，这里逐字对齐那个口径）。
     */
    private fun buildQuotaText(): String {
        // 功能没开 / 今天还没选日子 → 配额是「上学日」兜底值，剩余没有实际意义，不显示
        if (!DailyPlan.isEnabled() || !DailyPlan.hasDayTypeToday()) return ""
        if (DailyPlan.quotaMinutesToday() == DailyPlan.QUOTA_UNLIMITED) return ""
        val remainingMs = DailyPlan.remainingMsToday()
        if (remainingMs > 0L) return "剩余 " + TimeFormat.duration(remainingMs)
        val overMs = -remainingMs
        // 不足 1 分钟时 [TimeFormat.duration] 会说「不到 1 分钟」，
        // 拼成「已超 不到 1 分钟」读着别扭，换成不带数字的说法。
        return if (overMs < MS_PER_MINUTE) "已超额" else "已超 " + TimeFormat.duration(overMs)
    }
}
