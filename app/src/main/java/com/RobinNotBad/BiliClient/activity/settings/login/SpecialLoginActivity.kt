package com.RobinNotBad.BiliClient.activity.settings.login

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

import org.json.JSONException
import org.json.JSONObject

class SpecialLoginActivity : BaseActivity() {

    private var textInput: EditText? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_special)
        Logu.i("debug", "使用特殊登录方式")

        textInput = findViewById(R.id.loginInput)
        val confirm = findViewById<MaterialCardView>(R.id.confirm)
        val refuse = findViewById<MaterialCardView>(R.id.refuse)
        val copy = findViewById<MaterialCardView>(R.id.copy)
        val desc = findViewById<TextView>(R.id.desc)

        val intent = getIntent()

        if (intent.getBooleanExtra("login", true)) {
            refuse.setOnClickListener { v ->
                //「但是我拒绝」：无论从哪个入口进来都进主界面（#2，与扫码页保持一致）
                startActivity(Intent(this, RecommendActivity::class.java))
                finish()
            }

            confirm.setOnClickListener { view ->
                val loginInfo = textInput!!.getText().toString()
                try {
                    val jsonObject = JSONObject(loginInfo)
                    val cookies = jsonObject.getString("cookies")
                    SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, NetWorkUtil.getInfoFromCookie("DedeUserID", cookies).toLong())
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", cookies))
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies)
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, jsonObject.getString("refresh_token"))
                    runOnUiThread { MsgUtil.showMsg("登录成功！") }
                    SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.setup, true)

                    NetWorkUtil.refreshHeaders()

                    val intent1 = Intent()
                    intent1.setClass(this@SpecialLoginActivity, RecommendActivity::class.java)
                    startActivity(intent1)
                    finish()
                } catch (e: JSONException) {
                    runOnUiThread { MsgUtil.showMsg("请检查输入的内容，不要有多余空格或字符") }
                }
            }
        } else {
            desc.setText(R.string.special_login_export)

            val jsonObject = JSONObject()
            try {
                jsonObject.put("cookies", SharedPreferencesUtil.getString("cookies", ""))
                jsonObject.put("refresh_token", SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""))
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            textInput!!.setText(jsonObject.toString())
            textInput!!.clearFocus()

            refuse.visibility = View.GONE
            if (BiliTerminal.isDebugBuild()) {
                confirm.setOnClickListener { v ->
                    try {
                        val input = JSONObject(textInput!!.getText().toString())
                        val cookies = input.getString("cookies")
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies)
                        runOnUiThread { MsgUtil.showMsg("导入cookies成功") }

                        NetWorkUtil.refreshHeaders()
                    } catch (e: JSONException) {
                        runOnUiThread { MsgUtil.showMsg("请检查输入的内容，不要有多余空格或字符") }
                    }
                }
            } else confirm.visibility = View.GONE
            copy.setOnClickListener { view ->
                val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("label", textInput!!.getText())
                clipboardManager.setPrimaryClip(clipData)
                MsgUtil.showMsg("已复制")
            }
        }
    }

}
