package com.RobinNotBad.BiliClient.model

import android.text.SpannableStringBuilder

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.api.ReplyApi.TOP_TIP
import com.RobinNotBad.BiliClient.util.EmoteUtil
import com.RobinNotBad.BiliClient.util.JsonUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale

class Reply : Serializable {
    @JvmField var rpid: Long = 0
    @JvmField var oid: Long = 0
    @JvmField var root: Long = 0
    @JvmField var parent: Long = 0
    @JvmField var forceDelete: Boolean = false
    @JvmField var ofBvid: String? = ""
    @JvmField var pubTime: String? = null
    @JvmField var sender: UserInfo? = null
    @JvmField var message: CharSequence? = null
    @JvmField var pictureList: ArrayList<String>? = ArrayList()
    @JvmField var likeCount: Int = 0
    @JvmField var upLiked: Boolean = false
    @JvmField var upReplied: Boolean = false
    @JvmField var liked: Boolean = false
    @JvmField var childCount: Int = 0
    @JvmField var isDynamic: Boolean = false
    @JvmField var childMsgList: ArrayList<Reply>? = ArrayList()
    @JvmField var isTop: Boolean = false

    constructor()

    /**
     * @param isRoot    是否是根评论
     * @param replyJson 评论json对象
     * @throws JSONException json解析异常
     */
    constructor(isRoot: Boolean, replyJson: JSONObject) {
        this.rpid = replyJson.getLong("rpid")
        this.oid = replyJson.getLong("oid")
        this.root = replyJson.getLong("root")
        this.parent = replyJson.getLong("parent")
        this.sender = UserInfo(replyJson.getJSONObject("member"))

        val content = replyJson.getJSONObject("content")

        val replyCtrl = replyJson.getJSONObject("reply_control")
        val ctime = replyJson.getLong("ctime") * 1000

        var time: String
        if (System.currentTimeMillis() - ctime < 3 * 24 * 60 * 60 * 1000 && replyCtrl.has("time_desc")) {
            time = replyCtrl.getString("time_desc")
        } else {
            time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(ctime)
        }

        if (replyCtrl.has("location")) {
            time += " | IP:" + replyCtrl.getString("location").substring(5)  //这字符串还是切割一下吧不然太长了，只留个地址，前缀去了
        }
        this.pubTime = time

        if (replyCtrl.has("is_up_top")) {
            if (replyCtrl.getBoolean("is_up_top")) {
                this.isTop = true
            }
        }

        val messageSpannable = SpannableStringBuilder((if (isTop) TOP_TIP else "")
                + StringUtil.htmlToString(content.getString("message")))

        if (isTop) StringUtil.setTopSpan(messageSpannable)

        this.likeCount = replyJson.getInt("like")
        this.liked = replyJson.getInt("action") == 1

        if (content.has("emote") && !content.isNull("emote")) {
            val emoteList = ArrayList<Emote>()
            val emoteJson = content.getJSONObject("emote")
            val emoteKeys = JsonUtil.getJsonKeys(emoteJson)

            for (emoteKey in emoteKeys) {
                val key = emoteJson.getJSONObject(emoteKey)
                emoteList.add(Emote(
                        emoteKey,
                        key.getString("url"),
                        key.getJSONObject("meta").getInt("size")
                ))
            }

            EmoteUtil.textReplaceEmote(messageSpannable.toString(), emoteList, 1.0f, BiliTerminal.context, messageSpannable)
        }

        StringUtil.setLink(messageSpannable)

        if (content.has("at_name_to_mid") && !content.isNull("at_name_to_mid")) {
            val jsonObject = content.getJSONObject("at_name_to_mid")
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val `val` = jsonObject.getLong(key)
                StringUtil.setSingleAt(messageSpannable, key, `val`)
            }
        }

        val upAction = replyJson.getJSONObject("up_action")
        this.upLiked = upAction.getBoolean("like")
        this.upReplied = upAction.getBoolean("reply")


        if (isRoot) {
            if (content.has("pictures") && !content.isNull("pictures")) {
                val pictureList = ArrayList<String>()
                val pictures = content.getJSONArray("pictures")
                for (j in 0 until pictures.length()) {
                    val picture = pictures.getJSONObject(j)
                    pictureList.add(picture.getString("img_src"))
                }
                this.pictureList = pictureList
            }

            this.childCount = replyJson.getInt("rcount")

            if (replyJson.has("replies") && !replyJson.isNull("replies")) {
                val childMsgList = ArrayList<Reply>()
                val childReplies = replyJson.getJSONArray("replies")
                for (j in 0 until childReplies.length()) {
                    val childReply = childReplies.getJSONObject(j)
                    childMsgList.add(Reply(false, childReply))
                }
                this.childMsgList = childMsgList
            }
        }

        this.message = messageSpannable
    }
}
