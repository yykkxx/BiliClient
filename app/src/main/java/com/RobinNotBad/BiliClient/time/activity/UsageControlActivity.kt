package com.RobinNotBad.BiliClient.time.activity

import android.os.Bundle
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.TimeFormat
import com.RobinNotBad.BiliClient.time.TimePrefs
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

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
 * ## 为什么用协程而不是 Handler.postDelayed
 * 项目门禁禁止新增 `runOnUiThread` / `Thread.sleep`，统一走
 * [CoroutineScopes.launchUi]。协程还有个好处：`onDestroy` 里 `job.cancel()` 就干净了，
 * Handler 需要自己 removeCallbacks 且容易漏。
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

    /** 正在进行的「关闭总开关」倒计时；非 null 表示倒计时中 */
    private var closeGuardJob: Job? = null

    /** 正在进行的「调大配额」倒计时；非 null 表示倒计时中 */
    private var quotaGuardJob: Job? = null

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

    override fun onDestroy() {
        // 协程在 onDestroy 必须取消：否则它每秒还会去碰已经销毁的 View
        closeGuardJob?.cancel()
        closeGuardJob = null
        quotaGuardJob?.cancel()
        quotaGuardJob = null
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
        if (closeGuardJob != null) {
            setSwitchProgrammatically(true)
            return
        }
        val waitSec = TimePrefs.toggleOffWaitSec()
        if (!isChecked && waitSec > 0) {
            setSwitchProgrammatically(true)
            startCloseGuard(waitSec)
            return
        }
        // 打开方向立即生效（用户口径明确：只有关闭要等）
        TimePrefs.setEnabled(isChecked)
        if (isChecked) cancelCloseGuard()
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
     * 启动「关闭总开关」的原地倒计时。
     *
     * 每一秒刷新提示；归零后**真正写入 false** 并把开关拨到关。
     * 提示可点击 → [cancelCloseGuard]。
     */
    private fun startCloseGuard(totalSec: Int) {
        cancelCloseGuard()
        val tip = guardTipView
        tip?.visibility = View.VISIBLE
        closeGuardJob = CoroutineScopes.launchUi {
            var remaining = totalSec
            while (remaining > 0) {
                // 每秒写一次文案；totalSec 与 remaining 的顺序必须与 strings 的 %1$d/%2$d 一致
                tip?.text = getString(R.string.tm2_guard_tip, totalSec, remaining)
                delay(TICK_INTERVAL_MS)
                remaining -= 1
            }
            // 归零：此刻才真正关闭
            TimePrefs.setEnabled(false)
            closeGuardJob = null
            tip?.visibility = View.GONE
            setSwitchProgrammatically(false)
            renderSwitch()
        }
    }

    /** 取消「关闭总开关」的倒计时：开关保持开启、偏好不写 */
    private fun cancelCloseGuard() {
        closeGuardJob?.cancel()
        closeGuardJob = null
        guardTipView?.visibility = View.GONE
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
     * 「调大配额」的倒计时。归零后写目标值；期间提示可点击取消。
     */
    private fun startQuotaGuard(target: Int, totalSec: Int) {
        val hint = quotaHintView
        hint?.visibility = View.VISIBLE
        quotaGuardJob = CoroutineScopes.launchUi {
            var remaining = totalSec
            while (remaining > 0) {
                hint?.text = getString(
                    R.string.tm2_control_quota_increase_hint,
                    target,
                    totalSec,
                    remaining
                )
                delay(TICK_INTERVAL_MS)
                remaining -= 1
            }
            quotaGuardJob = null
            applyQuota(target, R.string.tm2_control_quota_increase_done)
        }
    }

    /** 取消「调大配额」的倒计时并给出「已取消」提示（用户必须知道配额没变） */
    private fun cancelQuotaGuard() {
        val job = quotaGuardJob ?: return
        job.cancel()
        quotaGuardJob = null
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
            if (quotaGuardJob != null) cancelQuotaGuard() else quotaHintView?.visibility = View.GONE
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
        /** 倒计时心跳：1 秒 */
        const val TICK_INTERVAL_MS = 1000L

        /** 配额加减步长（分钟）：手表上一格 1 分钟要点太多次，10 分钟一档最省事 */
        const val QUOTA_STEP_MIN = 10

        /** 配额下界（与 TimePrefs 的夹紧区间一致） */
        const val MIN_QUOTA_MIN = 1

        /** 配额上界：一整天（与 TimePrefs.MAX_SCHOOL_QUOTA_MIN 一致） */
        const val MAX_QUOTA_MIN = 24 * 60
    }
}
