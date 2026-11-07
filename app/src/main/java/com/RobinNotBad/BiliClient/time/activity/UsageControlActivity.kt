package com.RobinNotBad.BiliClient.time.activity

import android.content.Intent
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import android.os.Bundle
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.TimeFormat
import com.RobinNotBad.BiliClient.time.TimePrefs
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 小栏 1：使用控制（总开关 / 上学日配额 / 休息日说明 / 三色图例 / 各项等待秒数 / 长按解除说明）。
 *
 * # 两条「关闭要等、打开立即生效」的看守（本页全部难点都在这里）
 *
 * 1. **总开关关闭方向等 [TimePrefs.toggleOffWaitSec] 秒**（默认 120）。
 *    用户原话「这个开关关闭的时候需要进行倒计时 120s」。打开方向立即生效。
 * 2. **上学日配额调大等 [TimePrefs.quotaIncreaseWaitSec] 秒**（默认 120），**调小立即生效**。
 *    用户原话「更改变大的时候需要 120 秒倒计时」。
 *
 * ## ⚠️ 致命坑：`SwitchMaterial.setChecked()` 会**同步**触发 `OnCheckedChangeListener`
 * 关闭方向不能直接写 false —— 必须先 `setChecked(true)`（把开关顶回「开」）再倒计时。
 * 而那个 `setChecked(true)` 会**同步**回调一次监听器；监听器看到 `isChecked=true` 又会走
 * 「打开方向 → 立即写 true」，于是「顶回开」被当成用户新点击，倒计时被打断/无限重入，
 * 开关永远关不掉。
 * 所以程序性 `setChecked` **必须**用 [suppressChange] 包起来
 * （写法照抄 `adapter/SettingsAdapter.kt` 的 `setCheckedProgrammatically`，
 * 那是本仓库已验证过的实现，不重新发明）。
 *
 * ## 为什么倒计时可以点提示取消
 * 120 秒很长。用户误拨开关后必须能自救，否则只能干等两分钟（或者杀掉 App）。
 * 取消语义是安全的：「不关闭」不改变任何受限状态，只是回到原状。
 * 而「调大配额」的倒计时也一样可取消。
 *
 * ## v5.3.0：两条倒计时都搬到独立的倒计时页面了
 * 用户要求「包括那几个关闭按钮的所有倒计时都统一用那个独立的倒计时界面」。
 * 所以本页**不再自己跑倒计时**：`startCloseGuard` / `startQuotaGuard` 现在只是
 * 「拉起 [CountdownGateActivity] + 等结果」的薄壳（`registerForActivityResult`）。
 *
 * 三个直接收益（都不是「为统一而统一」）：
 * 1. **大数字看得清**：原地只是一行 11sp 小字，圆屏上几乎看不出还剩几秒；
 *    独立页有按位数自适应的 96sp 大数字；
 * 2. **息屏/切后台不继续倒计时**：独立页有 `ACTION_SCREEN_OFF` 广播 + `elapsedRealtime`
 *    累计，而原地协程在息屏时会被系统挂起、回前台一次性补发，等于白等；
 * 3. **等待期间不计入使用时长**：独立页会 `UsageTracker.pause()`，
 *    原地版本不会 —— 也就是说旧实现里「为了关掉限制而等待的 120 秒」反而被算进了使用时长，
 *    这本身就是个自相矛盾的 bug。
 *
 * 取消能力**保留**：倒计时页 `RESULT_CANCELED` → 本页不写任何偏好。
 * 原来原地提示可以点取消，换页后不能反而丢掉「反悔」能力。
 */
class UsageControlActivity : BaseActivity() {

    private var switchView: SwitchMaterial? = null
    private var switchDescView: TextView? = null
    private var guardTipView: TextView? = null
    private var quotaDescView: TextView? = null
    private var quotaValueView: TextView? = null
    private var quotaHintView: TextView? = null

    /**
     * 程序性 `setChecked` 期间的屏蔽标志。
     *
     * 必须是字段而不是局部变量：`setChecked` 是**同步**回调，局部变量在回调里读不到
     * （回调发生在 `setChecked` 返回之前，局部变量此时还没赋 true）。
     */
    private var suppressChange = false

