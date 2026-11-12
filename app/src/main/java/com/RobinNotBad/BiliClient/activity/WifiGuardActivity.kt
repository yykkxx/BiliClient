package com.RobinNotBad.BiliClient.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.WifiGuard
import com.google.android.material.button.MaterialButton

/**
 * 「受保护 WiFi」提示页（v5.3.4 实验室功能，需求 8）。
 *
 * # 用户需求原文
 * 「连接到特定 WiFi（用户填写）之后不使用网络，即不进行任何请求，
 * 直接弹出页面提示，点击继续之后才继续请求」
 *
 * # 它和 [WifiGuard] 的分工
 * - [WifiGuard]：**判定与拦截**（网络线程里执行，不许碰 UI）；
 * - 本页：**告知与放行**（主线程，唯一的放行入口是"继续"按钮）。
 *
 * # 这一页为什么必须能"退出"
 * 如果只给「继续」一个按钮，用户被拦在一个必须联网才能用的应用里，
 * 想退出只能按 home —— 而本项目的门禁传统是「宁可放行也不能困住用户」。
 * 所以给两个出口：
 * - **继续**：[WifiGuard.userContinued]，放行 10 分钟，页面关闭，请求继续；
 * - **退出应用**：直接 finishAffinity，让用户回到桌面而不是被困住。
 *
 * 没有「取消」按钮：取消等于"留在这个界面"，而留在界面什么也做不了 ——
 * 用户真正的选择只有"联网"或"不玩了"，所以只要这两个。
 *
 * # 为什么内容要写清「哪个 WiFi」
 * 用户可能填了多个 SSID，明确显示当前命中的那个，才能让他意识到
 * "哦是这个网络，我填过它"。否则会显得像应用无故断网。
 */
class WifiGuardActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_guard)
        setPageName("受保护的 WiFi")

        val ssid = WifiGuard.currentSsid(applicationContext)
        findViewById<TextView>(R.id.wifi_guard_ssid)?.text =
            if (ssid.isNullOrEmpty()) getString(R.string.wifi_guard_unknown_ssid)
            else getString(R.string.wifi_guard_current_ssid, ssid)

        // 点「继续」：放行并关闭本页。
        // 顺序很重要 —— **先** userContinued 再 finish：放行状态一经写入，
        // 在途的排队请求（如果有）才能在下一次 shouldBlock 判定时立刻通过。
        findViewById<MaterialButton>(R.id.wifi_guard_continue)?.setOnClickListener {
            WifiGuard.userContinued()
            finish()
        }

        // 点「退出应用」：不联网就退出，给用户一条不困住他的路
        findViewById<MaterialButton>(R.id.wifi_guard_exit)?.setOnClickListener {
            finishAffinity()
        }
    }

    /**
     * 返回键不关闭本页。
     *
     * 理由：本页是**拦截状态的唯一说明**。如果按返回就关掉，
     * 用户会回到一个「所有请求都在后台静默失败」的界面 —— 那种"看起来能用但什么都刷不出来"
     * 的状态比明确告知糟糕得多。想离开请用「退出应用」。
     *
     * 这与时间门禁页的做法一致（那几页同样吞掉返回键，见 DayTypePromptActivity）。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 有意不分派：想离开请点「退出应用」
    }

    companion object {
        /**
         * 从任意上下文拉起本页。
         *
         * 注意：从**非 Activity 上下文**（Application/Service）启动 Activity 在
         * Android 10+ 受限，所以这里必须带 `FLAG_ACTIVITY_NEW_TASK`。
         */
        @JvmStatic
        fun launch(context: Context?) {
            val ctx = context ?: return
            try {
                val intent = Intent(ctx, WifiGuardActivity::class.java)
                if (ctx !is android.app.Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // 已经在最上层时不重复创建（否则并发请求会弹好几层）
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ctx.startActivity(intent)
            } catch (e: Throwable) {
                android.util.Log.w("wifi-guard", "拉起守卫页面失败：" + e.javaClass.simpleName)
            }
        }
    }
}
