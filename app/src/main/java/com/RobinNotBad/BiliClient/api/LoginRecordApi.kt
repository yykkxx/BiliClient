package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.LoginRecord
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList

object LoginRecordApi {

    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getLoginRecord(mid: Long, buvid: String?): List<LoginRecord> {
        var url = "https://api.bilibili.com/x/safecenter/login_notice?mid=" + mid
        if (buvid != null && !buvid.isEmpty()) {
            url += "&buvid=" + buvid
        }

        val result = NetWorkUtil.getJson(url)
        val records: MutableList<LoginRecord> = ArrayList()

        if (result.getInt("code") == 0) {
            val data = result.getJSONObject("data")
            val recordMid = data.getLong("mid")
            val deviceName = data.optString("device_name", "未知设备")
            val loginType = data.optString("login_type", "未知方式")
            val loginTime = data.optString("login_time", "")
            val location = data.optString("location", "未知位置")
            val ip = data.optString("ip", "")

            records.add(LoginRecord(recordMid, deviceName, loginType, loginTime, location, ip))
        }

        return records
    }
}
