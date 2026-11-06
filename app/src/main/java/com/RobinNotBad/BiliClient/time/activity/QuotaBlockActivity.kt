// 本文件对外暴露两个类型：页面 QuotaBlockActivity 与只服务本页的长按进度环 LongPressRingView。
// detekt 的 MatchingDeclarationName 要求「文件名 = 唯一顶层类名」，两者对不上，故在此豁免。
@file:Suppress("MatchingDeclarationName")

package com.RobinNotBad.BiliClient.time.activity

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.Gate
import com.RobinNotBad.BiliClient.time.TimeFormat
import com.RobinNotBad.BiliClient.time.TimePrefs
import com.RobinNotBad.BiliClient.time.UsageTracker
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.google.android.material.button.MaterialButton

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * 配额耗尽拦截页（含「长按 45 秒解除今天限制」）。
 *
 * # 三条出路（用户原话「三条出路」+ 追加需求）
 * 1. **等到明天**：写「今天不再进入应用」的语义 —— 但本套模型里没有「关闭今天」这个键，
 *    等价表达就是**退出应用并且不再拦**。这里采用最小侵入的做法：`finishAffinity()` 直接退出，
 *    明天进程是新的，Gate 会重新问日子类型，配额自然重置（跨天即归零，逻辑层已保证）。
 *    换句话说「等到明天」不需要写任何偏好，**退出**本身就是它。
 * 2. **去借时间**：跳 [TimeBankActivity]。本页在 `onResume` 里重新判定，
 *    借到时间后配额变大 → [Gate.canUseNow] 为 true → 本页**自己**放行（见「自动放行」）。
 * 3. **退出**：`finishAffinity()`。
 *
 * # 长按 45 秒解除今天限制（用户追加需求）
 * - 秒数来自 [TimePrefs.longPressReleaseSec]（默认 45），**不硬编码**；
 * - 按住期间进度环走动 + 按钮文案显示「剩余 N 秒」；
 * - **抬手立即取消**（`ACTION_UP` / `ACTION_CANCEL` 都取消，并把环复位）——
 *   用户原话「抬手取消」；
 * - 满 45 秒 → [DailyPlan.bypassToday] → 放行。
 * - 为什么用 `ACTION_DOWN` + 自己计时而不是 `View.setOnLongClickListener`：
 *   系统 LongClick 只有 ~500ms~1.5s 的量级，且**无法给出进度**，更无法在抬手时回到中间态；
 *   而且长按 45 秒必须给用户**可视进度**，否则他不知道自己还要按多久。
 *
 * # 自动放行（Lead 点名的硬约束）
 * 本页**不依赖外部回调**自己判断放行：
 * - `onResume`（从借时间页回来 / 从倒计时页回来）时问一次 [Gate.canUseNow] 与
 *   [DailyPlan.isBypassedToday]，满足就 `setResult(RESULT_OK)` + `finish()`；
 * - 长按满 45 秒时立刻放行（不等 onResume）。
 * 这样「去借时间 → 借完 → 回到本页」这条链路对用户是**自动**的：
 * 他不需要再点任何按钮，页面自己就消失了。
 *
 * # 计时
 * 拦截页停留**不计入使用时长**（否则「被拦着等了 5 分钟」会让配额更不够，用户会觉得被双重处罚）。
 * onResume 调 [UsageTracker.pause]、onPause 调 [UsageTracker.resume]，成对出现。
 */
class QuotaBlockActivity : BaseActivity() {

    private var detailView: TextView? = null
    private var releaseTitleView: TextView? = null
    private var releaseButton: MaterialButton? = null
    private var releaseRing: LongPressRingView? = null

    /** 长按心跳任务；非 null = 正在按住 */
    private var holdJob: Job? = null

    /** 长按已走秒数（按住期间递增；抬手归零） */
    private var heldSeconds = 0

    /** 长按总秒数（来自 TimePrefs，默认 45） */
    private var holdTotalSeconds = 0

    /** 拦截页是否正在退出（防重复 finish） */
    private var leaving = false

