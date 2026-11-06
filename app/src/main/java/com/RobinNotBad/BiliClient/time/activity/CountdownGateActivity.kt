package com.RobinNotBad.BiliClient.time.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.Gate
import com.RobinNotBad.BiliClient.time.TimeBank
import com.RobinNotBad.BiliClient.time.TimeFormat
import com.RobinNotBad.BiliClient.time.UsageTracker
import com.RobinNotBad.BiliClient.util.CoroutineScopes

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * 打开应用时的倒计时等待页。
 *
 * # 需求（用户原话，逐条对应下面的实现）
 * 1. 「先是大的居中的倒计时数字」→ 层 1（[R.id.tm2_countdown_seconds]），字号按位数动态算；
 * 2. 「单击可以浏览当天使用时间详情，再次单击可以切回去」→ [onScreenClick] 切层；
 * 3. 「查看使用时间详情的时候继续倒计时」→ 倒计时**不依赖当前显示哪一层**，
 *    只依赖「前台 && 亮屏 && 未归零」三个条件；
 * 4. 「切到后台或者是息屏的时候不继续倒计时」→ [syncClock] 在 onPause / SCREEN_OFF 时
 *    把已走时长折进 [accumulatedMs] 并停掉心跳；onResume / SCREEN_ON 重新记起点。
 *    这样回来时**不会跳秒**（用 `postDelayed` 逐秒减 1 的写法，息屏时消息会被补发，回来会跳一大截）；
 * 5. 归零后 `setResult(RESULT_OK)` + `finish()` 自动放行（启动页收到后再问一次 Gate）。
 *
 * # 为什么用「累计 elapsedRealtime」而不是「每秒减 1」
 * 系统在息屏/后台会把页面挂起，Handler 的延时消息可能一次补发很多条。
 * 用「每秒减 1」就等价于「息屏期间继续倒计时」，与需求 4 直接冲突。
 * 本页只在**运行中的片段**里读 `elapsedRealtime`，暂停时把这一截折进累计值 ——
 * 天然不跳秒，也不需要任何「补偿」逻辑。
 *
 * # 与 UsageTracker 的成对调用（Lead 点名要求，务必保持）
 * - `onResume` → [UsageTracker.pause]
 * - `onPause` → [UsageTracker.resume]
 * UsageTracker 内部是**计数器**（不是布尔），所以「拦截页 → 倒计时页」这种嵌套暂停天然正确。
 * **漏一次 resume 的唯一后果是「使用时长永久不涨」**——这是本套设计最危险的失效模式，
 * 所以这两行必须与 onPause/onResume 严格成对。
 *
 * 需求「等待时间不计入使用时长」与「切后台不继续倒计时」其实是同一件事的两面：
 * 等待不该白扣配额。所以这里既暂停计时统计、又暂停倒计时本体。
 */
class CountdownGateActivity : BaseActivity() {

    private var bigLayer: View? = null
    private var detailLayer: View? = null
    private var secondsView: TextView? = null
    private var titleView: TextView? = null
    private var pausedHintView: TextView? = null
    private var detailUsedView: TextView? = null
    private var detailQuotaView: TextView? = null
    private var detailRemainingView: TextView? = null
    private var detailBorrowedView: TextView? = null
    private var detailLevelView: TextView? = null

    /** 本次需要等的总毫秒（来自 [Gate.waitSeconds]） */
    private var totalMs = 0L

    /** 已累计走过的毫秒（只在暂停时折进来） */
    private var accumulatedMs = 0L

    /** 本轮「运行中」片段的起点（elapsedRealtime）；0 = 没在跑 */
    private var segmentStart = 0L

    /** 本页是否处于 onResume ~ onPause 之间 */
    private var foreground = false

    /** 屏幕是否亮着（息屏等同暂停） */
    private var screenOn = true

    /** 是否已显示详情层（false = 大数字层，默认） */
    private var showingDetail = false

    /** 归零是否已处理（防重复 setResult / finish） */
    private var endHandled = false

    /** 上一次显示的秒数：内容没变就不 setText，省掉每 200ms 的无谓 remeasure */
    private var lastShownSeconds = Int.MIN_VALUE

    private var heartbeatJob: Job? = null
    private var receiverRegistered = false

    /** 息屏/亮屏广播：SCREEN_OFF 等同 onPause，SCREEN_ON 等同恢复（需求 4） */
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
        setContentView(R.layout.activity_countdown_gate)
        setPageName(getString(R.string.tm2_page_countdown))

