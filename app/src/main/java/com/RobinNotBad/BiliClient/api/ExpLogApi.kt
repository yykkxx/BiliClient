package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ExpLog
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList
import org.json.JSONException
import java.io.IOException

object ExpLogApi {

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getExpLog(): List<ExpLog> {
        val url = "https://api.bilibili.com/x/member/web/exp/log?jsonp=jsonp&web_location=333.33"
        val result = NetWorkUtil.getJson(url)
        val logs = ArrayList<ExpLog>()

        if (result.getInt("code") == 0) {
            val data = result.getJSONObject("data")
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val delta = item.getInt("delta")
                val time = item.getString("time")
                val reason = item.getString("reason")
                logs.add(ExpLog(delta, time, reason))
            }
        }

        return logs
    }
}
