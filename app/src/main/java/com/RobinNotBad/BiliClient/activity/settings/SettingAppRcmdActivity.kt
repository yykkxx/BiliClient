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
 * 从实验室进入。页面提供：
 * - 客户端身份选择（HD版 / TV版 / 手机版）
 * - 移动端登录：手机版支持「web账号一键转换」（无需扫码），HD/TV 走扫码；手机版转换失败也可扫码保底
 * - 「使用移动端推荐逻辑」开关（只影响推荐页）
 * - 退出移动端登录（只清独立键，不动 web 登录）
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

        //扫码登录（移动端方式）
        val loginCard = findViewById<MaterialCardView>(R.id.appRcmdLogin)
        loginCard.setOnClickListener {
            val identity = AppRcmdApi.getIdentity()
            if (identity.supportCookieConvert) {
                //手机版：优先 web 账号一键转换，失败再扫码保底
                showPhoneLoginDialog()
            } else {
                startActivity(Intent(this, AppRcmdLoginActivity::class.java))
            }
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
                    val wasLogin = AppRcmdApi.isLogin()
                    refreshStatus()
                    if (wasLogin && !AppRcmdApi.isLogin()) {
                        //token 换了身份：旧 token 是其他身份签发的，需要重新登录
                        MsgUtil.showMsgLong("已切换为${AppRcmdApi.getIdentity().displayName}；当前登录令牌不适用，请重新登录")
                    } else {
                        MsgUtil.showMsg("已切换为${AppRcmdApi.getIdentity().displayName}，刷新推荐页生效")
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手机版登录：web 账号一键转换 / 扫码登录 双路径 */
    @SuppressLint("SetTextI18n")
    private fun showPhoneLoginDialog() {
        AlertDialog.Builder(this)
            .setTitle("手机版登录")
            .setMessage("优先使用软件已登录的web账号直接转换（无需扫码）；\n如转换失败（未登录/风控），再扫码登录。")
            .setPositiveButton("web账号转换") { _, _ -> convertWebLogin() }
            .setNegativeButton("扫码登录") { _, _ ->
                startActivity(Intent(this, AppRcmdLoginActivity::class.java))
            }
            .show()
    }

    /** web 账号静默转换（后台执行） */
    @SuppressLint("SetTextI18n")
    private fun convertWebLogin() {
        MsgUtil.showMsg("正在转换…")
        CenterThreadPool.run(Runnable {
            try {
                val mid = AppRcmdApi.convertWebLogin()
                runOnUiThread {
                    refreshStatus()
                    MsgUtil.showMsg("转换成功，已登录手机端（uid：$mid）")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    MsgUtil.err(e)
                    MsgUtil.showMsgLong("转换失败，可改用扫码登录")
                }
            }
        })
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val identity = AppRcmdApi.getIdentity()
        val logged = AppRcmdApi.isLogin()
        val mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.APP_RCMD_MID, 0L)

        identityText!!.text = "客户端身份：${identity.displayName}"

        if (identity.supportCookieConvert) {
            loginText!!.text = if (logged) "登录（手机版）\n已登录：$mid" else "登录（手机版）\nweb账号一键转换 / 扫码登录"
        } else {
            loginText!!.text = if (logged) "扫码登录（移动端方式）\n已登录：$mid" else "扫码登录（移动端方式）"
        }
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