    /** 「去借时间」的结果回调：回来后会走 onResume，但显式回调一次更稳（onResume 可能不触发） */
    private val borrowLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            tryPassThrough()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quota_block)
        setPageName(getString(R.string.tm2_page_block))
        // 拦截页没有「返回上一层」的语义（上一页就是启动页），所以不给顶栏退出箭头，
        // 但保留系统返回键（用户按返回 = 回桌面/上一个 Activity，等同「退出」）

        detailView = findViewById(R.id.tm2_block_detail)
        releaseTitleView = findViewById(R.id.tm2_block_release_title)
        releaseButton = findViewById(R.id.tm2_block_release)
        releaseRing = findViewById(R.id.tm2_block_release_ring)

        holdTotalSeconds = TimePrefs.longPressReleaseSec()
        bindReleaseTexts()
        bindActions()
        refreshDetail()
    }

    override fun onResume() {
        super.onResume()
        // 拦截页停留不计入使用时长（成对：onPause 里 resume）
        UsageTracker.pause()
        refreshDetail()
        // 从「借时间」页回来且额度已够 → 自己放行（Lead 点名的硬约束）
        tryPassThrough()
    }

    override fun onPause() {
        // 切后台/息屏时长按必须取消：否则回到前台时计时还在跑，等于「偷偷放行」
        cancelHold()
        UsageTracker.resume()
        super.onPause()
    }

    override fun onDestroy() {
        holdJob?.cancel()
        holdJob = null
        detailView = null
        releaseTitleView = null
        releaseButton = null
        releaseRing = null
        super.onDestroy()
    }

    // ==========================================================================================
    // 文案
    // ==========================================================================================

    /** 详情：已用 / 配额；休息日与上学日两种口径（配额是 MAX_VALUE 时不能显示天文数字） */
    private fun refreshDetail() {
        val usedMs = DailyPlan.usedMsToday()
        val quotaMin = DailyPlan.quotaMinutesToday()
        detailView?.text = if (quotaMin == DailyPlan.QUOTA_UNLIMITED) {
            getString(R.string.tm2_block_detail_rest, TimeFormat.duration(usedMs))
        } else {
            getString(R.string.tm2_block_detail, TimeFormat.duration(usedMs), quotaMin)
        }
    }

    /** 长按区的标题与按钮文案（秒数全部来自偏好） */
    private fun bindReleaseTexts() {
        releaseTitleView?.text = getString(R.string.tm2_block_release_title, holdTotalSeconds)
        releaseButton?.text = getString(R.string.tm2_block_release_press, holdTotalSeconds)
        // 进度环初始为 0
        releaseRing?.update(0f, holdTotalSeconds)
    }

    // ==========================================================================================
    // 三条出路
    // ==========================================================================================

    @Suppress("ClickableViewAccessibility")
    private fun bindActions() {
        // 出路 1：等到明天 == 退出（跨天后 Gate 会重新问日子类型，配额自然重置）
        findViewById<View>(R.id.tm2_block_wait)?.setOnClickListener {
            MsgUtil.showMsg(getString(R.string.tm2_block_wait_done))
            leaveApp()
        }
        // 出路 2：去借时间（借完回来自动放行，见 tryPassThrough）
        findViewById<View>(R.id.tm2_block_borrow)?.setOnClickListener {
            borrowLauncher.launch(Intent(this, TimeBankActivity::class.java))
        }
        // 出路 3：退出
        findViewById<View>(R.id.tm2_block_exit)?.setOnClickListener { leaveApp() }

        // 长按区：按钮与环都可按（点环也算，扩大命中面积）
        val area = findViewById<View>(R.id.tm2_block_release_area)
        val listener = HoldListener()
        releaseButton?.setOnTouchListener(listener)
        area?.setOnTouchListener(listener)
    }

    /**
     * 退出应用。
     *
     * 用 `finishAffinity()` 而不是 `finish()`：`finish()` 只关掉本页，
     * 后面的启动页会立刻重问 Gate → 又回到拦截页，用户看到的是「点了退出又弹回来」。
     * `finishAffinity()` 会把同一 taskAffinity 的整条 Activity 栈一起关掉，真的回到桌面。
     */
    private fun leaveApp() {
        if (leaving) return
        leaving = true
        finishAffinity()
    }

    /**
     * 自动放行判断（幂等）。
     *
     * 两个条件任一满足就放行：
     * - [DailyPlan.isBypassedToday]：刚长按解除成功（或已被解除过）；
     * - [Gate.canUseNow]：配额又够了（借到了时间）。
     *
     * 放行 = `setResult(RESULT_OK)` + `finish()`。启动页收到结果后**会再问一次 Gate**，
     * 那时偏好已是新状态，于是直接 PASS 进主界面（见 LauncherActivity 的类注释）。
     */
    private fun tryPassThrough() {
        if (leaving || isDestroyed || isFinishing) return
        if (DailyPlan.isBypassedToday()) {
            passThrough(getString(R.string.tm2_block_bypassed))
            return
        }
        if (Gate.canUseNow()) {
            passThrough(getString(R.string.tm2_block_borrowed_enough))
        }
    }

    private fun passThrough(message: String) {
        if (leaving) return
        leaving = true
        cancelHold()
        MsgUtil.showMsg(message)
        setResult(RESULT_OK)
        if (!isDestroyed) finish()
    }

    // ==========================================================================================
    // 长按 45 秒（进度环 + 剩余秒数 + 抬手取消）
    // ==========================================================================================

    /**
     * 长按监听。
     *
     * 只处理 DOWN / UP / CANCEL 三件事：按下起心跳、抬手（含手势被父容器抢走的 CANCEL）取消。
     * 返回 true 是必须的 —— 返回 false 时收不到后续事件，抬起后环会一直停在半途。
     */
    private inner class HoldListener : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startHold()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 抬手/取消：已经满时的放行在 onHoldFinished 里；这里只处理没满的情况
                    onHoldReleased()
                }
            }
            return true
        }
    }

    /** 开始长按：复位计数、起心跳 */
    private fun startHold() {
        if (holdJob != null || leaving) return
        heldSeconds = 0
        releaseRing?.update(0f, holdTotalSeconds)
        holdJob = CoroutineScopes.launchUi {
            while (heldSeconds < holdTotalSeconds) {
                // 每秒推进一格；进度 = 已按住 / 总秒数
                releaseRing?.update(heldSeconds.toFloat(), holdTotalSeconds)
                releaseButton?.text =
                    getString(R.string.tm2_block_release_holding, holdTotalSeconds - heldSeconds)
                delay(HOLD_TICK_MS)
                heldSeconds += 1
            }
            // 先把环画到满（视觉上「成了」），再放行 —— 差这一下用户会觉得「按到头却没反应」
            releaseRing?.update(holdTotalSeconds.toFloat(), holdTotalSeconds)
            releaseButton?.text = getString(R.string.tm2_block_release_done)
            // 满时：清掉任务标记再放行（放行里还会调 cancelHold，必须允许）
            holdJob = null
            onHoldFinished()
        }
    }

    /**
     * 抬手：取消并复位。
     *
     * 用户原话「抬手取消」——**已按的秒数不做部分保存**，下次按从头开始。
     * 这看起来严厉，但正是这个操作的意义：它是「惩罚性」的，能随便推开就等于没有。
     */
    private fun onHoldReleased() {
        if (holdJob == null) {
            // 没有正在进行的按住（例如刚满放行后系统又补发一个 UP）：什么也不做
            return
        }
        cancelHold()
        MsgUtil.showMsg(getString(R.string.tm2_block_release_cancel))
    }

    /** 取消长按并复位环/文案 */
    private fun cancelHold() {
        holdJob?.cancel()
        holdJob = null
        heldSeconds = 0
        releaseRing?.update(0f, holdTotalSeconds)
        releaseButton?.text = getString(R.string.tm2_block_release_press, holdTotalSeconds)
    }

    /** 满时长：解除今天限制 → 放行 */
    private fun onHoldFinished() {
        DailyPlan.bypassToday()
        passThrough(getString(R.string.tm2_block_release_done))
    }

    private companion object {
        /** 长按心跳：1 秒（进度环每秒走一格，够平滑，也不费电） */
        const val HOLD_TICK_MS = 1000L
    }
}

