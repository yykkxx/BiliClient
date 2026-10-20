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

    /** 身份选择弹窗：HD / TV / 手机 */
    @SuppressLint("SetTextI18n")
    private fun showIdentityDialog() {
        val identities = arrayOf(
            AppRcmdApi.IDENTITY_HD to "HD版（android_hd）",
            AppRcmdApi.IDENTITY_TV to "TV版（云视听小电视）",
            AppRcmdApi.IDENTITY_PHONE to "手机版（与官方手机客户端一致）"
        )
        val currentId = AppRcmdApi.getIdentity().id
        val labels = identities.map { it.second }.toTypedArray()
        val checked = identities.indexOfFirst { it.first == currentId }

        AlertDialog.Builder(this)
            .setTitle("选择客户端身份")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val (newId, _) = identities[which]
                if (newId != currentId) {
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_IDENTITY, newId)
                    refreshStatus()
                    // v5.0.0 重构：三端登录态独立保留，切换身份只是换“当前用哪一派”，
                    // 已登录的端切回来还在，不要求重新登录
                    if (AppRcmdApi.isLogin()) {
                        MsgUtil.showMsg("已切换为${AppRcmdApi.getIdentity().displayName}（已登录），刷新推荐页生效")
                    } else {
                        MsgUtil.showMsg("已切换为${AppRcmdApi.getIdentity().displayName}（该端未登录），刷新推荐页生效")
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val identity = AppRcmdApi.getIdentity()
        val logged = AppRcmdApi.isLogin()
        val mid = AppRcmdApi.getIdentityMid(identity.id)

        // 三端独立登录状态总览（可同时登录）
        fun stateLine(name: String, id: String): String {
            return if (AppRcmdApi.isIdentityLogin(id)) "$name 已登录" else "$name 未登录"
        }
        identityText!!.text = "当前身份：${identity.displayName}\n" +
                stateLine("HD", AppRcmdApi.IDENTITY_HD) + "\n" +
                stateLine("TV", AppRcmdApi.IDENTITY_TV) + "\n" +
                stateLine("手机", AppRcmdApi.IDENTITY_PHONE)

        loginText!!.text = if (logged)
            "扫码登录（${identity.displayName}）\n已登录：$mid\n（三端可同时登录）"
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
