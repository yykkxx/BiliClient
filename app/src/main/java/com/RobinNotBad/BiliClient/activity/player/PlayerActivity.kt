package com.RobinNotBad.BiliClient.activity.player

import android.media.AudioManager.STREAM_MUSIC

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimationDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout

import androidx.appcompat.app.AlertDialog
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.InteractionDebugActivity
import com.RobinNotBad.BiliClient.adapter.QualitySelectorAdapter
import com.RobinNotBad.BiliClient.adapter.ViewPointAdapter
import com.RobinNotBad.BiliClient.api.ConfInfoApi
import com.RobinNotBad.BiliClient.api.DanmakuApi
import com.RobinNotBad.BiliClient.api.HistoryApi
import com.RobinNotBad.BiliClient.api.InteractionVideoApi
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.api.VideoInfoApi
import com.RobinNotBad.BiliClient.event.SnackEvent
import com.RobinNotBad.BiliClient.model.HighEnergyData
import com.RobinNotBad.BiliClient.model.InteractionVideoData
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.Subtitle
import com.RobinNotBad.BiliClient.model.SubtitleLink
import com.RobinNotBad.BiliClient.model.ViewPoint
import com.RobinNotBad.BiliClient.ui.widget.BatteryView
import com.RobinNotBad.BiliClient.ui.widget.HighEnergyProgressBar
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil.USER_AGENT_WEB
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StreamPolicyManager
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.RobinNotBad.BiliClient.util.WifiPreloadManager
import com.google.android.material.snackbar.Snackbar

import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject

import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.HashMap
import java.util.Locale
import java.util.Objects
import java.util.Timer
import java.util.TimerTask
import java.util.regex.Pattern

import master.flame.danmaku.controller.IDanmakuView
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.Request
import okhttp3.Response
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

class PlayerActivity : Activity(), IMediaPlayer.OnPreparedListener {
    @JvmField
    var destroyed = false

    private var ijkPlayer: IjkMediaPlayer? = null
    private var mDanmakuView: IDanmakuView? = null

    /** 弹幕层控制器（Kotlin）：配置、解析器、增量渲染、缺失分段补拉 */
    private var danmakuController: PlayerDanmakuController? = null

    /** 播放卡死看门狗（Kotlin）：网络波动导致假死时自动恢复 */
    private var playbackWatchdog: PlaybackWatchdog? = null

    /** 弹幕帧率温控（Kotlin）：按设备温度动态调整弹幕重绘帧率（#8） */
    private var danmakuThermalController: DanmakuThermalController? = null

    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var mSurfaceTexture: SurfaceTexture? = null

    private var subtitleLinks: Array<SubtitleLink?>? = null
    private var subtitles: Array<Subtitle?>? = null
    private var subtitle_curr_index = 0
    private var subtitle_count = 0
    private var subtitle_delta = 0f

    private var layout_control: RelativeLayout? = null
    private var layout_top: RelativeLayout? = null
    private var layout_video: RelativeLayout? = null
    private var layout_card_bg: RelativeLayout? = null
    private var card_time_jump: LinearLayout? = null
    private var time_jump_input: EditText? = null
    private var layout_audio_only: RelativeLayout? = null
    private var layout_speed: LinearLayout? = null
    private var right_control: LinearLayout? = null
    private var loading_info: LinearLayout? = null
    private var bottom_buttons: RelativeLayout? = null
    private var right_second: HorizontalScrollView? = null
    private var card_subtitle: LinearLayout? = null
    private var card_danmaku_send: LinearLayout? = null
    private var card_page_selector: LinearLayout? = null
    private var card_quality_selector: LinearLayout? = null
    private var card_viewpoint_selector: LinearLayout? = null

    private var img_loading: ImageView? = null
    private var anim_loading: AnimationDrawable? = null
    private var btn_control: ImageButton? = null
    private var btn_danmaku: ImageButton? = null
    private var btn_loop: ImageButton? = null
    private var btn_rotate: ImageButton? = null
    private var btn_menu: ImageButton? = null
    private var btn_subtitle: ImageButton? = null
    private var btn_danmaku_send: ImageButton? = null
    private var btn_audio_only: ImageButton? = null
    private var btn_page_selector: ImageButton? = null
    private var btn_auto_next: ImageButton? = null
    private var btn_quality: ImageButton? = null
    private var btn_viewpoint: ImageButton? = null
    private var btn_debug: ImageButton? = null
    private var seekbar_progress: HighEnergyProgressBar? = null
    private var seekbar_speed: SeekBar? = null
    private var text_progress: TextView? = null
    private var text_online: TextView? = null
    private var text_volume: TextView? = null
    private var loading_text0: TextView? = null
    private var loading_text1: TextView? = null
    private var text_speed: TextView? = null
    private var text_newspeed: TextView? = null

    /** 长按倍速时显示在视频上方的半透明提示框（#7） */
    private var text_speed_hint: TextView? = null

    @JvmField
    var text_title: TextView? = null

    @JvmField
    var text_subtitle: TextView? = null

    @JvmField
    var text_audio_title: TextView? = null

    @JvmField
    var text_audio_subtitle: TextView? = null

    private var progressTimer: Timer? = null
    private var speedTimer: Timer? = null
    private var loadingTimer: Timer? = null
    private var onlineTimer: Timer? = null

    @JvmField
    var pendingPrepare = false // surface就绪后需要触发MPPrepare（回调驱动，替代轮询定时器）

    @JvmField
    var pendingRecoverySeek: Long = -1L // 卡死自动恢复后需要跳回的进度（毫秒），-1 表示无

    /** surface 回调引用，便于重建时先移除，避免回调重复累积 */
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var mainHandler: Handler? = null
    private var danmakuSyncRunnable: Runnable? = null
    private var video_url: String? = null
    private var danmaku_url: String? = null
    private var mediaSession: MediaSession? = null

    private var isPlaying = false
    private var isPrepared = false
    private var isOnlineVideo = false
    private var isLiveMode = false
    private var isSeeking = false
    private var menu_opened = false
    private var isAudioOnlyMode = false
    private var isLocalAudioFile = false // 标记是否为本地音频文件

    private var video_all = 0
    private var video_now = 0
    private var video_now_last = 0
    private var progress_history: Long = 0
    private var progress_str: String? = null

    /** 控制条隐藏期间的低频刷新计数（#1） */
    private var hiddenTickCount = 0

    private var screen_width = 0
    private var screen_height = 0
    private var video_width = 0
    private var video_height = 0

    private var audioManager: AudioManager? = null

    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var scaleGestureListener: ViewScaleGestureListener? = null
    private var doubleTapGestureDetector: GestureDetector? = null
    private var previousX = 0f
    private var previousY = 0f
    private var gesture_moved = false
    private var gesture_scaled = false
    private var gesture_click_disabled = false

    /** 长按倍速的倍率（原实现固定 3.0x，提示框文案“二倍速”按此常量生成） */
    private val LONG_CLICK_SPEED = 2.0f

    /** 表冠控制进度时每一档前进/后退的毫秒数（#23） */
    private val CROWN_SEEK_STEP_MS = 5000f
    private var video_origX = 0f
    private var video_origY = 0f
    private var timestamp_click: Long = 0
    private var onLongClick = false

    private val speed_values = floatArrayOf(0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 1.75F, 2.0F, 3.0F)
    private val speed_strs = arrayOf("x 0.5", "x 0.75", "x 1.0", "x 1.25", "x 1.5", "x 1.75", "x 2.0", "x 3.0")

    private var finishWatching = false
    private var loop_enabled = false
    private var auto_next_enabled = false

    private var batteryView: BatteryView? = null
    private var batteryManager: BatteryManager? = null

    private var danmakuFile: File? = null

    private var screen_landscape = false
    private var screen_round = false

    /** 是否刚从后台切回（#6），用于判断弹幕层要不要重建 */
    private var wentToBackground = false

    /** 弹幕层是否正在重建（#6），避免重入导致同时存在两个弹幕层 */
    private var danmakuRestarting = false


    /** 弹幕层是否已装载内容（委托给 Kotlin 控制器） */
    private fun hasDanmaku(): Boolean {
        return danmakuController != null && danmakuController!!.hasDanmaku
    }

    /** 弹幕层当前是否可见 */
    private fun isDanmakuVisible(): Boolean {
        return danmakuController != null && danmakuController!!.danmakuVisible
    }

    /** 当前弹幕层是否可用（视图与控制器都已就绪） */
    private fun danmakuReady(): Boolean {
        return danmakuController != null && mDanmakuView != null
    }

    @JvmField
    var online_number = "0"

    private var aid: Long = 0
    private var cid: Long = 0
    private var mid: Long = 0

    private var pagenames: ArrayList<String>? = null
    private var cids: ArrayList<Long>? = null
    private var currentPageIndex = 0
    private var videoTitle: String? = null

    private var qnStrList: Array<String?>? = null
    private var qnValueList: IntArray? = null
    private var currentQuality = 0

    private var interactionData: InteractionVideoData? = null
    private var interactionGraphVersion: Long = 0
    private var currentEdgeId: Long = 0
    private var initialEdgeId: Long = 0
    private var currentQuestion: InteractionVideoData.InteractionQuestion? = null
    private var questionShown = false
    private var interactionChoiceLayout: LinearLayout? = null

