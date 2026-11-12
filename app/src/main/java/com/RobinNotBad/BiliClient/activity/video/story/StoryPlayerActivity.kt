package com.RobinNotBad.BiliClient.activity.video.story

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.player.PlayerDanmakuController
import com.RobinNotBad.BiliClient.api.StoryApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.RobinNotBad.BiliClient.util.WifiPreloadManager

import master.flame.danmaku.controller.IDanmakuView

/**
 * 短视频播放器（shortvideo 分支，专用竖屏播放页）。
 *
 * v5.0.2-alpha2：
 * - 竖屏优先：画面默认按视频真实比例铺满屏幕高度（可在「画面比例」里切 填满/拉伸），
 *   不会再把竖屏视频压成一个小方块；
 * - 顶部去掉「页面标题」那行加粗白字（原来是返回按钮上的文字，会压住真正的视频标题），
 *   标题/UP 贴顶、播放量/时长贴底，把画面中间让出来；
 * - 补齐主播放器的常用功能：**长按倍速**、倍速菜单、清晰度切换、进度条拖动、
 *   单击显隐控制层、双击暂停、音量键、上下滑切换、自动连播、点赞/详情；
 * - 圆屏手表：顶栏/按钮/底部信息收进圆形安全区。
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class StoryPlayerActivity : BaseActivity() {

    private var videoLayout: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var topBar: LinearLayout? = null
    private var buttonsBar: GridLayout? = null
    private var titleView: TextView? = null
    private var upnameView: TextView? = null
    private var loadingView: View? = null
    private var loadingText: TextView? = null
    private var statusView: TextView? = null
    private var hintView: TextView? = null
    private var speedHintView: TextView? = null
    private var playBtn: ImageView? = null
    private var likeBtn: ImageView? = null
    private var seekBar: SeekBar? = null
    private var metaView: TextView? = null
    private var timeView: TextView? = null
    private var speedBtn: TextView? = null
    private var qualityBtn: TextView? = null
    private var danmakuBtn: ImageView? = null
    private var danmakuView: IDanmakuView? = null

    private var danmakuController: PlayerDanmakuController? = null

    /** 弹幕开关（设置项 player_danmaku，与主播放器共用） */
    private var danmakuEnabled = true

    private var controller: StoryPlaybackController? = null

    /** 短视频流（StoryApi 专用接口，失败时自动降级推荐流） */
    private val storyList = ArrayList<StoryApi.StoryCard>()

    /** 已解析过的直链缓存（索引 → 直链），切换时秒开 */
    private val playCache = HashMap<Int, StoryApi.StoryPlayInfo>()

    private var currentIndex = -1
    private var currentCard: StoryApi.StoryCard? = null
    private var currentHeaders: HashMap<String, String> = HashMap()
    private var currentInfo: StoryApi.StoryPlayInfo? = null

    private var switching = false
    private var loadingMore = false
    private var triedHtml5 = false
    private var firstFrameShown = false
    private var failCount = 0

    /** 当前已经为哪一支视频启动过弹幕拉取（aid=0 表示没有） */
    private var danmakuAid = 0L

    /** 用户主动暂停：切到别的页面再回来时不要自作主张继续播放 */
    private var userPaused = false

    private var destroyed = false
    private var liked = false

    private var controlsVisible = true
    private var statusAutoHide: Runnable? = null

    private var gestureDetector: GestureDetector? = null

    // ---------------- 播放参数（与主播放器对齐） ----------------

    /** 当前清晰度（6=最低 16=360P 32=480P 64=720P 80=1080P） */
    private var currentQn = 6

    /** 常速（倍速菜单设置的值，长按倍速结束后恢复到这个值） */
    private var normalSpeed = 1.0f

    /** 画面比例：0=适应屏幕 1=填满屏幕 2=拉伸 */
    private var fitMode = FIT_FIT

    private var videoW = 0
    private var videoH = 0

    /** 手动旋转角度（0/90/180/270） */
    private var userRotation = 0

    private var seeking = false

    /** 自动连播（长按播放按钮切换，默认开） */
    private var autoNext = true

    // 长按倍速
    private var longPressActive = false
    private var downX = 0f
    private var downY = 0f
    private val longPressRunnable = Runnable { startLongPressSpeed() }

    // ---------------- 缩放 / 平移（需求1：支持放大） ----------------

    /** 双指缩放检测器（只在视频区域内生效） */
    private var scaleDetector: ScaleGestureDetector? = null

    /** 用户缩放倍数，1.0 = 原始大小，上限 [MAX_SCALE] */
    private var videoScale = 1f

    /** 缩放后允许的平移量（像素）：0 = 不能移动（此时 1x 画面正好铺满） */
    private var maxPanX = 0f
    private var maxPanY = 0f

    /** 当前平移偏移（像素），已被 [maxPanX]/[maxPanY] 钳位 */
    private var panX = 0f
    private var panY = 0f

    /** 是否正在双指缩放/平移：为 true 时上下滑切视频要让位给平移 */
    private var scalingGesture = false

    /** 一次手势里是否发生过缩放或平移（用于压制随后的单击/双击） */
    private var gestureMoved = false

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_story_player)

        videoLayout = findViewById(R.id.story_video_layout)
        surfaceView = findViewById(R.id.story_surface)
        topBar = findViewById(R.id.story_top)
        buttonsBar = findViewById(R.id.story_buttons)
        titleView = findViewById(R.id.story_title)
        upnameView = findViewById(R.id.story_upname)
        loadingView = findViewById(R.id.story_loading)
        loadingText = findViewById(R.id.story_loading_text)
        statusView = findViewById(R.id.story_status)
        hintView = findViewById(R.id.story_hint)
        speedHintView = findViewById(R.id.story_speed_hint)
        playBtn = findViewById(R.id.story_btn_play)
        likeBtn = findViewById(R.id.story_btn_like)
        seekBar = findViewById(R.id.story_seek)
        metaView = findViewById(R.id.story_meta)
        timeView = findViewById(R.id.story_time)
        speedBtn = findViewById(R.id.story_btn_speed)
        qualityBtn = findViewById(R.id.story_btn_quality)
        danmakuBtn = findViewById(R.id.story_btn_danmaku)
        danmakuView = findViewById<View>(R.id.story_danmaku) as IDanmakuView

        findViewById<View>(R.id.story_back).setOnClickListener { finish() }
        playBtn!!.setOnClickListener { togglePlay() }
        //长按播放按钮 = 开关自动连播（原播放器的 auto_next）
        playBtn!!.setOnLongClickListener {
            autoNext = !autoNext
            SharedPreferencesUtil.putBoolean("story_auto_next", autoNext)
            showHint(if (autoNext) "自动连播：开" else "自动连播：关")
            true
        }
        likeBtn!!.setOnClickListener { likeCurrent() }
        findViewById<View>(R.id.story_btn_detail).setOnClickListener { openDetail() }
        speedBtn!!.setOnClickListener { showSpeedDialog() }
        danmakuBtn!!.setOnClickListener { toggleDanmaku() }
        qualityBtn!!.setOnClickListener { showQualityDialog() }
        // 长按清晰度按钮 = 画面比例 / 旋转（不额外占按钮位置）
        qualityBtn!!.setOnLongClickListener {
            showDisplayDialog()
            true
        }
        // v5.3.4（需求6 第四项）：长按标题 = 短视频设置弹层。
        // 为什么不新加一个按钮：圆屏右侧按钮列已经挤到必须改两列（见 layoutRoundButtons），
        // 再塞一个按钮只会让"点不准"更严重。长按标题是零占位的入口，
        // 且与「长按播放=自动连播」「长按清晰度=画面比例」的手势语言一致。
        titleView?.setOnLongClickListener {
            showStorySettingsDialog()
            true
        }

        statusView!!.setOnClickListener {
            hideStatus()
            if (storyList.isEmpty()) {
                setLoading(true, "重新加载…")
                loadMore { if (!destroyed && storyList.isNotEmpty()) switchTo(0) }
            } else {
                retryCurrent()
            }
        }

        initSeekBar()
        applyRoundInsets()
        initGesture()

        controller = StoryPlaybackController(surfaceView!!, playerListener)
        initDanmaku()

        autoNext = SharedPreferencesUtil.getBoolean("story_auto_next", true)
        // 默认最低清晰度：手表屏幕小，省流、起播快
        currentQn = SharedPreferencesUtil.getInt("story_qn", 6)
        qualityBtn!!.text = qualityLabel(currentQn)
        normalSpeed = SharedPreferencesUtil.getInt("story_speed_idx", 2).let { idx ->
            SPEED_VALUES.getOrElse(idx) { 1.0f }
        }
        speedBtn!!.text = speedLabel(normalSpeed)
        fitMode = SharedPreferencesUtil.getInt("story_fit_mode", FIT_FIT)

        setLoading(true, "加载中…")
        loadMore {
            if (!destroyed && storyList.isNotEmpty()) switchTo(0)
            else if (!destroyed) setLoading(false)
        }
    }

    // ==================== 弹幕 ====================

    /** 初始化弹幕控制器（复用主播放器的 PlayerDanmakuController） */
    private fun initDanmaku() {
        val view = danmakuView ?: return
        danmakuEnabled = SharedPreferencesUtil.getBoolean("pref_switch_danmaku", true)
        danmakuBtn?.alpha = if (danmakuEnabled) 1f else 0.35f
        danmakuController = PlayerDanmakuController(
            view,
            { try { controller?.currentPosition() ?: 0L } catch (e: Exception) { 0L } },
            { try { controller?.isPrepared() ?: false } catch (e: Exception) { false } },
            { destroyed }
        )
        danmakuController?.buildContext()
        if (!danmakuEnabled) danmakuController?.hideAndClear()
    }

    private fun toggleDanmaku() {
        danmakuEnabled = !danmakuEnabled
        SharedPreferencesUtil.putBoolean("pref_switch_danmaku", danmakuEnabled)
        danmakuBtn?.alpha = if (danmakuEnabled) 1f else 0.35f
        showHint(if (danmakuEnabled) "弹幕：开" else "弹幕：关")
        val dc = danmakuController ?: return
        if (!danmakuEnabled) {
            dc.hideAndClear()
        } else {
            dc.show()
            // 关掉期间可能已经切过视频：重新拉一次当前视频的弹幕
            danmakuAid = 0L
            currentCard?.let { startDanmakuFor(it) }
            if (controller?.isPrepared() == true) {
                try {
                    dc.start()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    /**
     * 为当前视频重新拉取弹幕（切换视频时调用）。
     *
     * 注意不要在这里 hideAndClear()：那会把弹幕层置于「隐藏」状态，
     * 之后虽然分段照常拉取，但整层永远不显示 —— v5.0.2-alpha3 短视频
     * 切集后没有弹幕就是这个原因。需要清屏时用 resetForNewVideo()（只清数据不隐藏）。
     */
    private fun startDanmakuFor(card: StoryApi.StoryCard) {
        val dc = danmakuController ?: return
        if (!danmakuEnabled) {
            dc.hideAndClear()
            return
        }
        dc.show()
        // cid 可能还没解析出来：等 prepare 完成后再补一次（见 onPlayerPrepared）
        if (card.cid <= 0) return
        // 同一支视频重复进入（例如旋转/清晰度切换后重进）不重复拉取
        if (danmakuAid == card.aid && dc.hasDanmaku) return
        danmakuAid = card.aid
        dc.oldApiUrl = "https://comment.bilibili.com/" + card.cid + ".xml"
        dc.cacheFile = java.io.File(cacheDir, "story_danmaku_" + card.cid + ".xml")
        // 短视频一般很短：时长未就绪时按 60 秒估，控制器会等播放器解析出真实时长
        dc.startOnline(card.aid, card.cid, { controller?.currentDuration() ?: 0L }) { missing ->
            if (missing > 0) Logu.d("story", "弹幕缺失分段：" + missing)
        }
        dc.setSpeed(if (longPressActive) LONG_PRESS_SPEED else normalSpeed)
    }

    /** 弹幕时间轴与播放状态同步 */
    private fun syncDanmakuPlaying(playing: Boolean) {
        val dc = danmakuController ?: return
        if (!danmakuEnabled) return
        if (playing) dc.resume() else dc.pause()
    }

    override fun onDestroy() {
        destroyed = true
        try {
            prefetchDir().listFiles()?.forEach { it.delete() }
        } catch (ignored: Exception) {
        }
        try {
            controller?.release()
        } catch (ignored: Exception) {
        }
        controller = null
        try {
            danmakuController?.release()
        } catch (ignored: Exception) {
        }
        danmakuController = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        try {
            controller?.pause()
        } catch (ignored: Exception) {
        }
        try {
            danmakuController?.pause()
        } catch (ignored: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        //用户主动暂停时，回到前台不要自动续播
        if (!userPaused) {
            try {
                controller?.resume()
            } catch (ignored: Exception) {
            }
            try {
                if (firstFrameShown) danmakuController?.resume()
            } catch (ignored: Exception) {
            }
        }
        if (currentCard != null && !firstFrameShown && !switching && currentInfo == null) {
            retryCurrent()
        }
    }

    // ==================== 表冠 / 滚轮切换 ====================

    private var lastRotaryAt = 0L

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!destroyed && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val now = System.currentTimeMillis()
            if (Math.abs(v) > 0.1f && now - lastRotaryAt > 450) {
                lastRotaryAt = now
                // 表冠向上 = 下一个，向下 = 上一个（与上滑/下滑手势一致）
                switchRelative(if (v > 0) 1 else -1)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // ==================== 音量键（与主播放器一致） ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                changeVolume(true)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                changeVolume(false)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun changeVolume(up: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (now + if (up) 1 else -1).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            showStatus("音量：" + (target * 100 / max) + "%", autoHide = true)
        } catch (e: Exception) {
            Logu.e("story", "音量调节失败：" + e.message)
        }
    }

    // ==================== 圆屏适配 ====================

    /**
     * 圆屏手表：把顶栏/右侧按钮/底部信息收进圆形安全区。
     * 视频画面本身保持全屏（短视频要的就是整屏竖屏观感）。
     */
    @SuppressLint("RtlHardcoded")
    private fun applyRoundInsets() {
        if (!SharedPreferencesUtil.getBoolean("player_ui_round", false)) return
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val side = (w * 0.17f).toInt()
        val topBottom = (h * 0.04f).toInt()

        topBar?.setPadding(side, topBottom, side, ToolsUtil.dp2px(1f))
        buttonsBar?.setPadding(0, 0, (w * 0.10f).toInt(), 0)
        metaView?.setPadding(side, 0, side, (h * 0.06f).toInt())
        hintView?.setPadding(side, 0, side, (h * 0.10f).toInt())
        statusView?.maxWidth = (w * 0.62f).toInt()
        speedHintView?.let { it.translationY = -h * 0.16f }
        timeView?.setPadding(0, 0, (w * 0.10f).toInt(), (h * 0.06f).toInt())

        //弹幕不要跑到圆角外
        val dv = danmakuView?.view
        if (dv != null) {
            dv.setPadding(0, (h * 0.10f).toInt(), 0, (h * 0.10f).toInt())
        }

        val p = seekBar?.layoutParams
        if (p is FrameLayout.LayoutParams) {
            p.leftMargin = (w * 0.12f).toInt()
            p.rightMargin = (w * 0.12f).toInt()
            seekBar?.layoutParams = p
        }

        // v5.3.4（需求6 第二项）：右侧按钮列按圆弦高度决定列数 —— 修 notes_story_proposal.md 的 O1
        layoutRoundButtons(w, h)
    }

    /**
     * 圆屏下按「弦高」决定右侧按钮列数（v5.3.4，需求6 第二项）。
     *
     * # 要解决的问题（notes_story_proposal.md 的 O1）
     * 单列时 6 个按钮（4 个 34dp 图标 + 2 个 24dp 文字小按钮 + 5×8dp 间距）总高约 **224dp**。
     * 而屏幕只有 466px/320dpi ≈ **233dp**。按钮列贴右（`layout_gravity=end|center_vertical`），
     * 在那个 x 位置上，圆的**可用弦高只有约 130~140dp** ——
     * 首尾按钮直接落到圆外，**看不见也点不到**。这是"圆屏适配"里最实际的 bug。
     *
     * # 为什么用「弦高」而不是拍一个 padding
     * 圆的几何是确定的：在圆心为 (w/2, h/2)、半径 r = min(w,h)/2 的圆里，
     * 距中心横向 |dx| 处的竖向可用高度 = 2 * √(r² − dx²)。
     * 按钮列右边缘在 x ≈ w − insetEnd，取列中心的 dx 算弦高，
     * 就能得到"这一列实际能占多高"。比手工试出来的魔法数字可靠，
     * 换设备（不同屏尺寸/密度）也自动成立。
     *
     * # 列数的选择
     * - 弦高装得下单列（≤ 弦高）→ 保持 1 列（非圆屏时的观感完全不变）；
     * - 装不下 → 改 2 列（宽度够、高度减半），并按实际行数重算行间距。
     *
     * 2 列已经能把 224dp 压到约 120dp，落进 130~140dp 的弦高内。
     * 3 列会挤到手指点不准（34dp 宽的按钮在 233dp 屏上已经不小，但 3 列会逼到 28dp 以下）。
     */
    private fun layoutRoundButtons(w: Int, h: Int) {
        val grid = buttonsBar as? android.widget.GridLayout ?: return
        if (w <= 0 || h <= 0) return
        val buttonCount = grid.childCount
        if (buttonCount <= 0) return

        val singleColumnHeight = estimateSingleColumnHeight(grid, buttonCount)
        val availableHeight = chordHeightAtColumn(w, h)
        // 装不下单列就改两列（弦高按"列中心"算，见 chordHeightAtColumn 的注释）
        val needColumns = if (availableHeight >= singleColumnHeight + dp(4f)) 1 else 2
        grid.columnCount = needColumns
        applyButtonMargins(grid, buttonCount, needColumns)

        val rows = (buttonCount + needColumns - 1) / needColumns
        Logu.d(
            "story-round",
            "弦高=" + availableHeight.toInt() + "px 单列需=" + singleColumnHeight +
                "px 列数=" + needColumns + " 行数=" + rows
        )
    }

    /** 屏幕密度换算（本类多处用到，抽出来避免每个函数内联一个局部 lambda） */
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 估算「按钮列排成一列时」的总高度。
     *
     * 优先用子视图**已测量**的高度（最准）；尚未 layout 时按设计值兜底
     * （前 4 个是 34dp 图标、后 2 个是 24dp 文字小按钮，与 activity_story_player.xml 一致）。
     * 间距按 8dp × (n-1) 计入。
     */
    private fun estimateSingleColumnHeight(grid: android.widget.GridLayout, buttonCount: Int): Int {
        var total = 0
        for (i in 0 until buttonCount) {
            val child = grid.getChildAt(i)
            total += if (child.height > 0) child.height else dp(if (i < 4) 34f else 24f)
        }
        return total + dp(8f) * (buttonCount - 1)
    }

    /**
     * 圆屏上「按钮列所在 x 位置」的可用竖向高度（弦高）。
     *
     * 几何：圆心 (w/2, h/2)，半径 r = min(w,h)/2。距圆心横向 dx 处的竖向可用高度 = 2·√(r² − dx²)。
     * 按钮列贴右，列中心 x = w − 右边距 − 半个按钮宽（17dp ≈ 34dp/2）。
     * dx 超出半径（整列都在圆外）时返回 0 —— 夹到 0 而不是让 `sqrt` 拿到负数。
     *
     * 为什么用几何而不是试出来的魔法数字：换设备/换密度都自动成立，
     * 而魔法数字只能对某一台机器正确。
     */
    private fun chordHeightAtColumn(w: Int, h: Int): Float {
        val radius = minOf(w, h) / 2f
        val insetEnd = w * 0.10f   // 与 applyRoundInsets 里 buttonsBar 的右侧内边距一致
        val columnCenterX = w - insetEnd - dp(17f)
        val dx = columnCenterX - w / 2f
        val chordSquared = radius * radius - dx * dx
        return if (chordSquared > 0f) 2f * kotlin.math.sqrt(chordSquared) else 0f
    }

    /**
     * 按列数重设按钮间距。
     *
     * GridLayout 的 `marginTop` **在第一行也生效**，所以不能靠 xml 里给每个按钮写 marginTop
     * （那样第一行会凭空多出一段空隙，把整列往下推）。这里统一重设：
     * 第一行不留上边距、其余行留 gap；两列时左列加右边距避免贴在一起。
     */
    private fun applyButtonMargins(grid: android.widget.GridLayout, buttonCount: Int, columns: Int) {
        val gapDp = if (columns > 1) 4f else 8f
        for (i in 0 until buttonCount) {
            val child = grid.getChildAt(i)
            val params = child.layoutParams as? android.widget.GridLayout.LayoutParams ?: continue
            params.topMargin = if (i < columns) 0 else dp(gapDp)
            params.rightMargin = if (columns > 1 && i % columns == 0) dp(4f) else 0
            child.layoutParams = params
        }
    }

    // ==================== 画面比例 ====================

    private fun applyVideoLayout() {
        if (videoW <= 0 || videoH <= 0) return
        val container = videoLayout ?: return
        var cw = container.width
        var ch = container.height
        if (cw <= 0 || ch <= 0) {
            val dm = resources.displayMetrics
            cw = dm.widthPixels
            ch = dm.heightPixels
        }
        var vw = videoW.toFloat()
        var vh = videoH.toFloat()
        if (userRotation == 90 || userRotation == 270) {
            val t = vw
            vw = vh
            vh = t
        }
        val lp = surfaceView?.layoutParams as? FrameLayout.LayoutParams ?: return
        when (fitMode) {
            FIT_FILL -> {
                //填满屏幕（可能裁掉一部分），短视频最常用的观感
                val scale = Math.max(cw / vw, ch / vh)
                lp.width = (vw * scale).toInt()
                lp.height = (vh * scale).toInt()
            }
            FIT_STRETCH -> {
                lp.width = cw
                lp.height = ch
            }
            else -> {
                //适应屏幕：完整显示，多余部分留黑边（竖屏视频 = 满高 + 左右黑边）
                val scale = Math.min(cw / vw, ch / vh)
                lp.width = (vw * scale).toInt()
                lp.height = (vh * scale).toInt()
            }
        }
        lp.gravity = Gravity.CENTER
        surfaceView?.layoutParams = lp
        surfaceView?.rotation = userRotation.toFloat()
    }

    /**
     * 短视频设置弹层（v5.3.4，需求6 第四项）。
     *
     * # 为什么要有这一层（而不是只靠设置页）
     * 「API 端点可选择」是**用户在这个页面里调、也在这个页面里看到效果**的设置。
     * 如果只能去「设置 → 实验室」改，用户切一次端点要退出播放页、翻三层设置、再回来 ——
     * 而他其实只是想试试"换成 APP 推荐流内容是不是更好看"。
     * 页内弹层让这件事变成两次点击。
     *
     * # 为什么用 AlertDialog + 按钮列表而不是 setSingleChoiceItems
     * 圆屏上单选行太薄容易点错（这是需求9 的同一教训，见
     * FavoriteBatchDownloadActivity.showQualityChoice 的注释）。
     * 而且这里要同时放「端点」和「预加载开关」两类控件，单选行承载不了。
     *
     * # 内容
     * 1. **端点选择**：自动 / 短视频接口 / APP 推荐流 / 网页推荐流（对应 [StoryApi.endpointIds]）；
     * 2. **WiFi 预加载开关**：复用 WifiPreloadManager.PREF_WIFI_PRELOAD（与主播放器同一开关）。
     */
    private fun showStorySettingsDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(dp(14), dp(4), dp(14), dp(8))

        // ---- 端点 ----
        val endpointTitle = TextView(this)
        endpointTitle.text = "信息流端点"
        endpointTitle.textSize = 13f
        endpointTitle.alpha = 0.85f
        container.addView(endpointTitle)

        val currentEndpoint = StoryApi.endpoint()
        for (id in StoryApi.endpointIds) {
            val button = com.google.android.material.button.MaterialButton(this)
            val isCurrent = id == currentEndpoint
            button.text = (if (isCurrent) "✓ " else "") + StoryApi.endpointName(id)
            button.textSize = 13f
            button.isAllCaps = false
            button.minHeight = dp(44)
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(5)
            button.layoutParams = params
            button.setOnClickListener {
                if (id == currentEndpoint) {
                    dialogRef?.dismiss()
                    return@setOnClickListener
                }
                SharedPreferencesUtil.putInt(StoryApi.PREF_STORY_ENDPOINT, id)
                // 换端点 = 换内容源，必须清掉已加载的列表与直链缓存，
                // 否则用户会在旧内容上继续划，看起来像"换了没用"。
                storyList.clear()
                synchronized(playCache) { playCache.clear() }
                currentIndex = -1
                currentCard = null
                dialogRef?.dismiss()
                showHint("已切换到「" + StoryApi.endpointName(id) + "」")
                setLoading(true, "重新加载…")
                loadMore { if (!destroyed && storyList.isNotEmpty()) switchTo(0) }
            }
            container.addView(button)
        }

        // ---- WiFi 预加载 ----
        val preloadSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this)
        preloadSwitch.text = "WiFi 下预加载下一条"
        preloadSwitch.textSize = 13f
        preloadSwitch.isChecked = WifiPreloadManager.isEnabled()
        val switchParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        switchParams.topMargin = dp(10)
        preloadSwitch.layoutParams = switchParams
        preloadSwitch.setOnCheckedChangeListener { _, isChecked ->
            SharedPreferencesUtil.putBoolean(WifiPreloadManager.PREF_WIFI_PRELOAD, isChecked)
            // 打开时立刻预取当前的下一条，用户马上就能感受到效果（不用等下一轮播放）
            if (isChecked) {
                if (currentIndex + 1 < storyList.size) preloadNext(currentIndex + 1)
                showHint("已开启 WiFi 预加载")
            } else {
                showHint("已关闭 WiFi 预加载")
            }
        }
        container.addView(preloadSwitch)

        // 说明：告诉用户预加载只在 WiFi 生效，避免他以为"开了但没效果"
        val preloadDesc = TextView(this)
        preloadDesc.text = "只在这个 WiFi 下预取；移动数据下不预取。与「设置 → 实验室」里的同名开关是同一个。"
        preloadDesc.textSize = 10f
        preloadDesc.alpha = 0.6f
        preloadDesc.setPadding(0, dp(2), 0, 0)
        container.addView(preloadDesc)

        // 圆屏/小屏可滚动（档位多 + 开关 + 说明，固定高度必然裁切）
        val scroller = com.RobinNotBad.BiliClient.ui.widget.RotaryScrollView(this)
        scroller.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        scroller.addView(container)

        dialogRef = AlertDialog.Builder(this)
            .setTitle("短视频设置")
            .setView(scroller)
            .setNegativeButton("关闭", null)
            .create()
        dialogRef?.show()
    }

    private fun showDisplayDialog() {
        val modes = arrayOf("适应屏幕（完整显示）", "填满屏幕（可能裁切）", "拉伸铺满")
        AlertDialog.Builder(this)
            .setTitle("画面")
            .setSingleChoiceItems(modes, fitMode) { dialog, which ->
                fitMode = which
                SharedPreferencesUtil.putInt("story_fit_mode", which)
                applyVideoLayout()
                dialog.dismiss()
            }
            .setNeutralButton("旋转 90°") { _, _ ->
                userRotation = (userRotation + 90) % 360
                applyVideoLayout()
                showStatus("画面旋转 " + userRotation + "°", autoHide = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 倍速 ====================

    private fun showSpeedDialog() {
        val labels = SPEED_LABELS
        val checked = SPEED_VALUES.indexOfFirst { Math.abs(it - normalSpeed) < 0.01f }
        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                normalSpeed = SPEED_VALUES[which]
                SharedPreferencesUtil.putInt("story_speed_idx", which)
                speedBtn!!.text = SPEED_LABELS[which]
                if (!longPressActive) {
                    controller?.setSpeed(normalSpeed)
                    danmakuController?.setSpeed(normalSpeed)
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startLongPressSpeed() {
        if (destroyed || longPressActive) return
        if (!SharedPreferencesUtil.getBoolean("player_longclick", true)) return
        val c = controller ?: return
        if (!c.isPlaying()) return
        longPressActive = true
        c.setSpeed(LONG_PRESS_SPEED)
        danmakuController?.setSpeed(LONG_PRESS_SPEED)
        speedHintView?.text = "x " + LONG_PRESS_SPEED
        speedHintView?.visibility = View.VISIBLE
        hideControls()
    }

    private fun endLongPressSpeed() {
        if (!longPressActive) return
        longPressActive = false
        speedHintView?.visibility = View.GONE
        controller?.setSpeed(normalSpeed)
        danmakuController?.setSpeed(normalSpeed)
    }

    // ==================== 清晰度 ====================

    private fun showQualityDialog() {
        val labels = QUALITY_LABELS
        val checked = QUALITY_VALUES.indexOfFirst { it == currentQn }
        AlertDialog.Builder(this)
            .setTitle("清晰度")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val qn = QUALITY_VALUES[which]
                if (qn != currentQn) switchQuality(qn)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun switchQuality(qn: Int) {
        val card = currentCard ?: return
        currentQn = qn
        SharedPreferencesUtil.putInt("story_qn", qn)
        qualityBtn!!.text = qualityLabel(qn)

        val resume = controller?.currentPosition() ?: 0L
        val wasPaused = userPaused
        playCache.remove(currentIndex)
        localFileFor(card).delete()
        switching = true
        firstFrameShown = false
        setLoading(true, "切换清晰度…")

        CenterThreadPool.run(Runnable {
            var info: StoryApi.StoryPlayInfo? = null
            var error = ""
            try {
                if (card.cid <= 0) throw Exception("缺少 cid")
                info = StoryApi.resolvePlay(card.aid, card.cid, qn)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (info != null && info.url.isNotEmpty()) {
                    synchronized(playCache) { playCache[currentIndex] = info }
                    startPlayback(info, resume)
                    // 用户本来是暂停的：切清晰度后保持暂停，不要自动播起来
                    if (wasPaused) {
                        controller?.pause()
                        userPaused = true
                        playBtn?.setImageResource(R.drawable.btn_player_play)
                    }
                } else {
                    onPlayFailed(error)
                }
            }
        })
    }

    private fun qualityLabel(qn: Int): String {
        if (qn <= 6) return "最低"
        val idx = QUALITY_VALUES.indexOfFirst { it == qn }
        return if (idx >= 0) QUALITY_LABELS[idx] else "360P"
    }

    private fun speedLabel(speed: Float): String {
        val idx = SPEED_VALUES.indexOfFirst { Math.abs(it - speed) < 0.01f }
        return if (idx >= 0) SPEED_LABELS[idx] else String.format("%.1fx", speed)
    }

    // ==================== 进度条 ====================

    private fun initSeekBar() {
        seekBar!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = controller?.currentDuration() ?: 0L
                    if (dur > 0) timeView?.text = timeText(dur * progress / 1000, dur)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
                topBar?.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val dur = controller?.currentDuration() ?: 0L
                if (dur > 0) {
                    val target = dur * (sb?.progress ?: 0) / 1000
                    controller?.seekTo(target)
                    danmakuController?.seekTo(target)
                    timeView?.text = timeText(target, dur)
                }
                scheduleHideControls(2500)
            }
        })
    }

    private fun timeText(posMs: Long, durMs: Long): String {
        return formatTime(posMs) + " / " + formatTime(durMs)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return String.format("%02d:%02d", m, s)
    }

    // ==================== 状态显示 ====================

    private val showLoadingRunnable = Runnable {
        if (!destroyed) {
            loadingView?.visibility = View.VISIBLE
            loadingText?.text = loadingTextStr
        }
    }
    private var loadingTextStr = "加载中"

    /**
     * @param delayMs >0 时延迟显示（缓冲这种一闪而过的状态不要立刻弹遮罩，避免闪烁）
     */
    private fun setLoading(show: Boolean, text: String = "加载中", delayMs: Long = 0L) {
        val v = loadingView ?: return
        v.removeCallbacks(showLoadingRunnable)
        loadingTextStr = text
        if (!show) {
            v.visibility = View.GONE
            return
        }
        if (delayMs > 0) v.postDelayed(showLoadingRunnable, delayMs) else showLoadingRunnable.run()
    }

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
            v.postDelayed(r, 3000)
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

    /**
     * 手势总表（需求1 之后，优先级从上到下）：
     *
     * 1. **双指缩放 / 缩放后平移**：缩放态下接管一切，此时**不做**上下滑切视频；
     * 2. **双击**：先看是否处于缩放态 —— 缩放态 → 还原到 1x（平移归零）；
     *    非缩放态 → 暂停/继续（= 旧行为）。
     *    ⚠️ 与主播放器 `PlayerActivity.clickUI()` 的取舍**相反**：那边是「缩放还原
     *    交给手势本身，双击无条件暂停」，因为主播放器有独立的缩放开关和更大的屏幕。
     *    短视频在手表上双击的第一诉求是「把放大的画面收回来」，且缩放态下暂停
     *    与还原叠在一起时用户无法区分自己触发了哪个，所以这里还原优先。
     *    缩放态下想暂停：先双击还原（一次），再双击暂停（第二次）。
     * 3. **单击**：显隐控制层（缩放态同样生效；缩放/平移过的手势不触发）；
     * 4. **长按**：2x 倍速（缩放态下禁用，避免与平移冲突）；
     * 5. **上下滑**：切上一个/下一个（缩放态下禁用，改为平移）。
     */
    private fun initGesture() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (destroyed) return false
                scalingGesture = true
                //缩放开始就把长按倍速与单击候选都取消掉
                findViewById<View>(R.id.story_root).removeCallbacks(longPressRunnable)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (destroyed) return false
                val target = (videoScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (Math.abs(target - videoScale) < 0.001f) return true
                videoScale = target
                gestureMoved = true
                applyScaleTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scalingGesture = false
                //松手后如果缩回到 1x 附近就吸附回正，避免停在 1.02x 这种「看起来没放大但滑不动」的状态
                if (videoScale < 1.05f) {
                    videoScale = MIN_SCALE
                    panX = 0f
                    panY = 0f
                    applyScaleTransform()
                }
                if (videoScale > 1f) showHint("已放大 " + String.format(java.util.Locale.US, "%.1f", videoScale) + "x，双击还原")
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // compileSdk 34：onFling 的 e1（起始按下事件）在 API 34 里标成了 @Nullable，
            // Kotlin 覆写签名必须跟着改成可空，否则报 "overrides nothing"
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val down = e1 ?: return false
                //缩放态：上下滑改为平移画面，不切视频（见 initGesture 的优先级说明）
                if (isZoomed()) return false
                val dy = e2.y - down.y
                val dx = e2.x - down.x
                if (Math.abs(dy) > 90f && Math.abs(dy) > Math.abs(dx) * 2f) {
                    if (dy < 0) switchRelative(1) else switchRelative(-1)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                //缩放/平移过的手势不当作单击
                if (gestureMoved) return false
                setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                //缩放态：双击 = 还原（优先于暂停，理由见 initGesture 注释）
                if (isZoomed()) {
                    resetZoom(animated = true)
                    showHint("已还原")
                    return true
                }
                togglePlay()
                return true
            }
        })

        val root = findViewById<View>(R.id.story_root)
        root.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    gestureMoved = false
                    //缩放态下不启动长按倍速：单指拖动此时是平移
                    if (!isZoomed()) {
                        root.removeCallbacks(longPressRunnable)
                        root.postDelayed(longPressRunnable, 500)
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    //第二根手指落下 = 进入缩放，取消长按
                    root.removeCallbacks(longPressRunnable)
                    endLongPressSpeed()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.x - downX) > ToolsUtil.dp2px(12f) ||
                            Math.abs(event.y - downY) > ToolsUtil.dp2px(12f)) {
                        root.removeCallbacks(longPressRunnable)
                    }
                    //缩放态下的单指拖动 = 平移
                    if (isZoomed() && !scalingGesture && event.pointerCount == 1) {
                        panBy(event.x - downX, event.y - downY)
                        downX = event.x
                        downY = event.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    root.removeCallbacks(longPressRunnable)
                    endLongPressSpeed()
                    //平移过就把这次手势标记成「非点击」，避免收/开控制层
                    if (isZoomed() && (Math.abs(panX) > 1f || Math.abs(panY) > 1f)) {
                        gestureMoved = true
                    }
                }
            }
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    /** 当前是否处于放大状态 */
    private fun isZoomed(): Boolean = videoScale > 1.001f

    /**
     * 应用缩放与平移。
     *
     * 用视图变换（scaleX/scaleY/translationX/translationY）而不是改 LayoutParams：
     * 改尺寸会触发 Surface 的 surfaceChanged，ijkplayer 要重新配置解码器，
     * 连续缩放会卡顿甚至黑屏。SurfaceView 自 API 21 起跟随父容器变换。
     * minSdk 29，安全。
     */
    private fun applyScaleTransform() {
        val container = videoLayout ?: return
        //可平移范围 = 放大后超出容器的部分的一半（因为缩放以中心为基准）
        maxPanX = container.width * (videoScale - 1f) / 2f
        maxPanY = container.height * (videoScale - 1f) / 2f
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        container.scaleX = videoScale
        container.scaleY = videoScale
        container.translationX = panX
        container.translationY = panY
    }

    /** 相对平移（dx/dy 是手指位移，画面要跟手所以取反） */
    private fun panBy(dx: Float, dy: Float) {
        if (maxPanX <= 0f && maxPanY <= 0f) return
        panX = (panX - dx).coerceIn(-maxPanX, maxPanX)
        panY = (panY - dy).coerceIn(-maxPanY, maxPanY)
        videoLayout?.translationX = panX
        videoLayout?.translationY = panY
    }

    /** 还原到 1x（[animated] 时用轻量视图动画过渡） */
    private fun resetZoom(animated: Boolean) {
        val container = videoLayout ?: return
        videoScale = MIN_SCALE
        panX = 0f
        panY = 0f
        maxPanX = 0f
        maxPanY = 0f
        if (!animated) {
            container.scaleX = 1f
            container.scaleY = 1f
            container.translationX = 0f
            container.translationY = 0f
            return
        }
        container.animate()
            .scaleX(1f).scaleY(1f)
            .translationX(0f).translationY(0f)
            .setDuration(ZOOM_ANIM_MS)
            .start()
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        topBar?.visibility = if (visible) View.VISIBLE else View.GONE
        buttonsBar?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) scheduleHideControls(3000)
    }

    private fun hideControls() {
        controlsVisible = false
        topBar?.visibility = View.GONE
        buttonsBar?.visibility = View.GONE
    }

    private fun scheduleHideControls(delay: Long) {
        val v = topBar ?: return
        v.removeCallbacks(hideControlsRunnable)
        v.postDelayed(hideControlsRunnable, delay)
    }

    private val hideControlsRunnable = Runnable {
        if (destroyed) return@Runnable
        // 正在拖动进度条时不要把控制层收起来（否则拖到一半按钮/进度条消失）
        if (seeking) {
            scheduleHideControls(1500)
            return@Runnable
        }
        // 暂停状态保留控制层（和主播放器一致），方便直接继续播放/拖进度
        if (userPaused) return@Runnable
        setControlsVisible(false)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (controlsVisible && !destroyed) scheduleHideControls(3000)
    }

    // ==================== 流管理 ====================

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
                    // 去重：接口每次返回的批次之间会有重复稿件，直接 addAll 会造成
                    // 「上滑好几次都是同一个视频」。按 aid 过滤后再入库。
                    val known = HashSet<Long>(storyList.size * 2)
                    for (c in storyList) known.add(c.aid)
                    var added = 0
                    for (c in batch) {
                        if (c.aid <= 0 || !known.add(c.aid)) continue
                        storyList.add(c)
                        added++
                    }
                    if (warning.isNotEmpty()) showStatus(warning, autoHide = true)
                    if (added == 0) showHint("没有更多了")
                }
                onDone()
            }
        })
    }

    /** 上一次切视频的时间：手势连发时防抖，避免一次快速连滑触发多次取流 */
    private var lastSwitchAt = 0L

    /**
     * 切换动画进行中（需求6 第三项）。
     *
     * 动画期间必须拒掉新的切换请求：一次快速连滑会在 300ms 内连发多个 delta，
     * 若每个都启动动画，画面会叠着滑、`currentIndex` 也会跳着前进，用户看到的是"穿了好几条"。
     * 这个标志就是那道闸门（`switchRelative` 开头判它）。
     */
    private var switchAnimating = false

    /** 当前打开的短视频设置弹层（切换端点后需要主动关闭它，见 [showStorySettingsDialog]） */
    private var dialogRef: AlertDialog? = null

    private fun switchRelative(delta: Int) {
        //动画/切换进行中：忽略新的切换请求（防止一次快速连滑穿好几条视频）
        if (switchAnimating) {
            Logu.d("story", "切换动画中，忽略切换请求 delta=" + delta)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSwitchAt < 300) return
        lastSwitchAt = now
        val next = currentIndex + delta
        if (next < 0) {
            showHint("已经是第一个了")
            return
        }
        if (next >= storyList.size) {
            // 已经在拉了：等这一批回来再滑（旧实现会直接 return，回调永远不执行，
            // 提示一直停在“正在加载更多…”，用户以为卡死）
            if (loadingMore) {
                showHint("正在加载更多…")
                return
            }
            showHint("正在加载更多…")
            loadMore {
                if (!destroyed) {
                    if (currentIndex + 1 < storyList.size) animateSwitch(currentIndex + 1, 1)
                    else showHint("没有更多了")
                }
            }
            return
        }
        animateSwitch(next, if (delta > 0) 1 else -1)
    }

    /**
     * 切换动画（需求3）：整层画面按方向滑出 → 换内容 → 从反方向滑回。
     *
     * 为什么用 FrameLayout 的 translationY + alpha，而不是 ViewPager/新依赖：
     * - 视频层是 SurfaceView，不能像普通 View 那样做截屏过渡（Surface 内容是硬件合成的），
     *   但整层 translationY 是视图变换，SurfaceView 会跟随（API 21+）；
     * - 只做「滑出 + 滑入」两段，不加弹跳/回弹，轻量且不会拖慢起播；
     * - 新视频的取流/首帧在滑出的同时已经开始（见 switchTo），
     *   所以动画结束通常正好首帧就绪，观感是连续的。
     *
     * @param direction +1 = 下一个（画面向上滑出），-1 = 上一个（向下滑出）
     */
    private fun animateSwitch(index: Int, direction: Int) {
        val container = videoLayout
        if (container == null || destroyed) {
            switchTo(index)
            return
        }
        //缩放状态下切视频：先还原，否则新画面会带着上一支的缩放/平移
        if (isZoomed()) resetZoom(animated = false)
        switchAnimating = true
        val distance = container.height.toFloat() * 0.28f
        container.animate()
            .translationY(-distance * direction)
            .alpha(0.35f)
            .setDuration(SWITCH_ANIM_OUT_MS)
            .withEndAction {
                if (destroyed) {
                    switchAnimating = false
                } else {
                    //换内容
                    switchTo(index)
                    //从反方向就位后滑入
                    container.translationY = distance * direction
                    container.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(SWITCH_ANIM_IN_MS)
                        .withEndAction { switchAnimating = false }
                        .start()
                }
            }
            .start()
    }

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
        endLongPressSpeed()

        hideStatus()
        //切换视频：先清掉上一支的弹幕（只清数据、不隐藏弹幕层），再为这一支重新拉取
        try {
            danmakuAid = 0L
            danmakuController?.resetForNewVideo()
            startDanmakuFor(card)
        } catch (e: Exception) {
            Logu.e("story", "加载弹幕失败：" + e.message)
        }
        titleView?.text = card.title ?: ""
        upnameView?.text = if (card.upName.isNullOrEmpty()) "" else "UP：" + card.upName
        metaView?.text = buildMeta(card)
        seekBar?.progress = 0
        timeView?.text = ""
        liked = false
        userPaused = false
        likeBtn?.setImageResource(R.drawable.icon_like_0)
        playBtn?.setImageResource(R.drawable.btn_player_pause)
        // 延迟 400ms 再显示加载遮罩：命中预下载时首帧很快，避免闪一下黑底
        setLoading(true, "加载中…", 400L)
        setControlsVisible(true)
        scheduleHideControls(4000)

        if (storyList.size - index <= 2) loadMore()

        val cached = synchronized(playCache) { playCache[index] }
        if (cached != null && cached.url.isNotEmpty() && isFresh(cached)) {
            startPlayback(cached)
            return
        }
        //没有缓存直链时：先看看预下载文件在不在（在的话直接本地播放，秒开）
        resolveAndPlay(card, forceHtml5 = false)
    }

    private fun resolveAndPlay(card: StoryApi.StoryCard, forceHtml5: Boolean) {
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
                info = StoryApi.resolvePlay(card.aid, card.cid, currentQn, forceHtml5)
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

    /** 预下载目录（仅缓存下一两支，退出时清理） */
    private fun prefetchDir(): java.io.File = java.io.File(cacheDir, "story_preload")

    private fun localFileFor(card: StoryApi.StoryCard): java.io.File =
        java.io.File(prefetchDir(), (if (card.cid > 0) card.cid else card.aid).toString() + ".mp4")

    /** 后台把下一支下载到本地，切换时直接播本地文件（消除起播卡顿） */
    private fun prefetchMedia(index: Int, info: StoryApi.StoryPlayInfo) {
        if (index < 0 || index >= storyList.size) return
        val card = storyList[index]
        val dest = localFileFor(card)
        if (dest.exists() && dest.length() > 0) return
        CenterThreadPool.run(Runnable {
            try {
                StoryApi.prefetchToFile(info.url, dest)
            } catch (e: Exception) {
                Logu.d("story", "预下载失败：" + e.message)
            }
        })
    }

    /** 直链是否还没过期（B 站播放地址带时效，过期后必须重新解析） */
    private fun isFresh(info: StoryApi.StoryPlayInfo): Boolean =
        System.currentTimeMillis() - info.fetchedAt < PLAY_URL_TTL_MS

    private fun preloadNext(nextIndex: Int) {
        if (nextIndex < 0 || nextIndex >= storyList.size) return
        // v5.3.4（需求6 第五项：WiFi 预加载）
        // 只在 **WiFi + 实验室开关打开** 时预取。移动数据下不预取的理由：
        // 短视频是"划到哪看到哪"的场景，预取一条 4~8MB 在流量下是用户没同意的消费。
        // 开关复用主播放器详情页的同一个键（WifiPreloadManager.PREF_WIFI_PRELOAD）——
        // 用户心智是"我要预加载"，而不是"我要在详情页预加载"。两处共用同一个开关
        // 让"开了就到处都会预取"这件事可预期。
        //
        // 注意：这里只拦 playCache 的**预取**，不拦正常播放。用户主动划到的视频
        // 无论什么网络都必须能播 —— 那是他明确要求的动作，不属于"预加载"。
        if (!WifiPreloadManager.isEnabled() || !WifiPreloadManager.isOnWifi()) {
            return
        }
        synchronized(playCache) {
            val cached = playCache[nextIndex]
            if (cached != null) {
                if (isFresh(cached)) return
                playCache.remove(nextIndex)
            }
        }
        val card = storyList[nextIndex]
        CenterThreadPool.run(Runnable {
            try {
                if (card.cid <= 0) {
                    val vi = com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(card.aid)
                    val cid = vi?.cids?.firstOrNull() ?: 0L
                    if (cid > 0) card.cid = cid
                }
                if (card.cid <= 0) return@Runnable
                val info = StoryApi.resolvePlay(card.aid, card.cid, currentQn, false)
                if (info.url.isNotEmpty()) {
                    synchronized(playCache) {
                        if (playCache.size > 6) playCache.clear()
                        playCache[nextIndex] = info
                    }
                    prefetchMedia(nextIndex, info)
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

    private fun startPlayback(info: StoryApi.StoryPlayInfo, startPositionMs: Long = 0L) {
        currentInfo = info
        if (info.source == "html5") triedHtml5 = true

        //预下载命中：直接播本地文件，不用再走网络（切换几乎瞬开）
        val card = currentCard
        var local: java.io.File? = null
        if (card != null) {
            val f = localFileFor(card)
            if (f.exists() && f.length() > 1024) local = f
        }

        val headers = HashMap<String, String>()
        headers["Referer"] = "https://www.bilibili.com/"
        // web 未登录时不加 Cookie 头（P3-1）：短视频同样播放 CDN 直链，Cookie 非必需
        NetWorkUtil.cookieHeaderOrNull()?.let { headers["Cookie"] = it }
        headers["User-Agent"] = NetWorkUtil.USER_AGENT_WEB
        currentHeaders = headers

        if (info.note.isNotEmpty()) showStatus(info.note, autoHide = true)
        // 预下载命中/直链很快时首帧会先到，延迟显示遮罩能避免「闪一下黑底再出画」
        setLoading(true, "缓冲中…", 350L)

        val hardware = SharedPreferencesUtil.getBoolean("player_codec", true)
        videoW = 0
        videoH = 0
        val playUrl = local?.absolutePath ?: info.url
        Logu.d("story", "播放源：" + (if (local != null) "本地预下载 " else "直链 ") + playUrl.take(90))
        controller?.play(playUrl, if (local != null) HashMap() else headers, startPositionMs, hardware)
        // 播放器重建后恢复用户选择的倍速
        controller?.setSpeed(if (longPressActive) LONG_PRESS_SPEED else normalSpeed)

        //播起来之后立即预取下一支（直链 + 本地预下载）
        if (currentIndex + 1 < storyList.size) preloadNext(currentIndex + 1)
    }

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
                userPaused = true
                playBtn?.setImageResource(R.drawable.btn_player_play)
                syncDanmakuPlaying(false)
            } else {
                // 播完以后再点播放：从头开始（否则 ijk 会停在最后一帧不动）
                val dur = c.currentDuration()
                val pos = c.currentPosition()
                if (dur > 0 && pos >= dur - 500) {
                    c.seekTo(0)
                    danmakuController?.seekTo(0)
                }
                c.resume()
                userPaused = false
                playBtn?.setImageResource(R.drawable.btn_player_pause)
                syncDanmakuPlaying(true)
                scheduleHideControls(3000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val playerListener = object : StoryPlaybackController.Listener {
        override fun onPlayerPrepared(durationMs: Long) {
            if (destroyed) return
            switching = false
            if (durationMs > 0) timeView?.text = timeText(0, durationMs)
            // cid 是切集后才解析出来的（信息流卡片没带 cid）时，这里补一次弹幕
            currentCard?.let { card ->
                if (card.cid > 0 && danmakuAid != card.aid) {
                    try {
                        startDanmakuFor(card)
                    } catch (e: Exception) {
                        Logu.e("story", "补拉弹幕失败：" + e.message)
                    }
                }
            }
            if (danmakuEnabled) {
                try {
                    danmakuController?.start()
                    danmakuController?.setSpeed(if (longPressActive) LONG_PRESS_SPEED else normalSpeed)
                } catch (e: Exception) {
                    Logu.e("story", "启动弹幕失败：" + e.message)
                }
            }
        }

        override fun onPlayerProgress(positionMs: Long, durationMs: Long) {
            if (destroyed || seeking) return
            if (durationMs > 0) {
                seekBar?.progress = (positionMs * 1000L / durationMs).toInt().coerceIn(0, 1000)
                timeView?.text = timeText(positionMs, durationMs)
            }
        }

        override fun onPlayerBuffering(buffering: Boolean) {
            if (destroyed) return
            if (buffering) setLoading(true, "缓冲中…", 350L)
            else if (firstFrameShown) setLoading(false)
        }

        override fun onPlayerFirstFrame() {
            if (destroyed) return
            firstFrameShown = true
            failCount = 0
            switching = false
            setLoading(false)
            playBtn?.setImageResource(
                if (userPaused) R.drawable.btn_player_play else R.drawable.btn_player_pause
            )
            setControlsVisible(true)
            scheduleHideControls(3000)
            if (!userPaused) syncDanmakuPlaying(true)
        }

        override fun onPlayerCompletion() {
            if (destroyed) return
            if (autoNext) {
                switchRelative(1)
            } else {
                userPaused = true
                playBtn?.setImageResource(R.drawable.btn_player_play)
                syncDanmakuPlaying(false)
                showHint("播放结束")
            }
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
            if (destroyed || width <= 0 || height <= 0) return
            videoW = width
            videoH = height
            applyVideoLayout()
        }

        override fun onPlayerRotation(degrees: Int) {
            //部分竖屏视频用旋转元数据表示；只在用户没有手动旋转时跟随
            if (destroyed) return
            if (userRotation == degrees) return
            if ((degrees == 90 || degrees == 270) && videoW > videoH) {
                userRotation = degrees
                applyVideoLayout()
            }
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

    private fun openDetail() {
        val card = currentCard ?: return
        val intent = Intent(this, com.RobinNotBad.BiliClient.activity.video.info.VideoInfoActivity::class.java)
        intent.putExtra("aid", card.aid)
        if (!card.bvid.isNullOrEmpty()) intent.putExtra("bvid", card.bvid)
        startActivity(intent)
    }

    companion object {
        private val SPEED_VALUES = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
        private val SPEED_LABELS = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x", "3.0x")
        // 6 = 最低（服务端会回落到该视频支持的最低档，手表上够用且最省流）
        private val QUALITY_VALUES = intArrayOf(6, 16, 32, 64, 80)
        private val QUALITY_LABELS = arrayOf("最低", "360P", "480P", "720P", "1080P")
        private const val LONG_PRESS_SPEED = 2.0f

        private const val FIT_FIT = 0
        private const val FIT_FILL = 1
        private const val FIT_STRETCH = 2

        /** 直链缓存有效期：超过就重新解析（B 站直链约 2 小时失效，留足余量） */
        private const val PLAY_URL_TTL_MS = 20 * 60 * 1000L

        // ---------------- 缩放手势（需求6 第一项） ----------------

        /** 原始大小。1.0x 是「适应屏幕/填满/拉伸」三档比例各自的基准，缩放是在其上的叠乘 */
        private const val MIN_SCALE = 1.0f

        /**
         * 缩放上限。
         *
         * 4.0x 的选择依据：短视频多为竖屏 1080x1920，在 466px 宽的圆屏上
         * 4 倍约等于"放大到一个局部细节"，再大就只剩马赛克（源本身没有更多信息）；
         * 同时 4x 时平移范围仍在手指舒适区内（`maxPanX/Y` 按缩放后尺寸算）。
         */
        private const val MAX_SCALE = 4.0f

        /** 双击还原/放大的过渡时长：短到"立刻响应"，长到不显得生硬 */
        private const val ZOOM_ANIM_MS = 180L

        // ---------------- 切换动画（需求6 第三项） ----------------

        /**
         * 滑出时长。
         *
         * 比常见 ViewPager 的 250~300ms 短：短视频是"连续刷"的场景，
         * 单次切换太慢会明显拖累手感。130ms 足够让眼睛看到"方向"，
         * 又不会挡住下一条的起播（取流在滑出同时已经开始）。
         */
        private const val SWITCH_ANIM_OUT_MS = 130L

        /** 滑入时长：比滑出略长，收尾更柔和（人眼对"进入"比"离开"更敏感） */
        private const val SWITCH_ANIM_IN_MS = 160L
    }
}
