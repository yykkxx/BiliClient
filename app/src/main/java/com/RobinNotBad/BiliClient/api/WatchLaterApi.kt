package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList

//稍后再看API
//2023-08-17

object WatchLaterApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getWatchLaterList(): ArrayList<VideoCard> {
        val url = "https://api.bilibili.com/x/v2/history/toview/web"

        val result = NetWorkUtil.getJson(url)
        val data = result.getJSONObject("data")


        val videoCardList = ArrayList<VideoCard>()
        if (!data.isNull("list")) {
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val videoCard = list.getJSONObject(i)
                val aid = videoCard.getLong("aid")
                val bvid = videoCard.getString("bvid")
                val title = videoCard.getString("title")
                val cover = videoCard.getString("pic")
                val upName = videoCard.getJSONObject("owner").getString("name")
                val view = videoCard.getJSONObject("stat").getLong("view")
                val viewStr = StringUtil.toWan(view) + "观看"
                videoCardList.add(VideoCard(title, upName, viewStr, cover, aid, bvid))
            }
        }
        return videoCardList
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun delete(aid: Long): Int {
        val url = "https://api.bilibili.com/x/v2/history/toview/del"
        val per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)

        val result = JSONObject(response.body()!!.string())

        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun add(aid: Long): Int {
        val url = "https://api.bilibili.com/x/v2/history/toview/add"
        val per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)

        val result = JSONObject(response.body()!!.string())

        return result.getInt("code")
    }
}
