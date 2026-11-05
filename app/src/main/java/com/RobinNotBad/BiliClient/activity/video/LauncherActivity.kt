package com.RobinNotBad.BiliClient.activity.video

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.CatchActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity
import com.RobinNotBad.BiliClient.activity.time.CountdownActivity
import com.RobinNotBad.BiliClient.activity.time.TimeBankBlockActivity
import com.RobinNotBad.BiliClient.activity.time.TimeBankDayTypePrompt
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.CrashStore
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TimeBankManager
import com.RobinNotBad.BiliClient.util.TimeMgmtPrefs
import com.RobinNotBad.BiliClient.util.UsageLimitManager

/**
 * 启动页（#9）。
 *
 * 打开软件先进入这个名为「哔哩终端」的空 Activity，由它再跳到
 * 推荐 / 初次设置；渠道包构建脚本只改它的 label（QQ音乐），
 * 桌面上显示的应用名与这里保持一致。
 *
 * 逻辑尽量薄：未完成初次设置 → 设置页；其余情况（**无论有没有账号**）→ 推荐页。
 *
 * v5.1.4 账号解耦起，这里不再把用户扔进登录页：B 站匿名本身就能看推荐/搜索/视频，
 * 「点进 APP 就提示登录」是用户抱怨的核心之一；需要登录的操作由各页面自己轻提示。
 * 账号态只用 [AccountManager.isLoggedIn]（web 与三端任一登录都算），不再直接读 web 的 mid 键。
 *
 * v5.1.6 实验室「每日使用时长限制」：开屏动画期间就显示「今日已使用 X」，
 * 且开关打开时超时（≥30 分钟延时 5 秒 / ≥60 分钟延时 10 秒）会在本页倒计时并推迟进入主界面。
 * 已用时长**无论开关是否开启都会显示**（用户要求「每次打开应用都显示已使用时长」），
 * 开关只决定要不要处罚延时。
 *
 * v5.2.0 时间管理套件：本页在显示今日用量之后、进入主界面之前，按**优先级**串了四件事
 * （顺序即下面的 1~4，顺序本身是设计判断，不是随手排的）：
 * 1. 今日用量（上面那条，永远显示）；
 * 2. 时间银行「今天是上学日还是休息日」的每日首次询问 —— 必须排在配额判定**之前**，
 *    否则用户选了「休息日」却已经按上学日配额被拦下，判定就白问了；也因此放在**启动页**问，
 *    而不是进推荐页之后问（配额用完根本进不去主界面，问在推荐页等于永远不做判定）；
 * 3. 配额拦截：时间银行开启且今天配额已用完 → 只进 [TimeBankBlockActivity]，不再往下走；
 * 4. 延时处罚：时间银行开时旧版「30 分钟延时 5 秒 / 60 分钟延时 10 秒」被硬配额取代；
 *    未开时才按旧逻辑延时，且开启了 `countdown_page_enable` 时改用独立的
 *    [CountdownActivity]（页内小字倒计时同时**不再启动**，避免两个倒计时同时跑）。
 */
class LauncherActivity : BaseActivity() {

    private val countdownHandler = Handler(Looper.getMainLooper())

    /** 倒计时心跳任务；onDestroy 时移除，防止页面已销毁还在改 UI */
    private var countdownTask: Runnable? = null

    /** 跳转任务；onDestroy 时连同 logo 上的排队任务一起移除 */
    private var routeTask: Runnable? = null

    /** 时间银行每日询问的兜底探针任务；onDestroy 时移除 */
    private var dayTypeProbeTask: Runnable? = null

    /** 「对话已关闭、等一小会再继续」的延迟任务；onDestroy 时移除 */
    private var focusResumeTask: Runnable? = null

    private var logoView: View? = null
    private var countdownView: TextView? = null

    private var remainingSeconds = 0

    /** 是否正在等用户回答「上学日 / 休息日」（等的时候本页不许跳转） */
    private var awaitingDayType = false

    /** 询问发起时刻（elapsedRealtime）：用来区分「对话没弹出时的首次焦点」与「对话关闭后的焦点回归」 */
    private var dayTypeAskedElapsed = 0L

    /** 后续流程只跑一次：询问回调 / 焦点回归 / 兜底探针 / 延时跳转都可能把它触发多遍 */
    private var routed = false

    /** 优先级链（配额拦截 / 延时处罚）是否已经决定过：决定完再被兜底路径调用就忽略 */
    private var decided = false

