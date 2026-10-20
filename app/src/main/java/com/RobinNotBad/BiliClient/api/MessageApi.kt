package com.RobinNotBad.BiliClient.api

import android.text.SpannableString
import android.util.Pair

import com.RobinNotBad.BiliClient.model.MessageCard
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList

import okhttp3.Response

object MessageApi {
    @Throws(IOException::class, JSONException::class)
    private fun getUnreadData(): JSONObject {
        val url = "https://api.bilibili.com/x/msgfeed/unread"
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            return all.getJSONObject("data")
        }
        return JSONObject()
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getUnread(): JSONObject {
        val data = getUnreadData()
        val jsonObject = JSONObject()
        jsonObject.put("at", data.optInt("at", 0))
        jsonObject.put("like", data.optInt("like", 0))
        jsonObject.put("reply", data.optInt("reply", 0))
        jsonObject.put("system", data.optInt("sys_msg", 0))
        return jsonObject
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun checkMessageUnread(): Int {
        val data = getUnreadData()
        var total = 0
        total += data.optInt("at", 0)
        total += data.optInt("reply", 0)
        return total
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun checkPrivateMsgUnread(): Int {
        val url = "https://api.vc.bilibili.com/session_svr/v1/session_svr/single_unread"
        val all = NetWorkUtil.getJson(url)
        var total = 0
        if (all.has("data") && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            total += data.optInt("unfollow_unread", 0)
            total += data.optInt("follow_unread", 0)
            total += data.optInt("unfollow_push_msg", 0)
            total += data.optInt("dustbin_push_msg", 0)
            total += data.optInt("dustbin_unread", 0)
            total += data.optInt("biz_msg_unfollow_unread", 0)
            total += data.optInt("biz_msg_follow_unread", 0)
            total += data.optInt("custom_unread", 0)
        }
        return total
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun checkGroupMsgUnread(): Int {
        val url = "https://api.vc.bilibili.com/session_svr/v1/session_svr/my_group_unread"
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            return data.optInt("unread_count", 0)
        }
        return 0
    }

    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getLikeMsg(id: Long, time: Long): Pair<MessageCard.Cursor?, MutableList<MessageCard>> {
        var url = "https://api.bilibili.com/x/msgfeed/like?platform=web&build=0&mobi_app=web"
        if (id > 0 && time > 0)
            url += String.format("&id=%s&reply_time=%s", id, time)
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            // 所有消息
            val totalArray: ArrayList<MessageCard> = ArrayList()
            for (i in 0 until all.getJSONObject("data").getJSONObject("total").getJSONArray("items").length()) {
                val `object` = all.getJSONObject("data").getJSONObject("total").getJSONArray("items")
                        .get(i) as JSONObject
                val likeInfo = MessageCard()

                val userList: MutableList<UserInfo?> = ArrayList()
                for (j in 0 until `object`.getJSONArray("users").length()) {
                    val userArrayInfo = `object`.getJSONArray("users").getJSONObject(j)
                    userList.add(UserInfo(userArrayInfo.getLong("mid"), userArrayInfo.getString("nickname"),
                            userArrayInfo.getString("avatar"), "", userArrayInfo.getInt("fans"), 0, 0,
                            userArrayInfo.getBoolean("follow"), "", 0, "", 0))
                }

                likeInfo.id = `object`.getLong("id")
                likeInfo.user = userList
                likeInfo.timeStamp = `object`.getLong("like_time")

                val item = `object`.getJSONObject("item")
                likeInfo.businessId = item.getInt("business_id")
                likeInfo.subjectId = item.getLong("item_id")
                likeInfo.sourceId = item.optLong("source_id", -1)
                likeInfo.rootId = item.optLong("root_id", -1)
                likeInfo.itemType = item.getString("type")

                when (likeInfo.itemType) {
                    "video" -> {
                        likeInfo.content = "等总共 " + `object`.getLong("counts") + " 人点赞了你的视频"
                        val videoCard = VideoCard()
                        videoCard.aid = 0
                        videoCard.bvid = item.getString("uri").replace("https://www.bilibili.com/video/BV", "")
                        videoCard.upName = ""
                        videoCard.title = item.getString("title")
                        videoCard.cover = item.getString("image")
                        videoCard.view = ""
                        likeInfo.videoCard = videoCard
                    }
                    "reply" -> {
                        likeInfo.content = "等总共 " + `object`.getLong("counts") + " 人点赞了你的评论"
                        val replyInfo = Reply()
                        replyInfo.rpid = item.getLong("item_id")
                        replyInfo.sender = null
                        replyInfo.message = SpannableString(item.getString("title"))
                        replyInfo.pictureList = ArrayList()
                        replyInfo.likeCount = 0
                        replyInfo.upLiked = false
                        replyInfo.upReplied = false
                        replyInfo.liked = false
                        replyInfo.childCount = 0
                        replyInfo.ofBvid = item.getString("uri").replace("https://www.bilibili.com/video/", "")
                        replyInfo.childMsgList = ArrayList()
                        likeInfo.replyInfo = replyInfo
                    }
                    "dynamic", "album" -> {
                        likeInfo.content = "等总共 " + `object`.getLong("counts") + " 人点赞了你的动态"
                        val replyInfo_dynamic = Reply()
                        replyInfo_dynamic.rpid = item.getLong("item_id")
                        replyInfo_dynamic.sender = null
                        replyInfo_dynamic.message = SpannableString(item.getString("title"))
                        replyInfo_dynamic.pictureList = ArrayList()
                        replyInfo_dynamic.likeCount = 0
                        replyInfo_dynamic.upLiked = false
                        replyInfo_dynamic.upReplied = false
                        replyInfo_dynamic.liked = false
                        replyInfo_dynamic.isDynamic = true
                        replyInfo_dynamic.childCount = 0
                        replyInfo_dynamic.childMsgList = ArrayList()
                        likeInfo.dynamicInfo = replyInfo_dynamic
                    }
                    "article" -> {
                        likeInfo.content = "等总共 " + `object`.getLong("counts") + " 人点赞了你的专栏"
                        // 实在是抽象 但是我没时间改那么多
                        val replyChildInfo = Reply()
                        replyChildInfo.rpid = item.getLong("target_id")
                        replyChildInfo.message = SpannableString(item.getString("title"))
                        replyChildInfo.childCount = 0
                        likeInfo.replyInfo = replyChildInfo
                    }
                    else -> likeInfo.content = "无法识别这个类别：" + likeInfo.itemType
                }

                likeInfo.getType = MessageCard.GET_TYPE_LIKE
                totalArray.add(likeInfo)
            }

            val cursor = all.getJSONObject("data").optJSONObject("cursor")
            return Pair(if (cursor == null) null
                    else MessageCard.Cursor(cursor.optBoolean("is_end", true), cursor.optLong("id", -1),
                            cursor.optLong("time", -1)),
                    totalArray)
        } else {
            return Pair(null, ArrayList())
        }
    }

    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getReplyMsg(id: Long, time: Long): Pair<MessageCard.Cursor?, MutableList<MessageCard>> {
        var url = "https://api.bilibili.com/x/msgfeed/reply?platform=web&build=0&mobi_app=web"
        if (id > 0 && time > 0)
            url += String.format("&id=%s&reply_time=%s", id, time)
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            val totalArray: ArrayList<MessageCard> = ArrayList()
            for (i in 0 until all.getJSONObject("data").getJSONArray("items").length()) {
                val `object` = all.getJSONObject("data").getJSONArray("items").get(i) as JSONObject
                val replyInfo = MessageCard()

                val userList: MutableList<UserInfo?> = ArrayList()
                userList.add(UserInfo(`object`.getJSONObject("user").getLong("mid"),
                        `object`.getJSONObject("user").getString("nickname"),
                        `object`.getJSONObject("user").getString("avatar"), "",
                        `object`.getJSONObject("user").getInt("fans"), 0, 0,
                        `object`.getJSONObject("user").getBoolean("follow"), "", 0, "", 0))
                replyInfo.user = userList

                replyInfo.id = `object`.getLong("id")
                replyInfo.timeStamp = `object`.getLong("reply_time")

                val item = `object`.getJSONObject("item")
                replyInfo.businessId = item.getInt("business_id")
                replyInfo.subjectId = item.getLong("subject_id")
                replyInfo.sourceId = item.optLong("source_id", -1)
                replyInfo.rootId = item.optLong("root_id", -1)
                replyInfo.itemType = item.getString("type")
                replyInfo.getType = MessageCard.GET_TYPE_REPLY
                replyInfo.targetId = item.optLong("target_id", -1)

                replyInfo.content = item.getString("source_content")

                when (replyInfo.itemType) {
                    "video" -> {
                        val videoCard = VideoCard()
                        videoCard.aid = 0
                        videoCard.bvid = item.getString("uri").replace("https://www.bilibili.com/video/BV", "")
                        videoCard.upName = ""
                        videoCard.title = item.getString("title")
                        videoCard.cover = item.getString("image")
                        videoCard.view = ""
                        replyInfo.videoCard = videoCard
                    }
                    "reply" -> {
                        val replyChildInfo = Reply()
                        replyChildInfo.rpid = item.getLong("target_id")
                        replyChildInfo.sender = null
                        replyChildInfo.message = SpannableString("[评论] " + item.getString("title"))
                        replyChildInfo.pictureList = ArrayList()
                        replyChildInfo.likeCount = 0
                        replyChildInfo.upLiked = false
                        replyChildInfo.upReplied = false
                        replyChildInfo.liked = false
                        replyChildInfo.childCount = 0
                        replyChildInfo.ofBvid = item.getString("uri").replace("https://www.bilibili.com/video/", "")
                        replyChildInfo.childMsgList = ArrayList()
                        replyInfo.replyInfo = replyChildInfo
                    }
                    "dynamic", "album" -> {
                        val replyChildInfo_dynamic = Reply()
                        replyChildInfo_dynamic.rpid = item.getLong("target_id")
                        replyChildInfo_dynamic.sender = null
                        replyChildInfo_dynamic.message = SpannableString("[动态] " + item.getString("title"))
                        replyChildInfo_dynamic.pictureList = ArrayList()
                        replyChildInfo_dynamic.likeCount = 0
                        replyChildInfo_dynamic.upLiked = false
                        replyChildInfo_dynamic.upReplied = false
                        replyChildInfo_dynamic.liked = false
                        replyChildInfo_dynamic.childCount = 0
                        replyChildInfo_dynamic.isDynamic = true
                        replyChildInfo_dynamic.childMsgList = ArrayList()
                        replyInfo.dynamicInfo = replyChildInfo_dynamic
                    }
                    "article" -> {
                        val replyChildInfo_article = Reply()
                        replyChildInfo_article.rpid = item.getLong("target_id")
                        replyChildInfo_article.message = SpannableString("[专栏] " + item.getString("title"))
                        replyChildInfo_article.childCount = 0
                        replyInfo.replyInfo = replyChildInfo_article
                    }
                    else -> replyInfo.content = "无法识别这个类别：" + replyInfo.itemType
                }

                totalArray.add(replyInfo)
            }
            val cursor = all.getJSONObject("data").optJSONObject("cursor")
            return Pair(if (cursor == null) null
                    else MessageCard.Cursor(cursor.optBoolean("is_end", true), cursor.optLong("id", -1),
                            cursor.optLong("time", -1)),
                    totalArray)
        } else {
            return Pair(null, ArrayList())
        }
    }

    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getAtMsg(id: Long, time: Long): Pair<MessageCard.Cursor?, MutableList<MessageCard>> {
        var url = "https://api.bilibili.com/x/msgfeed/at?platform=web&build=0&mobi_app=web"
        if (id > 0 && time > 0)
            url += String.format("&id=%s&at_time=%s", id, time)
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            val totalArray: ArrayList<MessageCard> = ArrayList()
            for (i in 0 until all.getJSONObject("data").getJSONArray("items").length()) {
                val `object` = all.getJSONObject("data").getJSONArray("items").get(i) as JSONObject
                val replyInfo = MessageCard()

                val userList: MutableList<UserInfo?> = ArrayList()
                userList.add(UserInfo(`object`.getJSONObject("user").getLong("mid"),
                        `object`.getJSONObject("user").getString("nickname"),
                        `object`.getJSONObject("user").getString("avatar"), "",
                        `object`.getJSONObject("user").getInt("fans"), 0, 0,
                        `object`.getJSONObject("user").getBoolean("follow"), "", 0, "", 0))
                replyInfo.user = userList

                replyInfo.id = `object`.getLong("id")
                replyInfo.timeStamp = `object`.getLong("at_time")
                replyInfo.content = "提到了我"

                if (`object`.getJSONObject("item").getString("type") == "video") {
                    val videoCard = VideoCard()
                    videoCard.aid = 0
                    videoCard.bvid = `object`.getJSONObject("item").getString("uri")
                            .replace("https://www.bilibili.com/video/BV", "")
                    videoCard.upName = ""
                    videoCard.title = `object`.getJSONObject("item").getString("title")
                    videoCard.cover = `object`.getJSONObject("item").getString("image")
                    videoCard.view = ""
                    replyInfo.videoCard = videoCard
                } else if (`object`.getJSONObject("item").getString("type") == "reply") {
                    val replyChildInfo = Reply()
                    replyChildInfo.rpid = `object`.getJSONObject("item").getLong("target_id")
                    replyChildInfo.sender = null
                    replyChildInfo.message = SpannableString(
                            "[评论] " + `object`.getJSONObject("item").getString("title"))
                    replyChildInfo.pictureList = ArrayList()
                    replyChildInfo.likeCount = 0
                    replyChildInfo.upLiked = false
                    replyChildInfo.upReplied = false
                    replyChildInfo.liked = false
                    replyChildInfo.childCount = 0
                    replyChildInfo.ofBvid = `object`.getJSONObject("item").getString("uri")
                            .replace("https://www.bilibili.com/video/", "")
                    replyChildInfo.childMsgList = ArrayList()
                    replyInfo.replyInfo = replyChildInfo
                } else if (`object`.getJSONObject("item").getString("type") == "dynamic") {
                    val replyChildInfo = Reply()
                    replyChildInfo.rpid = `object`.getJSONObject("item").getLong("target_id")
                    replyChildInfo.sender = null
                    replyChildInfo.message = SpannableString(
                            "[动态] " + `object`.getJSONObject("item").getString("title"))
                    replyChildInfo.pictureList = ArrayList()
                    replyChildInfo.likeCount = 0
                    replyChildInfo.upLiked = false
                    replyChildInfo.upReplied = false
                    replyChildInfo.liked = false
                    replyChildInfo.isDynamic = true
                    replyChildInfo.childCount = 0
                    replyChildInfo.childMsgList = ArrayList()
                    replyInfo.dynamicInfo = replyChildInfo
                } else if (`object`.getJSONObject("item").getString("type") == "article") {
                    val replyChildInfo = Reply()
                    replyChildInfo.rpid = `object`.getJSONObject("item").getLong("target_id")
                    replyChildInfo.message = SpannableString(
                            "[专栏] " + `object`.getJSONObject("item").getString("title"))
                    replyChildInfo.childCount = 0
                    replyInfo.replyInfo = replyChildInfo
                }
                val item = `object`.getJSONObject("item")
                replyInfo.businessId = item.getInt("business_id")
                replyInfo.subjectId = item.getLong("subject_id")
                replyInfo.sourceId = item.optLong("source_id", -1)
                replyInfo.rootId = item.optLong("root_id", -1)
                replyInfo.itemType = item.getString("type")
                replyInfo.getType = MessageCard.GET_TYPE_AT

                totalArray.add(replyInfo)
            }

            val cursor = all.getJSONObject("data").optJSONObject("cursor")
            return Pair(if (cursor == null) null
                    else MessageCard.Cursor(cursor.optBoolean("is_end", true), cursor.optLong("id", -1),
                            cursor.optLong("time", -1)),
                    totalArray)
        } else {
            return Pair(null, ArrayList())
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getSystemMsg(): ArrayList<MessageCard> {
        val url = "https://message.bilibili.com/x/sys-msg/query_user_notify?csrf=" +
                NetWorkUtil.getInfoFromCookie("bili_jct",
                SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!) +
                "&page_size=35&build=0&mobi_app=web"
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            val totalArray: ArrayList<MessageCard> = ArrayList()
            if (data.has("system_notify_list") && !data.isNull("system_notify_list")) {
                for (i in 0 until data.getJSONArray("system_notify_list").length()) {
                    val `object` = data.getJSONArray("system_notify_list").getJSONObject(i)
                    val replyInfo = MessageCard()

                    replyInfo.user = ArrayList()

                    replyInfo.id = `object`.getLong("id")
                    replyInfo.timeDesc = `object`.getString("time_at")
                    replyInfo.content = `object`.getString("title") + "\n" + `object`.getString("content")

                    totalArray.add(replyInfo)
                }
            }

            return totalArray
        } else
            return ArrayList()
    }

