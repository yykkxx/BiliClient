package com.RobinNotBad.BiliClient.api

import android.util.Log

import com.RobinNotBad.BiliClient.model.Bangumi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList
import java.io.IOException

object BangumiApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFollowingList(page: Int, cardList: MutableList<VideoCard>): Int {
        val url = "https://api.bilibili.com/x/space/bangumi/follow/list?type=1&follow_status=0&pn=" + page +
                "&ps=15&vmid=" + SharedPreferencesUtil.getLong("mid", 0)

        val all = NetWorkUtil.getJson(url)
        if (all.getInt("code") != 0) throw JSONException(all.getString("message"))

        val data = all.getJSONObject("data")
        if (!data.has("list") || data.isNull("list")) return 1

        val list = data.getJSONArray("list")
        if (list.length() == 0) return 1

        for (i in 0 until list.length()) {
            val bangumi = list.getJSONObject(i)
            val card = VideoCard()
            card.type = "media_bangumi"
            card.aid = bangumi.getLong("media_id")
            card.title = bangumi.getString("title")
            card.cover = bangumi.getString("cover")
            card.view = StringUtil.toWan(bangumi.getJSONObject("stat").optInt("view").toLong())

            cardList.add(card)
        }
        return 0
    }


    //获取番剧信息, 详情页需要有基本的cover, 信息等
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getBangumi(mediaId: Long): Bangumi {
        val bangumi = Bangumi()
        bangumi.info = getInfo(mediaId)
        bangumi.sectionList = getSections(bangumi.info!!.season_id)
        return bangumi
    }

    @JvmStatic
    fun getMdidFromEpid(epid: Long): Long {
        try {
            val url = "https://api.bilibili.com/pgc/view/web/season?ep_id=" + epid
            val all = NetWorkUtil.getJson(url)

            Log.e("debug-epid", epid.toString())

            val code = all.getInt("code")
            if (code != 0) return 0L

            return all.getJSONObject("result").getLong("media_id")
        } catch (e: Exception) {
            e.printStackTrace()
            return 0L
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getInfo(mediaId: Long): Bangumi.Info {
        // 先获取基本信息
        val url1 = "https://api.bilibili.com/pgc/review/user?media_id=" + mediaId
        val all1 = NetWorkUtil.getJson(url1)

        val code1 = all1.getInt("code")
        if (code1 != 0) {
            throw JSONException("错误码：$code1")
        }

        val media = all1.getJSONObject("result").getJSONObject("media")

        val info = Bangumi.Info()
        info.media_id = media.getLong("media_id")
        info.season_id = media.getLong("season_id")
        info.title = media.getString("title")
        info.cover = media.getString("cover")
        info.cover_horizontal = media.getString("horizontal_picture")
        info.type = media.getInt("type")
        info.type_name = media.getString("type_name")

        val new_ep = media.optJSONObject("new_ep")
        if (new_ep != null) {
            info.indexShow = new_ep.optString("index_show", "敬请期待")
        }

        val rating = media.optJSONObject("rating")
        if (rating != null) {
            info.count = rating.optInt("count")
            info.score = rating.optDouble("score", 0.0).toFloat()
        }

        val areas = media.getJSONArray("areas")
        val stringBuilder = StringBuilder()
        for (i in 0 until areas.length()) {
            stringBuilder.append(areas.getJSONObject(i).getString("name"))
            if (i < areas.length() - 1) {
                stringBuilder.append(" | ")
            }
        }
        info.area_name = stringBuilder.toString()

        // 获取详细信息
        val url2 = "https://api.bilibili.com/pgc/view/web/season?season_id=" + info.season_id
        val all2 = NetWorkUtil.getJson(url2)

        val code2 = all2.getInt("code")
        if (code2 == 0) {
            val result = all2.getJSONObject("result")

            info.evaluate = result.optString("evaluate", "")
            info.staff = result.optString("staff", "")
            info.record = result.optString("record", "")
            info.subtitle = result.optString("subtitle", "")

            // 发布时间信息
            val publish = result.optJSONObject("publish")
            if (publish != null) {
                info.publish = Bangumi.Publish()
                info.publish!!.is_finish = publish.optInt("is_finish", 0)
                info.publish!!.is_started = publish.optInt("is_started", 0)
                info.publish!!.pub_time = publish.optString("pub_time", "")
                info.publish!!.pub_time_show = publish.optString("pub_time_show", "")
            }

            // 标签
            val styles = result.optJSONArray("styles")
            if (styles != null) {
                info.styles = ArrayList()
                for (i in 0 until styles.length()) {
                    info.styles!!.add(styles.getString(i))
                }
            }

            // 状态数
            val stat = result.optJSONObject("stat")
            if (stat != null) {
                info.stat = Bangumi.Stat()
                info.stat!!.favorites = stat.optInt("favorites", 0)
                info.stat!!.series_follow = stat.optInt("series_follow", 0)
                info.stat!!.views = stat.optInt("views", 0)
                info.stat!!.vt = stat.optInt("vt", 0)
            }

            // UP主信息
            val upInfo = result.optJSONObject("up_info")
            if (upInfo != null) {
                info.up_info = Bangumi.UpInfo()
                info.up_info!!.mid = upInfo.optLong("mid", 0)
                info.up_info!!.name = upInfo.optString("name", "")
                info.up_info!!.avatar = upInfo.optString("avatar", "")
            }

            // 系列信息
            val series = result.optJSONObject("series")
            if (series != null) {
                info.series = Bangumi.Series()
                info.series!!.series_id = series.optLong("series_id", 0)
                info.series!!.series_title = series.optString("series_title", "")
            }

            // 同系列所有季信息
            val seasons = result.optJSONArray("seasons")
            if (seasons != null) {
                info.seasons = ArrayList()
                for (i in 0 until seasons.length()) {
                    val seasonObj = seasons.getJSONObject(i)
                    val season = Bangumi.Season()
                    season.media_id = seasonObj.optLong("media_id", 0)
                    season.season_id = seasonObj.optLong("season_id", 0)
                    season.season_title = seasonObj.optString("season_title", "")
                    season.cover = seasonObj.optString("cover", "")
                    season.badge = seasonObj.optString("badge", "")
                    info.seasons!!.add(season)
                }
            }
        }

        return info
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getSections(season_id: Long): ArrayList<Bangumi.Section> {
        val url = "https://api.bilibili.com/pgc/web/season/section?season_id=" + season_id
        val all = JSONObject(NetWorkUtil.get(url).body()!!.string())  //得到一整个json
        val result = all.getJSONObject("result")
        val sectionList = ArrayList<Bangumi.Section>()

        val main_section = result.optJSONObject("main_section")
        if (main_section != null)
            sectionList.add(analyzeSection(result.getJSONObject("main_section")))

        val other_sections = result.optJSONArray("section")
        if (other_sections != null) {
            for (i in 0 until other_sections.length()) {
                sectionList.add(analyzeSection(other_sections.getJSONObject(i)))
            }
        }

        return sectionList
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeSection(jsonObject: JSONObject): Bangumi.Section {
        val section = Bangumi.Section()
        section.id = jsonObject.getLong("id")
        section.title = jsonObject.getString("title")
        section.type = jsonObject.getInt("type")

        val episodes = jsonObject.getJSONArray("episodes")
        val episodeList = ArrayList<Bangumi.Episode>()
        for (i in 0 until episodes.length()) {
            episodeList.add(analyzeEpisode(episodes.getJSONObject(i)))
        }
        section.episodeList = episodeList

        return section
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeEpisode(jsonObject: JSONObject): Bangumi.Episode {
        val episode = Bangumi.Episode()
        episode.id = jsonObject.getLong("id")
        episode.aid = jsonObject.getLong("aid")
        episode.cid = jsonObject.getLong("cid")
        episode.cover = jsonObject.getString("cover")
        episode.badge = jsonObject.getString("badge")
        episode.title = jsonObject.getString("title")
        episode.title_long = jsonObject.getString("long_title")

        return episode
    }
}