    /**
     * 独立倒计时页的结果回调。
     *
     * [CountdownActivity] 归零后 setResult(RESULT_OK) 并 finish（默认 finish_on_end=true），
     * 这里收到 OK 继续 [route]。**非 OK 也要放行**：用户中途按返回 / 页面被系统回收都属于
     * 「主动放弃剩下的等待」，此时如果还压着不跳转，启动页会一直挂着（比少罚几秒严重得多）。
     */
    private val countdownPageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                Log.i(TAG, "countdown page skipped by user, route anyway")
            }
            route()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        setPageName(getString(R.string.app_name))

        val hint = findViewById<TextView>(R.id.launcher_hint)
        val logo = findViewById<View>(R.id.launcher_logo)
        logoView = logo
        countdownView = findViewById<TextView>(R.id.launcher_countdown)
        startFadeIn(logo, hint)

        // 补展示上次崩溃：ErrorCatch 会在 startActivity(CatchActivity) 之后立刻 killProcess，
        // 页面经常来不及绘制，用户只看到「闪退一下」。这里在下一次启动时再展示一次，
        // 用户随手截图就能把完整堆栈反馈出来。（展示过就标记，不会每次都弹）
        showLastCrashIfAny()

        // 今日已用时长：开屏动画期间就显示（与开关是否开启无关）
        showTodayUsage(UsageLimitManager.todayUsedMs())

        // 被系统回收后重建：这一条链已经走过一遍（可能正有一个倒计时页的结果在途，
        // ActivityResultRegistry 会补交付）。不再重复询问 / 再开一个倒计时页，
        // 只留一个兜底跳转，保证「结果永远不来」时也不会卡死在启动页。
        if (savedInstanceState != null) {
            decided = true
            scheduleRoute(RECREATE_FALLBACK_MS)
            return
        }

        // 从这里开始按优先级往下走（见类注释 2~4）
        askDayTypeThenRoute()
    }

    // ---------------------------------------------------------------- 优先级链

    /**
     * 第 2 步：时间银行每日首次询问。
     *
     * 只在「时间银行开着」且「今天还没判定」且「今天还没被关闭」时问；其余情况直接进下一步。
     * 询问是模态对话，用户答完由 `onDone` 回调继续；万一对话被取消（或压根没弹出来），
     * 靠 [onWindowFocusChanged] 与 [scheduleDayTypeProbe] 两条兜底继续，绝不把用户卡在启动页。
     */
    private fun askDayTypeThenRoute() {
        val bankEnabled = SharedPreferencesUtil.getBoolean(TimeMgmtPrefs.BANK_ENABLE, false)
        if (bankEnabled && !TimeBankManager.hasDayTypeToday() && !TimeBankManager.isDisabledToday()) {
            awaitingDayType = true
            dayTypeAskedElapsed = SystemClock.elapsedRealtime()
            TimeBankDayTypePrompt.maybePrompt(this, Runnable { afterDayType() })
            scheduleDayTypeProbe()
            return
        }
        afterDayType()
    }

    /**
     * 兜底探针（只兜「对话没弹出来」这一种）：对话是抢焦点的模态窗口，
     * 它显示时本页 [hasWindowFocus] 为 false —— 那就什么都不做，等用户的回调或焦点回归。
     * 反过来如果 700ms 后本页仍持有窗口焦点，说明对话根本没弹（例如时间银行对话内部
     * 判定不需要弹），直接继续往下走。
     */
    private fun scheduleDayTypeProbe() {
        // maybePrompt 在「判定为不需要弹窗」时可能同步回调 onDone（流程已经往前走），
        // 那就不要再挂兜底探针了。
        if (decided || routed) return
        val logo = logoView ?: return
        val task = Runnable {
            dayTypeProbeTask = null
            if (!awaitingDayType || routed) return@Runnable
            if (hasWindowFocus()) afterDayType()
        }
        dayTypeProbeTask = task
        logo.postDelayed(task, DAY_TYPE_PROBE_MS)
    }

    /**
     * 对话关闭后本页会重新拿到窗口焦点：[awaitingDayType] 期间拿回焦点说明询问已经结束
     * （用户答完走 onDone，或者取消 → 不回调 onDone）。后者必须在这里兜住，否则页面会一直停在启动页。
     *
     * [DAY_TYPE_FOCUS_GRACE_MS] 用来排除「对话还没显示时本页的首次焦点」：
     * 首次焦点紧跟在 [askDayTypeThenRoute] 之后，间隔只有几十毫秒；用户关闭对话远慢于此。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !awaitingDayType || routed) return
        if (SystemClock.elapsedRealtime() - dayTypeAskedElapsed < DAY_TYPE_FOCUS_GRACE_MS) return
        // 不立刻继续：对话关闭的瞬间，本页拿回焦点可能**早于** onDone 写入今日类型，
        // 此时立刻判配额会拿旧的（未判定 → 按上学日）配额去算，用户明明选了休息日却被按
        // 上学日拦下。延迟一小段让 onDone 先跑；onDone 先到就把这个任务取消，不会重复执行。
        val logo = logoView ?: run {
            afterDayType()
            return
        }
        val task = Runnable { afterDayType() }
        focusResumeTask = task
        logo.postDelayed(task, FOCUS_RESUME_DELAY_MS)
    }

    /**
     * 第 3、4 步：配额拦截 → 延时处罚（独立倒计时页 / 旧版页内倒计时）→ 进主界面。
     *
     * 幂等：本函数可能被询问回调、焦点回归、兜底探针、倒计时结果多路调用，只允许真正生效一次。
     */
    private fun afterDayType() {
        if (routed || decided) return
        decided = true
        awaitingDayType = false
        cancelDayTypeProbe()
        cancelFocusResume()

        val bankEnabled = SharedPreferencesUtil.getBoolean(TimeMgmtPrefs.BANK_ENABLE, false)
        // 3a. 配额已用完：只进拦截页，且拦截页会自己 startActivity 到推荐页，
        //     所以这里 startActivity + finish 就够，不再往下走（也不做旧版延时）。
        if (bankEnabled && !TimeBankManager.isDisabledToday() && TimeBankManager.isExhaustedToday()) {
            routed = true
            startActivity(Intent(this, TimeBankBlockActivity::class.java))
            finish()
            return
        }

        val delaySeconds = legacyDelaySeconds(bankEnabled)
        if (delaySeconds > 0 && isCountdownPageEnabled()) {
            startCountdownPage(delaySeconds)
            return
        }
        // 3c. 旧行为：页内小字倒计时 + 延时跳转（独立页路径下这里不会启动倒计时）
        startCountdown(delaySeconds)
        scheduleRoute(ROUTE_BASE_DELAY_MS + delaySeconds.toLong() * COUNTDOWN_TICK_MS)
    }

    /**
     * 旧版延时秒数：时间银行开时由硬配额取代（返回 0，即不再处罚延时），
     * 未开时才按「≥30 分钟 → 5 秒 / ≥60 分钟 → 10 秒」算。
     */
    private fun legacyDelaySeconds(bankEnabled: Boolean): Int {
        if (bankEnabled || !UsageLimitManager.isEnabled()) return 0
        return UsageLimitManager.delaySecondsForUsed(UsageLimitManager.todayUsedMs())
    }

    private fun isCountdownPageEnabled(): Boolean =
        SharedPreferencesUtil.getBoolean(TimeMgmtPrefs.COUNTDOWN_PAGE, true)

    /** 独立倒计时页：结果回来（RESULT_OK）才 [route]；失败则退化为旧版页内倒计时，不留白屏 */
    private fun startCountdownPage(delaySeconds: Int) {
        val intent = Intent(this, CountdownActivity::class.java)
        intent.putExtra(CountdownActivity.EXTRA_SECONDS, delaySeconds)
        // 只要「归零后回调结果」；finish_on_end 保持默认 true —— CountdownActivity 的实现是
        // 「先 setResult(RESULT_OK)，再按 finish_on_end 决定 finish」，若把 finish 关掉，
        // 结果要等页面被关掉才交付，启动页就会一直挂着等。所以这里**不写** EXTRA_FINISH_ON_END。
        intent.putExtra(CountdownActivity.EXTRA_RESULT_ON_END, true)
        try {
            countdownPageLauncher.launch(intent)
        } catch (e: Exception) {
            // 例如页面未注册 / 系统限制后台启动 Activity：退化为旧版页内倒计时，用户照常能进主界面
            Log.w(TAG, "start countdown page failed, fallback to inline countdown", e)
            startCountdown(delaySeconds)
            scheduleRoute(ROUTE_BASE_DELAY_MS + delaySeconds.toLong() * COUNTDOWN_TICK_MS)
        }
    }

    /** 轻微的浮现动画，避免“卡了一下什么都没有”的观感 */
    private fun startFadeIn(logo: View, hint: TextView) {
        logo.alpha = 0f
        hint.alpha = 0f
        val logoAnim = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f).setDuration(FADE_DURATION_MS)
        val hintAnim = ObjectAnimator.ofFloat(hint, "alpha", 0f, 1f).setDuration(FADE_DURATION_MS)
        val animator = AnimatorSet()
        animator.playTogether(logoAnim, hintAnim)
        animator.start()
    }

    /** 显示「今日已使用 X」；用量为 0 也照常显示（用户要求每次打开都能看到） */
    private fun showTodayUsage(usedMs: Long) {
        val usageView = findViewById<TextView>(R.id.launcher_usage)
        usageView.text = getString(R.string.usage_limit_today_used, UsageLimitManager.formatUsed(usedMs))
    }

    /** 开屏倒计时：与跳转同一个 260ms 起点，每秒 tick 一次，从 delaySeconds 递减到 0 */
    private fun startCountdown(delaySeconds: Int) {
        if (delaySeconds <= 0) return
        remainingSeconds = delaySeconds
        postCountdownTick(ROUTE_BASE_DELAY_MS)
    }

    private fun postCountdownTick(delayMs: Long) {
        val task = Runnable { onCountdownTick() }
        countdownTask = task
        countdownHandler.postDelayed(task, delayMs)
    }

    private fun onCountdownTick() {
        if (isDestroyed || isFinishing) return
        val view = countdownView ?: return
        if (remainingSeconds <= 0) {
            view.visibility = View.GONE
            countdownTask = null
            return
        }
        view.visibility = View.VISIBLE
        view.text = getString(R.string.usage_limit_countdown, remainingSeconds)
        remainingSeconds -= 1
        postCountdownTick(COUNTDOWN_TICK_MS)
    }

    private fun showLastCrashIfAny() {
        val stack = CrashStore.takeUnacknowledged(this) ?: return
        try {
            val intent = Intent(this, CatchActivity::class.java)
            intent.putExtra("stack", stack)
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "show last crash failed", e)
        }
    }

    /** 用 postDelayed 保证动画至少可见一帧，再按延时秒数推迟 [route] */
    private fun scheduleRoute(delayMs: Long) {
        val logo = logoView ?: run {
            route()
            return
        }
        val task = Runnable { route() }
        routeTask = task
        logo.postDelayed(task, delayMs)
    }

    private fun route() {
        if (routed) return
        if (isDestroyed || isFinishing) return
        routed = true

        // 没完成初次设置，先去设置页（首启引导，跟账号态无关，保持原有流程）
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {
            startActivity(Intent(this, SetupUIActivity::class.java))
            finish()
            return
        }

        // 账号态**不再决定能不能进主界面**：B 站匿名本身就能看推荐/搜索/视频，
        // 「点进 APP 就提示登录」是用户抱怨的核心之一；需要登录的操作由各页面自己轻提示。
        // 首次使用引导登录已经放在上面的 setup=false 分支里（设置页 → 介绍页 → 登录页，
        // 登录页还有「但是我拒绝」可直接跳过），跟这里的账号态无关。
        // 账号态只记一行日志，方便排查「明明登录了却没显示」这类问题。
        Log.i(TAG, "route: isLoggedIn=" + AccountManager.isLoggedIn()
                + ", source=" + AccountManager.accountSource() + "，进入推荐页")
        startActivity(Intent(this, RecommendActivity::class.java))
        finish()
    }

    private fun cancelDayTypeProbe() {
        dayTypeProbeTask?.let { task -> logoView?.removeCallbacks(task) }
        dayTypeProbeTask = null
    }

    private fun cancelFocusResume() {
        focusResumeTask?.let { task -> logoView?.removeCallbacks(task) }
        focusResumeTask = null
    }

    override fun onDestroy() {
        // 清理挂着的倒计时 / 跳转 / 兜底任务：页面销毁后不再回调整 UI，也不长期持有 Activity。
        // 用户中途按返回退出时走的就是这条路径（不拦截返回，只保证不崩）。
        countdownTask?.let { countdownHandler.removeCallbacks(it) }
        countdownTask = null
        routeTask?.let { task -> logoView?.removeCallbacks(task) }
        routeTask = null
        cancelDayTypeProbe()
        cancelFocusResume()
        awaitingDayType = false
        countdownView = null
        logoView = null
        super.onDestroy()
    }

    private companion object {
        /** 启动页日志标签；showLastCrashIfAny 与 route 共用，方便 logcat 过滤 */
        const val TAG = "launcher"

        /** 开屏动画时长（毫秒） */
        const val FADE_DURATION_MS = 220L

        /** 跳转前的最小展示时长（毫秒）：保证动画至少可见一帧 */
        const val ROUTE_BASE_DELAY_MS = 260L

        /** 倒计时心跳间隔（毫秒）：1 秒 */
        const val COUNTDOWN_TICK_MS = 1000L

        /** 每日询问的兜底探针延迟（毫秒）：对话抢焦点是立刻生效的，700ms 足够区分「没弹出」 */
        const val DAY_TYPE_PROBE_MS = 700L

        /** 焦点宽限（毫秒）：小于它说明是「对话还没显示时的首次焦点」，不是对话关闭 */
        const val DAY_TYPE_FOCUS_GRACE_MS = 400L

        /** 进程被回收后重建时的兜底跳转延迟（毫秒）：等不到在途的倒计时结果就不再等 */
        const val RECREATE_FALLBACK_MS = 3000L

        /** 对话关闭后继续流程的延迟（毫秒）：让 onDone 先把今日类型写完，再判配额 */
        const val FOCUS_RESUME_DELAY_MS = 200L
    }
}
