package com.RobinNotBad.BiliClient.activity.video

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.CatchActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.CrashStore
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
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
 */
class LauncherActivity : BaseActivity() {

    private val countdownHandler = Handler(Looper.getMainLooper())

    /** 倒计时心跳任务；onDestroy 时移除，防止页面已销毁还在改 UI */
    private var countdownTask: Runnable? = null

    /** 跳转任务；onDestroy 时连同 logo 上的排队任务一起移除 */
    private var routeTask: Runnable? = null

    private var logoView: View? = null
    private var countdownView: TextView? = null

    private var remainingSeconds = 0

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
        val usedMs = UsageLimitManager.todayUsedMs()
        showTodayUsage(usedMs)

        // 只有开关打开时才处罚延时；未打开时延时为 0，行为与旧版完全一致
        val delaySeconds = if (UsageLimitManager.isEnabled()) {
            UsageLimitManager.delaySecondsForUsed(usedMs)
        } else {
            0
        }
        startCountdown(delaySeconds)
        // 用 postDelayed 保证动画至少可见一帧，超时则再额外等 delaySeconds 秒
        val task = Runnable { route() }
        routeTask = task
        logo.postDelayed(task, ROUTE_BASE_DELAY_MS + delaySeconds * COUNTDOWN_TICK_MS)
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

    private fun route() {
        if (isDestroyed || isFinishing) return

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

    override fun onDestroy() {
        // 清理挂着的倒计时与跳转任务：页面销毁后不再回调整 UI，也不长期持有 Activity。
        // 用户中途按返回退出时走的就是这条路径（不拦截返回，只保证不崩）。
        countdownTask?.let { countdownHandler.removeCallbacks(it) }
        countdownTask = null
        routeTask?.let { task -> logoView?.removeCallbacks(task) }
        routeTask = null
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
    }
}
