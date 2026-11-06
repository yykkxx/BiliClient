package com.RobinNotBad.BiliClient.activity.video

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.CatchActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.Gate
import com.RobinNotBad.BiliClient.time.TimeFormat
import com.RobinNotBad.BiliClient.time.activity.CountdownGateActivity
import com.RobinNotBad.BiliClient.time.activity.DayTypePromptActivity
import com.RobinNotBad.BiliClient.time.activity.QuotaBlockActivity
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.CrashStore
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 启动页（#9）。
 *
 * 打开软件先进入这个名为「哔哩终端」的空 Activity，由它再跳到
 * 推荐 / 初次设置；渠道包构建脚本只改它的 label（QQ音乐），
 * 桌面上显示的应用名与这里保持一致。
 *
 * 逻辑尽量薄：未完成初次设置 → 设置页；其余情况（**无论有没有账号**）→ 推荐页。
 * v5.1.4 账号解耦起，这里不再把用户扔进登录页：B 站匿名本身就能看推荐/搜索/视频，
 * 「点进 APP 就提示登录」是用户抱怨的核心之一；需要登录的操作由各页面自己轻提示。
 *
 * # v5.2.1 时间管理重写后的门禁流程（这是本页唯一的复杂逻辑）
 *
 * 一进来先显示「今天已用多少」，然后**只问 [Gate] 一个对象**该怎么办（顺序全在 Gate 里，
 * 本页不重复判断，避免「两个地方各判一次」这种历史上吃过亏的写法）：
 *
 * | Gate 结论 | 本页动作 |
 * | --- | --- |
 * | `PASS` | 直接 [route] 进主界面 |
 * | `ASK_DAY_TYPE` | 拉起 [DayTypePromptActivity]（不可取消），回来后**重新问一次 Gate** |
 * | `WAIT` | 拉起 [CountdownGateActivity]（大号倒计时），回来后**本次会话不再等第二次** |
 * | `BLOCK` | 拉起 [QuotaBlockActivity]（含长按 45 秒解除），回来后**重新问一次 Gate** |
 *
 * ## 为什么回来后要「重新问 Gate」而不是用子页面返回的结果
 * 三个门禁页都会**改写偏好**（选日子类型 / 长按解除 / 借到时间），而 Gate 是
 * 「每次现算、无状态」的（见 Gate 的类注释）。所以**重问一次**天然就是最新结论，
 * 不需要页面之间约定 resultCode 契约 —— 少一套契约就少一处会写错的地方。
 * 用户借到时间后配额变大，回到这里重问 → Gate 返回 PASS → 直接放行，
 * 这正是用户期待的「借完就能用」。
 *
 * ## 为什么 WAIT 之后不再重问（唯一的例外）
 * 倒计时等完之后，「今日已用」**没有变**（等待时间不计入使用时长），
 * 重问 Gate 必然又返回 WAIT → 无限循环。而用户的需求是「每次打开应用等一次」，
 * 不是「等完再等一次」。所以用 [waitedThisSession] 记住「本次会话已经付过等待」，
 * 等完直接往下走。
 *
 * ## 为什么 ASK/BLOCK 要防止死循环
 * 子页面可能因为用户按返回而直接 finish（BLOCK 页允许返回退出 App），
 * 也可能因为状态没变而回到同样的结论。用 [gateGuardDepth] 计数：
 * 最多连续拉起 4 次门禁页，超过就打日志放行 —— **宁可少拦一次，也不能让用户永远进不去**。
 * （这条是 v5.2.0 的血泪教训：当时「页面被系统回收后重建」就会卡在启动页。）
 */
class LauncherActivity : BaseActivity() {

    /** 是否已经在本次会话里等过倒计时（等过就不再等第二次，理由见类注释） */
    private var waitedThisSession = false

    /** 本次会话已经连续拉起过几次门禁页（防死循环） */
    private var gateGuardDepth = 0

    /** 后续流程只跑一次：多个回调路径都可能把它触发多遍 */
    private var routed = false

