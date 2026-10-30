package com.RobinNotBad.BiliClient.activity.player

import android.os.Handler
import android.os.Looper

import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * DASH 高音质伴音轨（v5.0.2-alpha10）。
 *
 * ## 为什么需要它
 * 主播放器播的是 **durl**（`fnval=1` 的单文件 mp4，`PlayerApi.getVideo`），
 * 而 durl 里内嵌的音轨永远是「流畅」档：实测 aid=80433022 / cid=137649199 时
 * durl qn=16 总码率 396kbps = 视频 ~350kbps + **音频 44kbps**（mp4a.40.5 HE-AAC），
 * 即使切到 qn=64（总 1952kbps），音轨还是那 44kbps —— 这就是用户听到的「沉闷」。
 * 同一视频的 DASH 音轨有 30216=44k / 30232=103k / 30280=204k（mp4a.40.2）。
 *
 * 所以做法是：**主播放器继续播 durl，但静音**；用第二个 IjkMediaPlayer 单播 DASH 音轨，
 * 以主播放器时钟为准做同步。这样画面/看门狗/回退链路全都不动，只把声音换成高清音轨。
 *
 * ## 安全底线（比音质更重要）
 * 任何一步失败（8s 没 prepared / onError / 抛异常 / 拿不到地址）都立刻
 * [onFailback] 让页面把主播放器音量恢复成 1.0，**绝不允许「彻底没声音」**。
 *
 * ## 同步策略
 * - 主播放器是时钟权威；伴音轨在 prepared 后先 seek 到同一位置再起播；
 * - 每 ~1s（由页面的进度 Timer 驱动 [onTick]）校正一次，偏差 > 300ms 才 seek，
 *   且两次校正至少间隔 3s，避免来回抖动；
 * - 暂停/继续/拖动/倍速由页面显式转发（tick 也会兜底跟随播放状态）。
 */