    override fun onBackPressed() {
        if (!SharedPreferencesUtil.getBoolean("back_disable", false))
            super.onBackPressed()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase))
    }

    private fun getExtras(): Boolean {
        val intent = getIntent() ?: return false

        video_url = intent.getStringExtra("url")
        danmaku_url = intent.getStringExtra("danmaku")
        val title = intent.getStringExtra("title")

        if (video_url == null)
            return false
        if (danmaku_url != null)
            Logu.v("弹幕", danmaku_url ?: "")
        Logu.v("视频", video_url ?: "")
        Logu.v("标题", title ?: "")
        text_title!!.text = title
        videoTitle = title

        aid = intent.getLongExtra("aid", 0)
        cid = intent.getLongExtra("cid", 0)
        mid = intent.getLongExtra("mid", 0)

        progress_history = intent.getIntExtra("progress", 0).toLong()
        Logu.d("history", progress_history.toString())

        isLiveMode = intent.getBooleanExtra("live_mode", false)
        isOnlineVideo = video_url!!.contains("http")

        if (intent.hasExtra("pagenames") && intent.hasExtra("cids")) {
            pagenames = intent.getStringArrayListExtra("pagenames")
            val cidList = ArrayList<Long>()
            val cidArray = intent.getLongArrayExtra("cids")
            if (cidArray != null) {
                for (c in cidArray) {
                    cidList.add(c)
                }
            }
            cids = cidList
            currentPageIndex = intent.getIntExtra("currentPageIndex", 0)
        }

        initialEdgeId = intent.getLongExtra("edgeId", 0)
        if (initialEdgeId > 0) {
            currentEdgeId = initialEdgeId
        }

        if (intent.hasExtra("qnStrList") && intent.hasExtra("qnValueList")) {
            qnStrList = intent.getStringArrayExtra("qnStrList")
            qnValueList = intent.getIntArrayExtra("qnValueList")
            currentQuality = intent.getIntExtra("currentQuality", SharedPreferencesUtil.getInt("play_qn", 16))
        }

        return true
    }

    @SuppressLint("SimpleDateFormat")
    override fun onCreate(savedInstanceState: Bundle?) {
        Logu.v("加载", "加载")
        super.onCreate(savedInstanceState)

        // 横屏模式（ui_landscape）已删除（#2），仅保留播放器自身的"默认横屏"偏好
        screen_landscape = SharedPreferencesUtil.getBoolean("player_autolandscape", false)
        if (SharedPreferencesUtil.getBoolean("dev_player_rotate_software", false) && screen_landscape) {
            MsgUtil.showMsg("不支持默认横屏！")
            screen_landscape = false
        } else {
            setRequestedOrientation(if (screen_landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        }

        setContentView(R.layout.activity_player)
        findview()
        if (!getExtras()) {
            finish()
            return
        }

        initUI()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE, false)) {
            initMediaSession()
        }

        IjkMediaPlayer.loadLibrariesOnce(null)

        ijkPlayer = IjkMediaPlayer()

        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        batteryView!!.setPower(batteryManager!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))

        startDanmakuThermalControl()

        loop_enabled = SharedPreferencesUtil.getBoolean("player_loop", false)
        // 从设置读取听视频模式的默认值
        isAudioOnlyMode = SharedPreferencesUtil.getBoolean("player_audio_only", false)
        // 从Intent读取是否为仅音频模式（用于播放本地音频文件）
        isLocalAudioFile = getIntent().getBooleanExtra("audio_only", false)
        if (isLocalAudioFile) {
            isAudioOnlyMode = true
        }
        img_loading!!.setImageResource(R.drawable.loading_tv_shaking)
        anim_loading = img_loading!!.getDrawable() as AnimationDrawable
        anim_loading!!.start()

        val cachepath = cacheDir
        if (!cachepath.exists())
            cachepath.mkdirs()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mainHandler = Handler(Looper.getMainLooper())

        setVideoGestures()
        autohideReset()

        initSeekbars()

        if (isLiveMode) {
            btn_control!!.visibility = View.GONE // 直播模式隐藏暂停按钮，使用GONE而不是INVISIBLE以保持UI布局正确
            seekbar_progress!!.visibility = View.GONE
            seekbar_progress!!.isEnabled = false
            streamDanmaku(null) // 用来初始化一下弹幕层
            // danmuSocketConnect();
            // 先把弹幕连接注释掉
        }

        layout_control!!.postDelayed(Runnable { CenterThreadPool.run(Runnable { // 等界面加载完成
            if (isLiveMode) {
                runOnUiThread {
                    btn_menu!!.visibility = View.GONE
                    // 直播模式隐藏清晰度按钮
                    btn_quality!!.visibility = View.GONE
                    // 直播模式隐藏循环按钮
                    btn_loop!!.visibility = View.GONE
                    // 直播模式隐藏听视频模式按钮
                    btn_audio_only!!.visibility = View.GONE
                    // 直播模式隐藏自动下一个按钮
                    btn_auto_next!!.visibility = View.GONE
                    // 直播模式隐藏分P选择器按钮
                    btn_page_selector!!.visibility = View.GONE
                }
                setDisplay()
                return@Runnable
            }

            runOnUiThread {
                loading_text0!!.text = "装填弹幕中"
                loading_text1!!.text = "(≧∇≦)"
            }
            if (isOnlineVideo) {
                danmakuFile = File(cachepath, "danmaku.xml")
                downdanmu()
            } else {
                runOnUiThread { btn_danmaku_send!!.visibility = View.GONE }
                danmakuFile = File(danmaku_url)
                if (danmakuFile!!.exists()) {
                    streamDanmaku(danmakuFile.toString())
                } else if (danmakuController != null) {
                    danmakuController!!.hasDanmaku = false
                }
            }

            if (!destroyed && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true))
                downSubtitle(false)

            // 加载高能进度条数据
            if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                loadHighEnergyData()
            }

            if (!destroyed && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                loadViewPoints()
            }

            if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                loadInteractionVideo()
            }

            if (!destroyed)
                setDisplay()
        }) }, 60)
    }

    private fun findview() {
        layout_control = findViewById(R.id.control_layout)
        layout_top = findViewById(R.id.top)
        right_control = findViewById(R.id.right_control)
        right_second = findViewById(R.id.right_second)
        layout_card_bg = findViewById(R.id.card_bg)
        card_subtitle = findViewById(R.id.subtitle_card)
        card_danmaku_send = findViewById(R.id.danmaku_send_card)
        card_time_jump = findViewById(R.id.time_jump_card)
        time_jump_input = findViewById(R.id.time_jump_input)
        card_page_selector = findViewById(R.id.page_selector_card)
        card_quality_selector = findViewById(R.id.quality_selector_card)
        card_viewpoint_selector = findViewById(R.id.viewpoint_selector_card)
        layout_audio_only = findViewById(R.id.audio_only_layout)

        loading_info = findViewById(R.id.loading_info)

        img_loading = findViewById(R.id.circle)
        text_progress = findViewById(R.id.text_progress)
        text_online = findViewById(R.id.text_online)
        btn_danmaku = findViewById(R.id.danmaku_btn)
        btn_loop = findViewById(R.id.loop_btn)
        btn_rotate = findViewById(R.id.rotate_btn)
        btn_menu = findViewById(R.id.menu_btn)
        btn_danmaku_send = findViewById(R.id.danmaku_send_btn)
        btn_subtitle = findViewById(R.id.subtitle_btn)
        btn_audio_only = findViewById(R.id.audio_only_btn)
        btn_control = findViewById(R.id.button_video)
        btn_page_selector = findViewById(R.id.button_page_selector)
        btn_auto_next = findViewById(R.id.auto_next_btn)
        btn_quality = findViewById(R.id.button_quality)
        btn_viewpoint = findViewById(R.id.viewpoint_btn)
        seekbar_progress = findViewById(R.id.videoprogress)
        loading_text0 = findViewById(R.id.loading_text0)
        loading_text1 = findViewById(R.id.loading_text1)
        text_title = findViewById(R.id.text_title)
        text_volume = findViewById(R.id.showsound)
        layout_video = findViewById(R.id.videoArea)
        mDanmakuView = findViewById<View>(R.id.sv_danmaku) as IDanmakuView
        batteryView = findViewById(R.id.battery)

        text_speed = findViewById(R.id.text_speed)
        text_speed_hint = findViewById(R.id.text_speed_hint)
        layout_speed = findViewById(R.id.layout_speed)
        seekbar_speed = findViewById(R.id.seekbar_speed)
        text_newspeed = findViewById(R.id.text_newspeed)
        bottom_buttons = findViewById(R.id.bottom_buttons)
        btn_debug = findViewById(R.id.btn_debug)

        text_subtitle = findViewById(R.id.text_subtitle)
        text_audio_title = findViewById(R.id.audio_title)
        text_audio_subtitle = findViewById(R.id.audio_subtitle)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setVideoGestures() {
        val doubleTapSeekEnabled = SharedPreferencesUtil.getBoolean("player_doubletap_seek", false)
        val doubleTapSeekSeconds = SharedPreferencesUtil.getInt("player_doubletap_seek_seconds", 10)

        if (doubleTapSeekEnabled) {
            doubleTapGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (ijkPlayer != null && isPrepared && !isLiveMode) {
                        val x = e.getX()
                        val viewWidth = layout_control!!.getWidth().toFloat()
                        val currentPosition = ijkPlayer!!.getCurrentPosition()
                        val seekOffset = doubleTapSeekSeconds * 1000L

                        gesture_click_disabled = true

                        if (x > viewWidth / 2.0f) {
                            var newPosition = currentPosition + seekOffset
                            val duration = ijkPlayer!!.getDuration()
                            if (newPosition > duration) {
                                newPosition = duration
                            }
                            seekToPosition(newPosition)
                        } else {
                            var newPosition = currentPosition - seekOffset
                            if (newPosition < 0) {
                                newPosition = 0
                            }
                            seekToPosition(newPosition)
                        }
                        return true
                    }
                    return false
                }
            })
        }

        if (SharedPreferencesUtil.getBoolean("player_scale", true)) {
            scaleGestureListener = ViewScaleGestureListener(layout_video!!)
            scaleGestureDetector = ScaleGestureDetector(this, scaleGestureListener!!)

            val doublemove_enabled = SharedPreferencesUtil.getBoolean("player_doublemove", true)

            layout_control!!.setOnTouchListener { v, event ->
                if (doubleTapSeekEnabled && doubleTapGestureDetector != null) {
                    doubleTapGestureDetector!!.onTouchEvent(event)
                }
                val action = event.getActionMasked()
                val pointerCount = event.getPointerCount()
                val singleTouch = pointerCount == 1
                val doubleTouch = pointerCount == 2

                // Logu.v("gesture", event.getEventTime() + "");
                scaleGestureDetector!!.onTouchEvent(event)
                val gesture_scaling = scaleGestureListener!!.scaling

                if (!gesture_scaled && gesture_scaling)
                    gesture_scaled = true

                // Logu.v("gesture", (scaling ? "scaled-yes" : "scaled-no"));

                when (action) {
                    MotionEvent.ACTION_MOVE -> {
                        if (singleTouch) {
                            if (gesture_scaling) {
                                videoMoveBy(0f, 0f) // 防止单指缩放出框
                            } else if (!(gesture_scaled && !doublemove_enabled)) {
                                val currentX = event.getX(0) // 单指移动
                                val currentY = event.getY(0)
                                val deltaX = currentX - previousX
                                val deltaY = currentY - previousY
                                if (deltaX != 0f || deltaY != 0f) {
                                    videoMoveBy(deltaX, deltaY)
                                    previousX = currentX
                                    previousY = currentY
                                }
                            }
                        }
                        if (doubleTouch && doublemove_enabled) {
                            val currentX = (event.getX(0) + event.getX(1)) / 2
                            val currentY = (event.getY(0) + event.getY(1)) / 2
                            val deltaX = currentX - previousX
                            val deltaY = currentY - previousY
                            if (deltaX != 0f || deltaY != 0f) {
                                videoMoveBy(deltaX, deltaY)
                                previousX = currentX
                                previousY = currentY
                            }
                        }
                    }

                    MotionEvent.ACTION_DOWN -> {
                        if (singleTouch) { // 如果是单指按下，设置起始位置为当前手指位置
                            previousX = event.getX(0)
                            previousY = event.getY(0)
                            // Logu.v("gesture", "touch_start:" + previousX + "," + previousY);
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (doubleTouch) { // 如果是双指按下，设置起始位置为两指连线的中心点
                            previousX = (event.getX(0) + event.getX(1)) / 2
                            previousY = (event.getY(0) + event.getY(1)) / 2
                            // Logu.v("gesture","double_touch");
                        }
                    }

                    MotionEvent.ACTION_POINTER_UP -> {
                        if (doubleTouch) {
                            val index = event.getActionIndex() // actionIndex是抬起来的手指位置
                            previousX = event.getX((if (index == 0) 1 else 0))
                            previousY = event.getY((if (index == 0) 1 else 0))
                            // Logu.v("gesture","single_touch");
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (onLongClick) {
                            onLongClick = false
                            val normalSpeed = speed_values[seekbar_speed!!.getProgress()] // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                            if (ijkPlayer != null)
                                ijkPlayer!!.setSpeed(normalSpeed) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                            if (danmakuReady())
                                danmakuController!!.setSpeed(normalSpeed) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                            text_speed!!.text = speed_strs[seekbar_speed!!.getProgress()] // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                            hideSpeedHint()
                        }
                        // #2：轻点命中"带时间戳的弹幕"时直接跳转（无任何提示）。
                        // 位置判断必须在重置 gesture_moved / gesture_scaled 之前，
                        // 否则拖动结束的那一下会被误判成点击。
                        if (tryJumpByDanmakuTap(event))
                            gesture_click_disabled = true
                        if (gesture_moved)
                            gesture_moved = false
                        if (gesture_scaled)
                            gesture_scaled = false
                    }
                }

                if (!gesture_click_disabled && (gesture_moved || gesture_scaled)) {
                    gesture_click_disabled = true
                    hidecon.run()
                }

                false
            }
        } else {
            layout_control!!.setOnTouchListener { view, motionEvent ->
                if (doubleTapSeekEnabled && doubleTapGestureDetector != null) {
                    doubleTapGestureDetector!!.onTouchEvent(motionEvent)
                }
                if (motionEvent.getAction() == MotionEvent.ACTION_UP && onLongClick) {
                    onLongClick = false
                    val normalSpeed = speed_values[seekbar_speed!!.getProgress()] // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    if (ijkPlayer != null)
                        ijkPlayer!!.setSpeed(normalSpeed) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    if (danmakuReady())
                        danmakuController!!.setSpeed(normalSpeed) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    text_speed!!.text = speed_strs[seekbar_speed!!.getProgress()] // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    hideSpeedHint()
                }
                // #2：未开启视频缩放时同样支持点击时间戳弹幕跳转
                if (motionEvent.getAction() == MotionEvent.ACTION_UP && tryJumpByDanmakuTap(motionEvent))
                    gesture_click_disabled = true
                false
            }
        }

        // 这个管普通点击
        layout_control!!.setOnClickListener { view ->
            if (gesture_click_disabled)
                gesture_click_disabled = false
            else
                clickUI()
        }
        // 这个管长按开始
        layout_control!!.setOnLongClickListener { view ->
            if (SharedPreferencesUtil.getBoolean("player_longclick", true) && ijkPlayer != null && isPlaying &&
                    !isLiveMode) {
                if (!onLongClick && !gesture_click_disabled) {
                    hidecon.run()
                    if (ijkPlayer != null)
                        ijkPlayer!!.setSpeed(LONG_CLICK_SPEED) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    if (danmakuReady())
                        danmakuController!!.setSpeed(LONG_CLICK_SPEED) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    text_speed!!.text = "x " + LONG_CLICK_SPEED // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    // 长按倍速时在视频上方显示半透明提示框（#7）
                    showSpeedHint(LONG_CLICK_SPEED)
                    onLongClick = true
                    Logu.v("gesture", "longclick_down")
                    return@setOnLongClickListener true
                }
                return@setOnLongClickListener false
            }
            false
        }

    }

    /**
     * 轻点时命中"带时间戳的弹幕"则直接跳转（#2）。
     *
     * 返回 true 表示已消费这次点击（调用方应阻止随后的 clickUI 展开/收起控制栏）。
     * 弹幕层被"弹幕按钮"关掉时不参与命中，避免关掉弹幕后还能点到看不见的弹幕。
     */
    private fun tryJumpByDanmakuTap(event: MotionEvent): Boolean {
        if (destroyed || !isPrepared || isLiveMode) return false
        if (gesture_moved || gesture_scaled) return false
        if (!danmakuReady() || !isDanmakuVisible()) return false
        if (!danmakuController!!.timeJumpEnabled) return false

        // 触摸坐标属于 layout_control，弹幕视图在圆屏模式（player_ui_round）下会被
        // 加上上下边距，两者不一定重合，这里换算成弹幕视图自身的坐标系。
        val danmakuView = mDanmakuView?.getView() ?: return false
        val x = event.getX() + layout_control!!.getLeft() - danmakuView.getLeft()
        val y = event.getY() + layout_control!!.getTop() - danmakuView.getTop()

        val timeMs = danmakuController!!.findTimestampAt(x, y)
        if (timeMs <= 0) return false
        jumpToDanmakuTimestamp(timeMs)
        return true
    }

    /** 显示长按倍速的半透明提示框（#7） */
    @SuppressLint("SetTextI18n")
    private fun showSpeedHint(speed: Float) {
        val hint = text_speed_hint ?: return
        runOnUiThread {
            hint.text = "x " + speed
            hint.visibility = View.VISIBLE
        }
    }

    /** 隐藏长按倍速提示框 */
    private fun hideSpeedHint() {
        val hint = text_speed_hint ?: return
        runOnUiThread { hint.visibility = View.GONE }
    }

    /**
     * 启动弹幕帧率温控（#8）。
     *
     * 开关打开时忽略用户设置的固定弹幕刷新间隔，按温度在
     * 15 / 30 / 60 FPS 之间切换；关闭时沿用设置项。
     */
    private fun startDanmakuThermalControl() {
        val controller = DanmakuThermalController(this) { interval ->
            danmakuController?.thermalIntervalOverride = interval
            danmakuController?.updateFrameRate(interval)
        }
        danmakuThermalController = controller
        if (controller.isEnabled()) {
            // 首次先按当前温度定档，并同步给弹幕层
            val interval = controller.currentInterval()
            danmakuController?.thermalIntervalOverride = interval
            controller.start()
        } else {
            danmakuController?.thermalIntervalOverride = null
        }
    }

    /**
     * 弹出"跳转到指定时间"的输入框。
     *
     * 输入格式见 [parseTimeInput]：纯秒数、`分:秒`、`时:分:秒`，
     * 分隔符可用空格 / 中文冒号 / 英文冒号（#4）。
     *
     * 注意：这个方法只应由**左侧进度时间**与**右侧收缩栏的时间按钮**触发；
     * 底部收起状态下的时间文本现在也可以点击呼出时间跳转卡片（#3 新口径）。
     */
    private fun showTimeJumpDialog() {
        if (!isPrepared || isLiveMode) return
        val duration = try {
            ijkPlayer!!.getDuration()
        } catch (e: Exception) {
            0L
        }
        if (duration <= 0) {
            MsgUtil.showMsg("视频时长未知，暂时无法跳转")
            return
        }
        showTimeJumpInputDialog(duration)
    }

    /** 直接跳到弹幕里提到的时间（#2，点击蓝色下划线时间触发） */
    private fun jumpToDanmakuTimestamp(timeMs: Long) {
        if (!isPrepared || isLiveMode || timeMs <= 0) return
        val duration = try {
            ijkPlayer!!.getDuration()
        } catch (e: Exception) {
            0L
        }
        var target = timeMs
        if (target < 0) target = 0
        if (duration > 0 && target > duration) target = duration
        seekToPosition(target)
    }

    private fun showTimeJumpInputDialog(duration: Long) {
        val editText = EditText(this)
        editText.hint = "例如 90、1:30、5 30、1：02：03"
        editText.isSingleLine = true
        editText.inputType = InputType.TYPE_CLASS_TEXT
        editText.setTextColor(0xffffffff.toInt())
        editText.setHintTextColor(0x80ffffff.toInt())
        val container = FrameLayout(this)
        val pad = ToolsUtil.dp2px(12f)
        container.setPadding(pad, pad, pad, pad)
        container.addView(editText)

        AlertDialog.Builder(this)
            .setTitle("跳转到指定时间")
            .setView(container)
            .setPositiveButton("跳转") { _, _ ->
                val seconds = parseTimeInput(editText.text.toString())
                if (seconds == null) {
                    MsgUtil.showMsg("时间格式不对，示例：90、1:30、5 30、1：02：03")
                    return@setPositiveButton
                }
                var targetMs = seconds * 1000L
                if (targetMs < 0) targetMs = 0
                if (targetMs > duration) targetMs = duration
                seekToPosition(targetMs)
                MsgUtil.showMsg("已跳转到 " + StringUtil.toTime((targetMs / 1000).toInt()))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 解析用户输入的时间文本为秒数（#4）。
     *
     * 分隔符同时接受：**空格**、**中文冒号（：）**、**英文冒号（:）**，
     * 且可以混用。例如 `90`（秒）、`1:30`、`1：30`、`5 30`（= 5 分 30 秒）、
     * `01:02:03`（时:分:秒）都解析为总秒数；解析失败返回 null。
     *
     * 规则：按分隔符拆成若干段，从前往后按 60 进位累加
     * （最后一段是最低位的秒），因此 `5 30` 与 `5:30` 等价。
     */
    private fun parseTimeInput(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 空格（含多空格 / 制表符）+ 中文冒号 + 英文冒号 都作为分隔符
        val parts = text.split(Regex("[\\s:：]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 3) return null

        var total = 0L
        for (part in parts) {
            val value = part.trim().toLongOrNull() ?: return null
            if (value < 0) return null
            total = total * 60 + value
        }
        return total
    }

    /**
     * 统一刷新左下角进度时间文本（#1）。
     *
     * 形如 `当前时间/总时长`，其中总时长在未知时用 `00:00` 兜底（原来会拼出字符串
     * `null`）。直播模式下不显示总时长。
     *
     * 之所以抽成一个函数：进度文本此前只在"用户拖动进度条"这一条路径上被刷新，
     * 播放过程中的定时刷新完全没碰它，导致时间显示永远不动。
     */
    @SuppressLint("SetTextI18n")
    private fun updateProgressText(positionMs: Int) {
        val text = text_progress ?: return
        val current = StringUtil.toTime(positionMs / 1000)
        if (isLiveMode) {
            text.text = current
        } else {
            val total = progress_str ?: "00:00"
            text.text = "$current/$total"
        }
    }

    private fun autohideReset() {
        layout_control!!.removeCallbacks(hidecon)
        layout_control!!.postDelayed(hidecon, 4000)
    }

    private fun clickUI() {
        val now_timestamp = System.currentTimeMillis()
        if (now_timestamp - timestamp_click < 300) {
            // 双击 = 暂停/继续（#7）。原逻辑双击时先还原缩放、暂停/继续只在
            // “无缩放可还原”时触发，导致缩放状态下双击无法暂停。
            // 现在缩放的还原交给手势本身（缩回 1x 后 can_reset 自动变 false），
            // 双击无条件走暂停/继续。
            if (!isLiveMode) {
                if (isPlaying)
                    playerPause()
                else
                    playerResume()
                showcon()
            }
        } else {
            timestamp_click = now_timestamp
            if (layout_top!!.getVisibility() == View.GONE)
                showcon()
            else
                hidecon.run()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showcon() {
        right_control!!.visibility = View.VISIBLE
        layout_top!!.visibility = View.VISIBLE
        bottom_buttons!!.visibility = View.VISIBLE
        seekbar_progress!!.visibility = View.VISIBLE
        seekbar_progress!!.isEnabled = false
        seekbar_progress!!.postDelayed(progressbarEnable, 200)
        if (isPrepared && !isLiveMode && !isAudioOnlyMode) {
            text_speed!!.visibility = View.VISIBLE
            updateDebugButtonVisibility()
        }
        batteryView!!.setPower(batteryManager!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        if (screen_round) {
            text_progress!!.setGravity(Gravity.NO_GRAVITY)
            text_progress!!.setPadding(ToolsUtil.dp2px(24f), 0, 0, 0)
            if (onlineTimer != null)
                text_online!!.visibility = View.VISIBLE
        }

        autohideReset()
    }

    private val progressbarEnable = Runnable { seekbar_progress!!.isEnabled = true }

    private val hidecon = Runnable {
        right_control!!.visibility = View.GONE
        layout_top!!.visibility = View.GONE
        bottom_buttons!!.visibility = View.GONE
        seekbar_progress!!.visibility = View.GONE
        if (isPrepared && !isAudioOnlyMode) {
            text_speed!!.visibility = View.GONE
            btn_debug!!.visibility = View.GONE
        }
        if (screen_round) {
            text_progress!!.setGravity(Gravity.CENTER)
            text_progress!!.setPadding(0, 0, 0, ToolsUtil.dp2px(8f))
            if (onlineTimer != null)
                text_online!!.visibility = View.GONE
        }
        if (menu_opened)
            btn_menu!!.performClick()
    }

    private fun setDisplay() {
        Logu.v("创建播放器")
        Logu.v("url", video_url ?: "")

        runOnUiThread { loading_text0!!.text = "初始化播放" }

        if (isAudioOnlyMode) {
            ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1L) // 禁用视频
            ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 48L) // 跳过所有视频帧
            ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        } else {
            ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec",
                    (if (SharedPreferencesUtil.getBoolean("player_codec", true)) 1L else 0L))
            ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        }

        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                (if (SharedPreferencesUtil.getBoolean("player_audio", false)) 1L else 0L))
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 4L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1L)

        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets")
        // 断线自动重连：网络抖动时由 FFmpeg 层自行重连，而不是直接报错停播
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 0L)
        ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5L)

        // 这个坑死我！请允许我为解决此问题而大大地兴奋一下ohhhhhhhhhhhhhhhhhhhhhhhhhhhh
        // ijkplayer是自带一个useragent的，要把默认的改掉才能用！
        if (isOnlineVideo) {
            // 拉流策略（#11）：脉冲式=快速拉满缓冲→停→消耗到下限再拉；
            // 慢速持续=原行为（边播边平滑缓冲）
            if (StreamPolicyManager.isPulse()) {
                ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                // 大缓冲：一次多拉一些，随后解析线程停住，期间没有网络请求
                ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 40L * 1024 * 1024) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                // 缓冲的帧数下限：消耗到这个量之前不再读网络
                ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 600L) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                // 触底后立刻高速回填
                ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 1L) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
            } else {
                ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
            }
            // 缓冲区依据设备内存自适应：缓冲越足，网络抖动时越不容易卡住。
            // 但缓冲过大反而增加内存压力与耗电，所以按档位区分。
            ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size",
                    (if (SharedPreferencesUtil.getBoolean("player_low_latency", false))
                        5L * 1024 * 1024 // 低延迟优先，弱网更易卡
                    else
                        20L * 1024 * 1024)) // 默认：足以跨越数秒的网络抖动
            ijkPlayer!!.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", USER_AGENT_WEB)
            Logu.v("设置ua")
        }

        Logu.v("准备设置显示")
        if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) { // Texture
            Logu.v("使用texture模式")
            // 回调驱动：不再轮询mSurfaceTexture，避免退出后定时器仍在轮询并操作已销毁的Surface
            pendingPrepare = true
            if (mSurfaceTexture != null) {
                ijkPlayer!!.setSurface(Surface(mSurfaceTexture))
                startPrepareIfNeeded()
            }
        } else {
            Logu.v("使用surface模式")
            val surfaceHolder = surfaceView!!.getHolder() // Surface
            Logu.v("获取surfaceHolder成功！")
            // 回调驱动：SurfaceView首次创建必然回调surfaceCreated，无需轮询isCreating
            pendingPrepare = true

            // 卡死自动恢复会重新走 setDisplay，必须先摘掉旧回调，
            // 否则同一个 Holder 上会累积多份回调，反复触发 setDisplay。
            if (surfaceCallback != null) {
                try {
                    surfaceHolder.removeCallback(surfaceCallback)
                } catch (ignored: Exception) {
                }
                surfaceCallback = null
            }

            surfaceCallback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
                    if (!destroyed) {
                        Logu.v("surface", "重新设置Holder")
                        ijkPlayer!!.setDisplay(surfaceHolder)
                        startPrepareIfNeeded()
                        if (isPrepared) {
                            // #1：用播放器真实进度回 seek。之前用 SeekBar 的进度，
                            // 但 UI 隐藏期间进度条可能停在其被隐藏前的旧位置，
                            // surface 重建（旋转/切后台回来）就会把视频跳回旧时间
                            val resumePos = try {
                                ijkPlayer!!.getCurrentPosition()
                            } catch (ignored: Exception) {
                                seekbar_progress!!.getProgress().toLong()
                            }
                            if (resumePos > 0)
                                ijkPlayer!!.seekTo(resumePos)
                        }
                    }
                }

                override fun surfaceChanged(surfaceHolder: SurfaceHolder, i: Int, i1: Int, i2: Int) {}

                override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
                    Logu.v("surface", "Holder没了")
                    if (isPrepared && !destroyed)
                        ijkPlayer!!.setDisplay(null)
                }
            }
            surfaceHolder.addCallback(surfaceCallback)
            Logu.v("添加callback成功！")
            // 已创建过的holder直接触发（surfaceCreated不会再回调）
            if (surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
                ijkPlayer!!.setDisplay(surfaceHolder)
                startPrepareIfNeeded()
            }
        }
    }

    /**
     * 仅触发一次MPPrepare，退出后不再prepare，防止退出瞬间卡死在prepare流程。
     * surfaceCreated（主线程）与 setDisplay（异步任务）可能并发到达，需同步防双触发。
     */
    private fun startPrepareIfNeeded() {
        synchronized(this) {
            if (!pendingPrepare)
                return
            pendingPrepare = false
        }
        if (destroyed || ijkPlayer == null)
            return
        MPPrepare(video_url)
    }

    private fun MPPrepare(nowurl: String?) {
        if (destroyed || ijkPlayer == null)
            return
        ijkPlayer!!.setOnPreparedListener(this)

        if (isLiveMode) {
            runOnUiThread { loading_text0!!.text = "载入直播中" }
            danmuSocketConnect()
        } else
            runOnUiThread { loading_text0!!.text = "载入视频中" }
        try {
            if (isOnlineVideo) {
                // 实验室功能（#5）：WiFi 预加载命中时改走本地代理（前段本地秒开，
                // 超出预加载范围由代理继续向源站透传）。未命中/开关关闭返回 null，行为不变。
                val playUrl = WifiPreloadManager.proxyUrlFor(aid, cid, nowurl) ?: nowurl
                val headers = HashMap<String, String>()
                headers["Referer"] = "https://www.bilibili.com/"
                headers["Cookie"] = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!
                ijkPlayer!!.setDataSource(playUrl, headers)
            } else
                ijkPlayer!!.setDataSource(nowurl)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        if (destroyed) // 设置数据源期间可能已经退出
            return

        ijkPlayer!!.setOnCompletionListener {
            finishWatching = true

            if (destroyed) return@setOnCompletionListener
            if (interactionData != null && interactionData!!.edges != null &&
                    interactionData!!.edges!!.questions != null && !questionShown) {
                checkEndInteractionQuestions()
                if (questionShown) {
                    isPlaying = false
                    if (hasDanmaku() && danmakuReady()) {
                        danmakuController!!.pause()
                    }
                    btn_control!!.setImageResource(R.drawable.btn_player_play)
                    return@setOnCompletionListener
                }
            }

            if (loop_enabled) {
                ijkPlayer!!.seekTo(0L)
                if (hasDanmaku() && danmakuReady()) {
                    danmakuController!!.seekTo(0L)
                }
                ijkPlayer!!.start()
            } else if (auto_next_enabled && hasMultiplePages() && currentPageIndex < pagenames!!.size - 1) {
                switchToPage(currentPageIndex + 1)
            } else {
                isPlaying = false
                if (hasDanmaku() && danmakuReady()) {
                    danmakuController!!.pause()
                }
                btn_control!!.setImageResource(R.drawable.btn_player_play)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
                    updateMediaSessionPlaybackState()
                }
            }
        }

        ijkPlayer!!.setOnErrorListener { iMediaPlayer, what, extra ->
            val EReport = "播放器可能遇到错误！\n错误码：$what\n附加：$extra"
            Logu.e("ijk-err", EReport)
            // Toast.makeText(PlayerActivity.this, EReport, Toast.LENGTH_LONG).show();
            false
        }

        ijkPlayer!!.setOnBufferingUpdateListener { mp, percent ->
            // #1：percent 变化顺带校准总时长——部分流 prepare 完时 getDuration 仍是 0
            if (video_all <= 0 && percent > 0 && ijkPlayer != null) {
                val dur = try {
                    ijkPlayer!!.getDuration().toInt()
                } catch (ignored: Exception) {
                    0
                }
                if (dur > 0) {
                    video_all = dur
                    seekbar_progress!!.setMax(video_all)
                    progress_str = StringUtil.toTime(video_all / 1000)
                }
            }
            seekbar_progress!!.setSecondaryProgress(percent * video_all / 100)
        }

        if (isOnlineVideo || isLiveMode)
            ijkPlayer!!.setOnInfoListener { mp, what, extra ->
                if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    if (playbackWatchdog != null)
                        playbackWatchdog!!.onBufferingStart()
                    runOnUiThread {
                        loading_info!!.visibility = View.VISIBLE
                        anim_loading!!.start()
                        loading_text0!!.text = "正在缓冲"
                        showLoadingSpeed()
                        if (hasDanmaku() && danmakuReady() && isPlaying) {
                            danmakuController!!.pause()
                        }
                    }
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    if (playbackWatchdog != null) {
                        playbackWatchdog!!.onBufferingEnd()
                        // 缓冲恢复正常，清零自动恢复计数
                        playbackWatchdog!!.notifyHealthy()
                    }
                    runOnUiThread {
                        if (loadingTimer != null)
                            loadingTimer!!.cancel()
                        loading_info!!.visibility = View.GONE
                        anim_loading!!.stop()
                        if (hasDanmaku() && danmakuReady() && isPlaying) {
                            danmakuController!!.resume()
                        }
                    }
                }

                false
            }

        ijkPlayer!!.setScreenOnWhilePlaying(true)
        if (destroyed) { // prepareAsync前最后一次检查，避免退出后才开始异步prepare
            try {
                ijkPlayer!!.stop()
            } catch (ignored: IllegalStateException) {
            }
            ijkPlayer!!.release()
            ijkPlayer = null
            return
        }
        ijkPlayer!!.prepareAsync()
        Logu.v("开始准备")
    }

    @SuppressLint("SetTextI18n")
    override fun onPrepared(mediaPlayer: IMediaPlayer) {
        if (destroyed) {
            // Activity已退出但prepare刚完成：此时onDestroy可能已release过，这里安全释放并直接返回，
            // 避免对已释放的播放器继续操作导致native层卡死
            try {
                mediaPlayer.release()
            } catch (ignored: IllegalStateException) {
            }
            return
        }

        isPrepared = true
        video_all = ijkPlayer!!.getDuration().toInt()
        // #1：DASH 流刚 prepare 完 getDuration 可能返回 0/负值，
        // 会把进度条 max 设成 0（进度显示全乱、总时长 00:00 不动）。
        // 晚些再取一次；仍取不到就等缓冲回调里补设。
        if (video_all <= 0) {
            mainHandler!!.postDelayed({
                if (!destroyed && isPrepared && ijkPlayer != null) {
                    val dur = try {
                        ijkPlayer!!.getDuration().toInt()
                    } catch (ignored: Exception) {
                        0
                    }
                    if (dur > 0 && video_all <= 0) {
                        video_all = dur
                        seekbar_progress!!.setMax(video_all)
                        progress_str = StringUtil.toTime(video_all / 1000)
                    }
                }
            }, 1000)
        }

        // 播放就绪：启动/复位卡死看门狗（仅在线播放需要）
        if (isOnlineVideo || isLiveMode) {
            ensurePlaybackWatchdog()
            playbackWatchdog!!.setPrepared(true)
            playbackWatchdog!!.setUserWantsPlay(true)
        }

        changeVideoSize()

        if ((isLiveMode || hasDanmaku()) && danmakuReady()) {
            danmakuController!!.start()
        }
        if (SharedPreferencesUtil.getBoolean("player_ui_showDanmakuBtn", true)) {
            btn_danmaku!!.setOnClickListener { view ->
                if (!danmakuReady())
                    return@setOnClickListener
                // 当前可见则隐藏，隐藏则显示（以控制器的状态为准，避免两套状态不一致）
                val willShow = !isDanmakuVisible()
                if (willShow) {
                    // #6：重开弹幕时把"获取流程"从头走一遍（见 restartDanmakuFetch）。
                    // 弱网下弹幕层可能已经处于半死状态，单纯 show() 救不回来；
                    // 重建后重新拉取才是一次干净的复位。
                    restartDanmakuFetch(true)
                } else {
                    // #6：关闭时直接清屏，避免"按钮已关、弹幕还在屏幕上"
                    danmakuController!!.hideAndClear()
                }
                btn_danmaku!!.setImageResource(if (willShow) R.mipmap.danmakuon else R.mipmap.danmakuoff)
                SharedPreferencesUtil.putBoolean("pref_switch_danmaku", willShow)
            }
            // 按用户偏好初始化可见性
            val prefShow = SharedPreferencesUtil.getBoolean("pref_switch_danmaku", true)
            if (!prefShow) {
                danmakuController!!.hideAndClear()
                btn_danmaku!!.setImageResource(R.mipmap.danmakuoff)
            } else {
                danmakuController!!.show()
                btn_danmaku!!.setImageResource(R.mipmap.danmakuon)
            }
            btn_danmaku!!.visibility = View.VISIBLE
        } else
            btn_danmaku!!.visibility = View.GONE
        // 原作者居然把旋转按钮命名为danmaku_btn，也是没谁了...我改过来了 ----RobinNotBad
        // 他大抵是觉得能用就行

        if (!isLiveMode) {
            if (loop_enabled)
                btn_loop!!.setImageResource(R.mipmap.loopon)
            else
                btn_loop!!.setImageResource(R.mipmap.loopoff)
            btn_loop!!.setOnClickListener { view ->
                btn_loop!!.setImageResource((if (loop_enabled) R.mipmap.loopoff else R.mipmap.loopon))
                loop_enabled = !loop_enabled
            }
            btn_loop!!.visibility = View.VISIBLE

            // 听视频模式按钮
            // 如果是本地音频文件，隐藏听视频开关（因为已经是纯音频了）
            if (isLocalAudioFile) {
                btn_audio_only!!.visibility = View.GONE
            } else {
                updateAudioOnlyButton()
                btn_audio_only!!.setOnClickListener { view -> toggleAudioOnlyMode() }
                btn_audio_only!!.visibility = View.VISIBLE
            }

            if (hasMultiplePages()) {
                btn_page_selector!!.visibility = View.VISIBLE
                btn_page_selector!!.setOnClickListener { view -> showPageSelectorCard() }
                btn_auto_next!!.visibility = View.VISIBLE
                updateAutoNextButton()
                btn_auto_next!!.setOnClickListener { view -> toggleAutoNext() }
            } else {
                btn_page_selector!!.visibility = View.GONE
                btn_auto_next!!.visibility = View.GONE
            }

            if (SharedPreferencesUtil.getBoolean("player_ui_showQualityBtn", true) && isOnlineVideo) {
                btn_quality!!.visibility = View.VISIBLE
                btn_quality!!.setOnClickListener { view -> showQualitySelectorCard() }
            } else {
                btn_quality!!.visibility = View.GONE
            }

            if (!SharedPreferencesUtil.getBoolean("player_ui_showPageBtn", true))
                btn_page_selector!!.visibility = View.GONE
        } else {
            // 直播模式下隐藏这些按钮
            btn_loop!!.visibility = View.GONE
            btn_audio_only!!.visibility = View.GONE
            btn_page_selector!!.visibility = View.GONE
            btn_auto_next!!.visibility = View.GONE
            btn_quality!!.visibility = View.GONE
        }

        seekbar_progress!!.setMax(video_all)
        progress_str = StringUtil.toTime(video_all / 1000)

        if (isAudioOnlyMode) {
            updateAudioOnlyUI()
        }

        // 自动恢复：无论偏好如何，都必须回到卡死前的进度
        if (pendingRecoverySeek > 0 && !isLiveMode) {
            val seekTarget = pendingRecoverySeek
            pendingRecoverySeek = -1L
            ijkPlayer!!.seekTo(seekTarget)
            if (hasDanmaku() && danmakuReady()) {
                danmakuController!!.seekTo(seekTarget)
            }
            Logu.d("恢复播放", "已跳回 " + seekTarget + "ms")
            runOnUiThread { MsgUtil.showMsg("已恢复播放") }
        }

        if (SharedPreferencesUtil.getBoolean("player_from_last", true) && !isLiveMode) {
            if (progress_history > 5) {
                ijkPlayer!!.seekTo(progress_history)
                if (hasDanmaku() && danmakuReady()) {
                    danmakuController!!.seekTo(progress_history)
                }
                Logu.d("进度跳转", progress_history.toString())
                runOnUiThread { MsgUtil.showMsg("已从上次的位置播放") }
            }
        }

        loading_info!!.visibility = View.GONE
        anim_loading!!.stop()
        isPlaying = true
        btn_control!!.setImageResource(R.drawable.btn_player_pause)

        text_speed!!.visibility = layout_top!!.getVisibility()
        if (isLiveMode)
            text_speed!!.visibility = View.GONE
        text_speed!!.setOnClickListener { view -> layout_speed!!.visibility = View.VISIBLE }
        // 点击进度时间区域（#3 反馈修正）：与点击视频其他区域一样呼出进度条控制界面，
        // 不再弹时间跳转小窗。收起状态与展开状态点击行为一致（都走 clickUI 语义）。
        text_progress!!.setOnClickListener { view ->
            if (!isPrepared || isLiveMode) return@setOnClickListener
            if (layout_top!!.getVisibility() == View.GONE) showcon()
            else clickUI()
        }
        layout_speed!!.setOnClickListener { view -> layout_speed!!.visibility = View.GONE }

        btn_debug!!.setOnClickListener { view -> showInteractionDebugDialog() }
        updateDebugButtonVisibility()

        progressChange()
        onlineChange()

        ijkPlayer!!.start()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionMetadata()
            updateMediaSessionPlaybackState()
        }

        btn_control!!.setOnClickListener { view -> controlVideo() }
        btn_subtitle!!.setOnClickListener { view -> CenterThreadPool.run(Runnable { downSubtitle(true) }) }
    }

    @SuppressLint("SetTextI18n")
    private fun showLoadingSpeed() {
        // 原实现每次缓冲都 new 一个 Timer 却不取消上一个，反复缓冲会残留多个定时器
        // 持续跑（耗电 + 发热 + 泄漏）。这里先取消再重建。
        if (loadingTimer != null) {
            loadingTimer!!.cancel()
            loadingTimer = null
        }
        if (destroyed || ijkPlayer == null)
            return
        loadingTimer = Timer("loading-speed", true)
        loadingTimer!!.schedule(object : TimerTask() {
            override fun run() {
                if (destroyed)
                    return
                val player = ijkPlayer ?: return
                val text: String
                try {
                    text = String.format(Locale.CHINA, "%.1f", player.getTcpSpeed() / 1024f) + "KB/s"
                } catch (e: Exception) {
                    return
                }
                runOnUiThread {
                    if (!destroyed)
                        loading_text1!!.text = text
                }
            }
        }, 0, 500)
    }

    private fun changeVideoSize() {
        if (!isPrepared || ijkPlayer == null)
            return
        val width = ijkPlayer!!.getVideoWidth() // quality-allow 回退保留的旧播放器代码，调用点前已有判空
        val height = ijkPlayer!!.getVideoHeight() // quality-allow 回退保留的旧播放器代码，调用点前已有判空
        Logu.v("screen", "$screen_width x$screen_height")
        Logu.v("video", "$width x$height")

        // 在听视频模式下，视频宽高可能为0，跳过尺寸调整
        if (width == 0 || height == 0) {
            Logu.v("视频尺寸", "视频宽高为0，跳过尺寸调整（可能处于听视频模式）")
            return
        }

        if (SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
            val video_mul = height.toFloat() / width.toFloat()
            val sqrt = Math.sqrt(screen_width * screen_width / ((height * height).toDouble() / (width * width) + 1))
            video_height = (sqrt * video_mul + 0.5).toInt()
            video_width = (sqrt + 0.5).toInt()
        } else {
            val width_case1 = width * screen_height / height
            val height_case2 = height * screen_width / width

            if (width_case1 <= screen_width) {
                video_width = width_case1
                video_height = screen_height
            } else {
                video_width = screen_width
                video_height = height_case2
            }
        }

        runOnUiThread {
            layout_video!!.layoutParams = RelativeLayout.LayoutParams(video_width, video_height)
            Logu.v("改变视频区域大小", "$video_width x$video_height")
            video_origX = (screen_width - video_width) / 2f
            video_origY = (screen_height - video_height) / 2f

            layout_video!!.postDelayed({
                layout_video!!.setX(video_origX)
                layout_video!!.setY(video_origY)
                Logu.v("改变视频位置", (((screen_width - video_width) / 2).toString() + "," + ((screen_height - video_height) / 2)))
            }, 60) // 别问为什么，问就是必须这么写，要等上面的绘制完成
        }
    }

    private fun progressChange() {
        // 自动恢复会重新触发 onPrepared，必须先取消旧的进度定时器，否则残留多个轮询线程
        if (progressTimer != null) {
            progressTimer!!.cancel()
            progressTimer = null
        }
        progressTimer = Timer("player-progress", true)
        val task: TimerTask = object : TimerTask() {
            @SuppressLint("SetTextI18n")
            override fun run() {
                if (!isPrepared || ijkPlayer == null)
                    return

                val position: Int
                try {
                    position = ijkPlayer!!.getCurrentPosition().toInt()
                } catch (e: Exception) {
                    return
                }

                // 卡死看门狗需要持续的位置输入（暂停时也喂，避免恢复播放瞬间被误判）
                if (playbackWatchdog != null)
                    playbackWatchdog!!.onPosition(position.toLong())

                if (!isPlaying || isSeeking)
                    return
                if (video_now_last == position)
                    return // 进度没变化，不做任何 UI 更新

                // 性能优化（#20）+ 时间同步修复（#1）：界面不可见时降低刷新频率
                // 但不停止——之前直接跳过 UI 更新，导致 SeekBar 停在旧位置：
                // 呼出控制条时看到的是过期进度（要等下一个 tick 才同步），
                // surfaceCreated 回 seek 时用的也是旧值（视频"偶尔跳转"）。
                // 现在隐藏时也每 4 个 tick（约 1s）刷新一次进度条，开销可忽略。
                val uiVisible = bottom_buttons!!.getVisibility() == View.VISIBLE ||
                        (subtitles != null) || isLiveMode ||
                        (viewPointAdapter != null && viewPoints != null && !viewPoints!!.isEmpty())
                if (!uiVisible) {
                    video_now_last = position
                    video_now = position
                    if (++hiddenTickCount >= 4) {
                        hiddenTickCount = 0
                        runOnUiThread {
                            if (destroyed)
                                return@runOnUiThread
                            if (!isLiveMode)
                                updateProgressText(position)
                            seekbar_progress!!.setProgress(position)
                        }
                    }
                    return
                }
                hiddenTickCount = 0

                video_now_last = position
                video_now = position
                val curr_sec = position / 1000f

                // 字幕在子线程算好，避免主线程做二分校正
                val subtitleText = if (subtitles != null) computeSubtitle(curr_sec + subtitle_delta) else null
                val subtitleVisible = subtitleText != null
                val updateOnline = isLiveMode

                runOnUiThread {
                    if (destroyed)
                        return@runOnUiThread
                    if (updateOnline) {
                        text_progress!!.text = StringUtil.toTime(curr_sec.toInt())
                        text_online!!.text = online_number
                    } else {
                        // #1：时间文本必须在这里直接刷新。
                        // 原实现把文字更新放在进度条的 onProgressChanged 里、且只在
                        // fromUser（用户拖动）时才写，而这里是程序 setProgress，
                        // 走的是 !fromUser 分支直接 return —— 于是播放中时间文本
                        // 一直停在布局初始值 00:00/00:00，这就是"时间不动"的根因。
                        updateProgressText(position)
                        seekbar_progress!!.setProgress(position)
                    }
                    // 避免每 250ms 都调用 setVisibility（会触发无谓的 requestLayout）
                    if (subtitleVisible) {
                        text_subtitle!!.text = subtitleText
                        if (text_subtitle!!.getVisibility() != View.VISIBLE)
                            text_subtitle!!.visibility = View.VISIBLE
                    } else if (text_subtitle!!.getVisibility() != View.GONE) {
                        text_subtitle!!.visibility = View.GONE
                    }
                }

                if (viewPointAdapter != null && viewPoints != null && !viewPoints!!.isEmpty()) {
                    viewPointAdapter!!.updateCurrentPosition(curr_sec.toInt())
                }

                // MediaSession 每秒更新一次即可：原实现 250ms 更新一次，
                // 每次都要跨进程构造 PlaybackState，是持续的额外开销（发热/耗电）。
                val now = System.currentTimeMillis()
                if (now - mediaSessionUpdateAt >= 1000) {
                    mediaSessionUpdateAt = now
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
                        updateMediaSessionPlaybackState()
                    }
                }
            }
        }
        progressTimer!!.schedule(task, 0, 250)
    }

    private var mediaSessionUpdateAt = 0L

    /**
     * 计算当前应显示的字幕文本（纯计算，可在子线程执行）。
     * 原实现在子线程直接操作 View，这里拆开以保证线程安全。
     */
    private fun computeSubtitle(curr_sec: Float): String? {
        if (subtitles == null || subtitle_count == 0)
            return null

        var subtitle_curr = subtitles!![subtitle_curr_index]
        var need_adjust = true
        var need_show = true

        while (need_adjust) {
            if (curr_sec < subtitle_curr!!.from) {
                if (subtitle_curr_index != 0 && curr_sec < subtitles!![subtitle_curr_index - 1]!!.to) {
                    subtitle_curr_index--
                    subtitle_curr = subtitles!![subtitle_curr_index]
                } else {
                    need_adjust = false
                    need_show = false
                }
            } else if (curr_sec > subtitle_curr!!.to) {
                if (subtitle_curr_index + 1 < subtitle_count && curr_sec > subtitles!![subtitle_curr_index + 1]!!.from) {
                    subtitle_curr_index++
                    subtitle_curr = subtitles!![subtitle_curr_index]
                } else {
                    need_adjust = false
                    need_show = false
                }
            } else
                need_adjust = false
        }

        return if (need_show) subtitles!![subtitle_curr_index]!!.content else null
    }

    private fun onlineChange() {
        if (!SharedPreferencesUtil.getBoolean("player_show_online", false) || isLiveMode || aid == 0L || cid == 0L)
            return

        if (onlineTimer != null) {
            onlineTimer!!.cancel()
            onlineTimer = null
        }
        onlineTimer = Timer("player-online", true)
        val task: TimerTask = object : TimerTask() {
            @SuppressLint("SetTextI18n")
            override fun run() {
                if (ijkPlayer != null && !destroyed) {
                    try {
                        online_number = VideoInfoApi.getWatching(aid, cid)
                        runOnUiThread {
                            if (!online_number.isEmpty())
                                text_online!!.text = online_number + "人在看"
                            else
                                text_online!!.text = ""
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            MsgUtil.err(e)
                            text_online!!.visibility = View.GONE
                        }
                        this.cancel()
                    }
                }
            }
        }
        onlineTimer!!.schedule(task, 0, 5000)
    }

    private fun getSubtitle(subtitle_url: String?) {
        if (subtitle_url == null || subtitle_url.isEmpty())
            return
        try {
            if (isOnlineVideo)
                subtitles = PlayerApi.getSubtitle(subtitle_url)
            else
                subtitles = PlayerApi.getSubtitle(File(subtitle_url))

            if (subtitles == null)
                return

            subtitle_count = subtitles!!.size
            subtitle_curr_index = 0
            runOnUiThread { btn_subtitle!!.setImageResource(R.mipmap.subtitle_on) }
        } catch (e: Exception) {
            MsgUtil.err(e)
        }
    }

    private var subtitle_selected = -1

    private fun downSubtitle(from_btn: Boolean) {
        try {
            if (subtitleLinks == null) { // 首次运行，获取字幕
                if (isOnlineVideo)
                    subtitleLinks = PlayerApi.getSubtitleLinks(aid, cid)
                else
                    subtitleLinks = PlayerApi.getSubtitleLinks(File(danmakuFile!!.getParentFile(), "subtitles"))
            }

            if (subtitleLinks!!.size == 1) {
                if (from_btn)
                    MsgUtil.showMsg("本视频无字幕")
                return
            }

            subtitle_delta = SharedPreferencesUtil.getFloat("player_subtitle_delta", 0.3f)

            val ai_not_only = subtitleLinks!!.size > 2 || (subtitleLinks!!.size == 2 && !subtitleLinks!![0]!!.isAI)
            val ai_allowed = from_btn || SharedPreferencesUtil.getBoolean("player_subtitle_ai_allowed", false)

            if (ai_not_only || ai_allowed) {
                if (subtitle_selected == -1)
                    subtitle_selected = subtitleLinks!!.size

                runOnUiThread {
                    val subtitleRecycler = findViewById<RecyclerView>(R.id.subtitle_list)
                    val adapter = SubtitleAdapter()
                    adapter.setData(subtitleLinks)
                    adapter.setSelectedItemIndex(subtitle_selected)
                    adapter.setOnItemClickListener { index ->
                        layout_card_bg!!.visibility = View.GONE
                        card_subtitle!!.visibility = View.GONE
                        // #4：越界/并发防护——点选瞬间列表可能已被切换视频清空
                        val links = subtitleLinks
                        if (index < 0 || links == null || index >= links.size) return@setOnItemClickListener
                        subtitle_selected = index

                        if (links[index]!!.id == -1L) {
                            subtitles = null
                            btn_subtitle!!.setImageResource(R.mipmap.subtitle_off)
                        } else
                            CenterThreadPool.run(Runnable { getSubtitle(links[index]!!.url) })
                    }
                    subtitleRecycler
                            .layoutManager = CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false)
                    subtitleRecycler.setHasFixedSize(true)
                    subtitleRecycler.adapter = adapter
                    layout_card_bg!!.visibility = View.VISIBLE
                    card_subtitle!!.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            MsgUtil.err(e)
        }
    }

    /**
     * 创建并准备弹幕层（委托给 Kotlin 的 PlayerDanmakuController）。
     *
     * @param danmakuFile 本地 XML 弹幕文件，可为 null
     */
    private fun streamDanmaku(danmakuFile: String?): Boolean {
        if (mDanmakuView == null || danmakuController == null && !ensureDanmakuController())
            return false
        return danmakuController!!.startLocalOrEmpty(danmakuFile)
    }

    // ------------------------------------------------------------------
    // 播放卡死自动恢复（网络波动导致“一直缓冲 / 进度不动”时兜底）
    // ------------------------------------------------------------------

    /**
     * 创建卡死看门狗。检测到播放假死时重建数据源并恢复到原来的进度。
     *
     * 之所以需要它：ijkplayer 在网络抖动时会卡在读取内核里，既不回调 onError，
     * 也不再推进进度，用户只能退出重进。这里做自动恢复，把“必须手动重开”
     * 变成“停顿几秒后自己继续”。
     */
    private fun ensurePlaybackWatchdog() {
        if (playbackWatchdog != null)
            return
        playbackWatchdog = PlaybackWatchdog(object : PlaybackWatchdog.Listener {
            override fun onPlaybackStalled(stalledMs: Long, whileBuffering: Boolean, attempt: Int) {
                if (destroyed)
                    return
                Logu.w("播放卡死", "卡死 " + stalledMs + "ms（buffering=" + whileBuffering + "），第 " + attempt + " 次自动恢复")
                runOnUiThread {
                    if (!destroyed)
                        MsgUtil.showMsg("网络波动，正在自动恢复播放…")
                }
                recoverPlayback()
            }

            override fun onRecoveryExhausted() {
                if (destroyed)
                    return
                runOnUiThread {
                    if (!destroyed) {
                        loading_info!!.visibility = View.GONE
                        anim_loading!!.stop()
                        MsgUtil.showMsgLong("网络似乎不太稳定，请检查网络后重试")
                    }
                }
            }
        })
        playbackWatchdog!!.start()
    }

    /**
     * 重建播放器数据源并恢复到当前进度。
     *
     * 这是“卡住无法播放”的核心修复：直接重建 IjkMediaPlayer，
     * 复用同一个 Surface，并把进度 seek 回原位置继续播放。
     */
    private fun recoverPlayback() {
        if (destroyed || ijkPlayer == null)
            return

        val resumePosition: Long
        val wasPlaying = isPlaying
        try {
            resumePosition = ijkPlayer!!.getCurrentPosition()
        } catch (e: Exception) {
            return
        }

        CenterThreadPool.run(Runnable {
            if (destroyed)
                return@Runnable
            try {
                runOnUiThread {
                    if (destroyed)
                        return@runOnUiThread
                    try {
                        ijkPlayer!!.stop()
                    } catch (ignored: Exception) {
                    }
                    try {
                        ijkPlayer!!.release()
                    } catch (ignored: Exception) {
                    }
                    ijkPlayer = IjkMediaPlayer()

                    // 复位状态，让 setDisplay 重新走一遍 prepare 流程
                    isPrepared = false
                    isPlaying = false
                    progress_history = if (resumePosition > 0) resumePosition else progress_history
                    // 恢复播放必须回到原进度，不受“从历史位置播放”这一偏好的影响
                    pendingRecoverySeek = if (resumePosition > 0) resumePosition else -1L

                    if (playbackWatchdog != null) {
                        playbackWatchdog!!.setPrepared(false)
                        playbackWatchdog!!.setUserWantsPlay(false)
                    }

                    loading_info!!.visibility = View.VISIBLE
                    anim_loading!!.start()
                    loading_text0!!.text = "正在恢复播放"

                    // 重新绑定 Surface 并 prepare（进度通过 progress_history 恢复）
                    setDisplay()
                }
            } catch (e: Exception) {
                Logu.e("播放卡死", "自动恢复失败: " + e.message)
            }
        })
    }

    /** 释放弹幕层（切P/切清晰度/退出时调用），控制器与视图一并清理 */
    private fun releaseDanmakuLayer() {
        if (danmakuController != null) {
            danmakuController!!.release()
            danmakuController = null
        }
        if (mDanmakuView != null) {
            try {
                mDanmakuView!!.release()
            } catch (ignored: Exception) {
            }
            mDanmakuView = null
        }
    }

    /**
     * 把弹幕获取流程从头走一遍（#6）。
     *
     * 触发场景：
     * 1. 用户把弹幕按钮关掉再打开（最常见的手动"救活"手段）；
     * 2. 从后台切回前台时发现弹幕层已不健康（弱网下绘制线程卡死）。
     *
     * 做三件事：
     * - **中断**上一次拉取（取消标志 + 补拉器），避免旧请求回来污染新弹幕层；
     * - **重建**弹幕层（release 旧控制器与视图，重新 findViewById）——
     *   绘制线程已经坏掉时，只有重建才能恢复；
     * - **重新拉取**全部弹幕分段（在线）或重新加载本地文件。
     *
     * 线程安排很重要：release() 会 join 弹幕绘制线程，弱网/卡死时可能阻塞数秒，
     * 绝不能在主线程做（那会变成新的"点一下卡半天"）。所以：
     * 主线程只负责立刻清屏，真正的释放与重建放到后台 + 回主线程重挂。
     *
     * @param showDanmaku 重建后是否让弹幕层可见（按钮点击路径恒为 true；
     *                    后台切回路径按用户偏好传入）
     */
    private fun restartDanmakuFetch(showDanmaku: Boolean) {
        if (destroyed) return
        if (danmakuRestarting) return
        danmakuRestarting = true

        // 立刻清屏：即便下面的重建失败，也不会留下"按钮关了弹幕还在"的残影
        try {
            danmakuController?.hideAndClear()
        } catch (ignored: Exception) {
        }

        CenterThreadPool.run(Runnable {
            // release 可能阻塞（join 绘制线程），必须在后台执行
            try {
                releaseDanmakuLayer()
            } catch (e: Exception) {
                Logu.w("弹幕", "重建弹幕层时释放失败: " + e.message)
            }
            runOnUiThread {
                if (destroyed) {
                    danmakuRestarting = false
                    return@runOnUiThread
                }
                rebuildDanmakuLayer(showDanmaku)
                danmakuRestarting = false
            }
        })
    }

    /** [restartDanmakuFetch] 的第二段：在主线程重新挂载弹幕层并重新拉取 */
    private fun rebuildDanmakuLayer(showDanmaku: Boolean) {
        // release 时已把视图引用置空，这里重新拿到
        mDanmakuView = findViewById<View>(R.id.sv_danmaku) as IDanmakuView

        // 直播模式：空弹幕层 + 立刻启动
        if (isLiveMode) {
            if (streamDanmaku(null) && danmakuReady()) {
                if (showDanmaku) danmakuController!!.show() else danmakuController!!.hideAndClear()
                danmakuController!!.start()
            }
            return
        }

        // 在线视频重新拉取全部分段；本地弹幕重新加载文件
        if (isOnlineVideo) {
            danmakuFile = File(cacheDir, "danmaku.xml")
            if (danmakuFile!!.exists()) {
                //noinspection ResultOfMethodCallIgnored
                danmakuFile!!.delete()
            }
            downdanmu()
        } else {
            val file = danmakuFile
            if (file != null && file.exists()) {
                streamDanmaku(file.toString())
            } else {
                streamDanmaku(danmaku_url)
            }
        }

        // 重建后弹幕层是全新的：恢复可见性，并把时间轴对齐到当前播放位置
        if (danmakuReady()) {
            if (showDanmaku) {
                danmakuController!!.show()
            } else {
                danmakuController!!.hideAndClear()
            }
            if (isPrepared && ijkPlayer != null) {
                val pos = try {
                    ijkPlayer!!.getCurrentPosition()
                } catch (e: Exception) {
                    0L
                }
                danmakuController!!.seekTo(pos)
                if (showDanmaku && isPlaying)
                    danmakuController!!.start()
            }
        }
    }

    /** 初始化弹幕控制器（只做一次），并注入播放器相关的能力；视图未就绪时返回 false */
    private fun ensureDanmakuController(): Boolean {
        if (mDanmakuView == null)
            return false
        if (danmakuController == null) {
            danmakuController = PlayerDanmakuController(
                    mDanmakuView,
                    {
                        try {
                            if (ijkPlayer != null) ijkPlayer!!.getCurrentPosition() else 0L
                        } catch (e: Exception) {
                            0L
                        }
                    },
                    { isPrepared },
                    { destroyed }
            )
            danmakuController!!.oldApiUrl = danmaku_url
            danmakuController!!.cacheFile = danmakuFile
            // #2：时间戳弹幕的视觉标识（蓝色下划线）与点击跳转都由控制器负责，
            // 这里不再注册任何会弹提示的回调（原来的巨大 Toast 已删除）。
        }
        danmakuController!!.buildContext()
        return true
    }

    /**
     * 加载弹幕（在线视频走新版 protobuf 接口，带重试与缺失分段后台补拉）。
     *
     * 修复点：逐段增量渲染 + 失败分段补拉，网络波动不再造成整段弹幕丢失。
     */
    private fun downdanmu() {
        if (danmaku_url == null || danmaku_url!!.isEmpty())
            return

        ensureDanmakuController()
        danmakuController!!.oldApiUrl = danmaku_url

        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NEW_DANMAKU_API, true)) {
            // 用户选择旧版接口
            danmakuController!!.startLocalOrEmpty(null)
            CenterThreadPool.run(Runnable {
                try {
                    if (danmakuFile == null)
                        danmakuFile = File(cacheDir, "danmaku.xml")
                    val file = OnlineDanmakuDownloader.download(danmaku_url, danmakuFile!!)
                    if (file != null && file.exists() && !destroyed) {
                        danmakuController!!.startLocal(file.getAbsolutePath())
                    }
                } catch (e: Exception) {
                    runOnUiThread { MsgUtil.err(e) }
                }
            })
            return
        }

        danmakuController!!.startOnline(aid, cid, {
            try {
                if (ijkPlayer != null) ijkPlayer!!.getDuration() else 0L
            } catch (e: Exception) {
                0L
            }
        }, { missingCount ->
            if (destroyed)
                return@startOnline
            if (missingCount > 0) {
                Logu.w("弹幕", "首次拉取缺少 " + missingCount + " 段，已转入后台补拉")
            }
        })
    }

    fun addDanmaku(text: String?, color: Int) {
        addDanmaku(text, color, 25, 1, 0)
    }

    fun addDanmaku(text: String?, color: Int, textSize: Int, type: Int, backgroundColor: Int) {
        if (danmakuController == null || text == null || ijkPlayer == null)
            return
        danmakuController!!.addLocalDanmaku(text, color, textSize, type, backgroundColor)
    }

    fun controlVideo() {
        if (isPlaying) {
            playerPause()
        } else {
            if (video_now >= video_all - 250) {
                if (interactionData != null && interactionData!!.edges != null &&
                        interactionData!!.edges!!.questions != null && !questionShown) {
                    if (!questionShown) {
                        ijkPlayer!!.seekTo(0L)
                        if (hasDanmaku() && danmakuReady()) {
                            danmakuController!!.seekTo(0L)
                        }
                        Logu.v("播完重播")
                    }
                } else {
                    ijkPlayer!!.seekTo(0L)
                    if (hasDanmaku() && danmakuReady()) {
                        danmakuController!!.seekTo(0L)
                    }
                    Logu.v("播完重播")
                }
            }
            playerResume()
        }
        autohideReset()
    }

    @SuppressLint("SetTextI18n")
    fun changeVolume(add_or_cut: Boolean?) {
        var volumeNow = audioManager!!.getStreamVolume(STREAM_MUSIC)
        val volumeMax = audioManager!!.getStreamMaxVolume(STREAM_MUSIC)
        val volumeNew = volumeNow + (if (add_or_cut!!) 1 else -1)
        if (volumeNew >= 0 && volumeNew <= volumeMax) {
            audioManager!!.setStreamVolume(STREAM_MUSIC, volumeNew, 0)
            volumeNow = volumeNew
        }
        val show = (volumeNow.toFloat() / volumeMax.toFloat() * 100).toInt()

        text_volume!!.visibility = View.VISIBLE
        text_volume!!.text = "音量：$show%"

        text_volume!!.removeCallbacks(hideVolume)
        text_volume!!.postDelayed(hideVolume, 3000)
        autohideReset()
    }

    private val hideVolume = Runnable { text_volume!!.visibility = View.GONE }

    /**
     * 软件旋屏，给某些特殊设备用的。
     * 终端屎山又增高啦
     */
    private fun softwareRotate() {
        val displayMetrics = DisplayMetrics()
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics)
        screen_width = if (screen_landscape) displayMetrics.heightPixels else displayMetrics.widthPixels
        screen_height = if (screen_landscape) displayMetrics.widthPixels else displayMetrics.heightPixels

        val root_layout = findViewById<ViewGroup>(R.id.root_layout)
        val params = root_layout.getLayoutParams()
        params.width = screen_width
        params.height = screen_height

        if (isPrepared && !destroyed)
            runOnUiThread {
                root_layout.setLayoutParams(params)
                root_layout.setPivotX(0f)
                root_layout.setPivotY(0f)
                root_layout.setX(if (screen_landscape) screen_height.toFloat() else 0f)
                root_layout.setRotation(if (screen_landscape) 90f else 0f)
                if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) {
                    if (textureView != null) {
                        val matrix = Matrix()
                        textureView!!.getTransform(matrix)
                        matrix.postRotate(0f)
                        textureView!!.setTransform(matrix)
                    }
                } else {
                    MsgUtil.showMsg("请切换为TextureView才能支持软件旋屏！")
                }
            }
        changeVideoSize()
    }

    private fun videoMoveBy(dx: Float, dy: Float) {
        var x = dx + layout_video!!.getX()
        var y = dy + layout_video!!.getY()

        val width_delta = 0.5f * video_width * (layout_video!!.getScaleX() - 1f)
        val height_delta = 0.5f * video_height * (layout_video!!.getScaleY() - 1f)
        val video_x_min = video_origX - width_delta
        val video_x_max = video_origX + width_delta
        val video_y_min = video_origY - height_delta
        val video_y_max = video_origY + height_delta

        if (x < video_x_min)
            x = video_x_min
        if (x > video_x_max)
            x = video_x_max
        if (y < video_y_min)
            y = video_y_min
        if (y > video_y_max)
            y = video_y_max

        if (layout_video!!.getX() != x || layout_video!!.getY() != y) {
            // Logu.v("gesture","moveto:" + x + "," + y);
            layout_video!!.setX(x)
            layout_video!!.setY(y)
            if (!gesture_moved && (Math.abs(video_origX - x) > 5f || Math.abs(video_origY - y) > 5f)) {
                gesture_moved = true
            }
        }
    }

    private fun playerPause() {
        isPlaying = false
        if (playbackWatchdog != null)
            playbackWatchdog!!.setUserWantsPlay(false)
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer!!.pause()
            if (hasDanmaku() && danmakuReady()) {
                danmakuController!!.pause()
            }
        }
        if (btn_control != null)
            btn_control!!.setImageResource(R.drawable.btn_player_play)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionPlaybackState()
        }
    }

    private fun playerResume() {
        isPlaying = true
        if (playbackWatchdog != null)
            playbackWatchdog!!.setUserWantsPlay(true)
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer!!.start()
            if (hasDanmaku() && danmakuReady()) {
                danmakuController!!.resume()
            }
        }
        if (btn_control != null)
            btn_control!!.setImageResource(R.drawable.btn_player_pause)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            updateMediaSessionPlaybackState()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Logu.v("开始旋转屏幕")

        val displayMetrics = DisplayMetrics()
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics)
        screen_width = displayMetrics.widthPixels// 获取屏宽
        screen_height = displayMetrics.heightPixels// 获取屏高
        changeVideoSize()

        Logu.v("旋转屏幕结束")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Logu.v("onNewIntent")
        finish()
    }

    override fun onPause() {
        super.onPause()
        Logu.v("onPause")
        if (!SharedPreferencesUtil.getBoolean("player_background", false)) {
            playerPause()
        }
    }

    override fun onResume() {
        super.onResume()
        Logu.v("onResume")
        // #6：从后台切回时，弹幕绘制线程可能已经死了（表面被销毁）。
        // 只要发现弹幕层不健康，就整条重建 + 重新拉取，避免"卡死/残留"。
        if (wentToBackground) {
            wentToBackground = false
            if (isPrepared && !isLiveMode && danmakuReady() && !isDanmakuLayerHealthy()) {
                Logu.w("弹幕", "切回前台发现弹幕层异常，重建并重新拉取")
                restartDanmakuFetch(SharedPreferencesUtil.getBoolean("pref_switch_danmaku", true))
            }
        }
    }

    /**
     * 弹幕层是否健康：绘制线程还存在即视为可用。
     *
     * 判据只能是 "handler != null"：
     * DanmakuView.stop()/release() 会把 handler 置空并 join 绘制线程，
     * 这是"弹幕层真的坏了"的唯一可靠信号。
     * 注意**不能**用 `handler.isStop`：播放暂停时 DrawHandler 的 quitFlag 本来就是 true，
     * 拿它当"坏掉"判据会导致每次暂停后切回都白白重拉一遍全部弹幕分段（耗流量、发热）。
     */
    private fun isDanmakuLayerHealthy(): Boolean {
        val view = mDanmakuView ?: return false
        return view.getHandler() != null
    }

    override fun onStop() {
        super.onStop()
        Logu.v("onStop")
        // #6：记录"曾经切到后台"。回到前台时据此做一次弹幕层健康检查——
        // 弱网下弹幕绘制线程可能卡在内核读取里不再推进（不回调、不报错），
        // 表现就是"切回来弹幕卡死"。这里只记录事件，检查放在 onResume。
        wentToBackground = true
    }

    @JvmField
    var liveWebSocket: WebSocket? = null

    /** 直播弹幕重连退避间隔（毫秒），成功认证后归零 */
    @JvmField
    var liveReconnectDelay: Long = 0L

    @JvmField
    var liveReconnectScheduled = false

    override fun onDestroy() {
        if (!isFinishing()) {
            super.onDestroy()
            return // 貌似有些设备启动activity会先调用一下onDestroy，头大…… 不super还会报错
        }

        Logu.v("结束")
        if (eventBusInit) {
            EventBus.getDefault().unregister(this)
            eventBusInit = false
        }
        destroyed = true

        // 实验室功能（#5）：退出播放页时停掉本地预加载代理（预加载文件保留复用）
        WifiPreloadManager.stopProxy()

        cancelAllTimers()

        // #3：ijkPlayer 的 stop/release 是 native 同步调用，会等播放/解码线程退出；
        // 弹幕层 release 也要等绘制线程退出。弱网缓冲中退出时它们可能阻塞主线程
        // 数秒（"退出至详情页卡死"的主因）。destroyed 已置位，所有异步回调都有守卫，
        // 这里把整段释放搬到后台线程，主线程只做轻量清理后立刻返回。
        val playerRef = ijkPlayer
        ijkPlayer = null
        val danmakuFileRef = danmakuFile
        val liveWsRef = liveWebSocket
        liveWebSocket = null
        CenterThreadPool.run(Runnable {
            try {
                releaseDanmakuLayer()
            } catch (ignored: Exception) {
            }
            if (playerRef != null) {
                // 必须先stop再release：release在native层等待播放线程退出，直接release可能长时间阻塞
                try {
                    playerRef.stop()
                } catch (ignored: IllegalStateException) {
                } catch (ignored: Exception) {
                }
                try {
                    playerRef.release()
                } catch (ignored: Exception) {
                }
            }
            if (isOnlineVideo && danmakuFileRef != null && danmakuFileRef.exists())
                //noinspection ResultOfMethodCallIgnored
                danmakuFileRef.delete()
            try {
                liveWsRef?.close(1000, "")
            } catch (ignored: Exception) {
            }
        })

        danmakuThermalController?.stop()
        danmakuThermalController = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mediaSession != null) {
            mediaSession!!.release()
            mediaSession = null
        }

        // 横屏模式已删除（#2）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        super.onDestroy()
    }

    private fun cancelAllTimers() {
        if (playbackWatchdog != null) {
            playbackWatchdog!!.stop()
            playbackWatchdog = null
        }
        if (progressTimer != null) {
            progressTimer!!.cancel()
            progressTimer = null
        }
        if (onlineTimer != null) {
            onlineTimer!!.cancel()
            onlineTimer = null
        }
        if (loadingTimer != null) {
            loadingTimer!!.cancel()
            loadingTimer = null
        }
        if (speedTimer != null) {
            speedTimer!!.cancel()
            speedTimer = null
        }
        if (mainHandler != null) {
            mainHandler!!.removeCallbacksAndMessages(null)
        }
        layout_control!!.removeCallbacks(hidecon)
        text_volume!!.removeCallbacks(hideVolume)
        seekbar_progress!!.removeCallbacks(progressbarEnable)
    }

    @JvmField
    var okHttpClient: OkHttpClient? = null

    private fun danmuSocketConnect() {
        CenterThreadPool.run(Runnable {
            try {
                var url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?type=0&id=" + aid
                val mHeaders = ArrayList<String>()
                mHeaders.add("Cookie")
                mHeaders.add(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!)
                mHeaders.add("Referer")
                mHeaders.add("https://live.bilibili.com/" + aid)
                mHeaders.add("Origin")
                mHeaders.add("https://live.bilibili.com")
                mHeaders.add("User-Agent")
                mHeaders.add(USER_AGENT_WEB)

                val response = NetWorkUtil.get(ConfInfoApi.signWBI(url), mHeaders)
                val data = JSONObject(Objects.requireNonNull(response.body)!!.string())
                        .getJSONObject("data")
                val host = data.getJSONArray("host_list").getJSONObject(0)

                url = "wss://" + host.getString("host") + ":" + host.getInt("wss_port") + "/sub"
                Logu.v("连接WebSocket", url)

                okHttpClient = OkHttpClient()
                val request = Request.Builder()
                        .url(url)
                        .header("Cookie", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!)
                        .header("Origin", "https://live.bilibili.com")
                        .header("User-Agent", USER_AGENT_WEB)
                        .build()

                val listener = PlayerDanmuClientListener()
                listener.mid = mid
                listener.roomid = aid
                listener.key = data.getString("token")
                listener.attachPlayer(this)
                // 断线自动重连（指数退避，由 Kotlin 侧计时）
                listener.onDisconnected = Runnable { scheduleLiveReconnect() }

                liveWebSocket = okHttpClient!!.newWebSocket(request, listener)
                // 连接建立 30s 后仍在（未被判定断线重连），则认为网络已恢复，重置退避
                mainHandler!!.postDelayed({
                    if (!destroyed && liveWebSocket != null)
                        liveReconnectDelay = 0L
                }, 30000L)
                // okHttpClient.dispatcher().executorService().shutdown();
            } catch (e: Exception) {
                MsgUtil.showMsg("直播弹幕连接失败")
                e.printStackTrace()
                scheduleLiveReconnect()
            }
        })
    }

    /**
     * 直播弹幕断线自动重连（指数退避）。
     *
     * 修复点：原实现连接断了就再也不重连，直播间弹幕从断线那一刻起全部丢失，
     * 用户只能退出直播间重进。现在断开后 2s→4s→8s…（上限 60s）自动重连，
     * 网络恢复后弹幕自动接上。认证成功后由 onDisconnected=null 之前的 isAuth 状态归零退避。
     */
    private fun scheduleLiveReconnect() {
        if (destroyed || !isLiveMode)
            return
        synchronized(this) {
            if (liveReconnectScheduled)
                return
            liveReconnectScheduled = true
        }
        val delay = if (liveReconnectDelay <= 0) 2000L else Math.min(liveReconnectDelay * 2, 60000L)
        liveReconnectDelay = delay
        Logu.w("直播弹幕", "将在 " + delay + "ms 后重连")
        mainHandler!!.postDelayed({
            liveReconnectScheduled = false
            if (destroyed || !isLiveMode)
                return@postDelayed
            // 关闭旧连接（若有），重新走完整连接流程
            if (liveWebSocket != null) {
                try {
                    liveWebSocket!!.close(1000, "reconnect")
                } catch (ignored: Exception) {
                }
                liveWebSocket = null
            }
            danmuSocketConnect()
        }, delay)
    }

    @SuppressLint("WrongConstant")
    private fun initMediaSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        mediaSession = MediaSession(this, "BiliClientPlayer")
        mediaSession!!.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession!!.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                super.onPlay()
                runOnUiThread {
                    if (!isPlaying) {
                        playerResume()
                        updateMediaSessionPlaybackState()
                    }
                }
            }

            override fun onPause() {
                super.onPause()
                runOnUiThread {
                    if (isPlaying) {
                        playerPause()
                        updateMediaSessionPlaybackState()
                    }
                }
            }

            override fun onSkipToNext() {
                super.onSkipToNext()
                runOnUiThread {
                    if (hasMultiplePages() && currentPageIndex < pagenames!!.size - 1) {
                        switchToPage(currentPageIndex + 1)
                    }
                }
            }

            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
                runOnUiThread {
                    if (hasMultiplePages() && currentPageIndex > 0) {
                        switchToPage(currentPageIndex - 1)
                    }
                }
            }

            override fun onSeekTo(pos: Long) {
                super.onSeekTo(pos)
                runOnUiThread {
                    seekToPosition(pos)
                    updateMediaSessionPlaybackState()
                }
            }
        })
        mediaSession!!.setActive(true)
    }

    private fun updateMediaSessionMetadata() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaSession == null) {
            return
        }
        val metadataBuilder = MediaMetadata.Builder()
        if (videoTitle != null) {
            metadataBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, videoTitle)
        }
        if (video_all > 0) {
            metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, video_all.toLong())
        }
        mediaSession!!.setMetadata(metadataBuilder.build())
    }

    private fun updateMediaSessionPlaybackState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || mediaSession == null) {
            return
        }
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val position = if (isPrepared && ijkPlayer != null) ijkPlayer!!.getCurrentPosition() else 0
        var actions = (PlaybackState.ACTION_PLAY
                or PlaybackState.ACTION_PAUSE
                or PlaybackState.ACTION_SEEK_TO
                or PlaybackState.ACTION_SKIP_TO_NEXT
                or PlaybackState.ACTION_SKIP_TO_PREVIOUS).toLong()
        if (!hasMultiplePages() || currentPageIndex >= pagenames!!.size - 1) {
            actions = actions and PlaybackState.ACTION_SKIP_TO_NEXT.inv()
        }
        if (!hasMultiplePages() || currentPageIndex <= 0) {
            actions = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS.inv()
        }
        val stateBuilder = PlaybackState.Builder()
                .setState(state, position, 1.0f)
                .setActions(actions)
        mediaSession!!.setPlaybackState(stateBuilder.build())
    }

    private fun initUI() {
        val displayMetrics = DisplayMetrics()
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics)
        screen_width = displayMetrics.widthPixels// 获取屏宽
        screen_height = displayMetrics.heightPixels// 获取屏高

        if (SharedPreferencesUtil.getBoolean("player_ui_showRotateBtn", true))
            btn_rotate!!.visibility = View.VISIBLE
        else
            btn_rotate!!.visibility = View.GONE

        screen_round = SharedPreferencesUtil.getBoolean("player_ui_round", false)
        if (screen_round) {
            val padding = (screen_width * 0.03).toInt()

            val progressParams = seekbar_progress!!.getLayoutParams() as LinearLayout.LayoutParams
            progressParams.leftMargin = padding * 4
            progressParams.rightMargin = padding * 4
            seekbar_progress!!.setLayoutParams(progressParams)

            text_online!!.setPadding(0, 0, padding * 3, 0)
            text_progress!!.setPadding(padding * 3, 0, 0, 0)

            bottom_buttons!!.setPadding(padding, 0, padding, padding)

            right_control!!.setPadding(0, 0, padding, 0)

            val danmakuParams = mDanmakuView!!.getLayoutParams() as RelativeLayout.LayoutParams
            danmakuParams.setMargins(0, padding * 3, 0, padding * 3)
            mDanmakuView!!.setLayoutParams(danmakuParams)

            text_subtitle!!.setMaxWidth((screen_width * 0.65).toInt())

            layout_top!!.setPadding(padding * 7, padding, padding * 7, 0)

            val clockLayout = findViewById<LinearLayout>(R.id.clock_layout)
            clockLayout.setOrientation(LinearLayout.HORIZONTAL)
            val clockLayoutParams = RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clockLayoutParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
            clockLayout.setLayoutParams(clockLayoutParams)

            val titleParams = RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            titleParams.addRule(RelativeLayout.BELOW, R.id.clock_layout)
            titleParams.topMargin = padding / 2
            text_title!!.setLayoutParams(titleParams)
            text_title!!.setGravity(Gravity.CENTER)

            val textClock = findViewById<TextView>(R.id.clock)
            val textClockParams = textClock.getLayoutParams() as LinearLayout.LayoutParams
            textClockParams.leftMargin = padding / 2
            textClockParams.topMargin = padding / 4
            textClock.setLayoutParams(textClockParams)
        }

        if (!SharedPreferencesUtil.getBoolean("player_show_online", false) || aid == 0L || cid == 0L)
            text_online!!.visibility = View.GONE

        layout_top!!.setOnClickListener { view -> finish() }

        val params = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        if (SharedPreferencesUtil.getBoolean("player_display", Build.VERSION.SDK_INT < 26)) {
            textureView = TextureView(this)
            textureView!!.setSurfaceTextureListener(object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, i: Int, i1: Int) {
                    Logu.v("surfacetexture", "available")
                    mSurfaceTexture = surfaceTexture
                    if (ijkPlayer != null) {
                        ijkPlayer!!.setSurface(Surface(surfaceTexture))
                        startPrepareIfNeeded()
                    }
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, i: Int, i1: Int) {
                    Logu.v("surfacetexture", "sizechanged")
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    Logu.v("surfacetexture", "destroyed")
                    mSurfaceTexture = null
                    if (ijkPlayer != null)
                        ijkPlayer!!.setSurface(null)
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
            })
            layout_video!!.addView(textureView, params)
        } else {
            surfaceView = SurfaceView(this)
            layout_video!!.addView(surfaceView, params)
        }

        btn_rotate!!.setOnClickListener { view ->
            Logu.v("点击旋转按钮")
            screen_landscape = !screen_landscape
            if (SharedPreferencesUtil.getBoolean("dev_player_rotate_software", false))
                softwareRotate()
            else
                setRequestedOrientation(if (screen_landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        }

        findViewById<View>(R.id.button_sound_add).setOnClickListener { view -> changeVolume(true) }
        findViewById<View>(R.id.button_sound_cut).setOnClickListener { view -> changeVolume(false) }

        btn_menu!!.setOnClickListener { view ->
            if (menu_opened) {
                right_second!!.visibility = View.GONE
                btn_menu!!.setImageResource(R.mipmap.morehide)
            } else {
                right_second!!.visibility = View.VISIBLE
                btn_menu!!.setImageResource(R.mipmap.moreshow)
            }
            menu_opened = !menu_opened
        }

        layout_card_bg!!.setOnClickListener { view ->
            layout_card_bg!!.visibility = View.GONE
            card_subtitle!!.visibility = View.GONE
            card_danmaku_send!!.visibility = View.GONE
            card_page_selector!!.visibility = View.GONE
            card_quality_selector!!.visibility = View.GONE
            card_viewpoint_selector!!.visibility = View.GONE
            card_time_jump!!.visibility = View.GONE
        }
        btn_danmaku_send!!.setOnClickListener { view ->
            layout_card_bg!!.visibility = View.VISIBLE
            card_danmaku_send!!.visibility = View.VISIBLE
        }

        // 时间调节小窗（#10）：与发送弹幕小窗同款卡片
        findViewById<View>(R.id.time_jump_btn).setOnClickListener { view ->
            if (!isPrepared || isLiveMode) {
                MsgUtil.showMsg("当前无法调节进度")
                return@setOnClickListener
            }
            layout_card_bg!!.visibility = View.VISIBLE
            card_time_jump!!.visibility = View.VISIBLE
            showcon()
        }
        findViewById<View>(R.id.time_jump_apply).setOnClickListener { view1 ->
            val editText = time_jump_input ?: return@setOnClickListener
            val seconds = parseTimeInput(editText.text.toString())
            if (seconds == null) {
                MsgUtil.showMsg("时间格式不对，示例：90、1:30、5 30、1：02：03")
                return@setOnClickListener
            }
            val duration = try { ijkPlayer!!.getDuration() } catch (e: Exception) { 0L }
            var targetMs = seconds * 1000L
            if (targetMs < 0) targetMs = 0
            if (duration > 0 && targetMs > duration) targetMs = duration
            seekToPosition(targetMs)
            MsgUtil.showMsg("已跳转到 " + StringUtil.toTime((targetMs / 1000).toInt()))
            layout_card_bg!!.visibility = View.GONE
            card_time_jump!!.visibility = View.GONE
        }
        findViewById<View>(R.id.danmaku_send).setOnClickListener { view1 ->
            val editText = findViewById<EditText>(R.id.danmaku_send_edit)
            if (editText.getText().toString().isEmpty()) {
                MsgUtil.showMsg("不能发送空弹幕喵")
            } else {
                layout_card_bg!!.visibility = View.GONE
                card_danmaku_send!!.visibility = View.GONE

                CenterThreadPool.run(Runnable {
                    try {
                        MsgUtil.showMsg("正在发送~")

                        val result = DanmakuApi.sendVideoDanmakuByAid(cid, editText.getText().toString(), aid,
                                video_now.toLong(), ToolsUtil.getRgb888(Color.WHITE), 1)

                        if (result == 0) {
                            MsgUtil.showMsg("发送成功喵~")
                            runOnUiThread {
                                addDanmaku(editText.getText().toString(), Color.WHITE)
                                editText.setText("")
                            }
                        } else
                            MsgUtil.showMsg("发送失败：$result")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        MsgUtil.err(e)
                    }
                })
            }
        }
    }

    private fun initSeekbars() {
        seekbar_progress!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            @SuppressLint("SetTextI18n")
            override fun onProgressChanged(seekBar: SeekBar, position: Int, fromUser: Boolean) {
                // 只有用户拖动才需要预览文本；程序 setProgress 走这条回调时
                // 不重复写 text_progress（进度定时器自己会刷新，防止两路更新互相覆盖）
                if (!fromUser)
                    return
                runOnUiThread {
                    if (!isLiveMode)
                        updateProgressText(position)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeeking = false
                if (isPrepared && !destroyed) {
                    val seekPos = seekbar_progress!!.getProgress()
                    ijkPlayer!!.seekTo(seekPos.toLong())
                    if (hasDanmaku() && danmakuReady()) {
                        danmakuController!!.seekTo(seekPos.toLong())
                    }
                    autohideReset()
                }
            }
        })

        seekbar_speed!!.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, position: Int, fromUser: Boolean) {
                // #4：越界防护——SeekBar 的进度可能被系统恢复成超过数组长度的值
                if (position < 0 || position >= speed_strs.size) return
                if (fromUser) {
                    text_newspeed!!.text = speed_strs[position] // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    text_speed!!.text = speed_strs[position] // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    if (ijkPlayer != null)
                        ijkPlayer!!.setSpeed(speed_values[position]) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                    if (danmakuReady())
                        danmakuController!!.setSpeed(speed_values[position]) // quality-allow 回退保留的旧播放器代码，调用点前已有判空
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                if (speedTimer != null)
                    speedTimer!!.cancel()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                speedTimer = Timer()
                val timerTask: TimerTask = object : TimerTask() {
                    override fun run() {
                        runOnUiThread { layout_speed!!.visibility = View.GONE }
                    }
                }
                speedTimer!!.schedule(timerTask, 200)
            }
        })
    }

    /**
     * 表冠控制视频前进/后退（#23）。
     *
     * 打开播放页小菜单栏（right_second）后，滚动手表表冠即可前后微调播放进度，
     * 无需再去拖动进度条。每次一档约 5 秒，符合手表上“微调”的定位。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isPrepared && !isLiveMode &&
            event.action == MotionEvent.ACTION_SCROLL &&
            (event.source and InputDevice.SOURCE_CLASS_MASK) == InputDevice.SOURCE_ROTARY_ENCODER
        ) {
            // 时间调节小窗打开时（#10），表冠在输入框基础上 ±5s 微调并回填输入框
            if (card_time_jump != null && card_time_jump!!.visibility == View.VISIBLE) {
                val steps = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                if (steps != 0f) {
                    val base = parseTimeInput(time_jump_input?.text?.toString() ?: "") ?: 0L
                    val durationSec = (try { ijkPlayer!!.getDuration() } catch (e: Exception) { 0L }) / 1000
                    var target: Long = base + (steps * CROWN_SEEK_STEP_MS / 1000f).toInt()
                    if (target < 0) target = 0
                    if (durationSec > 0 && target > durationSec) target = durationSec
                    time_jump_input?.setText(StringUtil.toTime(target.toInt()))
                    time_jump_input?.setSelection(time_jump_input?.text?.length ?: 0)
                    return true
                }
                return true
            }
            if (menu_opened) {
                val steps = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                if (steps != 0f) {
                    val deltaMs = (steps * CROWN_SEEK_STEP_MS).toLong()
                    var target = ijkPlayer!!.getCurrentPosition() + deltaMs
                    val duration = ijkPlayer!!.getDuration()
                    if (target < 0) target = 0
                    if (duration > 0 && target > duration) target = duration
                    seekToPosition(target)
                    showSeekHint(target)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** 表冠微调进度时在屏幕上给出即时反馈 */
    private fun showSeekHint(positionMs: Long) {
        val hint = text_speed_hint ?: return
        runOnUiThread {
            val curr = StringUtil.toTime((positionMs / 1000).toInt())
            hint.text = curr + " / " + progress_str
            hint.visibility = View.VISIBLE
            hint.removeCallbacks(seekHintHide)
            hint.postDelayed(seekHintHide, 800L)
        }
    }

    private val seekHintHide = Runnable {
        if (!onLongClick) text_speed_hint?.visibility = View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isPrepared)
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> controlVideo()
                KeyEvent.KEYCODE_DPAD_LEFT -> seekToPosition(ijkPlayer!!.getCurrentPosition() - 10000L)
                KeyEvent.KEYCODE_DPAD_RIGHT -> seekToPosition(ijkPlayer!!.getCurrentPosition() + 10000L)
                KeyEvent.KEYCODE_DPAD_UP -> changeVolume(true)
                KeyEvent.KEYCODE_DPAD_DOWN -> changeVolume(false)
            }
        return super.onKeyDown(keyCode, event)
    }

    private fun seekToPosition(position: Long) {
        if (ijkPlayer != null && isPrepared) {
            ijkPlayer!!.seekTo(position)
            if (hasDanmaku() && danmakuReady()) {
                danmakuController!!.seekTo(position)
            }
        }
    }

    private fun toggleAudioOnlyMode() {
        val oldMode = isAudioOnlyMode
        isAudioOnlyMode = !isAudioOnlyMode
        // 不保存状态，仅在当前播放会话中切换

        if (isPrepared && ijkPlayer != null) {
            val currentPosition = ijkPlayer!!.getCurrentPosition()
            val wasPlaying = isPlaying

            MsgUtil.showMsg(if (isAudioOnlyMode) "正在切换到听视频模式..." else "正在切换到普通模式...")

            CenterThreadPool.run(Runnable {
                try {
                    runOnUiThread {
                        if (destroyed)
                            return@runOnUiThread
                        if (hasDanmaku() && danmakuReady()) {
                            danmakuController!!.pause()
                        }
                        if (ijkPlayer != null) {
                            ijkPlayer!!.stop()
                            ijkPlayer!!.release()
                        }

                        loading_info!!.visibility = View.VISIBLE
                        anim_loading!!.start()
                        loading_text0!!.text = if (isAudioOnlyMode) "切换到听视频模式" else "切换到普通模式"
                        isPrepared = false
                        isPlaying = false

                        updateAudioOnlyButton()
                        updateAudioOnlyUI()
                    }

                    Thread.sleep(100)

                    runOnUiThread {
                        if (destroyed)
                            return@runOnUiThread
                        ijkPlayer = IjkMediaPlayer()
                        progress_history = currentPosition

                        setDisplay()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        MsgUtil.showMsg("切换失败，请重试")
                        isAudioOnlyMode = oldMode
                        // 不保存状态
                        updateAudioOnlyButton()
                        updateAudioOnlyUI()
                        loading_info!!.visibility = View.GONE
                        anim_loading!!.stop()
                    }
                }
            })
        } else {
            updateAudioOnlyButton()
            updateAudioOnlyUI()
            MsgUtil.showMsg(if (isAudioOnlyMode) "已切换到听视频模式" else "已切换到普通模式")
        }
    }

    private fun updateAudioOnlyButton() {
        val audioBtn = btn_audio_only
        if (audioBtn != null) {
            audioBtn
                    .setImageResource(if (isAudioOnlyMode) R.drawable.icon_audio_only_on else R.drawable.icon_audio_only_off)
        }
    }

    private fun updateAudioOnlyUI() {
        runOnUiThread {
            if (isAudioOnlyMode) {
                // 进入听视频模式
                text_speed!!.visibility = View.GONE
                btn_debug!!.visibility = View.GONE
                btn_danmaku!!.visibility = View.GONE
                layout_video!!.visibility = View.GONE
                layout_audio_only!!.visibility = View.VISIBLE
                if (mDanmakuView != null) {
                    mDanmakuView!!.setVisibility(View.GONE)
                }
                if (text_audio_title != null) {
                    val title = text_title!!.getText().toString()
                    text_audio_title!!.text = if (title.isEmpty()) "听视频模式" else title
                }
            } else {
                // 退出听视频模式
                text_speed!!.visibility = View.VISIBLE
                updateDebugButtonVisibility()
                btn_danmaku!!.visibility = View.VISIBLE
                layout_video!!.visibility = View.VISIBLE
                layout_audio_only!!.visibility = View.GONE
                if (mDanmakuView != null && isDanmakuVisible()) {
                    mDanmakuView!!.setVisibility(View.VISIBLE)
                }
            }
        }
    }

    private var eventBusInit = false

    override fun onStart() {
        super.onStart()
        if (eventBusEnabled() && !eventBusInit) {
            EventBus.getDefault().register(this)
            Logu.v("event", "register")
            eventBusInit = true
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onEvent(snackEvent: SnackEvent) {
        if (isFinishing())
            return
        Logu.v("event", "onEvent")

        val currentTime = System.currentTimeMillis()

        val duration: Int
        if (snackEvent.duration > 0)
            duration = snackEvent.duration
        else if (snackEvent.duration == Snackbar.LENGTH_SHORT)
            duration = 1950
        else if (snackEvent.duration == Snackbar.LENGTH_INDEFINITE)
            duration = Integer.MAX_VALUE
        else
            duration = 2750

        val endTime = snackEvent.startTime + duration
        if (currentTime >= endTime) {
            EventBus.getDefault().removeStickyEvent(snackEvent)
        } else {
            MsgUtil.toast(snackEvent.message) // 由于Theme.Black不支持，只能这样用了
        }
    }

    protected fun eventBusEnabled(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true)
    }

    /**
     * 加载高能进度条数据
     */
    private fun loadHighEnergyData() {
        if (!SharedPreferencesUtil.getBoolean("player_high_energy", false)) {
            Logu.d("高能进度条", "功能已禁用")
            return
        }

        CenterThreadPool.run(Runnable {
            try {
                Logu.d("高能进度条", "开始加载数据 aid=$aid cid=$cid")
                val data = PlayerApi.getHighEnergyData(cid, aid)

                if (data != null && data.hasValidData()) {
                    runOnUiThread {
                        if (!destroyed && seekbar_progress != null) {
                            seekbar_progress!!.setHighEnergyData(data.events, data.stepSec)
                            Logu.d("高能进度条", "数据加载成功并设置到进度条")
                        }
                    }
                } else {
                    Logu.w("高能进度条", "未获取到有效数据")
                }
            } catch (e: Exception) {
                Logu.e("高能进度条", "加载失败: " + e.message)
                e.printStackTrace()
            }
        })
    }

    private fun hasMultiplePages(): Boolean {
        return pagenames != null && pagenames!!.size > 1
    }

    private fun showPageSelectorCard() {
        if (!hasMultiplePages())
            return

        runOnUiThread {
            val pageSelectorRecycler = findViewById<RecyclerView>(R.id.page_selector_list)
            val adapter = PageSelectorAdapter()
            adapter.setData(pagenames, currentPageIndex)
            adapter.setOnItemClickListener { index ->
                layout_card_bg!!.visibility = View.GONE
                card_page_selector!!.visibility = View.GONE
                if (index != currentPageIndex) {
                    switchToPage(index)
                }
            }
            pageSelectorRecycler.layoutManager = CustomLinearManager(this)
            pageSelectorRecycler.adapter = adapter
            layout_card_bg!!.visibility = View.VISIBLE
            card_page_selector!!.visibility = View.VISIBLE
        }
    }

    private fun switchToPage(pageIndex: Int) {
        if (!hasMultiplePages() || pageIndex < 0 || pageIndex >= pagenames!!.size)
            return
        if (pageIndex == currentPageIndex)
            return

        currentPageIndex = pageIndex
        val newCid = cids!![pageIndex]
        val newTitle = pagenames!![pageIndex]

        MsgUtil.showMsg("切换到 P" + (pageIndex + 1))

        CenterThreadPool.run(Runnable {
            try {
                val playerData = PlayerData()
                playerData.aid = aid
                playerData.cid = newCid
                playerData.title = newTitle
                playerData.mid = mid
                playerData.qn = SharedPreferencesUtil.getInt("play_qn", 16)
                playerData.pagenames = pagenames
                playerData.cids = cids
                playerData.currentPageIndex = currentPageIndex

                if (isOnlineVideo) {
                    PlayerApi.getVideo(playerData, false)
                } else {
                    runOnUiThread { MsgUtil.showMsg("本地视频暂不支持切换分P") }
                    return@Runnable
                }

                runOnUiThread {
                    if (destroyed)
                        return@runOnUiThread

                    if (ijkPlayer != null) {
                        try {
                            ijkPlayer!!.stop()
                        } catch (ignored: IllegalStateException) {
                        }
                        ijkPlayer!!.release()
                    }
                    releaseDanmakuLayer()

                    cid = newCid
                    video_url = playerData.videoUrl
                    danmaku_url = playerData.danmakuUrl
                    text_title!!.text = newTitle
                    videoTitle = newTitle

                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList
                        qnValueList = playerData.qnValueList
                        currentQuality = playerData.qn
                    }

                    loading_info!!.visibility = View.VISIBLE
                    anim_loading!!.start()
                    loading_text0!!.text = "加载P" + (pageIndex + 1)
                    isPrepared = false
                    isPlaying = false
                    finishWatching = false
                    progress_history = 0
                    subtitles = null
                    subtitleLinks = null
                    subtitle_selected = -1
                    viewPoints = null
                    viewPointAdapter = null
                    if (btn_viewpoint != null) {
                        btn_viewpoint!!.visibility = View.GONE
                    }

                    interactionData = null
                    currentEdgeId = 0
                    currentQuestion = null
                    questionShown = false
                    if (interactionChoiceLayout != null) {
                        interactionChoiceLayout!!.visibility = View.GONE
                        interactionChoiceLayout!!.removeAllViews()
                    }

                    ijkPlayer = IjkMediaPlayer()
                    mDanmakuView = findViewById<View>(R.id.sv_danmaku) as IDanmakuView

                    setDisplay()

                    layout_control!!.postDelayed(Runnable { CenterThreadPool.run(Runnable {
                        if (destroyed)
                            return@Runnable

                        runOnUiThread {
                            loading_text0!!.text = "装填弹幕中"
                            loading_text1!!.text = "(≧∇≦)"
                        }

                        if (isOnlineVideo) {
                            danmakuFile = File(cacheDir, "danmaku.xml")
                            if (danmakuFile!!.exists()) {
                                danmakuFile!!.delete()
                            }
                            downdanmu()
                        }

                        if (!destroyed && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true)) {
                            downSubtitle(false)
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                            loadHighEnergyData()
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                            loadViewPoints()
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                            loadInteractionVideo()
                        }
                    }) }, 60)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    MsgUtil.err(e)
                    MsgUtil.showMsg("切换失败")
                }
            }
        })
    }

    private fun toggleAutoNext() {
        auto_next_enabled = !auto_next_enabled
        updateAutoNextButton()
        MsgUtil.showMsg(if (auto_next_enabled) "已开启自动连播" else "已关闭自动连播")
    }

    private fun updateAutoNextButton() {
        val autoBtn = btn_auto_next
        if (autoBtn != null) {
            autoBtn
                    .setImageResource(if (auto_next_enabled) R.drawable.icon_auto_next_on else R.drawable.icon_auto_next_off)
        }
    }

    private fun showQualitySelectorCard() {
        if (qnStrList == null || qnValueList == null || qnStrList!!.size == 0) {
            MsgUtil.showMsg("清晰度列表未加载")
            return
        }

        runOnUiThread {
            val qualitySelectorRecycler = findViewById<RecyclerView>(R.id.quality_selector_list)
            val adapter = QualitySelectorAdapter()
            // currentQuality 为 0（还没拿到服务端确认的清晰度）时，用设置里的默认清晰度做兜底，
            // 保证清晰度框里始终能标出“当前选的是哪个”
            val highlightQuality = if (currentQuality > 0) currentQuality
            else SharedPreferencesUtil.getInt("play_qn", 16)
            adapter.setData(qnStrList as Array<String>, qnValueList as IntArray, highlightQuality)
            adapter.setOnItemClickListener { index ->
                layout_card_bg!!.visibility = View.GONE
                card_quality_selector!!.visibility = View.GONE
                if (index >= 0 && index < qnValueList!!.size && qnValueList!![index] != currentQuality) {
                    switchQuality(qnValueList!![index])
                }
            }
            qualitySelectorRecycler
                    .layoutManager = CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false)
            qualitySelectorRecycler.adapter = adapter

            // 横向列表：把当前项滚到可见区域，避免选中项在屏幕外看不出来
            if (adapter.selectedItemIndex >= 0) {
                qualitySelectorRecycler.scrollToPosition(adapter.selectedItemIndex)
            }
            layout_card_bg!!.visibility = View.VISIBLE
            card_quality_selector!!.visibility = View.VISIBLE
        }
    }

    private fun switchQuality(newQuality: Int) {
        if (!isOnlineVideo || newQuality == currentQuality) {
            return
        }

        MsgUtil.showMsg("正在切换清晰度...")

        CenterThreadPool.run(Runnable {
            try {
                val playerData = PlayerData()
                playerData.aid = aid
                playerData.cid = cid
                playerData.title = text_title!!.getText().toString()
                playerData.mid = mid
                playerData.qn = newQuality
                playerData.pagenames = pagenames
                playerData.cids = cids
                playerData.currentPageIndex = currentPageIndex

                PlayerApi.getVideo(playerData, false)

                runOnUiThread {
                    if (destroyed)
                        return@runOnUiThread

                    val currentPosition = if (ijkPlayer != null) ijkPlayer!!.getCurrentPosition() else 0
                    val wasPlaying = isPlaying

                    if (ijkPlayer != null) {
                        try {
                            ijkPlayer!!.stop()
                        } catch (ignored: IllegalStateException) {
                        }
                        ijkPlayer!!.release()
                    }

                    video_url = playerData.videoUrl
                    currentQuality = newQuality

                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList
                        qnValueList = playerData.qnValueList
                    }

                    loading_info!!.visibility = View.VISIBLE
                    anim_loading!!.start()
                    loading_text0!!.text = "切换清晰度中"
                    isPrepared = false
                    isPlaying = false

                    ijkPlayer = IjkMediaPlayer()
                    progress_history = currentPosition

                    setDisplay()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    MsgUtil.err(e)
                    MsgUtil.showMsg("清晰度切换失败")
                }
            }
        })
    }

    private var viewPoints: List<ViewPoint>? = null
    private var viewPointAdapter: ViewPointAdapter? = null

    private fun loadViewPoints() {
        CenterThreadPool.run(Runnable {
            try {
                Logu.d("视频分段", "开始加载分段数据 aid=$aid cid=$cid")
                viewPoints = PlayerApi.getViewPoints(aid, cid)

                if (viewPoints != null && !viewPoints!!.isEmpty()) {
                    runOnUiThread {
                        if (!destroyed && btn_viewpoint != null) {
                            btn_viewpoint!!.visibility = View.VISIBLE
                            btn_viewpoint!!.setOnClickListener { view -> showViewPointSelectorCard() }
                            Logu.d("视频分段", "成功加载 " + viewPoints!!.size + " 个分段")
                        }
                    }
                } else {
                    Logu.d("视频分段", "未获取到分段数据")
                }
            } catch (e: Exception) {
                Logu.e("视频分段", "加载失败: " + e.message)
                e.printStackTrace()
            }
        })
    }

    private fun showViewPointSelectorCard() {
        if (viewPoints == null || viewPoints!!.isEmpty())
            return

        runOnUiThread {
            val viewPointRecycler = findViewById<RecyclerView>(R.id.viewpoint_selector_list)
            if (viewPointAdapter == null) {
                viewPointAdapter = ViewPointAdapter()
                viewPointAdapter!!.setData(viewPoints)
                viewPointAdapter!!.setOnItemClickListener { index ->
                    layout_card_bg!!.visibility = View.GONE
                    card_viewpoint_selector!!.visibility = View.GONE
                    if (index >= 0 && index < viewPoints!!.size) {
                        val vp = viewPoints!![index]
                        seekToPosition(vp.from * 1000L)
                        MsgUtil.showMsg("跳转到: " + vp.content)
                    }
                }
                viewPointRecycler.layoutManager = CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false)
                viewPointRecycler.adapter = viewPointAdapter
            }
            if (ijkPlayer != null && isPrepared) {
                val currentPos = (ijkPlayer!!.getCurrentPosition() / 1000).toInt()
                viewPointAdapter!!.updateCurrentPosition(currentPos)
            }
            layout_card_bg!!.visibility = View.VISIBLE
            card_viewpoint_selector!!.visibility = View.VISIBLE
        }
    }


    // 再往下就是互动视频的天下了

    private fun loadInteractionVideo() {
        CenterThreadPool.run(Runnable {
            try {
                val graphVersion = PlayerApi.getInteractionGraphVersion(aid, cid)
                if (graphVersion > 0) {
                    interactionGraphVersion = graphVersion
                    Logu.d("互动视频", "检测到互动视频，graph_version: " + interactionGraphVersion + ", cid: " + cid)

                    runOnUiThread {
                        questionShown = false
                        currentQuestion = null
                        if (interactionChoiceLayout != null) {
                            interactionChoiceLayout!!.visibility = View.GONE
                            interactionChoiceLayout!!.removeAllViews()
                        }
                    }

                    var edgeId: Long = 0
                    if (initialEdgeId > 0) {
                        edgeId = initialEdgeId
                        initialEdgeId = 0
                    } else if (interactionData != null && currentEdgeId > 0) {
                        edgeId = currentEdgeId
                    }

                    interactionData = InteractionVideoApi.getEdgeInfo(aid, null, interactionGraphVersion, edgeId)
                    if (interactionData != null) {
                        currentEdgeId = interactionData!!.edgeId
                        Logu.d("互动视频", "成功加载互动视频数据，edge_id: " + currentEdgeId)
                        runOnUiThread { updateDebugButtonVisibility() }
                    }
                } else {
                    interactionData = null
                    currentEdgeId = 0
                    runOnUiThread {
                        questionShown = false
                        currentQuestion = null
                        updateDebugButtonVisibility()
                    }
                }
            } catch (e: Exception) {
                Logu.e("互动视频", "加载失败: " + e.message)
                e.printStackTrace()
                interactionData = null
                currentEdgeId = 0
                runOnUiThread {
                    questionShown = false
                    currentQuestion = null
                    updateDebugButtonVisibility()
                }
            }
        })
    }

    private fun updateDebugButtonVisibility() {
        if (btn_debug == null) return
        val debugEnabled = SharedPreferencesUtil.getBoolean("player_interaction_debug", false)
        if (debugEnabled && interactionData != null && interactionData!!.hiddenVars != null && !interactionData!!.hiddenVars!!.isEmpty() && !isLiveMode && !isAudioOnlyMode) {
            btn_debug!!.visibility = layout_top!!.getVisibility()
        } else {
            btn_debug!!.visibility = View.GONE
        }
    }

    private fun checkEndInteractionQuestions() {
        if (interactionData == null || interactionData!!.edges == null ||
                interactionData!!.edges!!.questions == null || questionShown) {
            return
        }

        for (question in interactionData!!.edges!!.questions!!) {
            if (question.type == 0) {
                if (question.choices != null && !question.choices!!.isEmpty()) {
                    for (choice in question.choices!!) {
                        if (choice.isHidden == 1) continue

                        if (choice.condition != null && !choice.condition!!.isEmpty()) {
                            if (!evaluateCondition(choice.condition)) {
                                continue
                            }
                        }

                        handleChoiceSelection(choice)
                        break
                    }
                }
                continue
            }
            showInteractionQuestion(question)
        }
    }

    private fun showInteractionQuestion(question: InteractionVideoData.InteractionQuestion) {
        if (questionShown || question.choices == null || question.choices!!.isEmpty()) {
            return
        }

        runOnUiThread {
            questionShown = true
            currentQuestion = question

            if (question.pauseVideo == 1 && isPlaying) {
                ijkPlayer!!.pause()
                isPlaying = false
                btn_control!!.setImageResource(R.drawable.btn_player_play)
            }

            if (interactionChoiceLayout == null) {
                createInteractionChoiceLayout()
            }

            interactionChoiceLayout!!.removeAllViews()

            for (choice in question.choices!!) {
                if (choice.isHidden == 1) continue

                if (choice.condition != null && !choice.condition!!.isEmpty()) {
                    if (!evaluateCondition(choice.condition)) {
                        continue
                    }
                }

                val choiceView = createChoiceView(choice)
                interactionChoiceLayout!!.addView(choiceView)
            }

            if (interactionChoiceLayout!!.getChildCount() > 0) {
                interactionChoiceLayout!!.visibility = View.VISIBLE
            }
        }
    }

    private fun createChoiceView(choice: InteractionVideoData.InteractionChoice): TextView {
        val choiceView = LayoutInflater.from(this).inflate(R.layout.cell_interaction_choice, null) as TextView
        choiceView.text = choice.option
        val fontSize = SharedPreferencesUtil.getFloat("player_interaction_choice_size", 17.0f)
        choiceView.setTextSize(fontSize)
        choiceView.setOnClickListener { v -> handleChoiceSelection(choice) }
        return choiceView
    }

    private fun createInteractionChoiceLayout() {
        val rootLayout = findViewById<RelativeLayout>(R.id.root_layout)
        interactionChoiceLayout = LinearLayout(this)
        interactionChoiceLayout!!.setOrientation(LinearLayout.VERTICAL)
        interactionChoiceLayout!!.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)

        val params = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        params.setMargins(0, 0, 0, 100)

        interactionChoiceLayout!!.setLayoutParams(params)
        interactionChoiceLayout!!.setVisibility(View.GONE)
        rootLayout.addView(interactionChoiceLayout)
    }

    private fun evaluateCondition(condition: String?): Boolean {
        if (interactionData == null || interactionData!!.hiddenVars == null || condition == null || condition.isEmpty()) {
            return true
        }

        try {
            var result = condition
            for (`var` in interactionData!!.hiddenVars!!) {
                if (`var`.idV2 != null && !`var`.idV2!!.isEmpty()) {
                    val pattern = "\\b" + Pattern.quote(`var`.idV2!!) + "\\b"
                    result = result!!.replace(Regex(pattern), `var`.value.toString())
                }
                if (`var`.id != null && !`var`.id!!.isEmpty() && `var`.id != `var`.idV2) {
                    val pattern = "\\b" + Pattern.quote(`var`.id!!) + "\\b"
                    result = result!!.replace(Regex(pattern), `var`.value.toString())
                }
            }

            return evaluateExpression(result!!)
        } catch (e: Exception) {
            Logu.e("互动视频", "条件判断失败: " + e.message)
            return true
        }
    }

    private fun evaluateExpression(expr: String): Boolean {
        try {
            var expr = expr.trim { it <= ' ' }
            if (expr.contains(">=")) {
                val parts = expr.split(">=")
                val left = parts[0].trim { it <= ' ' }.toLong()
                val right = parts[1].trim { it <= ' ' }.toLong()
                return left >= right
            } else if (expr.contains("<=")) {
                val parts = expr.split("<=")
                val left = parts[0].trim { it <= ' ' }.toLong()
                val right = parts[1].trim { it <= ' ' }.toLong()
                return left <= right
            } else if (expr.contains(">")) {
                val parts = expr.split(">")
                val left = parts[0].trim { it <= ' ' }.toLong()
                val right = parts[1].trim { it <= ' ' }.toLong()
                return left > right
            } else if (expr.contains("<")) {
                val parts = expr.split("<")
                val left = parts[0].trim { it <= ' ' }.toLong()
                val right = parts[1].trim { it <= ' ' }.toLong()
                return left < right
            } else if (expr.contains("==")) {
                val parts = expr.split("==")
                val left = parts[0].trim { it <= ' ' }.toLong()
                val right = parts[1].trim { it <= ' ' }.toLong()
                return left == right
            } else if (expr.contains("!=")) {
                val parts = expr.split("!=")
                val left = parts[0].trim { it <= ' ' }.toLong()
                val right = parts[1].trim { it <= ' ' }.toLong()
                return left != right
            }
        } catch (e: Exception) {
            Logu.e("互动视频", "表达式计算失败: " + expr)
        }
        return true
    }

    private fun handleChoiceSelection(choice: InteractionVideoData.InteractionChoice) {
        hideInteractionChoices()

        if (choice.nativeAction != null && !choice.nativeAction!!.isEmpty()) {
            executeNativeAction(choice.nativeAction)
        }

        CenterThreadPool.run(Runnable {
            try {
                val targetEdgeId = choice.id
                val newData = InteractionVideoApi.getEdgeInfo(aid, null, interactionGraphVersion, targetEdgeId)

                if (newData == null) {
                    runOnUiThread { MsgUtil.showMsg("获取互动视频数据失败") }
                    return@Runnable
                }

                interactionData = newData
                currentEdgeId = newData.edgeId

                val targetCid = choice.cid
                if (targetCid > 0 && targetCid != cid) {
                    jumpToInteractionPage(targetCid, newData)
                } else {
                    resumePlaybackIfPaused()
                }
            } catch (e: Exception) {
                Logu.e("互动视频", "处理选择失败: " + e.message)
                runOnUiThread { MsgUtil.showMsg("处理选择失败: " + e.message) }
            }
        })
    }

    private fun hideInteractionChoices() {
        runOnUiThread {
            if (interactionChoiceLayout != null) {
                interactionChoiceLayout!!.visibility = View.GONE
            }
            questionShown = false
            currentQuestion = null
        }
    }

    private fun jumpToInteractionPage(targetCid: Long, newData: InteractionVideoData) {
        CenterThreadPool.run(Runnable {
            try {
                val playerData = PlayerData()
                playerData.aid = aid
                playerData.cid = targetCid
                playerData.title = newData.title
                playerData.mid = mid
                playerData.qn = getTargetQuality()

                if (pagenames != null && cids != null) {
                    playerData.pagenames = pagenames
                    playerData.cids = cids
                    val newPageIndex = cids!!.indexOf(targetCid)
                    if (newPageIndex >= 0) {
                        playerData.currentPageIndex = newPageIndex
                        currentPageIndex = newPageIndex
                    }
                }

                PlayerApi.getVideo(playerData, false)

                runOnUiThread {
                    if (destroyed)
                        return@runOnUiThread

                    if (ijkPlayer != null) {
                        ijkPlayer!!.stop()
                        ijkPlayer!!.release()
                    }
                    releaseDanmakuLayer()

                    cid = targetCid
                    video_url = playerData.videoUrl
                    danmaku_url = playerData.danmakuUrl
                    text_title!!.text = newData.title
                    videoTitle = newData.title
                    currentEdgeId = newData.edgeId

                    if (playerData.qnStrList != null && playerData.qnValueList != null) {
                        qnStrList = playerData.qnStrList
                        qnValueList = playerData.qnValueList
                        currentQuality = playerData.qn
                    }

                    loading_info!!.visibility = View.VISIBLE
                    anim_loading!!.start()
                    loading_text0!!.text = "加载互动分P"
                    isPrepared = false
                    isPlaying = false
                    finishWatching = false
                    progress_history = 0
                    subtitles = null
                    subtitleLinks = null
                    subtitle_selected = -1
                    viewPoints = null
                    viewPointAdapter = null
                    if (btn_viewpoint != null) {
                        btn_viewpoint!!.visibility = View.GONE
                    }

                    interactionData = newData
                    currentQuestion = null
                    questionShown = false
                    if (interactionChoiceLayout != null) {
                        interactionChoiceLayout!!.visibility = View.GONE
                        interactionChoiceLayout!!.removeAllViews()
                    }

                    ijkPlayer = IjkMediaPlayer()
                    mDanmakuView = findViewById<View>(R.id.sv_danmaku) as IDanmakuView

                    setDisplay()

                    layout_control!!.postDelayed(Runnable { CenterThreadPool.run(Runnable {
                        if (destroyed)
                            return@Runnable

                        runOnUiThread {
                            loading_text0!!.text = "装填弹幕中"
                            loading_text1!!.text = "(≧∇≦)"
                        }

                        if (isOnlineVideo) {
                            danmakuFile = File(cacheDir, "danmaku.xml")
                            if (danmakuFile!!.exists()) {
                                danmakuFile!!.delete()
                            }
                            downdanmu()
                        }

                        if (!destroyed && SharedPreferencesUtil.getBoolean("player_subtitle_autoshow", true)) {
                            downSubtitle(false)
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0) {
                            loadHighEnergyData()
                        }

                        if (!destroyed && isOnlineVideo && aid > 0 && cid > 0 && SharedPreferencesUtil.getBoolean("player_show_viewpoints", false)) {
                            loadViewPoints()
                        }
                    }) }, 60)
                }
            } catch (e: Exception) {
                Logu.e("互动视频", "跳转失败: " + e.message)
                runOnUiThread { MsgUtil.showMsg("跳转失败: " + e.message) }
            }
        })
    }

    private fun resumePlaybackIfPaused() {
        runOnUiThread {
            if (currentQuestion != null && currentQuestion!!.pauseVideo == 1 && !isPlaying) {
                ijkPlayer!!.start()
                isPlaying = true
                btn_control!!.setImageResource(R.drawable.btn_player_pause)
            }
        }
    }

    private fun getTargetQuality(): Int {
        val defaultQn = SharedPreferencesUtil.getInt("play_qn", 16)
        if (qnValueList == null || qnValueList!!.size == 0) {
            return if (currentQuality > 0) currentQuality else defaultQn
        }

        for (qn in qnValueList!!) {
            if (qn == currentQuality) {
                return currentQuality
            }
        }

        return if (currentQuality > 0) currentQuality else defaultQn
    }

    private fun executeNativeAction(nativeAction: String?) {
        if (interactionData == null || interactionData!!.hiddenVars == null || nativeAction == null || nativeAction.isEmpty()) {
            return
        }

        val actions = nativeAction.split(";")
        for (action in actions) {
            val action = action.trim { it <= ' ' }
            if (action.isEmpty()) continue

            try {
                if (action.contains("=")) {
                    val parts = action.split("=")
                    if (parts.size == 2) {
                        val varId = parts[0].trim { it <= ' ' }
                        val valueExpr = parts[1].trim { it <= ' ' }

                        val value = evaluateValueExpression(valueExpr)

                        for (`var` in interactionData!!.hiddenVars!!) {
                            if ((`var`.idV2 != null && `var`.idV2 == varId) ||
                                    (`var`.id != null && `var`.id == varId)) {
                                `var`.value = value
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logu.e("互动视频", "执行动作失败: " + action)
            }
        }
    }

    private fun evaluateValueExpression(expr: String): Long {
        try {
            var expr = expr.trim { it <= ' ' }
            if (expr.contains("+")) {
                val parts = expr.split("\\+")
                var sum: Long = 0
                for (part in parts) {
                    sum += evaluateValueExpression(part.trim { it <= ' ' })
                }
                return sum
            } else if (expr.contains("-")) {
                val parts = expr.split("-")
                var result = evaluateValueExpression(parts[0].trim { it <= ' ' })
                for (i in 1 until parts.size) {
                    result -= evaluateValueExpression(parts[i].trim { it <= ' ' })
                }
                return result
            } else {
                if (interactionData != null && interactionData!!.hiddenVars != null) {
                    for (`var` in interactionData!!.hiddenVars!!) {
                        if ((`var`.idV2 != null && expr == `var`.idV2) ||
                                (`var`.id != null && expr == `var`.id)) {
                            return `var`.value
                        }
                    }
                }
                if (expr.contains(".")) {
                    return java.lang.Double.parseDouble(expr).toLong()
                } else {
                    return expr.toLong()
                }
            }
        } catch (e: Exception) {
            Logu.e("互动视频", "值表达式计算失败: " + expr + ", 错误: " + e.message)
            return 0
        }
    }

    private fun showInteractionDebugDialog() {
        if (interactionData == null || interactionData!!.hiddenVars == null || interactionData!!.hiddenVars!!.isEmpty()) {
            MsgUtil.showMsg("当前没有互动视频变量")
            return
        }

        InteractionDebugActivity.setInteractionData(interactionData)
        val intent = Intent(this, InteractionDebugActivity::class.java)
        startActivity(intent)
    }

    override fun finish() {
        // #3：playerPause 内的 ijkPlayer.pause() 是 native 同步调用，
        // 网络缓冲/TCP 重传状态下可能阻塞主线程数百毫秒（退出卡死的元凶之一）。
        // 状态位同步翻转，真正的 pause 移到后台。
        isPlaying = false
        if (playbackWatchdog != null)
            playbackWatchdog!!.setUserWantsPlay(false)

        val progress: Long = try {
            if (ijkPlayer != null) ijkPlayer!!.getCurrentPosition() else -1L
        } catch (e: Exception) {
            -1L
        }
        if (progress >= 0) {
            val result = Intent()
            result.putExtra("progress", progress.toInt())
            Logu.d("进度回传", progress.toString())
            setResult(RESULT_OK, result)
            reportProgressInBackground(progress)
        } else
            setResult(RESULT_CANCELED)

        // 后台补一次 pause + 弹幕暂停（主线程不再等 native 返回）
        CenterThreadPool.run(Runnable {
            try {
                if (ijkPlayer != null && isPrepared)
                    ijkPlayer!!.pause()
                if (hasDanmaku() && danmakuReady())
                    danmakuController!!.pause()
            } catch (ignored: Exception) {
            }
        })

        // 进度条按钮态直接更新（不依赖 playerPause 的 UI 分支）
        if (btn_control != null)
            btn_control!!.setImageResource(R.drawable.btn_player_play)

        super.finish()
    }

    /**
     * 退出播放时在后台异步上报观看进度。
     *
     * 原先进度上报依赖已删除的 JumpToPlayerActivity 的 onActivityResult 回调，
     * 并且是在返回播放器界面之后才发起请求；现在改为退出瞬间丢到后台线程执行，
     * 不阻塞界面退出，也不受调用方是否接收 result 影响。
     */
    private fun reportProgressInBackground(progressMs: Long) {
        if (isLiveMode) return
        val reportAid = aid
        val reportCid = cid
        if (reportAid <= 0 || reportCid <= 0 || mid <= 0) return

        val progressSecond = progressMs / 1000
        CenterThreadPool.run(Runnable {
            try {
                HistoryApi.reportHistory(reportAid, reportCid, progressSecond)
            } catch (e: Exception) {
                MsgUtil.err("进度上报：", e)
            }
        })
    }
}
