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
    /**
     * 画面冻结的**确认窗口**（v5.3.0 新增；v5.3.1 从 6000 改到 2000）。
     *
     * v5.3.1 语义变更：这个值不再是「多久没帧算冻结」，而是「探测连续成立多久才采信」。
     * 真正的时长门槛下移到了供数方（`Media3Player.isRenderStalled`，12 秒），
     * 因为只有它知道「帧计数之外」的信号（位置是否在涨、画面是否挂着、是否刚 seek）。
     * 本类只做**消抖**：tick 是 1000ms，2000ms 意味着必须连续 3 拍都为 true 才通知，
     * 单拍抖动（某个 tick 恰好撞上解码器换档）不会再直接触发切内核。
     */
    private val freezeThresholdMs: Long = 2000L
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

    /**
     * 播放页当前是否可见（v5.3.1）。
     *
     * 不可见时（切后台、画中画被折叠、被全屏其它窗口遮挡）Surface 会销毁或不再出帧，
     * 而音频/位置照常 —— 不排除必然把「看不见」判成「冻住了」。
     * 默认 true：注入方（播放页）如果不调用 [onVisibilityChanged]，行为与 v5.3.0 一致。
     */
    @Volatile
    private var pageVisible = true

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

    /**
     * 本次冻结是否已经通知过上层（一次冻结只通知一次，避免刷屏/重复切内核）。
     *
     * v5.3.1：从原来与首帧超时**共用**一个标记拆成两个独立标记。
     * 原实现两处共用 `freezeNotified`，于是「冻结报过一次、但切换没成功」
     * （例如页面已兜底过一次）之后，首帧超时这条就**永远报不出来**了 ——
     * 两种形态虽然互斥（一个有首帧、一个没有），但跨 prepare 复用实例时
     * 前后两段媒体可以是不同形态，共用一个标记会让后一段彻底失去兜底。
     */
    @Volatile
    private var freezeNotified = false

    /** 本次「无首帧」是否已经通知过上层（与 [freezeNotified] 相互独立，见上） */
    @Volatile
    private var firstFrameNotified = false

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
        firstFrameNotified = false
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
        firstFrameNotified = false
    }

    fun setUserWantsPlay(wantsPlay: Boolean) {
        userWantsPlay = wantsPlay
        if (wantsPlay) {
            // 恢复播放时重新开始计时，否则会被误判为卡死
            lastProgressAt = System.currentTimeMillis()
            lastPosition = -1L
            // v5.3.1：冻结计时也必须一起清。
            // 原实现只清了进度计时，freezeSince 保留暂停前的值 ——
            // 「暂停时画面本来就不动」这一拍会把 freezeSince 置上，恢复后
            // 立刻满足「已冻了 N 秒」→ 刚点继续播就切内核。
            freezeSince = 0L
            freezeNotified = false
        }
    }

    fun setPrepared(isPrepared: Boolean) {
        prepared = isPrepared
        if (isPrepared) {
            lastProgressAt = System.currentTimeMillis()
            lastPosition = -1L
            // 同理：换P/切清晰度后是新的一段流，旧的冻结计时不能顺延（v5.3.1）
            freezeSince = 0L
            freezeNotified = false
            firstFrameNotified = false
        }
    }

    fun onBufferingStart() {
        buffering = true
        bufferingSince = System.currentTimeMillis()
        // v5.3.1：缓冲期间 check() 会整段跳过画面判定（缓冲时不出帧是正常的），
        // 于是 freezeSince 会**停在缓冲前**的值上。缓冲结束、画面若还没起来，
        // 第一拍就算出「已冻结 N 秒」→ 一次正常重缓冲被当成冻结切内核。
        // 这里把冻结计时清掉，缓冲结束后重新数。
        freezeSince = 0L
        freezeNotified = false
    }

    fun onBufferingEnd() {
        buffering = false
        bufferingSince = 0L
        // 缓冲结束后重新计时，给解码器一点恢复时间
        lastProgressAt = System.currentTimeMillis()
        lastPosition = -1L
        // 冻结计时也要清，与 [onBufferingStart] 和 [onSeek] 保持一致（v5.3.1）。
        // 只清 onBufferingStart 是不够的：缓冲期间的 freezeSince 已经归零，但缓冲**结束**
        // 后画面往往还要再等几拍才起来；若此时 freezeSince 又被置上而画面迟迟不出，
        // 会把「刚重缓冲完的正常等待」算进冻结时长。两端都清，语义最直白：
        // **缓冲这一段完全不参与冻结判定**。
        freezeSince = 0L
        freezeNotified = false
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

    /**
     * 页面可见性变化（v5.3.1 新增）。
     *
     * 为什么必要：切后台/画中画/被其它窗口完全遮挡时，Surface 会被销毁或不再出帧，
     * 而**音频仍在播、位置仍在涨** —— 这恰好是冻结判据的特征组合，不排除必然误判。
     * 本方法只清计时并置位「不可见」，不改变 [userWantsPlay]（用户意图没变，只是看不见）。
     */
    fun onVisibilityChanged(visible: Boolean) {
        pageVisible = visible
        freezeSince = 0L
        freezeNotified = false
        if (!visible) {
            // 不可见期间发现的「冻结」不作数，回到前台重新数
            Logu.d("看门狗", "页面不可见，冻结判定挂起")
        }
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
        // v5.3.1：页面不可见时也不判（Surface 被销毁/遮挡时不出帧是必然的）。
        if (!buffering && pageVisible && checkVideoFrozen(now)) return

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
        // v5.3.1：这里只做**消抖**。freezeThresholdMs 已改为 2000ms 的确认窗口，
        // 真正的时长门槛在供数方（位置是否在涨、画面是否挂着、是否刚 seek、帧计数 12 秒没动）。
        // 必须连续多拍都成立才采信，避免某一拍恰好撞上解码器换档就立刻切内核。
        if (frozenMs < freezeThresholdMs || freezeNotified) return false
        // 一次冻结只通知一次：切换内核是重动作，重复通知会让画面反复黑一下
        freezeNotified = true
        Logu.w("看门狗", "检测到画面冻结（已连续 ${frozenMs}ms 判定成立），交由上层切换内核")
        listener.onVideoFrozen(frozenMs)
        return true
    }

    /**
     * 无首帧超时是否成立；成立则通知上层并返回 true（本次 tick 已处理完）。
     *
     * v5.3.1：改用**独立的** [firstFrameNotified] 做「一次为限」，不再与冻结共用
     * [freezeNotified]。原实现共用会让「冻结报过一次没切成」之后首帧超时永久失效。
     * 注意两种形态本身仍是互斥的（由供数方保证：出过首帧就不判首帧超时），
     * 所以拆开标记不会造成同一 tick 双报。
     */
    private fun handleFirstFrameOverdue(): Boolean {
        val probe = firstFrameOverdueProbe ?: return false
        val overdue = try {
            probe()
        } catch (e: Exception) {
            Logu.w("看门狗", "首帧探测异常: " + e.message)
            false
        }
        if (!overdue || firstFrameNotified) return false
        firstFrameNotified = true
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
