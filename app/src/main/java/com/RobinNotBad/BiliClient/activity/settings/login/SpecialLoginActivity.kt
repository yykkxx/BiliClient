package com.RobinNotBad.BiliClient.activity.settings.login

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity
import com.RobinNotBad.BiliClient.util.AccountCredentials
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

/** 特殊登录兼容旧 Web 格式；查看/复制统一使用四端、多账号、多凭据的 v3 格式。 */
class SpecialLoginActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 页面包含完整的登录秘密，防截图/后台最近任务预览泄露。
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_login_special)
        val input = findViewById<EditText>(R.id.loginInput)
        val confirm = findViewById<MaterialCardView>(R.id.confirm)
        val refuse = findViewById<MaterialCardView>(R.id.refuse)
        val copy = findViewById<MaterialCardView>(R.id.copy)
        val desc = findViewById<TextView>(R.id.desc)
        val loginMode = intent.getBooleanExtra("login", true)
        if (loginMode) {
            copy.visibility = View.GONE
            refuse.setOnClickListener { openRecommend() }
            confirm.setOnClickListener { importCredentials(input.text.toString()) }
        } else {
            setPageName("登录信息（四端）")
            desc.setText(R.string.login_info_four_ports_desc)
            input.setText(AccountCredentials.exportAll())
            input.keyListener = null
            input.setTextIsSelectable(true)
            input.clearFocus()
            confirm.visibility = View.GONE
            refuse.visibility = View.GONE
            copy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard == null) {
                    MsgUtil.showMsg("系统剪贴板不可用")
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("biliclient-credentials", input.text))
                    MsgUtil.showMsg("已复制全部四端登录凭据，请勿分享给他人")
                }
            }
        }
    }

    private fun importCredentials(text: String) {
        try {
            val result = AccountCredentials.importAll(text.trim())
            if (result.values.sum() <= 0) {
                MsgUtil.showMsg("未找到有效凭据，请检查内容（支持旧 Web 格式和四端导出格式）")
                return
            }
            NetWorkUtil.refreshHeaders()
            SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.setup, true)
            MsgUtil.showMsg("登录凭据已导入")
            openRecommend()
        } catch (e: org.json.JSONException) { // quality-allow：兼容历史凭据 JSON 格式
            MsgUtil.showMsg("凭据格式错误，请检查复制内容")
        } catch (e: IllegalArgumentException) {
            MsgUtil.showMsg("凭据内容无效，请重新复制")
        }
    }

    private fun openRecommend() {
        startActivity(Intent(this, RecommendActivity::class.java))
        finish()
    }
}
