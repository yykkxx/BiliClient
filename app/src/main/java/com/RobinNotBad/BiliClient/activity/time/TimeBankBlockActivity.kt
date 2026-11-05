package com.RobinNotBad.BiliClient.activity.time

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.TimeBankManager
import com.RobinNotBad.BiliClient.util.TimeTracking
import com.google.android.material.button.MaterialButton

/**
 * 超配额拦截页（v5.2.0 需求 3，规格 §3.1「用完不可进入主界面」）。
 *
 * 启动页在进入主界面前判定「时间银行开着 && 今天没被关闭 && 今天已超配额」→ 只进本页。
 * 本页给用户三条出路（对应 §3.1 的 1/2/3）：
 * 1. 「等待 N 秒后关闭此功能一天」→ 启动 [CountdownActivity]（`EXTRA_RESULT_ON_END=true`），
 *    归零回调 `RESULT_OK` → [TimeBankManager.disableToday] → 进主界面 → 自己 finish；
 * 2. 「借时间」→ [TimeBankActivity]（借完返回本页，本页在 `onResume` 里重新判定，
 *    配额够了就直接放行，不用用户再点一次）；
 * 3. 「退出应用」→ `finishAffinity()`。
 *
 * # 为什么本页要暂停使用时长统计（规格 §4.1 / §4.3）
 * 用户在拦截页上等 120 秒，这 120 秒不该算进「今天用了多久」——否则等待反而让配额更不够，
 * 用户会觉得被双重处罚。所以 `onResume` → `TimeTracking.pauseTracking()`、
 * `onPause` → `TimeTracking.resumeTracking()`。`TimeTracking` 由 usage-stats 代理提供，
 * 本页只按 §4.3 的签名调用。
 *
 * # 放行策略（设计判断）
 * 本页**不把「进主界面」当成一次性动作**：只要用户还在本页、配额又变够了（借到时间了），
 * 就立刻自己跳转主界面并 finish。这样用户从「借时间」页按返回键回来时不会看到一个
 * 仍写着「配额已用完」的死页（那会让人以为借失败了）。
 */
class TimeBankBlockActivity : BaseActivity() {

    private var detailView: TextView? = null
    private var waitButton: MaterialButton? = null

    /** 是否正在等倒计时页（等的时候 onResume 不要抢先放行，否则等待就没意义了） */
    private var waitingCountdown = false

    /**
     * 倒计时页结果回调。
     *
     * 归零 → `RESULT_OK` → 关掉今天并放行；用户中途返回（非 OK）→ 视为放弃等待，
     * 留在本页（**不**放行：没等到时间就不该白给）。
     */
    private val countdownLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            waitingCountdown = false
            if (result.resultCode == RESULT_OK) {
                TimeBankManager.disableToday()
                enterMain()
            } else {
                Log.i(TAG, "countdown cancelled, stay on block page")
                refreshState()
            }
        }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_bank_block)
        setPageName(getString(R.string.time_bank_block_title))
        // 拦截页没有「返回上一层」的语义：退出应用才是明确出路，所以不给顶栏退出，
        // 但保留返回键（用户按返回就是回到桌面/启动页，等同于「退出应用」）
        detailView = findViewById(R.id.time_bank_block_detail)
        waitButton = findViewById(R.id.time_bank_block_wait)

        val waitSeconds = TimeBankManager.waitCloseSec()
        waitButton?.text = getString(R.string.time_bank_block_wait_action, waitSeconds)
        waitButton?.setOnClickListener { startCountdown(waitSeconds) }

        findViewById<View>(R.id.time_bank_block_borrow).setOnClickListener {
            startActivity(Intent(this, TimeBankActivity::class.java))
        }
        findViewById<View>(R.id.time_bank_block_exit).setOnClickListener {
            finishAffinity()
        }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        // 等待统计暂停：拦截页上的停留不算「今天用了多久」（规格 §4.1）
        TimeTracking.pauseTracking()
        refreshState()
        // 从「借时间」页回来且配额已经够了 → 直接放行（见类注释「放行策略」）
        if (!waitingCountdown && !TimeBankManager.isExhaustedToday()) {
            enterMain()
        }
    }

    override fun onPause() {
        TimeTracking.resumeTracking()
        super.onPause()
    }

    /** 刷新「已用 X / 可用 Y」 */
    private fun refreshState() {
        detailView?.text = getString(
            R.string.time_bank_block_detail,
            TimeBankManager.usedMinToday(),
            TimeBankManager.availableQuotaMinToday()
        )
    }

    /**
     * 启动独立倒计时页（规格 §2.2 的静态接口）。
     *
     * **`EXTRA_FINISH_ON_END` 用默认值 true**（不显式传 false）：
     * 用户点「等待 N 秒后关闭此功能一天」之后，期待的是「等完就自动放行」，
     * 不需要再点一下屏幕。CountdownActivity 归零时先 `setResult(RESULT_OK)` 再 `finish()`，
     * 结果会正常交付到本页的 [countdownLauncher]，于是自动 `disableToday()` + `enterMain()`。
     *
     * （历史方案曾传 `false`，让倒计时页停在归零画面、由用户单击关闭；实测体验更差——
     * 用户以为卡住了，还要再点一次。此处改为自动放行。）
     */
    private fun startCountdown(waitSeconds: Int) {
        waitingCountdown = true
        val intent = Intent(this, CountdownActivity::class.java)
        intent.putExtra(CountdownActivity.EXTRA_SECONDS, waitSeconds)
        intent.putExtra(CountdownActivity.EXTRA_TITLE, getString(R.string.time_bank_block_headline))
        intent.putExtra(CountdownActivity.EXTRA_SUBTITLE, getString(R.string.time_bank_block_wait_hint, waitSeconds))
        intent.putExtra(CountdownActivity.EXTRA_RESULT_ON_END, true)
        try {
            countdownLauncher.launch(intent)
        } catch (e: Exception) {
            // 倒计时页不可用（例如未注册）时不能把用户困在拦截页：
            // 直接当作「等待已完成」，关掉今天并放行，与用户预期（我要出去）一致。
            waitingCountdown = false
            Log.w(TAG, "countdown page unavailable, disable today directly", e)
            MsgUtil.toast(getString(R.string.time_bank_block_countdown_unavailable))
            TimeBankManager.disableToday()
            enterMain()
        }
    }

    /** 放行：进主界面并 finish 自己（规格 §3.4-2 明确要求 startActivity，不要靠 finishAffinity） */
    private fun enterMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, RecommendActivity::class.java))
        finish()
    }

    private companion object {
        /** 日志标签 */
        const val TAG = "time_bank_block"
    }
}
