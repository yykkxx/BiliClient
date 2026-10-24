package com.RobinNotBad.BiliClient.activity.video.story

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.StoryApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

/**
 * 短视频播放器（shortvideo 分支，专用竖屏播放页）。
 *
 * v5.0.1-alpha5 重构（针对「黑屏 + 连视频详细信息都加载不出来」）：
 * - 数据源改为 StoryApi.loadFeed：专用接口失败自动降级推荐流，并把失败原因显示在页面上；
 * - 取流改为 StoryApi.resolvePlay：PC durl → HTML5 durl → DASH 三级回退；
 * - 播放内核换成 StoryPlaybackController（SurfaceView + 与主播放器一致的参数 +
 *   首帧看门狗 + 硬解失败自动软解）；
 * - 所有错误常驻显示、点按重试，不再「一闪而过」导致只能看到黑屏；
 * - 圆屏手表（player_ui_round）下给顶栏/按钮/底部信息留出圆形安全区。
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class StoryPlayerActivity : BaseActivity() {

    private var surfaceView: SurfaceView? = null
    private var topBar: LinearLayout? = null
    private var buttonsBar: LinearLayout? = null
    private var titleView: TextView? = null
    private var upnameView: TextView? = null
    private var loadingView: View? = null
    private var loadingText: TextView? = null
    private var statusView: TextView? = null
    private var hintView: TextView? = null
    private var playBtn: ImageView? = null
    private var likeBtn: ImageView? = null
    private var progressView: ProgressBar? = null
    private var metaView: TextView? = null

    private var controller: StoryPlaybackController? = null

    /** 短视频流（StoryApi 专用接口，失败时自动降级推荐流） */
    private val storyList = ArrayList<StoryApi.StoryCard>()

    /** 已解析过的直链缓存（索引 → 直链），切换时秒开 */
    private val playCache = HashMap<Int, StoryApi.StoryPlayInfo>()

    private var currentIndex = -1
    private var currentCard: StoryApi.StoryCard? = null
    private var currentHeaders: HashMap<String, String> = HashMap()
    private var currentInfo: StoryApi.StoryPlayInfo? = null

    /** 正在切换/加载（防连滑重入） */
    private var switching = false
    private var loadingMore = false

    /** 是否已经尝试过备用线路（HTML5） */
    private var triedHtml5 = false

    /** 是否已经出过画面（决定缓冲提示是否隐藏） */
    private var firstFrameShown = false

    /** 连续失败计数（出画面后清零） */
    private var failCount = 0

    private var destroyed = false
    private var liked = false

    private var controlsVisible = true
    private var statusAutoHide: Runnable? = null

    private var gestureDetector: GestureDetector? = null

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_player)

        surfaceView = findViewById(R.id.story_surface)
        topBar = findViewById(R.id.story_top)
        buttonsBar = findViewById(R.id.story_buttons)
        titleView = findViewById(R.id.story_title)
        upnameView = findViewById(R.id.story_upname)
        loadingView = findViewById(R.id.story_loading)
        loadingText = findViewById(R.id.story_loading_text)
        statusView = findViewById(R.id.story_status)
        hintView = findViewById(R.id.story_hint)
        playBtn = findViewById(R.id.story_btn_play)
        likeBtn = findViewById(R.id.story_btn_like)
        progressView = findViewById(R.id.story_progress)
        metaView = findViewById(R.id.story_meta)

        findViewById<View>(R.id.story_back).setOnClickListener { finish() }
        playBtn!!.setOnClickListener { togglePlay() }
        likeBtn!!.setOnClickListener { likeCurrent() }
        findViewById<View>(R.id.story_btn_detail).setOnClickListener { openDetail() }

        // 状态文字：常驻错误提示，点按重试当前视频（列表为空时重新拉流）
        statusView!!.setOnClickListener {
            hideStatus()
            if (storyList.isEmpty()) {
                setLoading(true, "重新加载…")
                loadMore { if (!destroyed && storyList.isNotEmpty()) switchTo(0) }
            } else {
                retryCurrent()
            }
        }

        applyRoundInsets()
        initGesture()

        controller = StoryPlaybackController(surfaceView!!, playerListener)

        // 进入即拉第一刷
        setLoading(true, "加载中…")
        loadMore {
            if (!destroyed && storyList.isNotEmpty()) switchTo(0)
            else if (!destroyed) setLoading(false)
        }
    }

    override fun onDestroy() {
        destroyed = true
        try {
            controller?.release()
        } catch (ignored: Exception) {
        }
        controller = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // 切后台暂停，回来继续（避免后台耗电）
        try {
            controller?.pause()
        } catch (ignored: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        //无条件恢复：若离开时还在 prepare，回来时 firstFrameShown 仍是 false，
        //只按 firstFrameShown 判断会导致"回来继续黑屏"
        try {
            controller?.resume()
        } catch (ignored: Exception) {
        }
        if (currentCard != null && !firstFrameShown && !switching) {
            //回到前台但一直没有画面：给一次重试机会（比如后台时 Surface 被销毁）
            if (currentInfo == null) retryCurrent()
        }
    }

    // ==================== 圆屏适配 ====================

    /**
     * 圆屏手表：把顶栏/右侧按钮/底部信息收进圆形安全区。
     * 与主播放器一致使用 player_ui_round 开关（设置-界面-圆屏适配）。
     */
    @SuppressLint("RtlHardcoded")
    private fun applyRoundInsets() {
        if (!SharedPreferencesUtil.getBoolean("player_ui_round", false)) return
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val side = (w * 0.16f).toInt()
        val top = (h * 0.10f).toInt()
        val bottom = (h * 0.12f).toInt()

        topBar?.setPadding(side, top, side, ToolsUtil.dp2px(2f))
        buttonsBar?.setPadding(0, 0, (w * 0.10f).toInt(), 0)
        metaView?.setPadding(side, 0, side, bottom)
        hintView?.setPadding(side, 0, side, bottom)
        statusView?.maxWidth = (w * 0.62f).toInt()

        val p = progressView?.layoutParams
        if (p is FrameLayout.LayoutParams) {
            p.leftMargin = side
            p.rightMargin = side
            progressView?.layoutParams = p
        }
    }

    /**
     * 按视频真实宽高设置画面区域。
     * @param round true=圆屏（缩进圆内接矩形），false=按屏幕等比缩放居中
     */
    private fun fitVideo(width: Int, height: Int, round: Boolean) {
        try {
            val dm = resources.displayMetrics
            val aspect = height.toFloat() / width.toFloat()
            val videoW: Float
            val videoH: Float
            if (round) {
                val screen = Math.min(dm.widthPixels, dm.heightPixels).toFloat()
                videoW = (screen / Math.sqrt((aspect * aspect + 1).toDouble())).toFloat()
                videoH = videoW * aspect
            } else {
                val screenW = dm.widthPixels.toFloat()
                val screenH = dm.heightPixels.toFloat()
                val scale = Math.min(screenW / width.toFloat(), screenH / height.toFloat())
                videoW = width * scale
                videoH = height * scale
            }
            val lp = surfaceView?.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.width = videoW.toInt()
            lp.height = videoH.toInt()
            lp.gravity = android.view.Gravity.CENTER
            surfaceView?.layoutParams = lp
        } catch (e: Exception) {
            Logu.e("story", "视频尺寸适配失败：" + e.message)
        }
    }

    // ==================== 状态显示 ====================

    private fun setLoading(show: Boolean, text: String = "加载中") {
        loadingView?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) loadingText?.text = text
    }

    /** 常驻状态/错误提示（autoHide=true 时 4 秒后自动消失） */
    private fun showStatus(text: String, autoHide: Boolean = false) {
        val v = statusView ?: return
        statusAutoHide?.let { v.removeCallbacks(it) }
        statusAutoHide = null
        v.text = text
        v.visibility = View.VISIBLE
        if (autoHide) {
            val r = Runnable {
                if (!destroyed) v.visibility = View.GONE
            }
            statusAutoHide = r
            v.postDelayed(r, 4000)
        }
    }

    private fun hideStatus() {
        statusAutoHide?.let { statusView?.removeCallbacks(it) }
        statusAutoHide = null
        statusView?.visibility = View.GONE
    }

    private fun showHint(text: String) {
        val v = hintView ?: return
        v.text = text
        v.visibility = View.VISIBLE
        v.removeCallbacks(hideHint)
        v.postDelayed(hideHint, 1800)
    }

    private val hideHint = Runnable { hintView?.visibility = View.GONE }

    // ==================== 手势 ====================

    private fun initGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                // 竖直快扫：上滑下一个、下滑上一个
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > 90f && Math.abs(dy) > Math.abs(dx) * 2f) {
                    if (dy < 0) switchRelative(1) else switchRelative(-1)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 抖音式：单击切换控制层显隐（顶栏+右侧按钮）
                setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }
        })

        findViewById<View>(R.id.story_root).setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        topBar?.visibility = if (visible) View.VISIBLE else View.GONE
        buttonsBar?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) scheduleHideControls(3000)
    }

    private fun scheduleHideControls(delay: Long) {
        val v = topBar ?: return
        v.removeCallbacks(hideControlsRunnable)
        v.postDelayed(hideControlsRunnable, delay)
    }

    private val hideControlsRunnable = Runnable {
        if (!destroyed) setControlsVisible(false)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (controlsVisible && !destroyed) scheduleHideControls(3000)
    }

    // ==================== 流管理 ====================

    /** 拉一刷新短视频（追加到流尾部；失败自动降级推荐流，并显示原因） */
    private fun loadMore(onDone: () -> Unit = {}) {
        if (loadingMore) return
        loadingMore = true
        CenterThreadPool.run(Runnable {
            val batch = ArrayList<StoryApi.StoryCard>()
            var warning = ""
            var error: String? = null
            try {
                val result = StoryApi.loadFeed(batch)
                warning = result.warning
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                Logu.e("story", "拉流失败：" + error)
            }
            runOnUiThread {
                loadingMore = false
                if (destroyed) return@runOnUiThread
                if (error != null) {
                    setLoading(false)
                    showStatus("加载失败：" + error + "\n\n点按重试", autoHide = false)
                } else {
                    storyList.addAll(batch)
                    if (warning.isNotEmpty()) showStatus(warning, autoHide = true)
                    if (batch.isEmpty()) showHint("没有更多了")
                }
                onDone()
            }
        })
    }

    private fun switchRelative(delta: Int) {
        //加载中也可以滑动切换：旧请求回来时按 currentIndex 丢弃，不会串台
        val next = currentIndex + delta
        if (next < 0) {
            showHint("已经是第一个了")
            return
        }
        if (next >= storyList.size) {
            showHint("正在加载更多…")
            loadMore {
                if (!destroyed) {
                    if (currentIndex + 1 < storyList.size) switchTo(currentIndex + 1)
                    else showHint("没有更多了")
                }
            }
            return
        }
        switchTo(next)
    }

    /** 切到指定索引的短视频（加载流地址并播放） */
    private fun switchTo(index: Int) {
        if (destroyed) return
        if (index < 0 || index >= storyList.size) return
        if (index == currentIndex && firstFrameShown) return

        val card = storyList[index]
        currentIndex = index
        currentCard = card
        currentInfo = null
        switching = true
        triedHtml5 = false
        failCount = 0
        firstFrameShown = false

        hideStatus()
        titleView?.text = card.title ?: ""
        upnameView?.text = if (card.upName.isNullOrEmpty()) "" else "UP：" + card.upName
        metaView?.text = buildMeta(card)
        progressView?.progress = 0
        liked = false
        likeBtn?.setImageResource(R.drawable.icon_like_0)
        playBtn?.setImageResource(R.drawable.btn_player_pause)
        setLoading(true, "加载中…")
        // 切换时先把标题露出来（旧版一进来就自动隐藏，用户以为"信息没加载"）
        setControlsVisible(true)
        scheduleHideControls(4000)

        // 流剩余不多时提前补拉（保持无限刷的顺滑）
        if (storyList.size - index <= 2) loadMore()

        val cached = synchronized(playCache) { playCache[index] }
        if (cached != null && cached.url.isNotEmpty()) {
            startPlayback(cached)
            return
        }
        resolveAndPlay(card, forceHtml5 = false)
    }

    /** 后台取流 → 主线程开始播放 */
    private fun resolveAndPlay(card: StoryApi.StoryCard, forceHtml5: Boolean) {
        // 记录目标索引：取流期间用户可能已经滑到别的视频，回来时要丢弃过期结果
        val targetIndex = currentIndex
        CenterThreadPool.run(Runnable {
            var info: StoryApi.StoryPlayInfo? = null
            var error = ""
            try {
                if (card.cid <= 0) {
                    val vi = com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(card.aid)
                    val cid = vi?.cids?.firstOrNull() ?: 0L
                    if (cid > 0) card.cid = cid
                }
                if (card.cid <= 0) throw Exception("取不到 cid（aid=" + card.aid + "）")
                info = StoryApi.resolvePlay(card.aid, card.cid, QN, forceHtml5)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                Logu.e("story", "取流失败 aid=" + card.aid + "：" + error)
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (targetIndex != currentIndex) return@runOnUiThread
                if (info != null && info.url.isNotEmpty()) {
                    synchronized(playCache) { playCache[targetIndex] = info }
                    if (currentIndex + 1 < storyList.size) preloadNext(currentIndex + 1)
                    startPlayback(info)
                } else {
                    onPlayFailed(error)
                }
            }
        })
    }

    /** 后台预取下一支视频的直链（不播放） */
    private fun preloadNext(nextIndex: Int) {
        if (nextIndex < 0 || nextIndex >= storyList.size) return
        if (playCache.containsKey(nextIndex)) return
        val card = storyList[nextIndex]
        CenterThreadPool.run(Runnable {
            try {
                if (card.cid <= 0) {
                    val vi = com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(card.aid)
                    val cid = vi?.cids?.firstOrNull() ?: 0L
                    if (cid > 0) card.cid = cid
                }
                if (card.cid <= 0) return@Runnable
                val info = StoryApi.resolvePlay(card.aid, card.cid, QN, false)
                if (info.url.isNotEmpty()) {
                    synchronized(playCache) {
                        if (playCache.size > 4) playCache.clear()
                        playCache[nextIndex] = info
                    }
                }
            } catch (e: Exception) {
                Logu.d("story", "预取下一支失败：" + e.message)
            }
        })
    }

    private fun buildMeta(card: StoryApi.StoryCard): String {
        val view = card.view ?: ""
        val dur = formatDuration(card.durationSec)
        return when {
            view.isNotEmpty() && dur.isNotEmpty() -> view + " · " + dur
            view.isNotEmpty() -> view
            else -> dur
        }
    }

    private fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    // ==================== 播放 ====================

    private fun startPlayback(info: StoryApi.StoryPlayInfo) {
        currentInfo = info
        if (info.source == "html5") triedHtml5 = true

        val headers = HashMap<String, String>()
        headers["Referer"] = "https://www.bilibili.com/"
        headers["Cookie"] = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        headers["User-Agent"] = NetWorkUtil.USER_AGENT_WEB
        currentHeaders = headers

        if (info.note.isNotEmpty()) showStatus(info.note, autoHide = true)
        setLoading(true, "缓冲中…")

        // 跟随播放器设置里"使用硬解"的开关（默认开）
        val hardware = SharedPreferencesUtil.getBoolean("player_codec", true)
        controller?.play(info.url, headers, 0L, hardware)
    }

    /** 播放失败：先换备用线路，再跳下一个 */
    private fun onPlayFailed(error: String) {
        if (destroyed) return
        switching = false
        setLoading(false)
        if (!triedHtml5) {
            triedHtml5 = true
            showStatus("播放失败：" + error + "\n\n正在尝试备用线路…", autoHide = false)
            val card = currentCard ?: return
            resolveAndPlay(card, forceHtml5 = true)
            return
        }
        failCount++
        showStatus("播放失败：" + error + "\n\n点按重试，或上滑换一个", autoHide = false)
        val failedIndex = currentIndex
        if (failCount < 3) {
            findViewById<View>(R.id.story_root).postDelayed({
                //用户可能已经滑走了，别把新视频也跳过去
                if (!destroyed && currentIndex == failedIndex) switchRelative(1)
            }, 1500)
        }
    }

    private fun retryCurrent() {
        val card = currentCard ?: return
        triedHtml5 = false
        failCount = 0
        firstFrameShown = false
        switching = false
        playCache.remove(currentIndex)
        setLoading(true, "重新加载…")
        resolveAndPlay(card, forceHtml5 = false)
    }

    private fun togglePlay() {
        val c = controller ?: return
        try {
            if (c.isPlaying()) {
                c.pause()
                playBtn?.setImageResource(R.drawable.btn_player_play)
            } else {
                c.resume()
                playBtn?.setImageResource(R.drawable.btn_player_pause)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val playerListener = object : StoryPlaybackController.Listener {
        override fun onPlayerPrepared(durationMs: Long) {
            if (destroyed) return
            switching = false
        }

        override fun onPlayerProgress(positionMs: Long, durationMs: Long) {
            if (destroyed) return
            if (durationMs > 0) {
                val v = (positionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
                progressView?.progress = v
            }
        }

        override fun onPlayerBuffering(buffering: Boolean) {
            if (destroyed) return
            if (buffering) setLoading(true, "缓冲中…")
            else if (firstFrameShown) setLoading(false)
        }

        override fun onPlayerFirstFrame() {
            if (destroyed) return
            firstFrameShown = true
            failCount = 0
            switching = false
            setLoading(false)
            playBtn?.setImageResource(R.drawable.btn_player_pause)
            setControlsVisible(true)
            scheduleHideControls(3000)
        }

        override fun onPlayerCompletion() {
            if (!destroyed) switchRelative(1)
        }

        override fun onPlayerError(what: Int, extra: Int) {
            if (!destroyed) onPlayFailed("播放器错误（$what/$extra）")
        }

        override fun onPlayerCodecChanged(hardware: Boolean) {
            if (destroyed) return
            showStatus(if (hardware) "已切换硬解" else "硬解失败，正在改用软解…", autoHide = true)
            setLoading(true, "缓冲中…")
        }

        override fun onPlayerVideoSize(width: Int, height: Int) {
            //按视频真实宽高设置画面区域：
            //- 圆屏：缩进圆的内接矩形（与主播放器 changeVideoSize 一致），四角不会被圆屏裁掉；
            //- 方屏/竖屏：等比缩放居中，避免 ijk 把画面拉变形。
            if (destroyed || width <= 0 || height <= 0) return
            fitVideo(width, height, SharedPreferencesUtil.getBoolean("player_ui_round", false))
        }
    }

    // ==================== 交互入口 ====================

    private fun likeCurrent() {
        val card = currentCard ?: return
        likeBtn?.isEnabled = false
        val willLike = !liked
        CenterThreadPool.run(Runnable {
            try {
                val result = com.RobinNotBad.BiliClient.api.LikeCoinFavApi.like(card.aid, if (willLike) 1 else 0)
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    likeBtn?.isEnabled = true
                    if (result == 0) {
                        liked = willLike
                        likeBtn?.setImageResource(if (willLike) R.drawable.icon_like_1 else R.drawable.icon_like_0)
                        showHint(if (willLike) "点赞成功" else "已取消点赞")
                    } else {
                        showHint("点赞失败，错误码：$result")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    likeBtn?.isEnabled = true
                    showHint("点赞失败")
                }
            }
        })
    }

    /** 打开当前视频的详情页（评论/弹幕/收藏等完整功能在原详情页） */
    private fun openDetail() {
        val card = currentCard ?: return
        val intent = Intent(this, com.RobinNotBad.BiliClient.activity.video.info.VideoInfoActivity::class.java)
        intent.putExtra("aid", card.aid)
        if (!card.bvid.isNullOrEmpty()) intent.putExtra("bvid", card.bvid)
        startActivity(intent)
    }

    companion object {
        /** 短视频固定 360P：手表屏幕小、省流量（需要更高可自行调大） */
        private const val QN = 16
    }
}
