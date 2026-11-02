package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.login.AppRcmdLoginActivity
import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 移动端推荐逻辑（实验性）设置页。
 *
 * v5.0.0 登录体系重构后：
 * - 客户端身份选择（HD版 / TV版 / 手机版）= 选择“当前用哪一派”，
 *   三端登录状态各自独立保留，可同时登录、随时切换，切换不要求重新登录；
 * - 登录一律走各端自己的扫码（原「手机版 web 账号一键转换」已移除——
 *   禁止 web 与外部端之间、以及三端互相之间的任何转移）；
 * - 「使用移动端推荐逻辑」开关（只影响推荐页，用的是当前身份的登录态）；
 * - 退出登录只清当前身份的登录，不影响其他端和 web 登录。
 */
class SettingAppRcmdActivity : BaseActivity() {

    private var switchView: SwitchMaterial? = null
    private var loginText: TextView? = null
    private var logoutCard: MaterialCardView? = null
    private var identityText: TextView? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting_app_rcmd)
        setPageName("移动端推荐逻辑")
        setTopbarExit()

        switchView = findViewById(R.id.appRcmdSwitch)
        loginText = findViewById(R.id.appRcmdLoginText)
        logoutCard = findViewById(R.id.appRcmdLogout)
        identityText = findViewById(R.id.appRcmdIdentityText)

        //客户端身份选择（HD / TV / 手机）
        findViewById<MaterialCardView>(R.id.appRcmdIdentity).setOnClickListener { showIdentityDialog() }

        //扫码登录（每端只能用自己的扫码协议登录，禁止任何跨端转移）
        val loginCard = findViewById<MaterialCardView>(R.id.appRcmdLogin)
        loginCard.setOnClickListener {
            startActivity(Intent(this, AppRcmdLoginActivity::class.java))
        }

        //开启/关闭移动端推荐逻辑（只影响推荐页）
        switchView!!.setOnCheckedChangeListener(switchListener)

        //退出移动端登录：只清移动端登录信息，不动 web 登录
        logoutCard!!.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("退出移动端登录")
                .setMessage("确定要退出移动端登录吗？（不影响软件本身的web登录）")
                .setPositiveButton("退出") { _, _ ->
                    AppRcmdApi.logout()
                    refreshStatus()
                    MsgUtil.showMsg("已退出移动端登录")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        //从扫码登录页返回时刷新状态（登录成功后可能已开启开关）
        refreshStatus()
    }

    /**
     * 身份选择弹窗。
     *
     * 二级/三级下可选三个 APP 端口（HD / TV / 手机）；
     * **三级额外多一个「Web 端」**（用户明确要求）——选中后推荐页退回 web 推荐接口，
     * 其余接口仍走所选端口。这样「推荐页想用 Web、其它想用端口」也能做到。
     */
    @SuppressLint("SetTextI18n")
    private fun showIdentityDialog() {
        val identities = ArrayList<Pair<String, String>>(4)
        if (showWebIdentityOption()) {
            // 用空串代表「Web 端」：AppRcmdApi 的身份 id 里没有 web 这一派，
            // 这里只是 UI 上的一个选项，落到偏好键 APP_RCMD_USE_WEB，不参与身份体系
            identities.add("" to "Web 端（推荐页走 Web 接口，与软件登录共用）")
        }
        identities.add(AppRcmdApi.IDENTITY_HD to "HD版（android_hd）")
        identities.add(AppRcmdApi.IDENTITY_TV to "TV版（云视听小电视）")
        identities.add(AppRcmdApi.IDENTITY_PHONE to "手机版（与官方手机客户端一致）")

        val usingWeb = isWebChannelChosen()
        val currentId = if (usingWeb) "" else AppRcmdApi.getIdentity().id
        // 每个身份后面直接标出登录状态，避免"选了哪端、哪端登没登录"看不出来
        val labels = identities.map { (id, label) ->
            if (id.isEmpty()) label else label + if (AppRcmdApi.isIdentityLogin(id)) " · 已登录" else " · 未登录"
        }.toTypedArray()
        val checked = identities.indexOfFirst { it.first == currentId }

        AlertDialog.Builder(this)
            .setTitle("推荐页使用哪个通道")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onIdentityChosen(identities[which].first, currentId)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 三级模式下才显示「Web 端」选项 */
    private fun showWebIdentityOption(): Boolean {
        return AccountManager.currentTier() == AccountManager.TIER_FULL
    }

    /** 当前推荐页是否被指定走 Web 接口 */
    private fun isWebChannelChosen(): Boolean {
        if (!showWebIdentityOption()) return false
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.APP_RCMD_USE_WEB, false)
    }

    /** 处理身份/通道选择（拆出来避免 onCheckedChange 回调里嵌套过深） */
    @SuppressLint("SetTextI18n")
    private fun onIdentityChosen(newId: String, currentId: String) {
        if (newId == currentId) return
        if (newId.isEmpty()) {
            // 选 Web 端：推荐页退回 web，其余接口仍受三级路由控制
            SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.APP_RCMD_USE_WEB, true)
            refreshStatus()
            MsgUtil.showMsg("推荐页已改用 Web 接口（其余接口仍走端口），刷新推荐页生效")
            return
        }
        // 选具体端口：清掉 Web 端标记，并把该端设为当前身份
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.APP_RCMD_USE_WEB, false)
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_IDENTITY, newId)
        refreshStatus()
        if (AppRcmdApi.isIdentityLogin(newId)) {
            MsgUtil.showMsg("已切换为" + AppRcmdApi.getIdentity().displayName + "（已登录），刷新推荐页生效")
        } else {
            MsgUtil.showMsg("已切换为" + AppRcmdApi.getIdentity().displayName + "（该端未登录），刷新推荐页生效")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val identity = AppRcmdApi.getIdentity()
        val logged = AppRcmdApi.isLogin()
        val mid = AppRcmdApi.getIdentityMid(identity.id)
        val webChannel = isWebChannelChosen()

        // 三端独立登录状态总览（可同时登录）。
        // 第一行固定是「当前身份 + 该端登录状态」，后面三行才是各端明细——
        // 旧版布局高度写死 35sp，四行文字被裁得只剩「HD 已登录」，看起来像是
        // 当前身份永远是 HD（本轮 bug 的根因之一）。
        val sb = StringBuilder()
        sb.append("推荐页通道：")
        sb.append(if (webChannel) "Web（三级模式下可选）" else identity.displayName)
        sb.append(if (!webChannel && logged) "（已登录）" else if (!webChannel) "（未登录）" else "")
        sb.append("\n当前身份：").append(identity.displayName)
        sb.append(if (logged) "（已登录）" else "（未登录）")
        val identities = listOf(
            AppRcmdApi.IDENTITY_HD to "HD",
            AppRcmdApi.IDENTITY_TV to "TV",
            AppRcmdApi.IDENTITY_PHONE to "手机"
        )
        for ((id, name) in identities) {
            sb.append('\n').append(name).append("：")
            if (AppRcmdApi.isIdentityLogin(id)) {
                sb.append("已登录")
                val idMid = AppRcmdApi.getIdentityMid(id)
                if (idMid > 0) sb.append("（uid ").append(idMid).append("）")
            } else {
                sb.append("未登录")
            }
            // 标出当前正在使用的一派，避免再看错行
            if (id == identity.id) sb.append("（当前）")
        }
        identityText!!.text = sb.toString()

        loginText!!.text = if (logged)
            "扫码登录（${identity.displayName}）\n已登录：uid $mid\n（三端可同时登录）"
        else
            "扫码登录（${identity.displayName}）\n每端只能自己扫码登录\n（三端可同时登录）"
        logoutCard!!.visibility = if (logged) View.VISIBLE else View.GONE

        //避免程序化置位触发 OnCheckedChangeListener，先摘掉监听再恢复
        switchView!!.setOnCheckedChangeListener(null)
        switchView!!.isChecked = AppRcmdApi.isEnabled()
        switchView!!.setOnCheckedChangeListener(switchListener)
    }

    private val switchListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.APP_RCMD_ENABLED, isChecked)
            if (isChecked) {
                MsgUtil.showMsg("已开启移动端推荐逻辑，刷新推荐页生效")
            } else {
                MsgUtil.showMsg("已恢复web端推荐，刷新推荐页生效")
            }
        }
}
