package com.RobinNotBad.BiliClient.api

import android.net.Uri

import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

import org.json.JSONObject

import java.util.Calendar
import java.util.HashMap
import java.util.TreeMap

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import java.io.IOException

/**
 * 被 luern0313 创建于 2019/8/25.
 * (人尽皆知的)绝 · 密 · 档 · 案
 * #以下代码修改自腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

object ConfInfoApi {


    /*
    这里是WBI签名校验
    https://socialsisteryi.github.io/bilibili-API-collect/docs/misc/sign/wbi.html#wbi-%E7%AD%BE%E5%90%8D%E7%AE%97%E6%B3%95
     */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52)

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getWBIRawKey(): String {
        val getJson = NetWorkUtil.getJson("https://api.bilibili.com/x/web-interface/nav")
        val wbi_img = getJson.getJSONObject("data").getJSONObject("wbi_img")  //不要被名称骗了，这玩意是签名用的
        val img_key = FileUtil.getFileFirstName(FileUtil.getFileNameFromLink(wbi_img.getString("img_url")))  //得到文件名
        val sub_key = FileUtil.getFileFirstName(FileUtil.getFileNameFromLink(wbi_img.getString("sub_url")))

        return img_key + sub_key  //相连
    }

    @JvmStatic
    fun getWBIMixinKey(raw_key: String): String {
        val key = StringBuilder()
        for (i in 0 until 32) {
            key.append(raw_key[MIXIN_KEY_ENC_TAB[i]])
        }

        return key.toString()
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun signWBI(url_query: String): String {
        val mixin_key: String?
        val curr = getDateCurr()
        if (SharedPreferencesUtil.getInt("last_wbi", 0) < curr) {    //限制一天一次
            Logu.d("检查WBI")
            SharedPreferencesUtil.putInt("last_wbi", curr)

            mixin_key = getWBIMixinKey(getWBIRawKey())
            SharedPreferencesUtil.putString("wbi_mixin_key", mixin_key)
        } else mixin_key = SharedPreferencesUtil.getString("wbi_mixin_key", "")

        val wts = (System.currentTimeMillis() / 1000).toString()
        val calc_str = sortUrlParams(Uri.encode(url_query, "@#&=*+-_.,:!?()/~'%") + "&wts=" + wts) + mixin_key
        Logu.d(calc_str)

        val w_rid = ToolsUtil.md5(calc_str)

        return url_query.toHttpUrlOrNull()!!.newBuilder().addQueryParameter("w_rid", w_rid).addQueryParameter("wts", wts).build().toString()
    }

    @JvmStatic
    fun sortUrlParams(url: String): String {
        var encodedParam = url.toHttpUrlOrNull()!!.encodedQuery
        if (encodedParam == null) encodedParam = ""
        // 解析URL参数
        val paramMap = HashMap<String, String>()
        val params = encodedParam.split("&".toRegex()).toTypedArray()
        for (param in params) {
            val keyValue = param.split("=".toRegex()).toTypedArray()
            if (keyValue.size == 2) {
                paramMap[keyValue[0]] = keyValue[1]
            } else if (keyValue.size == 1) {
                paramMap[keyValue[0]] = ""
            }
        }

        // 使用TreeMap对参数进行排序
        val sortedMap = TreeMap(paramMap)

        // 构建排序后的URL
        val sortedUrl = StringBuilder()
        var isFirst = true
        for (entry in sortedMap.entries) {
            if (!isFirst) {
                sortedUrl.append("&")
            } else {
                isFirst = false
            }
            sortedUrl.append(entry.key).append("=").append(entry.value)
        }

        return sortedUrl.toString()
    }


    @JvmStatic
    fun getDateCurr(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 10000 + calendar.get(Calendar.MONTH) * 100 + calendar.get(Calendar.DATE)
    }
}
