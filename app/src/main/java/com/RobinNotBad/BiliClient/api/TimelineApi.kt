package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.Timeline
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList

object TimelineApi {
    @JvmSuppressWildcards
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getTimeline(types: String, before: Int, after: Int): MutableList<Timeline.DayInfo> {
        val url = "https://api.bilibili.com/pgc/web/timeline?types=" + types + "&before=" + before + "&after=" + after

        val all = NetWorkUtil.getJson(url)
        if (all.getInt("code") != 0) {
            throw JSONException(all.optString("message", "请求失败"))
        }

        val result = all.getJSONArray("result")
        val dayInfoList = ArrayList<Timeline.DayInfo>()

        for (i in 0 until result.length()) {
            val dayObj = result.getJSONObject(i)
            val dayInfo = Timeline.DayInfo()
            dayInfo.date = dayObj.getString("date")
            dayInfo.date_ts = dayObj.getLong("date_ts")
            dayInfo.day_of_week = dayObj.getInt("day_of_week")
            dayInfo.is_today = dayObj.getInt("is_today")

            val episodesArray = dayObj.getJSONArray("episodes")
            val episodeList = ArrayList<Timeline.Episode>()
            dayInfo.episodes = episodeList
            for (j in 0 until episodesArray.length()) {
                val epObj = episodesArray.getJSONObject(j)
                val episode = Timeline.Episode()
                episode.cover = epObj.optString("cover", "")
                episode.delay = epObj.optInt("delay", 0)
                episode.delay_id = epObj.optLong("delay_id", 0)
                episode.delay_index = epObj.optString("delay_index", "")
                episode.delay_reason = epObj.optString("delay_reason", "")
                episode.ep_cover = epObj.optString("ep_cover", "")
                episode.episode_id = epObj.optLong("episode_id", 0)
                episode.pub_index = epObj.optString("pub_index", "")
                episode.pub_time = epObj.optString("pub_time", "")
                episode.pub_ts = epObj.optLong("pub_ts", 0)
                episode.published = epObj.optInt("published", 0)
                episode.follows = epObj.optString("follows", "")
                episode.plays = epObj.optString("plays", "")
                episode.season_id = epObj.optLong("season_id", 0)
                episode.square_cover = epObj.optString("square_cover", "")
                episode.title = epObj.optString("title", "")
                episodeList.add(episode)
            }

            dayInfoList.add(dayInfo)
        }

        return dayInfoList
    }
}
