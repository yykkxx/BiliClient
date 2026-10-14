package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.PageInfo
import com.RobinNotBad.BiliClient.model.Series
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException

/**
 * 我宣布：
 * 在此之后：series统一叫系列，collection统一叫合集
 * 我看b站他们自己都没搞明白，全都叫合集，请允许我在此问候下他们的开发者……
 * 2024-10-20
 * 我靠怎么还有season
 */

object SeriesApi {

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getUserSeries(mid: Long, page: Int, seasonList: MutableList<Series>): Int {
        var url = "https://api.bilibili.com/x/polymer/web-space/seasons_series_list?"
        url += "mid=" + mid + "&page_num=" + page + "&page_size=20"
        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url), NetWorkUtil.webHeaders)

        if (all.has("data") && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            if (data.has("items_lists") && !data.isNull("items_lists")) {
                val itemsLists = data.getJSONObject("items_lists")
                var finished = false
                if (itemsLists.has("seasons_list")) {
                    val seasons = itemsLists.getJSONArray("seasons_list")
                    for (i in 0 until seasons.length())
                        seasonList.add(getSeriesByJson(seasons.getJSONObject(i)))
                    if (seasons.length() > 0) finished = true
                }
                if (itemsLists.has("series_list")) {
                    val series = itemsLists.getJSONArray("series_list")
                    for (i in 0 until series.length())
                        seasonList.add(getSeriesByJson(series.getJSONObject(i)))
                    if (series.length() > 0) finished = true
                }
                if (finished) return 0
                else return 1
            }
        }
        return -1
    }

    /**
     * 获取合集信息
     *
     * @param mid  mid（或许可以为任意）
     * @param id   合集id
     * @param page 页数
     * @return Collection对象与分页信息
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getSeriesInfo(type: String, mid: Long, id: Int, page: Int, videoList: MutableList<VideoCard>): PageInfo {
        val url: String
        when (type) {
            "series" ->
                url = "https://api.bilibili.com/x/series/archives" + NetWorkUtil.FormData()
                    .setUrlParam(true)
                    .put("mid", mid)
                    .put("series_id", id)
                    .put("pn", page)
                    .put("ps", 30)
            "season" ->
                url = "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list" + NetWorkUtil.FormData()
                    .setUrlParam(true)
                    .put("mid", mid)
                    .put("season_id", id)
                    .put("page_num", page)
                    .put("page_size", 30)
            else -> throw JSONException("传入类型有误！")
        }

        val result = NetWorkUtil.getJson(url)

        val pageInfo = PageInfo()

        val data = result.optJSONObject("data")
        if (data != null) {
            val pageJson = data.optJSONObject("page")
            if (pageJson != null) {
                when (type) {
                    "series" -> {
                        pageInfo.page_num = pageJson.optInt("num", -1)
                        pageInfo.require_ps = pageJson.optInt("size", -1)
                        pageInfo.total = pageJson.optInt("total", -1)
                    }
                    "season" -> {
                        pageInfo.page_num = pageJson.optInt("page_num", -1)
                        pageInfo.require_ps = pageJson.optInt("page_size", -1)
                        pageInfo.total = pageJson.optInt("total", -1)
                    }
                }
            }

            val archives = data.optJSONArray("archives")
            if (archives != null) {
                pageInfo.return_ps = archives.length()
                for (i in 0 until archives.length()) {
                    val archive = archives.getJSONObject(i)
                    val videoCard = VideoCard()
                    videoCard.aid = archive.optLong("aid", 0)
                    videoCard.bvid = archive.optString("bvid", "")
                    videoCard.cover = archive.optString("pic", "")
                    videoCard.view = StringUtil.toWan(archive.getJSONObject("stat").optInt("view", -1).toLong())
                    videoCard.title = archive.optString("title", "")
                    videoList.add(videoCard)
                }
            }
        }
        return pageInfo
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun getSeriesByJson(data: JSONObject): Series {
        val series = Series()
        val meta = data.getJSONObject("meta")

        if (meta.has("season_id")) {
            series.type = "season"
            series.id = meta.getInt("season_id")
        } else {
            series.type = "series"
            series.id = meta.getInt("series_id")
        }
        series.title = meta.getString("name")
        series.cover = meta.optString("cover", "")
        series.mid = meta.getLong("mid")
        series.intro = meta.getString("description")
        series.total = meta.getString("total")

        return series
    }
}