    /**
     * 读取消息设置（#1 卡死修复重写）。
     *
     * 旧实现走 NetWorkUtil.post + 全局共享的 webHeaders 列表 —— 与 refreshHeaders()
     * 并发时存在结构性竞争（遍历时列表被改，headers[i+1] 可能越界，即用户遇到的
     * IndexOutOfBoundsException 报错来源之一），且全局 CookieSaveInterceptor
     * 会在每个响应上做 SharedPreferences 读写，连续切换开关时请求风暴放大为卡死。
     *
     * 新实现：
     * - 用 GET 语义发到同一端点（实测该端点支持 GET，无 Set-Cookie）；
     * - headers 用**独立快照**（不碰全局 webHeaders，也不受 refreshHeaders 影响）；
     * - 不走共享 OkHttp 的 CookieSaveInterceptor 链之外的额外副作用。
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getMsgSettings(): JSONObject {
        val csrf = NetWorkUtil.getInfoFromCookie("bili_jct",
                SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!)
        val url = "https://api.vc.bilibili.com/link_setting/v1/link_setting/get" +
                "?msg_notify=1&show_unfollowed_msg=1&build=0&mobi_app=web" +
                "&csrf_token=" + java.net.URLEncoder.encode(csrf, "UTF-8") +
                "&csrf=" + java.net.URLEncoder.encode(csrf, "UTF-8")
        val response = NetWorkUtil.get(url, msgSettingHeaders())
        val result = response.body!!.string()
        return JSONObject(result)
    }

    /** 消息设置专用请求头快照（#1）：独立构造，避免与全局 webHeaders 的并发竞争 */
    private fun msgSettingHeaders(): ArrayList<String> {
        val headers = ArrayList<String>()
        headers.add("Cookie")
        headers.add(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
        headers.add("Origin")
        headers.add("https://www.bilibili.com")
        headers.add("Referer")
        headers.add("https://www.bilibili.com/")
        headers.add("User-Agent")
        headers.add(NetWorkUtil.USER_AGENT_WEB)
        return headers
    }

    /**
     * 保存消息设置（#1 卡死修复重写，同 getMsgSettings 的理由）。
     * POST 表单体本身不变（服务端语义一致），但 headers 用独立快照。
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun setMsgSettings(settings: JSONObject): JSONObject {
        val csrf = NetWorkUtil.getInfoFromCookie("bili_jct",
                SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!)
        val url = "https://api.vc.bilibili.com/link_setting/v1/link_setting/set"
        val formData = NetWorkUtil.FormData()
                .put("csrf_token", csrf)
                .put("csrf", csrf)
                .put("build", 0)
                .put("mobi_app", "web")

        if (settings.has("msg_notify"))
            formData.put("msg_notify", settings.getInt("msg_notify"))
        if (settings.has("show_unfollowed_msg"))
            formData.put("show_unfollowed_msg", settings.getInt("show_unfollowed_msg"))
        if (settings.has("is_group_fold"))
            formData.put("is_group_fold", settings.getInt("is_group_fold"))
        if (settings.has("should_receive_group"))
            formData.put("should_receive_group", settings.getInt("should_receive_group"))
        if (settings.has("receive_unfollow_msg"))
            formData.put("receive_unfollow_msg", settings.getInt("receive_unfollow_msg"))
        if (settings.has("ai_intercept"))
            formData.put("ai_intercept", settings.getInt("ai_intercept"))

        val response = NetWorkUtil.post(url, formData.toString(), msgSettingHeaders())
        val result = response.body!!.string()
        return JSONObject(result)
    }
}
