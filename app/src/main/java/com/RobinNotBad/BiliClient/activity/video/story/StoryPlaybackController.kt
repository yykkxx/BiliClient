package com.RobinNotBad.BiliClient.activity.video.story

import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 短视频播放内核（v5.0.1-alpha5 重构）。
 *
 * 为什么不再自己 new TextureView：
 * - 原实现用 TextureView + Surface，首帧 attach 前 surfaceTexture 为 null（alpha4 已补），
 *   但手表上 TextureView 依赖硬件加速层，部分设备上 MediaCodec 输出到 SurfaceTexture
 *   会整块黑屏；主播放器一直用 **SurfaceView** 且工作正常，这里统一改成 SurfaceView。
 * - 播放器参数与 PlayerActivity 对齐（framedrop / analyzeduration / reconnect / user_agent），
 *   不再只设 mediacodec 一个选项。
 *
 * 另外补两条保命机制（原实现完全没有，所以只能看到黑屏）：
 * 1. **首帧看门狗**：prepared 后 6 秒还没收到 MEDIA_INFO_VIDEO_RENDERING_START，
 *    判定为解码/渲染失败，自动从硬解切软解重试（保留播放进度）。
 * 2. **错误自动降级**：IjkMediaPlayer 报错时同样先切软解，软解也失败才向上报错，
 *    由页面层换备用线路或跳下一个。
 */
