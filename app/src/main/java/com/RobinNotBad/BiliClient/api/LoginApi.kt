package com.RobinNotBad.BiliClient.api

import android.graphics.Bitmap

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.QRCodeUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

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
    private const val TAG = "login-api"

    /** `data.url` 里可能携带 cookie 的键（旧式 query 形态，官方文档的 `crossDomain?...&SESSDATA=..`）。 */
    private val URL_COOKIE_KEYS = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")

    /** `data.url` 里可能携带 web refresh_token 的键（历史/兼容形态，官方文档未明写，按「有则取」处理）。 */
    private val URL_TOKEN_KEYS = listOf("refresh_token", "ac_time_value")

    private var oauthKey: String? = null

    /**
     * 登录回包里**实际取到的**凭据，以及每一部分的来源（来源只用于日志/回归断言，不含凭据值）。
     *
     * 为什么要单独一个类型：v5.3.3 及更早只认 `data.refresh_token` 一个来源，
     * 取不到就当「服务端没下发」。但 `data.url` 同样是官方文档定义的凭据载体
     * （旧式形态 query 里直接带 `SESSDATA/bili_jct/DedeUserID`，新式 `crossDomain`
     * 形态则只在 follow 它时的 `Set-Cookie` 里下发）。只认一个来源会把这些白丢。
     */
    class WebLoginPayload(
        /** web `refresh_token`；空串表示所有来源都没给（**含完整凭据，禁止打印**）。 */
        val refreshToken: String,
        /** 登录后的 Web Cookie 串（**含完整凭据，禁止打印**）。 */
        val cookies: String,
        /** token 是从哪里取到的：`top` / `url` / `none`。 */
        val tokenSource: String
    )

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

    /**
     * 跟进 `data.url`，返回**登录完成后**应当写进账号槽的 Web Cookie 串。
     *
     * # 为什么必须先 follow、后读 Cookie（本轮修的顺序 bug）
     * 2026-08 起 B 站把登录成功后的 `data.url` 换成了 `crossDomain` 票据链接：
     * **URL 的 query 里不再带 Cookie**，真正的 `SESSDATA` / `bili_jct` / `DedeUserID`
     * 只在 follow 这个链接时的 `Set-Cookie` 响应头里下发。
     *
     * v5.3.3 的调用顺序是「先读 `SharedPreferencesUtil.cookies` 交给 `loginWeb`，
     * 再 `NetWorkUtil.get(data.url)`」—— 于是新形态下**写进账号槽的是上一份旧 Cookie**，
     * 这次登录的 Cookie 是之后才被 `CookieSaveInterceptor` 落库的，压根没进账号槽。
     * 反过来以后，`Set-Cookie` 已经落库，读到的就是本次登录的凭据。
     *
     * # 三条兜底（优先级从高到低）
     * 1. `Set-Cookie` 落库后的激活 Cookie 串（覆盖新式 `crossDomain` 与老式两路）；
     * 2. `data.url` 的 query 里的 cookie 键（老式形态；仅用于补 ① 缺失的键）；
     * 3. 都拿不到就沿用原来的 Cookie 串（**不破坏**任何历史行为）。
     *
     * @param loginUrl `data.url`；空串时直接返回当前 Cookie（与历史行为一致）
     */
    @JvmStatic
    fun followLoginUrl(loginUrl: String): String {
        val before = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        if (loginUrl.isBlank()) return before
        // 失败不能影响登录：历史上这一步本来就只是「顺手种 Cookie」。
        try {
            NetWorkUtil.get(loginUrl)?.close()
        } catch (e: IOException) {
            Logu.w(TAG, "登录跳转链接访问失败（不影响已保存的登录态）：" + e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            Logu.w(TAG, "登录跳转链接处理异常（不影响已保存的登录态）：" + e.javaClass.simpleName)
        }
        val after = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        // 读回与访问前一样（= Set-Cookie 没带来任何东西）时，交给 URL query 兜底。
        val base = if (after.isNotBlank()) after else before
        return mergeUrlCookies(base, parseUrlQuery(loginUrl))
    }

    /**
     * 解析 poll 回包的 `data`，按多路径兜底得到 [WebLoginPayload]。
     *
     * 取值优先级：
     * 1. `data.refresh_token`（顶层，官方字段，历史主路径 —— 保持原优先级不变）；
     * 2. `data.url` query 里的 `refresh_token` / `ac_time_value`（兼容形态）；
     * 3. 都没有 → 空串（**不阻塞登录**，由调用方决定是否尝试自举）。
     *
     * @param data            poll 回包的 `data` 对象
     * @param cookiesAfterUrl 已经 follow 过 `data.url` 之后读到的 Cookie 串
     */
    @JvmStatic
    fun extractWebLoginPayload(data: JSONObject, cookiesAfterUrl: String): WebLoginPayload {
        val query = parseUrlQuery(data.optString("url", ""))
        val topToken = data.optString("refresh_token", "").trim()
        if (topToken.isNotEmpty()) {
            return WebLoginPayload(topToken, mergeUrlCookies(cookiesAfterUrl, query), "top")
        }
        for (key in URL_TOKEN_KEYS) {
            val token = query[key]?.trim().orEmpty()
            if (token.isNotEmpty()) {
                return WebLoginPayload(token, mergeUrlCookies(cookiesAfterUrl, query), "url")
            }
        }
        return WebLoginPayload("", mergeUrlCookies(cookiesAfterUrl, query), "none")
    }

    /** 把 `url` 的 query 解析成 键→值（同键取第一个非空值；解析失败返回空 map）。 */
    private fun parseUrlQuery(url: String): Map<String, String> {
        val start = url.indexOf('?')
        if (start < 0 || start == url.length - 1) return emptyMap()
        val query = url.substring(start + 1)
        // 去掉 ? 之后的 fragment，避免把锚点算进最后一个参数
        val cut = query.indexOf('#')
        val body = if (cut >= 0) query.substring(0, cut) else query
        val result = linkedMapOf<String, String>()
        for (part in body.split('&')) {
            val pair = queryPairOf(part)
            if (pair != null) result.putIfAbsent(pair.first, pair.second)
        }
        return result
    }

    /** 把一个 `k=v` 片段拆成键值对；非 `k=v` 形状、空键、空值都返回 null（供调用方单分支处理）。 */
    private fun queryPairOf(part: String): Pair<String, String>? {
        if (part.isEmpty()) return null
        val pair = part.split('=', limit = 2)
        if (pair.size != 2 || pair[0].isEmpty()) return null
        val value = decode(pair[1])
        if (value.isEmpty()) return null
        return decode(pair[0]) to value
    }

    /**
     * 最小百分号解码：`data.url` 的 query 只会用到 `%XX`（`gourl` 被整体转义）。
     *
     * 不用 `java.net.URLDecoder`：它把 `+` 当成空格，而 Cookie/token 值里 `+` 是合法字符，
     * 走 URLDecoder 会把凭据改坏。
     *
     * 实现上先做「哪些位置是 `%XX`」的一次预扫描，主循环里只剩单分支 ——
     * 既避免循环体里 `continue`/`i += n` 混用（detekt LoopWithTooManyJumpStatements），
     * 语义与逐字符判断完全等价。
     */
    private fun decode(text: String): String {
        if (text.indexOf('%') < 0) return text
        return buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val decoded = decodeEscapeAt(text, i)
                if (decoded == null) {
                    append(text[i])
                    i++
                } else {
                    append(decoded.toChar())
                    i += 3
                }
            }
        }
    }

    /** 若 `index` 处是合法的 `%XX`，返回该字节；否则返回 null（供 [decode] 单分支处理）。 */
    private fun decodeEscapeAt(text: String, index: Int): Int? {
        if (text[index] != '%' || index + 2 >= text.length) return null
        val high = Character.digit(text[index + 1], 16)
        val low = Character.digit(text[index + 2], 16)
        if (high < 0 || low < 0) return null
        return (high shl 4) or low
    }

    /**
     * 把 URL query 里的登录 Cookie 合并进已有 Cookie 串（**只补缺失的键**，不覆盖已有值）。
     *
     * 为什么只补不覆盖：`Set-Cookie` 落库的那一份是最新且服务端主动写的，比 URL query 更可信；
     * URL query 只在这台设备没拿到 `Set-Cookie`（老式形态）时才需要站出来。
     */
    private fun mergeUrlCookies(current: String, query: Map<String, String>): String {
        if (query.isEmpty()) return current
        val cookies = parseCookies(current)
        var added = false
        for (key in URL_COOKIE_KEYS) {
            val value = query[key]?.trim().orEmpty()
            if (value.isNotEmpty() && cookies[key].isNullOrEmpty()) {
                cookies[key] = value
                added = true
            }
        }
        // 没有任何补充时**原样返回**：避免把 current 重新拼接一遍（会改掉原有顺序/重复键形态）。
        if (!added) return current
        return cookies.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    private fun parseCookies(cookies: String): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        for (part in cookies.split(';')) {
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotEmpty()) result[pair[0]] = pair[1]
        }
        return result
    }

    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun requestSSOs() {
        val listUrl = "https://passport.bilibili.com/x/passport-login/web/sso/list"
        val listResult = JSONObject(NetWorkUtil.post(listUrl, NetWorkUtil.FormData().put("csrf", SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "")).toString()).body!!.string())
        if (listResult.has("data") && !listResult.isNull("data")) {
            val sso = listResult.getJSONObject("data").getJSONArray("sso")
            for (i in 0 until sso.length()) {
                NetWorkUtil.post(sso.getString(i), "")
            }
        }
    }

}
