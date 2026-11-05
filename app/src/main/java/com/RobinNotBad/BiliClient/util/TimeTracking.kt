package com.RobinNotBad.BiliClient.util

import android.os.SystemClock

/**
 * 页面停留追踪 + 「等待时间不计入使用时长」的暂停闸门（v5.2.0）。
 *
 * ## 它解决的两个问题
 *
 * ### 1. 「在哪个页面停留最久」需要页面级的起点/终点
 * `BiliTerminal.ForegroundUsageTracker` 用 0→1 / 1→0 的**边沿判定**只能得到
 * 「App 连续在前台的总时长」——推荐↔详情↔播放来回切都算同一段，拿不到「哪一页待了多久」。
 * 所以这里单独维护一份「当前页 + 该页起点」：
 * - [onPageResumed]（Activity resume）：把上一页结算掉，记下当前页起点；
 * - [onPagePaused]（Activity pause）：结算当前页，起点清空；
 * - [flushCurrentPage]：退到后台时兜底结算（幂等，重复调用不会重复记账）。
 *
 * 页面名由 `BaseActivity.setPageName` 经 [setCurrentPage] 写入——全 App 49 处调用
 * 都会经过那里，不用改任何业务页面。
 *
 * ### 2. 倒计时 / 配额拦截页的等待时间不该算作「使用时长」
 * 需求明确：「倒计时等待时间不计算使用时长」。倒计时页与拦截页在 `onResume` 里调
 * [pauseTracking]、`onPause` 里调 [resumeTracking]，这期间累计的毫秒记在 [pausedMs]，
 * `BiliTerminal` 在 1→0 结算总时长前用 [consumePausedMs] **取出并清零**，从总时长里扣掉。
 *
 * ## 线程模型
 * 全部由 Activity 生命周期回调（主线程）驱动，字段用 `@Volatile` 只为跨线程读安全，
 * 不做加锁（都是单次 Long 读写，不存在复合竞态）。
 *
 * ## 不写偏好、不持线程
 * 本对象只在内存里攒「本次会话」的页面停留，真正的落盘由 [UsageStatsManager] 负责
 * （它在 `addPageUsage` 里写 `usage_page_stats`）。这样即使进程被杀，最多丢最后一次
 * 未结算的停留，不会留下半截数据。
 */
object TimeTracking {

    /** 当前页面名；null = 还不知道（页面还没调 setPageName） */
    @Volatile
    private var currentPage: String? = null

    /** 当前页停留起点（elapsedRealtime）；0 = 未在计时 */
    @Volatile
    private var pageStartElapsed = 0L

    /** 累计的「暂停」毫秒（倒计时/拦截页停留时长）；由 BiliTerminal 结算时取走 */
    @Volatile
    private var pausedMs = 0L

    /** 暂停闸门：>0 表示当前处于「不计入使用时长」的页面 */
    @Volatile
    private var pauseDepth = 0

    /** 暂停开始时刻（elapsedRealtime）；0 = 未暂停 */
    @Volatile
    private var pauseStartElapsed = 0L

    /**
     * 记录当前页面名（由 `BaseActivity.setPageName` 调用）。
     *
     * 页面切换时**不在这里结算**——真正的结算在 [onPageResumed]/[onPagePaused]，
     * 因为 `setPageName` 可能在 `onCreate` 里就调（那时页面还没显示），
     * 用它当起点会把「页面创建到显示」的时间算进去，也会把上一个页面的尾巴丢掉。
     */
    @JvmStatic
    fun setCurrentPage(name: String?) {
        currentPage = name
    }

    /** 当前页面名（统计页/日志用；可能为 null） */
    @JvmStatic
    fun currentPageName(): String? = currentPage

    /**
     * 新页面 resume：先把**上一页**的停留结算掉，再开始给当前页计时。
     *
     * 顺序很重要：先结算再改起点，否则上一页的时长会算到这一页头上。
     */
    @JvmStatic
    fun onPageResumed() {
        settlePage()
        if (pauseDepth > 0) return
        pageStartElapsed = SystemClock.elapsedRealtime()
    }

