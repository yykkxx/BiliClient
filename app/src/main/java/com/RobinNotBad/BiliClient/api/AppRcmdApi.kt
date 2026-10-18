package com.RobinNotBad.BiliClient.api

import android.graphics.Bitmap

import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.QRCodeUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.io.UnsupportedEncodingException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Random
import java.util.TreeMap

/**
 * 移动端推荐逻辑（实验性功能）的账号与请求工具。
 *
 * 登录采用「电视端扫码登录」（passport-tv-login），与软件现有的 web 扫码登录完全独立：
 * 所有登录信息只写入 app_rcmd_ 前缀的独立键，不读写主登录（cookies/csrf/mid/refresh_token），
 * 退出/切换互不影响。
 *
 * 签名方式：appkey + 参数按字典序拼接 + appsec 后取 MD5（参照 PiliPlus 的 AppSign）。
 */
object AppRcmdApi {
    // android_hd（HD版）的 appkey/appsec
    private const val APP_KEY = "dfca71928277209b"
    private const val APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"

    private const val AUTH_CODE_URL = "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code"
    private const val POLL_URL = "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll"

    private const val APP_UA =
        "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2"

    private val buvidRandom = Random()

    private var authCode: String? = null

    /** 移动端推荐逻辑是否开启 */
    @JvmStatic
    fun isEnabled(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.APP_RCMD_ENABLED, false)
    }

    /** 是否已完成移动端登录（只看独立键，与 web 登录无关） */
    @JvmStatic
    fun isLogin(): Boolean {
        return getAccessToken().isNotEmpty()
    }

    @JvmStatic
    fun getAccessToken(): String {
        return SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_ACCESS_TOKEN, "") ?: ""
    }

    /** 移动端推荐专用 buvid：首次生成后持久化，保证推荐结果稳定 */
    @JvmStatic
    fun getBuvid(): String {
        var buvid = SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_BUVID, "")
        if (buvid.isNullOrEmpty()) {
            val sb = StringBuilder("XY")
            val hex = "0123456789ABCDEF"
            repeat(32) { sb.append(hex[buvidRandom.nextInt(16)]) }
            buvid = sb.toString()
            SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_BUVID, buvid)
        }
        return buvid
    }

    /**
     * 移动端请求公共头（buvid 识别设备）。不带 web 端的 Cookie，
     * 避免把 web 登录态发给 app 接口、也避免响应 Set-Cookie 污染主 Cookie 存储
     * （CookieSaveInterceptor 只在主客户端上，这里单独走 headers）。
     */
    @JvmStatic
    fun appHeaders(): ArrayList<String> {
        return arrayListOf(
            "buvid", getBuvid(),
            "env", "prod",
            "app-key", APP_KEY,
            "User-Agent", APP_UA,
            "x-bili-trace-id", "11111111111111111111111111111111:1111111111111111:0:0",
            "x-bili-aurora-eid", "",
            "x-bili-aurora-zone", ""
        )
    }

    /**
     * APP 端参数签名：appkey/ts/sign 追加进给定表单（对 app.bilibili.com 的请求都要签）。
     * 参照 PiliPlus 的 AppSign：参数按字典序拼接后 + appsec 取 MD5。
     */
    @Throws(UnsupportedEncodingException::class, NoSuchAlgorithmException::class)
    @JvmStatic
    fun sign(formData: NetWorkUtil.FormData): NetWorkUtil.FormData {
        formData.remove("sign")
        formData.put("appkey", APP_KEY)
        formData.put("ts", (System.currentTimeMillis() / 1000L).toString())

        val sorted = TreeMap<String, String>()
        // FormData 没有遍历接口，这里通过 toString 重新解析出键值对
        // （setUrlParam(true) 时开头会有个 '?'，要先去掉）
        val query = formData.toString().removePrefix("?")
        if (query.isNotEmpty()) {
            for (pair in query.split("&")) {
                val idx = pair.indexOf('=')
                if (idx > 0) sorted[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }

        val sb = StringBuilder()
        for ((key, value) in sorted) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(key).append('=').append(value)
        }
        sb.append(APP_SEC)

        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        val signSb = StringBuilder()
        for (b in digest) signSb.append(String.format("%02x", b))
        formData.put("sign", signSb.toString())
        return formData
    }

    /** 获取电视端扫码登录二维码（auth_code 流程） */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getLoginQR(): Bitmap {
        val formData = sign(NetWorkUtil.FormData()
            .put("local_id", "0")
            .put("mobi_app", "android_hd")
            .put("platform", "android"))

        val result = NetWorkUtil.post(AUTH_CODE_URL, formData.toString(), appHeaders()).body!!.string()
        val json = JSONObject(result)
        if (json.optInt("code", -1) != 0) {
            throw IOException("获取二维码失败：" + json.optString("message", "未知错误"))
        }
        val data = json.getJSONObject("data")
        authCode = data.getString("auth_code")
        return QRCodeUtil.createQRCodeBitmap(data.getString("url"), 320, 320)!!
    }

    /**
     * 轮询扫码状态。
     * @return data.code：86038=二维码失效 86090=已扫码未确认 0=登录成功
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getLoginState(): JSONObject {
        val code = authCode ?: throw IOException("尚未获取二维码")
        val formData = sign(NetWorkUtil.FormData()
            .put("auth_code", code)
            .put("local_id", "0"))

        val result = NetWorkUtil.post(POLL_URL, formData.toString(), appHeaders()).body!!.string()
        return JSONObject(result)
    }

    /**
     * 登录成功后保存移动端登录信息（只写独立键）。
     * 电视端协议的结构：token_info{access_token,refresh_token,mid} + cookie_info{cookies[]}。
     * @return 登录用户 mid；被风控（token_info 为空）时抛出 JSONException
     */
    @Throws(JSONException::class)
    @JvmStatic
    fun saveLogin(data: JSONObject): Long {
        val tokenInfo = data.optJSONObject("token_info")
            ?: throw JSONException("接口未返回身份信息，可能被风控，请尝试重新扫码")

        val accessToken = tokenInfo.optString("access_token", "")
        val refreshToken = tokenInfo.optString("refresh_token", "")
        val mid = tokenInfo.optLong("mid", 0L)

        // cookie_info 里的 cookie 仅拼成独立字符串，供移动端推荐请求使用
        val cookieSb = StringBuilder()
        val cookieInfo = data.optJSONObject("cookie_info")
        if (cookieInfo != null) {
            val cookies: JSONArray = cookieInfo.optJSONArray("cookies") ?: JSONArray()
            for (i in 0 until cookies.length()) {
                val c = cookies.optJSONObject(i) ?: continue
                val name = c.optString("name")
                val value = c.optString("value")
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    if (cookieSb.isNotEmpty()) cookieSb.append("; ")
                    cookieSb.append(name).append('=').append(value)
                }
            }
        }

        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_ACCESS_TOKEN, accessToken)
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_REFRESH_TOKEN, refreshToken)
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.APP_RCMD_MID, mid)
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_COOKIES, cookieSb.toString())
        return mid
    }

    /** 退出移动端登录：只清独立键，不动 web 登录与功能开关 */
    @JvmStatic
    fun logout() {
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_ACCESS_TOKEN, "")
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_REFRESH_TOKEN, "")
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.APP_RCMD_MID, 0L)
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_COOKIES, "")
    }
}
