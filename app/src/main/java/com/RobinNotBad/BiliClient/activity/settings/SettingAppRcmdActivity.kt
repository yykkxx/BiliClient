package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.login.AppRcmdLoginActivity
import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 移动端推荐逻辑（实验性）设置页。
 *
 * 从实验室进入。页面提供：
 * - 移动端方式扫码登录（电视端扫码协议，与 web 登录完全独立）
 * - 「使用移动端推荐逻辑」开关（只影响推荐页）
 * - 退出移动端登录（只清独立键，不动 web 登录）
 */
class SettingAppRcmdActivity : BaseActivity() {

    private var switchView: SwitchMaterial? = null
    private var loginText: android.widget.TextView? = null
    private var logoutCard: MaterialCardView? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting_app_rcmd)
        setPageName("移动端推荐逻辑")
        setTopbarExit()

        switchView = findViewById(R.id.appRcmdSwitch)
        loginText = findViewById(R.id.appRcmdLoginText)
        logoutCard = findViewById(R.id.appRcmdLogout)

        //扫码登录（移动端方式）
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

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val logged = AppRcmdApi.isLogin()
        val mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.APP_RCMD_MID, 0L)
        if (logged) {
            loginText!!.text = "扫码登录（移动端方式）\n已登录：$mid"
            logoutCard!!.visibility = android.view.View.VISIBLE
        } else {
            loginText!!.text = "扫码登录（移动端方式）"
            logoutCard!!.visibility = android.view.View.GONE
        }
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
