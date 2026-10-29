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
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.player.PlayerAudioPolicy
import com.RobinNotBad.BiliClient.activity.player.PlayerDanmakuController
import com.RobinNotBad.BiliClient.api.StoryApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

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
 *
 * v5.0.2-alpha7 追加（都不新增按钮，避免圆屏右侧按钮列再变长）：
 * - **长按点赞 = 一键三连**（LikeCoinFavApi.triple：点赞 + 投币 + 收藏）；
 * - **点 UP 名 = UP 主主页，长按 UP 名 = 关注该 UP 主**（UserInfoActivity / UserInfoApi.followUser，
 *   mid 用 VideoInfoApi 按需解析并按 aid 缓存）；
 * - **长按弹幕按钮 = 一键静音/取消静音**（只改 IjkMediaPlayer 音量，不动系统音量与弹幕开关）；
 * - 下一支预取加在飞去重（原来会被「取流成功」和「开始播放」各触发一次）、
 *   预下载目录只留最近 3 支、切清晰度/重试后弹幕时间轴跟随起播位置。
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class StoryPlayerActivity : BaseActivity() {

    private var videoLayout: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var topBar: LinearLayout? = null
    private var buttonsBar: LinearLayout? = null
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

    /**
     * 正在预取的下标。下一支视频会被「取流成功」和「开始播放」两处同时触发预取，
     * 用这个在飞集合去重，避免同一支视频并发请求两次直链 + 下载两次（手表上很费流量和电）。
     */
    private val prefetchInFlight = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** aid → UP 主 mid 缓存（信息流卡片不带 mid，按需查一次后复用） */
    private val upMidCache = HashMap<Long, Long>()

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

    /**
     * 视频像素宽高比（SAR），1/1 = 方形像素。
     *
     * v5.0.2-alpha8（bug1）：画面比例按「显示宽高」（像素宽高 * SAR）来算。
     * 之前只用像素宽高，遇到 SAR 非 1:1 的片源会被横向压扁。
     */
    private var videoSarNum = 1
    private var videoSarDen = 1

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
        //长按点赞 = 一键三连（点赞 + 投币 + 收藏到默认收藏夹），复用 LikeCoinFavApi.triple
        likeBtn?.setOnLongClickListener {
            tripleCurrent()
            true
        }
        //点 UP 名 = 看 UP 主页（复用 UserInfoActivity）；长按 UP 名 = 关注该 UP 主
        //注意：这两个监听会让 UP 名区域自己消费触摸事件，该区域不再触发「单击显隐控制层」，
        //其余区域的手势（单击/双击/长按 2x/上下滑）不受影响。
        upnameView?.minHeight = ToolsUtil.dp2px(24f)
        upnameView?.setOnClickListener { openUpSpace() }
        upnameView?.setOnLongClickListener {
            followCurrentUp()
            true
        }
        //长按弹幕按钮 = 一键静音 / 取消静音（不改变弹幕开关）
        danmakuBtn?.setOnLongClickListener {
            toggleMute()
            true
        }
        findViewById<View>(R.id.story_btn_detail).setOnClickListener { openDetail() }
        speedBtn!!.setOnClickListener { showSpeedDialog() }
        danmakuBtn!!.setOnClickListener { toggleDanmaku() }
        qualityBtn!!.setOnClickListener { showQualityDialog() }
        // 长按清晰度按钮 = 画面比例 / 旋转（不额外占按钮位置）
        qualityBtn!!.setOnLongClickListener {
            showDisplayDialog()
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
        // v5.0.2-alpha8（bug1）：用「显示宽高」= 像素宽高 * SAR 计算比例。
        // 旧实现直接拿像素宽高，像素不是方形（SAR≠1:1）的片源会被横向压扁。
        // 每个视频切换时 onPlayerVideoSize 都会重新进来算一次，
        // 保证不同长宽比的视频各自按自己的比例摆放，不会共用同一个比例。
        var vw = videoW.toDouble() * videoSarNum
        var vh = videoH.toDouble() * videoSarDen
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
        //v5.0.2-alpha8（bug1）：真机排查用——显示宽高与最终画面框尺寸，比例对不上时一眼看出
        Logu.d("story", "画面比例 " + videoW + "x" + videoH + " sar " + videoSarNum + "/" + videoSarDen
                + " -> " + lp.width + "x" + lp.height + "（模式 " + fitMode + "）")
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

    private fun initGesture() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (Math.abs(dy) > 90f && Math.abs(dy) > Math.abs(dx) * 2f) {
                    if (dy < 0) switchRelative(1) else switchRelative(-1)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }
        })

        findViewById<View>(R.id.story_root).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    findViewById<View>(R.id.story_root).removeCallbacks(longPressRunnable)
                    findViewById<View>(R.id.story_root).postDelayed(longPressRunnable, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.x - downX) > ToolsUtil.dp2px(12f) ||
                            Math.abs(event.y - downY) > ToolsUtil.dp2px(12f)) {
                        findViewById<View>(R.id.story_root).removeCallbacks(longPressRunnable)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    findViewById<View>(R.id.story_root).removeCallbacks(longPressRunnable)
                    endLongPressSpeed()
                }
            }
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

    private fun switchRelative(delta: Int) {
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
                    if (currentIndex + 1 < storyList.size) switchTo(currentIndex + 1)
                    else showHint("没有更多了")
                }
            }
            return
        }
        switchTo(next)
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
        synchronized(playCache) {
            val cached = playCache[nextIndex]
            if (cached != null) {
                if (isFresh(cached)) return
                playCache.remove(nextIndex)
            }
        }
        //同一支视频的预取可能被两处同时触发：已经在飞就直接跳过
        if (!prefetchInFlight.add(nextIndex)) return
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
            } finally {
                prefetchInFlight.remove(nextIndex)
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
        headers["Cookie"] = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        headers["User-Agent"] = NetWorkUtil.USER_AGENT_WEB
        currentHeaders = headers

        if (info.note.isNotEmpty()) showStatus(info.note, autoHide = true)
        // 预下载命中/直链很快时首帧会先到，延迟显示遮罩能避免「闪一下黑底再出画」
        setLoading(true, "缓冲中…", 350L)

        val hardware = SharedPreferencesUtil.getBoolean("player_codec", true)
        videoW = 0
        videoH = 0
        //v5.0.2-alpha8（bug1）：换视频时把 SAR 也复位，避免上一支的 SAR 影响新视频的比例
        videoSarNum = 1
        videoSarDen = 1
        val playUrl = local?.absolutePath ?: info.url
        Logu.d("story", "播放源：" + (if (local != null) "本地预下载 " else "直链 ") + playUrl.take(90))
        controller?.play(playUrl, if (local != null) HashMap() else headers, startPositionMs, hardware)
        // 播放器重建后恢复用户选择的倍速
        controller?.setSpeed(if (longPressActive) LONG_PRESS_SPEED else normalSpeed)
        //从指定位置重新起播（切清晰度 / 失败重试）时弹幕时间轴要跟着跳，
        //否则弹幕会停在上一次的时间点，和画面错位
        try {
            danmakuController?.seekTo(startPositionMs)
        } catch (e: Exception) {
            Logu.e("story", "弹幕时间轴同步失败：" + e.message)
        }

        //播起来之后立即预取下一支（直链 + 本地预下载）
        if (currentIndex + 1 < storyList.size) preloadNext(currentIndex + 1)
        //只留最近 3 支的预下载文件，长时间刷视频不会把缓存目录撑大
        trimPrefetchCache()
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
            //v5.0.2-alpha8（bug1）：首帧出来时再按当前尺寸摆一次画面。
            //部分片源/硬解的尺寸事件晚于首帧，之前只会沿用上一支视频的 layoutParams，
            //看起来就像「不同长宽比都被压成同一个比例」。
            applyVideoLayout()
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

        override fun onPlayerVideoSize(width: Int, height: Int, sarNum: Int, sarDen: Int) {
            if (destroyed || width <= 0 || height <= 0) return
            videoW = width
            videoH = height
            videoSarNum = if (sarNum > 0) sarNum else 1
            videoSarDen = if (sarDen > 0) sarDen else 1
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

    // ==================== 快捷操作（不新增按钮，复用现有 API） ====================

    /**
     * 长按点赞按钮：一键三连（点赞 + 投币 + 收藏到默认收藏夹）。
     * 复用 LikeCoinFavApi.triple（与网页端同一接口），成功后把点赞图标同步成已赞。
     */
    private fun tripleCurrent() {
        val card = currentCard ?: return
        likeBtn?.let { it.isEnabled = false }
        CoroutineScopes.launchUi {
            val code = try {
                CoroutineScopes.io { com.RobinNotBad.BiliClient.api.LikeCoinFavApi.triple(card.aid) }
            } catch (e: Exception) {
                Logu.e("story", "一键三连失败：" + e.message)
                -1
            }
            if (destroyed) return@launchUi
            likeBtn?.let { it.isEnabled = true }
            when {
                code == 0 -> {
                    liked = true
                    likeBtn?.setImageResource(R.drawable.icon_like_1)
                    showHint("已三连：点赞 + 投币 + 收藏")
                }
                code == -101 || code == -111 -> showHint("三连失败：请先登录")
                else -> showHint("三连失败，错误码：" + code)
            }
        }
    }

    /** 点击 UP 名：打开 UP 主主页（Intent 参数与其它入口一致：mid） */
    private fun openUpSpace() {
        resolveUpMid(currentCard) { mid ->
            if (mid <= 0) {
                showHint("暂时拿不到 UP 主信息")
                return@resolveUpMid
            }
            val intent = Intent(this, com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity::class.java)
            intent.putExtra("mid", mid)
            startActivity(intent)
        }
    }

    /** 长按 UP 名：关注该 UP 主（复用 UserInfoApi.followUser，未登录先提示） */
    private fun followCurrentUp() {
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) {
            showHint("请先登录")
            return
        }
        resolveUpMid(currentCard) { mid ->
            if (mid <= 0) {
                showHint("暂时拿不到 UP 主信息")
                return@resolveUpMid
            }
            CoroutineScopes.launchUi {
                val code = try {
                    CoroutineScopes.io {
                        com.RobinNotBad.BiliClient.api.UserInfoApi.followUser(mid, true)
                    }
                } catch (e: Exception) {
                    Logu.e("story", "关注 UP 主失败：" + e.message)
                    -1
                }
                if (destroyed) return@launchUi
                showHint(if (code == 0) "已关注该 UP 主" else "关注失败，错误码：" + code)
            }
        }
    }

    /**
     * 解析当前视频的 UP 主 mid。
     * 信息流卡片不一定带 owner.mid（StoryCard 里没有该字段），所以按需用 VideoInfoApi 查一次
     * （同 aid 结果缓存，避免重复请求）；拿不到就回调 0，由调用方提示。
     */
    private fun resolveUpMid(card: StoryApi.StoryCard?, onReady: (Long) -> Unit) {
        if (card == null) {
            onReady(0L)
            return
        }
        upMidCache[card.aid]?.let { cached ->
            onReady(cached)
            return
        }
        CoroutineScopes.launchUi {
            val mid = try {
                CoroutineScopes.io {
                    com.RobinNotBad.BiliClient.api.VideoInfoApi.getVideoInfo(card.aid)
                        ?.staff?.firstOrNull()?.mid ?: 0L
                }
            } catch (e: Exception) {
                Logu.e("story", "解析 UP 主 mid 失败：" + e.message)
                0L
            }
            if (destroyed) return@launchUi
            if (mid > 0) upMidCache[card.aid] = mid
            onReady(mid)
        }
    }

    /** 长按弹幕按钮：一键静音 / 取消静音（只改播放器音量，弹幕开关不受影响） */
    private fun toggleMute() {
        val c = controller ?: return
        val muted = !c.isMuted()
        c.setMuted(muted)
        showHint(if (muted) "已静音（长按弹幕按钮恢复）" else "已恢复声音")
    }

    /**
     * 预下载目录只保留最近 [keep] 支视频（当前正在播放的那支永远不删）。
     * 旧实现只在退出页面时删整个目录，连续刷几十支后会一直堆在 cacheDir 里。
     */
    private fun trimPrefetchCache(keep: Int = 3) {
        //当前正在播的本地文件可能还没被 ijk 打开（Surface 未就绪时是延迟 prepare），
        //先把它排除掉，避免刚清完缓存就播放失败
        val playing = currentCard?.let { localFileFor(it).absolutePath }
        CoroutineScopes.launchIo {
            try {
                val files = prefetchDir().listFiles() ?: return@launchIo
                val sorted = files.filter { it.isFile }.sortedByDescending { it.lastModified() }
                var kept = 0
                for (f in sorted) {
                    if (f.absolutePath == playing) continue
                    kept++
                    if (kept > keep) f.delete()
                }
            } catch (e: Exception) {
                Logu.d("story", "清理预下载缓存失败：" + e.message)
            }
        }
    }

    companion object {
        // v5.0.2-alpha10：倍速档位/文案改为与主播放器共用 PlayerAudioPolicy 里的同一份，
        // 上限 2.0（3.0x 超出 SoundTouch 稳定区间，倍速时会成段吞掉音节）。
        private val SPEED_VALUES: FloatArray = PlayerAudioPolicy.SPEED_VALUES
        private val SPEED_LABELS: Array<String> = PlayerAudioPolicy.SPEED_LABELS
        // 6 = 最低（服务端会回落到该视频支持的最低档，手表上够用且最省流）
        private val QUALITY_VALUES = intArrayOf(6, 16, 32, 64, 80)
        private val QUALITY_LABELS = arrayOf("最低", "360P", "480P", "720P", "1080P")
        private const val LONG_PRESS_SPEED = 2.0f

        private const val FIT_FIT = 0
        private const val FIT_FILL = 1
        private const val FIT_STRETCH = 2

        /** 直链缓存有效期：超过就重新解析（B 站直链约 2 小时失效，留足余量） */
        private const val PLAY_URL_TTL_MS = 20 * 60 * 1000L
    }
}
