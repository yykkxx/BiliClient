package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.StringUtil
import com.google.android.material.card.MaterialCardView
import org.json.JSONException
import org.json.JSONObject

class ShowTextActivity : BaseActivity() {

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_text)

        val intent = getIntent()
        var content: String? = intent.getStringExtra("content")

        val pagename = findViewById<TextView>(R.id.pageName)
        pagename.text = intent.getStringExtra("title")


        val textView = findViewById<TextView>(R.id.textView)

        if (content != null) {
            content = content.replace("[extra_insert]", "<extra_insert>")
            val extra_start = content.indexOf("<extra_insert>")
            if (extra_start != -1) {
                try {
                    val jsonObject = JSONObject(content.substring(extra_start + 14))
                    if (jsonObject.getString("type") == "video") {
                        val videoCard = View.inflate(this, R.layout.cell_message_reply, findViewById(R.id.linearLayout))
                        val cardView = videoCard.findViewById<MaterialCardView>(R.id.cardView)
                        cardView.setOnClickListener { BiliTerminal.jumpToVideo(this, jsonObject.optString("content")) }
                        val title_video = videoCard.findViewById<TextView>(R.id.content)
                        title_video.text = jsonObject.optString("title")
                        content = content.substring(0, extra_start)
                    } else {
                        content = content + "\n文本中存在无法识别的附加信息，请更新版本查看"
                    }
                } catch (e: JSONException) {
                    content = content + "\n文本中存在无法识别的附加信息，请更新版本查看"
                }
            }
        }
        if (intent.data != null) {
            content = intent.data.toString()
        }

        textView.text = content
        StringUtil.setCopy(textView)


    }
}
