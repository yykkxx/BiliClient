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
    private val maxRecoveryAttempts: Int = 3
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

    private fun check() {
        if (!running || !prepared || !userWantsPlay) return

        val now = System.currentTimeMillis()

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
