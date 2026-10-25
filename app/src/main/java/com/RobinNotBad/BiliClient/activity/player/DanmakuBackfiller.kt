package com.RobinNotBad.BiliClient.activity.player

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.RobinNotBad.BiliClient.api.DanmakuApi
import com.RobinNotBad.BiliClient.model.DmSegMobileReply
import com.RobinNotBad.BiliClient.util.Logu
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 弹幕补拉器（新增，Kotlin）
 *
 * 解决的问题：**网络波动导致的弹幕丢失**。
 *
 * 首次拉取时若某些分段因网络抖动失败，这里会在后台按退避策略继续补拉，
 * 每补到一段就立刻交给播放页增量追加到弹幕层，用户只会感觉“这条弹幕晚了一点出现”，
 * 而不是整段弹幕从此消失。
 *
 * 设计要点：
 * - 只在“还缺分段”时工作，全部补齐后自动停止，不常驻耗电；
 * - 退避间隔递增（2s → 4s → 8s → 上限 30s），网络长时间不通时不会疯狂重试；
 * - 通过 AtomicBoolean 支持取消，播放页退出时立即停止；
 * - 优先补拉“播放进度即将到达”的分段，让用户最快看到缺失内容。
 */
class DanmakuBackfiller(
    private val aid: Long,
    private val cid: Long,
    /** 每个分段的起始秒数 = (index - 1) * 360 */
    missingSegments: Collection<Int>,
    private val listener: Listener,
    /** 提供当前播放位置（秒），用于优先补拉即将播放到的分段 */
    private val positionProvider: () -> Int = { 0 }
) {

    interface Listener {
        /** 成功补拉到一段，立即增量渲染 */
        fun onSegmentFetched(index: Int, reply: DmSegMobileReply)

        /** 全部补齐 */
        fun onFinished(pendingCount: Int)

        /** 补拉被取消（播放页退出） */
        fun onCancelled()
    }

    /** 剩余待补分段 */
    private val pending = LinkedHashSet<Int>().apply { addAll(missingSegments) }

    /**
     * 补拉调度放在**独立后台线程**。
     *
     * 原实现把 task 直接 post 到 main Looper，而 doRound() 里是同步网络请求
     * （每段还可能重试 2 次），弱网时主线程会被整段请求卡住 —— 播放中表现为
     * 明显卡顿/掉帧，甚至 ANR。补拉本来就是“后台补齐”，不该占用 UI 线程。
     */
    private val workerThread = HandlerThread("danmaku-backfill").apply { start() }
    private val handler = Handler(workerThread.looper)
    /** 回调统一切回主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

    /** 当前退避间隔 */
    private var backoffMs = INITIAL_BACKOFF_MS

    /** 连续失败轮数 */
    private var failedRounds = 0

    private var running = false

    /** 已重试总次数，超过上限则放弃（极端弱网下避免长时间耗电） */
    private var totalAttempts = 0

    /** 单次网络请求的最大尝试次数（内部还会做段级重试） */
    private val maxTotalAttempts = MAX_TOTAL_ATTEMPTS

    private val task = object : Runnable {
        override fun run() {
            if (!running || cancelled.get()) return
            doRound()
            if (!running || cancelled.get()) return
            if (pending.isEmpty()) {
                finish()
                return
            }
            if (totalAttempts >= maxTotalAttempts) {
                Logu.w("弹幕补拉", "重试次数已达上限，放弃补拉，剩余 ${pending.size} 段")
                finish()
                return
            }
            // 指数退避，避免弱网下持续打请求（耗电 & 发热）
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            handler.postDelayed(this, backoffMs)
        }
    }

    fun start() {
        if (running) return
        if (pending.isEmpty()) {
            finish()
            return
        }
        running = true
        Logu.d("弹幕补拉", "开始补拉，待补分段: $pending")
        // 稍等一下再开始，避免和首次拉取/首帧渲染抢网络与CPU
        handler.postDelayed(task, INITIAL_DELAY_MS)
    }

    fun cancel() {
        if (cancelled.getAndSet(true)) return
        running = false
        handler.removeCallbacks(task)
        stopWorker()
        Logu.d("弹幕补拉", "已取消")
        mainHandler.post { listener.onCancelled() }
    }

    /** 本实例结束后释放后台线程（不会被复用；下一次补拉会新建实例） */
    private fun stopWorker() {
        try {
            workerThread.quitSafely()
        } catch (ignored: Exception) {
        }
    }

    /** 新增待补分段（例如切换清晰度后重新发现缺失） */
    fun addMissing(segments: Collection<Int>) {
        val added = segments.filter { pending.add(it) }
        if (added.isNotEmpty()) {
            backoffMs = INITIAL_BACKOFF_MS
            totalAttempts = 0
            Logu.d("弹幕补拉", "新增待补分段: $added")
            if (!running && !cancelled.get()) start()
        }
    }

    private fun doRound() {
        if (pending.isEmpty()) return

        // 优先补拉用户马上要看到的分段：按距离当前进度由近到远排序
        // （positionProvider 读的是播放器状态，异常时按第 1 段处理，别让补拉线程崩掉）
        val currentSeg = try {
            (positionProvider() / DanmakuApi.SEGMENT_SECONDS) + 1
        } catch (e: Exception) {
            1
        }
        val ordered = pending.sortedBy { kotlin.math.abs(it - currentSeg) }

        for (index in ordered) {
            if (cancelled.get()) return
            if (totalAttempts >= maxTotalAttempts) return

            totalAttempts++
            // 段级内部还有重试（2 次），这里减少每轮尝试次数以尽快走完一轮
            val reply = DanmakuApi.getVideoDanmakuSegmentWithRetry(
                aid, cid, index, SEGMENT_ATTEMPTS_PER_ROUND, cancelled
            )
            if (reply == null) {
                Logu.d("弹幕补拉", "分段 $index 本轮仍失败，稍后再试")
                continue
            }

            pending.remove(index)
            failedRounds = 0
            Logu.d("弹幕补拉", "分段 $index 补拉成功（${reply.elems.size} 条），剩余 ${pending.size} 段")
            // 回调切主线程，方便直接操作弹幕层
            mainHandler.post { listener.onSegmentFetched(index, reply) }

            if (pending.isEmpty()) return
        }
        failedRounds++
    }

    private fun finish() {
        if (running) {
            running = false
        }
        handler.removeCallbacks(task)
        val remain = pending.size
        if (remain == 0) {
            Logu.d("弹幕补拉", "全部补齐")
        }
        mainHandler.post { listener.onFinished(remain) }
        stopWorker()
    }

    private companion object {
        const val INITIAL_DELAY_MS = 3000L
        const val INITIAL_BACKOFF_MS = 2000L
        const val MAX_BACKOFF_MS = 30000L
        const val SEGMENT_ATTEMPTS_PER_ROUND = 2

        /** 总重试上限，避免弱网下无休止重试 */
        const val MAX_TOTAL_ATTEMPTS = 24
    }
}
