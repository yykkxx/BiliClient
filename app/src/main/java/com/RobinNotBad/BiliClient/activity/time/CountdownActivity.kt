package com.RobinNotBad.BiliClient.activity.time

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TimeTracking
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.RobinNotBad.BiliClient.util.UsageColorUtil
import com.RobinNotBad.BiliClient.util.UsageLimitManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * 独立的全屏倒计时页面（v5.2.0 §2，需求原文「所有倒计时界面均为独立界面」）。
 *
 * ## 用法
 * - 不需要结果：`CountdownActivity.start(context, seconds, title, subtitle)`；
 * - 需要「等完了再干下一步」：自己构造 Intent，按下面两个布尔键控制归零行为，
 *   用 `registerForActivityResult(StartActivityForResult)` 收 `RESULT_OK`。
 *
 * | 键 | 默认 | 归零时 |
 * | --- | --- | --- |
 * | [EXTRA_RESULT_ON_END] | false | `setResult(RESULT_OK)`（先设结果） |
 * | [EXTRA_FINISH_ON_END] | true | `finish()` |
 *
 * 两者都为 true（最常见的「等完自动关」）时先 `setResult` 再 `finish`，结果不会丢；
 * `result=true + finish=false` 是给 `TimeBankBlockActivity` 那种「归零后页面还留在那儿
 * 展示一个 0」的场景——此时**不**自动关闭，单击屏幕才关闭（并交付已设置好的结果）。
 *
 * ## 为什么不用 postDelayed 递减（§2.3 硬要求）
 * 息屏 / 切后台时系统会把页面挂起，`Handler` 的延时消息可能一次补发很多条，
 * 用「每秒减 1」就会看到回来瞬间跳好几秒，等于「息屏期间继续计时」。
 * 本页改用 [SystemClock.elapsedRealtime] 做**累计**：只在「前台且亮屏」时累计已走毫秒，
 * 一旦暂停就把这段时长折进 [accumulatedMs]，恢复时重新记起点，天然不会跳秒。
 *
 * ## 两个视图层（§2.4）
 * 初始显示详情层（标题 / 副标题 / 「今日已使用 X」+ 进度条），单击整屏 → 大数字层，
 * 再点一次回详情层。切层只改 `visibility` + `alpha`（120ms 淡入淡出），不重建布局。
 *
 * ## 跨代理依赖（只调用，不实现）
 * - [UsageColorUtil.colorForUsed]：绿 / 黄 / 红（§4.2）；
 * - [TimeTracking.pauseTracking] / [TimeTracking.resumeTracking]：倒计时等待不计入使用时长（§4.3）。
 */
class CountdownActivity : BaseActivity() {

