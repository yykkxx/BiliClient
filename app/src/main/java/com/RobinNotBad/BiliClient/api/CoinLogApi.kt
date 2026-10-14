package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.CoinLog
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList
import org.json.JSONException
import java.io.IOException

object CoinLogApi {

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getCoinLog(): List<CoinLog> {
        val url = "https://api.bilibili.com/x/member/web/coin/log"
        val result = NetWorkUtil.getJson(url)
        val logs = ArrayList<CoinLog>()

        if (result.getInt("code") == 0) {
            val data = result.getJSONObject("data")
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val time = item.getString("time")
                val delta = item.getInt("delta")
                val reason = item.getString("reason")
                logs.add(CoinLog(time, delta, reason))
            }
        }

        return logs
    }
}
