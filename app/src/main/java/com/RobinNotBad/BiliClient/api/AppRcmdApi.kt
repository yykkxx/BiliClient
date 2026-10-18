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
 * 支持 HD / TV / 手机 三种客户端身份（可在设置页切换）：
 * - HD（默认）：android_hd 密钥扫码登录；推荐请求沿用 PiliPlus 的 android_i 参数组合；
 * - TV：android_tv 密钥（云视听小电视）扫码登录 + 推荐；
 * - 手机：推荐请求用 android 密钥签名（与官方手机客户端一致）；
 *   登录优先「web Cookie 静默转换」（软件已 web 登录时一键转 access_token，无需扫码），
 *   转换失败（风控等）保底走 TV 密钥扫码登录。
 *
 * 扫码统一走 passport-tv-login 协议（APP 端通用扫码通道，身份由密钥决定）；
 * cookie 静默转换参照 PiliPala：auth_code + h5/qrcode/confirm(csrf) + poll。
 *
 * 登录信息与软件的 web 登录完全独立：只读写 app_rcmd_ 前缀独立键。
 */
object AppRcmdApi {
    //身份常量
    const val IDENTITY_HD = "hd"
    const val IDENTITY_TV = "tv"
    const val IDENTITY_PHONE = "phone"

    private const val AUTH_CODE_URL = "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code"
    private const val AUTH_CODE_URL_BAK = "https://passport.snm0516.aisee.tv/x/passport-tv-login/qrcode/auth_code"
    private const val POLL_URL = "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll"
    private const val CONFIRM_URL = "https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/confirm"

    private val buvidRandom = Random()

    private var authCode: String? = null

    /**
     * 客户端身份：一套 appkey/appsec、登录参数、UA 与推荐请求参数。
     * scanKey = 扫码登录用密钥（手机端 android appkey 不被扫码接口接受，借 TV 密钥）；
     * feedKey = 推荐请求签名密钥。
     */
    class RcmdIdentity(
        @JvmField val id: String,
        @JvmField val displayName: String,
        /** 扫码登录密钥 */
        @JvmField val scanAppKey: String,
        @JvmField val scanAppSec: String,
        /** 推荐请求签名密钥（可与扫码密钥不同，如手机端） */
        @JvmField val feedAppKey: String,
        @JvmField val feedAppSec: String,
        /** app-key 请求头的值（平台名） */
        @JvmField val appKeyHeader: String,
        /** 扫码登录用的 mobi_app */
        @JvmField val loginMobiApp: String,
        @JvmField val ua: String,
        /** 推荐请求的 mobi_app */
        @JvmField val feedMobiApp: String,
        @JvmField val feedBuild: Int,
        @JvmField val feedColumn: Int,
        @JvmField val feedDeviceName: String,
        @JvmField val feedDevice: String,
        @JvmField val feedFlush: Int,
        /** 是否支持 web cookie 静默转换（手机端） */
        @JvmField val supportCookieConvert: Boolean
    )

    //HD版（android_hd）：本功能自 v5.0.0-alpha 以来的默认身份
    private val HD_IDENTITY = RcmdIdentity(
        IDENTITY_HD, "HD版",
        "dfca71928277209b", "b5475a8825547a4fc26c7d518eaaa02e",
        "dfca71928277209b", "b5475a8825547a4fc26c7d518eaaa02e",
        "android_hd",
        "android_hd",
        "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2",
        "android_i", 8430300, 2, "android", "phone", 8, false)

    //TV版（android_tv / 云视听小电视）
    private val TV_IDENTITY = RcmdIdentity(
        IDENTITY_TV, "TV版",
        "4409e2ce8ffd12b8", "59b43e04ad6965f34319062b478f83dd",
        "4409e2ce8ffd12b8", "59b43e04ad6965f34319062b478f83dd",
        "android_tv",
        "android_tv",
        "Mozilla/5.0 BiliDroid/1.9.4 (bbcallen@gmail.com) os/android model/android_tv mobi_app/android_tv build/102801 channel/master innerVer/102801 osVer/15 network/2",
        "android_tv", 102801, 2, "android_tv", "phone", 8, false)

    //手机版（android）：推荐请求用 android 密钥（与官方手机客户端一致）；
    //扫码登录借 TV 密钥（android appkey 不被扫码接口接受），并支持 web cookie 静默转换
    private val PHONE_IDENTITY = RcmdIdentity(
        IDENTITY_PHONE, "手机版",
        "4409e2ce8ffd12b8", "59b43e04ad6965f34319062b478f83dd",
        "1d8b6e7d45225436", "560c52ccd288fed045859ed18bffd973",
        "android",
        "android_tv",
        "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2",
        "android", 8430300, 2, "android", "phone", 8, true)