class StoryPlaybackController(
    private val surfaceView: SurfaceView,
    private val listener: Listener
) : SurfaceHolder.Callback {

    interface Listener {
        fun onPlayerPrepared(durationMs: Long)
        fun onPlayerProgress(positionMs: Long, durationMs: Long)
        fun onPlayerBuffering(buffering: Boolean)
        fun onPlayerFirstFrame()
        fun onPlayerCompletion()
        fun onPlayerError(what: Int, extra: Int)
        /** 解码方式自动切换（true=硬解 false=软解），页面可提示用户 */
        fun onPlayerCodecChanged(hardware: Boolean)
        /** 视频宽高（圆屏适配用：把画面缩进圆形安全区） */
        fun onPlayerVideoSize(width: Int, height: Int)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var player: IjkMediaPlayer? = null
    private var surfaceReady = false
    private var prepared = false
    private var firstFrameRendered = false
    private var hardwareCodec = true
    private var wantPlay = true
    private var released = false
    private var pendingPlay = false
    private var prepareRetry = 0

    private var currentUrl: String? = null
    private var currentHeaders: HashMap<String, String> = HashMap()
    private var startPosition = 0L

    private var watchdog: Runnable? = null
    private var prepareWatchdog: Runnable? = null
    private var progressTask: Runnable? = null

    init {
        try {
            surfaceView.holder.addCallback(this)
        } catch (e: Exception) {
            Logu.e("story", "注册 Surface 回调失败：" + e.message)
        }
    }

    // ======================== 对外接口 ========================

    /**
     * 播放一个直链。
     * @param hardware true=MediaCodec 硬解，false=软解
     */
    fun play(url: String, headers: Map<String, String>, startPositionMs: Long = 0L, hardware: Boolean = true) {
        if (released) return
        currentUrl = url
        currentHeaders = HashMap(headers)
        startPosition = if (startPositionMs > 0) startPositionMs else 0L
        hardwareCodec = hardware
        wantPlay = true
        firstFrameRendered = false
        prepared = false
        prepareRetry = 0
        releasePlayerInternal()
        createPlayer()
        if (player != null) startPrepareWatchdog()
    }

    fun pause() {
        wantPlay = false
        try {
            player?.pause()
        } catch (ignored: Exception) {
        }
    }

    fun resume() {
        wantPlay = true
        try {
            player?.start()
        } catch (ignored: Exception) {
        }
    }

    fun isPlaying(): Boolean {
        return try {
            player?.isPlaying ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun currentPosition(): Long {
        return try {
            player?.currentPosition ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun currentDuration(): Long {
        return try {
            player?.duration ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun isHardwareCodec(): Boolean = hardwareCodec

    /** 释放播放器（保留 Surface 回调，页面退出时调用） */
    fun release() {
        released = true
        stopTimers()
        try {
            surfaceView.holder.removeCallback(this)
        } catch (ignored: Exception) {
        }
        releasePlayerInternal()
    }

    // ======================== 内部实现 ========================

    private fun createPlayer() {
        val p = try {
            IjkMediaPlayer()
        } catch (t: Throwable) {
            Logu.e("story", "创建播放器失败：" + t.message)
            listener.onPlayerError(-2, -2)
            return
        }
        player = p
        applyOptions(p, hardwareCodec)

        p.setOnPreparedListener { mp ->
            if (released) return@setOnPreparedListener
            prepared = true
            prepareRetry = 0
            cancelPrepareWatchdog()
            val duration = try {
                mp.duration
            } catch (e: Exception) {
                0L
            }
            listener.onPlayerPrepared(duration)
            startProgress()
            startWatchdog()
            if (startPosition > 0) {
                try {
                    mp.seekTo(startPosition)
                } catch (ignored: Exception) {
                }
            }
            if (wantPlay) {
                try {
                    mp.start()
                } catch (ignored: Exception) {
                }
            }
        }

        p.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            if (!released) listener.onPlayerVideoSize(width, height)
        }

        p.setOnCompletionListener {
            if (released) return@setOnCompletionListener
            listener.onPlayerCompletion()
        }

        p.setOnErrorListener { _, what, extra ->
            if (released) return@setOnErrorListener true
            Logu.e("story", "播放器错误 what=" + what + " extra=" + extra)
            handlePlaybackFailure(what, extra)
            true
        }

        p.setOnInfoListener { _, what, extra ->
            if (released) return@setOnInfoListener false
            when (what) {
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    firstFrameRendered = true
                    cancelWatchdog()
                    listener.onPlayerFirstFrame()
                }
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> listener.onPlayerBuffering(true)
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> listener.onPlayerBuffering(false)
            }
            false
        }

        pendingPlay = true
        tryStartPrepare()
    }

    /** Surface 就绪才 setDisplay + prepareAsync（与主播放器一致的回调驱动模式） */
    private fun tryStartPrepare() {
        val p = player ?: return
        if (!pendingPlay) return
        // 不能在这里 return on !surfaceReady：如果 addCallback 时 Surface 已经创建，
        // surfaceCreated 不会再回调，surfaceReady 永远是 false，结果就是永远不开始播放
        // （这正是旧版"黑屏且毫无信息"的一个来源）。

        val holder: SurfaceHolder = surfaceView.holder
        // 注意：SurfaceHolder.addCallback 在 Surface 已创建之后才注册时**不会**再回调
        // surfaceCreated（主播放器也是因此才额外检查一次 isValid），所以这里以
        // 「holder.surface 是否有效」为准，surfaceReady 只作辅助。
        val surfaceOk = try {
            holder.surface != null && holder.surface.isValid
        } catch (e: Exception) {
            false
        }
        if (!surfaceOk) {
            // 最多等 5 秒（100 × 50ms）：弱机上 Surface 创建可能明显慢于布局
            if (prepareRetry < 100) {
                prepareRetry++
                handler.postDelayed({ tryStartPrepare() }, 50)
            } else {
                Logu.e("story", "等待 Surface 超时，无法开始播放")
                listener.onPlayerError(-5, -5)
            }
            return
        }
        surfaceReady = true

        pendingPlay = false
        try {
            p.setDisplay(holder)
        } catch (t: Throwable) {
            Logu.e("story", "setDisplay 失败：" + t.message)
            // 重试几次，Surface 刚创建时偶发拿不到
            if (prepareRetry < 20) {
                prepareRetry++
                pendingPlay = true
                handler.postDelayed({ tryStartPrepare() }, 50)
            } else {
                listener.onPlayerError(-3, -3)
            }
            return
        }

        val url = currentUrl ?: return
        try {
            p.setDataSource(url, currentHeaders)
            p.setScreenOnWhilePlaying(true)
            p.prepareAsync()
            Logu.d("story", "开始 prepare：" + url.take(80))
        } catch (t: Throwable) {
            Logu.e("story", "prepareAsync 失败：" + t.message)
            handlePlaybackFailure(-4, -4)
        }
    }

    private fun applyOptions(p: IjkMediaPlayer, hardware: Boolean) {
        //与 PlayerActivity#setDisplay 保持一致的参数集
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (hardware) 1L else 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 4L)
        //start 由 onPrepared 自己控制，否则暂停状态会被 start-on-prepared 覆盖
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets")
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 20L * 1024 * 1024)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1L)
        // UA 必须换掉 ijk 自带的，否则部分 CDN 403
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", NetWorkUtil.USER_AGENT_WEB)
        // 网络 IO 超时（微秒）：避免弱网时永远卡在 prepare
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000L)
    }

    private fun handlePlaybackFailure(what: Int, extra: Int) {
        if (released) return
        if (hardwareCodec) {
            //硬解失败/无首帧：先自动切软解重试一次（保留进度）
            val pos = currentPosition()
            hardwareCodec = false
            Logu.e("story", "硬解失败（" + what + "/" + extra + "），回退软解 @" + pos)
            listener.onPlayerCodecChanged(false)
            val url = currentUrl
            if (url.isNullOrEmpty()) {
                listener.onPlayerError(what, extra)
                return
            }
            play(url, currentHeaders, pos, false)
        } else {
            listener.onPlayerError(what, extra)
        }
    }

    /** prepare 也要有超时：弱网下 prepareAsync 可能永远不回调，页面会一直"缓冲中" */
    private fun startPrepareWatchdog() {
        cancelPrepareWatchdog()
        val task = object : Runnable {
            override fun run() {
                if (released || prepared) return
                if (!wantPlay) {
                    handler.postDelayed(this, 5000)
                    return
                }
                Logu.e("story", "prepare 超过 20 秒仍未完成")
                handlePlaybackFailure(-101, -101)
            }
        }
        prepareWatchdog = task
        handler.postDelayed(task, 20000)
    }

    private fun cancelPrepareWatchdog() {
        prepareWatchdog?.let { handler.removeCallbacks(it) }
        prepareWatchdog = null
    }

    private fun startWatchdog() {
        cancelWatchdog()
        val task = object : Runnable {
            override fun run() {
                if (released || firstFrameRendered || !prepared) return
                if (!wantPlay) {
                    // 用户切到后台/暂停了，本来就不该出画面：等回来再计时，
                    // 否则会在后台被误判成"黑屏"而触发重试
                    handler.postDelayed(this, 2000)
                    return
                }
                Logu.e("story", "prepared 后 6 秒仍无首帧，判定黑屏")
                if (hardwareCodec) {
                    handlePlaybackFailure(-100, -100)
                } else {
                    listener.onPlayerError(-100, -100)
                }
            }
        }
        watchdog = task
        handler.postDelayed(task, 6000)
    }

    private fun cancelWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    private fun startProgress() {
        stopProgress()
        val task = object : Runnable {
            override fun run() {
                if (released) return
                val p = player
                if (p != null && prepared) {
                    try {
                        val dur = p.duration
                        val pos = p.currentPosition
                        if (dur > 0 && pos >= 0) listener.onPlayerProgress(pos, dur)
                    } catch (ignored: Exception) {
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        progressTask = task
        handler.postDelayed(task, 500)
    }

    private fun stopProgress() {
        progressTask?.let { handler.removeCallbacks(it) }
        progressTask = null
    }

    private fun stopTimers() {
        cancelWatchdog()
        cancelPrepareWatchdog()
        stopProgress()
    }

    private fun releasePlayerInternal() {
        val p = player
        player = null
        prepared = false
        pendingPlay = false
        try {
            p?.setDisplay(null)
        } catch (ignored: Exception) {
        }
        try {
            p?.stop()
        } catch (ignored: Exception) {
        }
        try {
            p?.release()
        } catch (ignored: Exception) {
        }
    }

    // ======================== Surface 生命周期 ========================

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (released) return
        val p = player ?: return
        if (pendingPlay) {
            tryStartPrepare()
        } else if (prepared) {
            //从后台回来：重新绑定 Surface 并恢复进度/播放状态
            try {
                p.setDisplay(holder)
                val pos = currentPosition()
                if (pos > 0) p.seekTo(pos)
                if (wantPlay) p.start()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        try {
            player?.setDisplay(null)
        } catch (ignored: Exception) {
        }
    }
}
