package com.RobinNotBad.BiliClient.player

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkTimedText
import tv.danmaku.ijk.media.player.MediaInfo
import tv.danmaku.ijk.media.player.misc.IMediaDataSource
import tv.danmaku.ijk.media.player.misc.ITrackInfo

import java.io.FileDescriptor
import java.io.IOException
import java.util.Locale

/**
 * Media3（ExoPlayer）→ IMediaPlayer 适配器（5.1.2 实验播放内核，见 notes/player_lib_512.md）。
 *
 * 设计约束：
 * - 对调用方（PlayerActivity / StoryPlaybackController）完全透明：实现 ijk 的 IMediaPlayer，
 *   setOption 原样存表并做关键项映射，调用点代码一行不改。
 * - 线程模型：media3 要求主线程创建/操作。本类所有"动作"入口（start/pause/stop/reset/release/
 *   seekTo/prepareAsync 等）统一经 [runOnMain] 收敛到主线程执行——app 在 CenterThreadPool 上
 *   调 stop/release 的既有路径不用改。回调统一"回主线程再分发"（media3 事件本就在主线程，
 *   分发语义与 IjkMediaPlayer 的 EventHandler 最接近）。
 * - 取值入口（getCurrentPosition/getDuration/isPlaying）不能 post（要同步返回值）：主线程直读
 *   player，后台线程读镜像变量。media3 1.4.1 的这三个 getter 在非 application 线程上会直接抛
 *   IllegalStateException（throwsWhenUsingWrongThread=true，实测源码），镜像必须保留。
 * - 不开 handleAudioFocus / handleAudioBecomingNoisy：与 5.1.1 行为一致（app 只调系统音量，
 *   完全不管音频焦点，见 notes/player_lib_512.md R4）。
 */
@OptIn(UnstableApi::class)
class Media3Player(context: Context) : IMediaPlayer {

    private val appContext: Context = context.applicationContext
    private val mainLooper: Looper = Looper.getMainLooper()
    private val mainHandler = Handler(mainLooper)

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var released = false

    /** 拉流档位指纹：DefaultLoadControl 是 ExoPlayer 构造参数，option 变化时须重建实例 */
    private var bufferFingerprint: String? = null

    // setDataSource 只存参数，prepareAsync 时才真正交给 media3
    @Volatile
    private var dataSourceUrl: String? = null

    @Volatile
    private var dataSourceHeaders: Map<String, String>? = null

    /** ijk option 存表（name → 字符串值）；关键项在 prepareAsync 时映射进 media3 配置 */
    private val options = HashMap<String, String>()

    // 监听器（ijk 语义，原样转发）
    @Volatile
    private var preparedListener: IMediaPlayer.OnPreparedListener? = null

    @Volatile
    private var completionListener: IMediaPlayer.OnCompletionListener? = null

    @Volatile
    private var bufferingUpdateListener: IMediaPlayer.OnBufferingUpdateListener? = null

    @Volatile
    private var seekCompleteListener: IMediaPlayer.OnSeekCompleteListener? = null

    @Volatile
    private var videoSizeChangedListener: IMediaPlayer.OnVideoSizeChangedListener? = null

    @Volatile
    private var errorListener: IMediaPlayer.OnErrorListener? = null

    @Volatile
    private var infoListener: IMediaPlayer.OnInfoListener? = null

    @Volatile
    private var timedTextListener: IMediaPlayer.OnTimedTextListener? = null

    // 后台线程 getter 读的镜像状态（主线程 ticker / 事件回调持续刷新）
    @Volatile
    private var mirrorPositionMs = 0L

    @Volatile
    private var mirrorDurationMs = 0L

    @Volatile
    private var mirrorPlaying = false

    @Volatile
    private var videoWidthPx = 0

    @Volatile
    private var videoHeightPx = 0

    private var screenOn = false

    @Volatile
    private var pendingHolder: SurfaceHolder? = null

    @Volatile
    private var pendingSurface: Surface? = null

    private var preparedNotified = false
    private var completionNotified = false
    private var lastPlaybackState = Player.STATE_IDLE

    /** HTTP 数据源工厂：prepareAsync 时按当前 option / headers 重配，MediaSource 构建时引用 */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()

    // ------------------------------------------------------------------
    // IMediaPlayer 动作入口：全部收敛到主线程
    // ------------------------------------------------------------------

    override fun setDataSource(path: String) {
        dataSourceUrl = path
        dataSourceHeaders = null
    }

