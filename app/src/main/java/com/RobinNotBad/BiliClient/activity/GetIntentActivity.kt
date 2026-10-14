package com.RobinNotBad.BiliClient.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.net.Uri
import androidx.annotation.Nullable
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.util.MsgUtil

class GetIntentActivity : Activity() {
    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = getIntent()
        val type = intent.getStringExtra("type")

        if (type != null) when (intent.getStringExtra("type")) {
            "video_av" -> BiliTerminal.jumpToVideo(this, intent.getLongExtra("content", 0))
            "video_bv" -> BiliTerminal.jumpToVideo(this, intent.getStringExtra("content"))
            "article" -> BiliTerminal.jumpToArticle(this, intent.getLongExtra("content", 0))
            "user" -> BiliTerminal.jumpToUser(this, intent.getLongExtra("content", 0))
            else -> MsgUtil.showMsgLong("不支持打开：" + type)
        }

        val uri: Uri? = intent.data
        if (uri != null) {
            val host = uri.host
            Log.e("debug-host", host ?: "")

            when (host) {
                "video" -> BiliTerminal.jumpToVideo(this, java.lang.Long.parseLong(uri.lastPathSegment))
                "article" -> BiliTerminal.jumpToArticle(this, java.lang.Long.parseLong(uri.lastPathSegment))
                else -> MsgUtil.showMsgLong("不支持打开：" + host)
            }
        }

        finish()
    }
}
