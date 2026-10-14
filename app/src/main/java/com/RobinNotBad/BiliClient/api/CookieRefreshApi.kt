package com.RobinNotBad.BiliClient.api

import android.util.Base64

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONObject
import org.jsoup.Jsoup

import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Objects

import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.json.JSONException
import java.io.IOException

/*
这里是Cookie刷新逻辑
https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E8%8E%B7%E5%8F%96refresh-csrf
 */
object CookieRefreshApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun cookieInfo(): JSONObject {
        val url = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        val result = NetWorkUtil.getJson(url)
        return result.getJSONObject("data")
    }

    @JvmStatic
    fun getCorrespondPath(timestamp: Long): String { //时间戳要精准到毫秒！
        /*
        https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E7%94%9F%E6%88%90correspondpath%E7%AE%97%E6%B3%95
         */
        try {
            val publicKeyPEM = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB"
            val x509EncodedKeySpec = X509EncodedKeySpec(Base64.decode(publicKeyPEM, Base64.DEFAULT))
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(x509EncodedKeySpec)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            val oaepParameterSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParameterSpec)
            val data = "refresh_$timestamp"
            return base16Encode(cipher.doFinal(data.toByteArray()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    @Throws(IOException::class)
    @JvmStatic
    fun getRefreshCsrf(CorrespondPath: String): String {
        if (Objects.equals(CorrespondPath, "")) return ""
        val url = "https://www.bilibili.com/correspond/1/" + CorrespondPath
        val response = NetWorkUtil.get(url)
        if (response.body() != null) {
            val document = Jsoup.parse(response.body()!!.string())
            if (document.select("#1-name").size > 0)
                return document.select("#1-name").get(0).text()
            else return ""
        } else return ""
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun refreshCookie(RefreshCsrf: String): Boolean {
        //必须的参数不能为空
        if (Objects.equals(RefreshCsrf, "")) return false
        if (Objects.equals(SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""), ""))
            return false

        val cookies_old = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")

        //请求一个新的cookie
        val url = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        val args = "csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!) + "&refresh_csrf=" + RefreshCsrf + "&source=main_web&refresh_token=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "")
        val response = NetWorkUtil.post(url, args, NetWorkUtil.webHeaders)
        val result = JSONObject(response.body()!!.string())
        if (result.getInt("code") == 0) {
            val refreshToken_new = result.getJSONObject("data").getString("refresh_token")
            Logu.v("新的RefreshToken", refreshToken_new)

            val cookies_new = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!
            Logu.v("新的cookies", cookies_new)


            //使老的Cookie失效
            val confirmCode = JSONObject(NetWorkUtil.post("https://passport.bilibili.com/x/passport-login/web/confirm/refresh", "csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", cookies_new) + "&refresh_token=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""), NetWorkUtil.webHeaders).body()!!.string()).getInt("code")
            if (confirmCode != 0) { //必须要等确认更新Cookie成功，不然就无法完成Cookie的刷新
                Logu.e("Cookie刷新失败", "确认刷新时返回:$confirmCode")
                SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies_old)
                return false
            }
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, refreshToken_new)
            SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, NetWorkUtil.getInfoFromCookie("DedeUserID", cookies_new).toLong())
            SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", cookies_new))
            Logu.v("Cookie刷新成功")
            NetWorkUtil.refreshHeaders()
            return true
        } else {
            Logu.e("Cookie刷新失败", "刷新时返回:" + result.getInt("code"))
            return false
        }
    }

    @JvmStatic
    fun base16Encode(src: ByteArray): String {
        val strbuf = StringBuilder(src.size * 2)

        for (i in src.indices) {
            if ((src[i].toInt() and 0xff) < 0x10)
                strbuf.append("0")

            strbuf.append(java.lang.Long.toString((src[i].toInt() and 0xff).toLong(), 16))
        }

        return strbuf.toString()
    }
}
