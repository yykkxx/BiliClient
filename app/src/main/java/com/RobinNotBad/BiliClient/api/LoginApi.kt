package com.RobinNotBad.BiliClient.api

import android.graphics.Bitmap

import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.QRCodeUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException

import okhttp3.Response

/**
 * Created by liupe on 2018/10/6.
 * 各位大佬好
 * #以下代码修改自腕上哔哩的开源项目，感谢开源者做出的贡献！
 */

object LoginApi {
    private var oauthKey: String? = null

    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getLoginQR(): Bitmap {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header&go_url=https:%2F%2Fwww.bilibili.com%2F"
        val loginUrlJson = NetWorkUtil.getJson(url, CookiesApi.genWebHeaders()).getJSONObject("data")
        oauthKey = loginUrlJson.getString("qrcode_key")
        return QRCodeUtil.createQRCodeBitmap(loginUrlJson.getString("url"), 320, 320)!!
    }

    @Throws(IOException::class)
    @JvmStatic
    fun getLoginState(): Response {
        return NetWorkUtil.get("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?source=main-fe-header&qrcode_key=" + oauthKey, CookiesApi.genWebHeaders())
    }

    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun requestSSOs() {
        val listUrl = "https://passport.bilibili.com/x/passport-login/web/sso/list"
        val listResult = JSONObject(NetWorkUtil.post(listUrl, NetWorkUtil.FormData().put("csrf", SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "")).toString()).body()!!.string())
        if (listResult.has("data") && !listResult.isNull("data")) {
            val sso = listResult.getJSONObject("data").getJSONArray("sso")
            for (i in 0 until sso.length()) {
                NetWorkUtil.post(sso.getString(i), "")
            }
        }
    }

}
