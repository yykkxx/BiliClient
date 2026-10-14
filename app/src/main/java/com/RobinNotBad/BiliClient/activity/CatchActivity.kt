package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.annotation.Nullable
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.google.android.material.button.MaterialButton

class CatchActivity : BaseActivity() {
    private var openStack = false

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catch)

        val reason_view = findViewById<TextView>(R.id.catch_reason)
        val stack_view = findViewById<TextView>(R.id.stack)
        val btn_upload = findViewById<MaterialButton>(R.id.upload_btn)

        val intent = getIntent()
        val stack = intent.getStringExtra("stack")

        stack_view.text = stack

        findViewById<View>(R.id.exit_btn).setOnClickListener { System.exit(-1) }

        var reason_str: SpannableString? = null

        if (stack != null) {

            var allow_upload = false

            if (stack.contains("java.lang.NumberFormatException"))
                reason_str = SpannableString("可能的崩溃原因：\n数值转换出错")
            else if (stack.contains("java.lang.UnsatisfiedLinkError"))
                reason_str = SpannableString("可能的崩溃原因：\n外部库加载出错，可能设备太老或修改了安装包")
            else if (stack.contains("org.json.JSONException"))
                reason_str = SpannableString("可能的崩溃原因：\n数据解析错误")
            else if (stack.contains("java.lang.OutOfMemoryError"))
                reason_str = SpannableString("可能的崩溃原因：\n内存爆了，这在小内存设备上很正常")
            else
                allow_upload = true

            if (allow_upload) btn_upload.setOnClickListener {
                btn_upload.isEnabled = false
                if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, -1L) == -1L)
                    MsgUtil.toast("我们不对未登录时遇到的问题负责\n——除非它真的经常出现且非常影响使用")
                else {
                    CenterThreadPool.run(Runnable {
                        val res: ApiResult = AppInfoApi.uploadStack(stack, this)
                        runOnUiThread {
                            if (res.code >= 0)
                                btn_upload.text = "请带着你的报错ID：" + res.code + "\n和你崩溃前进行的操作\n去找开发者\n（提醒：开发者不保证会修好也不保证随时回复你）"
                            else btn_upload.text = res.message ?: ""

                            if (res.code == -1) btn_upload.isEnabled = true
                        }
                    })
                }
            }
            else btn_upload.text = "此类型报错不可上传\n非特殊情况请勿打扰开发者谢谢喵"

        } else finish()

        findViewById<View>(R.id.restart_btn).setOnClickListener {
            finish()
            stopService(Intent(this, DownloadService::class.java))
            startActivity(Intent(this, com.RobinNotBad.BiliClient.activity.video.RecommendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Process.killProcess(Process.myPid())
        }

        if (reason_str != null) {
            reason_str.setSpan(StyleSpan(Typeface.BOLD), 0, 8, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            reason_view.text = reason_str
        } else reason_view.text = "未知的崩溃原因"

        stack_view.setOnClickListener {
            openStack = !openStack
            if (openStack) stack_view.maxLines = 200
            else stack_view.maxLines = 5
        }

        StringUtil.setCopy(stack_view)
    }

    override fun eventBusEnabled(): Boolean {
        return false
    }
}