    override fun setDataSource(context: Context, uri: Uri) {
        dataSourceUrl = uri.toString()
        dataSourceHeaders = null
    }

    override fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>?) {
        dataSourceUrl = uri.toString()
        dataSourceHeaders = headers
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        dataSourceUrl = path
        dataSourceHeaders = headers
    }

    override fun setDataSource(fd: FileDescriptor) {
        // app 全部调用点都是 String/(String,headers)，没有 fd/IMediaDataSource 入口
        throw IOException("Media3Player 不支持 setDataSource(FileDescriptor)")
    }

    override fun setDataSource(mediaDataSource: IMediaDataSource) {
        throw IOException("Media3Player 不支持 setDataSource(IMediaDataSource)")
    }

    override fun getDataSource(): String? = dataSourceUrl

    override fun prepareAsync() {
        runOnMain { prepareOnMain() }
    }

    override fun start() {
        runOnMain { if (!released) player?.play() }
    }

    override fun pause() {
        runOnMain { if (!released) player?.pause() }
    }

    override fun stop() {
        runOnMain { if (!released) player?.stop() }
    }

    override fun seekTo(msec: Long) {
        runOnMain { if (!released) player?.seekTo(msec) }
    }

    override fun reset() {
        // story 连续切换复用实例：stop + 清列表，保留实例（media3 复用时 Surface 保留上一帧，
        // 与 story 注释里 ijk 的复用行为一致）
        runOnMain {
            val p = player ?: return@runOnMain
            p.stop()
            p.clearMediaItems()
            preparedNotified = false
            completionNotified = false
            lastPlaybackState = Player.STATE_IDLE
            videoWidthPx = 0
            videoHeightPx = 0
            mirrorPositionMs = 0L
            mirrorDurationMs = 0L
        }
    }

    override fun release() {
        released = true
        runOnMain {
            mainHandler.removeCallbacks(ticker)
            player?.release()
            player = null
        }
    }

    override fun setSpeed(speed: Float) {
        // Sonic 变速：0.5~3.0 保音调（ijk 的 soundtouch/2x 上限问题在 media3 侧不存在）
        runOnMain { if (!released) player?.playbackParameters = PlaybackParameters(speed) }
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        // app 实际从不调用（只用系统音量），保接口即可
        runOnMain { if (!released) player?.volume = leftVolume }
    }

    override fun setSurface(surface: Surface?) {
        pendingSurface = surface
        pendingHolder = null
        runOnMain { if (!released) player?.setVideoSurface(surface) }
    }

    override fun setDisplay(sh: SurfaceHolder?) {
        pendingHolder = sh
        pendingSurface = null
        runOnMain {
            if (released) return@runOnMain
            player?.setVideoSurfaceHolder(sh)
            applyScreenOn()
        }
    }

    override fun setScreenOnWhilePlaying(screenOn: Boolean) {
        this.screenOn = screenOn
        runOnMain { applyScreenOn() }
    }

    override fun setLooping(looping: Boolean) {
        runOnMain {
            if (!released) player?.repeatMode =
                if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    override fun isLooping(): Boolean {
        val p = player ?: return false
        return if (Looper.myLooper() == mainLooper) {
            p.repeatMode == Player.REPEAT_MODE_ONE
        } else false
    }

    override fun setOption(category: Int, name: String?, value: String?) {
        if (name == null) return
        synchronized(options) { options[name] = value ?: "" }
    }

    override fun setOption(category: Int, name: String?, value: Long) {
        if (name == null) return
        synchronized(options) { options[name] = value.toString() }
    }

    override fun setAudioStreamType(streamtype: Int) {
        // no-op：media3 固定走 STREAM_MUSIC 常规路径（notes/player_lib_512.md §3.3）
    }

    override fun setKeepInBackground(keepInBackground: Boolean) {
        // no-op
    }

    override fun setWakeMode(context: Context?, mode: Int) {
        // no-op：不持锁，与 ijk 现状调用一致（app 未使用）
    }

    override fun setLogEnabled(enable: Boolean) {
        // no-op
    }

    override fun isPlayable(): Boolean = true

    override fun setOnPreparedListener(listener: IMediaPlayer.OnPreparedListener?) {
        preparedListener = listener
    }

    override fun setOnCompletionListener(listener: IMediaPlayer.OnCompletionListener?) {
        completionListener = listener
    }

    override fun setOnBufferingUpdateListener(listener: IMediaPlayer.OnBufferingUpdateListener?) {
        bufferingUpdateListener = listener
    }

    override fun setOnSeekCompleteListener(listener: IMediaPlayer.OnSeekCompleteListener?) {
        seekCompleteListener = listener
    }

    override fun setOnVideoSizeChangedListener(listener: IMediaPlayer.OnVideoSizeChangedListener?) {
        videoSizeChangedListener = listener
    }

    override fun setOnErrorListener(listener: IMediaPlayer.OnErrorListener?) {
        errorListener = listener
    }

    override fun setOnInfoListener(listener: IMediaPlayer.OnInfoListener?) {
        infoListener = listener
    }

    override fun setOnTimedTextListener(listener: IMediaPlayer.OnTimedTextListener?) {
        timedTextListener = listener
    }

    override fun getVideoWidth(): Int = videoWidthPx

    override fun getVideoHeight(): Int = videoHeightPx

    override fun getVideoSarNum(): Int = 1

    override fun getVideoSarDen(): Int = 1

    override fun getCurrentPosition(): Long {
        val p = player ?: return mirrorPositionMs
        return if (Looper.myLooper() == mainLooper) p.currentPosition else mirrorPositionMs
    }

    override fun getDuration(): Long {
        val p = player ?: return mirrorDurationMs
        return if (Looper.myLooper() == mainLooper) clampDuration(p.duration) else mirrorDurationMs
    }

    override fun isPlaying(): Boolean {
        val p = player ?: return mirrorPlaying
        return if (Looper.myLooper() == mainLooper) p.isPlaying else mirrorPlaying
    }

    override fun getAudioSessionId(): Int {
        val p = player ?: return 0
        return if (Looper.myLooper() == mainLooper) p.audioSessionId else 0
    }

    override fun getTrackInfo(): Array<ITrackInfo> = arrayOf()

    override fun getMediaInfo(): MediaInfo? = null

    // ------------------------------------------------------------------
    // 内部：主线程执行
    // ------------------------------------------------------------------

    private fun prepareOnMain() {
        if (released) return
        val url = dataSourceUrl ?: return
        val p = obtainPlayer()

        // ---- HTTP 配置：UA / 超时 / 跨协议重定向 / headers ----
        // UA：ijk 路径两条调用链都会显式 setOption("user_agent", USER_AGENT_WEB)，
        // 这里缺省同样给 USER_AGENT_WEB，保证与 IjkPlayer 路径一致（部分 CDN 会 403）。
        httpDataSourceFactory.setUserAgent(optionString("user_agent", NetWorkUtil.USER_AGENT_WEB))
        // timeout 选项按 ijk 语义是微秒（story 传 15000000），换算成毫秒喂给 HTTP 栈
        val timeoutMs = (optionLong("timeout", DEFAULT_TIMEOUT_US) / 1000L).toInt()
        httpDataSourceFactory.setConnectTimeoutMs(timeoutMs)
        httpDataSourceFactory.setReadTimeoutMs(timeoutMs)
        // B 站 CDN 常见 http<->https 302：DefaultHttpDataSource 默认拒绝跨协议重定向，必须打开
        httpDataSourceFactory.setAllowCrossProtocolRedirects(true)
        httpDataSourceFactory.setDefaultRequestProperties(dataSourceHeaders ?: emptyMap())

        // ---- 起播行为：start-on-prepared（ijk 缺省 1=自动播；story 显式传 0 再手动 start）----
        p.playWhenReady = optionLong("start-on-prepared", 1L) == 1L

        // ---- 听视频模式：vn=1 直接禁视频轨（不解码不渲染）----
        val disableVideo = optionLong("vn", 0L) == 1L
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
            .build()

        p.setMediaSource(buildMediaSource(url))
        p.prepare()
    }

    private fun obtainPlayer(): ExoPlayer {
        val fingerprint = bufferFingerprintOf()
        val existing = player
        var p = if (existing != null && fingerprint != bufferFingerprint) {
            // 拉流档位变了（脉冲/持续/低延迟）：LoadControl 只能在构造时给，重建实例
            existing.release()
            player = null
            preparedNotified = false
            completionNotified = false
            lastPlaybackState = Player.STATE_IDLE
            null
        } else {
            existing
        }
        if (p != null) return p

        val created = ExoPlayer.Builder(appContext)
            .setLoadControl(buildLoadControl())
            .build()
        created.addListener(playerListener)
        created.addAnalyticsListener(analyticsListener)
        // setDisplay/setSurface 可能发生在 prepareAsync 之前（story/PlayerActivity 都是先设画面
        // 再 prepare）：建实例时补挂挂起的画面
        val surface = pendingSurface
        if (surface != null) {
            created.setVideoSurface(surface)
        } else {
            created.setVideoSurfaceHolder(pendingHolder)
        }
        applyScreenOn()
        player = created
        bufferFingerprint = fingerprint
        startTicker()
        p = created
        return p
    }

    private fun buildLoadControl(): DefaultLoadControl {
        // ijk 拉流 option → DefaultLoadControl 近似映射（非等价复刻，见 notes/player_lib_512.md R3）：
        //  - DefaultLoadControl 的天然节奏是「buffered < minBuffer 时持续读网，达到后停读，
        //    消耗到低于 minBuffer 再续读」——用 minBuffer 的高低模拟 ijk 的两档：
        //    packet-buffering=1（慢速持续）→ min 20s；
        //    packet-buffering=0（脉冲式）→ min 90s，一次拉足后停读，触底再拉；
        //  - max-buffer-size → targetBufferBytes（字节上限，先到者停）；
        //  - min-frames 是帧数语义，media3 无等价开关，忽略；起播缓冲用 500ms 近似。
        val pulse = optionLong("packet-buffering", 1L) == 0L
        val maxBufferBytes = optionLong("max-buffer-size", DEFAULT_MAX_BUFFER_BYTES)
            .coerceAtLeast(1L).toInt()
        val minBufferMs = if (pulse) MIN_BUFFER_PULSE_MS else MIN_BUFFER_CONTINUOUS_MS
        val maxBufferMs = if (pulse) MAX_BUFFER_PULSE_MS else MAX_BUFFER_CONTINUOUS_MS
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_REBUFFER_MS)
            .setTargetBufferBytes(maxBufferBytes)
            .build()
    }

    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val isHttp = url.startsWith("http://") || url.startsWith("https://")
        // 本地缓存文件走 FileDataSource（file:// 与纯路径都支持）；127.0.0.1 预加载代理是 http
        val dataSourceFactory: DataSource.Factory =
            if (isHttp) httpDataSourceFactory else FileDataSource.Factory()
        val policy: LoadErrorHandlingPolicy =
            if (optionLong("reconnect", 1L) == 1L) {
                // reconnect=1（两条调用链的固定配置）：网络错误按默认策略退避重试
                DefaultLoadErrorHandlingPolicy()
            } else {
                NoRetryLoadErrorHandlingPolicy()
            }
        val path = (uri.path ?: url).lowercase(Locale.ROOT)
        return if (path.endsWith(".m3u8")) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory, DefaultExtractorsFactory())
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(uri))
        }
    }

    private fun applyScreenOn() {
        val holder = pendingHolder ?: return
        try {
            holder.setKeepScreenOn(screenOn)
        } catch (ignored: Exception) {
        }
    }

    private fun bufferFingerprintOf(): String {
        val packetBuffering = optionLong("packet-buffering", 1L)
        val maxBuffer = optionLong("max-buffer-size", DEFAULT_MAX_BUFFER_BYTES)
        return "pb=$packetBuffering,mbs=$maxBuffer"
    }

    private fun optionString(name: String, def: String): String {
        synchronized(options) { return options[name] ?: def }
    }

    private fun optionLong(name: String, def: Long): Long {
        synchronized(options) {
            val raw = options[name] ?: return def
            return raw.toLongOrNull() ?: def
        }
    }

    private fun clampDuration(durationMs: Long): Long {
        // C.TIME_UNSET → 0：app 把 duration<=0 当"未就绪"，不 clamp 会崩进度条（notes §3.3）
        return if (durationMs == C.TIME_UNSET || durationMs < 0) 0L else durationMs
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    /** 非 main 调用方一律 post 回主线程（动作与回调分发共用） */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == mainLooper) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun notifyInfo(what: Int, extra: Int) {
        runOnMain {
            val listener = infoListener ?: return@runOnMain
            listener.onInfo(this, what, extra)
        }
    }

    // ------------------------------------------------------------------
    // 事件翻译（media3 → ijk 语义）
    // ------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released) return
            if (playbackState == Player.STATE_BUFFERING) {
                notifyInfo(IMediaPlayer.MEDIA_INFO_BUFFERING_START, 0)
            } else if (lastPlaybackState == Player.STATE_BUFFERING) {
                // 离开缓冲态（READY/ENDED/IDLE）→ 702，与 ijk 的缓冲结束事件对齐
                notifyInfo(IMediaPlayer.MEDIA_INFO_BUFFERING_END, 0)
            }
            when (playbackState) {
                Player.STATE_READY -> {
                    // 首次 READY 当 onPrepared（media3 无独立 prepared 回调）；
                    // 复用实例再 prepare 时由 reset()/重建逻辑重置标记
                    if (!preparedNotified) {
                        preparedNotified = true
                        runOnMain { preparedListener?.onPrepared(this@Media3Player) }
                    }
                }
                Player.STATE_ENDED -> {
                    if (!completionNotified) {
                        completionNotified = true
                        runOnMain { completionListener?.onCompletion(this@Media3Player) }
                    }
                }
                else -> Unit
            }
            lastPlaybackState = playbackState
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            mirrorPlaying = playWhenReady && lastPlaybackState == Player.STATE_READY
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mirrorPlaying = isPlaying
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                runOnMain { seekCompleteListener?.onSeekComplete(this@Media3Player) }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoWidthPx = videoSize.width
            videoHeightPx = videoSize.height
            val rotation = videoSize.unappliedRotationDegrees
            if (rotation > 0) {
                // MEDIA_INFO_VIDEO_ROTATION_CHANGED：story 的画面旋转依赖该事件
                notifyInfo(IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED, rotation)
            }
            runOnMain {
                videoSizeChangedListener?.onVideoSizeChanged(
                    this@Media3Player, videoSize.width, videoSize.height, 1, 1)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues.firstNotNullOfOrNull { it.text?.toString() } ?: return
            runOnMain {
                timedTextListener?.onTimedText(this@Media3Player, IjkTimedText(Rect(), text))
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Logu.e(TAG, "media3 播放错误：" + error.errorCodeName + " " + (error.message ?: ""))
            runOnMain {
                val listener = errorListener ?: return@runOnMain
                // app 两侧都不解释 what/extra 码值（只记日志/统一降级重试），
                // 统一映射 what=UNKNOWN，extra=media3 errorCode（notes/player_lib_512.md R8）
                listener.onError(this@Media3Player, IMediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode)
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            // MEDIA_INFO_VIDEO_RENDERING_START(3)：story 的 6s 首帧看门狗依赖
            notifyInfo(IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0)
        }
    }

    /** 500ms 轻量轮询：驱动 onBufferingUpdate + 刷新后台线程 getter 用的镜像状态 */
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val p = player
            if (p != null) {
                mirrorPositionMs = p.currentPosition
                mirrorDurationMs = clampDuration(p.duration)
                mirrorPlaying = p.isPlaying
                val state = p.playbackState
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    val listener = bufferingUpdateListener
                    if (listener != null) {
                        // media3 无周期缓冲回调，由这里模拟（保持 app 的副进度条逻辑不变）
                        val percent = (p.bufferedPercentage * 100f).toInt().coerceIn(0, 100)
                        listener.onBufferingUpdate(this@Media3Player, percent)
                    }
                }
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private class NoRetryLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            // reconnect!=1：不重试，立即把错误抛给 onPlayerError（app 有自己的重试兜底）
            return C.TIME_UNSET
        }
    }

    private companion object {
        const val TAG = "Media3Player"

        /** 轮询间隔：与 PlayerActivity 的进度刷新节奏匹配 */
        const val POLL_INTERVAL_MS = 500L

        /** max-buffer-size 缺省值：与 PlayerActivity 默认档一致（20MB） */
        const val DEFAULT_MAX_BUFFER_BYTES = 20L * 1024L * 1024L

        /** 脉冲档 minBuffer：一次拉足（≈90s）再停读，触底续读（近似 ijk 的 packet-buffering=0） */
        const val MIN_BUFFER_PULSE_MS = 90_000
        const val MAX_BUFFER_PULSE_MS = 120_000

        /** 持续档 minBuffer：边播边平滑缓冲（≈20s，对应 PlayerActivity 默认 20MB） */
        const val MIN_BUFFER_CONTINUOUS_MS = 20_000
        const val MAX_BUFFER_CONTINUOUS_MS = 60_000

        /** 起播/卡顿恢复缓冲（秒级起播优先；对齐 ijk 的快速起播体验） */
        const val BUFFER_FOR_PLAYBACK_MS = 500
        const val BUFFER_FOR_REBUFFER_MS = 1_000

        /** HTTP 超时缺省（微秒）：与 story 的 timeout option 一致（15s） */
        const val DEFAULT_TIMEOUT_US = 15_000_000L
    }
}