    /**
     * 门禁页的结果回调。
     *
     * 不读 resultCode：三个页面都会改写偏好，"重问 Gate" 本身就拿到了最新结论。
     * 这也顺带解决了「用户按返回直接退出子页面」——回来照样重问，不会卡住。
     */
    private val gateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                Log.i(TAG, "gate page returned without OK, re-checking gate anyway")
            }
            proceedAfterGate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        setPageName(getString(R.string.app_name))

        val hint = findViewById<TextView>(R.id.launcher_hint)
        val logo = findViewById<View>(R.id.launcher_logo)
        startFadeIn(logo, hint)

        // 补展示上次崩溃：ErrorCatch 会在 startActivity(CatchActivity) 之后立刻 killProcess，
        // 页面经常来不及绘制，用户只看到「闪退一下」。这里在下一次启动时再展示一次，
        // 用户随手截图就能把完整堆栈反馈出来。（展示过就标记，不会每次都弹）
        showLastCrashIfAny()

        // 今日已用：无论功能是否开启都显示（用户要求「进入应用的时候显示当前已用时间」）
        showTodayUsage()

        // 被系统回收后重建：门禁链已经走过一遍（可能正有一个门禁页在途，ActivityResultRegistry
        // 会补交付）。不再重新判定，只留一个兜底跳转，保证「结果永远不来」时也不会卡死。
        if (savedInstanceState != null) {
            scheduleRoute(RECREATE_FALLBACK_MS)
            return
        }

        proceedAfterGate()
    }

    // ---------------------------------------------------------------- 门禁链

    /**
     * 问一次 [Gate]，按结论拉起对应页面或直接进主界面。
     *
     * 幂等：已 [routed] 就直接返回（多路回调重复调用不会重复跳转）。
     */
    private fun proceedAfterGate() {
        if (routed || isDestroyed || isFinishing) return

        val decision = try {
            Gate.decide()
        } catch (e: Exception) {
            // 门禁本身出异常时**必须放行**：这是「少拦一次」与「用户完全进不去」之间的取舍，
            // 后者严重得多（历史上出现过偏好损坏导致永远进不去）。
            Log.w(TAG, "gate decide failed, pass through", e)
            Gate.Decision.PASS
        }
        Log.i(TAG, "gate decision=$decision, usedMs=${Gate.usedMsToday()}, waited=$waitedThisSession")

        when (decision) {
            Gate.Decision.PASS -> route()

            Gate.Decision.WAIT -> {
                // 本次会话已经等过 → 不再等第二次（否则等完重问又 WAIT，无限循环）
                if (waitedThisSession || Gate.waitSeconds() <= 0) {
                    route()
                    return
                }
                waitedThisSession = true
                launchGatePage(CountdownGateActivity::class.java)
            }

            Gate.Decision.ASK_DAY_TYPE -> launchGatePage(DayTypePromptActivity::class.java)

            Gate.Decision.BLOCK -> launchGatePage(QuotaBlockActivity::class.java)
        }
    }

    /**
     * 拉起一个门禁页；连续拉起次数超上限就放弃拦截直接放行（防死循环，理由见类注释）。
     */
    private fun launchGatePage(target: Class<*>) {
        gateGuardDepth += 1
        if (gateGuardDepth > MAX_GATE_HOPS) {
            Log.w(TAG, "gate hops exceeded $MAX_GATE_HOPS, pass through to avoid trap")
            route()
            return
        }
        try {
            gateLauncher.launch(Intent(this, target))
        } catch (e: Exception) {
            // 例如页面未注册 / 系统限制后台启动 Activity：放行比卡在启动页好得多
            Log.w(TAG, "launch gate page ${target.simpleName} failed, pass through", e)
            route()
        }
    }

    /** 轻微的浮现动画，避免"卡了一下什么都没有"的观感 */
    private fun startFadeIn(logo: View, hint: TextView) {
        logo.alpha = 0f
        hint.alpha = 0f
        val logoAnim = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f).setDuration(FADE_DURATION_MS)
        val hintAnim = ObjectAnimator.ofFloat(hint, "alpha", 0f, 1f).setDuration(FADE_DURATION_MS)
        val animator = AnimatorSet()
        animator.playTogether(logoAnim, hintAnim)
        animator.start()
    }

    /**
     * 显示「今日已用 X」+ 三档颜色 + 剩余配额。
     *
     * 颜色由 [DailyPlan.levelOf] 决定（绿 <30min / 黄 30~60min / 红 ≥60min），
     * 色值取 [TimeFormat.levelColor]，**不跟随主题**（三档是语义色，跟随主题就失去警示意义）。
     *
     * 剩余配额只在功能开启且今天是上学日时才有意义：休息日配额是
     * `Int.MAX_VALUE`，直接算剩余会得到一个天文数字，所以先判断
     * `quotaMinutesToday() == QUOTA_UNLIMITED`（这个坑 v5.2.0 踩过）。
     */
    private fun showTodayUsage() {
        val usedMs = Gate.usedMsToday()
        val level = DailyPlan.levelOf(usedMs)

        val titleView = findViewById<TextView>(R.id.launcher_usage_title)
        titleView.text = getString(R.string.launcher_usage_title)

        val usageView = findViewById<TextView>(R.id.launcher_usage)
        usageView.text = TimeFormat.duration(usedMs)
        usageView.setTextColor(TimeFormat.levelColor(this, level))

        val remainingView = findViewById<TextView>(R.id.launcher_remaining)
        // 功能没开就完全不显示剩余（否则会显示一个「不限时长」，让没开功能的用户莫名其妙）
        if (!DailyPlan.isEnabled() || !DailyPlan.hasDayTypeToday()) {
            remainingView.visibility = View.GONE
            return
        }
        remainingView.visibility = View.VISIBLE
        remainingView.text = TimeFormat.remainingText(
            DailyPlan.quotaMinutesToday(),
            DailyPlan.remainingMsToday()
        )
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

    /** 用 postDelayed 保证动画至少可见一帧，再 [route] */
    private fun scheduleRoute(delayMs: Long) {
        val logo = findViewById<View>(R.id.launcher_logo) ?: run {
            route()
            return
        }
        logo.postDelayed({ route() }, delayMs)
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
        // 账号态只记一行日志，方便排查「明明登录了却没显示」这类问题。
        Log.i(
            TAG,
            "route: isLoggedIn=" + AccountManager.isLoggedIn()
                    + ", source=" + AccountManager.accountSource() + "，进入推荐页"
        )
        startActivity(Intent(this, RecommendActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        findViewById<View>(R.id.launcher_logo)?.let { logo ->
            logo.removeCallbacks(null)
        }
        super.onDestroy()
    }

    private companion object {
        /** 启动页日志标签；showLastCrashIfAny 与 route 共用，方便 logcat 过滤 */
        const val TAG = "launcher"

        /** 开屏动画时长（毫秒） */
        const val FADE_DURATION_MS = 220L

        /** 进程被回收后重建时的兜底跳转延迟（毫秒）：等不到在途的门禁结果就不再等 */
        const val RECREATE_FALLBACK_MS = 3000L

        /**
         * 连续拉起门禁页的次数上限。
         *
         * 正常流程最多 2 次（问日子 → 等/拦），留到 4 是给「拦截页 → 借时间 → 重问」
         * 这类正当的二次往返留余量。超过就放行：**宁可少拦一次，也不能让用户永远进不去**。
         */
        const val MAX_GATE_HOPS = 4
    }
}