    companion object {
        /** 剩余秒数（Int，必填；0 或负数视为「立刻结束」） */
        const val EXTRA_SECONDS = "countdown_seconds"

        /** 标题（String，可选，例「今日配额已用完」） */
        const val EXTRA_TITLE = "countdown_title"

        /** 副标题（String，可选；不传则详情层不显示这一行） */
        const val EXTRA_SUBTITLE = "countdown_subtitle"

        /** 倒计时结束是否自动 finish（Boolean，默认 true） */
        const val EXTRA_FINISH_ON_END = "countdown_finish_on_end"

        /** 倒计时结束是否回调结果（Boolean，默认 false）；true 时先 setResult(RESULT_OK) */
        const val EXTRA_RESULT_ON_END = "countdown_result_on_end"

        /**
         * 「满格」配额分钟数（Int，可选，**增量扩展**，不传 = 60 分钟）。
         * 只影响详情层进度条的分母；签名里没有配额参数，所以做成可选的独立键，
         * 传/不传都不影响上面五个键的任何行为（老调用方零感知）。
         */
        const val EXTRA_QUOTA_MINUTES = "countdown_quota_min"

        /** 启动辅助：不需要结果时用它（需要结果请自己构造 Intent）。 */
        @JvmStatic
        fun start(context: Context, seconds: Int, title: String?, subtitle: String?) {
            val intent = Intent(context, CountdownActivity::class.java)
            intent.putExtra(EXTRA_SECONDS, seconds)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_SUBTITLE, subtitle)
            // 非 Activity 的 Context 启动 Activity 必须带 NEW_TASK
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /** 心跳间隔：200ms 足够让「秒」这一位准时翻动，又不至于白烧电 */
        private const val TICK_INTERVAL_MS = 200L

        /** 切层淡入淡出时长（§2.4 要求 120ms） */
        private const val LAYER_FADE_MS = 120L

        /** 毫秒 → 秒 的换算 */
        private const val MS_PER_SECOND = 1000L

        /** 进度条默认分母：60 分钟满格（调用方没给配额时用它） */
        private const val DEFAULT_FULL_MS = 60L * 60L * 1000L

        /** 圆屏适配开关：与 BaseActivity.setRound / 播放器共用同一个偏好键 */
        private const val PREF_ROUND_UI = "player_ui_round"

        /** 其他屏幕下的大数字上限（§2.4：min(120sp, 屏宽/4) 的 120sp 这一半） */
        private const val NORMAL_MAX_SP = 120f

        /** 圆屏下的大数字上限：圆里放不下 120sp，超过会被裁掉左右两头 */
        private const val ROUND_MAX_SP = 56f

        /** 等宽字体每个数字的宽度约为字号的 0.62 倍（数字位数 → 可用宽度的换算系数） */
        private const val MONO_DIGIT_ADVANCE = 0.62f

        /** 圆屏内接矩形约占屏宽的 0.62，留出这一点横向空间，文字才不会被圆形裁掉 */
        private const val ROUND_WIDTH_FRACTION = 0.62f

        /** 非圆屏用屏宽的 0.92：数字左右各留一点呼吸空间 */
        private const val NORMAL_WIDTH_FRACTION = 0.92f

        /** 圆屏左右留白（占屏宽比例），把内容挤进内接矩形 */
        private const val ROUND_SIDE_PADDING_FRACTION = 0.13f

        /** 非圆屏左右留白（dp） */
        private const val SIDE_PADDING_DP = 8f
    }

    private var detailLayer: View? = null
    private var bigLayer: View? = null
    private var bigSecondsView: TextView? = null
    private var bigTitleView: TextView? = null
    private var detailTitleView: TextView? = null
    private var detailSubtitleView: TextView? = null
    private var detailUsedView: TextView? = null
    private var detailProgress: ProgressBar? = null
    private var endHintView: View? = null

    /** 总时长（毫秒），来自 EXTRA_SECONDS（负数按 0） */
    private var totalMs = 0L

    /** 已累计走过的毫秒（只在暂停时折进来） */
    private var accumulatedMs = 0L

    /** 本轮「运行中」片段的起点（elapsedRealtime） */
    private var segmentStart = 0L

    /** 本页自己的计时器是否在走（= 前台 && 亮屏 && 未归零） */
    private var running = false

    /** 是否处于 onResume ~ onPause 之间 */
    private var foreground = false

    /** 屏幕是否亮着（息屏时等同于暂停） */
    private var screenOn = true

    /** 当前是否显示大数字层（初始为 false = 详情层） */
    private var showingBig = false

    /** 归零是否已处理过（防止重复 setResult / finish） */
    private var endHandled = false

    private var heartbeatJob: Job? = null
    private var receiverRegistered = false

    private var finishOnEnd = true
    private var resultOnEnd = false

    /** 进度条分母（毫秒）：有配额键用配额，否则 60 分钟 */
    private var quotaFullMs = DEFAULT_FULL_MS

    /** 上一次显示过的秒数：内容没变就不 setText，省掉每 200ms 的无谓重绘（§7） */
    private var lastShownSeconds = Int.MIN_VALUE

