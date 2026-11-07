package com.RobinNotBad.BiliClient.activity.player

import android.os.Handler
import android.os.Looper
import com.RobinNotBad.BiliClient.util.Logu

/**
 * 播放卡死看门狗（新增，Kotlin）
 *
 * 解决的问题：**网络波动导致的视频卡住无法播放**。
 *
 * ijkplayer 在网络抖动时会出现两类“假死”，而且都不会触发 onError：
 * 1. 一直停在缓冲状态（MEDIA_INFO_BUFFERING_START 之后再也收不到 BUFFERING_END），
 *    画面永远停在转圈；
 * 2. 播放进度不再推进，但播放器自认为正在播放（内核读取线程卡在 TCP 重传里）。
 *
 * 原代码对这两种情况没有任何兜底，用户只能退出重进。
 *
 * 本类周期性检查播放进度与缓冲状态，判定卡死后回调 [Listener.onPlaybackStalled]，
 * 由播放页执行“重建数据源 + 恢复播放进度”的恢复动作；恢复有次数上限与退避，
 * 避免网络彻底断开时疯狂重建播放器反而更耗电。
 */
class PlaybackWatchdog @JvmOverloads constructor(
    private val listener: Listener,
    /** 检查周期 */
    private val intervalMs: Long = 1000L,
    /** 进度多久不推进判定为卡死 */
    private val stallThresholdMs: Long = 9000L,
    /** 持续缓冲多久判定为卡死（正常网络抖动几秒内会恢复） */
    private val bufferingThresholdMs: Long = 13000L,
    /** 单次会话最多自动恢复次数 */
    private val maxRecoveryAttempts: Int = 3,
    /** 画面多久没有新帧判定为冻结（v5.3.0；0 表示不判冻结） */
    private val freezeThresholdMs: Long = 6000L
) {

    interface Listener {
        /**
         * 检测到播放卡死，需要上层执行恢复。
         *
         * @param stalledMs     卡死持续时长
         * @param whileBuffering 是否处于缓冲状态时卡死
         * @param attempt       第几次恢复（从 1 开始）
         */
        fun onPlaybackStalled(stalledMs: Long, whileBuffering: Boolean, attempt: Int)

        /** 自动恢复次数已用尽，交给用户手动处理 */
        fun onRecoveryExhausted()

        /**
         * 检测到**画面冻结**（v5.3.0 新增）：播放位置照常推进，但画面不再出新帧。
         *
         * 与 [onPlaybackStalled] 的区别是两者**互斥且不共用恢复动作**：
         * - 卡死 = 网络/解码卡住 → 位置不动 → 恢复动作是「重建数据源」；
         * - 冻结 = 声音在放、位置在涨、只是画面停在某一帧 → 重建数据源没用
         *   （数据是好的，是当前内核渲染不出画面）→ 恢复动作必须换成「切兼容内核」。
         *
         * 为什么合并到看门狗里而不是另起一个定时器：两者共用同一套
         * 「用户想播 / 已 prepared / 不在缓冲」的前置门控和同一个主线程 tick，
         * 分开写只会多一条生命周期要同步的定时器链（这个页面已经有 4 个 Timer 了）。
         *
         * @param frozenMs 画面连续多久没有新帧
         */
        fun onVideoFrozen(frozenMs: Long) {
            // 默认空实现：不关心画面冻结的调用方（目前只有播放页）无需跟着改签名
            Logu.d("看门狗", "画面冻结 ${frozenMs}ms（未处理）")
        }

        /**
         * 就绪后超时仍**没有任何首帧**（v5.3.0 新增）：用户看到的是「黑屏但声音正常」。
         *
         * 与 [onVideoFrozen] 分开成两个回调，是因为两者的**已发生事实不同**，
         * 上层要打的日志/提示也不同（「黑屏起不来」vs「画面冻住」）；但兜底动作相同
         * （都切兼容内核），所以两者共用同一个「一次为限」标记。
         */
        fun onFirstFrameTimeout() {
            Logu.d("看门狗", "首帧超时（未处理）")
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var running = false

    /** 用户是否希望处于播放状态（暂停时不做卡死判定） */
    @Volatile
    private var userWantsPlay = false

    /** 播放器是否已准备完成 */
    @Volatile
    private var prepared = false

    /** 是否处于缓冲中 */
    @Volatile
    private var buffering = false

    /** 最近一次观察到的播放进度 */
    @Volatile
    private var lastPosition = -1L

    /** 进度最后一次发生变化的时间 */
    @Volatile
    private var lastProgressAt = 0L

    /** 缓冲开始时间 */
    @Volatile
    private var bufferingSince = 0L

    private var recoveryAttempts = 0

    // ---- 画面冻结观测（v5.3.0）--------------------------------------------
    /** 画面冻结判据的供数器：由上层（播放页）注入，问「现在画面冻住了吗」 */
    @Volatile
    private var frozenProbe: (() -> Boolean)? = null

    /** 是否已经就「这一次冻结」通知过上层（一次冻结只通知一次，避免刷屏/重复切内核） */
    @Volatile
    private var freezeNotified = false

    /** 上一次探测时刻：用于把「已经冻了多久」算给上层看 */
    @Volatile
    private var freezeSince = 0L

    /**
     * 无首帧超时判据（v5.3.0）：由上层注入，返回 true 表示「READY 已超时但一帧都没出」。
     *
     * 与 [frozenProbe] 分开的原因：这两条判据的**起算点完全不同** ——
     * 冻结从「最后一次见到新帧」算，首帧超时从「进入 READY」算。合成一个回调会让
     * 供数方在下层做两套状态，反而更绕；分开注入，各自语义单一。
     */
    @Volatile
    private var firstFrameOverdueProbe: (() -> Boolean)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                check()
            } catch (e: Exception) {
                Logu.w("看门狗", "检查异常: " + e.message)
            }
            if (running) {
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        reset()
        handler.postDelayed(tick, intervalMs)
        Logu.d("看门狗", "已启动")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        Logu.d("看门狗", "已停止")
    }

    /** 重置统计（换P、切换清晰度、恢复完成后调用） */
    fun reset() {
        buffering = false
        bufferingSince = 0L
        lastPosition = -1L
        lastProgressAt = 0L
        recoveryAttempts = 0
        freezeNotified = false
        freezeSince = 0L
    }

    /**
     * 注入画面冻结判据（v5.3.0）。
     *
     * 为什么用回调注入而不是让看门狗自己去问播放器：
     * 只有 `Media3Player` 知道「渲染帧计数」这个信号（ijk 没有等价物），
     * 而看门狗是**与引擎无关**的通用组件（ijk 也在用它）。把引擎细节塞进看门狗
     * 会把两条引擎的耦合塞进一层，改成上层在认识引擎的地方决定要不要注入。
     *
     * @param probe 返回 true 表示「此刻画面确实冻住了」（判据由供数方负责，
     *   包括「没出过首帧不判」「听视频模式不判」这些前置条件）；null 表示不判冻结
     */
    fun setFrozenProbe(probe: (() -> Boolean)?) {
        frozenProbe = probe
        freezeNotified = false
        freezeSince = 0L
    }

    /**
     * 注入「无首帧超时」判据（v5.3.0）。
     *
     * @param probe 返回 true 表示「已就绪但超时仍没有首帧」；null 表示不判
     */
    fun setFirstFrameOverdueProbe(probe: (() -> Boolean)?) {
        firstFrameOverdueProbe = probe
    }

    fun setUserWantsPlay(wantsPlay: Boolean) {
        userWantsPlay = wantsPlay
        if (wantsPlay) {
            // 恢复播放时重新开始计时，否则会被误判为卡死
            lastProgressAt = System.currentTimeMillis()
            lastPosition = -1L
        }
    }

    fun setPrepared(isPrepared: Boolean) {
        prepared = isPrepared
        if (isPrepared) {
            lastProgressAt = System.currentTimeMillis()
            lastPosition = -1L
        }
    }

    fun onBufferingStart() {
        buffering = true
        bufferingSince = System.currentTimeMillis()
    }

    fun onBufferingEnd() {
        buffering = false
        bufferingSince = 0L
        // 缓冲结束后重新计时，给解码器一点恢复时间
        lastProgressAt = System.currentTimeMillis()
        lastPosition = -1L
    }

    /** 由进度定时器喂入当前播放位置 */
    fun onPosition(positionMs: Long) {
        if (positionMs != lastPosition) {
            lastPosition = positionMs
            lastProgressAt = System.currentTimeMillis()
        }
    }

    /** 播放成功后清零恢复计数 */
    fun notifyHealthy() {
        recoveryAttempts = 0
    }

    /**
     * 用户/程序发生了一次 seek（v5.3.0）。
     *
     * 为什么要单独通知：seek 之后画面本来就会黑一下再从新位置出帧，
     * 若不清掉冻结计时，一次正常的跳转会被当成「画面冻住了」而误切内核。
     * 同时清掉 [freezeNotified]，让「切过内核之后用户又手动跳转」这种场景
     * 能重新获得一次判定机会。
     */
    fun onSeek() {
        freezeSince = 0L
        freezeNotified = false
        lastProgressAt = System.currentTimeMillis()
        lastPosition = -1L
    }

    private fun check() {
        if (!running || !prepared || !userWantsPlay) return

        val now = System.currentTimeMillis()

        // 0) 画面类故障（v5.3.0）：**必须排在最前面**。
        //
        // 为什么顺序重要：冻结时位置照常推进，走到下面「进度不推进」分支会一路通过，
        // 永远不触发任何兜底 —— 这正是用户反馈的那个盲区。先问画面，才能在这个
        // 「位置正常、只有画面死」的形态下接管。
        // 缓冲态不判：缓冲时不出帧是正常的，交给下面的缓冲超时判据。
        if (!buffering && checkVideoFrozen(now)) return

        // 1) 缓冲超时
        if (buffering) {
            val bufferedMs = if (bufferingSince > 0) now - bufferingSince else 0L
            if (bufferedMs >= bufferingThresholdMs) {
                triggerRecovery(bufferedMs, true)
            }
            return
        }

        // 2) 进度不推进
        if (lastProgressAt <= 0L) {
            lastProgressAt = now
            return
        }
        val stalledMs = now - lastProgressAt
        if (stalledMs >= stallThresholdMs) {
            triggerRecovery(stalledMs, false)
        }
    }

    /**
     * 判一次「画面类故障」（v5.3.0）：先看「根本没出首帧」，再看「出过帧后冻住」。
     *
     * @return true 表示本次 tick 已被画面逻辑处理完，调用方**不要**再走卡死判定
     *   （这两者的本质都是「位置在涨、画面不动」，兼容判据同时成立时只有画面那条是对的）
     */
    private fun checkVideoFrozen(now: Long): Boolean {
        // 无首帧超时先判：它描述的是「一帧都没出过」，与冻结（出过帧后停住）
        // 是互斥的两个形态。先判掉它，后面的冻结计时就不会把「从未出帧」也算进去。
        if (handleFirstFrameOverdue()) return true
        val probe = frozenProbe ?: return false
        if (freezeThresholdMs <= 0L) return false
        val frozen = try {
            probe()
        } catch (e: Exception) {
            Logu.w("看门狗", "冻结探测异常: " + e.message)
            false
        }
        if (!frozen) {
            // 恢复出帧：清掉计时与已通知标记，下一次冻结可以再报一次
            freezeSince = 0L
            freezeNotified = false
            return false
        }
        if (freezeSince <= 0L) freezeSince = now
        val frozenMs = now - freezeSince
        if (frozenMs < freezeThresholdMs || freezeNotified) return false
        // 一次冻结只通知一次：切换内核是重动作，重复通知会让画面反复黑一下
        freezeNotified = true
        Logu.w("看门狗", "检测到画面冻结（${frozenMs}ms 无新帧），交由上层切换内核")
        listener.onVideoFrozen(frozenMs)
        return true
    }

    /**
     * 无首帧超时是否成立；成立则通知上层并返回 true（本次 tick 已处理完）。
     *
     * 与 [checkVideoFrozen] 的冻结分支共用 [freezeNotified] 做「一次为限」：
     * 对上层来说两者是同一类问题的两个形态，兜底动作也一样（切内核），
     * 一次会话里只需要触发一次，不该先报冻结再报无首帧。
     */
    private fun handleFirstFrameOverdue(): Boolean {
        val probe = firstFrameOverdueProbe ?: return false
        val overdue = try {
            probe()
        } catch (e: Exception) {
            Logu.w("看门狗", "首帧探测异常: " + e.message)
            false
        }
        if (!overdue || freezeNotified) return false
        freezeNotified = true
        Logu.w("看门狗", "就绪后超时仍无首帧，交由上层切换内核")
        listener.onFirstFrameTimeout()
        return true
    }

    private fun triggerRecovery(stalledMs: Long, whileBuffering: Boolean) {
        if (recoveryAttempts >= maxRecoveryAttempts) {
            running = false
            handler.removeCallbacks(tick)
            Logu.w("看门狗", "自动恢复次数用尽，停止看门狗")
            listener.onRecoveryExhausted()
            return
        }
        recoveryAttempts++
        // 重置计时，给恢复动作留出时间
        lastProgressAt = System.currentTimeMillis()
        bufferingSince = if (buffering) System.currentTimeMillis() else 0L
        Logu.w(
            "看门狗",
            "检测到播放卡死（${stalledMs}ms, buffering=$whileBuffering），执行第 $recoveryAttempts 次恢复"
        )
        listener.onPlaybackStalled(stalledMs, whileBuffering, recoveryAttempts)
    }
}
