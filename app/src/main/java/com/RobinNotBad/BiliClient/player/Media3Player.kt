package com.RobinNotBad.BiliClient.player

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
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
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * B 站 DASH 的**音频轨**独立地址（v5.2.1 新增，修「有画面没声音 / 有声音没画面」）。
     *
     * 为什么需要单独一个字段：B 站的 DASH 把音频和视频放在**两个不同的 URL** 上
     * （`dash.audio[].baseUrl` / `dash.video[].baseUrl`），而 `PlayerData` 一直有两个字段
     * （`videoUrl` / `audioUrl`），但旧代码**只把 videoUrl 传进播放器**（`buildPlayerIntent`
     * 里没有 audioUrl），于是 media3 拿到的单条流只有视频轨 —— 表现为"能播但没声音"，
     * 或者某些分片结构下直接起不来。ijk（ffmpeg）对单轨流的容忍度更高，所以旧版用 ijk 时
     * 这个问题没被发现。
     *
     * 现在用 `MergingMediaSource(video, audio)` 合并成一条完整媒体源。
     * 为 null 时不合并（纯视频/本地文件/直播，行为与改动前一致）。
     */
    @Volatile
    private var dataSourceAudioUrl: String? = null

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

    // 平滑外插锚点（v5.1.3）：后台线程（弹幕绘制线程每帧）读位置不能等 500ms ticker，
    // 否则弹幕时间轴一格一格跳（真机实测「Media3 弹幕很卡」）。锚点 = 最近一次主线程采样，
    // 后台读 = 锚点 + 经过墙钟 × 倍速；暂停时静止。
    @Volatile
    private var mirrorPositionAtRealtimeMs = 0L

    @Volatile
    private var mirrorSpeed = 1f

    @Volatile
    private var videoWidthPx = 0

    @Volatile
    private var videoHeightPx = 0

    private var screenOn = false

    @Volatile
    private var pendingHolder: SurfaceHolder? = null

    @Volatile
    private var pendingSurface: Surface? = null

    // ---- 真实下载速率（v5.1.5）------------------------------------------
    // media3 没有 ijk 的 getTcpSpeed()，也没有"已下载字节数"的公开 getter。这里用官方
    // TransferListener（BaseDataSource.bytesTransferred 每次网络 read 回调一次，
    // DefaultBandwidthMeter 内部用的正是同一信号）在**加载线程**累加真实字节，
    // 再由已有的 500ms ticker 在**主线程**做窗口差分 → B/s。不包 DataSource，不碰 ExoPlayer
    // 内部状态，续传/重试产生的字节也都是真实网络流量。
    private val transferredBytes = AtomicLong(0L)

    /** 上一采样窗口的字节快照（只由主线程 ticker 写） */
    private var speedSampleBytes = 0L

    /** 上一采样时刻；0 表示尚无基线（下一个窗口只建基线不出值） */
    private var speedSampleAtMs = 0L

    /** 对外发布的速率（B/s）：后台/Timer 线程无锁读 */
    @Volatile
    private var tcpSpeedBytesPerSec = 0L

    /** 挂到 HTTP DataSource.Factory 上；file:// 本地播放不计（不是网络流量） */
    private val transferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            // 无需处理：只关心字节增量
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            // 无需处理：起止时刻由 ticker 的窗口边界决定
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            // 加载线程回调：只做一次原子累加，不做任何分发/加锁
            if (isNetwork && bytesTransferred > 0) transferredBytes.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            // 无需处理：窗口内没有新字节时 ticker 会自动归零（暂停/缓冲满/停读）
        }
    }

    private var preparedNotified = false
    private var completionNotified = false
    private var lastPlaybackState = Player.STATE_IDLE

    // ---- 画面冻结观测（v5.3.0）--------------------------------------------
    // 为什么需要这三个字段：用户反馈「media3 播视频时画面停在开头不动，但声音、弹幕、
    // 网速都正常」。这类故障**不产生 onPlayerError**（解码器没报错，只是不再吐新帧），
    // 所以 PlayerActivity 的「onError 才切 ijk」兜底永远不触发；PlaybackWatchdog 又只
    // 看「播放位置是否推进」—— 音视频合并后位置由音频驱动照常前进，它同样不触发。
    // 两个监控同时失效，用户只能退出重进。
    //
    // 判据必须回答「**解码器到底有没有继续输出新帧**」，位置和缓冲状态都回答不了。
    // media3 1.4.1 的 ExoPlayer.getVideoDecoderCounters().renderedOutputBufferCount
    // 就是这个信号：它是**单调递增的真实渲染帧数**（javap 核对过该公开字段）。
    // 用它做差分比拦截逐帧回调便宜得多（不占渲染热路径、不新建任何对象）。
    //
    // 三重信号分工：
    // - [firstFrameAtMs]：AnalyticsListener.onRenderedFirstFrame 置位。为 0 表示
    //   「还没出图」，此时一律不判冻结（起播缓冲期本来就没有帧，否则必然误判）；
    // - [renderedFrameCount]：ticker 每 500ms 采样的渲染帧计数镜像；
    // - [lastRenderedFrameAtMs]：最近一次观测到「帧数变了」的时刻，冻结判据的锚点。
    @Volatile
    private var firstFrameAtMs = 0L

    @Volatile
    private var lastRenderedFrameAtMs = 0L

    private var renderedFrameCount = 0

    /** 「本次 prepare 进入 STATE_READY」的时刻：首帧超时判据的起算点（0 = 还没 READY） */
    @Volatile
    private var readyAtMs = 0L

    /**
     * 是否对上层「视频轨在解码但渲染帧不推进」的冻结状态作出判定（v5.3.0）。
     *
     * 由 `PlayerActivity` 在引擎是 Media3 且非听视频模式时置 true。
     * 为什么需要开关而不是一直判：听视频模式（vn=1）本来就禁了视频轨、不会渲染帧，
     * 若照判必然误报；纯音频文件同理。
     */
    @Volatile
    private var renderStallMonitorEnabled = false

    /**
     * 是否存在一条**在解码但没出帧**的视频轨（等价于「画面冻住」）。
     *
     * 判据（三个条件必须同时成立，缺一不可，见 [updateRenderStallSample]）：
     * 1. 播放器在播（`isPlaying`）且不处于缓冲态 —— 暂停/缓冲时不出帧是正常的；
     * 2. 已经出过首帧（[firstFrameAtMs] > 0）—— 起播前没有帧也是正常的；
     * 3. 渲染帧计数在 [RENDER_STALL_TIMEOUT_MS] 内**一次都没涨过** ——
     *    注意这里用「计数没涨」而不是「位置没动」：位置由音频轨驱动，
     *    画面冻住时它照常前进，这正是旧看门狗失明的原因。
     *
     * 供 `PlayerActivity` 的冻结兜底轮询调用（任意线程安全：只读 @Volatile 字段，
     * 唯一的内存读写都发生在主线程 ticker 里）。
     */
    fun isRenderStalled(): Boolean {
        if (!renderStallMonitorEnabled || released) return false
        val first = firstFrameAtMs
        if (first <= 0L) return false
        val last = lastRenderedFrameAtMs
        if (last <= 0L) return false
        return SystemClock.elapsedRealtime() - last >= RENDER_STALL_TIMEOUT_MS
    }

    /** 是否已经出过首帧（供上层区分「黑屏起不来」与「出过图后冻住」） */
    fun hasRenderedFirstFrame(): Boolean = firstFrameAtMs > 0L

    /**
     * prepare 完成之后多久仍然一帧都没渲染出来（v5.3.0）。
     *
     * 与 [isRenderStalled] 是**互补**的两条判据，覆盖两个不同的用户症状：
     * - 这条管「黑屏起不来」：`STATE_READY` 了（音频都出来了）却完全没有首帧 ——
     *   典型是硬解器初始化成功但 surface 上不出图，**不报错**；
     * - [isRenderStalled] 管「出过图之后冻住」：用户本轮反馈的那个。
     *
     * 为什么两者都要：media3 在「视频轨解码失败但音频正常」时既不报 onPlayerError，
     * 也不会让位置停住；上一轮修的「onError 才切 ijk」对这两种形态都够不着。
     *
     * @return true 表示超时无首帧，上层应切内核
     */
    fun isFirstFrameOverdue(): Boolean {
        if (!renderStallMonitorEnabled || released) return false
        if (firstFrameAtMs > 0L) return false
        val readyAt = readyAtMs
        if (readyAt <= 0L) return false
        return SystemClock.elapsedRealtime() - readyAt >= FIRST_FRAME_TIMEOUT_MS
    }

    /** 冻住之后要重置观测锚点（兜底动作执行前调用）。 */
    fun resetRenderStallAnchor() {
        renderedFrameCount = currentRenderedFrameCount()
        lastRenderedFrameAtMs = SystemClock.elapsedRealtime()
    }

    /** HTTP 数据源工厂：prepareAsync 时按当前 option / headers 重配，MediaSource 构建时引用 */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()

    /**
     * 最近一次采样窗口内的真实下载速率（字节/秒），单位与 ijk 的 getTcpSpeed() 对齐。
     *
     * 供 PlayerActivity 的缓冲网速显示使用：读的是一个 @Volatile 字段，无锁、无分发，
     * 可以在任意线程（含 java.util.Timer 线程）安全调用，不会碰 ExoPlayer 内部状态
     * （media3 要求主线程访问，见类注释）。未起播/暂停/已 release 时返回 0。
     */
    fun getTcpSpeed(): Long = if (released) 0L else tcpSpeedBytesPerSec

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

    /**
     * 该地址是否**极可能**是 FLV（v5.2.1，配合 PlayerActivity 的「开局就换内核」判断）。
     *
     * ## 为什么需要这个判断（先纠正一个流传下来的误解）
     * 本仓库此前的笔记（HANDOFF §3.4）曾说「media3 1.4.1 没有 FLV 提取器」，**这个说法是错的**。
     * 本轮实测：解包
     * `media3-extractor-1.4.1.aar` → `classes.jar` 里**存在**
     * `androidx/media3/extractor/flv/FlvExtractor.class`，
     * 且 `DefaultExtractorsFactory.addExtractorsForFileType` 的 `tableswitch` 分支
     * (case 5) 会 `new FlvExtractor()` 并加进列表，`DEFAULT_EXTRACTOR_ORDER` 数组里
     * 也含它（`Java.long` 核对过 21 项初始化的前 12 项）。
     * **所以「media3 完全不能播 FLV」不成立**，FLV 不是主因。
     *
     * ## 那为什么还要这个判断
     * 因为 **B 站的 `durl` 链路给的不是干净的 FLV**：它是「FLV 容器 + 分片 durl 多段」
     * 或干脆是 `.flv?` 带一堆 query 的 CDN 直链。`ProgressiveMediaSource` 要求**单条可 seek
     * 的完整流**，遇到分片 FLV 会起不来（或起播后立刻 EOF）—— 这才是 ijk 能播、media3 不行的
     * 真实差异（ijk 的 ffmpeg demuxer 容忍分片与残缺）。
     *
     * 所以本函数的目的**不是"因为没提取器所以换内核"，而是"这条流结构不适合 Progressive 模式，
     * 提前换内核，别让用户对着黑屏等 8 秒"**。判断很保守（只看后缀），宁可漏判也不误判：
     * 漏判由 [PlayerActivity.tryFallbackToIjk] 的 onPlayerError 兜底，误判会让本来能播的视频
     * 白换一次内核（用户会看到画质/行为变化）。
     */
    fun isLikelyFlv(): Boolean {
        val url = dataSourceUrl ?: return false
        val path = Uri.parse(url).path?.lowercase(Locale.ROOT) ?: return false
        return path.endsWith(".flv")
    }

    override fun setDataSource(fd: FileDescriptor) {
        // app 全部调用点都是 String/(String,headers)，没有 fd/IMediaDataSource 入口
        throw IOException("Media3Player 不支持 setDataSource(FileDescriptor)")
    }

    override fun setDataSource(mediaDataSource: IMediaDataSource) {
        throw IOException("Media3Player 不支持 setDataSource(IMediaDataSource)")
    }

    /**
     * 设置 DASH 音频轨地址（v5.2.1，**新增 API，不改变任何已有签名**）。
     *
     * 由 `PlayerActivity.MPPrepare` 在 `isOnlineVideo && dash 音视频分离` 时调用；
     * 其它情况（本地文件、直播、单流）不调，行为与改动前完全一致。
     *
     * 必须在 [prepareAsync] 之前调用（它只是存字段，prepare 时才参与媒体源构建）。
     */
    fun setAudioDataSource(audioUrl: String?, headers: Map<String, String>?) {
        dataSourceAudioUrl = audioUrl
        // 音频轨与视频轨同源（同一个 CDN、同样的 Referer/UA），所以 headers 共用；
        // 但若调用方显式给了音频 headers 而没给主 headers，这里不覆盖主 headers（防串）
        if (headers != null && dataSourceHeaders == null) dataSourceHeaders = headers
    }

    /**
     * 开启/关闭「画面冻住」观测（v5.3.0，新增 API，不影响任何已有签名）。
     *
     * 调用方需要在**每次 prepare 之前**按当前模式设置，因为同一实例可能被复用
     * （story 切集走 `reset()`）。听视频模式必须关闭：那时视频轨被禁用，
     * 渲染帧永远不涨，开着就会立刻误判成冻结。
     */
    fun setRenderStallMonitorEnabled(enabled: Boolean) {
        renderStallMonitorEnabled = enabled
        if (!enabled) {
            firstFrameAtMs = 0L
            lastRenderedFrameAtMs = 0L
            renderedFrameCount = 0
        }
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
        runOnMain {
            if (!released) {
                player?.seekTo(msec)
                // seek 是异步生效的，直接以目标位置为锚点，避免后台线程在 seek 生效前外插旧位置
                mirrorPositionMs = msec
                mirrorPositionAtRealtimeMs = SystemClock.elapsedRealtime()
            }
        }
    }

    override fun reset() {
        // story 连续切换复用实例：stop + 清列表，保留实例（media3 复用时 Surface 保留上一帧，
        // 与 story 注释里 ijk 的复用行为一致）
        runOnMain {
            // 速率状态先清：即使实例已不在（player==null 的早退路径），也不能留上一段的旧值
            resetSpeedSample()
            // 冻结观测同理必须清：上一段的首帧时刻若留着，复用实例起新流后会误判
            // 「已经出过首帧但现在不涨帧」→ 把正常起播当成冻结
            resetRenderStallSample()
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

    /** 清空冻结观测状态（reset/release/新一段媒体开始前调用） */
    private fun resetRenderStallSample() {
        firstFrameAtMs = 0L
        lastRenderedFrameAtMs = 0L
        renderedFrameCount = 0
        readyAtMs = 0L
    }

    override fun release() {
        released = true
        // 先清速率再停 ticker：release 后 Timer 线程仍在读 getTcpSpeed()，不能留旧值
        tcpSpeedBytesPerSec = 0L
        runOnMain {
            mainHandler.removeCallbacks(ticker)
            resetSpeedSample()
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
        if (Looper.myLooper() != mainLooper) return interpolatedPositionMs()
        // 主线程直读顺手重锚，后台线程的外插基线始终新鲜
        refreshPositionMirror(p)
        return mirrorPositionMs
    }

    /** 主线程采样锚点：位置 + 采样时刻 + 倍速 */
    private fun refreshPositionMirror(p: Player) {
        mirrorPositionMs = p.currentPosition
        mirrorPositionAtRealtimeMs = SystemClock.elapsedRealtime()
        mirrorSpeed = p.playbackParameters.speed
    }

    /** 后台线程用的平滑位置：锚点 + 经过墙钟 × 倍速外插 */
    private fun interpolatedPositionMs(): Long {
        val pos = mirrorPositionMs
        if (!mirrorPlaying) return pos
        val elapsed = SystemClock.elapsedRealtime() - mirrorPositionAtRealtimeMs
        if (elapsed <= 0L) return pos
        val interpolated = pos + (elapsed * mirrorSpeed).toLong()
        val duration = mirrorDurationMs
        return if (duration > 0L && interpolated > duration) duration else interpolated
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

        // 每次开始一条新流都重新起算冻结观测：首帧时刻必须由本条流自己的
        // onRenderedFirstFrame 重新置位，否则复用实例（story 切集）会把上一段的
        // 首帧时刻当成自己的，导致新流还没出图就"已经出过首帧"。
        resetRenderStallSample()

        // v5.3.0：**每次 prepare 都要清 prepared 标记**。
        //
        // 为什么（这是「画面停在上一帧不动」的一条真实成因）：
        // [obtainPlayer] 在拉流档位没变时会**复用同一个 ExoPlayer 实例**，
        // 然后这里 `setMediaSource` + `prepare()` 重新加载。media3 会正常地
        // 重新解封装、重新初始化解码器、再次进入 `STATE_READY` —— 但
        // [playerListener] 里的 `if (!preparedNotified)` 因为标记还是上一轮留下的 true
        // 而**不再回调 onPrepared**。于是上层（PlayerActivity）的 `isPrepared` 永远是 true，
        // 不会重新走「起播后设尺寸、恢复进度、启动弹幕、start()」这套流程：
        // 画面就停在复用实例保留的上一帧（surface 不清屏），声音/位置却按新流在走。
        // 表现正是用户说的「能加载、有网速、有弹幕、有声音，但画面停在开头不动」。
        preparedNotified = false
        completionNotified = false
        lastPlaybackState = Player.STATE_IDLE

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
        // 真实下载字节来源：TransferListener 在每次网络 read 时回调（见字段注释）。
        // 走官方 DataSource.Factory 的 listener 通道，不包一层 DataSource，续传/重试的字节
        // 也会原样计入（它们是真实流量）。
        httpDataSourceFactory.setTransferListener(transferListener)

        // ---- 起播行为：start-on-prepared（ijk 缺省 1=自动播；story 显式传 0 再手动 start）----
        p.playWhenReady = optionLong("start-on-prepared", 1L) == 1L

        // ---- 听视频模式：vn=1 直接禁视频轨（不解码不渲染）----
        val disableVideo = optionLong("vn", 0L) == 1L
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
            .build()

        p.setMediaSource(buildMediaSource(url, dataSourceAudioUrl))
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
            resetSpeedSample()
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

    /**
     * 构建媒体源。
     *
     * @param url 视频（或单流）地址
     * @param audioUrl DASH 音频轨地址；非空时用 [MergingMediaSource] 与视频合并成一条完整媒体源
     *   （**这是修「有画面没声音」的关键**：B 站 DASH 的音视频是两条独立 URL，
     *   只给 video 一条，media3 就没有音轨可渲染）。null / 空串时行为与改动前一致。
     */
    private fun buildMediaSource(url: String, audioUrl: String?): MediaSource {
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
        val videoSource = if (path.endsWith(".m3u8")) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory, DefaultExtractorsFactory())
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.fromUri(uri))
        }
        if (audioUrl.isNullOrEmpty() || audioUrl == url) return videoSource
        // DASH 音视频分离：音频轨同样是 fMP4 分片，走 ProgressiveMediaSource 即可
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory, DefaultExtractorsFactory())
            .setLoadErrorHandlingPolicy(policy)
            .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
        // MergingMediaSource 要求两条源时长一致（B 站同一次 playurl 返回的必然一致）；
        // 万一不一致，media3 会在 prepare 阶段报错，被 [onPlayerError] 接住并由
        // PlayerActivity 的「切 ijk」兜底，不会静默。
        return MergingMediaSource(videoSource, audioSource)
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
                        // 首帧超时的起算点：READY 意味着解封装+解码器都已就绪，
                        // 正常情况下首帧会在几百毫秒内到达；从这里开始计时最有意义
                        // （从 prepare 开始计会把网络等待也算进去，误判率太高）。
                        readyAtMs = SystemClock.elapsedRealtime()
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
            // 暂停瞬间重锚，后台线程从此静止在当前位置（不再外插）
            if (!isPlaying) {
                val p = player
                if (p != null && Looper.myLooper() == mainLooper) refreshPositionMirror(p)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                runOnMain {
                    player?.let { refreshPositionMirror(it) }
                    seekCompleteListener?.onSeekComplete(this@Media3Player)
                }
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
            // 首帧时刻是冻结判据的「起算点」：没出过图之前一律不判冻结，
            // 否则起播缓冲（本来就没帧）会被当成冻住并误切内核。
            val now = SystemClock.elapsedRealtime()
            firstFrameAtMs = now
            lastRenderedFrameAtMs = now
            renderedFrameCount = currentRenderedFrameCount()
            // MEDIA_INFO_VIDEO_RENDERING_START(3)：story 的 6s 首帧看门狗依赖
            notifyInfo(IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0)
        }
    }

    /**
     * 读「已渲染输出帧数」——冻结判据的唯一可靠信号。
     *
     * 为什么要防御：`getVideoDecoderCounters()` 只能在应用主线程调用（ExoPlayerImpl 里
     * 先 `verifyApplicationThread()`），且实例可能在 release 后为 null。 ticker 恒在主线程，
     * 但仍做 try/catch —— 一旦这里抛异常，整个 ticker 会死掉，连带网速/缓冲/镜像全都停更，
     * 那比画面冻结更严重（用户会看到进度条彻底不动）。
     */
    private fun currentRenderedFrameCount(): Int {
        val p = player ?: return 0
        return try {
            p.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        } catch (ignored: IllegalStateException) {
            // 非主线程 / 已释放：拿不到就当作「没有新帧」，由 [isRenderStalled] 的
            // 「已出过首帧」前置条件兜住，不会在起播阶段炸出误判
            0
        }
    }

    /**
     * 冻结观测的一拍（只由主线程 ticker 调用）。
     *
     * 只在「确实该出帧」的状态下推进锚点，否则每次缓冲/暂停都会把
     * [lastRenderedFrameAtMs] 刷新成当下，反而让真正的冻结被掩盖。
     */
    private fun updateRenderStallSample(p: Player) {
        if (!renderStallMonitorEnabled) return
        if (p.playbackState != Player.STATE_READY || !p.isPlaying) return
        val count = currentRenderedFrameCount()
        if (count != renderedFrameCount) {
            renderedFrameCount = count
            lastRenderedFrameAtMs = SystemClock.elapsedRealtime()
        }
    }

    /** 500ms 轻量轮询：驱动 onBufferingUpdate + 刷新后台线程 getter 用的镜像状态 + 更新网速 */
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val p = player
            if (p != null) {
                refreshPositionMirror(p)
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
                updateSpeedSample(p)
                updateRenderStallSample(p)
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * 清空速率采样状态（release/reset/重建播放器时调用）。
     *
     * 累计字节清零、基线丢弃、对外值归零：复用实例（story 切集）时新一段从零起算，
     * 不会把上一段残留的字节算进第一拍。
     */
    private fun resetSpeedSample() {
        transferredBytes.set(0L)
        speedSampleBytes = 0L
        speedSampleAtMs = 0L
        tcpSpeedBytesPerSec = 0L
    }

    /**
     * 一个采样窗口：累计下载字节做差分 → 上窗口的实际吞吐（B/s）。只由主线程 ticker 调用。
     *
     * 判据：只有"确实在该拉流"的窗口才计速——用户暂停后（playWhenReady=false 且不在缓冲态）
     * 即使 media3 还在把缓冲区填满，也归零，避免暂停期间还挂着一个速度；
     * 停止计速时同时**丢弃基线**，否则恢复拉流后第一拍会把"停读的那段时间"算进分母，
     * 得出一个明显偏小的假速度。
     */
    private fun updateSpeedSample(p: Player) {
        val now = SystemClock.elapsedRealtime()
        val total = transferredBytes.get()
        val buffering = p.playbackState == Player.STATE_BUFFERING
        val active = p.isLoading && (p.playWhenReady || buffering)
        if (!active) {
            tcpSpeedBytesPerSec = 0L
            speedSampleAtMs = 0L
            speedSampleBytes = total
            return
        }
        val lastAt = speedSampleAtMs
        if (lastAt == 0L) {
            // 本次拉流的第一拍只建基线（分母不能为 0）
            speedSampleAtMs = now
            speedSampleBytes = total
            return
        }
        val elapsed = now - lastAt
        if (elapsed < MIN_SPEED_WINDOW_MS) return
        val delta = total - speedSampleBytes
        speedSampleAtMs = now
        speedSampleBytes = total
        // 一个窗口内没有新字节（解析/等首包/弱网停顿）：归零，别显示上一拍的旧值
        tcpSpeedBytesPerSec = if (delta <= 0L) 0L else delta * 1000L / elapsed
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

        /**
         * 网速差分的最小窗口：ticker 名义 500ms，但主线程卡顿会让相邻两拍只差几十毫秒，
         * 那样算出来的 B/s 会把抖动放大好几倍。短于这个值就跳过本拍（保留旧值）。
         */
        const val MIN_SPEED_WINDOW_MS = 200L

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

        /**
         * 渲染帧停止推进多久判定为「画面冻住」（v5.3.0）。
         *
         * 取值理由：正常 24~60fps 视频在 500ms 内至少出 12 帧，连续 6 秒**一帧都没有**
         * 已经远超任何正常抖动（解码器换档、surface 重建都在百毫秒级）。
         * 同时这个时长要小于用户「以为播放器坏了」的忍耐阈值（10 秒级），
         * 又不能太短 —— 太短会把「音频在播、画面正在等关键帧」的正常起播尾段误判掉。
         * 6 秒与 story 的首帧看门狗一致，便于用户与日志对照。
         */
        const val RENDER_STALL_TIMEOUT_MS = 6_000L

        /**
         * `STATE_READY` 之后多久仍然没有任何首帧，判定为「黑屏起不来」（v5.3.0）。
         *
         * 为什么比 [RENDER_STALL_TIMEOUT_MS] 长：READY 之后还要等第一个关键帧
         * （B 站 DASH 分片的 IDR 间隔可达 2~4 秒），而 8 秒是 HANDOFF §3.4 里
         * 已经定下的口径（"prepare 后 8 秒无首帧 → 自动切 ijk"），保持一致便于对照。
         */
        const val FIRST_FRAME_TIMEOUT_MS = 8_000L
    }
}
