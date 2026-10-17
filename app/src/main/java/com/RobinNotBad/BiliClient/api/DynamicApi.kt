package com.RobinNotBad.BiliClient.api

import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.util.Pair

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.model.At
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.model.Emote
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.model.Stats
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.DmImgParamUtil
import com.RobinNotBad.BiliClient.util.EmoteUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList
import java.util.regex.Pattern

import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException

//新的动态api，旧的那个实在太蛋疼而且说不定随时会被弃用（

object DynamicApi {

    /**
     * 发送纯文本动态
     *
     * @param content 文字内容
     * @return 发送成功返回的动态id，失败返回-1
     */
    @Throws(IOException::class)
    @JvmStatic
    fun publishTextContent(content: String): Long {
        val url = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/create"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("dynamic_id", 0)
                .put("type", 4)
                .put("rid", 0)
                .put("content", content)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        try {
            val body = resp.body
            if (body == null) return -1
            val result = JSONObject(body.string())
            if (result.getString("code") == "0" && result.has("data"))
                return result.getJSONObject("data").getLong("dynamic_id")
        } catch (ignored: JSONException) {
            return -1
        }
        return -1
    }

    /**
     * 发送复杂动态
     *
     * @param contents 动态内容
     * @param pics     携带图片
     * @param option   选项
     * @param topic    话题
     * @param scene    动态类型
     * @return 发送成功返回的动态id，失败返回-1
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun publishComplex(contents: JSONArray, pics: JSONArray?, option: JSONObject?, topic: JSONObject?, scene: Int, otherArgs: Map<String, Any>?): Long {
        val url = "https://api.bilibili.com/x/dynamic/feed/create/dyn?csrf=" + SharedPreferencesUtil.getString("csrf", "")
        var reqBody = JSONObject()
                .put("content", JSONObject().put("contents", contents))
                .put("scene", scene)
                .put("meta", JSONObject().put("app_meta", JSONObject()
                        .put("from", "create.dynamic.web")
                        .put("mobi_app", "web")))
        if (pics != null) reqBody.put("pics", pics)
        if (option != null) reqBody.put("option", option)
        if (topic != null) reqBody.put("topic", topic)
        reqBody = JSONObject().put("dyn_req", reqBody)
        if (otherArgs != null) {
            for (entry in otherArgs.entries) {
                val key = entry.key
                val `val` = entry.value
                reqBody.put(key, `val`)
            }
        }
        Logu.v("publishComplex reqBody=$reqBody")
        val resp = NetWorkUtil.postJson(url, reqBody.toString())!!
        try {
            val body = resp.body
            if (body == null) return -1
            val result = JSONObject(body.string())
            if (result.getString("code") == "0" && result.has("data"))
                return result.getJSONObject("data").getLong("dyn_id")
        } catch (e: JSONException) {
            MsgUtil.err("发送动态", e)
            return -1
        }
        return -1
    }

    /**
     * 发布可包含艾特信息的文本动态
     *
     * @param content   文本内容
     * @param atUserUid 文本内at到的人的用户名uid map
     * @return 发送成功返回的动态id，失败返回-1
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun publishTextContent(content: String, atUserUid: Map<String, Long>): Long {
        return publishComplex(parseAtContent(content, atUserUid), null, null, null,
                1, null)
    }

    /**
     * 转发视频到动态，瞎扒的api
     *
     * @param text 附加文字
     * @param aid  aid
     * @return 发送成功返回的动态id，失败返回-1
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun relayVideo(text: String?, atUserUid: Map<String, Long>?, aid: Long): Long {
        return publishComplex(if (text == null) JSONArray().put(Content.create("", 1, null)) else if (atUserUid != null) parseAtContent(text, atUserUid) else JSONArray().put(Content.create(text, 1, null)),
                null, null, null,
                5, mapOf<String, Any>("web_repost_src" to
                        JSONObject().put("revs_id", JSONObject()
                                .put("dyn_type", 8)
                                .put("rid", aid))))
    }

    /**
     * 转发动态
     *
     * @param text 文字内容
     * @param dyid 动态id
     * @return 发送成功返回的动态id，失败返回-1
     */
    @Throws(IOException::class)
    @JvmStatic
    fun relayDynamic(text: String, dyid: Long): Long {
        val url = "https://api.vc.bilibili.com/dynamic_repost/v1/dynamic_repost/repost"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("dynamic_id", dyid)
                .put("content", text)
                .put("csrf_token", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        try {
            val body = resp.body
            if (body == null) return -1
            val result = JSONObject(body.string())
            if (result.getString("code") == "0" && result.has("data"))
                return result.getJSONObject("data").getLong("dynamic_id")
        } catch (ignored: JSONException) {
            return -1
        }
        return -1
    }

    /**
     * 转发动态（复杂动态api），还是自己瞎扒的api
     *
     * @param text      文字内容
     * @param atUserUid 文本内at到的人的用户名uid map
     * @param dyid      动态id
     * @return 发送成功返回的动态id，失败返回-1
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun relayDynamic(text: String?, atUserUid: Map<String, Long>?, dyid: Long): Long {
        return publishComplex(if (text == null) JSONArray().put(Content.create("", 1, null)) else if (atUserUid != null) parseAtContent(text, atUserUid) else JSONArray().put(Content.create(text, 1, null)),
                null, null, null,
                4, mapOf<String, Any>("web_repost_src" to JSONObject().put("dyn_id_str", dyid.toString())))
    }

    /**
     * 解析包含艾特信息的文本动态内容
     *
     * @param content   文本内容
     * @param atUserUid 文本内at到的人的用户名uid map
     * @return Content JSON数组
     */
    @Throws(JSONException::class)
    @JvmStatic
    fun parseAtContent(content: String, atUserUid: Map<String, Long>): JSONArray {
        val contentJSONArray = JSONArray()

        val indexes = HashSet<Pair<Int, Int>>()
        val uidIndexes = HashMap<Pair<Int, Int>, Long>()
        for (entry in atUserUid.entries) {
            val key = entry.key
            val `val` = entry.value

            val pattern = Pattern.compile("@" + key + " ")
            val matcher = pattern.matcher(content)
            val mIndex = ArrayList<Pair<Int, Int>>()
            while (matcher.find()) {
                val start = matcher.start()
                // 不包含空格，我直接按照我抓的请求内容弄的
                val end = matcher.end()
                val pair = Pair(start, end)
                mIndex.add(pair)
                uidIndexes[pair] = `val`
            }
            indexes.addAll(mIndex)
        }

        val indexesList = ArrayList(indexes)
        var pos = 0
        for (index in indexesList) {
            val start = index.first
            val end = index.second
            val sub = content.substring(pos, start)
            if (!sub.isEmpty()) contentJSONArray.put(Content.create(sub, 1, null))
            val subAt = content.substring(start, end)
            if (!subAt.isEmpty())
                contentJSONArray.put(Content.create(subAt, 2, uidIndexes[index].toString()))
            pos = end
        }
        val sub = content.substring(pos)
        if (!sub.isEmpty()) contentJSONArray.put(Content.create(sub, 1, null))

        if (indexesList.isEmpty()) contentJSONArray.put(Content.create(content, 1, null))
        return contentJSONArray
    }

    /**
     * 动态点赞/取消赞
     *
     * @param dyid 动态id
     * @param up   是否为点赞
     * @return resultCode
     */
    @Throws(IOException::class)
    @JvmStatic
    fun likeDynamic(dyid: Long, up: Boolean): Int {
        val url = "https://api.vc.bilibili.com/dynamic_like/v1/dynamic_like/thumb"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("dynamic_id", dyid)
                .put("up", if (up) 1 else 2)
                .put("csrf_token", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        try {
            val responseBody = resp.body
            if (responseBody == null) return -1
            val result = JSONObject(responseBody.string())
            return result.getInt("code")
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun deleteDynamic(dyid: Long): Int {
        val url = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/rm_dynamic"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("dynamic_id", dyid)
                .put("csrf_token", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        try {
            val body = resp.body
            if (body == null) return -1
            val result = JSONObject(body.string())
            return result.getInt("code")
        } catch (ignored: JSONException) {
            return -1
        }
    }

    /**
     * 寻找用户（完全匹配），仍然自己瞎扒的，不清楚是否有更好方案
     *
     * @param name 名称
     * @return 用户UID，未找到返回-1
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun mentionAtFindUser(name: String): Long {
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/mention/search?keyword=$name"

        val resp = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)
        if (resp.has("data") && !resp.isNull("data")) {
            val data = resp.getJSONObject("data")
            if (data.has("groups") && !data.isNull("groups")) {
                val groups = data.getJSONArray("groups")
                for (i in 0 until groups.length()) {
                    val items = groups.getJSONObject(i).getJSONArray("items")
                    for (j in 0 until items.length()) {
                        if (items.getJSONObject(j).getString("name") == name)
                            return items.getJSONObject(j).getString("uid").toLong()
                    }
                }
            }
        }

        return -1
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getDynamicList(dynamicList: MutableList<Dynamic>, offset: Long, mid: Long, type: String?): Long {
        val url: String
        if (mid == 0L) {
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=" + type +
                    (if (offset == 0L) "" else "&offset=" + offset) +
                    "&features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,forwardListHidden,decorationCard,commentsNewVersion,onlyfansAssetsV2,ugcDelete,onlyfansQaCard,avatarAutoTheme,sunflowerStyle,eva3CardOpus,eva3CardVideo,eva3CardComment"
        } else {
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=" + mid +
                    "&platform=web" +
                    "&web_location=333.1387" +
                    "&timezone_offset=-480" +
                    (if (offset == 0L) "" else "&offset=" + offset) +
                    "&features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote,forwardListHidden,decorationCard,commentsNewVersion,onlyfansAssetsV2,ugcDelete,onlyfansQaCard,avatarAutoTheme,sunflowerStyle,eva3CardOpus,eva3CardVideo,eva3CardComment"
        }

        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url)))
        if (all.getInt("code") != 0) throw JSONException(all.getString("message"))


        val data = all.getJSONObject("data")

        val has_more = data.getBoolean("has_more")
        val offset_new = if (has_more) data.getString("offset").toLong() else -1

        if (mid == 0L) {
            val update_baseline = data.optLong("update_baseline", -1)
            if (update_baseline > -1) SharedPreferencesUtil.putLong("dynamic_update_baseline", update_baseline)
            else if (offset_new != -1L) {
                SharedPreferencesUtil.putLong("dynamic_update_baseline", offset_new)
            }
        }

        val items = data.getJSONArray("items")
        for (i in 0 until items.length()) {
            dynamicList.add(analyzeDynamic(items.getJSONObject(i)))
        }

        return offset_new
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getDynamic(id: Long): Dynamic {
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=$id"

        val all = NetWorkUtil.getJson(url)
        if (all.getInt("code") != 0) throw JSONException(all.getString("message"))

        val data = all.getJSONObject("data")
        val item = data.getJSONObject("item")
        return analyzeDynamic(item)
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun checkDynamicUpdate(type: String?, updateBaseline: Long): Int {
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all/update?type=" + type + "&update_baseline=" + updateBaseline + "&web_location=333.1365"
        val result = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)
        if (result.getInt("code") != 0) throw JSONException(result.getString("message"))
        if (result.has("data") && !result.isNull("data")) {
            val data = result.getJSONObject("data")
            return data.optInt("update_num", 0)
        }
        return 0
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeDynamic(dynamic_json: JSONObject): Dynamic {
        Logu.v("--------------")
        val dynamic = Dynamic()

        if (!dynamic_json.isNull("id_str"))
            try {
                dynamic.dynamicId = dynamic_json.optString("id_str", "0").toLong()
            } catch (ignored: Exception) {
            }
        else {
            dynamic.dynamicId = 0
        }
        dynamic.type = dynamic_json.optString("type")

        val basic = dynamic_json.getJSONObject("basic")
        val comment_id = basic.optString("comment_id_str", "0")
        if (!TextUtils.isEmpty(comment_id))
            try {
                dynamic.comment_id = comment_id.toLong()
            } catch (ignored: Exception) {
            }
        else
            dynamic.comment_id = 0

        dynamic.comment_type = basic.optInt("comment_type")

        Logu.v("id", dynamic.dynamicId.toString())
        Logu.v("oid", dynamic.comment_id.toString())
        Logu.v("type", dynamic.type)
        Logu.v("otype", dynamic.comment_type.toString())

        val modules = dynamic_json.getJSONObject("modules")

        //发布者
        val userInfo = UserInfo()
        if (!modules.isNull("module_author")) {
            val module_author = modules.getJSONObject("module_author")
            userInfo.mid = module_author.getLong("mid")
            userInfo.name = module_author.getString("name")
            if (!module_author.isNull("following"))
                userInfo.followed = module_author.getBoolean("following")
            userInfo.avatar = module_author.getString("face")
            val vipJson = module_author.optJSONObject("vip")
            if (vipJson != null) {
                userInfo.vip_nickname_color = vipJson.optString("nickname_color", "")
            }
            Logu.v("sender", userInfo.name)
            dynamic.pubTime = module_author.getString("pub_time")
        }
        dynamic.userInfo = userInfo

        if (dynamic.type == "DYNAMIC_TYPE_NONE") {
            dynamic.content = "[动态不存在]"
            return dynamic
        }

        //动态主体
        if (!modules.isNull("module_dynamic")) {
            val module_dynamic = modules.getJSONObject("module_dynamic")

            //内容
            if (!module_dynamic.isNull("desc")) {
                val desc = module_dynamic.getJSONObject("desc")
                val rich_text_nodes = desc.optJSONArray("rich_text_nodes")
                dynamic.content = analyzeTextContent(rich_text_nodes)
            } else dynamic.content = ""

            //这里面什么都有，直译为主要的
            if (!module_dynamic.isNull("major")) {
                val major = module_dynamic.getJSONObject("major")
                val major_type = major.getString("type")
                dynamic.major_type = major_type
                Logu.d(major_type)
                when (major_type) {
                    "MAJOR_TYPE_ARCHIVE" ->
                        dynamic.major_object = analyzeVideoCard(major.getJSONObject("archive"))
                    "MAJOR_TYPE_UGC_SEASON" ->
                        dynamic.major_object = analyzeVideoCard(major.getJSONObject("ugc_season"))
                    "MAJOR_TYPE_PGC" -> {
                        val bangumi = major.getJSONObject("pgc")
                        val card = VideoCard()
                        card.type = "media_bangumi"
                        card.aid = BangumiApi.getMdidFromEpid(bangumi.getLong("epid"))
                        card.title = bangumi.getString("title")
                        card.cover = bangumi.getString("cover")
                        card.view = bangumi.getJSONObject("stat").optString("play", "")
                        // org.json 的 getString 对 JSON null 会返回字面量 "null"（#5），改 optString 兜底
                        dynamic.major_object = card
                    }
                    "MAJOR_TYPE_ARTICLE" -> {
                        val article = major.getJSONObject("article")
                        dynamic.major_object = ArticleCard(
                                article.getString("title"),
                                article.getLong("id"),
                                (if (article.has("covers") && !article.isNull("covers")) article.getJSONArray("covers").getString(0) else ""),
                                "投稿文章",
                                article.getString("label")
                        )
                    }

                    "MAJOR_TYPE_DRAW" -> {
                        val draw = major.getJSONObject("draw")
                        val items = draw.getJSONArray("items")
                        val picture_list = ArrayList<String>()
                        for (i in 0 until items.length()) {
                            picture_list.add(items.getJSONObject(i).getString("src"))
                        }
                        dynamic.major_object = picture_list
                    }

                    "MAJOR_TYPE_COMMON" ->
                        dynamic.content = dynamic.content.toString() + "\n[无法显示活动类动态的附加内容]"

                    "MAJOR_TYPE_LIVE_RCMD" -> {
                        val live_rcmd = JSONObject(major.getJSONObject("live_rcmd").getString("content")).getJSONObject("live_play_info")
                        val room = LiveRoom()
                        room.roomid = live_rcmd.getLong("room_id")
                        room.title = live_rcmd.getString("title")
                        room.cover = live_rcmd.getString("cover")
                        room.online = live_rcmd.getInt("online")
                        dynamic.major_object = room
                        dynamic.content = if (TextUtils.isEmpty(dynamic.content)) "" else dynamic.content.toString() + "\n"
                    }

                    "MAJOR_TYPE_LIVE" -> {
                        val live = major.getJSONObject("live")
                        val room_card = LiveRoom()
                        room_card.roomid = live.getLong("id")
                        room_card.title = live.getString("title")
                        room_card.cover = live.getString("cover")
                        dynamic.major_object = room_card
                        dynamic.content = if (TextUtils.isEmpty(dynamic.content)) "" else dynamic.content.toString() + "\n"
                    }

                    "MAJOR_TYPE_OPUS" -> {
                        val opusJson = major.getJSONObject("opus")

                        val title = opusJson.optString("title")
                        if (!TextUtils.isEmpty(title) && title != "null")
                            dynamic.title = title

                        val pics = opusJson.optJSONArray("pics")
                        if (pics != null) {
                            val opusPicList = ArrayList<String>()
                            for (i in 0 until pics.length())
                                opusPicList.add(pics.getJSONObject(i).optString("url"))

                            dynamic.major_object = opusPicList
                        }

                        val summary = opusJson.optJSONObject("summary")
                        if (summary != null)
                            dynamic.content = analyzeTextContent(summary.optJSONArray("rich_text_nodes"))
                        else
                            dynamic.content = ""
                    }

                    else ->
                        dynamic.content = dynamic.content.toString() + "\n[*哔哩终端暂时无法查看此动态的附加内容QwQ|类型：" + major_type + "]"
                }
            }
            if (modules.has("module_additional") && !modules.isNull("module_additional")) {
                val module_additional = modules.getJSONObject("module_additional")
                if (module_additional.getString("type") == "ADDITIONAL_TYPE_UGC") {
                    dynamic.major_type = "MAJOR_TYPE_ARCHIVE"
                    dynamic.major_object = analyzeVideoCard(module_additional.getJSONObject("ugc"))
                } else Logu.v("addi", module_additional.getString("type"))
            }
        }

        // 动态Stats
        if (modules.has("module_stat") && !modules.isNull("module_stat")) {
            val module_stat = modules.getJSONObject("module_stat")
            val like = module_stat.getJSONObject("like")
            val stats = Stats()
            stats.like = like.getInt("count")
            stats.liked = like.getBoolean("status")
            stats.like_disabled = like.getBoolean("forbidden")
            // TODO 转发&回复

            dynamic.stats = stats
        }

        if (modules.has("module_more") && !modules.isNull("module_more")) {
            val supportItemTypes = ArrayList<String>()
            val three_point_items = modules.getJSONObject("module_more").getJSONArray("three_point_items")
            for (i in 0 until three_point_items.length()) {
                supportItemTypes.add(three_point_items.getJSONObject(i).getString("type"))
            }
            dynamic.canDelete = supportItemTypes.contains("THREE_POINT_DELETE")
        }

        if (dynamic_json.has("orig") && !dynamic_json.isNull("orig")) {
            dynamic.dynamic_forward = analyzeDynamic(dynamic_json.getJSONObject("orig"))
        }

        return dynamic
    }

    private fun analyzeVideoCard(jsonObject: JSONObject): VideoCard {
        // #5：getString 对 JSON null 返回字面量 "null"，全部换成带兜底的 optString
        return VideoCard(
                jsonObject.optString("title", ""),
                "投稿视频",
                jsonObject.getJSONObject("stat").optString("play", ""),
                jsonObject.optString("cover", ""),
                jsonObject.optString("aid", "0").toLongOrNull() ?: 0L,
                jsonObject.optString("bvid", "")
        )
    }

    private fun analyzeTextContent(rich_text_nodes: JSONArray?): SpannableStringBuilder {
        if (rich_text_nodes == null) return SpannableStringBuilder("[动态内容解析异常]")

        val emoteList = ArrayList<Emote>()
        val atList = ArrayList<At>()
        val content = SpannableStringBuilder()
        for (i in 0 until rich_text_nodes.length()) {
            val rich_text_node = rich_text_nodes.optJSONObject(i) ?: continue
            val type = rich_text_node.optString("type")
            when (type) {
                "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                    content.append(rich_text_node.optString("text"))
                    val emoji = rich_text_node.optJSONObject("emoji") ?: continue
                    emoteList.add(Emote(emoji.optString("text"), emoji.optString("icon_url"), emoji.optInt("size")))
                }
                "RICH_TEXT_NODE_TYPE_AT" -> {
                    val indexs = StringUtil.appendString(content, rich_text_node.optString("text"))
                    atList.add(At(rich_text_node.optLong("rid"), indexs.first, indexs.second))
                }
                "RICH_TEXT_NODE_TYPE_WEB" ->
                    content.append(rich_text_node.optString("orig_text"))
                else ->
                    content.append(rich_text_node.optString("text"))
            }
        }

        EmoteUtil.textReplaceEmote(content.toString(), emoteList, 1.0f, BiliTerminal.context, content)
        for (at in atList) {
            StringUtil.setSingleAt(content, at)
        }

        return content
    }

    class Content {
        companion object {
            @Throws(JSONException::class)
            @JvmStatic
            fun create(raw_text: String, type: Int, biz_id: String?): JSONObject {
                return JSONObject()
                        .put("raw_text", raw_text)
                        .put("type", type)
                        .put("biz_id", if (biz_id == null) "" else biz_id)
            }
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getRecentUpList(): List<UpInfo> {
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/portal"
        val result = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)
        if (result.getInt("code") != 0) throw JSONException(result.getString("message"))

        val upList = ArrayList<UpInfo>()
        if (result.has("data") && !result.isNull("data")) {
            val data = result.getJSONObject("data")
            if (data.has("up_list") && !data.isNull("up_list")) {
                val upListArray = data.getJSONArray("up_list")
                for (i in 0 until upListArray.length()) {
                    val upJson = upListArray.getJSONObject(i)
                    val upInfo = UpInfo()
                    upInfo.mid = upJson.getLong("mid")
                    upInfo.uname = upJson.getString("uname")
                    upInfo.face = upJson.getString("face")
                    upInfo.has_update = upJson.optBoolean("has_update", false)
                    upList.add(upInfo)
                }
            }
        }
        return upList
    }

    class UpInfo {
        @JvmField var mid: Long = 0
        @JvmField var uname: String? = null
        @JvmField var face: String? = null
        @JvmField var has_update: Boolean = false
    }
}