class DashAudioCompanion(
        private val mainPlayerProvider: () -> IjkMediaPlayer?,
        private val onMuteMain: () -> Unit,
        private val onFailback: (String) -> Unit) {

    companion object {
        private const val TAG = "伴音轨"

        /** 准备超时：超过这个时间没 prepared 就放弃，恢复主播放器原声 */
        private const val PREPARE_TIMEOUT_MS = 8000L

        /** 偏差超过这个值才校正（小了没必要，反而容易抖动） */
        private const val DRIFT_SEEK_THRESHOLD_MS = 300L

        /** 两次校正之间的最小间隔（节流） */
        private const val DRIFT_SEEK_MIN_INTERVAL_MS = 3000L
    }

    private var player: IjkMediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var ready = false
    private var failed = false
    private var released = false
    private var wantPlaying = true
    private var speed = 1.0F
    private var startAtMs = 0L
    private var lastSeekAt = 0L

    private val timeoutTask = Runnable {
        if (!ready && !failed && !released)
            fail("准备超时（" + PREPARE_TIMEOUT_MS + "ms）")
    }

    /** 伴音轨是否已经接管（页面据此决定主播放器是否保持静音） */
    fun isReady(): Boolean = ready

    /**
     * 启动伴音轨。
     *
     * @param url DASH AAC 音轨地址
     * @param headers Referer / Cookie（部分 CDN 缺了会 403）
     * @param positionMs 与主播放器对齐的起始位置
     * @param speed 当前倍速（走 PlayerAudioPolicy 的变速策略）
     * @param play 当前是否在播
     */
    fun start(url: String, headers: Map<String, String>, positionMs: Long,
              speed: Float, play: Boolean) {
        if (released) return
        releaseInternal()

        this.startAtMs = positionMs
        this.speed = PlayerAudioPolicy.clampSpeed(speed)
        this.wantPlaying = play
        this.ready = false
        this.failed = false

        val p = try {
            IjkMediaPlayer()
        } catch (t: Throwable) {
            fail("创建伴音轨播放器失败：" + t.message)
            return
        }
        player = p

        try {
            // 纯音频：关掉视频解码，只留音频链路
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1L)
            // 变速引擎与主播放器同一套策略：≤2.0x 默认走系统变速（AudioTrack PlaybackParams），
            // >2.0x 或用户选了 SoundTouch 才用 App 层 TDStretch（后者咬字略糊，证据见 notes）。
            // 注意选项只在 prepare 前生效，所以要在 setDataSource/prepareAsync 之前调。
            PlayerAudioPolicy.applyPrepareOptions(p, this.speed,
                    SharedPreferencesUtil.getBoolean("player_audio", false))
            // 伴音轨必须连续缓冲：缺包填静音会让「音质修复」变成「吞字复现」
            PlayerAudioPolicy.applyBufferingPolicy(p, this.speed, false)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets")
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", NetWorkUtil.USER_AGENT_WEB)
            // 起播时机自己控制：默认的 start-on-prepared=1 会在 prepared 瞬间从 0 开始出声，
            // 那时还没 seek 到主播放器位置、主播放器也还没静音 —— 会听到「双声 + 开头一瞬」。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
            p.setVolume(1.0F, 1.0F)

            p.setOnPreparedListener { mp ->
                if (released || failed) return@setOnPreparedListener
                try {
                    if (startAtMs > 0)
                        mp.seekTo(startAtMs)
                    // setSpeed 只在 IjkMediaPlayer 上有，回调参数是 IMediaPlayer 接口
                    PlayerAudioPolicy.applySpeed(p, this.speed)
                    ready = true
                    handler.removeCallbacks(timeoutTask)
                    // 顺序很关键：先让主播放器静音，再让伴音轨出声，
                    // 这样既不会「静音等 8 秒」也不会出现两路声音重叠。
                    onMuteMain()
                    if (wantPlaying)
                        p.start()
                    Logu.v(TAG, "就绪，接管音频：pos=" + startAtMs + " speed=" + this.speed)
                } catch (e: Exception) {
                    fail("prepared 处理异常：" + e.message)
                }
            }
            p.setOnErrorListener { _, what, extra ->
                fail("onError what=" + what + " extra=" + extra)
                true
            }

            if (headers.isEmpty())
                p.setDataSource(url)
            else
                p.setDataSource(url, headers)

            p.prepareAsync()
            handler.postDelayed(timeoutTask, PREPARE_TIMEOUT_MS)
            Logu.v(TAG, "开始准备：" + url.take(80))
        } catch (e: Exception) {
            fail("设置数据源/准备异常：" + e.message)
        }
    }

    /** 由页面进度 Timer 每 ~1s 调用：跟随播放状态 + 漂移校正 */
    fun onTick() {
        if (released || failed || !ready) return
        val p = player ?: return
        val main = mainPlayerProvider() ?: return
        // 拆成两个小函数：detekt 的 CyclomaticComplexMethod 对单个函数有阈值
        followPlayState(p, main)
        correctDrift(p, main)
    }

    /** 跟随主播放器的播放/暂停状态（暂停时伴音轨必须一起停） */
    private fun followPlayState(p: IjkMediaPlayer, main: IjkMediaPlayer) {
        val mainPlaying = try {
            main.isPlaying()
        } catch (e: Exception) {
            Logu.d(TAG, "读取主播放器状态失败：" + e.message)
            return
        }
        if (mainPlaying == wantPlaying) return
        wantPlaying = mainPlaying
        try {
            if (mainPlaying) p.start() else p.pause()
        } catch (e: Exception) {
            Logu.d(TAG, "跟随播放状态失败：" + e.message)
        }
    }

    /** 音画漂移校正：>300ms 才 seek，且两次校正至少间隔 3s（节流，避免抖动） */
    private fun correctDrift(p: IjkMediaPlayer, main: IjkMediaPlayer) {
        if (!wantPlaying) return

        val mainPos: Long
        val avPos: Long
        try {
            mainPos = main.getCurrentPosition()
            avPos = p.getCurrentPosition()
        } catch (e: Exception) {
            Logu.d(TAG, "读取进度失败：" + e.message)
            return
        }
        val drift = mainPos - avPos
        if (Math.abs(drift) <= DRIFT_SEEK_THRESHOLD_MS) return

        val now = System.currentTimeMillis()
        if (now - lastSeekAt < DRIFT_SEEK_MIN_INTERVAL_MS) return
        lastSeekAt = now
        try {
            p.seekTo(mainPos)
            Logu.v(TAG, "校正音画：主=" + mainPos + " 伴=" + avPos + " 偏差=" + drift)
        } catch (e: Exception) {
            Logu.d(TAG, "校正 seek 失败：" + e.message)
        }
    }

    /** 倍速转发（页面切倍速/长按恢复/onPrepared 补应用都要调） */
    fun setSpeed(newSpeed: Float) {
        speed = PlayerAudioPolicy.clampSpeed(newSpeed)
        player?.let { PlayerAudioPolicy.applySpeed(it, speed) }
    }

    fun pause() {
        wantPlaying = false
        try {
            player?.pause()
        } catch (e: Exception) {
            Logu.d(TAG, "暂停失败：" + e.message)
        }
    }

    fun resume() {
        wantPlaying = true
        try {
            player?.start()
        } catch (e: Exception) {
            Logu.d(TAG, "继续失败：" + e.message)
        }
    }

    fun seekTo(positionMs: Long) {
        // 记一次时间戳：显式拖动后短时间内不再让 tick 校正，避免「拖动 → 校正」打架
        lastSeekAt = System.currentTimeMillis()
        try {
            player?.seekTo(positionMs)
        } catch (e: Exception) {
            Logu.d(TAG, "拖动同步失败：" + e.message)
        }
    }

    /** 页面退出 / 换源时调用（不会触发回退回调） */
    fun release() {
        released = true
        handler.removeCallbacks(timeoutTask)
        releaseInternal()
    }

    private fun fail(reason: String) {
        if (failed || released) return
        failed = true
        ready = false
        handler.removeCallbacks(timeoutTask)
        releaseInternal()
        Logu.e(TAG, "失败，回退主播放器原声：" + reason)
        onFailback(reason)
    }

    private fun releaseInternal() {
        ready = false
        val p = player
        player = null
        if (p == null) return
        // release 在 native 层会等播放线程退出，弱网时可能阻塞，放后台线程做
        CenterThreadPool.run(Runnable {
            try {
                p.stop()
            } catch (e: Exception) {
                Logu.d(TAG, "stop 异常：" + e.message)
            }
            try {
                p.release()
            } catch (e: Exception) {
                Logu.d(TAG, "release 异常：" + e.message)
            }
        })
    }
}