    /** 页面 pause：结算当前页，起点清零（回到后台时 BiliTerminal 也会调 flush 兜底） */
    @JvmStatic
    fun onPagePaused() {
        settlePage()
    }

    /**
     * 退到后台时兜底结算当前页（幂等）。
     *
     * 为什么要它：`onPagePaused` 与 `onActivityStopped` 的先后顺序在个别机型上不保证，
     * 若最后一步没走到 pause，这段停留就会丢。这里再刷一次，`settlePage()` 内部
     * 靠「起点清零」保证重复调用不会重复记账。
     */
    @JvmStatic
    fun flushCurrentPage() {
        settlePage()
    }

    /**
     * 结算当前页的停留并写进 [UsageStatsManager]。
     *
     * - 起点为 0（没在计时）→ 直接返回（这就是幂等的来源）；
     * - 页面名为 null → 丢弃（没有名字的停留无法归类，记了也只是脏数据）；
     * - 暂停中（`pauseDepth > 0`）→ 这段时间属于倒计时/拦截页，不算页面停留。
     */
    private fun settlePage() {
        val start = pageStartElapsed
        if (start <= 0L) return
        pageStartElapsed = 0L
        if (pauseDepth > 0) return
        val page = currentPage ?: return
        val delta = SystemClock.elapsedRealtime() - start
        if (delta <= 0L) return
        UsageStatsManager.addPageUsage(page, delta)
    }

    // ------------------------------------------------------------------ 暂停闸门

    /**
     * 进入「不计入使用时长」的页面（倒计时页 / 配额拦截页）。
     *
     * 用**计数**而不是布尔：万一两个这样的页面叠加（拦截页 → 倒计时页），
     * 内层 resume 时不会把外层的暂停提前解除；对应地 [resumeTracking] 也要成对调用。
     */
    @JvmStatic
    fun pauseTracking() {
        pauseDepth += 1
        if (pauseDepth == 1) {
            pauseStartElapsed = SystemClock.elapsedRealtime()
            // 暂停期间不属于任何「正常页面」的停留：把当前页的计时停掉，
            // 否则倒计时页的 onResume 会把上一页的停留结算进暂停时段
            settlePage()
        }
    }

    /** 离开「不计入使用时长」的页面；计数归零时把这段时长累进 [pausedMs] */
    @JvmStatic
    fun resumeTracking() {
        if (pauseDepth <= 0) return
        pauseDepth -= 1
        if (pauseDepth == 0) {
            val start = pauseStartElapsed
            pauseStartElapsed = 0L
            if (start > 0L) {
                val delta = SystemClock.elapsedRealtime() - start
                if (delta > 0L) pausedMs += delta
            }
        }
    }

    /** 当前是否处于暂停（统计页/日志用） */
    @JvmStatic
    fun isPaused(): Boolean = pauseDepth > 0

    /**
     * 取出并清零累计的暂停毫秒。
     *
     * 由 `BiliTerminal` 在 1→0 结算总时长时调用：`addUsage(总时长 - consumePausedMs())`。
     * **取出即清零**——所以一次前台会话只该调用一次；若调用方没调，
     * 这段暂停时长会一直留到下一次结算（那就会多扣一次，所以约定「1→0 必调」）。
     */
    @JvmStatic
    fun consumePausedMs(): Long {
        val value = pausedMs
        pausedMs = 0L
        return value
    }

    /**
     * 丢弃全部内存态（测试 / 用户「清空统计」后调用）。
     *
     * 只清内存，不碰偏好——偏好数据由 [UsageStatsManager.clear] 负责。
     */
    @JvmStatic
    fun reset() {
        currentPage = null
        pageStartElapsed = 0L
        pausedMs = 0L
        pauseDepth = 0
        pauseStartElapsed = 0L
    }
}
