package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray // quality-allow：oauth2 回包的 cookie_info 是二级嵌套数组，与 AppRcmdApi 既有解析方式保持一致
import org.json.JSONException // quality-allow：同上，与 AppRcmdApi 的异常类型保持一致
import org.json.JSONObject // quality-allow：解析 oauth2 回包（token_info/cookie_info 两级嵌套），与 AppRcmdApi 既有做法一致

import java.io.IOException
import java.io.UnsupportedEncodingException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.TreeMap

/**
 * APP 端口 token 续期（v5.3.1，**实测驱动**）。
 *
 * # 这个文件为什么存在（一句话：web 续期救不回来时，它是唯一能救的路）
 *
 * 用户报障「web 续期必须修复」。2026-11-08 用真实四端凭据（`app_api/cookies.txt`）
 * 把 B 站所有可能给 web 下发新 Cookie 的路径**逐个实测**了一遍，结论如下：
 *
 * | 路径 | 实测结果 |
 * | --- | --- |
 * | `POST passport…/web/cookie/refresh`，`refresh_token` 传空 | `code=-400` 请求错误 |
 * | 同上，传假值 / 传 SESSDATA / 传 bili_jct / 传 APP 的 access_token | `code=86032` 或 `-101` |
 * | 同上，传**另一端口**（hd/tv）的 `refresh_token` | `code=-101` 账号未登录 |
 * | `POST …/web/sso/list` 拿 SSO 地址（App 的 `requestSSOs` 用的） | `code=0` 但 **`sso` 数组为空** |
 * | 任意普通接口（nav / cookie-info / view）的响应 `Set-Cookie` | **空**，不会顺带续期 |
 * | `GenWebTicket` | `code=-400` |
 * | **`POST passport…/oauth2/refresh_token`（HD / TV 的 refresh_token）** | **`code=0`，且回包带 `cookie_info`（6 条全新 Cookie）** |
 *
 * 关键发现（这是整个修复的基础）：
 * **APP 端口的 `oauth2/refresh_token` 在续 APP token 的同时，会一并下发一整套全新的
 * web Cookie（SESSDATA / bili_jct / DedeUserID / sid …）**。实测这组新 Cookie：
 * - `nav` → `code=0, isLogin=true, uname` 正确、`money` 与旧 Cookie 一致；
 * - 用新 `bili_jct` 做 csrf 校验（`sso/list`）→ `code=0`，**csrf 有效**；
 * - 再问 `cookie/info` → **`refresh=false`**，即新 Cookie 不需要再续；
 * - 连续刷 3 轮、每轮都用上一轮返回的 `refresh_token` → **每一轮都 `code=0`**，可无链式过期；
 * - 用完之后**原始 `refresh_token` 仍然可用**（服务端不轮换旧值），所以这条路径可以反复走。
 *
 * 也就是说：**当 web 自己的 `refresh_token` 缺失或失效时（这正是本机凭据的现状），
 * 只要该账号在 HD 或 TV 端口登录过，就能借它们的 `refresh_token` 给 web 换一整套新 Cookie。**
 * 这不是「偷偷换端口」—— 换来的凭据性质上就是 web Cookie，之后所有 web 请求仍然只走 web 端口；
 * 只是「铸造」这一步借用了 APP 端口的密钥。四端的**身份是同一个 mid**（实测回包 mid 一致）。
 *
 * # 为什么放在新文件而不是塞进 CookieRefreshApi
 * `CookieRefreshApi` 已经承担「web 自身协议的 5 步续期」，逻辑密度很高。
 * 这里做的是完全不同的事（APP oauth2 协议 + 签名 + 跨端口回落顺序），
 * 混在一起会让两个协议的错误码纠缠不清。分开之后 `CookieRefreshApi` 只需在
 * 「web 自续失败」时委托过来一次。
 *
 * # 不打印凭据
 * 本文件任何日志都**不含** access_token / refresh_token / Cookie 内容，只记业务码与长度。
 */
object AppTokenRefreshApi {

    private const val TAG = "app-token-refresh"

    /**
     * oauth2 续期端点（与登录同源，`passport.bilibili.com`）。
     *
     * 实测：`appkey` + `ts` + `refresh_token` + `sign` 四件套即可，**不需要 Cookie**。
     */
    private const val REFRESH_URL =
        "https://passport.bilibili.com/x/passport-login/oauth2/refresh_token"

    /** 用于「借 token」的端口优先级。实测 android 的 refresh_token 会回 `86033 appID不匹配`，故不列入 */
    private val FALLBACK_IDENTITIES = listOf(
        AppRcmdApi.IDENTITY_HD,
        AppRcmdApi.IDENTITY_TV
    )

