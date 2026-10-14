package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException

object HistoryApi {

    /**
     * 上传历史记录
     *
     * @param aid      视频aid
     * @param cid      分集cid
     * @param progress 观看进度，单位为s
     */
    @Throws(IOException::class)
    @JvmStatic
    fun reportHistory(aid: Long, cid: Long, progress: Long) {
        val url = "https://api.bilibili.com/x/v2/history/report"
        val per = "aid=" + aid + "&cid=" + cid +
                "&progress=" + (if (progress >= 0) progress else "") +
                "&platform=pc" +
                "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "")
        NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)
    }

    /**
     * 获取视频历史记录
     *
     * @param lastResult 上一次获取返回的ApiResult，如果是第一次就传入新对象
     * @param videoList  已有的视频列表
     * @return 新的ApiResult，包含了返回码、文本信息以及翻页所需的offset
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getHistory(lastResult: ApiResult, videoList: MutableList<VideoCard>): ApiResult {
        val url = "https://api.bilibili.com/x/web-interface/history/cursor?type=archive&view_at=" + lastResult.timestamp + "&business=" + lastResult.business + "&max=" + lastResult.offset
        val result = NetWorkUtil.getJson(url)
        val apiResult = ApiResult(result)
        if (!result.isNull("data")) {
            val data = result.getJSONObject("data")
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val videoCard = list.getJSONObject(i)
                val title = videoCard.getString("title")
                val cover = videoCard.getString("cover")
                val upName = videoCard.getString("author_name")
                val progress = videoCard.getInt("progress")

                val history = videoCard.getJSONObject("history")
                val aid = history.getLong("oid")
                val bvid = history.getString("bvid")

                val viewStr: String
                if (progress == 0) viewStr = "还没看过"
                else viewStr = "看到" + StringUtil.toTime(videoCard.getInt("progress"))

                videoList.add(VideoCard(title, upName, viewStr, cover, aid, bvid))
            }
            if (list.length() == 0) apiResult.isBottom = true

            val cursor = data.getJSONObject("cursor")
            apiResult.business = cursor.optString("business")
            apiResult.offset = cursor.optLong("max")
            apiResult.timestamp = cursor.optLong("view_at")
        }
        return apiResult
    }

}
