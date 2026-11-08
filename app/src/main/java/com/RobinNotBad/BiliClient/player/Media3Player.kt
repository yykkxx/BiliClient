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

    /** 动作队列与 prepare/reset 使用同一代际，旧 seek/start/回调不能作用到下一条流。 */
    private val commandGeneration = AtomicLong(0L)
    private var desiredSpeed = 1f
    private var desiredVolume = 1f
    private var desiredLooping = false

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

    /**
     * 上一次「确实测到吞吐」的时刻（v5.3.2，对应症状 B 的网速横跳）。
     *
     * 只由主线程 ticker 写。用途见 [updateSpeedSample]：短时无字节时保持显示上一个有效值，
     * 而不是立刻归零（即"界面从有数字变成空白"的那一下）。0 表示还没有过有效值。
     */
    private var lastNonZeroSpeedAtMs = 0L

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

    /**
     * 上一次的播放状态（v5.3.2 起加 `@Volatile`）。
     *
     * 加 volatile 的原因：冻结/首帧两条判据要拿它当「当前不在 READY 就一律不判」的
     * **实时硬前置**，而这两条判据的调用方（看门狗 tick、播放页 probe）虽然也在主线程，
     * 但判据本身被文档承诺为「任意线程安全」，所以按线程安全口径声明，避免以后有人
     * 从 Timer/弹幕线程调用时读到撕裂值或陈旧值。
     */
    @Volatile
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

    // ---- 画面类判据的辅助信号（v5.3.1：把「单一信号」升级成「多信号同时成立」）------
    //
    // v5.3.0 的两条判据各自只看一个信号（渲染帧计数不涨 / READY 后没有首帧），
    // 而这两个信号在**完全正常**的情况下也会短暂不推进：
    //   - 硬解换档、DASH 分片边界、自适应切轨、surface 重建（旋转/切前台）时，
    //     解码器会有百毫秒到数秒的「不出帧」窗口；
    //   - 只用一个信号 + 6 秒窗口，这些正常窗口一旦叠在一起就变成「冻死」→ 误切内核。
    // 用户看到的正是「播了几秒之后自动回退 ijk」。
    //
    // 现在追加两个**独立**的旁证，任何一条不成立就不判：
    //   - [positionChangedAtMs]：播放位置是否还在推进。真冻结是「声音在放、位置在涨、
    //     只有画面不动」；位置也不涨说明是网络/解码卡死，该走看门狗的重建数据源路径；
    //   - [lastSampleAllowed]：最近一拍是否真的「READY 且在播」。暂停/缓冲时本来就没有帧。
    // 另外加两道时间闸：[seekAnchorAtMs]（seek 后宽限）与 [videoSurfaceAttached]
    // （画面被摘掉时根本不可能出帧，判它必错）。

    /** 最近一次「确实该出帧」的采样是否成立（READY 且 isPlaying），由 ticker 维护 */
    @Volatile
    private var lastSampleAllowed = false

    /** 播放位置最近一次发生变化的时刻：证明「还在播」的独立信号 */
    @Volatile
    private var positionChangedAtMs = 0L

    /** seek 宽限的起算点：seek 后解码器要重建、画面要等关键帧，这段时间不判 */
    @Volatile
    private var seekAnchorAtMs = 0L

    /** 画面（Surface/Holder）当前是否挂在播放器上。摘掉画面时不出帧是必然的 */
    @Volatile
    private var videoSurfaceAttached = false

    /**
     * 播放页当前是否可见（v5.3.1）：不可见时一律不判冻结/首帧超时。
     *
     * 与 [renderStallMonitorEnabled] 分工不同 —— 后者决定「这个视频该不该观测」
     * （听视频/直播不该），本条决定「此刻能不能观测」。默认 true，
     * 不调用 `setPageVisibleForRenderObservation` 的调用方行为与 v5.3.0 一致。
     */
    @Volatile
    private var pageVisibleForRender = true

    /** 上一拍采样到的播放位置（只在主线程 ticker 里读写） */
    private var lastSampledPositionMs = -1L

    /** 上一拍是否处于「该出帧」状态（暂停→恢复的跳变要靠它识别，见 updateRenderStallSample） */
    private var wasSampling = false

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
     * ## v5.3.1：从「单信号」升级成「多信号同时成立」
     *
     * v5.3.0 原来只要求「已出过首帧 + 渲染帧计数 6 秒没涨」。用户反馈「media3 播了几秒
     * 就自动回退 ijk」就是这个判据的误报 —— 渲染帧计数在**完全正常**的播放里也会停住
     * 好几秒（硬解换档、DASH 分片边界、自适应切轨、surface 重建、缓冲），单靠它区分不了
     * 「正常不出帧」和「真的冻死」。用户明确要求「宁可漏判，不可误判」，所以现在要求
     * **下列条件全部成立**才判冻结（任何一条不成立就返回 false）：
     *
     * 1. 观测开关开、未 release；
     * 2. 已出过首帧（[firstFrameAtMs] > 0）——起播前没有帧是正常的；
     * 3. **[lastSampleAllowed]**：最近一拍确实处于「READY + 在播 + 画面挂着」。
     *    这条堵住 v5.3.0 最大的漏洞 —— 原来判据只看历史时间戳，不看**当前**状态，
     *    于是「暂停很久 → 恢复播放」的第一拍会因为锚点还停在暂停前而立刻判成冻结；
     * 4. **[videoSurfaceAttached]**：画面（Surface/Holder）真的挂在播放器上。
     *    摘掉画面时（切后台 surfaceDestroyed、旋转重建、听视频切换）根本不可能出帧；
     * 5. **不在 seek 宽限期内**（[seekAnchorAtMs]）：seek 后解码器要重建、要等关键帧，
     *    B 站 DASH 的 IDR 间隔可达 2~4 秒，这段时间不出帧是必然的；
     * 6. **播放位置仍在推进**（[positionChangedAtMs]）：这是判「真冻结」的关键旁证。
     *    真冻结的特征是「**声音在放、位置在涨、只有画面不动**」；位置也不涨说明是
     *    网络/解码卡死，那该走看门狗「重建数据源」的路径，**换内核没用**；
     * 7. 渲染帧计数在 [RENDER_STALL_TIMEOUT_MS]（12 秒）内一次都没涨过。
     *
     * 供 `PlayerActivity` 的冻结兜底轮询调用（任意线程安全：只读 @Volatile 字段，
     * 唯一的内存读写都发生在主线程 ticker 里）。
     */
    fun isRenderStalled(): Boolean {
        if (!renderStallMonitorEnabled || released || !pageVisibleForRender) return false
        val first = firstFrameAtMs
        val last = lastRenderedFrameAtMs
        // 2)~4)：没出过首帧 / 当前不该出帧 / 画面没挂着 —— 三者都不是「冻住」
        if (first <= 0L || last <= 0L) return false
        // ⚠ v5.3.2（对应真机症状 A「放着放着就卡，然后切到老播放器」）：
        // **缓冲态绝不能参与冻结判定**。BUFFERING 期间媒体时间轴不前进 → 帧计数必然不涨、
        // 位置也未必涨；而 `lastSampleAllowed` 是 ticker **每 500ms** 才刷新的，
        // 判据在两个 tick 之间被问到的那一瞬间可能还读着「上一拍 READY+在播」的旧值 ——
        // 于是「刚进缓冲」的那一拍会被算成「画面冻住」。这里直接读实时状态，堵死这个窗口。
        if (lastPlaybackState != Player.STATE_READY) return false
        if (!lastSampleAllowed || !videoSurfaceAttached) return false
        val now = SystemClock.elapsedRealtime()
        // 5) seek 宽限：跳转后解码器重建/等关键帧，这段时间不判
        val seekAt = seekAnchorAtMs
        val inSeekGrace = seekAt > 0L && now - seekAt < SEEK_GRACE_MS
        // 6) 位置必须还在推进，否则不是「画面冻结」而是卡死（另有兜底路径）
        val posChangedAt = positionChangedAtMs
        val positionAlive = posChangedAt > 0L && now - posChangedAt <= POSITION_ALIVE_MS
        // 7) 真正的判据：12 秒没有新帧
        return !inSeekGrace && positionAlive && now - last >= RENDER_STALL_TIMEOUT_MS
    }

    /** 是否已经出过首帧（供上层区分「黑屏起不来」与「出过图后冻住」） */
    fun hasRenderedFirstFrame(): Boolean = firstFrameAtMs > 0L

    /**
     * prepare 完成之后多久仍然一帧都没渲染出来（v5.3.0；v5.3.1 大幅收紧）。
     *
     * 与 [isRenderStalled] 是**互补**的两条判据，覆盖两个不同的用户症状：
     * - 这条管「黑屏起不来」：`STATE_READY` 了（音频都出来了）却完全没有首帧 ——
     *   典型是硬解器初始化成功但 surface 上不出图，**不报错**；
     * - [isRenderStalled] 管「出过图之后冻住」：用户本轮反馈的那个。
     *
     * ## v5.3.1 为什么必须收紧
     * 原来只看「READY 后 8 秒没有首帧」。但**纯网络慢**（弱网、CDN 边缘差、分片大）
     * 完全可能是「READY 了但 8 秒还没解出第一个关键帧」—— 那时位置根本不推进，
     * 画面黑着但**内核没问题**，换 ijk 一样黑，只是白扔一次切内核（还会重复弹提示）。
     * 现在追加三条硬前置，全部成立才判：
     * - **播放位置确实在推进**：证明音频真的在放 → 才是「有声音没画面」那个真故障；
     *   位置不动 = 还没开始播，属于网络/加载问题，交给看门狗的重建数据源路径；
     * - 画面真的挂上了（[videoSurfaceAttached]）；
     * - 不在 seek 宽限期内；
     * - 超时从 8 秒拉长到 [FIRST_FRAME_TIMEOUT_MS]（20 秒）。
     *
     * @return true 表示超时无首帧，上层应切内核
     */
    fun isFirstFrameOverdue(): Boolean {
        if (!renderStallMonitorEnabled || released || !pageVisibleForRender) return false
        if (firstFrameAtMs > 0L) return false
        // 同上（v5.3.2）：缓冲/回缓冲期间"还没出首帧"是**必然**的，不是黑屏故障。
        // 弱网下 READY → 缓冲 → READY 反复切换是常态，如果缓冲期也计入 20 秒超时，
        // 那「一直在缓冲、始终没凑够起播条件」的正常弱网就会被判成黑屏并切内核。
        if (lastPlaybackState != Player.STATE_READY) return false
        val readyAt = readyAtMs
        if (readyAt <= 0L || !videoSurfaceAttached) return false
        val now = SystemClock.elapsedRealtime()
        val seekAt = seekAnchorAtMs
        if (seekAt > 0L && now - seekAt < SEEK_GRACE_MS) return false
        // 位置不推进 = 还没真正播起来（纯加载慢），换内核解决不了任何问题
        val posChangedAt = positionChangedAtMs
        if (posChangedAt <= 0L || now - posChangedAt > POSITION_ALIVE_MS) return false
        return now - readyAt >= FIRST_FRAME_TIMEOUT_MS
    }

    /** 冻住之后要重置观测锚点（兜底动作执行前调用）。 */
    fun resetRenderStallAnchor() {
        currentRenderedFrameCount()?.let { renderedFrameCount = it }
        lastRenderedFrameAtMs = SystemClock.elapsedRealtime()
        positionChangedAtMs = SystemClock.elapsedRealtime()
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
            readyAtMs = 0L
            // v5.3.1：辅助信号同样要清，否则重新开启时会带着上一次会话的旧锚点
            lastSampleAllowed = false
            positionChangedAtMs = 0L
            seekAnchorAtMs = 0L
            lastSampledPositionMs = -1L
            wasSampling = false
        } else {
            // 开启时把「画面是否挂着」刷新成当下的事实（ticker 每拍也会现算，
            // 这里只是让开关生效后的第一拍之前就有一个可用的值）
            videoSurfaceAttached = isVideoSurfaceAlive()
        }
    }

    override fun getDataSource(): String? = dataSourceUrl

    override fun prepareAsync() {
        if (released) return
        // 参数在这里同步快照 + 分配代际：主线程执行时若已被 reset/新 prepare 超过，那条旧流必须作废。
        val generation = commandGeneration.incrementAndGet()
        val url = dataSourceUrl ?: return
        val audio = dataSourceAudioUrl
        val headers = dataSourceHeaders?.toMap()
        runOnMain { prepareOnMain(url, audio, headers, generation) }
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
                // 冻结观测：seek 后解码器要重建、画面要等关键帧，这段时间不出帧是必然的。
                // 只清看门狗那一侧的计时是不够的 —— 冻结判据的锚点在本类里，
                // 不清它就会「seek 刚完就判冻结」（v5.3.1）
                onRenderObservationSeek()
            }
        }
    }

    /**
     * 通知冻结观测「发生了一次 seek」（v5.3.1）。
     *
     * 由本类的 [seekTo] 与上层（播放页的 seekToPosition / seekPlayerAndDanmaku）共同调用。
     * 上层那两条路径**不经过** [seekTo]（它们直接拿 ijkPlayer.seekTo），所以要单独通知。
     */
    fun onRenderObservationSeek() {
        seekAnchorAtMs = SystemClock.elapsedRealtime()
        // 位置锚点也一起重起算：seek 目标位置本身不算「位置在推进」的证据
        positionChangedAtMs = SystemClock.elapsedRealtime()
        lastSampledPositionMs = -1L
        // seek 之后必定重新等关键帧，把帧锚点一起重锚，避免「seek 完立刻判冻结」
        lastRenderedFrameAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * 页面可见性变化（v5.3.1）。
     *
     * 不可见时（切后台/画中画折叠/被遮挡）Surface 可能已销毁，**不出帧是必然的**，
     * 而音频与播放位置照常推进 —— 不排除必然误判成「画面冻结」。
     * 这里只让判据失效，不改开关（[renderStallMonitorEnabled] 决定的是「这个视频
     * 该不该观测」，与「此刻能不能观测」是两件事）。
     */
    fun setPageVisibleForRenderObservation(visible: Boolean) {
        if (pageVisibleForRender == visible) return
        pageVisibleForRender = visible
        val now = SystemClock.elapsedRealtime()
        // 两个方向都要重锚（GAP-B）：
        // - 变成不可见：不可见期间不积累判定，清掉计时；
        // - **变回可见**：回到前台的那一刻，锚点可能还停在切后台之前（几分钟前），
        //   如果只在上一个分支清，回前台首拍就会算出「已冻结好几分钟」→ **首拍即误判**。
        //   同时把 wasSampling 复位，让下一拍走「恢复采样」分支重新建立基线。
        lastRenderedFrameAtMs = now
        positionChangedAtMs = now
        wasSampling = false
        lastSampledPositionMs = -1L
    }

    override fun reset() {
        if (released) return
        commandGeneration.incrementAndGet()
        // 先同步清参数，不能让排队的 reset 把随后 setDataSource 的新参数擦掉。
        dataSourceUrl = null
        dataSourceAudioUrl = null
        dataSourceHeaders = null
        clearPlaybackMirror()
        runOnMain {
            resetSpeedSample()
            resetRenderStallSample()
            discardPlayer()
        }
    }

    private fun clearPlaybackMirror() {
        mirrorPlaying = false
        mirrorPositionMs = 0L
        mirrorDurationMs = 0L
        mirrorPositionAtRealtimeMs = SystemClock.elapsedRealtime()
        videoWidthPx = 0
        videoHeightPx = 0
    }

    private fun discardPlayer() {
        val old = player
        // release 会产生事件，必须先撤销身份再释放。
        player = null
        mainHandler.removeCallbacks(ticker)
        old?.release()
        preparedNotified = false
        completionNotified = false
        lastPlaybackState = Player.STATE_IDLE
    }

    /** 清空冻结观测状态（reset/release/新一段媒体开始前调用） */
    private fun resetRenderStallSample() {
        firstFrameAtMs = 0L
        lastRenderedFrameAtMs = 0L
        renderedFrameCount = 0
        readyAtMs = 0L
        // v5.3.1 新增的多信号状态必须一起清：留着上一段的「位置在涨」会让新流的
        // 首帧超时判据一上来就成立（旧值 + 现在的时间差），把正常起播判成黑屏
        lastSampleAllowed = false
        positionChangedAtMs = 0L
        seekAnchorAtMs = 0L
        lastSampledPositionMs = -1L
        wasSampling = false
    }

    override fun release() {
        if (released) return
        released = true
        commandGeneration.incrementAndGet()
        clearPlaybackMirror()
        tcpSpeedBytesPerSec = 0L
        videoSurfaceAttached = false
        pendingHolder = null
        pendingSurface = null
        runOnMain {
            resetSpeedSample()
            resetRenderStallSample()
            discardPlayer()
        }
    }

    override fun setSpeed(speed: Float) {
        // Sonic 变速：0.5~3.0 保音调（ijk 的 soundtouch/2x 上限问题在 media3 侧不存在）
        desiredSpeed = speed
        runOnMain { if (!released) player?.playbackParameters = PlaybackParameters(speed) }
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        // app 实际从不调用（只用系统音量），保接口即可
        desiredVolume = leftVolume
        runOnMain { if (!released) player?.volume = leftVolume }
    }

    override fun setSurface(surface: Surface?) {
        pendingSurface = surface
        pendingHolder = null
        // 画面摘掉时不出帧是必然的，冻结判据必须立刻失效（v5.3.1）。
        // ticker 每拍也会现算（见 isVideoSurfaceAlive），这里只是让"下一拍之前"也对。
        videoSurfaceAttached = isVideoSurfaceAlive()
        runOnMain { if (!released) player?.setVideoSurface(surface) }
    }

    override fun setDisplay(sh: SurfaceHolder?) {
        pendingHolder = sh
        pendingSurface = null
        // 同理：surfaceDestroyed 会走到 setDisplay(null)，那之后判冻结 100% 是误报
        videoSurfaceAttached = isVideoSurfaceAlive()
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
        desiredLooping = looping
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

    /**
     * 主线程执行真正的 prepare。
     *
     * 参数在 [prepareAsync] 里**同步快照**后传进来（而不是在这里再读字段）：
     * 队列里排着的 prepare 与随后到来的 setDataSource/reset 有竞争，
     * 读字段会拿到后来那一轮的地址；只有快照才能保证「prepareAsync 那一刻的参数」
     * 就是这次真正加载的参数。
     *
     * [generation] 是本条流的代际：主线程执行前若已过期（被 reset/release/新 prepare 超过），
     * 直接丢弃，避免旧流覆盖新流。
     */
    private fun prepareOnMain(url: String, audio: String?, headers: Map<String, String>?, generation: Long) {
        if (released || generation != commandGeneration.get()) return
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
        httpDataSourceFactory.setDefaultRequestProperties(headers ?: emptyMap())
        // 真实下载字节来源：TransferListener 在每次网络 read 时回调（见字段注释）。
        // 走官方 DataSource.Factory 的 listener 通道，不包一层 DataSource，续传/重试的字节
        // 也会原样计入（它们是真实流量）。
        httpDataSourceFactory.setTransferListener(transferListener)

        // ---- 起播行为：start-on-prepared（ijk 缺省 1=自动播；story 显式传 0 再手动 start）----
        p.playWhenReady = optionLong("start-on-prepared", 1L) == 1L
        // 重建实例（档位变化）时把调用方设置过的倍速/音量/循环补回去，
        // 否则重建一次就静默回到 1.0x —— 用户只会觉得"切个清晰度倍速就没了"。
        p.playbackParameters = PlaybackParameters(desiredSpeed)
        p.volume = desiredVolume
        p.repeatMode = if (desiredLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

        // ---- 听视频模式：vn=1 直接禁视频轨（不解码不渲染）----
        val disableVideo = optionLong("vn", 0L) == 1L
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disableVideo)
            .build()

        p.setMediaSource(buildMediaSource(url, audio))
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
        videoSurfaceAttached = isVideoSurfaceAlive()
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

    /**
     * 这次 STATE_ENDED 是否真的「播到末尾」。
     *
     * 判据：
     * 1. **未见首帧时，只有「本来就不该出帧」的场景才放行**（v5.3.2 修正）：
     *    听视频模式（`vn=1` 禁视频轨）与纯音频文件**永远不会有视频帧**，
     *    `firstFrameAtMs` 恒为 0。原判据无条件要求 `firstFrameAtMs > 0`，
     *    于是这两类流的 ENDED **永远不会上报完成** —— 表现为「放完了不停、
     *    不切下一P、循环失效」。修法是：观测开关关闭（`renderStallMonitorEnabled == false`，
     *    上层正是用它标记"本次不该观测画面"）时，跳过首帧这一条，
     *    仍然要求「时长已知 + 位置推进到末尾」，这才防得住原来要防的
     *    「起点接在末尾/来源被截断就立刻 ENDED」；
     * 2. 时长已知且大于 0；
     * 3. 播放位置已推进到末尾附近（留 1% 或 2 秒的容差 —— DASH 音频轨通常比视频轨短
     *    几百毫秒，硬等精确相等会让**真播完**的流永远不上报完成）。
     *
     * 位置门槛是防误报的真正主力：无论有没有出过帧，「position 还没推到末尾」
     * 都说明不是播完，所以首帧这一条只作为**附加**约束（新鲜路径下更严格）。
     */
    private fun reachedEndOfStream(): Boolean {
        // 未见首帧时仅在「本次不该观测画面」时才放行（听视频/纯音频/直播）
        if (firstFrameAtMs <= 0L && renderStallMonitorEnabled) return false
        val duration = try {
            player?.duration ?: 0L
        } catch (ignored: Exception) {
            0L
        }
        if (duration <= 0L) return false
        val position = try {
            player?.currentPosition ?: 0L
        } catch (ignored: Exception) {
            0L
        }
        if (position <= 0L) return false
        val tolerance = maxOf(END_TOLERANCE_MIN_MS, duration / 100L)
        return position >= duration - tolerance
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released) return
            // v5.3.2（症状 A 的落地点）：**进入/离开缓冲态都要把冻结观测的锚点重起算**。
            //
            // 为什么不能只靠 ticker 的 `wasSampling` 分支：ticker 每 500ms 才跑一拍，
            // 而「缓冲结束 → 恢复播放」的判定可能在两次 tick 之间被读走；
            // 更关键的是**进入缓冲**这一侧：位置在缓冲期间自然不动，
            // `positionChangedAtMs` 会一路老化到 3 秒以上，如果缓冲持续 12 秒以上，
            // 缓冲刚结束的那一拍就同时满足「12 秒没新帧」+「位置刚恢复推进」→ 立刻判冻结。
            // 真机上就是「卡了一下（短暂缓冲）→ 被判冻结 → 切到老播放器」。
            if (playbackState == Player.STATE_BUFFERING || lastPlaybackState == Player.STATE_BUFFERING) {
                val now = SystemClock.elapsedRealtime()
                lastRenderedFrameAtMs = now
                positionChangedAtMs = now
                seekAnchorAtMs = now
                lastSampledPositionMs = -1L
                wasSampling = false
            }
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
                    // STATE_ENDED 不一定代表「真的播完了」：起点接在末尾、来源被截断、
                    // 或者复用实例时 position 还留在上一段末尾，都会在**没播过内容**的情况下
                    // 立刻进入 ENDED。上层收到 onCompletion 会当成「播完」，
                    // 于是「打开视频就自动切到另一个视频/下一P」—— 必须加一道时长门槛：
                    // 只有本次确实渲染过帧、且播放位置真的推进到接近末尾时才上报完成。
                    if (!completionNotified && reachedEndOfStream()) {
                        completionNotified = true
                        runOnMain { completionListener?.onCompletion(this@Media3Player) }
                    } else if (!completionNotified) {
                        Logu.w("media3", "忽略过早的 ENDED（未真正播到末尾），不触发完成回调")
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
            currentRenderedFrameCount()?.let { renderedFrameCount = it }
            // 首帧到达本身就证明「位置在推进」这条旁证是活跃的（v5.3.1）：
            // 有些设备首帧回调早于第一次位置采样，不置位的话首帧超时会在
            // 出图之后仍被判成立（positionChangedAtMs 还是 0）
            if (positionChangedAtMs <= 0L) positionChangedAtMs = now
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
    private fun currentRenderedFrameCount(): Int? {
        val p = player ?: return null
        return try {
            // ⚠ v5.3.1 修正（GAP-A，高危）：这里**不能**再用 `?: 0`。
            //
            // 反编译 media3 1.4.1 确认：`ExoPlayerImpl$ComponentListener.onVideoDisabled`
            // 会把 `videoDecoderCounters` 置为 **null**（`aconst_null; putfield`），
            // 触发链是 `MediaCodecVideoRenderer.onDisabled` → `BaseRenderer.disable()`
            // → `eventDispatcher.disabled(counters)`。
            //
            // 旧写法把「读不到计数器」伪装成「计数是 0」，后果非常隐蔽：
            //   - 出过首帧后 `renderedFrameCount` 被记成 N（比如 500）；
            //   - 渲染器 disable 后每拍都读到 0；
            //   - 于是 `count != renderedFrameCount` 恒成立（0 != 500），锚点**每次都在刷新**
            //     ……看起来反而"安全"？
            //   - 但真正糟糕的是另一种次序：disable 的那一刻读到 0，把 `renderedFrameCount`
            //     写成 0；之后一直读 0 → `0 != 0` 不成立 → **锚点永久不再刷新** →
            //     12 秒后必判冻结，而画面可能完全正常。
            // 两种次序都不可接受。正确做法是把「读不到」表达成 null（未知），
            // 由调用方**续命**（把锚点推到当下）而不是「当成没帧」。
            p.videoDecoderCounters?.renderedOutputBufferCount
        } catch (ignored: IllegalStateException) {
            // 非主线程 / 已释放：同样是「未知」，不是「没帧」
            null
        }
    }

    /**
     * 画面此刻是否真的挂在播放器上（v5.3.1）。
     *
     * 两种画面模式都要覆盖：TextureView 走 [pendingSurface]，SurfaceView 走 [pendingHolder]。
     * 不做 try/catch 之外的花样：`Surface.isValid` / `SurfaceHolder.getSurface` 都是
     * 轻量同步调用，ticker 每 500ms 跑一次，开销可忽略。
     */
    private fun isVideoSurfaceAlive(): Boolean {
        val surface = pendingSurface
        if (surface != null) {
            return try {
                surface.isValid
            } catch (ignored: Exception) {
                false
            }
        }
        val holder = pendingHolder ?: return false
        return try {
            holder.surface?.isValid == true
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * 冻结观测的一拍（只由主线程 ticker 调用）。
     *
     * 只在「确实该出帧」的状态下推进锚点，否则每次缓冲/暂停都会把
     * [lastRenderedFrameAtMs] 刷新成当下，反而让真正的冻结被掩盖。
     *
     * v5.3.1 追加的维护项（都是 [isRenderStalled] 的多信号判据要读的）：
     * - [lastSampleAllowed]：这一拍到底算不算「该出帧」。**每次都要更新**（包括 false），
     *   因为判据要在暂停/缓冲时立刻失效，而不是等下一条什么边沿事件；
     * - [positionChangedAtMs]：位置推进的旁证；
     * - 暂停→恢复的跳变要**重新起算**锚点（见下面 wasSampling 的注释）。
     */
    private fun updateRenderStallSample(p: Player) {
        if (!renderStallMonitorEnabled) return
        val now = SystemClock.elapsedRealtime()

        // 画面是否真的挂着：**每拍现算**，不依赖 setSurface/setDisplay 的边沿事件。
        // 为什么现算而不是只用边沿赋值：SurfaceView 的 surface 在 surfaceCreated 之前
        // 就是 invalid 的，靠边沿记录很容易停在「永远 false」（那会让冻结判据彻底失效，
        // 真冻死时反而不能兜底）。这里读的是同一个 holder 的实时状态，语义更直接。
        videoSurfaceAttached = isVideoSurfaceAlive()

        // 「这一拍是否确实该出帧」——判据每次读的都是这个最新值，所以先无条件更新它。
        // 不允许出帧的三种情况：不在 READY（IDLE/BUFFERING/ENDED）、没在播、画面没挂。
        val allowed = p.playbackState == Player.STATE_READY && p.isPlaying && videoSurfaceAttached
        lastSampleAllowed = allowed

        // 位置推进是「还在播」的独立证据。即使不允许出帧也要继续记：
        // 首帧超时判据正是在「音频已经出了、画面还没出」时用位置推进来区分
        // 「真黑屏」和「纯加载慢」。
        //
        // 注意 `>= 0` 这个前置：刚 reset/seek 后基线是 -1，此时任何位置值都
        // 「不等于 -1」，若不排除就会把「起播时停在 0」误记成「位置在推进」，
        // 让首帧超时判据的硬前置失效。
        val position = p.currentPosition
        if (lastSampledPositionMs >= 0L && position != lastSampledPositionMs) {
            positionChangedAtMs = now
        }
        lastSampledPositionMs = position

        if (!allowed) {
            // 不允许出帧的这一段，锚点保持不动（不能刷新，否则会掩盖真冻结）；
            // 但要记住「这一拍没在采样」，好在恢复播放的那一刻重新起算（见下）。
            wasSampling = false
            return
        }
        // 暂停/缓冲 → 恢复播放的**跳变**：把锚点重锚到当下。
        //
        // 这是 v5.3.0 漏掉的一条误判来源：暂停期间 lastRenderedFrameAtMs 冻在暂停前，
        // 用户暂停 20 秒再继续播，恢复后的第一拍 elapsed-last 已经是 20 秒 > 6 秒，
        // 于是「刚点继续播就判冻结并切内核」。恢复的瞬间重锚，这一条就消失了。
        if (!wasSampling) {
            wasSampling = true
            currentRenderedFrameCount()?.let { renderedFrameCount = it }
            lastRenderedFrameAtMs = now
        }
        val count = currentRenderedFrameCount()
        if (count == null) {
            // GAP-A 的落地点：计数器此刻读不到（渲染器被 disable / 已 release）。
            // 「读不到」不代表「没出帧」—— 无法度量就不该判定，把锚点推到当下续命，
            // 避免「永久卡在旧锚点 → 12 秒后必判冻结」。
            lastRenderedFrameAtMs = now
            return
        }
        if (count != renderedFrameCount) {
            renderedFrameCount = count
            lastRenderedFrameAtMs = now
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
        // v5.3.2：保持窗口也要清 —— 换流后不能拿上一段的"最后一次有吞吐"续着显示
        lastNonZeroSpeedAtMs = 0L
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
            // v5.3.2（症状 B「网速指示不断从有数字到空白横跳」）：
            // media3 的 `isLoading` 在「读满一个分片 → 停读 → 再读」之间会**非常频繁地开合**
            // （尤其是修好起播缓冲后，一次分片读完就会停一拍）。旧实现只要 `!active` 就立刻把
            // 对外值打成 0，界面（`formatLoadingSpeed` 里 `<= 0` → "-- KB/s"）于是每两次
            // 采样就横跳一次，用户看起来像"网速一直在闪"。
            // 真实含义是"这一拍没有新字节"，不等于"网速为 0"，所以给一个短暂的保持窗口：
            // 只要距上一次有吞吐不到 [SPEED_HOLD_MS] 就继续显示上一个有效值；
            // 超过窗口才归零（那时确实停读了，显示 0 是对的）。
            if (now - lastNonZeroSpeedAtMs >= SPEED_HOLD_MS) tcpSpeedBytesPerSec = 0L
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
        // 一个窗口内没有新字节（解析/等首包/弱网停顿）：同样走保持窗口，而不是直接归零，
        // 否则弱网下每个空窗口都会把数字抹掉一次（横跳的第二个来源）。
        if (delta <= 0L) {
            if (now - lastNonZeroSpeedAtMs >= SPEED_HOLD_MS) tcpSpeedBytesPerSec = 0L
            return
        }
        tcpSpeedBytesPerSec = delta * 1000L / elapsed
        lastNonZeroSpeedAtMs = now
    }

    private class NoRetryLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            // reconnect!=1：不重试，立即把错误抛给 onPlayerError（app 有自己的重试兜底）
            return C.TIME_UNSET
        }
    }

    private companion object {
        const val TAG = "Media3Player"

        /**
         * 判定「播到末尾」的最小容差：时长 1%（下限 2 秒）。
         *
         * 为什么不能要求 position == duration：DASH 音频轨通常比视频轨短几百毫秒，
         * 尾帧解码也会吞掉一点时长，硬等精确相等会让**真播完**的流永远不上报完成，
         * 表现就是「放完了不停、也不切下一P」。
         */
        const val END_TOLERANCE_MIN_MS = 2_000L

        /** 轮询间隔：与 PlayerActivity 的进度刷新节奏匹配 */
        const val POLL_INTERVAL_MS = 500L

        /**
         * 网速差分的最小窗口：ticker 名义 500ms，但主线程卡顿会让相邻两拍只差几十毫秒，
         * 那样算出来的 B/s 会把抖动放大好几倍。短于这个值就跳过本拍（保留旧值）。
         */
        const val MIN_SPEED_WINDOW_MS = 200L

        /**
         * 网速显示的在「无新字节」期间的保持窗口（v5.3.2，对应症状 B 的横跳）。
         *
         * media3 的 `isLoading` 与 TransferListener 的字节增量都是**分片级**脉动的：
         * 一个分片读完后会短暂停读，下一分片才开始。ticker 是 500ms 一拍，
         * 于是「读到 / 没读到」会交替出现。旧实现在「没读到」这一拍直接把对外值写成 0，
         * 界面就 `-- KB/s` ↔ `123 KB/s` 来回闪。
         *
         * 取 2 秒：足够跨过「分片边界 + 一次短重试退避」的空窗，又不会让真正的停播
         * （暂停/缓冲完成停读）显示一个假的旧速率太久 —— 真停读 2 秒后仍会归零。
         */
        const val SPEED_HOLD_MS = 2_000L

        /** max-buffer-size 缺省值：与 PlayerActivity 默认档一致（20MB） */
        const val DEFAULT_MAX_BUFFER_BYTES = 20L * 1024L * 1024L

        /**
         * 脉冲档 minBuffer（v5.3.2 从 90s 降到 30s）：一次拉足再停读，触底续读
         * （近似 ijk 的 `packet-buffering=0` + 40MB `max-buffer-size`）。
         *
         * 为什么不再用 90s：`minBufferMs` 在 DefaultLoadControl 的语义是「低于它就一直读网」，
         * 90s 等于**要攒满 90 秒才肯停读**。手表上带宽本来就窄，这个窗口内读网几乎不停，
         * 用户看到的就是网速指示一直跳；而一旦某一拍读不动，`buffered` 又掉不到
         * `bufferForPlaybackMs` 之上，就永远在「读→缓冲→再读」之间循环（症状 B）。
         * 30s 足够覆盖一次网络抖动，又不会被 20MB 的 `targetBufferBytes` 上限直接截断
         * （B 站 DASH 视频轨码率约 0.3~1.5Mbps，20MB ≈ 110~500s，取 30s 不会被字节上限反噬）。
         */
        const val MIN_BUFFER_PULSE_MS = 30_000
        const val MAX_BUFFER_PULSE_MS = 60_000

        /** 持续档 minBuffer：边播边平滑缓冲（≈20s，对应 PlayerActivity 默认 20MB） */
        const val MIN_BUFFER_CONTINUOUS_MS = 20_000
        const val MAX_BUFFER_CONTINUOUS_MS = 60_000

        /**
         * 起播/卡顿恢复缓冲（v5.3.2：0.5s / 1s → 3s / 5s）。
         *
         * ## 为什么必须加长（对应真机症状 A/B）
         * 旧值只要求「缓冲到 500ms 就开始播」「欠载后缓冲到 1s 就恢复」，这是
         * **桌面/局域网网络的取值**。手表弱网下 500ms 的缓冲量在一次码率抖动或
         * TCP 重传里几帧就吃光 → 立刻 underrun → 进 BUFFERING → 读网 → 攒 1s →
         * 又恢复播放 …… 用户看到的就是
         * - 「网速指示不断从有数字到空白横跳」（`isLoading` 反复开合，见 updateSpeedSample）；
         * - 「一直加载不出视频」（起播那 500ms 永远凑不稳，反复回退到缓冲态）。
         * 而 ijk 侧是 `packet-buffering=1` + 20MB 缓冲先攒够再放，所以一进去就能播。
         *
         * 取 3s/5s：3 秒是弱网下能扛住一次 RTT 抖动 + 一次 IDR 等待的最小量；
         * 5 秒给「卡了一下之后」的恢复留出更多余量（宁愿多等两秒也不要反复卡）。
         * ⚠ 约束（`DefaultLoadControl` 构造里的 `assertGreaterOrEqual`，**违反会直接抛
         * IllegalArgumentException 崩掉**）：
         *   bufferForPlaybackMs <= minBufferMs 且 bufferForRebufferMs <= minBufferMs；
         *   且 maxBufferMs >= minBufferMs。当前取值 3s/5s vs min 20s（持续）/30s（脉冲）
         *   全部满足，改这几个常量时必须重算这三条不等式。
         */
        const val BUFFER_FOR_PLAYBACK_MS = 3_000
        const val BUFFER_FOR_REBUFFER_MS = 5_000

        /** HTTP 超时缺省（微秒）：与 story 的 timeout option 一致（15s） */
        const val DEFAULT_TIMEOUT_US = 15_000_000L

        /**
         * 渲染帧停止推进多久判定为「画面冻住」（v5.3.0；v5.3.1 从 6 秒拉长到 12 秒）。
         *
         * ## 为什么必须拉长（这是本轮误判修复的核心之一）
         * 6 秒的原始理由「正常 24~60fps 视频 500ms 内至少出 12 帧」只对**稳态播放**成立。
         * 实测会连续数秒不出帧的正常场景有一大把：
         * - 硬解换档 / 自适应切轨（`mediacodec-handle-resolution-change`）→ 解码器重建；
         * - B 站 DASH 分片边界的 IDR 间隔，2~4 秒不出帧是常态；
         * - 切到后台又切回来、旋转屏幕 → Surface 销毁重建，整条渲染管线重来；
         * - 弱网下的重缓冲（位置由音频轨驱动仍在涨，画面先停）。
         * 这些窗口一旦叠起来就超过 6 秒 → 判冻结 → 用户看到「播了几秒就切内核」。
         *
         * 12 秒的取值依据：① 它必须**明显大于**上面任何单个正常窗口
         * （IDR 最长 4 秒 + 解码器重建 < 1 秒 + surface 重建 < 1 秒 ≈ 6 秒，取 2 倍余量）；
         * ② 真·冻死时用户已经在怀疑播放器了（10 秒级），12 秒切换仍在可接受范围，
         * 而且真冻死用户还会手动拖进度条（拖完有 seek 宽限 + 位置重建，不会卡住不出手）。
         * ③ 用户明确要求「宁可漏判，不可误判」—— 多等 6 秒的代价远小于白切一次内核。
         */
        const val RENDER_STALL_TIMEOUT_MS = 12_000L

        /**
         * seek 之后的宽限窗口（v5.3.1 新增）：这段时间内一律不判冻结/首帧超时。
         *
         * 为什么需要：seek 会重建解码器并从新的关键帧开始，B 站 DASH 的 IDR 间隔
         * 可达 2~4 秒；原实现只清了看门狗的计时，没清渲染判据的锚点，
         * 于是「刚拖完进度条就被判成冻结」（也是一条「播几秒就切内核」的来源）。
         * 取 5 秒（略大于最长 IDR 间隔 + 重建开销），比冻结阈值短，
         * 保证 seek 之后仍有可能在 12 秒判出真冻结（5 + 12 = 17 秒内必有一次机会）。
         */
        const val SEEK_GRACE_MS = 5_000L

        /**
         * 「播放位置多久没变化就算没在播」（v5.3.1 新增）：冻结判据的独立旁证。
         *
         * 3 秒的取值理由：音频轨驱动的位置每帧都会变，正常播放时**几乎每一拍都在变**；
         * 这里留 3 秒是给「高倍速下位置跳变极大」「主线程卡顿导致 ticker 漏拍」留余量。
         * 一旦位置 3 秒没动，就说明不是在播（网络卡死/解码卡死/暂停），
         * 那属于看门狗「重建数据源」的职责，**换内核解决不了问题**，所以不判冻结。
         */
        const val POSITION_ALIVE_MS = 3_000L

        /**
         * `STATE_READY` 之后多久仍然没有任何首帧，判定为「黑屏起不来」
         * （v5.3.0；v5.3.1 从 8 秒拉长到 20 秒）。
         *
         * 为什么不沿用 8 秒：8 秒是 HANDOFF §3.4 里「prepare 后 8 秒无首帧」的旧口径，
         * 但它**没有区分「网络慢」和「渲染不出」**。弱网下 READY 之后还要下几个分片、
         * 等一个 4 秒 IDR、硬解器初始化 —— 8 秒被吃完是常见现象，
         * 此时换 ijk 同样黑屏，纯属白切。
         * 20 秒的依据：正常首帧在 3 秒内必到；超过 20 秒还黑着，
         * 要么真是渲染管线坏了（该切），要么用户早就自己退出了 —— 是个安全的下界。
         * 再叠加「位置必须在推进」这条硬前置（见 [isFirstFrameOverdue]），
         * 纯加载慢的场景根本不会触发。
         */
        const val FIRST_FRAME_TIMEOUT_MS = 20_000L
    }
}