    /** 当前选中的客户端身份（默认 HD，与旧版本行为一致） */
    @JvmStatic
    fun getIdentity(): RcmdIdentity {
        return when (SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_IDENTITY, IDENTITY_HD)) {
            IDENTITY_TV -> TV_IDENTITY
            IDENTITY_PHONE -> PHONE_IDENTITY
            else -> HD_IDENTITY
        }
    }

    /** 移动端推荐逻辑是否开启 */
    @JvmStatic
    fun isEnabled(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.APP_RCMD_ENABLED, false)
    }

    /**
     * 是否已完成当前身份的移动端登录。
     * access_token 由 TV 密钥签发（HD/TV/手机共用 TV 扫码协议），跨身份通用，
     * 但为避免歧义仍按身份隔离记录；旧版本（仅 HD）升级上来按 HD 处理。
     */
    @JvmStatic
    fun isLogin(): Boolean {
        if (getAccessToken().isEmpty()) return false
        val tokenIdentity = (SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_TOKEN_IDENTITY, "") ?: "")
            .ifEmpty { IDENTITY_HD }
        return getIdentity().id == tokenIdentity
    }

    /** 是否存有任意 token（判断要不要提示） */
    @JvmStatic
    fun hasAnyToken(): Boolean {
        return getAccessToken().isNotEmpty()
    }

    @JvmStatic
    fun getAccessToken(): String {
        return SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_ACCESS_TOKEN, "") ?: ""
    }

    /** 移动端推荐专用 buvid：首次生成后持久化（三种身份共用） */
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
     * 移动端请求公共头（按当前身份生成）。
     * 不带 web Cookie：登录态由 access_key 表达。
     */
    @JvmStatic
    fun appHeaders(): ArrayList<String> {
        val identity = getIdentity()
        return arrayListOf(
            "buvid", getBuvid(),
            "env", "prod",
            "app-key", identity.appKeyHeader,
            "User-Agent", identity.ua,
            "x-bili-trace-id", "11111111111111111111111111111111:1111111111111111:0:0",
            "x-bili-aurora-eid", "",
            "x-bili-aurora-zone", ""
        )
    }

    /**
     * APP 端参数签名：appkey/ts/sign（参数按字典序拼接 + appsec 取 MD5）。
     * 推荐请求用当前身份的 feed 密钥签名。
     */
    @Throws(UnsupportedEncodingException::class, NoSuchAlgorithmException::class)
    @JvmStatic
    fun sign(formData: NetWorkUtil.FormData): NetWorkUtil.FormData {
        val identity = getIdentity()
        return signWith(formData, identity.feedAppKey, identity.feedAppSec)
    }

    @Throws(UnsupportedEncodingException::class, NoSuchAlgorithmException::class)
    private fun signWith(formData: NetWorkUtil.FormData, appKey: String, appSec: String): NetWorkUtil.FormData {
        formData.remove("sign")
        formData.put("appkey", appKey)
        formData.put("ts", (System.currentTimeMillis() / 1000L).toString())

        val sorted = TreeMap<String, String>()
        // FormData 没有遍历接口，通过 toString 重新解析（setUrlParam(true) 开头有 '?'）
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
        sb.append(appSec)

        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        val signSb = StringBuilder()
        for (b in digest) signSb.append(String.format("%02x", b))
        formData.put("sign", signSb.toString())
        return formData
    }

    /** 获取扫码登录二维码（auth_code 流程，用当前身份的扫码密钥） */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getLoginQR(): Bitmap {
        val identity = getIdentity()
        val formData = signWith(NetWorkUtil.FormData()
            .put("local_id", "0")
            .put("mobi_app", identity.loginMobiApp)
            .put("platform", "android"), identity.scanAppKey, identity.scanAppSec)

        //主域名优先，失败退 snm0516 备用域名（PiliPala 用的就是这个）
        val result = try {
            NetWorkUtil.post(AUTH_CODE_URL, formData.toString(), appHeaders()).body!!.string()
        } catch (e: IOException) {
            NetWorkUtil.post(AUTH_CODE_URL_BAK, formData.toString(), appHeaders()).body!!.string()
        }
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
     * @return code：86038=二维码失效 86039=待扫码 86061/86090=已扫码未确认 0=登录成功
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getLoginState(): JSONObject {
        val identity = getIdentity()
        val code = authCode ?: throw IOException("尚未获取二维码")
        val formData = signWith(NetWorkUtil.FormData()
            .put("auth_code", code)
            .put("local_id", "0"), identity.scanAppKey, identity.scanAppSec)

        val result = NetWorkUtil.post(POLL_URL, formData.toString(), appHeaders()).body!!.string()
        return JSONObject(result)
    }

    /**
     * 手机端：用软件已有的 web 登录（Cookie + csrf）静默换 access_token，无需扫码。
     * 参照 PiliPala 的 cookieToKey：auth_code → h5/qrcode/confirm(csrf) → poll。
     * 只读 web 登录信息做转换，不写任何 web 键。
     * @return mid；未 web 登录/转换失败抛 IOException
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun convertWebLogin(): Long {
        //需要软件本身已 web 登录
        val cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        val csrf = SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "") ?: ""
        if (cookies.isEmpty() || csrf.isEmpty()) {
            throw IOException("软件尚未登录web账号，无法转换；请先用扫码登录")
        }

        val identity = getIdentity()

        //1. 用扫码密钥申请 auth_code（未确认状态）
        val formData = signWith(NetWorkUtil.FormData()
            .put("local_id", "0")
            .put("mobi_app", identity.loginMobiApp)
            .put("platform", "android"), identity.scanAppKey, identity.scanAppSec)
        val authResult = NetWorkUtil.post(AUTH_CODE_URL, formData.toString(), appHeaders()).body!!.string()
        val authJson = JSONObject(authResult)
        if (authJson.optInt("code", -1) != 0) {
            throw IOException("申请授权码失败：" + authJson.optString("message", "未知错误"))
        }
        val authCodeStr = authJson.getJSONObject("data").getString("auth_code")

        //2. web cookie + csrf 自动确认授权码（这一步把 web 登录态授给该 auth_code）
        val confirmData = NetWorkUtil.FormData()
            .put("auth_code", authCodeStr)
            .put("build", 708200)
            .put("csrf", csrf)
        val webHeaders = NetWorkUtil.webHeaders
        val confirmResult = NetWorkUtil.post(CONFIRM_URL, confirmData.toString(), webHeaders).body!!.string()
        val confirmJson = JSONObject(confirmResult)
        if (confirmJson.optInt("code", -1) != 0) {
            throw IOException("授权确认失败：" + confirmJson.optString("message", "未知错误"))
        }

        //3. poll 拿 access_token（与扫码登录同一条收尾路径）
        val pollForm = signWith(NetWorkUtil.FormData()
            .put("auth_code", authCodeStr)
            .put("local_id", "0"), identity.scanAppKey, identity.scanAppSec)
        val pollResult = NetWorkUtil.post(POLL_URL, pollForm.toString(), appHeaders()).body!!.string()
        val pollJson = JSONObject(pollResult)
        if (pollJson.optInt("code", -1) != 0 || pollJson.optJSONObject("data") == null) {
            throw IOException("获取令牌失败：" + pollJson.optString("message", "未知错误"))
        }
        return saveLogin(pollJson.getJSONObject("data"))
    }

    /**
     * 登录成功后保存移动端登录信息（只写独立键），并记录 token 归属的身份。
     * 响应结构：token_info{access_token,refresh_token,mid} + cookie_info{cookies[]}。
     * @return 登录用户 mid；被风控（token_info 为空）时抛出 JSONException
     */
    @Throws(JSONException::class)
    @JvmStatic
    fun saveLogin(data: JSONObject): Long {
        val tokenInfo = data.optJSONObject("token_info")
            ?: throw JSONException("接口未返回身份信息，可能被风控，请尝试重新登录")

        val accessToken = tokenInfo.optString("access_token", "")
        val refreshToken = tokenInfo.optString("refresh_token", "")
        val mid = tokenInfo.optLong("mid", 0L)

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
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_TOKEN_IDENTITY, getIdentity().id)
        return mid
    }

    /** 退出移动端登录：只清独立键，不动 web 登录与功能开关 */
    @JvmStatic
    fun logout() {
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_ACCESS_TOKEN, "")
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_REFRESH_TOKEN, "")
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.APP_RCMD_MID, 0L)
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_COOKIES, "")
        SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_TOKEN_IDENTITY, "")
    }
}
