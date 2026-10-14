package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException

object RankingApi {

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getRanking(videoCardList: MutableList<VideoCard>, rid: Int, type: String?) {
        var url = "https://api.bilibili.com/x/web-interface/ranking/v2"
        url += NetWorkUtil.FormData().setUrlParam(true)
                .put("rid", rid)
                .put("type", type)
                .put("web_location", "333.934")

        val result = NetWorkUtil.getJson(ConfInfoApi.signWBI(url))

        if (result.has("data") && !result.isNull("data")) {
            val data = result.getJSONObject("data")
            if (data.has("list") && !data.isNull("list")) {
                val list = data.getJSONArray("list")
                for (i in 0 until list.length()) {
                    val card = list.getJSONObject(i)
                    val videoCard = VideoCard()
                    videoCard.aid = card.getLong("aid")
                    videoCard.bvid = card.getString("bvid")
                    videoCard.cover = card.getString("pic")
                    videoCard.title = card.getString("title")
                    videoCard.upName = card.getJSONObject("owner").getString("name")
                    videoCard.view = StringUtil.toWan(card.getJSONObject("stat").getLong("view")) + "观看"
                    videoCardList.add(videoCard)
                }
            }
        }
    }
}