        bigLayer = findViewById(R.id.tm2_countdown_big)
        detailLayer = findViewById(R.id.tm2_countdown_detail)
        secondsView = findViewById(R.id.tm2_countdown_seconds)
        titleView = findViewById(R.id.tm2_countdown_title)
        pausedHintView = findViewById(R.id.tm2_countdown_paused)
        detailUsedView = findViewById(R.id.tm2_countdown_detail_used)
        detailQuotaView = findViewById(R.id.tm2_countdown_detail_quota)
        detailRemainingView = findViewById(R.id.tm2_countdown_detail_remaining)
        detailBorrowedView = findViewById(R.id.tm2_countdown_detail_borrowed)
        detailLevelView = findViewById(R.id.tm2_countdown_detail_level)

        // 秒数来自 Gate（它已经是唯一的口径来源：21:30 之后返回 0）
        totalMs = Gate.waitSeconds().toLong() * MS_PER_SECOND

        findViewById<View>(R.id.tm2_countdown_root)?.setOnClickListener { onScreenClick() }
        fillTexts()
        applyDigitSize()
        refreshDetail()
        registerScreenReceiver()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        // 等待时间不计入使用时长（成对：本页 onPause 里 resume）
        UsageTracker.pause()
        // 等待期间用量不会变（追踪已暂停），只需在这一刻取一次真源
        refreshDetail()
        if (totalMs <= 0L) {
            // 没有需要等的（例如 Gate 判断已过 21:30）：立刻放行，不要闪一个 0
            updateSeconds(0)
            onCountdownEnd()
            return
        }
        syncClock()
    }

    override fun onPause() {
        foreground = false
        // 先把「跑到此刻」这一截折进累计值，再停心跳
        syncClock()
        UsageTracker.resume()
        super.onPause()
    }

    override fun onDestroy() {
        // 心跳/广播/引用全部在这里收口，避免协程继续持有已销毁的 Activity
        stopHeartbeat()
        unregisterScreenReceiver()
        bigLayer = null
        detailLayer = null
        secondsView = null
        titleView = null
        pausedHintView = null
        detailUsedView = null
        detailQuotaView = null
        detailRemainingView = null
        detailBorrowedView = null
        detailLevelView = null
        super.onDestroy()
    }

    // ==========================================================================================
    // 文案
    // ==========================================================================================

    /**
     * 标题按当前档位分两种说法（黄档 / 红档）。
     *
     * 为什么不用一句通用文案：用户原话是「30 分钟到 60 分钟等待 5 秒，60 分钟以上等待 10 秒」，
     * 把「你现在是黄档、要等 5 秒」写出来，用户才知道这不是随机卡住。
     */
    private fun fillTexts() {
        val usedMs = DailyPlan.usedMsToday()
        val waitSec = Gate.waitSeconds()
        val titleRes = when (DailyPlan.levelOf(usedMs)) {
            DailyPlan.LEVEL_RED -> R.string.tm2_countdown_title_high
            DailyPlan.LEVEL_YELLOW -> R.string.tm2_countdown_title_mid
            else -> R.string.tm2_countdown_title
        }
        // 绿色档不会进本页（等待 0 秒），但兜底用无参数那句，避免格式化参数对不上
        titleView?.text = if (DailyPlan.waitSecondsFor(usedMs) > 0) {
            getString(titleRes, TimeFormat.duration(usedMs), waitSec)
        } else {
            getString(titleRes)
        }

        // 大数字的颜色也跟三档语义走（黄档黄字、红档红字）：
        // 「为什么让我等」这件事，颜色比一句 13sp 的说明更快被看见。
        // **色值只从 TimeFormat 取**，不在这里写死任何颜色 —— 三档色全 App 只有一个来源。
        secondsView?.setTextColor(TimeFormat.levelColor(this, DailyPlan.levelOf(usedMs)))
    }

    /**
     * 详情层的全部数字。
     *
     * 剩余量一律走 [TimeFormat.remainingText]：休息日配额是 `Int.MAX_VALUE`，
     * 直接用 [DailyPlan.remainingMsToday] 会得到约 1.3 亿毫秒的天文数字，
     * 显示成「今日还剩 1549 天」——这个坑 v5.2.0 踩过（Lead 也提醒过）。
     */
    private fun refreshDetail() {
        val usedMs = DailyPlan.usedMsToday()
        val quotaMin = DailyPlan.quotaMinutesToday()

        detailUsedView?.text = getString(R.string.tm2_countdown_detail_used, TimeFormat.duration(usedMs))
        detailUsedView?.setTextColor(TimeFormat.levelColor(this, DailyPlan.levelOf(usedMs)))

        // 配额：休息日与上学日的说法完全不同，分开写（不要显示 "配额 2147483647 分钟"）
        detailQuotaView?.text = if (quotaMin == DailyPlan.QUOTA_UNLIMITED) {
            getString(R.string.tm2_countdown_detail_quota_rest)
        } else {
            getString(R.string.tm2_countdown_detail_quota, quotaMin)
        }

        detailRemainingView?.text = TimeFormat.remainingText(quotaMin, DailyPlan.remainingMsToday())

        val borrowed = TimeBank.borrowedTotalMinutes()
        if (borrowed > 0) {
            detailBorrowedView?.visibility = View.VISIBLE
            detailBorrowedView?.text = getString(R.string.tm2_countdown_detail_borrowed, borrowed)
        } else {
            detailBorrowedView?.visibility = View.GONE
        }

        val level = DailyPlan.levelOf(usedMs)
        detailLevelView?.text = getString(R.string.tm2_countdown_detail_level, TimeFormat.levelName(level))
        detailLevelView?.setTextColor(TimeFormat.levelColor(this, level))
    }

    // ==========================================================================================
    // 字号自适应（圆屏）
    // ==========================================================================================

    /**
     * 大数字的字号按「屏宽 / 位数」算。
     *
     * 为什么必须动态算：这里最长可能是 `00:60` 这种 5 字符（[TimeFormat.countdown] 两位补零），
     * 手表屏宽只有 300~400px，72sp 的五字符会直接超出屏宽被裁掉两端 —— 用户会看到
     * 「数字被切掉一截」，那比字号小一点难看得多。
     * 等宽字体每个字符宽度约 0.6 倍字号，用它做换算。
     * 圆屏再从 [ROUND_WIDTH_FRACTION] 收一点（圆的内接区域比矩形窄）。
     */
    private fun applyDigitSize() {
        val widthPx = if (window_width > 0) window_width else resources.displayMetrics.widthPixels
        val round = isRoundUi()
        val fraction = if (round) ROUND_WIDTH_FRACTION else NORMAL_WIDTH_FRACTION
        // 位数：`mm:ss` 固定 5 个字符；超过 59 分钟时也是 5 个（TimeFormat 不引入 hh 段）
        val chars = DIGIT_CHARS
        val fitPx = widthPx * fraction / (chars * MONO_ADVANCE)
        val capSp = if (round) ROUND_MAX_SP else NORMAL_MAX_SP
        val capPx = sp2px(capSp)
        secondsView?.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(fitPx, capPx))
    }

    /** 是否开启「圆屏适配」——复用播放器/BaseActivity.setRound 的那个偏好键，保证全局一致 */
    private fun isRoundUi(): Boolean =
        runCatching {
            com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.getBoolean(PREF_ROUND_UI, false)
        }.getOrDefault(false)

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    // ==========================================================================================
    // 计时核心
    // ==========================================================================================

    /**
     * 把「该走 / 该停」落到计时状态上。
     *
     * 只有 `前台 && 亮屏 && 未归零 && 还有时长` 才走。每次状态变化都把当前片段折进
     * [accumulatedMs]，恢复时重新记 [segmentStart] —— 所以息屏/后台的时间一律不计，回来也不跳秒。
     */
    private fun syncClock() {
        val shouldRun = foreground && screenOn && !endHandled && totalMs > 0L
        if (shouldRun && segmentStart <= 0L) {
            segmentStart = SystemClock.elapsedRealtime()
            pausedHintView?.visibility = View.GONE
            startHeartbeat()
        } else if (!shouldRun && segmentStart > 0L) {
            accumulatedMs += (SystemClock.elapsedRealtime() - segmentStart).coerceAtLeast(0L)
            segmentStart = 0L
            stopHeartbeat()
            refreshRemaining()
            // 处于前台但被息屏打断时给一句「已暂停」，避免用户以为卡死
            if (foreground && !endHandled) pausedHintView?.visibility = View.VISIBLE
        }
    }

    /** 已走过的总毫秒 = 已沉淀 + 正在跑的这一段（没在跑就只算沉淀部分） */
    private fun currentElapsedMs(): Long =
        if (segmentStart > 0L) accumulatedMs + (SystemClock.elapsedRealtime() - segmentStart) else accumulatedMs

    /**
     * 刷新剩余时间。
     *
     * 毫秒 → 秒用**向上取整**：还剩 0.2 秒时显示「1」，永远不会在「其实还有时间」的时候先掉到 0。
     */
    private fun refreshRemaining() {
        if (endHandled) return
        val remainingMs = totalMs - currentElapsedMs()
        if (remainingMs <= 0L) {
            updateSeconds(0)
            onCountdownEnd()
            return
        }
        updateSeconds(((remainingMs + MS_PER_SECOND - 1) / MS_PER_SECOND).toInt())
    }

    /**
     * 更新大数字。
     *
     * 只在秒数真的变了才 setText：200ms 一次的心跳不该每次都触发 remeasure/layout。
     * 用 [TimeFormat.countdown] 的 `mm:ss` 两位补零 —— 位数固定，居中数字不会因为
     * 「10 → 9」而左右跳一格。
     */
    private fun updateSeconds(seconds: Int) {
        if (seconds == lastShownSeconds) return
        lastShownSeconds = seconds
        secondsView?.text = TimeFormat.countdown(seconds)
    }

    /**
     * 归零：`setResult(RESULT_OK)` 后 `finish()`，由启动页重问 Gate 放行。
     *
     * 幂等（[endHandled] 守住）：心跳与 onPause 都可能走到这里。
     */
    private fun onCountdownEnd() {
        if (endHandled) return
        endHandled = true
        accumulatedMs = totalMs
        segmentStart = 0L
        stopHeartbeat()
        updateSeconds(0)
        setResult(RESULT_OK)
        if (!isDestroyed) finish()
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = CoroutineScopes.launchUi {
            // 心跳 200ms：够让「秒」准时翻动，又不至于白烧电
            while (!endHandled && segmentStart > 0L) {
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

    // ==========================================================================================
    // 层切换
    // ==========================================================================================

    /**
     * 单击整屏：在大数字层与详情层之间切换（用户原话「单击…再次单击切回去」）。
     *
     * **倒计时不受影响**：它只看 [segmentStart] / [accumulatedMs]，与 `showingDetail` 无关。
     * 所以「看详情时倒计时继续」是自然成立的，不需要额外逻辑。
     */
    private fun onScreenClick() {
        showingDetail = !showingDetail
        bigLayer?.visibility = if (showingDetail) View.GONE else View.VISIBLE
        detailLayer?.visibility = if (showingDetail) View.VISIBLE else View.GONE
        if (showingDetail) {
            // 切到详情时重算一次数字（等待期间用量不会变，但借入量可能刚变过）
            refreshDetail()
        }
    }

    // ==========================================================================================
    // 息屏广播
    // ==========================================================================================

    /**
     * 注册 `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`。
     *
     * 大多数机型息屏会走 onPause，但**不保证**（部分 ROM 息屏只关屏不 pause）。
     * 需求明确要求「息屏不继续倒计时」，只靠 onPause 就是赌机型；
     * 广播是确定性手段，两条路径都覆盖（syncClock 本身幂等，双触发无副作用）。
     *
     * API 33+ 显式传 `RECEIVER_NOT_EXPORTED`（这些是系统广播，非导出即可收到）。
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
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
        } catch (ignored: IllegalArgumentException) {
            // 极少数 ROM 限制系统广播注册：失败就退化成「只靠 onPause 暂停」，不影响主流程
            receiverRegistered = false
        }
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (ignored: IllegalArgumentException) {
            // onCreate 注册、onDestroy 注销；重复注销只可能是系统状态错乱，吞掉即可
        }
        receiverRegistered = false
    }

    private companion object {
        /** 毫秒 → 秒 */
        const val MS_PER_SECOND = 1000L

        /** 心跳间隔：200ms 足够让「秒」准时翻动 */
        const val TICK_INTERVAL_MS = 200L

        /** `mm:ss` 固定 5 个字符（[TimeFormat.countdown] 的口径） */
        const val DIGIT_CHARS = 5f

        /** 等宽字体单字符宽度约为字号的 0.62 倍 */
        const val MONO_ADVANCE = 0.62f

        /** 普通屏最多 96sp（再大在手表上会顶到边） */
        const val NORMAL_MAX_SP = 96f

        /** 圆屏最多 56sp（圆里放不下更大的） */
        const val ROUND_MAX_SP = 56f

        /** 普通屏用屏宽的 0.86：数字左右各留一点呼吸空间 */
        const val NORMAL_WIDTH_FRACTION = 0.86f

        /** 圆屏用屏宽的 0.62：圆的内接区域比矩形窄，撑满一定被裁 */
        const val ROUND_WIDTH_FRACTION = 0.62f

        /** 圆屏适配开关的偏好键（与 BaseActivity.setRound / 播放器共用） */
        const val PREF_ROUND_UI = "player_ui_round"
    }
}
