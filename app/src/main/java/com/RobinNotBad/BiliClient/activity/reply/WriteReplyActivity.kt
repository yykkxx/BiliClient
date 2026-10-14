package com.RobinNotBad.BiliClient.activity.reply

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.util.Pair
import android.widget.EditText

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.EmoteActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.EmoteApi
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

import org.greenrobot.eventbus.EventBus

class WriteReplyActivity : BaseActivity() {

    var editText: EditText? = null
    private val emoteLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null && data.hasExtra("text")) {
            editText!!.append(data.getStringExtra("text"))
        }
    }

    @JvmField
    var sent = false

    @JvmField
    var dontKyPlease = true

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_write_reply)

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) {
            MsgUtil.showMsg("还没有登录喵~")
            finish()
        }

        val intent = getIntent()
        val oid = intent.getLongExtra("oid", 0)
        val rpid = intent.getLongExtra("rpid", 0)
        val parent = intent.getLongExtra("parent", 0)
        val replyType = intent.getIntExtra("replyType", ReplyApi.REPLY_TYPE_VIDEO)
        val parentSender = intent.getStringExtra("parentSender")
        val pos = intent.getIntExtra("pos", -1)

        editText = findViewById(R.id.editText)
        val send = findViewById<MaterialCardView>(R.id.send)

        if (parentSender != null && parentSender.isNotEmpty()) {
            editText!!.setText("回复 @$parentSender :")
            editText!!.setSelection(editText!!.getText().length)
        }

        send.setOnClickListener { view ->
            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
                if (!sent) {
                    CenterThreadPool.run(Runnable {
                        val text = editText!!.getText().toString()
                        if (!text.isEmpty()) {
                            if (checkKy(text) && dontKyPlease) {
                                MsgUtil.showDialog("保护措施……", getString(R.string.reply_dont_ky), 15)
                                dontKyPlease = false
                                return@Runnable
                            }
                            try {
                                val result = ReplyApi.sendReply(oid, rpid, parent, text, replyType)
                                val resultCode = result.first
                                val resultReply = result.second

                                sent = true

                                if (resultCode == 0) {
                                    runOnUiThread { MsgUtil.showMsg("发送成功>w<") }
                                    resultReply!!.forceDelete = true
                                    resultReply.pubTime = "刚刚"
                                    EventBus.getDefault().post(ReplyEvent(1, resultReply, pos, oid))
                                    finish()
                                } else {
                                    val toast_msg = "评论发送失败：\n" + (if (msgMap.containsKey(resultCode)) msgMap[resultCode] else resultCode)
                                    runOnUiThread { MsgUtil.showMsg(toast_msg) }
                                    sent = false
                                }
                            } catch (e: Exception) {
                                runOnUiThread { MsgUtil.err(e) }
                            }
                        } else runOnUiThread { MsgUtil.showMsg("还没输入内容呢~") }
                    })
                } else MsgUtil.showMsg("正在发送中")
            } else
                MsgUtil.showDialog("无法发送", "上一次的Cookie刷新失败了，\n您可能需要重新登录以进行敏感操作", -1)
        }

        findViewById<View>(R.id.emote).setOnClickListener { view ->
            emoteLauncher.launch(Intent(this, EmoteActivity::class.java).putExtra("from", EmoteApi.BUSINESS_REPLY))
        }
    }

    /**
     * P用没有的保护措施
     *
     * @param str 评论文本
     */
    private fun checkKy(str: String): Boolean {
        if (str.contains("哔哩终端")) return true
        if (str.contains("终端")) {
            return str.contains("表") || str.contains("b站") || str.contains("B站") || str.contains("bili") || str.contains("哔")
        }
        return false
    }

    companion object {
        private val msgMap: MutableMap<Int, String> = HashMap()

        init {
            msgMap[-101] = "没有登录or登录信息有误？"
            msgMap[-102] = "账号被封禁！"
            msgMap[-509] = "请求过于频繁！"
            msgMap[12015] = "需要评论验证码...？"
            msgMap[12016] = "包含敏感内容！"
            msgMap[12025] = "字数过多啦QAQ"
            msgMap[12035] = "被拉黑了..."
            msgMap[12051] = "重复评论，请勿刷屏！"
        }
    }
}
