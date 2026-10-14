package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONObject

import java.util.regex.Matcher
import java.util.regex.Pattern
import org.json.JSONException
import java.io.IOException

/*
创作中心
 */

object CreativeCenterApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getVideoStat(): JSONObject? {
        val url = "https://member.bilibili.com/x/web/index/stat"
        val result = NetWorkUtil.getJson(url)
        return result.optJSONObject("data")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getBeUPTime(): ApiResult {
        val url = "https://member.bilibili.com/x/web/index/scrolls"
        val response = NetWorkUtil.getJson(url)

        val result = ApiResult(response)
        try {
            val data = response.getJSONObject("data")
            val scrollsArray = data.getJSONArray("scrolls")
            val scroll = scrollsArray.getJSONObject(0)
            val name = scroll.optString("name")
            result.result = extractNumber(name).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            result.result = "获取失败"
        }
        return result
    }

    private fun extractNumber(str: String?): Int {
        if (str == null || str.isEmpty()) {
            return 0
        }

        val pattern = Pattern.compile("-?\\d+")
        val matcher = pattern.matcher(str)

        if (matcher.find()) {
            try {
                return matcher.group().toInt()
            } catch (ex: NumberFormatException) {
                return 0
            }
        }
        return 0
    }
}
