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
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.util.StringUtil

class CatchActivity : BaseActivity() {
    private var openStack = false

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catch)

        val reason_view = findViewById<TextView>(R.id.catch_reason)
        val stack_view = findViewById<TextView>(R.id.stack)

        val intent = getIntent()
        val stack = intent.getStringExtra("stack")

        stack_view.text = stack

        findViewById<View>(R.id.exit_btn).setOnClickListener { System.exit(-1) }

        var reason_str: SpannableString? = null

        if (stack != null) {
            // 仅保留本地的“可能原因”提示；不再提供上传堆栈到服务器的功能
            if (stack.contains("java.lang.NumberFormatException"))
                reason_str = SpannableString("可能的崩溃原因：\n数值转换出错")
            else if (stack.contains("IndexOutOfBoundsException"))
                reason_str = SpannableString("可能的崩溃原因：\n列表数据与界面不同步（越界）\n通常在快速滚动/切换时偶发")
            else if (stack.contains("java.lang.UnsatisfiedLinkError"))
                reason_str = SpannableString("可能的崩溃原因：\n外部库加载出错，可能设备太老或修改了安装包")
            else if (stack.contains("org.json.JSONException"))
                reason_str = SpannableString("可能的崩溃原因：\n数据解析错误")
            else if (stack.contains("java.lang.OutOfMemoryError"))
                reason_str = SpannableString("可能的崩溃原因：\n内存爆了，这在小内存设备上很正常")
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
