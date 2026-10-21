package com.RobinNotBad.BiliClient.activity.video.story

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.api.StoryApi
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 短视频播放器（shortvideo 分支，专用竖屏播放页）。
 *
 * 与原播放器（PlayerActivity）完全独立：
 * - 数据源是短视频专用接口 StoryApi（/x/v2/feed/index/story，抖音式无限流），
 *   不是"推荐下一个/相关视频"；
 * - 上滑 = 下一个、下滑 = 上一个（快扫手势）；
 * - 看完自动连播下一个；流不够自动补拉；
 * - 点赞 / 查看详情（跳原视频详情页）入口在右侧按钮列。
 *
 * 原播放器不承担任何短视频逻辑（v5.0.1-alpha 里的 related 流实现已按反馈删除）。
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class StoryPlayerActivity : BaseActivity() {

    private var videoLayout: android.widget.FrameLayout? = null
    private var titleView: TextView? = null
    private var upnameView: TextView? = null
    private var loadingView: View? = null
    private var loadingText: TextView? = null
    private var hintView: TextView? = null
    private var playBtn: ImageView? = null
    private var likeBtn: ImageView? = null
    private var progressView: android.widget.ProgressBar? = null
    private var metaView: TextView? = null

    private var ijkPlayer: IjkMediaPlayer? = null
    private var textureView: TextureView? = null

    /** 进度刷新定时器（500ms 一档，轻量） */
    private var progressTimer: java.util.Timer? = null

    /** 控制层（顶栏+右侧按钮）自动隐藏 */
    private val controlsHideRunnable = Runnable { setControlsVisible(false) }
    private var controlsVisible = true

    /** 下一支视频的直链缓存（预加载用，切换秒开） */
    @Volatile
    private var nextUrl: String? = null

    /** 短视频流（StoryApi 专用接口，无限刷） */
    private val storyList = ArrayList<StoryApi.StoryCard>()

    /** 当前播放索引 */
    private var currentIndex = -1

    /** 正在切换/加载（防连滑重入） */
    @Volatile
    private var switching = false

    private var destroyed = false
    private var prepared = false
    private var playing = false

    /** 手势：快扫判定（上滑/下滑切视频），单击暂停/继续 */
    private var gestureDetector: GestureDetector? = null

    // ---------------- 生命周期 ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_player)

        videoLayout = findViewById(R.id.story_video_layout)
        titleView = findViewById(R.id.story_title)
        upnameView = findViewById(R.id.story_upname)
        loadingView = findViewById(R.id.story_loading)
        loadingText = findViewById(R.id.story_loading_text)
        hintView = findViewById(R.id.story_hint)
        playBtn = findViewById(R.id.story_btn_play)
        likeBtn = findViewById(R.id.story_btn_like)
        progressView = findViewById(R.id.story_progress)
        metaView = findViewById(R.id.story_meta)

        findViewById<View>(R.id.story_back).setOnClickListener { finish() }
        playBtn!!.setOnClickListener { togglePlay() }
        likeBtn!!.setOnClickListener { likeCurrent() }
        findViewById<View>(R.id.story_btn_detail).setOnClickListener { openDetail() }

        initGesture()

        // 进入即拉第一刷
        loadMore {
            if (storyList.isNotEmpty() && !destroyed) {
                runOnUiThread { switchTo(0) }
            }
        }

        // 首次 4 秒后自动隐藏控制层（先让用户看清入口）
        findViewById<View>(R.id.story_top).postDelayed({ setControlsVisible(false) }, 4000)
    }

    override fun onDestroy() {
        destroyed = true
        progressTimer?.cancel()
        releasePlayer()
        super.onDestroy()
    }

    // ---------------- 手势 ----------------

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

        findViewById<View>(R.id.story_root).setOnTouchListener { v, event ->
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    /** 控制层显隐（3 秒无操作自动隐藏，播放时全屏沉浸） */
    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val top = findViewById<View>(R.id.story_top)
        val buttons = findViewById<View>(R.id.story_buttons)
        top.visibility = if (visible) View.VISIBLE else View.GONE
        buttons.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            top.removeCallbacks(controlsHideRunnable)
            top.postDelayed(controlsHideRunnable, 3000)
        } else {
            top.removeCallbacks(controlsHideRunnable)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // 任何交互都刷新控制层存活时间
        if (controlsVisible && !destroyed) {
            val top = findViewById<View>(R.id.story_top)
            top.removeCallbacks(controlsHideRunnable)
            top.postDelayed(controlsHideRunnable, 3000)
        }
    }

    // ---------------- 流管理 ----------------

    /** 拉一刷新短视频（追加到流尾部） */
    private fun loadMore(onDone: () -> Unit = {}) {
        CenterThreadPool.run(Runnable {
            try {
                val batch = ArrayList<StoryApi.StoryCard>()
                StoryApi.getStoryFeed(batch)
                runOnUiThread {
                    if (!destroyed) {
                        storyList.addAll(batch)
                        if (batch.isEmpty()) showHint("没有更多了")
                    }
                    onDone()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!destroyed) {
                        showHint("加载失败，上滑重试")
                        if (storyList.isEmpty()) setLoading(false)
                    }
                    onDone()
                }
            }
        })
    }

    private fun switchRelative(delta: Int) {
        if (switching) return
        val next = currentIndex + delta
        if (next < 0) {
            showHint("已经是第一个了")
            return
        }
        if (next >= storyList.size) {
            // 流耗尽：先补拉，再切
            showHint("正在加载更多…")
            loadMore {
                runOnUiThread {
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
        if (switching || destroyed) return
        if (index < 0 || index >= storyList.size) return
        val card = storyList[index]
        switching = true
        currentIndex = index

        titleView!!.text = card.title ?: ""
        upnameView!!.text = "UP：" + (card.upName ?: "")
        metaView!!.text = (card.view ?: "") + " · " + formatDuration(card.durationSec)
        // 切换视频时控制层短暂可见（让用户看到标题），随后自动隐藏
        setControlsVisible(true)
        setLoading(true, "加载中")
        likeBtn!!.setImageResource(R.drawable.icon_like_0)
        liked = false
        progressView!!.progress = 0
        nextUrl = null

        // 流剩余不多时提前补拉（保持无限刷的顺滑）
        if (storyList.size - index <= 2) loadMore()

        CenterThreadPool.run(Runnable {
            try {
                val pd = PlayerData()
                pd.aid = card.aid
                pd.cid = card.cid
                pd.title = card.title
                pd.qn = 16   // 竖屏短视频 360P 足够清晰且省流
                // cid 缺失时先取视频信息
                if (pd.cid <= 0) {
                    val info = com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(card.aid)
                    pd.cid = info?.cids?.firstOrNull() ?: 0L
                }
                PlayerApi.getVideo(pd, false)
                val url = pd.videoUrl ?: throw Exception("取流失败")

                // 预加载下一支的直链（后台顺手做，切换时秒开）
                preloadNext(index + 1)

                runOnUiThread {
                    if (destroyed) {
                        switching = false
                        return@runOnUiThread
                    }
                    playUrl(url)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    switching = false
                    if (!destroyed) {
                        setLoading(false)
                        showHint("加载失败：" + (e.message ?: ""))
                        // 失败自动跳下一个；连续失败 3 次就停下（避免接口整体故障时无限空转）
                        failCount++
                        if (currentIndex + 1 < storyList.size && failCount < 3) {
                            findViewById<View>(android.R.id.content).postDelayed({ switchTo(currentIndex + 1) }, 800)
                        } else if (failCount >= 3) {
                            showHint("连续加载失败，稍后再试")
                        }
                    }
                }
            }
        })
    }

    /** 连续失败计数（成功 prepared 后清零） */
    private var failCount = 0

    /** 后台预取下一支视频的直链（不播放，只缓存 url） */
    private fun preloadNext(nextIndex: Int) {
        if (nextIndex < 0 || nextIndex >= storyList.size) return
        val card = storyList[nextIndex]
        CenterThreadPool.run(Runnable {
            try {
                val pd = PlayerData()
                pd.aid = card.aid
                pd.cid = card.cid
                pd.title = card.title
                pd.qn = 16
                if (pd.cid <= 0) {
                    val info = com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(card.aid)
                    pd.cid = info?.cids?.firstOrNull() ?: 0L
                }
                PlayerApi.getVideo(pd, false)
                if (!pd.videoUrl.isNullOrEmpty()) nextUrl = pd.videoUrl
            } catch (e: Exception) {
                // 预加载失败无所谓，切换时再正常取流
            }
        })
    }

    private fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    // ---------------- 播放 ----------------

    private fun playUrl(url: String) {
        releasePlayer()
        prepared = false
        playing = false

        ijkPlayer = IjkMediaPlayer()
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)

        if (textureView == null) {
            textureView = TextureView(this)
            videoLayout!!.addView(textureView,
                    android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            textureView!!.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    ijkPlayer?.setSurface(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

        // 修复：TextureView 首帧 attach 前 surfaceTexture 为 null，
        // Surface(null) 会直接 NPE 崩溃（alpha3 "点开就崩/黑屏"的另一元凶）。
        // surface 还没就绪时先不设，等 onSurfaceTextureAvailable 回调里补设。
        val st = textureView!!.surfaceTexture
        if (st != null) ijkPlayer!!.setSurface(Surface(st))

        ijkPlayer!!.setOnPreparedListener { mp ->
            prepared = true
            playing = true
            switching = false
            failCount = 0
            setLoading(false)
            playBtn!!.setImageResource(R.drawable.btn_player_pause)
            try {
                val dur = mp.duration
                if (dur > 0) progressView!!.max = 1000
            } catch (ignored: Exception) {
            }
            mp.start()
            startProgressTimer()
        }

        ijkPlayer!!.setOnCompletionListener {
            // 看完自动下一个（抖音式）
            if (!destroyed) switchRelative(1)
        }

        ijkPlayer!!.setOnErrorListener { _, what, extra ->
            runOnUiThread {
                switching = false
                setLoading(false)
                showHint("播放出错（$what/$extra），切换下一个")
            }
            true
        }

        try {
            val headers = HashMap<String, String>()
            headers["Referer"] = "https://www.bilibili.com/"
            headers["Cookie"] = com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
                    .getString(com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.cookies, "") ?: ""
            headers["User-Agent"] = NetWorkUtil.USER_AGENT_WEB
            ijkPlayer!!.setDataSource(url, headers)
            ijkPlayer!!.prepareAsync()
        } catch (e: Exception) {
            switching = false
            setLoading(false)
            showHint("播放失败：" + (e.message ?: ""))
        }
    }

    private fun togglePlay() {
        val p = ijkPlayer ?: return
        try {
            if (playing) {
                p.pause()
                playing = false
                playBtn!!.setImageResource(R.drawable.btn_player_play)
            } else {
                p.start()
                playing = true
                playBtn!!.setImageResource(R.drawable.btn_player_pause)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releasePlayer() {
        stopProgressTimer()
        try { ijkPlayer?.stop() } catch (ignored: Exception) {}
        try { ijkPlayer?.release() } catch (ignored: Exception) {}
        ijkPlayer = null
        prepared = false
        playing = false
    }

    /** 进度条刷新（500ms 一档） */
    private fun startProgressTimer() {
        stopProgressTimer()
        progressTimer = java.util.Timer()
        progressTimer!!.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (destroyed) {
                    cancel()
                    return
                }
                val p = ijkPlayer ?: run { cancel(); return }
                try {
                    val cur = p.currentPosition
                    val dur = p.duration
                    if (dur > 0) {
                        val v = (cur * 1000L / dur).toInt()
                        runOnUiThread { if (!destroyed) progressView?.progress = v }
                    }
                } catch (e: Exception) {
                    // 播放器已释放等，安静退出
                    cancel()
                }
            }
        }, 200, 500)
    }

    private fun stopProgressTimer() {
        progressTimer?.cancel()
        progressTimer?.purge()
        progressTimer = null
    }

    // ---------------- 交互入口 ----------------

    /** 点赞当前视频（再次点击取消点赞） */
    private var liked = false

    private fun likeCurrent() {
        val card = storyList.getOrNull(currentIndex) ?: return
        likeBtn!!.isEnabled = false
        val willLike = !liked
        CenterThreadPool.run(Runnable {
            try {
                val result = com.RobinNotBad.BiliClient.api.LikeCoinFavApi.like(card.aid, if (willLike) 1 else 0)
                runOnUiThread {
                    likeBtn!!.isEnabled = true
                    if (result == 0) {
                        liked = willLike
                        likeBtn!!.setImageResource(if (willLike) R.drawable.icon_like_1 else R.drawable.icon_like_0)
                        showHint(if (willLike) "点赞成功" else "已取消点赞")
                    } else {
                        showHint("点赞失败，错误码：$result")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    likeBtn!!.isEnabled = true
                    showHint("点赞失败")
                }
            }
        })
    }

    /** 打开当前视频的详情页（评论/弹幕/收藏等完整功能在原详情页） */
    private fun openDetail() {
        val card = storyList.getOrNull(currentIndex) ?: return
        val intent = Intent(this, com.RobinNotBad.BiliClient.activity.video.info.VideoInfoActivity::class.java)
        intent.putExtra("aid", card.aid)
        if (!card.bvid.isNullOrEmpty()) intent.putExtra("bvid", card.bvid)
        startActivity(intent)
    }

    // ---------------- UI 辅助 ----------------

    private fun setLoading(show: Boolean, text: String = "加载中") {
        loadingView!!.visibility = if (show) View.VISIBLE else View.GONE
        if (show) loadingText!!.text = text
    }

    private fun showHint(text: String) {
        hintView!!.text = text
        hintView!!.visibility = View.VISIBLE
        hintView!!.removeCallbacks(hideHint)
        hintView!!.postDelayed(hideHint, 1800)
    }

    private val hideHint = Runnable { hintView?.visibility = View.GONE }
}