    /** 息屏 / 亮屏广播：SCREEN_OFF 等同 onPause，SCREEN_ON 等同恢复（§2.3） */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    syncClock()
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    syncClock()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_countdown)
        setPageName(getString(R.string.countdown_page_name))

        readExtras()
        bindViews()
        fillTexts()
        applyGeometry()
        refreshUsageInfo()
        // 归零时的初始画面：详情层（XML 里就是 visible），大数字层先藏起来
        applyLayer(showBig = false, animate = false)
        registerScreenReceiver()

        findViewById<View>(R.id.countdown_root)?.setOnClickListener { onScreenClick() }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        // 倒计时等待时间不计入使用时长（§4.1 / §4.3）
        TimeTracking.pauseTracking()
        // 等待期间用量不会变（追踪已暂停），只需在这一刻取一次真源
        refreshUsageInfo()

        if (totalMs <= 0L) {
            updateBigSeconds(0)
            onCountdownEnd()
            return
        }
        syncClock()
    }

    override fun onPause() {
        foreground = false
        syncClock()
        // 离开本页即恢复使用时长统计
        TimeTracking.resumeTracking()
        super.onPause()
    }

    override fun onDestroy() {
        // 心跳 / 广播 / 动画全在这里收口，避免协程继续持有已销毁的 Activity
        stopHeartbeat()
        unregisterScreenReceiver()
        detailLayer?.animate()?.cancel()
        bigLayer?.animate()?.cancel()
        detailLayer = null
        bigLayer = null
        bigSecondsView = null
        bigTitleView = null
        detailTitleView = null
        detailSubtitleView = null
        detailUsedView = null
        detailProgress = null
        endHintView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 初始化

    private fun readExtras() {
        val intent = getIntent()
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
        totalMs = max(0, seconds).toLong() * MS_PER_SECOND
        finishOnEnd = intent.getBooleanExtra(EXTRA_FINISH_ON_END, true)
        resultOnEnd = intent.getBooleanExtra(EXTRA_RESULT_ON_END, false)
        val quotaMin = intent.getIntExtra(EXTRA_QUOTA_MINUTES, 0)
        quotaFullMs = if (quotaMin > 0) quotaMin.toLong() * 60L * MS_PER_SECOND else DEFAULT_FULL_MS
    }

    private fun bindViews() {
        detailLayer = findViewById(R.id.countdown_detail)
        bigLayer = findViewById(R.id.countdown_big)
        bigSecondsView = findViewById(R.id.countdown_big_seconds)
        bigTitleView = findViewById(R.id.countdown_big_title)
        detailTitleView = findViewById(R.id.countdown_detail_title)
        detailSubtitleView = findViewById(R.id.countdown_detail_subtitle)
        detailUsedView = findViewById(R.id.countdown_detail_used)
        detailProgress = findViewById(R.id.countdown_detail_progress)
        endHintView = findViewById(R.id.countdown_end_hint)
    }

    /** 标题 / 副标题文案：标题缺省用「请稍候」，副标题缺省整行隐藏 */
    private fun fillTexts() {
        val title = getIntent().getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.countdown_default_title)
        bigTitleView?.text = title
        detailTitleView?.text = title

        val subtitle = getIntent().getStringExtra(EXTRA_SUBTITLE)?.takeIf { it.isNotBlank() }
        val subtitleView = detailSubtitleView
        if (subtitle == null || subtitleView == null) {
            subtitleView?.visibility = View.GONE
        } else {
            subtitleView.text = subtitle
            subtitleView.visibility = View.VISIBLE
        }
    }

    /**
     * 字号与边距自适应：大数字取 `min(120sp, 屏宽字宽能放下的最大值)`，
     * 圆屏再压到 56sp 并把整页左右往里推——圆屏上 120sp 的四位数会被裁掉两端。
     */
    private fun applyGeometry() {
        val widthPx = if (window_width > 0) window_width else resources.displayMetrics.widthPixels
        val round = SharedPreferencesUtil.getBoolean(PREF_ROUND_UI, false)

        val fraction = if (round) ROUND_WIDTH_FRACTION else NORMAL_WIDTH_FRACTION
        val digits = digitCountOf(totalMs / MS_PER_SECOND)
        // 等宽数字：可用宽度 / (位数 × 单字宽比例) 即能放下的最大字号
        val fitPx = widthPx * fraction / (digits * MONO_DIGIT_ADVANCE)
        val capSp = if (round) ROUND_MAX_SP else NORMAL_MAX_SP
        val capPx = ToolsUtil.sp2px(capSp).toFloat()
        bigSecondsView?.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(fitPx, capPx))

        val sidePadding = if (round) (widthPx * ROUND_SIDE_PADDING_FRACTION).toInt()
        else ToolsUtil.dp2px(SIDE_PADDING_DP)
        detailLayer?.setPadding(sidePadding, 0, sidePadding, 0)
        bigLayer?.setPadding(sidePadding, 0, sidePadding, 0)
    }

    /** 显示的秒数位数（用来算字号）；最少按 2 位、最多按 6 位估宽 */
    private fun digitCountOf(seconds: Long): Int =
        seconds.coerceAtLeast(0L).toString().length.coerceIn(2, 6)

    // ------------------------------------------------------------------ 计时核心

    /**
     * 把「应该走 / 应该停」落到计时器状态上。
     *
     * 只有 `前台 && 亮屏 && 未归零` 才走；每次变化都把当前片段折进 [accumulatedMs]，
     * 恢复时重新记 [segmentStart] —— 这样息屏、切后台的时间一律不计，回来也不会跳秒。
     */
    private fun syncClock() {
        val shouldRun = foreground && screenOn && !endHandled && totalMs > 0L
        if (shouldRun && !running) {
            segmentStart = SystemClock.elapsedRealtime()
            running = true
            startHeartbeat()
        } else if (!shouldRun && running) {
            accumulatedMs += SystemClock.elapsedRealtime() - segmentStart
            running = false
            stopHeartbeat()
            refreshRemaining()
        }
    }

    /** 当前已经走过的总毫秒：已沉淀的 + 正在跑的这段（未在跑就只算沉淀部分） */
    private fun currentElapsedMs(): Long =
        if (running) accumulatedMs + (SystemClock.elapsedRealtime() - segmentStart) else accumulatedMs

    /**
     * 刷新剩余秒数。毫秒 → 秒用**向上取整**：还剩 1.2 秒时显示「2」、还剩 0.2 秒时显示「1」，
     * 永远不会在「其实还有时间」的时候先掉到 0（设计判断见 notes 里的说明）。
     */
    private fun refreshRemaining() {
        if (endHandled) return
        val remainingMs = totalMs - currentElapsedMs()
        if (remainingMs <= 0L) {
            updateBigSeconds(0)
            onCountdownEnd()
            return
        }
        // (x + 999) / 1000 即 ceil(x / 1000)，避免浮点
        updateBigSeconds(((remainingMs + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt())
    }

    /** 只在数字真的变了才改 TextView：200ms 一次的心跳不该每次都触发重新 measure/layout */
    private fun updateBigSeconds(seconds: Int) {
        if (seconds == lastShownSeconds) return
        lastShownSeconds = seconds
        bigSecondsView?.text = seconds.toString()
    }

    /**
     * 归零处理（幂等）。顺序照 §2.2：先 `setResult(RESULT_OK)`（仅在需要结果时），
     * 再按 [finishOnEnd] 决定是否 `finish()`；不 finish 的场景（TimeBankBlockActivity）
     * 会在两层都挂出「点击屏幕结束」，点一下才关。
     */
    private fun onCountdownEnd() {
        if (endHandled) return
        endHandled = true
        accumulatedMs = totalMs
        running = false
        stopHeartbeat()
        updateBigSeconds(0)

        if (resultOnEnd) setResult(RESULT_OK)
        if (finishOnEnd) {
            if (!isDestroyed) finish()
            return
        }
        // 不自动关闭：告知用户这一页已经走完，点击即关闭（结果已设置好）
        endHintView?.visibility = View.VISIBLE
        bigTitleView?.text = getString(R.string.countdown_end_hint)
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = CoroutineScopes.launchUi {
            while (running && !endHandled) {
                refreshRemaining()
                if (endHandled) break
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ------------------------------------------------------------------ 用量与颜色

    /**
     * 「今日已使用 X」+ 进度条：文字与进度条都取 [UsageColorUtil] 算出的
     * 绿 / 黄 / 红（0~30min 绿、30~60min 黄、>60min 红），进度按 [quotaFullMs] 做分母。
     */
    private fun refreshUsageInfo() {
        val usedMs = UsageLimitManager.todayUsedMs()
        val color = UsageColorUtil.colorForUsed(usedMs)

        detailUsedView?.let { view ->
            view.text = getString(R.string.countdown_today_used, UsageLimitManager.formatUsed(usedMs))
            view.setTextColor(color)
        }

        detailProgress?.let { bar ->
            bar.max = quotaFullMs.toInt()
            bar.progress = usedMs.coerceIn(0L, quotaFullMs).toInt()
            bar.progressTintList = ColorStateList.valueOf(color)
            bar.progressBackgroundTintList =
                ColorStateList.valueOf(getColor(R.color.countdown_progress_track))
        }
    }

    // ------------------------------------------------------------------ 两个视图层

    /** 单击整屏：未归零时在两层之间切换；已归零但页面还留着（不自动关）时点一下即关闭 */
    private fun onScreenClick() {
        if (endHandled && !finishOnEnd) {
            if (!isDestroyed) finish()
            return
        }
        showingBig = !showingBig
        applyLayer(showBig = showingBig, animate = true)
    }

    /** 切层：只动 visibility + alpha，不重建布局；`animate=false` 用于初始化定位 */
    private fun applyLayer(showBig: Boolean, animate: Boolean) {
        val showView = (if (showBig) bigLayer else detailLayer) ?: return
        val hideView = if (showBig) detailLayer else bigLayer

        showView.animate().cancel()
        hideView?.animate()?.cancel()

        if (!animate) {
            showView.alpha = 1f
            showView.visibility = View.VISIBLE
            if (hideView != null) {
                hideView.alpha = 1f
                hideView.visibility = View.GONE
            }
            return
        }

        showView.alpha = 0f
        showView.visibility = View.VISIBLE
        showView.animate().alpha(1f).setDuration(LAYER_FADE_MS).start()

        if (hideView == null) return
        hideView.animate().alpha(0f).setDuration(LAYER_FADE_MS).withEndAction {
            if (isDestroyed) return@withEndAction
            hideView.visibility = View.GONE
            // 复位透明度，下次淡入时才不会从「上次的 0」开始（看起来像闪一下）
            hideView.alpha = 1f
        }.start()
    }

    // ------------------------------------------------------------------ 息屏广播

    /**
     * 注册 `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`。
     *
     * §2.3 要求用 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`，但本项目锁定的
     * `androidx.core:core-ktx:1.8.0` 里 `ContextCompat` 还没有这个带 flags 的重载（1.9.0 才加），
     * 因此这里直接调平台 API：API 33+ 明确传 `RECEIVER_NOT_EXPORTED`（等价语义，且 app 才能在
     * 高 targetSdk 上注册非系统广播），API 33 以下平台默认不导出，无需 flags。
     * 注册/注销都在本页生命周期内配对，不会泄漏。
     */
    @Suppress("DEPRECATION")
    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (ignored: Exception) {
            // 极少数 ROM 对系统广播注册有限制：失败就退化成「只靠 onPause 暂停」，不影响主流程
            receiverRegistered = false
        }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (ignored: Exception) {
            // onCreate 已注册、onDestroy 注销，重复注销只可能是系统状态错乱，吞掉即可
        }
        receiverRegistered = false
    }
}
