package com.RobinNotBad.BiliClient.activity.player

import android.content.SharedPreferences
import android.graphics.Color
import com.RobinNotBad.BiliClient.api.DanmakuApi
import com.RobinNotBad.BiliClient.model.DmSegMobileReply
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView
import master.flame.danmaku.danmaku.parser.android.BiliDanmukuParser
import master.flame.danmaku.danmaku.parser.android.BiliProtobufDanmakuParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 播放器弹幕控制器（Kotlin 重构，从 PlayerActivity 中抽离的关键代码）
 *
 * 统一负责弹幕层的完整生命周期：
 * - DanmakuContext 配置（含**重绘频率限制**，降低发热）；
 * - 解析器创建（新版 protobuf / 旧版 XML / 空层）；
 * - 时间轴与播放器同步；
 * - 在线弹幕拉取：**逐段增量渲染 + 失败分段后台补拉**，解决网络波动导致的弹幕丢失；
 * - 本地弹幕文件加载；
 * - 手动发送/直播弹幕的即时插入。
 *
 * 从 Java 侧调用（PlayerActivity）时通过 [attach]、[startLocal]、
 * [startOnline] 等公开方法即可，内部细节全部收敛在 Kotlin 里。
 */
class PlayerDanmakuController(
    private val danmakuView: IDanmakuView?,
    /** 播放位置提供者（毫秒），用于把弹幕时间轴对齐到播放器 */
    private val positionProvider: () -> Long,
    /** 播放器是否已准备完成 */
    private val preparedProvider: () -> Boolean,
    /** 是否已销毁，避免退出后继续操作弹幕层 */
    private val destroyedProvider: () -> Boolean
) {

    companion object {
        /** 默认弹幕重绘间隔：33ms ≈ 30fps。原实现为 16ms(60fps)，红色重绘量减半。 */
        const val DEFAULT_REFRESH_RATE_MS = 33

        /** 弹幕重绘频率设置键 */
        const val PREF_REFRESH_RATE = "player_danmaku_refreshrate"

        /** 等待播放器解析时长的上限 */
        private const val DURATION_WAIT_MS = 4000L
        private const val DURATION_POLL_MS = 150L
    }

    /** 视频时长提供者（毫秒）。播放器可能尚未解析出时长，此时返回 <=0。 */
    fun interface DurationProvider {
        fun getDurationMs(): Long
    }

    /** 首次弹幕拉取结束回调（Java 侧可直接用 lambda） */
    fun interface LoadFinishedListener {
        fun onLoadFinished(missingCount: Int)
    }

    /** 弹幕上下文 */
    var context: DanmakuContext? = null
        private set

    /** 当前解析器（增量追加分段时需要） */
    private var parser: BaseDanmakuParser? = null

    /** 当前是否使用 protobuf 解析器 */
    private var protobufParser: BiliProtobufDanmakuParser? = null

    /** 是否已经 prepare 过弹幕层 */
    private var layerPrepared = false

    /** 弹幕层是否已启动 */
    private var started = false

    /**
     * 已到达但弹幕层尚未 prepare 完成的分段。
     *
     * 必要性：DanmakuView.prepare() 是异步的（要等 Surface 就绪 + 建绘制线程），
     * 而弹幕分段可能在它完成之前就下载好了。若此时直接解析，解析器里的
     * mContext 还是 null，createDanmaku 会返回 null —— 这一段的弹幕就被静默丢掉了。
     * 所以先缓存，等 prepared() 回调里一次性补进去。
     */
    private val pendingSegments = ArrayList<DmSegMobileReply>()

    /** 是否存在弹幕 */
    @JvmField
    var hasDanmaku = false

    /**
     * 温控（#8）算出的重绘间隔覆盖值；为 null 时使用用户设置的固定间隔。
     * 由播放页在温度变化时写入；改变后需要调用 buildContext(force = true) 生效。
     */
    @JvmField
    var thermalIntervalOverride: Int? = null

    /** 补拉器 */
    private var backfiller: DanmakuBackfiller? = null

    /** 首次在线拉取的取消标志 */
    private var fetchCancelled: AtomicBoolean? = null

    /** 弹幕层是否可见（用于 hide/show 切换后恢复） */
    @JvmField
    var danmakuVisible = true

    /** 旧版 XML 弹幕地址，由播放页注入（新版接口失败时回退用） */
    @JvmField
    var oldApiUrl: String? = null

    /** 弹幕缓存文件，由播放页注入（旧接口回退时写入）。Java 侧直接字段赋值。 */
    @JvmField
    var cacheFile: File? = null

    /**
     * 配置弹幕上下文。与旧实现保持一致的可配置项，另外加入重绘频率限制以降低发热。
     */
    @JvmOverloads
    fun buildContext(force: Boolean = false): DanmakuContext {
        val existing = context
        if (existing != null && !force) return existing

        val ctx = DanmakuContext.create()
        val maxLinesPair = HashMap<Int, Int>()
        maxLinesPair[BaseDanmaku.TYPE_SCROLL_RL] = SharedPreferencesUtil.getInt("player_danmaku_maxline", 15)
        val overlap = HashMap<Int, Boolean>()
        val allowOverlap = SharedPreferencesUtil.getBoolean("player_danmaku_allowoverlap", true)
        overlap[BaseDanmaku.TYPE_SCROLL_LR] = allowOverlap
        overlap[BaseDanmaku.TYPE_FIX_BOTTOM] = allowOverlap

        // 重绘频率限制：默认 33ms（30fps）。弹幕是覆盖层，30fps 与 60fps 观感差异很小，
        // 但重绘次数减半能实实在在降低 GPU/CPU 占用与发热。
        // 温控开启时（#8）忽略用户设置的固定帧率，改由温度决定（见 thermalIntervalOverride）。
        ctx.refreshRateMS = thermalIntervalOverride
            ?: SharedPreferencesUtil.getInt(PREF_REFRESH_RATE, DEFAULT_REFRESH_RATE_MS).coerceIn(16, 66)

        ctx.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 1f)
            .setDuplicateMergingEnabled(SharedPreferencesUtil.getBoolean("player_danmaku_mergeduplicate", false))
            .setScrollSpeedFactor(SharedPreferencesUtil.getFloat("player_danmaku_speed", 1.0f))
            .setScaleTextSize(SharedPreferencesUtil.getFloat("player_danmaku_size", 0.7f))
            .setMaximumLines(maxLinesPair)
            .setDanmakuTransparency(SharedPreferencesUtil.getFloat("player_danmaku_transparency", 0.5f))
            .preventOverlapping(overlap)

        // #7：同屏数量硬上限。默认 -1 走"自动"（ELAPSED_TIME 过滤），弹幕密集时
        // 同屏仍可能堆几十条导致绘制/缓存压力大而卡顿。改为固定数量过滤
        // （QUANTITY 过滤器），超出部分按优先级丢弃，画面清爽且 GPU 压力可控。
        val maxInScreen = SharedPreferencesUtil.getInt("player_danmaku_maxinscreen", 30)
        if (maxInScreen > 0)
            ctx.setMaximumVisibleSizeInScreen(maxInScreen)

        context = ctx
        return ctx
    }

    private fun prefs(): SharedPreferences? = SharedPreferencesUtil.getSharedPreferences()

    /**
     * 设置弹幕层回调并 prepare。空层也走这里，保证弹幕视图随时可用
     * （直播模式、发送弹幕、欢迎语都依赖已 prepare 的层）。
     *
     * @param danmakuFile 旧版 XML 弹幕文件，可为 null
     * @param segments    新版 protobuf 分段，可为 null
     */
    @JvmOverloads
    fun attach(danmakuFile: String? = null, segments: List<DmSegMobileReply>? = null) {
        val view = danmakuView ?: return
        val ctx = context ?: buildContext()

        // prepare 是异步的：在 prepared() 回调回来之前，解析器的 mContext 仍为 null，
        // 此时任何 addSegment 都会解析失败被丢弃。所以先标记「未就绪」，
        // 让新到的分段进入暂存队列，等 prepared() 时再补渲染。
        layerPrepared = false

        val newParser = createParser(danmakuFile, segments)
        parser = newParser

        view.setCallback(object : DrawHandler.Callback {
            override fun prepared() {
                Logu.v("danmaku", "prepared")
                layerPrepared = true

                // 把「先于弹幕层就绪」到达的分段补渲染，避免这部分弹幕丢失
                flushPendingSegments()

                // 弹幕层就绪后，如果之前已经在播放，立刻对齐时间轴
                if (started) {
                    view.start(positionProvider())
                }
                val msg = if (protobufParser != null) {
                    "弹幕君准备完毕～(是新来的哦～)"
                } else {
                    "弹幕君准备完毕～(*≧ω≦)"
                }
                addLocalDanmaku(msg, Color.WHITE, 25, 1, 0)
            }

            override fun updateTimer(timer: DanmakuTimer) {
                // 时间轴与播放器严格对齐，避免长时间播放后弹幕与画面漂移
                if (preparedProvider()) {
                    timer.update(positionProvider())
                }
            }

            override fun danmakuShown(danmaku: BaseDanmaku) {
                // #2：弹幕文本里带时间戳（如 "8:17"、"1:02:03"）时提示可跳转。
                // 弹幕层没有点击焦点，所以展示时解析、由播放页决定是否提供跳转入口。
                val listener = timestampListener ?: return
                val text = danmaku.text ?: return
                val ms = parseTimestamp(text)
                if (ms > 0) {
                    try {
                        listener.onTimestamp(text.toString(), ms)
                    } catch (ignored: Exception) {
                    }
                }
            }

            override fun drawingFinished() {}
        })

        // 文本绘制缓存：避免每帧重复测量与描边，显著降低绘制开销
        view.enableDanmakuDrawingCache(true)
        view.prepare(newParser, ctx)
    }

    /**
     * 启动弹幕滚动。
     *
     * 传当前播放位置而不是 0：从暂停/缓冲恢复、切P、自动恢复等场景下，
     * 弹幕时间轴能立刻对齐画面，不会出现“弹幕从头滚一遍”。
     */
    fun start() {
        val view = danmakuView ?: return
        started = true
        val position = try {
            positionProvider()
        } catch (e: Exception) {
            0L
        }
        view.start(position)
    }

    fun pause() {
        danmakuView?.pause()
    }

    fun resume() {
        if (hasDanmaku || started) danmakuView?.resume()
    }

    fun seekTo(ms: Long) {
        if (hasDanmaku) danmakuView?.seekTo(ms)
    }

    fun setSpeed(speed: Float) {
        danmakuView?.setSpeed(speed)
    }

    /**
     * 运行时调整弹幕重绘帧率（温控用，#8）。
     *
     * @param intervalMs 两帧最小间隔毫秒（16≈60fps / 33≈30fps / 66≈15fps）
     */
    fun updateFrameRate(intervalMs: Int) {
        val view = danmakuView as? DanmakuView
        view?.handler?.updateFrameRate(intervalMs.toLong())
    }

    fun hide() {
        danmakuVisible = false
        danmakuView?.hide()
    }

    fun show() {
        danmakuVisible = true
        danmakuView?.show()
    }

    fun release() {
        fetchCancelled?.set(true)
        backfiller?.cancel()
        backfiller = null
        danmakuView?.release()
    }

    /** 当前弹幕时间轴位置（毫秒），用于插入即时弹幕 */
    fun currentTime(): Long = danmakuView?.currentTime ?: 0L

    // ------------------------------------------------------------------
    // 加载入口
    // ------------------------------------------------------------------

    /**
     * 加载本地 XML 弹幕文件。
     *
     * @return 是否成功加载
     */
    fun startLocal(file: String): Boolean {
        if (file.isEmpty()) {
            hasDanmaku = false
            return false
        }
        if (!File(file).exists()) {
            hasDanmaku = false
            return false
        }
        hasDanmaku = true
        attach(file, null)
        return true
    }

    /**
     * 加载本地弹幕；[danmakuFile] 为 null 或文件不存在时只准备空弹幕层
     * （直播模式、在线弹幕分段到达前的占位都走这里）。
     */
    @JvmOverloads
    fun startLocalOrEmpty(danmakuFile: String? = null): Boolean {
        if (danmakuFile.isNullOrEmpty() || !File(danmakuFile).exists()) {
            hasDanmaku = false
            attach(null, null)
            return false
        }
        hasDanmaku = true
        attach(danmakuFile, null)
        return true
    }

    /**
     * 在线拉取弹幕（新版 API，protobuf）。
     *
     * 这是“网络波动导致弹幕丢失”的核心修复点：
     * 1. 逐段拉取，**每拿到一段立即追加渲染**，不再等全部拉完；
     * 2. 单段失败自动重试（指数退避），彻底失败只登记为缺失，不影响其他段；
     * 3. 缺失分段交给 [DanmakuBackfiller] 在后台持续补拉，补到即增量渲染。
     *
     * @param aid           稿件 avid
     * @param cid           视频 cid
     * @param durationProvider 视频时长提供者（毫秒）。播放器尚未解析出时长时返回 <=0，
     *                         本方法会短暂等待，避免短视频被切成过多分段（省流量、省电）。
     * @param onLoadFinished   首次拉取结束回调（参数为缺失分段数）
     */
    fun startOnline(
        aid: Long,
        cid: Long,
        durationProvider: DurationProvider,
        onLoadFinished: LoadFinishedListener? = null
    ) {
        if (destroyedProvider()) return

        // 先把弹幕层准备好（空层），这样分段一到就能立刻渲染
        hasDanmaku = true
        attach(null, ArrayList())

        val cancelled = AtomicBoolean(false)
        fetchCancelled = cancelled

        CenterThreadPool.run {
            try {
                val durationMs = resolveDuration(durationProvider, cancelled)
                val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else 3600
                Logu.d("弹幕", "开始拉取在线弹幕 aid=$aid cid=$cid duration=${durationSec}s")

                val report = DanmakuApi.fetchAllSegments(
                    aid, cid, durationSec,
                    { index, reply ->
                        // 每段到手立即渲染（回调在拉取线程，addSegment 内部会切主线程）
                        addSegment(reply)
                        Logu.d("弹幕", "分段 $index 已增量渲染")
                    },
                    cancelled
                )

                if (destroyedProvider() || cancelled.get()) return@run

                if (report.fetchedCount == 0) {
                    // 一段都没拿到：回退到旧版 XML 接口再试一次
                    Logu.w("弹幕", "新版接口一段都没拿到，回退旧版接口")
                    fallbackToOldApi()
                    onLoadFinished?.onLoadFinished(report.totalSegments)
                    return@run
                }

                onLoadFinished?.onLoadFinished(report.missing.size)

                // 缺失分段后台补拉
                if (report.missing.isNotEmpty() && !destroyedProvider()) {
                    startBackfill(aid, cid, report.missing)
                }
            } catch (e: Exception) {
                Logu.e("弹幕", "在线弹幕拉取异常: " + e.message)
                if (!destroyedProvider()) {
                    fallbackToOldApi()
                    onLoadFinished?.onLoadFinished(-1)
                }
            }
        }
    }

    /**
     * 等待播放器解析出视频时长。
     *
     * 原实现在拿不到时长时直接按 1 小时估算，结果一个 3 分钟的视频也要请求 11 个分段，
     * 绝大多数都是空包 —— 纯浪费流量和电量。这里最多等 4 秒拿到真实时长。
     */
    private fun resolveDuration(durationProvider: DurationProvider, cancelled: AtomicBoolean): Long {
        var duration = try {
            durationProvider.getDurationMs()
        } catch (e: Exception) {
            0L
        }
        if (duration > 0) return duration

        val deadline = System.currentTimeMillis() + DURATION_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.get() || destroyedProvider()) return 0L
            try {
                Thread.sleep(DURATION_POLL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0L
            }
            duration = try {
                durationProvider.getDurationMs()
            } catch (e: Exception) {
                0L
            }
            if (duration > 0) return duration
        }
        Logu.d("弹幕", "等待视频时长超时，按 1 小时估算分段数")
        return 0L
    }

    private fun startBackfill(aid: Long, cid: Long, missing: List<Int>) {
        backfiller?.cancel()
        val bf = DanmakuBackfiller(
            aid, cid, missing,
            object : DanmakuBackfiller.Listener {
                override fun onSegmentFetched(index: Int, reply: DmSegMobileReply) {
                    if (destroyedProvider()) return
                    Logu.d("弹幕", "补拉分段 $index 增量渲染（${reply.elems.size} 条）")
                    // #7：不在回调线程直接解析。补拉回调可能多段集中到达，
                    // 每段几百条弹幕的 parseSegment + 逐条 addDanmaku 必须放在
                    // 后台线程执行（与首次拉取路径一致），否则主线程被卡，
                    // 表现为"弹幕太多时界面卡住"。
                    CenterThreadPool.run {
                        if (destroyedProvider()) return@run
                        addSegment(reply)
                    }
                }

                override fun onFinished(pendingCount: Int) {
                    if (pendingCount > 0) {
                        Logu.w("弹幕", "补拉结束，仍有 $pendingCount 段未取到")
                    }
                }

                override fun onCancelled() {}
            },
            { (positionProvider() / 1000L).toInt() }
        )
        backfiller = bf
        bf.start()
    }

    /**
     * 旧版 XML 弹幕接口回退。
     *
     * 新版 seg.so 接口在弱网/风控下可能整段失败，此时用旧接口
     * https://comment.bilibili.com/{cid}.xml 兜底，保证“有弹幕可看”。
     */
    private fun fallbackToOldApi() {
        if (destroyedProvider()) return
        val url = oldApiUrl
        if (url.isNullOrEmpty()) {
            Logu.w("弹幕", "无旧版弹幕地址，跳过回退")
            return
        }
        val target = cacheFile
        if (target == null) {
            Logu.w("弹幕", "无缓存文件，跳过旧版回退")
            return
        }
        try {
            val cancelled = fetchCancelled
            val file = OnlineDanmakuDownloader.download(url, target, cancelled)
            if (file != null && file.exists() && !destroyedProvider() && cancelled?.get() != true) {
                Logu.d("弹幕", "旧版接口回退成功，加载 XML 弹幕")
                attach(file.absolutePath, null)
                hasDanmaku = true
                addLocalDanmaku("已回退到旧版弹幕接口～", Color.WHITE, 25, 1, 0)
            }
        } catch (e: Exception) {
            Logu.e("弹幕", "旧版接口回退失败: " + e.message)
        }
    }

    // ------------------------------------------------------------------
    // 增量渲染
    // ------------------------------------------------------------------

    /**
     * 把一段 protobuf 弹幕增量追加到弹幕层。
     *
     * 关键点：追加后重置 DrawTask 的时间窗口缓存，保证“已经划过屏幕的时间点”
     * 也能被重新纳入索引（否则补拉的弹幕不会显示）。这一步通过 addDanmaku 内部
     * 对窗口边界的重置逻辑天然完成，这里只需保证逐条加入。
     */
    fun addSegment(reply: DmSegMobileReply?) {
        if (reply == null || reply.elems.isEmpty()) return
        if (danmakuView == null) return

        // 弹幕层还没准备好：先缓存，等 prepared() 回调里再补渲染
        if (!layerPrepared) {
            synchronized(pendingSegments) {
                pendingSegments.add(reply)
            }
            Logu.d("danmaku", "弹幕层未就绪，分段暂存（当前暂存 ${pendingSegments.size} 段）")
            return
        }

        renderSegmentNow(reply)
    }

    /** 把缓存的分段一次性渲染（弹幕层就绪后调用） */
    private fun flushPendingSegments() {
        val snapshot = synchronized(pendingSegments) {
            if (pendingSegments.isEmpty()) return
            val copy = ArrayList(pendingSegments)
            pendingSegments.clear()
            copy
        }
        Logu.d("danmaku", "弹幕层就绪，补渲染暂存的 ${snapshot.size} 个分段")
        for (reply in snapshot) {
            renderSegmentNow(reply)
        }
    }

    /**
     * 立即把一段弹幕渲染到弹幕层。
     */
    private fun renderSegmentNow(reply: DmSegMobileReply) {
        val p = protobufParser ?: createProtobufParser()
        val danmakus = p.parseSegment(reply)
        addDanmakus(danmakus)
    }

    /**
     * 基于当前上下文创建一个可用的 protobuf 解析器（弹幕层就绪后调用）。
     * 用于“已回退到旧版 XML 之后又收到新版分段”这种混合场景。
     */
    private fun createProtobufParser(): BiliProtobufDanmakuParser {
        val ctx = context ?: buildContext()
        val p = BiliProtobufDanmakuParser()
        p.sharedPreferences = prefs()
        p.setConfig(ctx)
        p.setDisplayer(ctx.displayer)
        p.setTimer(DanmakuTimer())
        protobufParser = p
        return p
    }

    private fun addDanmakus(danmakus: IDanmakus?) {
        val view = danmakuView ?: return
        if (danmakus == null || danmakus.isEmpty) return
        var count = 0
        val it = danmakus.iterator()
        while (it.hasNext()) {
            val item = it.next() ?: continue
            view.addDanmaku(item)
            count++
        }
        if (count > 0) hasDanmaku = true
        Logu.d("danmaku", "增量追加 $count 条弹幕")
    }

    /** 插入一条本地弹幕（欢迎语 / 自己发送的弹幕 / 直播弹幕） */
    fun addLocalDanmaku(text: String?, color: Int, textSize: Int, type: Int, backgroundColor: Int) {
        if (text.isNullOrEmpty()) return
        val view = danmakuView ?: return
        val ctx = context ?: return
        val danmaku = ctx.mDanmakuFactory.createDanmaku(type) ?: return
        danmaku.text = text
        danmaku.padding = 5
        danmaku.priority = 1
        danmaku.textColor = color
        danmaku.backgroundColor = backgroundColor
        danmaku.textSize = textSize * (ctx.displayer.density - 0.6f)
        danmaku.time = currentTime() + 100
        view.addDanmaku(danmaku)
    }

    /** 弹幕显示时回调（时间戳跳转用，#2）。参数为解析出的毫秒数。 */
    fun interface DanmakuTimestampListener {
        fun onTimestamp(text: String, timeMs: Long)
    }

    /** 弹幕文本时间戳监听（播放页注入，null 关闭该功能） */
    @JvmField
    var timestampListener: DanmakuTimestampListener? = null

    /**
     * 弹幕文本时间戳格式：8:17 / 04:16 / 1:02:03。
     * 必须整段独立出现（前后不是数字/冒号），避免把 1:02:03 的 "02:03" 或
     * "比分 3:2" 之类误判；纯 hh:mm:ss 与 mm:ss 都接受。
     */
    private val timestampPattern = Regex("(?<![0-9:])[0-9]{1,2}:[0-9]{1,2}(?::[0-9]{1,2})?(?![0-9:])")

    /**
     * 从弹幕文本解析第一个时间戳（毫秒），格式不匹配返回 -1。
     * 在绘制线程调用，必须轻量。
     */
    private fun parseTimestamp(text: CharSequence): Long {
        val s = text.toString().trim()
        if (s.isEmpty() || s.length > 60) return -1L
        val m = timestampPattern.find(s) ?: return -1L
        val parts = m.value.split(":")
        var total = 0L
        for (part in parts) {
            val v = part.toLongOrNull() ?: return -1L
            if (v < 0 || v >= 60 && part !== parts.last()) return -1L
            total = total * 60 + v
        }
        // 至少要有实际的分钟+秒结构（m:ss），纯 "5" 之类不会匹配正则
        return if (total > 0) total * 1000 else -1L
    }

    // ------------------------------------------------------------------
    // 解析器构造
    // ------------------------------------------------------------------

    private fun createParser(danmakuFile: String?, segments: List<DmSegMobileReply>?): BaseDanmakuParser {
        if (segments != null && segments.isNotEmpty()) {
            val p = BiliProtobufDanmakuParser()
            p.sharedPreferences = prefs()
            p.setDanmakuSegments(segments)
            protobufParser = p
            return p
        }

        if (segments != null) {
            // 空分段：仍使用 protobuf 解析器占位，后续可增量追加
            val p = BiliProtobufDanmakuParser()
            p.sharedPreferences = prefs()
            p.setDanmakuSegments(segments)
            protobufParser = p
            return p
        }

        if (danmakuFile == null) {
            // 空层：返回空解析器（后续通过 addSegment 增量追加）
            protobufParser = null
            return object : BaseDanmakuParser() {
                override fun parse(): IDanmakus = Danmakus()
            }
        }

        // 旧版 XML
        val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
        loader?.load(danmakuFile)
        val p = BiliDanmukuParser()
        p.sharedPreferences = prefs()
        val source = loader?.dataSource
        if (source != null) p.load(source)
        protobufParser = null
        return p
    }
}