    /**
     * 是否有一个「关闭总开关」的倒计时页正在前台。
     *
     * v5.3.0 起倒计时由独立的 [CountdownGateActivity] 承担（用户要求统一），
     * 本页不再持有协程 Job，只记「派人出去了」这个状态 —— 用于两件事：
     * 1. 挡住等待期间的重复点击（否则用户狂点开关会叠出好几个倒计时页）；
     * 2. 结果回来时知道该执行什么动作。
     *
     * 用布尔 + 单独的目标值而不是「Job != null」：协程 Job 那套随倒计时一起搬走了，
     * 但「有没有在等」这个语义仍然需要，只是载体从 Job 换成了 ActivityResult 在途标志。
     */
    private var closeGuardLauncherPending = false

    /** 关闭总开关的等待秒数（>0 表示正在等）；仅用于诊断与状态一致性 */
    private var closeGuardPending = 0

    /** 是否有一个「调大配额」的倒计时页正在前台 */
    private var quotaGuardLauncherPending = false

    /** 调大配额的目标值；0 = 没有在等 */
    private var quotaGuardTarget = 0

    /**
     * 「关闭总开关」倒计时页的结果回调。
     *
     * 只有 `RESULT_OK`（真的走完）才写 `setEnabled(false)`；其余情况一律复位，
     * 保证「取消 = 什么都没发生」。
     */
    private val closeGuardLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        closeGuardLauncherPending = false
        val waitedSec = closeGuardPending
        closeGuardPending = 0
        if (result.resultCode == RESULT_OK) {
            TimePrefs.setEnabled(false)
            setSwitchProgrammatically(false)
            renderSwitch()
            MsgUtil.showMsg(getString(R.string.tm2_guard_done, waitedSec))
        } else {
            // 取消：开关本来就是「开」，只需按真实偏好重画一次（幂等）
            renderSwitch()
        }
    }

    /** 「调大配额」倒计时页的结果回调（语义同 [closeGuardLauncher]） */
    private val quotaGuardLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        quotaGuardLauncherPending = false
        val target = quotaGuardTarget
        quotaGuardTarget = 0
        if (result.resultCode == RESULT_OK && target > 0) {
            applyQuota(target, R.string.tm2_control_quota_increase_done)
        } else {
            cancelQuotaGuard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage_control)
        setPageName(getString(R.string.tm2_page_control))

        switchView = findViewById(R.id.tm2_control_switch)
        switchDescView = findViewById(R.id.tm2_control_switch_desc)
        guardTipView = findViewById(R.id.tm2_control_guard_tip)
        quotaDescView = findViewById(R.id.tm2_control_quota_desc)
        quotaValueView = findViewById(R.id.tm2_quota_value)
        quotaHintView = findViewById(R.id.tm2_control_quota_hint)

        bindSwitch()
        bindQuotaButtons()
        bindCancelAreas()
        bindStaticTexts()
        renderSwitch()
        renderQuota()
        renderLevels()
    }

    override fun onResume() {
        super.onResume()
        // 从倒计时页返回时会先走本回调（结果回调紧随其后）：
        // 这里把「在途」标志清掉，避免结果回调与 onResume 的顺序差异导致标志残留。
        // 真正的动作仍在 ActivityResult 回调里执行，这里不写任何偏好。
        if (closeGuardLauncherPending || quotaGuardLauncherPending) {
            renderSwitch()
        }
    }

    override fun onDestroy() {
        // 本页已不持有协程（倒计时搬到独立页），这里只需清掉在途标志，
        // 避免页面重建后「以为还在等」而把开关锁死。
        closeGuardLauncherPending = false
        closeGuardPending = 0
        quotaGuardLauncherPending = false
        quotaGuardTarget = 0
        super.onDestroy()
    }

    // ==========================================================================================
    // 总开关
    // ==========================================================================================

    private fun bindSwitch() {
        val switch = switchView ?: return
        switch.setOnCheckedChangeListener { _, isChecked ->
            // 程序性 setChecked / 初始化 setChecked 都走这里：必须放行，否则会把「顶回开」当成用户点击
            if (suppressChange) return@setOnCheckedChangeListener
            onSwitchToggled(isChecked)
        }
    }

    /**
     * 用户真的拨了开关。
     *
     * 三种情况：
     * - 倒计时中又拨：忽略并把开关顶回「开」（不允许叠加，也不允许用再拨一次来跳过等待）；
     * - 关闭方向且等待秒数 > 0：顶回「开」，起倒计时；
     * - 其它（打开方向 / 等待为 0）：立即写偏好。
     */
    private fun onSwitchToggled(isChecked: Boolean) {
        // 倒计时页正在前台（用户去等待了）：这一页此时是 onPause，正常不会再收到
        // 开关回调；但部分 ROM 会在返回瞬间补发一次，此时把开关顶回「开」即可，
        // 真正的结果由 ActivityResult 回调统一处理。
        if (closeGuardLauncherPending) {
            setSwitchProgrammatically(true)
            return
        }
        val waitSec = TimePrefs.toggleOffWaitSec()
        if (!isChecked && waitSec > 0) {
            // v5.3.0：不再在原地跑协程倒计时，改为拉起**统一的独立倒计时页**
            // （用户要求「所有倒计时都统一用那个独立的倒计时界面」）。
            // 开关先顶回「开」：等待期间偏好仍是 true，界面与真实状态一致；
            // 只有倒计时页返回 RESULT_OK 才真正写 false。
            setSwitchProgrammatically(true)
            startCloseGuard(waitSec)
            return
        }
        // 打开方向立即生效（用户口径明确：只有关闭要等）
        TimePrefs.setEnabled(isChecked)
        renderSwitch()
    }

    /**
     * 程序性改开关状态：必须屏蔽监听器。
     *
     * 这是本页最关键的一行（理由见类注释的「致命坑」）。抽成函数是为了让每个
     * 调用点都看得见 `suppressChange` 的存在，避免有人以后直接写 `switch.setChecked(...)`。
     */
    private fun setSwitchProgrammatically(checked: Boolean) {
        val switch = switchView ?: return
        suppressChange = true
        switch.setChecked(checked)
        suppressChange = false
    }

    /**
     * 启动「关闭总开关」的等待。
     *
     * v5.3.0：**改由独立倒计时页承担**（用户要求「所有倒计时都统一用那个独立的倒计时界面」）。
     * 本函数只剩「拉起页面 + 处理结果」两件事，原来的协程倒计时循环与
     * `guardTipView` 逐秒刷文案已删除。
     *
     * 结果处理：
     * - `RESULT_OK` → 倒计时真的走完了 → 此刻才写 `setEnabled(false)`；
     * - 其它（用户按返回 / 页面被系统回收）→ **不写任何偏好**，开关复位成「开」。
     *
     * 取消路径是刻意保留的：原来原地提示可以点取消，换成独立页后如果丢掉这个能力，
     * 用户误拨开关就只能干等两分钟。
     */
    private fun startCloseGuard(totalSec: Int) {
        closeGuardPending = totalSec
        val intent = Intent(this, CountdownGateActivity::class.java)
        intent.putExtra(CountdownGateActivity.EXTRA_TOTAL_SEC, totalSec)
        intent.putExtra(
            CountdownGateActivity.EXTRA_TITLE,
            getString(R.string.tm2_guard_title, totalSec)
        )
        // 不显示「当天使用时间详情」：这条等待与"今天用了多久"无关，
        // 点开详情只会让用户以为关个开关也被计入使用时长。
        intent.putExtra(CountdownGateActivity.EXTRA_SHOW_DETAIL, false)
        closeGuardLauncherPending = true
        try {
            closeGuardLauncher.launch(intent)
        } catch (e: Exception) {
            // 页面未注册 / 系统限制后台启动：宁可立刻生效也不要卡住用户
            closeGuardLauncherPending = false
            closeGuardPending = 0
            Logu.w(TAG, "拉起倒计时页失败，直接关闭总开关：" + e.javaClass.simpleName)
            TimePrefs.setEnabled(false)
            renderSwitch()
        }
    }

    /** 取消「关闭总开关」的等待：开关保持开启、偏好不写 */
    private fun cancelCloseGuard() {
        closeGuardLauncherPending = false
        closeGuardPending = 0
        guardTipView?.visibility = View.GONE
        renderSwitch()
    }

    /**
     * 开关相关的渲染：**把开关状态与偏好对齐** + 刷新描述文案。
     *
     * 必须在这里做一次程序性 setChecked：XML 里 SwitchMaterial 的初始态永远是「关」，
     * 若用户打开功能后重进本页，开关会显示成关闭而描述写着「已开启」——自相矛盾，
     * 而且用户第一次点它时会被当成「打开」（其实是从错误起点出发）。
     * 走 [setSwitchProgrammatically] 而不是裸 `setChecked`，正是本任务的头号坑（见类注释）。
     */
    private fun renderSwitch() {
        val enabled = TimePrefs.isEnabled()
        setSwitchProgrammatically(enabled)
        switchDescView?.text = if (enabled) {
            getString(
                R.string.tm2_control_switch_desc_on,
                TimePrefs.toggleOffWaitSec()
            )
        } else {
            getString(R.string.tm2_control_switch_desc_off)
        }
    }

    // ==========================================================================================
    // 上学日配额
    // ==========================================================================================

    private fun bindQuotaButtons() {
        findViewById<View>(R.id.tm2_quota_minus)?.setOnClickListener { stepQuota(-QUOTA_STEP_MIN) }
        findViewById<View>(R.id.tm2_quota_plus)?.setOnClickListener { stepQuota(QUOTA_STEP_MIN) }
    }

    /**
     * 加减配额。
     *
     * - **调小**：立即生效（用户口径「调小立即生效」）。
     * - **调大**：等待 [TimePrefs.quotaIncreaseWaitSec] 秒；等待期间再点加减**取消**等待
     *   （否则用户点了 +10 又反悔、却必须等两分钟，体验很糟）。
     *
     * 已在上/下界时只提示，不启动倒计时 —— 让用户等 120 秒才发现「改不了」是最差体验。
     */
    private fun stepQuota(delta: Int) {
        val current = TimePrefs.schoolQuotaMin()
        val target = (current + delta).coerceIn(MIN_QUOTA_MIN, MAX_QUOTA_MIN)
        if (target == current) {
            val messageRes = if (delta < 0) {
                R.string.tm2_control_quota_min_reached
            } else {
                R.string.tm2_control_quota_max_reached
            }
            // 到达边界只提示，不起倒计时
            quotaHintView?.visibility = View.VISIBLE
            quotaHintView?.text = getString(messageRes, current)
            return
        }
        // 用户已反悔 / 重新选择 → 先撤掉之前的等待
        cancelQuotaGuard()
        if (delta < 0) {
            applyQuota(target, R.string.tm2_control_quota_decreased)
            return
        }
        val waitSec = TimePrefs.quotaIncreaseWaitSec()
        if (waitSec <= 0) {
            applyQuota(target, R.string.tm2_control_quota_increase_done)
            return
        }
        startQuotaGuard(target, waitSec)
    }

    /** 真正写配额并刷新 */
    private fun applyQuota(target: Int, messageRes: Int) {
        TimePrefs.setSchoolQuotaMin(target)
        renderQuota()
        quotaHintView?.visibility = View.VISIBLE
        quotaHintView?.text = getString(messageRes, TimePrefs.schoolQuotaMin())
    }

    /**
     * 「调大配额」的等待。
     *
     * v5.3.0：与 [startCloseGuard] 一样改由独立倒计时页承担。
     * `RESULT_OK` 才写目标配额；取消则提示「已取消，配额未变」。
     */
    private fun startQuotaGuard(target: Int, totalSec: Int) {
        quotaGuardTarget = target
        val intent = Intent(this, CountdownGateActivity::class.java)
        intent.putExtra(CountdownGateActivity.EXTRA_TOTAL_SEC, totalSec)
        intent.putExtra(
            CountdownGateActivity.EXTRA_TITLE,
            getString(R.string.tm2_quota_guard_title, target, totalSec)
        )
        intent.putExtra(CountdownGateActivity.EXTRA_SHOW_DETAIL, false)
        quotaGuardLauncherPending = true
        try {
            quotaGuardLauncher.launch(intent)
        } catch (e: Exception) {
            // 拉不起来就直接生效（同上：卡住用户比少等 120 秒更糟）
            quotaGuardLauncherPending = false
            Logu.w(TAG, "拉起倒计时页失败，直接调整配额：" + e.javaClass.simpleName)
            applyQuota(target, R.string.tm2_control_quota_increase_done)
        }
    }

    /** 取消「调大配额」的等待并给出「已取消」提示（用户必须知道配额没变） */
    private fun cancelQuotaGuard() {
        quotaGuardLauncherPending = false
        quotaGuardTarget = 0
        quotaHintView?.visibility = View.VISIBLE
        quotaHintView?.text = getString(
            R.string.tm2_control_quota_increase_cancelled,
            TimePrefs.schoolQuotaMin()
        )
    }

    /** 配额文案 + 数值 */
    private fun renderQuota() {
        val minutes = TimePrefs.schoolQuotaMin()
        quotaValueView?.text = getString(R.string.tm2_control_quota_value, "${minutes}分钟")
        // 配额本身**不是**三档语义的一档（三档讲的是「今天已经用了多久」）。
        // 这里刻意用中性白而不是绿色：否则用户会把「配额数字是绿的」误读成「当前处于绿档」。
        // 三档色的唯一合法用法是 TimeFormat.levelColor(...)，本页的「当前档位」那行走它。
        quotaDescView?.text = getString(
            R.string.tm2_control_quota_desc,
            minutes,
            TimePrefs.quotaIncreaseWaitSec()
        )
    }

    // ==========================================================================================
    // 取消区（两块倒计时提示都可点击取消）
    // ==========================================================================================

    private fun bindCancelAreas() {
        guardTipView?.setOnClickListener { cancelCloseGuard() }
        quotaHintView?.setOnClickListener {
            // 只有「调大等待中」才有可取消的东西；其它情况（边界提示/成功提示）点了就收起来
            if (quotaGuardLauncherPending) cancelQuotaGuard() else quotaHintView?.visibility = View.GONE
        }
    }

    // ==========================================================================================
    // 静态说明文字（数值全部从 TimePrefs 读，绝不硬编码）
    // ==========================================================================================

    private fun bindStaticTexts() {
        findViewById<TextView>(R.id.tm2_wait_toggle_off)?.text =
            getString(R.string.tm2_control_wait_toggle_off, TimePrefs.toggleOffWaitSec())
        findViewById<TextView>(R.id.tm2_wait_quota_up)?.text =
            getString(R.string.tm2_control_wait_quota_up, TimePrefs.quotaIncreaseWaitSec())
        findViewById<TextView>(R.id.tm2_wait_mid)?.text =
            getString(R.string.tm2_control_wait_mid, TimePrefs.midWaitSec())
        findViewById<TextView>(R.id.tm2_wait_high)?.text =
            getString(R.string.tm2_control_wait_high, TimePrefs.highWaitSec())
        findViewById<TextView>(R.id.tm2_wait_after)?.text =
            getString(R.string.tm2_control_wait_after, formatMinuteOfDay(TimePrefs.noCountdownAfterMin()))
        findViewById<TextView>(R.id.tm2_release_desc)?.text =
            getString(R.string.tm2_control_release_desc, TimePrefs.longPressReleaseSec())
    }

    /** 「当天第几分钟」→ `HH:mm`（21:30 这种文案给用户看，写 1290 没人懂） */
    private fun formatMinuteOfDay(minuteOfDay: Int): String {
        val hour = (minuteOfDay / 60).coerceIn(0, 23)
        val minute = (minuteOfDay % 60).coerceIn(0, 59)
        return String.format(LocaleHolder.LOCALE, "%02d:%02d", hour, minute)
    }

    // ==========================================================================================
    // 三色图例
    // ==========================================================================================

    /**
     * 图例的三行文案 + 「当前档位」。
     *
     * 绿档文案里没有秒数（不等待），黄/红各自带自己的等待秒数 —— 这两个数字必须来自
     * [TimePrefs.midWaitSec] / [TimePrefs.highWaitSec]，否则用户改了口径这里就对不上。
     */
    private fun renderLevels() {
        findViewById<TextView>(R.id.tm2_level_yellow_text)?.text =
            getString(R.string.tm2_control_level_yellow, TimePrefs.midWaitSec())
        findViewById<TextView>(R.id.tm2_level_red_text)?.text =
            getString(R.string.tm2_control_level_red, TimePrefs.highWaitSec())

        val usedMs = DailyPlan.usedMsToday()
        val level = DailyPlan.levelOf(usedMs)
        val nowView = findViewById<TextView>(R.id.tm2_level_now)
        nowView?.text = getString(R.string.tm2_control_level_now, TimeFormat.levelName(level))
        nowView?.setTextColor(TimeFormat.levelColor(this, level))
    }

    /** 内部持有 Locale 常量，避免每次 `String.format` 走默认 locale 造成数字本地化差异 */
    private object LocaleHolder {
        @JvmField
        val LOCALE: java.util.Locale = java.util.Locale.US
    }

    private companion object {
        /** 日志标签 */
        private const val TAG = "usage-control"
        /** 配额加减步长（分钟）：手表上一格 1 分钟要点太多次，10 分钟一档最省事 */
        const val QUOTA_STEP_MIN = 10

        /** 配额下界（与 TimePrefs 的夹紧区间一致） */
        const val MIN_QUOTA_MIN = 1

        /** 配额上界：一整天（与 TimePrefs.MAX_SCHOOL_QUOTA_MIN 一致） */
        const val MAX_QUOTA_MIN = 24 * 60
    }
}