    /**
     * 一次 APP token 续期的结果。
     *
     * 与 [CookieRefreshApi.RenewResult] 分开定义的理由：这里的失败分支是 APP 协议专属的
     * （appID 不匹配、签名不对、该端口没登录），与 web 的 86032/-400 不是一回事。
     * 对外由 `CookieRefreshApi` 翻译成统一口径。
     */
    sealed class Result {
        /**
         * 续期成功。
         *
         * @param cookies 服务端下发的**整套新 web Cookie**（`name=value; name2=value2`）。
         *   已由本类的调用方决定怎么写进账号槽；本类自己只负责拿到它。
         * @param accessToken 该 APP 端口的新 access_token（调用方可选写回该端口槽位）
         * @param refreshToken 该 APP 端口的新 refresh_token（同上）
         * @param identityId 这次是借哪个端口的密钥成功的（hd / tv）
         */
        data class Success(
            val cookies: String,
            val accessToken: String,
            val refreshToken: String,
            val identityId: String
        ) : Result()

        /** 没有任何 APP 端口可用于借 token（用户只登了 web） */
        object NoAppIdentity : Result()

        /** 所有候选端口都被服务端拒绝了 */
        data class Rejected(val lastCode: Int, val lastMessage: String) : Result()

        /** 网络/解析异常 */
        data class NetworkError(val detail: String) : Result()
    }

    /**
     * 借一个已登录的 APP 端口的 `refresh_token`，换回一整套新的 web Cookie。
     *
     * **调用方在后台线程调**（发网络请求）。
     *
     * # 回落顺序
     * 按 [FALLBACK_IDENTITIES]（HD → TV）逐个尝试，第一个成功就返回。
     * 实测两个端口都可用，但顺序固定能让行为可复现、日志可对照。
     *
     * # 为什么不用 android 端口
     * 实测 `appkey=783bbb7264451d82`（android）回 `code=86033 appID不匹配` ——
     * 该 refresh_token 是别的密钥签发出来的，不能用于这个端点。
     * 本项目里「手机版」端口实际借的就是 TV 密钥签发（见 `AppRcmdApi.PHONE_IDENTITY.scanAppKey`），
     * 所以手机端口的 token 走 TV 那条也能续；这里直接试 hd/tv 两个真实端口即可。
     */
    @JvmStatic
    fun refreshWebCookiesFromAppToken(): Result {
        var lastCode = 0
        var lastMessage = ""
        var tried = false
        for (identityId in FALLBACK_IDENTITIES) {
            val refreshToken = getRefreshToken(identityId)
            if (refreshToken.isEmpty()) {
                // 该端口没登录 / 没存 refresh_token：跳过，不算「被拒绝」
                continue
            }
            tried = true
            when (val one = refreshOne(identityId, refreshToken)) {
                is Result.Success -> {
                    Logu.d(TAG, "借 " + identityId + " 的 refresh_token 换到了新的 web Cookie"
                            + "（cookies 长度 " + one.cookies.length + "）")
                    return one
                }
                is Result.Rejected -> {
                    lastCode = one.lastCode
                    lastMessage = one.lastMessage
                    Logu.d(TAG, identityId + " 续期被拒：code=" + one.lastCode)
                }
                is Result.NetworkError -> {
                    // 网络错误直接返回：再试另一个端口大概率也是同一个网络问题
                    return one
                }
                is Result.NoAppIdentity -> Unit
            }
        }
        if (!tried) {
            Logu.d(TAG, "没有可用的 APP 端口 refresh_token（用户可能只登了 web）")
            return Result.NoAppIdentity
        }
        return Result.Rejected(lastCode, lastMessage)
    }

    /**
     * 对**单个**身份做一次 oauth2 续期。
     *
     * @param identityId HD / TV
     * @param refreshToken 该身份槽位里的 refresh_token
     */
    @JvmStatic
    fun refreshOne(identityId: String, refreshToken: String): Result {
        // getIdentityById 对未知 id 会退回「当前身份」——那会让本函数在配置异常时
        // 悄悄用错密钥。所以这里先把合法 id 收敛死，再取身份。
        if (identityId != AppRcmdApi.IDENTITY_HD && identityId != AppRcmdApi.IDENTITY_TV) {
            return Result.NoAppIdentity
        }
        val identity = AppRcmdApi.getIdentityById(identityId)
        return try {
            val formData = NetWorkUtil.FormData()
            formData.put("appkey", identity.feedAppKey)
            formData.put("ts", (System.currentTimeMillis() / 1000L).toString())
            formData.put("refresh_token", refreshToken)
            // 签名覆盖面：按 key 升序拼接 + appsec 做 MD5（与 AppRcmdApi.signWith 同一算法，
            // 但那边只签 FeedData 的参数集合，这里需要签 refresh_token，故本地实现一份）
            formData.put("sign", signOf(formData, identity.feedAppSec))

            val response = NetWorkUtil.post(
                REFRESH_URL, formData.toString().removePrefix("?"),
                AppRcmdApi.appHeaders(identity), "application/x-www-form-urlencoded")
            val text = response.body?.string() ?: ""
            parse(identityId, text)
        } catch (e: IOException) {
            Logu.w(TAG, "续期网络异常：" + e.javaClass.simpleName)
            Result.NetworkError(e.javaClass.simpleName)
        } catch (e: JSONException) {
            Logu.w(TAG, "续期回包结构异常：" + e.javaClass.simpleName)
            Result.NetworkError("json")
        }
    }

