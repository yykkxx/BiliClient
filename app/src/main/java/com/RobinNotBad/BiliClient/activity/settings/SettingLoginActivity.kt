package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.login.AppRcmdLoginActivity
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity
import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.api.migration.ApiRouter
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

/**
 * 「登录选项」总栏目（实验室「重构账号体系」的配套页面，v5.1.1）。
 *
 * 这里统一管理四个"端口"的登录状态与操作：
 * - Web = 软件本体的 Cookie 登录（与设置主页的登录/清空登录数据同一套逻辑）；
 * - Android手机 / HD / TV = 复用 [AppRcmdApi] 既有三端 token 槽位
 *   （app_rcmd_{phone|hd|tv}_*），扫码入口是带 identity extra 的 [AppRcmdLoginActivity]；
 * - 端口选择写 [SharedPreferencesUtil.ACCOUNT_REFACTOR_PORT]，选 web = 全接口保持 web 通道；
 * - 兼容性红线：本页只读写新增键，绝不改写旧键；绝不把 web Cookie 写进任何
 *   app_rcmd 槽位，也不反向转移（各端登录互相独立）。
 *
 * 实验室总开关没开时，页面顶部显示说明并引导去实验室开启（入口一直可见，便于发现）。
 */
class SettingLoginActivity : BaseActivity() {

