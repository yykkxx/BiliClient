package com.RobinNotBad.BiliClient.activity.video.story

import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView

import com.RobinNotBad.BiliClient.activity.player.PlayerAudioPolicy
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
        /** 视频宽高（画面按真实比例摆放，避免 ijk 拉伸变形） */
        fun onPlayerVideoSize(width: Int, height: Int, sarNum: Int = 1, sarDen: Int = 1)
        /** 视频旋转角度（0/90/180/270，部分竖屏视频用旋转元数据表示） */
        fun onPlayerRotation(degrees: Int)
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

    /** 一键静音状态（长按弹幕按钮切换）：只改播放器音量，不动系统音量 */
    private var muted = false

    /** 当前是否处于缓冲中（首帧看门狗据此暂停计时，避免弱网被误判成黑屏） */
    private var buffering = false

    /** 首帧看门狗因「缓冲中」宽容的轮数：只宽容有限轮，防止弱网永远缓冲卡死 */
    private var watchdogBufferedRounds = 0

    private var currentUrl: String? = null
    private var currentHeaders: HashMap<String, String> = HashMap()
    private var startPosition = 0L

    /**
     * 最近一次收到的视频像素宽高比（SAR），默认 1:1。
     *
     * v5.0.2-alpha8（bug1）：画面比例要用「显示宽高 = 像素宽高 * SAR」，
     * 只在 onVideoSizeChanged 里拿得到；onPrepared 里补报尺寸时要带上它，
     * 否则重 prepare 后会用 1:1 覆盖掉真实 SAR。
     */
    private var videoSarNum = 1
    private var videoSarDen = 1

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
        buffering = false
        prepareRetry = 0
        //复用同一个播放器实例：每次重建 IjkMediaPlayer（native init）要几百毫秒，
        //短视频连续切换时非常明显。reset() 后重新 setDataSource 还能保留上一帧画面，
        //切换过程不会黑屏。
        val existing = player
        if (existing != null) {
            stopTimers()
            try {
                existing.stop()
            } catch (ignored: Exception) {
            }
            try {
                existing.reset()
            } catch (ignored: Exception) {
            }
            applyOptions(existing, hardwareCodec)
            pendingPlay = true
            tryStartPrepare()
        } else {
            releasePlayerInternal()
            createPlayer()
        }
        //复用/新建播放器后都要把静音状态补回去（reset 会把音量还原成 1.0）
        player?.let { applyVolume(it) }
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

    /** 是否已完成 prepare（弹幕控制器需要） */
    fun isPrepared(): Boolean = prepared

    /** 目标倍速：prepare 之前设置也能生效（在 onPrepared 里补应用） */
    private var speed = 1.0f

    /** 一键静音（长按弹幕按钮切换）。静音状态跨切集保留，新建/复用播放器后自动补应用 */
    fun setMuted(value: Boolean) {
        muted = value
        player?.let { applyVolume(it) }
    }

    fun isMuted(): Boolean = muted

    /** 把当前静音状态应用到播放器实例（新建 / 复用 / 软解重试后都要补一次） */
    private fun applyVolume(p: IjkMediaPlayer) {
        try {
            val volume = if (muted) 0f else 1f
            p.setVolume(volume, volume)
        } catch (e: Exception) {
            Logu.e("story", "设置音量失败：" + e.message)
        }
    }

    /** 倍速（长按倍速 / 倍速菜单共用） */
    fun setSpeed(newSpeed: Float) {
        // v5.0.2-alpha10：倍速上限由共享策略约束（SoundTouch 稳定区间 0.5~2.0），
        // 超过 2.0x 时 TDStretch 会把音节成段丢掉，听感就是「吞字」。
        val clamped = PlayerAudioPolicy.clampSpeed(newSpeed)
        speed = clamped
        try {
            player?.setSpeed(clamped)
        } catch (e: Exception) {
            Logu.e("story", "设置倍速失败：" + e.message)
        }
    }

    /** 拖动进度条跳转 */
    fun seekTo(positionMs: Long) {
        try {
            player?.seekTo(positionMs)
        } catch (e: Exception) {
            Logu.e("story", "seek 失败：" + e.message)
        }
    }

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
        applyVolume(p)

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
            //prepare 前设置的倍速在这里补上（ijk 在未 prepare 时 setSpeed 可能不生效）
            try {
                player?.setSpeed(speed)
            } catch (ignored: Exception) {
            }
            // v5.0.2-alpha8（bug1）：尺寸事件（FFP_MSG_VIDEO_SIZE_CHANGED）不一定早于
            // prepared 到达（硬解重新配置解码器时尤其容易晚），这里用 prepared 时
            // 已知的尺寸再补报一次，保证页面在开播前就能按真实比例摆好画面，
            // 而不是继续沿用上一支视频的 layoutParams（那正是「所有视频同一长宽比」的来源）。
            val preparedW = try {
                mp.videoWidth
            } catch (e: Exception) {
                0
            }
            val preparedH = try {
                mp.videoHeight
            } catch (e: Exception) {
                0
            }
            if (preparedW > 0 && preparedH > 0)
                listener.onPlayerVideoSize(preparedW, preparedH, videoSarNum, videoSarDen)
            player?.let { applyVolume(it) }
            if (wantPlay) {
                try {
                    mp.start()
                } catch (ignored: Exception) {
                }
            }
        }

        p.setOnVideoSizeChangedListener { _, width, height, sarNum, sarDen ->
            if (!released) {
                videoSarNum = if (sarNum > 0) sarNum else 1
                videoSarDen = if (sarDen > 0) sarDen else 1
                listener.onPlayerVideoSize(width, height, videoSarNum, videoSarDen)
            }
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
                    buffering = false
                    cancelWatchdog()
                    listener.onPlayerFirstFrame()
                }
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                    buffering = true
                    listener.onPlayerBuffering(true)
                }
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                    buffering = false
                    listener.onPlayerBuffering(false)
                }
                10001 /* MEDIA_INFO_VIDEO_ROTATION_CHANGED */ -> listener.onPlayerRotation(extra)
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
        // v5.0.2-alpha10：音频策略统一走 PlayerAudioPolicy（与主播放器同一份实现，
        // 避免 alpha8 那种「注释说一致、实际写反」的分叉）。
        // soundtouch 恒 1：1.0x 时上游守卫让它根本不进 SoundTouch（不会发闷），
        // >1.0x 时它是唯一保音调的变速实现。必须在 prepare 之前设置。
        PlayerAudioPolicy.applySoundtouch(p)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets")
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5L)
        // 短视频没有脉冲拉流策略，恒用连续缓冲；仍走共享策略以便与主播放器同一判定
        PlayerAudioPolicy.applyBufferingPolicy(p, speed, false)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 20L * 1024 * 1024)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1L)
        // UA 必须换掉 ijk 自带的，否则部分 CDN 403
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", NetWorkUtil.USER_AGENT_WEB)
        // 网络 IO 超时（微秒）：避免弱网时永远卡在 prepare
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000000L)
        // 降低探测开销：短视频时长很短，不需要很大的 probesize，首帧能更快出来
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 131072L)
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
        watchdogBufferedRounds = 0
        val task = object : Runnable {
            override fun run() {
                if (released || firstFrameRendered || !prepared) return
                if (!wantPlay) {
                    // 用户切到后台/暂停了，本来就不该出画面：等回来再计时，
                    // 否则会在后台被误判成"黑屏"而触发重试
                    handler.postDelayed(this, 2000)
                    return
                }
                if (buffering && watchdogBufferedRounds < MAX_BUFFERED_ROUNDS) {
                    // 弱网正在缓冲：此时没画面是正常的，不该判黑屏去切软解打断播放。
                    // 但只宽容 MAX_BUFFERED_ROUNDS 轮（每轮 2 秒），防止"永远缓冲"卡死。
                    watchdogBufferedRounds++
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

    private companion object {
        /** 首帧看门狗在「缓冲中」最多宽容几轮（每轮 2 秒） */
        const val MAX_BUFFERED_ROUNDS = 5
    }
}
