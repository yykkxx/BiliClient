package com.RobinNotBad.BiliClient.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity
import com.RobinNotBad.BiliClient.util.Logu

/**
 * 注册拨号盘暗码：*#*#159951#*#*
 *
 * 在系统拨号盘输入该暗码即可直接进入注册/登录入口。
 * 社区里习惯把这个入口叫做“注册暗码”，它实际打开的是账号入口页，
 * 未登录用户可以在那里扫码注册或登录。
 *
 * 暗码由 Android 系统在拨号盘解析后以 ACTION_SECRET_CODE 广播发出，
 * 因此必须声明 exported，且 data 的 scheme 必须是 android_secret_code。
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECRET_CODE) return
        if (intent.data?.host != SECRET_CODE) return

        Logu.i("debug", "拨号盘暗码 $SECRET_CODE 触发，进入账号入口")

        // 广播接收器没有 Activity 栈，必须带上 NEW_TASK
        val entry = Intent(context, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FROM_SECRET_CODE, true)
        }
        try {
            context.startActivity(entry)
        } catch (e: Exception) {
            Logu.e("debug", "暗码跳转失败：${e.message}")
        }
    }

    companion object {
        /** 拨号盘暗码本体，对应 *#*#159951#*#* */
        const val SECRET_CODE = "159951"

        const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"

        /** 标记本次进入账号页是由暗码触发，便于页面给出提示 */
        const val EXTRA_FROM_SECRET_CODE = "from_secret_code"
    }
}