/**
 * 长按进度环（自绘）。
 *
 * 画一个轨道 + 一段进度弧 + 中间剩余秒数。之所以把「剩余秒数」也画在环里而不是只放按钮上：
 * 用户按住时视线在手指上，环是唯一「余光能看到进度」的地方，
 * 而按钮文案可能被手指遮挡。
 *
 * # onDraw 零分配
 * 画笔构造时建好，只有一个复用 [rect]，秒数文本在 [update] 时算好。
 */
class LongPressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 已完成的秒数（0..total） */
    private var held = 0f

    /** 总秒数 */
    private var total = 1

    /** 环中间的文本（预格式化） */
    private var centerText = ""

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    init {
        val density = resources.displayMetrics.density
        val stroke = RING_STROKE_DP * density
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        textPaint.textSize = RING_TEXT_SP * resources.displayMetrics.scaledDensity
        trackPaint.color = ContextCompat.getColor(context, R.color.tm2_chart_bar_empty)
        arcPaint.color = ContextCompat.getColor(context, R.color.tm2_level_yellow)
        textPaint.color = ContextCompat.getColor(context, R.color.tm2_text_secondary)
    }

    /**
     * 更新进度。
     *
     * @param heldSeconds 已按住秒数（0 = 没按，复位态）
     * @param totalSeconds 总秒数（<=0 按 1 处理，避免除零）
     */
    fun update(heldSeconds: Float, totalSeconds: Int) {
        held = heldSeconds.coerceAtLeast(0f)
        total = totalSeconds.coerceAtLeast(1)
        val remaining = (total - held).toInt().coerceAtLeast(0)
        centerText = if (held <= 0f) "$total" else "$remaining"
        // 按住期间颜色从黄变绿：满时是绿的（「可以了」的视觉信号）
        val ratio = (held / total.toFloat()).coerceIn(0f, 1f)
        arcPaint.color = ContextCompat.getColor(
            context,
            if (ratio >= 1f) R.color.tm2_level_green else R.color.tm2_level_yellow
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val pad = RING_STROKE_DP * resources.displayMetrics.density / 2f +
            RING_PAD_DP * resources.displayMetrics.density
        rect.set(pad, pad, size - pad, size - pad)

        canvas.drawArc(rect, START_ANGLE, FULL_SWEEP, false, trackPaint)
        val ratio = (held / total.toFloat()).coerceIn(0f, 1f)
        if (ratio > 0f) {
            canvas.drawArc(rect, START_ANGLE, FULL_SWEEP * ratio, false, arcPaint)
        }

        // 中间数字：基线用字号近似居中，避免每帧 new FontMetrics
        canvas.drawText(centerText, size / 2f, size / 2f + textPaint.textSize * 0.35f, textPaint)
    }

    /**
     * 让点击环也能算长按起点：本 View 自己不处理触摸，
     * 由父容器 [R.id.tm2_block_release_area] 的 OnTouchListener 统一处理（避免两套计时）。
     * 这里只声明「可以接收触摸」，否则 ACTION_DOWN 会被父容器直接吞掉而环收不到任何反馈。
     */
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private companion object {
        /** 环的描边宽度（dp） */
        const val RING_STROKE_DP = 5f

        /** 与控件边缘的额外留白（dp） */
        const val RING_PAD_DP = 3f

        /** 中间秒数字号 */
        const val RING_TEXT_SP = 13f

        /** 从 12 点方向开始（-90°） */
        const val START_ANGLE = -90f

        /** 一整圈 */
        const val FULL_SWEEP = 360f
    }
}