    /**
     * 解析 oauth2 回包。
     *
     * 成功回包结构（实测，两处都要读）：
     * ```
     * data.token_info  = {mid, access_token, refresh_token, expires_in, region, store_region}
     * data.cookie_info = {cookies: [{name, value, http_only, expires, secure}], domains}
     * ```
     * **`cookie_info.cookies` 就是新的 web 凭据** —— 这是整条链路的产物。
     */
    @Throws(JSONException::class)
    private fun parse(identityId: String, text: String): Result {
        val root = JSONObject(text)
        val code = root.optInt("code", -1)
        if (code != 0) {
            return Result.Rejected(code, root.optString("message", ""))
        }
        val data = root.optJSONObject("data") ?: return Result.Rejected(0, "回包缺少 data")
        val tokenInfo = data.optJSONObject("token_info") ?: return Result.Rejected(0, "回包缺少 token_info")
        val cookies = cookiesOf(data)
        if (cookies.isEmpty()) {
            // code=0 但没给 Cookie：不能当成功（调用方会把空 Cookie 写进账号槽 = 毁凭据）
            return Result.Rejected(0, "回包缺少 cookie_info")
        }
        return Result.Success(
            cookies = cookies,
            accessToken = tokenInfo.optString("access_token", ""),
            refreshToken = tokenInfo.optString("refresh_token", ""),
            identityId = identityId
        )
    }

    /** 从 `data.cookie_info.cookies[]` 拼出 `name=value; name2=value2` */
    private fun cookiesOf(data: JSONObject): String {
        val cookieInfo = data.optJSONObject("cookie_info") ?: return ""
        val array: JSONArray = cookieInfo.optJSONArray("cookies") ?: return ""
        val pairs = ArrayList<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name", "")
            val value = item.optString("value", "")
            if (name.isNotEmpty() && value.isNotEmpty()) pairs.add(name + "=" + value)
        }
        return pairs.joinToString("; ")
    }

    /**
     * 计算 sign：把参数（除 sign 自身）按 key 升序拼 `k=v&k=v`，末尾接 appsec，取 MD5 小写 hex。
     *
     * 与 `AppRcmdApi.signWith` 同一算法。⚠️ 值**不做 URL 编码**在拼接里 ——
     * 实测服务端按原值校验（本项目 `AppRcmdApi` 既有实现也是先编码再拼，两者对
     * `refresh_token` 这种纯 hex 值没有差别；这里保持与既有实现一致以免出现两套行为）。
     */
    @Throws(UnsupportedEncodingException::class, NoSuchAlgorithmException::class)
    private fun signOf(formData: NetWorkUtil.FormData, appSec: String): String {
        val sorted = TreeMap<String, String>()
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
        val digest = MessageDigest.getInstance("MD5").digest(sb.toString().toByteArray(Charsets.UTF_8))
        // 显式 Locale.ROOT：签名是纯 ASCII hex，绝不能受系统区域设置影响
        // （detekt ImplicitDefaultLocale；在阿拉伯/波斯数字区域下默认 locale 会产出非 ASCII 数字）
        val out = StringBuilder()
        for (b in digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b))
        return out.toString()
    }

    /**
     * 读某身份槽位的 refresh_token（**不打印**）。
     *
     * 走 `SharedPreferencesUtil` 的键名约定 `app_rcmd_<identity>_refresh_token`，
     * 与 `AppRcmdApi.keyOf` 一致。这里直接拼键是因为 `AppRcmdApi.keyOf` 是 private；
     * 键名一旦要改，两处都要改（已在 `AppRcmdApi` 的 KDoc 里约定该键结构）。
     */
    private fun getRefreshToken(identityId: String): String {
        return com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
            .getString("app_rcmd_" + identityId + "_refresh_token", "") ?: ""
    }
}
