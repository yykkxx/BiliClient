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
import com.RobinNotBad.BiliClient.util.AccountManager
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
    private var tierText: TextView? = null
    private var logoutAllText: TextView? = null
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
        tierText = findViewById(R.id.loginOptionsTierText)
        logoutAllText = findViewById(R.id.loginOptionsLogoutAllText)
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
        // 一键退出全部账号（web + 三端口一起清）；单独各行的退出逻辑保持不变
        findViewById<MaterialCardView>(R.id.loginOptionsLogoutAll).setOnClickListener { onLogoutAllClick() }
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
     *
     * 「退出全部账号」也要清 web，为避免两个入口各写一份（迟早走样），
     * 实际动作抽到 [clearWebLoginSilently]，这里只负责提示与刷新。行为与改动前逐字一致。
     */
    private fun clearWebLogin() {
        clearWebLoginSilently()
        MsgUtil.showMsg("Web 登录已退出")
        refreshStatus()
    }

    /**
     * web 退出的实际动作：协议退出（后台）+ 清 web 键 + 清 web 昵称/头像展示缓存。
     * 不提示、不刷新，供「Web 账号」行与「退出全部账号」两个入口复用——
     * 两者必须清同样的东西，否则只点单行退出时会残留上个号的昵称（见 [AccountManager.displayName]）。
     */
    private fun clearWebLoginSilently() {
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
        // 展示缓存也要清（只清昵称/头像，不动任何凭据），与 MySpaceActivity.doWebLogout() 口径一致
        AccountManager.clearWebProfile()
    }

    // ==================== 一键退出全部账号 ====================

    /**
     * 「退出全部账号」：三级模式下用户可能 web + 多个端口同时登录，逐个点太麻烦。
     *
     * 这里**只做全清**，各行的单独退出逻辑完全不动（用户仍可只退某一端）。
     * 二次确认里明确写「只清登录凭据、不删其他数据」，因为「退出全部」听起来像重置账号。
     */
    private fun onLogoutAllClick() {
        if (loggedChannelCount() == 0) {
            MsgUtil.showMsg("当前没有任何登录，无需退出")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出全部账号")
            .setMessage(
                "将同时退出：\n• Web 登录（软件本身的 Cookie）\n• Android手机 / HD / TV 三端的扫码登录\n\n" +
                        "只清登录凭据，不删除收藏夹、稍后再看、历史、动态等其他任何数据；重新登录后数据仍在。"
            )
            .setPositiveButton("全部退出") { _, _ -> logoutAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 全清：web 走本页既有逻辑（含展示缓存），三端各清各自槽位 */
    private fun logoutAll() {
        // 三端是纯本地写偏好（同步完成），先清它们；再清 web（含后台协议退出与昵称/头像缓存）。
        // 两者都不发回调，所以最后统一 refreshStatus 一次，界面就是「全部已退出」的稳定状态。
        for (identityId in IDENTITIES) AppRcmdApi.logoutIdentity(identityId)
        clearWebLoginSilently()
        refreshStatus()
        MsgUtil.showMsg("已退出全部账号：Web 与 Android手机/HD/TV 三端")
    }

    /** 当前有几处登录（web 与三端各算一处），用于按钮文案与"无事可做"的判断 */
    private fun loggedChannelCount(): Int {
        var count = if (isWebLogin()) 1 else 0
        for (identityId in IDENTITIES) {
            if (AppRcmdApi.isIdentityLogin(identityId)) count++
        }
        return count
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

        // 状态卡（风格沿用「全 API 迁移状态」页）：当前级别 + 当前端口 + 四端登录状态
        // 级别放第一、二行：这是用户在这个页面上最先要确认的事（我现在几级、这一级意味着什么），
        // 往下滚才是端口与四端登录明细。级别的完整解释在紧跟着的说明卡里。
        val tier = AccountManager.currentTier()
        val sb = StringBuilder()
        sb.append("当前级别：").append(AccountManager.tierName(tier))
        sb.append("\n　→ ").append(tierSummary(tier))
        sb.append("\n当前端口：").append(ApiRouter.portDisplayName(port))
        sb.append(if (labEnabled && port != ApiRouter.PORT_WEB) "（单端口模式，全接口走该端）" else "（全接口走 web，与 5.1.0 一致）")
        sb.append("\nWeb：").append(if (isWebLogin()) "已登录（uid " + SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) + "）" else "未登录")
        sb.append("\nAndroid手机：").append(identityStatusText(AppRcmdApi.IDENTITY_PHONE))
        sb.append("\nHD：").append(identityStatusText(AppRcmdApi.IDENTITY_HD))
        sb.append("\nTV：").append(identityStatusText(AppRcmdApi.IDENTITY_TV))
        statusText?.text = sb.toString()

        // 级别说明卡：当前级别 + 这一级的含义 + 该级别要不要 Web 登录
        tierText?.text = buildTierText()

        // 「退出全部账号」卡片：把会发生什么提前说清楚，未登录时也说明当前无事可做
        val channels = loggedChannelCount()
        logoutAllText?.text = if (channels > 0) {
            "当前共 " + channels + " 处登录（Web 与三端分别计算）\n点击一次退出全部"
        } else {
            "当前没有任何登录\n（Web 与 Android手机/HD/TV 均未登录）"
        }

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

    /**
     * 级别说明卡（A + C 两项任务的核心产出）。
     *
     * 用户在这个页面上最容易问的两件事就是「我现在是几级」和「这一级还要不要登 Web」。
     * 级别名与一句话结论已在上方状态卡给出，这里展开**该级别的行为含义**，
     * 而不是让用户自己去实验室页面拼开关组合推断。
     */
    private fun buildTierText(): String {
        val tier = AccountManager.currentTier()
        val sb = StringBuilder()
        sb.append(tierMeaning(tier))
        // 级别在「实验室 → 重构账号体系」选了非 web 端口时会被无条件拉成三级，
        // 这时用户并没有打开全 API 迁移开关，必须说明来源，否则会觉得"我明明没开怎么变三级了"。
        if (tier == AccountManager.TIER_FULL && !isMigrationSwitchOn()) {
            sb.append("\n\n级别来源：实验室「重构账号体系」选了非 Web 端口，因此等同三级——")
            sb.append("此时端口就是唯一账号，不放行的话整个 App 都用不了。")
        }
        return sb.toString()
    }

    /** 状态卡里的一句话级别结论（每个级别只讲"最要紧的一件事"） */
    private fun tierSummary(tier: Int): String {
        return when (tier) {
            AccountManager.TIER_FULL -> "任一端口登录即可，无需登录 Web"
            AccountManager.TIER_RECOMMEND -> "只有推荐页走 APP 接口，账号功能仍需 Web 登录"
            else -> "纯 Web，账号功能需要 Web 登录"
        }
    }

    /** 各级别的含义 + 需不需要 Web 登录（一级/二级必须说清"要 Web"，三级必须说清"不用 Web"） */
    private fun tierMeaning(tier: Int): String {
        return when (tier) {
            AccountManager.TIER_FULL ->
                "三级模式：任一端口登录即可使用全部功能，无需登录 Web。" +
                        "退出 Web 登录不会影响「我的」「稍后再看」「动态」「消息」等其他功能。\n" +
                        "全接口都走上方所选端口，失败自动回退 Web。"
            AccountManager.TIER_RECOMMEND ->
                "二级模式：只有推荐页走 APP 接口，其余接口仍全部走 Web。\n" +
                        "当前级别需要 Web 登录才能使用收藏/历史等账号功能" +
                        "（端口账号此时不参与判定，仅推荐页接口用得到它）。"
            else ->
                "一级模式：纯 Web，与老版本行为一致。\n" +
                        "当前级别需要 Web 登录才能使用收藏/历史等账号功能" +
                        "（端口登录不会让这些按钮变成可用，登录了也只走 Web）。"
        }
    }

    /** 全 API 迁移开关是否真的开着（用于区分"三级"是来自开关还是来自单端口模式） */
    private fun isMigrationSwitchOn(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.API_MIGRATION_ENABLED, true)
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

        /** 三个端口身份（顺序与 AccountManager 内部一致：手机 → HD → TV） */
        private val IDENTITIES = arrayOf(
            AppRcmdApi.IDENTITY_PHONE, AppRcmdApi.IDENTITY_HD, AppRcmdApi.IDENTITY_TV
        )
    }
}
