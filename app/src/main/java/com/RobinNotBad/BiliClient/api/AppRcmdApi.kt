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
 * v5.0.0 账号登录体系重构后的规则：
 * - **四派完全独立**：Web（软件本体的 Cookie 登录）与 HD / TV / 手机三端各自成一派；
 * - **禁止一切跨端转移**：web Cookie 不能转换成任何端 token（原「手机版 web 账号一键
 *   转换」已按需求移除），三端之间也不共用 / 不转移 token；
 * - **三端可同时登录**：每个身份有独立 token 槽位（app_rcmd_{hd|tv|phone}_*），
 *   可以同时扫码登录 HD、TV、手机三端，切换身份只是切换“当前用哪一派”，
 *   不再要求重新登录（各槽位的登录状态各自保留）；
 * - 每端只能用自己这一端的扫码协议登录（HD=android_hd 密钥，TV=android_tv 密钥，
 *   手机端扫码借 TV 密钥签发——android appkey 不被扫码接口接受，但 token 存在
 *   手机端独立槽位，不与 TV 共用）。
 *
 * 老版本（v5.0.0-alpha2 及以前）升级：旧版只有一个共享 token（app_rcmd_access_token）
 * 加一个身份标记（app_rcmd_token_identity）——升级后自动迁移到对应身份的独立槽位
 * （无标记按 HD 处理），迁移完成后清掉旧键，用户体验无缝衔接。
 *
 * 扫码统一走 passport-tv-login 协议（APP 端通用扫码通道，身份由密钥决定）。
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
        @JvmField val feedFlush: Int
    )

    //HD版（android_hd）：本功能自 v5.0.0-alpha 以来的默认身份
    private val HD_IDENTITY = RcmdIdentity(
        IDENTITY_HD, "HD版",
        "dfca71928277209b", "b5475a8825547a4fc26c7d518eaaa02e",
        "dfca71928277209b", "b5475a8825547a4fc26c7d518eaaa02e",
        "android_hd",
        "android_hd",
        "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2",
        "android_i", 8430300, 2, "android", "phone", 8)

    //TV版（android_tv / 云视听小电视）
    private val TV_IDENTITY = RcmdIdentity(
        IDENTITY_TV, "TV版",
        "4409e2ce8ffd12b8", "59b43e04ad6965f34319062b478f83dd",
        "4409e2ce8ffd12b8", "59b43e04ad6965f34319062b478f83dd",
        "android_tv",
        "android_tv",
        "Mozilla/5.0 BiliDroid/1.9.4 (bbcallen@gmail.com) os/android model/android_tv mobi_app/android_tv build/102801 channel/master innerVer/102801 osVer/15 network/2",
        "android_tv", 102801, 2, "android_tv", "phone", 8)

    //手机版（android）：推荐请求用 android 密钥（与官方手机客户端一致）；
    //扫码登录借 TV 密钥签发（android appkey 不被扫码接口接受），
    //但 token 存进手机端独立槽位，不与 TV 端共用（禁止跨端转移）
    private val PHONE_IDENTITY = RcmdIdentity(
        IDENTITY_PHONE, "手机版",
        "4409e2ce8ffd12b8", "59b43e04ad6965f34319062b478f83dd",
        "1d8b6e7d45225436", "560c52ccd288fed045859ed18bffd973",
        "android",
        "android_tv",
        "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2",
        "android", 8430300, 2, "android", "phone", 8)

    /**
     * 当前选中的客户端身份（默认 HD，与旧版本行为一致）。
     * 读取时做一次归一化（去空格/转小写），避免历史版本写入了大小写不一致的值后
     * 永远回退成 HD——那会表现为「不管选哪端，当前身份都显示 HD」。
     */
    @JvmStatic
    fun getIdentity(): RcmdIdentity {
        val raw = (SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_IDENTITY, IDENTITY_HD) ?: "")
                .trim()
                .lowercase()
        return when (raw) {
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
     * 是否已完成当前身份的移动端登录（v5.0.0 起：各身份独立槽位，互不影响）。
     */
    @JvmStatic
    fun isLogin(): Boolean {
        return getAccessToken().isNotEmpty()
    }

    /** 是否存有任意 token（判断要不要提示） */
    @JvmStatic
    fun hasAnyToken(): Boolean {
        return getAccessToken(IDENTITY_HD).isNotEmpty()
                || getAccessToken(IDENTITY_TV).isNotEmpty()
                || getAccessToken(IDENTITY_PHONE).isNotEmpty()
    }

    /** 当前身份的 access_token */
    @JvmStatic
    fun getAccessToken(): String {
        return getAccessToken(getIdentity().id)
    }

    /** 指定身份的 access_token（身份槽位独立，三端可同时登录） */
    @JvmStatic
    fun getAccessToken(identityId: String): String {
        ensureMigrated()
        return SharedPreferencesUtil.getString(keyOf(identityId, "access_token"), "") ?: ""
    }

    /** 指定身份是否已登录（不切换当前身份） */
    @JvmStatic
    fun isIdentityLogin(identityId: String): Boolean {
        return getAccessToken(identityId).isNotEmpty()
    }

    /** 指定身份的登录 mid（未登录返回 0） */
    @JvmStatic
    fun getIdentityMid(identityId: String): Long {
        ensureMigrated()
        return SharedPreferencesUtil.getLong(keyOf(identityId, "mid"), 0L)
    }

    /** 槽位键名：app_rcmd_{identity}_{suffix} */
    private fun keyOf(identityId: String, suffix: String): String {
        return "app_rcmd_" + identityId + "_" + suffix
    }

    /**
     * 老版本迁移（只做一次）：
     * 旧版只有一个共享 token（app_rcmd_access_token 等）+ 身份标记
     * （app_rcmd_token_identity，无标记视为 HD）。迁移 = 把旧值搬进对应身份的
     * 独立槽位，然后清空旧键。新装用户旧键本来就是空的，等价于无事发生。
     */
    @Volatile
    private var migrated = false

    /** 供启动时主动触发迁移用（幂等） */
    @JvmStatic
    fun touchMigration() {
        ensureMigrated()
    }

    private fun ensureMigrated() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            try {
                val oldToken = SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_ACCESS_TOKEN, "") ?: ""
                if (oldToken.isNotEmpty()) {
                    val identityId = (SharedPreferencesUtil.getString(
                            SharedPreferencesUtil.APP_RCMD_TOKEN_IDENTITY, "") ?: "")
                        .ifEmpty { IDENTITY_HD }
                    // 只认可三种身份，其余按 HD
                    val target = if (identityId == IDENTITY_TV || identityId == IDENTITY_PHONE) identityId else IDENTITY_HD

                    SharedPreferencesUtil.putString(keyOf(target, "access_token"), oldToken)
                    SharedPreferencesUtil.putString(keyOf(target, "refresh_token"),
                            SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_REFRESH_TOKEN, "") ?: "")
                    SharedPreferencesUtil.putLong(keyOf(target, "mid"),
                            SharedPreferencesUtil.getLong(SharedPreferencesUtil.APP_RCMD_MID, 0L))
                    SharedPreferencesUtil.putString(keyOf(target, "cookies"),
                            SharedPreferencesUtil.getString(SharedPreferencesUtil.APP_RCMD_COOKIES, "") ?: "")

                    // 清掉旧键，标记迁移完成（用旧 token_identity 键存 "migrated" 哨兵）
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_ACCESS_TOKEN, "")
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_REFRESH_TOKEN, "")
                    SharedPreferencesUtil.putLong(SharedPreferencesUtil.APP_RCMD_MID, 0L)
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_COOKIES, "")
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_TOKEN_IDENTITY, "migrated")
                } else {
                    SharedPreferencesUtil.putString(SharedPreferencesUtil.APP_RCMD_TOKEN_IDENTITY, "migrated")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            migrated = true
        }
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

    // v5.0.0 重构：原「手机版 web 账号一键转换」（convertWebLogin）已按需求移除——
    // 登录体系重构后禁止任何跨端转移：web Cookie 不能转换成任何外部端 token，
    // 三端之间也不能互转。每端只能用自己这一端的扫码协议登录。


    /**
     * 登录成功后保存移动端登录信息（写入**当前身份**的独立槽位，不碰其他端）。
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

        val identityId = getIdentity().id
        SharedPreferencesUtil.putString(keyOf(identityId, "access_token"), accessToken)
        SharedPreferencesUtil.putString(keyOf(identityId, "refresh_token"), refreshToken)
        SharedPreferencesUtil.putLong(keyOf(identityId, "mid"), mid)
        SharedPreferencesUtil.putString(keyOf(identityId, "cookies"), cookieSb.toString())
        return mid
    }

    /** 退出移动端登录：只清**当前身份**槽位，其他端登录态与 web 登录、功能开关都不动 */
    @JvmStatic
    fun logout() {
        logoutIdentity(getIdentity().id)
    }

    /** 退出指定身份的登录（不影响其他端） */
    @JvmStatic
    fun logoutIdentity(identityId: String) {
        SharedPreferencesUtil.putString(keyOf(identityId, "access_token"), "")
        SharedPreferencesUtil.putString(keyOf(identityId, "refresh_token"), "")
        SharedPreferencesUtil.putLong(keyOf(identityId, "mid"), 0L)
        SharedPreferencesUtil.putString(keyOf(identityId, "cookies"), "")
    }
}
