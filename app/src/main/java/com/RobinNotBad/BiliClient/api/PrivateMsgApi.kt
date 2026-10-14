package com.RobinNotBad.BiliClient.api

import android.content.Context
import android.text.SpannableStringBuilder
import android.util.Log

import com.RobinNotBad.BiliClient.model.PrivateMessage
import com.RobinNotBad.BiliClient.model.PrivateMsgSession
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.EmoteUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ExecutionException

import okhttp3.Response

object PrivateMsgApi {

    const val MSG_TYPE_TEXT = 1
    const val MSG_TYPE_PIC = 2
    const val MSG_TYPE_RETRACT = 5

    // 返回的是倒序的消息列表，使用时记得列表倒置
    //seqno传0时只看size，不进行seqno的筛选
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getPrivateMsg(talkerId: Long, size: Int, beginSeqno: Long, endSeqno: Long): JSONObject {
        val url =
            "https://api.vc.bilibili.com/svr_sync/v1/svr_sync/fetch_session_msgs?session_type=1&talker_id=" + talkerId +
                    "&size=" + size +
                    "&begin_seqno=" + beginSeqno +
                    "&end_seqno=" + endSeqno
        val allMsgJson = NetWorkUtil.getJson(url)
        if (allMsgJson.has("data") && !allMsgJson.isNull("data"))
            return allMsgJson.getJSONObject("data")
        return JSONObject()
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getPrivateMsgList(allMsgJson: JSONObject): ArrayList<PrivateMessage> {
        val list = ArrayList<PrivateMessage>()
        if (!allMsgJson.isNull("messages")) {
            val messages = allMsgJson.getJSONArray("messages")
            val myInfo = UserInfoApi.getCurrentUserInfo()
            var targetInfo: UserInfo? = UserInfo()

            // list.add(new PrivateMessage(114514,1,new JSONObject("{\"content\":"
            // 。\"}"),11111111,"aaaaa",111));

            var isReqTargetInfo = false
            for (i in 0 until messages.length()) {
                val msgObject = PrivateMessage()
                val msgJson = messages.getJSONObject(i)
                msgObject.uid = msgJson.getLong("sender_uid")
                msgObject.type = msgJson.getInt("msg_type")
                if (msgObject.uid == myInfo.mid) {
                    msgObject.name = myInfo.name
                } else {
                    if (!isReqTargetInfo) {
                        targetInfo = UserInfoApi.getUserInfo(msgObject.uid)
                        isReqTargetInfo = true
                    }
                    if (targetInfo != null) msgObject.name = targetInfo.name
                }
                msgObject.content = JSONObject("{\"content\":\" .\"}") // 防止内容不为json时解析错误
                if (msgJson.getString("content").endsWith("}") &&
                        msgJson.getString("content").startsWith("{")) {
                    try {
                        msgObject.content =
                                JSONObject(msgJson.getString("content") /*.replace("\\","")*/)
                    } catch (ignore: Exception) {}
                    try{
                    msgObject.content_array =
                            JSONArray(msgJson.getString("content") /*.replace("\\","")*/)
                    }catch (ignore: Exception){}
                }
                msgObject.timestamp = msgJson.getLong("timestamp")
                msgObject.msg_source = msgJson.getInt("msg_source")
                msgObject.msgId = msgJson.getLong("msg_key")
                msgObject.msgSeqno = msgJson.getLong("msg_seqno")
                list.add(msgObject)

            }
            Log.e("", "返回msgList")
            for (i in list) {
                Log.e("msg",
                        i.name + "." + i.uid + "." + i.msgId + "." + i.timestamp + "." + i.content + "." + i.type)
            }
        }
        return list
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun getEmoteJsonArray(allMsgJson: JSONObject): JSONArray {
        return if (allMsgJson.has("e_infos") && !allMsgJson.isNull("e_infos")) {
            allMsgJson.getJSONArray("e_infos")
        } else {
            JSONArray()
        }
    }

    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getUsersInfo(uidList: ArrayList<Long>): HashMap<Long, UserInfo> {
        val userString = StringBuilder()
        for (uid in uidList) {
            userString.append(uid).append(",")
        }
        val url = "https://api.vc.bilibili.com/account/v1/user/cards?uids=" +
                userString.substring(0, userString.length - 1)
        val userMap = HashMap<Long, UserInfo>()
        val root = NetWorkUtil.getJson(url)
        if (root.has("data") && !root.isNull("data")) {
            val data = root.getJSONArray("data")
            for (i in 0 until data.length()) {
                val userJson = data.getJSONObject(i)
                val user = UserInfo()
                user.mid = userJson.getLong("mid")
                user.name = userJson.getString("name")
                user.avatar = userJson.getString("face")
                Log.e("", user.mid.toString() + user.name + user.avatar)
                userMap[user.mid] = user
            }
        }
        return userMap
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getSessionsList(size: Int): ArrayList<PrivateMsgSession> {
        val url =
            "https://api.vc.bilibili.com/session_svr/v1/session_svr/get_sessions?session_type=1&size=" + size
        val root = NetWorkUtil.getJson(url)
        val sessionList = ArrayList<PrivateMsgSession>()
        if (root.has("data") && !root.isNull("data")) {
            val sessions = root.getJSONObject("data").getJSONArray("session_list")
            for (i in 0 until sessions.length()) {
                val session = PrivateMsgSession()
                val sessionJson = sessions.getJSONObject(i)
                session.talkerUid = sessionJson.getLong("talker_id")

                if (!sessionJson.isNull("last_msg")) {
                    session.contentType = sessionJson.getJSONObject("last_msg").getInt("msg_type")
                    val content = sessionJson.getJSONObject("last_msg").getString("content")

                    if (content.endsWith("}") && content.startsWith("{"))
                        session.content = JSONObject(content)
                }

                session.unread = sessionJson.getInt("unread_count")

                if (!sessionJson.has("account_info") && sessionJson.isNull("account_info"))
                    sessionList.add(session)

            }
        }
        return sessionList
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getNewSessionsList(beginTs: Long, size: Int, build: Int, mobiApp: String?): ArrayList<PrivateMsgSession> {
        val url = StringBuilder("https://api.vc.bilibili.com/session_svr/v1/session_svr/new_sessions?")
        if (beginTs > 0) {
            url.append("begin_ts=").append(beginTs).append("&")
        }
        if (size > 0) {
            url.append("size=").append(size).append("&")
        }
        if (build > 0) {
            url.append("build=").append(build).append("&")
        }
        if (mobiApp != null && !mobiApp.isEmpty()) {
            url.append("mobi_app=").append(mobiApp).append("&")
        }
        if (url[url.length - 1] == '&') {
            url.setLength(url.length - 1)
        }
        val root = NetWorkUtil.getJson(url.toString())
        val sessionList = ArrayList<PrivateMsgSession>()
        if (root.has("data") && !root.isNull("data")) {
            val data = root.getJSONObject("data")
            if (data.has("session_list") && !data.isNull("session_list")) {
                val sessions = data.getJSONArray("session_list")
                for (i in 0 until sessions.length()) {
                    val session = PrivateMsgSession()
                    val sessionJson = sessions.getJSONObject(i)
                    session.talkerUid = sessionJson.getLong("talker_id")

                    if (!sessionJson.isNull("last_msg")) {
                        session.contentType = sessionJson.getJSONObject("last_msg").getInt("msg_type")
                        val content = sessionJson.getJSONObject("last_msg").getString("content")

                        if (content.endsWith("}") && content.startsWith("{"))
                            session.content = JSONObject(content)
                    }

                    session.unread = sessionJson.getInt("unread_count")
                    sessionList.add(session)
                }
            }
        }
        return sessionList
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun sendMsg(senderUid: Long, receiverUid: Long, msgType: Int, timestamp: Long, content: String): JSONObject {
        val url = "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg?"
        val per =
            "msg[dev_id]=" + getDevId() +
                    "&msg[msg_type]=" + msgType +
                    "&msg[content]=" + content +
                    "&msg[receiver_type]=1&csrf=" + SharedPreferencesUtil.getString("csrf", "") +
                    "&msg[sender_uid]=" + senderUid +
                    "&msg[receiver_id]=" + receiverUid +
                    "&msg[timestamp]=" + timestamp

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body()!!.string())

        Log.e("debug-发送私信", result.toString())
        Log.e("debug-发送私信", NetWorkUtil.webHeaders.toString())
        return result
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun updateAck(talkerId: Long, sessionType: Int, ackSeqno: Long): JSONObject {
        val url = "https://api.vc.bilibili.com/session_svr/v1/session_svr/update_ack"
        val csrf = SharedPreferencesUtil.getString("csrf", "")
        val per = StringBuilder()
        per.append("talker_id=").append(talkerId)
        per.append("&session_type=").append(sessionType)
        if (ackSeqno > 0) {
            per.append("&ack_seqno=").append(ackSeqno)
        }
        per.append("&csrf_token=").append(csrf)
        per.append("&csrf=").append(csrf)
        per.append("&build=0")
        per.append("&mobi_app=web")

        val response = NetWorkUtil.post(url, per.toString(), NetWorkUtil.webHeaders)
        if (response.body() != null) {
            return JSONObject(response.body()!!.string())
        }
        return JSONObject()
    }

    private fun getDevId(): String {
        val b = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
        val s = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".toCharArray()
        for (i in s.indices) {
            if ('-' == s[i] || '4' == s[i]) {
                continue
            }
            val randomInt = (16 * Math.random()).toInt()
            if ('x' == s[i]) {
                s[i] = b[randomInt]
            } else {
                s[i] = b[3 and randomInt or 8]
            }
        }
        return String(s)
    }

    // 由于接口特殊性定制的textReplaceEmote
    @Throws(JSONException::class, ExecutionException::class, InterruptedException::class)
    @JvmStatic
    fun textReplaceEmote(text: String, emote: JSONArray?, scale: Float, context: Context): SpannableStringBuilder {
        val result = SpannableStringBuilder(text)
        if (emote != null && emote.length() > 0) {
            for (i in 0 until emote.length()) {    //遍历每一个表情包
                val key = emote.getJSONObject(i)

                val name = key.getString("text")
                val emoteUrl = key.getString("url")
                val size = key.getInt("size")  //B站十分贴心的帮你把表情包大小都写好了，快说谢谢蜀黍

                EmoteUtil.replaceSingle(result, name, emoteUrl, size, scale, context)
            }
        }
        return result
    }
}
