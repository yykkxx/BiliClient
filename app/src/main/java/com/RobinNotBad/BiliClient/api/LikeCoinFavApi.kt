package com.RobinNotBad.BiliClient.api

import android.util.Log

import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException

object LikeCoinFavApi {

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun triple(aid: Long): Int {
        val url = "https://api.bilibili.com/x/web-interface/archive/like/triple"
        val per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-三联", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun like(aid: Long, likeState: Int): Int {  //likeState 1点赞0取消
        val url = "https://api.bilibili.com/x/web-interface/archive/like"
        val per = "aid=" + aid + "&like=" + likeState + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-点赞", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun coin(aid: Long, multiply: Int): Int {
        val url = "https://api.bilibili.com/x/web-interface/coin/add"
        val per = "aid=" + aid + "&multiply=" + multiply + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-投币", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun favorite(aid: Long, fid: Long): Int {
        val strMid = SharedPreferencesUtil.getLong("mid", 0).toString()
        val addFid = fid.toString() + strMid.substring(strMid.length - 2)
        val url = "https://api.bilibili.com/medialist/gateway/coll/resource/deal"
        val per = "rid=" + aid + "&type=2&add_media_ids=" + addFid + "&del_media_ids=&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-添加收藏", result.toString())
        return result.getInt("code")
    }

    @JvmStatic
    fun getVideoStats(videoInfo: VideoInfo): ApiResult {
        val apiResult = ApiResult()
        if (SharedPreferencesUtil.getLong("mid", 0) == 0L) return apiResult
        try {
            val url = "https://api.bilibili.com/x/web-interface/archive/relation?aid=" + videoInfo.aid
            val result = NetWorkUtil.getJson(url)
            apiResult.fromJson(result)
            val data = result.getJSONObject("data")
            videoInfo.stats!!.followed = data.optBoolean("attention")
            videoInfo.stats!!.liked = data.optBoolean("like")
            videoInfo.stats!!.disliked = data.optBoolean("dislik")
            videoInfo.stats!!.favoured = data.optBoolean("favorite")
            videoInfo.stats!!.coined = data.optInt("coin")
        } catch (e: Exception) {
            MsgUtil.err(apiResult.message, e)
        }
        return apiResult
    }

}