    private var statusText: TextView? = null
    private var statsText: TextView? = null
    private var labTipCard: MaterialCardView? = null
    private var webText: TextView? = null
    private var androidText: TextView? = null
    private var hdText: TextView? = null
    private var tvText: TextView? = null
    private var portGroup: RadioGroup? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting_login)
        setPageName("登录选项")
        setTopbarExit()

        statusText = findViewById(R.id.loginOptionsStatusText)
        statsText = findViewById(R.id.loginOptionsStatsText)
        webText = findViewById(R.id.loginOptionsWebText)
        androidText = findViewById(R.id.loginOptionsAndroidText)
        hdText = findViewById(R.id.loginOptionsHdText)
        tvText = findViewById(R.id.loginOptionsTvText)
        portGroup = findViewById(R.id.loginOptionsPortGroup)
        labTipCard = findViewById(R.id.loginOptionsLabTip)

        portGroup?.setOnCheckedChangeListener(portListener)

        findViewById<MaterialCardView>(R.id.loginOptionsWeb).setOnClickListener { onWebClick() }
        findViewById<MaterialCardView>(R.id.loginOptionsAndroid).setOnClickListener {
            onIdentityClick(AppRcmdApi.IDENTITY_PHONE)
        }
        findViewById<MaterialCardView>(R.id.loginOptionsHd).setOnClickListener {
            onIdentityClick(AppRcmdApi.IDENTITY_HD)
        }
        findViewById<MaterialCardView>(R.id.loginOptionsTv).setOnClickListener {
            onIdentityClick(AppRcmdApi.IDENTITY_TV)
        }
        // 实验开关没开时的引导卡：跳实验室
        labTipCard?.setOnClickListener {
            startActivity(Intent(this, SettingLaboratoryActivity::class.java))
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 从扫码登录页 / 实验室返回时刷新状态
        refreshStatus()
    }

    private val portListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->
        val port = when (checkedId) {
            R.id.loginOptionsPortAndroid -> ApiRouter.PORT_ANDROID
            R.id.loginOptionsPortHd -> ApiRouter.PORT_HD
            R.id.loginOptionsPortTv -> ApiRouter.PORT_TV
            else -> ApiRouter.PORT_WEB
        }
        if (port != ApiRouter.currentPort()) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.ACCOUNT_REFACTOR_PORT, port)
            // 端口切换会改变路由身份映射：清掉上一轮按旧身份累计的连续失败暂停，
            // 让端点立刻按新端口重试（幂等，重复触发无副作用）
            ApiRouter.clearSuspensions()
            MsgUtil.showMsg("已切换，全接口将走" + ApiRouter.portDisplayName(port) + "端")
            refreshStatus()
        }
    }

    /** Web 行：已登录 → 确认退出（与设置主页同一套 web 退出逻辑）；未登录 → 去 web 扫码登录 */
    private fun onWebClick() {
        if (isWebLogin()) {
            AlertDialog.Builder(this)
                .setTitle("退出 Web 登录")
                .setMessage("确定要退出软件本身的 web 登录吗？（只清 web 登录数据，不影响 Android手机/HD/TV 三端的扫码登录）")
                .setPositiveButton("退出") { _, _ -> clearWebLogin() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }

    /**
     * web 退出登录：与设置主页「清空登录数据」同一套逻辑
     * （协议退出 + 清 web 键；绝不碰 app_rcmd 槽位）。
     */
    private fun clearWebLogin() {
        CenterThreadPool.run(Runnable {
            try {
                UserInfoApi.exitLogin()
            } catch (e: Exception) {
                Logu.d(TAG, "web 协议退出失败：" + e.javaClass.simpleName)
            }
        })
        SharedPreferencesUtil.removeValue(SharedPreferencesUtil.cookies)
        SharedPreferencesUtil.removeValue(SharedPreferencesUtil.mid)
        SharedPreferencesUtil.removeValue(SharedPreferencesUtil.csrf)
        SharedPreferencesUtil.removeValue(SharedPreferencesUtil.refresh_token)
        SharedPreferencesUtil.removeValue(SharedPreferencesUtil.cookie_refresh)
        MsgUtil.showMsg("Web 登录已退出")
        refreshStatus()
    }

    /** 三端行：该端已登录 → 确认退出（只清该端槽位）；未登录 → 按该身份扫码登录 */
    private fun onIdentityClick(identityId: String) {
        if (AppRcmdApi.isIdentityLogin(identityId)) {
            AlertDialog.Builder(this)
                .setTitle("退出" + identityDisplayName(identityId) + "登录")
                .setMessage("确定要退出该端登录吗？（只清该端 token，不影响 web 与其他端）")
                .setPositiveButton("退出") { _, _ ->
                    AppRcmdApi.logoutIdentity(identityId)
                    MsgUtil.showMsg("已退出" + identityDisplayName(identityId) + "登录")
                    refreshStatus()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            val intent = Intent(this, AppRcmdLoginActivity::class.java)
            intent.putExtra(AppRcmdLoginActivity.EXTRA_IDENTITY, identityId)
            startActivity(intent)
        }
    }

    private fun identityDisplayName(identityId: String): String {
        return when (identityId) {
            AppRcmdApi.IDENTITY_PHONE -> "Android手机端"
            AppRcmdApi.IDENTITY_HD -> "HD端"
            AppRcmdApi.IDENTITY_TV -> "TV端"
            else -> identityId
        }
    }

    private fun isWebLogin(): Boolean {
        return SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) > 0L
    }

    private fun identityStatusText(identityId: String): String {
        if (!AppRcmdApi.isIdentityLogin(identityId)) return "未登录"
        val mid = AppRcmdApi.getIdentityMid(identityId)
        return if (mid > 0) "已登录（uid $mid）" else "已登录"
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val port = ApiRouter.currentPort()
        val labEnabled = ApiRouter.isAccountRefactorLabEnabled()

        // 实验开关没开：顶部提示卡可见（入口本身一直可见，便于发现）
        labTipCard?.visibility = if (labEnabled) View.GONE else View.VISIBLE

        // 状态卡（风格沿用「全 API 迁移状态」页）：当前端口 + 四端登录状态
        val sb = StringBuilder()
        sb.append("当前端口：").append(ApiRouter.portDisplayName(port))
        sb.append(if (labEnabled && port != ApiRouter.PORT_WEB) "（单端口模式，全接口走该端）" else "（全接口走 web，与 5.1.0 一致）")
        sb.append("\nWeb：").append(if (isWebLogin()) "已登录（uid " + SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) + "）" else "未登录")
        sb.append("\nAndroid手机：").append(identityStatusText(AppRcmdApi.IDENTITY_PHONE))
        sb.append("\nHD：").append(identityStatusText(AppRcmdApi.IDENTITY_HD))
        sb.append("\nTV：").append(identityStatusText(AppRcmdApi.IDENTITY_TV))
        statusText?.text = sb.toString()

        // 每端口操作行文案（点同一行的行为随登录状态变化）
        webText?.text = if (isWebLogin()) {
            "Web 账号：已登录（uid " + SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) + "）\n点击退出 web 登录"
        } else {
            "Web 账号：未登录\n点击去登录（软件本身的 web 扫码）"
        }
        androidText?.text = identityRowText(AppRcmdApi.IDENTITY_PHONE, "Android手机端")
        hdText?.text = identityRowText(AppRcmdApi.IDENTITY_HD, "HD端")
        tvText?.text = identityRowText(AppRcmdApi.IDENTITY_TV, "TV端")

        // 端口单选回显：程序化置位会触发监听器，先摘掉再恢复
        val group = portGroup
        if (group != null) {
            group.setOnCheckedChangeListener(null)
            when (port) {
                ApiRouter.PORT_ANDROID -> group.check(R.id.loginOptionsPortAndroid)
                ApiRouter.PORT_HD -> group.check(R.id.loginOptionsPortHd)
                ApiRouter.PORT_TV -> group.check(R.id.loginOptionsPortTv)
                else -> group.check(R.id.loginOptionsPortWeb)
            }
            group.setOnCheckedChangeListener(portListener)
        }

        // 迁移统计（与「全 API 迁移状态」页同源，沿用 ApiRouter 既有统计口径）
        statsText?.text = buildStatsText()
    }

    /** 三端操作行文案：登录态 + uid + 本行点击会做什么 */
    private fun identityRowText(identityId: String, displayName: String): String {
        return if (AppRcmdApi.isIdentityLogin(identityId)) {
            displayName + "：已登录（uid " + AppRcmdApi.getIdentityMid(identityId) + "）\n点击退出该端登录"
        } else {
            displayName + "：未登录\n点击扫码登录该端"
        }
    }

    private fun buildStatsText(): String {
        val hits = ApiRouter.hitCount()
        val fallbacks = ApiRouter.fallbackCount()
        val attempts = hits + fallbacks
        val rate = if (attempts > 0) hits * 100L / attempts else 0L
        val sb = StringBuilder()
        sb.append("迁移统计（与「全 API 迁移状态」页同源）：")
        sb.append("\nAPP 通道命中：").append(hits).append(" 次")
        sb.append("\n回退 web：").append(fallbacks).append(" 次")
        sb.append("\n命中率：").append(rate).append("%")
        sb.append("\n写操作不重放：").append(ApiRouter.noReplayCount()).append(" 次")
        return sb.toString()
    }

    companion object {
        private const val TAG = "login-options"
    }
}
