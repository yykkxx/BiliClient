package com.RobinNotBad.BiliClient.api

import android.text.SpannableStringBuilder
import android.util.Pair

import com.RobinNotBad.BiliClient.model.At
import com.RobinNotBad.BiliClient.model.Collection
import com.RobinNotBad.BiliClient.model.Stats
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayList


//视频信息API 自己写的


object VideoInfoApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getVideoInfo(bvid: String): VideoInfo? {  //通过bvid获取json
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid
        val result = NetWorkUtil.getJson(url)
        if (!result.has("data")) return null
        val videoInfo = getInfoByJson(result.getJSONObject("data"))
        LikeCoinFavApi.getVideoStats(videoInfo)
        return videoInfo
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getVideoInfo(aid: Long): VideoInfo? {  //通过aid获取json
        val url = "https://api.bilibili.com/x/web-interface/view?aid=" + aid
        val result = NetWorkUtil.getJson(url)
        if (!result.has("data")) return null
        val videoInfo = getInfoByJson(result.getJSONObject("data"))
        LikeCoinFavApi.getVideoStats(videoInfo)
        return videoInfo
    }


    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getTags(bvid: String): String {  //通过bvid获取tag
        val url = "https://api.bilibili.com/x/tag/archive/tags?bvid=" + bvid
        val result = NetWorkUtil.getJson(url)
        return analyzeTags(result.getJSONArray("data"))
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getTags(aid: Long): String {  //通过aid获取tag
        val url = "https://api.bilibili.com/x/tag/archive/tags?aid=" + aid
        val result = NetWorkUtil.getJson(url)
        return analyzeTags(result.getJSONArray("data"))
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeTags(tagJson: JSONArray): String {
        val tags = StringBuilder()
        for (i in 0 until tagJson.length()) {
            if (i > 0) tags.append("/")
            tags.append((tagJson.get(i) as JSONObject).getString("tag_name"))
        }
        return tags.toString()
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeUgcSeason(json: JSONObject): Collection {
        val collection = Collection()
        collection.id = json.optInt("id", -1)
        collection.title = json.optString("title")
        collection.intro = json.optString("intro")
        collection.cover = json.optString("cover")
        collection.mid = json.optLong("mid")
        collection.view = StringUtil.toWan(json.getJSONObject("stat").optLong("view"))
        val sections = json.optJSONArray("sections")
        if (sections != null) {
            val sectionList = ArrayList<Collection.Section>()
            for (i in 0 until sections.length()) {
                val sectionJson = sections.getJSONObject(i)
                val section = Collection.Section()
                section.season_id = sectionJson.optInt("season_id", -1)
                section.id = sectionJson.optInt("id", -1)
                section.title = sectionJson.optString("title")
                val episodes = sectionJson.optJSONArray("episodes")
                if (episodes != null) {
                    val episodeList = ArrayList<Collection.Episode>()
                    for (j in 0 until episodes.length()) {
                        val episodeJson = episodes.getJSONObject(j)
                        val episode = Collection.Episode()
                        episode.season_id = episodeJson.optInt("season_id", -1)
                        episode.section_id = episodeJson.optInt("section_id", -1)
                        episode.id = episodeJson.optInt("id", -1).toLong()
                        episode.aid = episodeJson.optLong("aid", -1)
                        episode.cid = episodeJson.optLong("cid", -1)
                        episode.title = episodeJson.optString("title")
                        val arc = episodeJson.optJSONObject("arc")
                        if (arc != null) {
                            episode.arc = getInfoByJson(arc)
                        }
                        episode.bvid = episodeJson.optString("bvid")
                        episodeList.add(episode)
                    }
                    section.episodes = episodeList
                }
                sectionList.add(section)
            }
            collection.sections = sectionList
        }
        return collection
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun getInfoByJson(data: JSONObject): VideoInfo {  //项目实在太多qwq 拆就完事了
        val videoInfo = VideoInfo()
        Logu.v("视频信息", "--------")
        videoInfo.title = data.optString("title")
        Logu.v("标题", videoInfo.title)
        videoInfo.cover = data.optString("pic")
        Logu.v("封面", videoInfo.cover)
        if (data.has("desc_v2") && !data.isNull("desc_v2")) {
            val sb = SpannableStringBuilder()
            val descArray = data.getJSONArray("desc_v2")
            val ats = ArrayList<At>()
            for (i in 0 until descArray.length()) {
                val curObj = descArray.optJSONObject(i) ?: continue
                val type = curObj.optInt("type")
                when (type) {
                    2 -> {
                        val indexs = StringUtil.appendString(sb, "@" + curObj.optString("raw_text"))
                        ats.add(At(curObj.optLong("biz_id"), indexs.first, indexs.second))
                    }
                    else -> {
                        sb.append(curObj.optString("raw_text"))
                    }
                }
            }
            videoInfo.description = sb.toString()
            videoInfo.descAts = ats
        } else {
            videoInfo.description = data.optString("desc")
        }
        Logu.v("简介", videoInfo.description)

        videoInfo.bvid = data.optString("bvid")
        videoInfo.aid = data.optLong("aid")

        @Suppress("SimpleDateFormat") val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        videoInfo.timeDesc = sdf.format(data.optLong("pubdate") * 1000)
        Logu.v("发布时间", videoInfo.timeDesc.toString())

        videoInfo.duration = StringUtil.toTime(data.optInt("duration"))
        Logu.v("视频时长", videoInfo.duration)

        if (data.has("copyright") && !data.isNull("copyright"))
            videoInfo.copyright = data.optInt("copyright")

        // 注意：以下全部用 optXxx 而不是 getXxx。
        // 实测：未登录（登录态失效/未登录）时 /x/web-interface/view 匿名回包的 stat 里
        // **没有 like 字段**（只有 aid/view/danmaku/reply/favorite/coin/share/now_rank），
        // 老代码 stat.getInt("like") 会抛 JSONException，详情页变成「获取信息失败」；
        // 若该异常发生在未捕获的后台线程，就是一次闪退。字段缺失一律给 0 兜底。
        val stat = data.optJSONObject("stat")
        val stats = Stats()
        stats.view = stat?.optInt("view", 0) ?: 0
        stats.like = stat?.optInt("like", 0) ?: 0
        stats.coin = stat?.optInt("coin", 0) ?: 0
        stats.reply = stat?.optInt("reply", 0) ?: 0
        stats.danmaku = stat?.optInt("danmaku", 0) ?: 0
        stats.favorite = stat?.optInt("favorite", -1) ?: -1
        stats.coin_limit = if (videoInfo.copyright == VideoInfo.COPYRIGHT_REPRINT) 1 else 2
        videoInfo.stats = stats

        val pages = data.optJSONArray("pages")
        if (pages != null) {
            val pagenames = ArrayList<String>()
            val cids = ArrayList<Long>()
            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val pagename = page.optString("part")
                pagenames.add(pagename)
                Logu.v("第" + i + "个视频的标题", pagename)
                val cid = page.optLong("cid")
                cids.add(cid)
                Logu.v("第" + i + "个视频的cid", cid.toString())
            }
            videoInfo.pagenames = pagenames
            videoInfo.cids = cids
        }

        videoInfo.upowerExclusive = data.optBoolean("is_upower_exclusive", true)

        val rights = data.optJSONObject("rights")
        if (rights != null) {
            videoInfo.isCooperation = (rights.optInt("is_cooperation", 0) == 1)
            videoInfo.isSteinGate = (rights.optInt("is_stein_gate", 0) == 1)
            videoInfo.is360 = (rights.optInt("is_360", 0) == 1)
        }

        val staff_list = ArrayList<UserInfo>()
        if (videoInfo.isCooperation) { //如果是联合投稿就存储联合UP列表
            // 合作稿的 staff 数组在部分状态下会缺失（APP 通道/风控/灰度都可能），
            // 老代码 data.getJSONArray("staff") 缺字段就抛 JSONException → 详情页报错甚至闪退。
            val staff = data.optJSONArray("staff")
            if (staff != null) {
                for (i in 0 until staff.length()) {
                    val staff_member = UserInfo()
                    val staff_info = staff.optJSONObject(i) ?: continue

                    staff_member.mid = staff_info.optLong("mid")
                    staff_member.sign = staff_info.optString("title") //卡片的简介用来显示参与的事物
                    staff_member.name = staff_info.optString("name")
                    staff_member.avatar = staff_info.optString("face")
                    staff_member.fans = staff_info.optInt("follower")
                    staff_member.level = 6
                    staff_member.followed = false
                    staff_member.notice = ""
                    val official = staff_info.optJSONObject("official")
                    staff_member.official = official?.optInt("role") ?: 0
                    staff_member.officialDesc = official?.optString("title") ?: ""

                    staff_list.add(staff_member)
                }
            }
        } else {
            val owner = data.optJSONObject("owner")
            if (owner != null) {
                val userInfo = UserInfo()
                userInfo.name = owner.optString("name")
                userInfo.avatar = owner.optString("face")
                userInfo.mid = owner.optLong("mid")
                userInfo.sign = "UP主"
                staff_list.add(userInfo)
            }
        }
        videoInfo.staff = staff_list

        val argueInfo = data.optJSONObject("argue_info")
        if (argueInfo != null) {
            videoInfo.argueMsg = argueInfo.optString("argue_msg")
        }

        try {
            if (data.has("redirect_url") && (data.getString("redirect_url").isNotEmpty()) && (data.getString("redirect_url").contains("bangumi")))
                videoInfo.epid = data.getString("redirect_url").replace("https://www.bilibili.com/bangumi/play/ep", "").toLong()
            else videoInfo.epid = -1
        } catch (e: Exception) {
            e.printStackTrace()
            videoInfo.epid = -1
        }

        val ugc_season = data.optJSONObject("ugc_season")
        if (ugc_season != null) {
            videoInfo.collection = analyzeUgcSeason(ugc_season)
        }

        return videoInfo
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getWatching(aid: Long, cid: Long): String {
        val url = "https://api.bilibili.com/x/player/online/total?aid=" + aid + "&cid=" + cid
        val result = NetWorkUtil.getJson(url)
        if (result.has("data") && !result.isNull("data")) {
            val data = result.getJSONObject("data")
            if (data.has("total") && !data.isNull("total")) {
                if (data.get("total") is String) return data.getString("total")
                else return StringUtil.toWan(data.getLong("total"))
            }
        }
        return ""
    }

}
