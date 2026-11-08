package com.RobinNotBad.BiliClient.api

import android.os.SystemClock
import android.util.Base64
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.WebRenewalState
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException // quality-allow：Web 续期协议边界
import org.json.JSONObject // quality-allow：Web 续期协议边界
import org.jsoup.Jsoup
import java.io.IOException
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Web 默认仅自续；跨端修复必须有用户本次确认或独立开关，且限定同账号。 */
object CookieRefreshApi {
    private const val TAG = "cookie-refresh"
    private const val PASSPORT = "https://passport.bilibili.com/x/passport-login/web/"
    private const val RENEW_INTERVAL_MS = 30L * 60L * 1000L
    private const val CODE_REFRESH_TOKEN_EXPIRED = 86032
    private val inFlight = AtomicBoolean(false)
    private val lastCheckElapsed = AtomicLong(0L)

    // 不复用带 CookieSaveInterceptor 的共享客户端：网络过程中切号不能污染新账号。
    // 不经过 ApiRouter；即使开启「回退 Web」也不会跨端续期。
    private val client by lazy {
        OkHttpClient.Builder()
            .dns(NetWorkUtil.Inet4Selector())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    sealed class RenewResult {
        object NotNeeded : RenewResult()
        object Success : RenewResult()
        data class CrossPortSuccess(val port: String) : RenewResult()
        object NoRefreshToken : RenewResult()
        object TokenExpired : RenewResult()
        object Busy : RenewResult()
        object AccountChanged : RenewResult()
        data class ServerError(val code: Int, val message: String) : RenewResult()
        data class NetworkError(val detail: String) : RenewResult()
        fun renewed(): Boolean = this is Success || this is CrossPortSuccess
    }

    /** 兼容推荐页检查；force 只控制本地节流，不再用 data.refresh 拦截主动续期。 */
    @JvmStatic
    fun renewIfNeededOnForeground(force: Boolean): RenewResult {
        if (!AccountStore.isPortLoggedIn(AccountStore.PORT_WEB)) return RenewResult.NotNeeded
        val now = SystemClock.elapsedRealtime()
        val last = lastCheckElapsed.get()
        if (!force && last > 0L && now - last < RENEW_INTERVAL_MS) return RenewResult.NotNeeded
        return renewWeb()
    }

    /**
     * 每次进 APP / 手点按钮直接执行，不等待服务端 refresh=true 或 Cookie 到期。
     *
     * **默认路径（用户需求 1）**：跨端修复是否放开由独立开关 `WEB_CROSS_PORT_REPAIR` 决定，
     * 该开关缺省 false，且与「API 请求回退 Web」开关**不联动**。
     * 关着时 [renewWeb] 里 `allowCrossPort && ...` 第一项就为 false，
     * 于是 [renewViaAppToken] → [AppTokenRefreshApi.refreshWebCookiesFromAppToken] **完全不会被调用**，
     * 连一次 oauth2 请求都不会发出（见 tests/auth 的 `default_false_missing_web_token_no_cross_no_network`：
     * 断言 `Suite.requests.isEmpty()`）。
     */
    @JvmStatic
    fun renewWeb(): RenewResult = renewWeb(isCrossPortRepairEnabled())

    fun isCrossPortRepairEnabled(): Boolean =
        SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.WEB_CROSS_PORT_REPAIR, false)

    /** allowCrossPort=true 只能来自确认弹窗或已打开的独立开关。 */
    fun renewWeb(allowCrossPort: Boolean, authorizedSnapshot: WebRenewalState.Snapshot? = null): RenewResult {
        if (!inFlight.compareAndSet(false, true)) return RenewResult.Busy
        return try {
            lastCheckElapsed.set(SystemClock.elapsedRealtime())
            val snapshot = authorizedSnapshot ?: WebRenewalState.capture()
            if (!WebRenewalState.isCurrent(snapshot)) return RenewResult.AccountChanged
            val ownResult = when {
                snapshot.mid <= 0L -> RenewResult.NotNeeded
                snapshot.refreshToken.isBlank() -> RenewResult.NoRefreshToken
                else -> doRenewWeb(snapshot)
            }
            // 短路求值：allowCrossPort=false 时右侧整体不求值，CanOffer 与借端逻辑都不执行。
            if (allowCrossPort && canOfferCrossPortRepair(ownResult)) renewViaAppToken(snapshot, ownResult)
            else ownResult
        } catch (e: JSONException) {
            Logu.w(TAG, "Web 续期响应格式异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError("响应格式异常")
        } catch (e: IOException) {
            Logu.w(TAG, "Web 续期网络异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError(e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            Logu.w(TAG, "Web 续期异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError(e.javaClass.simpleName)
        } finally {
            inFlight.set(false)
        }
    }

    fun canOfferCrossPortRepair(result: RenewResult): Boolean =
        result is RenewResult.NoRefreshToken || result is RenewResult.TokenExpired ||
            (result is RenewResult.ServerError && result.code == -101)

    /**
     * 借用同账号 HD/TV 的 refresh_token 修复 Web。
     *
     * F5：发 oauth2 **之前**再验一次快照。已实测：oauth2/refresh_token 成功会轮换借用端的
     * token，而结果一旦因切号被拒就是「借用端 token 被换掉、新 token 被丢弃」——
     * 这是不可撤回的服务端副作用。所以只要此时已知快照过时，就立刻返回 AccountChanged，
     * 一个请求都不发（见 `response_after_switch_never_starts_oauth`）。
     */
    private fun renewViaAppToken(snapshot: WebRenewalState.Snapshot, ownResult: RenewResult): RenewResult {
        if (!WebRenewalState.isCurrent(snapshot)) return RenewResult.AccountChanged
        return when (val result = AppTokenRefreshApi.refreshWebCookiesFromAppToken(snapshot.mid)) {
            is AppTokenRefreshApi.Result.Success -> {
                if (WebRenewalState.applyCrossPort(snapshot, result)) RenewResult.CrossPortSuccess(result.port)
                else RenewResult.AccountChanged
            }
            is AppTokenRefreshApi.Result.NoAppIdentity -> ownResult
            is AppTokenRefreshApi.Result.Rejected -> RenewResult.ServerError(result.lastCode, "同账号跨端修复被拒绝")
            is AppTokenRefreshApi.Result.NetworkError -> RenewResult.NetworkError(result.detail)
        }
    }

    private fun doRenewWeb(snapshot: WebRenewalState.Snapshot): RenewResult {
        val root = requestJson(PASSPORT + "cookie/info", snapshot.cookies)
        val code = root.optInt("code", -1)
        if (code != 0) return rejection(root)
        val timestamp = root.optJSONObject("data")?.optLong("timestamp", 0L) ?: 0L
        if (timestamp <= 0L) return RenewResult.ServerError(0, "缺少服务器时间戳")
        // data.refresh=false 只表示尚未进入被动续期窗口，不阻止用户主动换新。
        val path = getCorrespondPath(timestamp)
        if (path.isEmpty()) return RenewResult.NetworkError("RSA 加密失败")
        val csrf = refreshCsrf(path, snapshot.cookies)
        if (csrf.isBlank()) return RenewResult.ServerError(-1, "无法取得 Web refresh_csrf")
        return submitRefresh(snapshot, csrf)
    }

    private fun submitRefresh(snapshot: WebRenewalState.Snapshot, refreshCsrf: String): RenewResult {
        val body = FormBody.Builder()
            .add("csrf", cookieValue(snapshot.cookies, "bili_jct"))
            .add("refresh_csrf", refreshCsrf)
            .add("source", "main_web")
            .add("refresh_token", snapshot.refreshToken)
            .build()
        val request = webRequest(PASSPORT + "cookie/refresh", snapshot.cookies).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return RenewResult.ServerError(response.code, "Web 续期 HTTP 错误")
            val root = JSONObject(response.body?.string() ?: throw IOException("续期响应为空"))
            if (root.optInt("code", -1) != 0) return rejection(root)
            val token = root.optJSONObject("data")?.optString("refresh_token", "") ?: ""
            val newCookies = Cookie.parseAll(response.request.url, response.headers)
                .filter { it.matches(response.request.url) }
                .associate { it.name to it.value }
            if (token.isBlank() || newCookies["SESSDATA"].isNullOrBlank() ||
                newCookies["bili_jct"].isNullOrBlank()) {
                return RenewResult.ServerError(0, "续期响应缺少完整的新 Web 凭据")
            }
            val returnedMid = newCookies["DedeUserID"]?.toLongOrNull()
            if (returnedMid != null && returnedMid != snapshot.mid) {
                return RenewResult.ServerError(0, "续期返回的 Web 账号不一致，未写入")
            }
            val cookies = mergeCookies(snapshot.cookies, newCookies)
            // 先可靠落库，再用新 Cookie + 旧 token 确认；确认失败不能丢掉已生效的新 token。
            if (!WebRenewalState.apply(snapshot, cookies, token)) return RenewResult.AccountChanged
            confirmRefresh(snapshot.refreshToken, cookies)
            Logu.d(TAG, "Web 独立续期成功，已同步当前账号及当前凭据")
            return RenewResult.Success
        }
    }

    private fun rejection(root: JSONObject): RenewResult {
        val code = root.optInt("code", -1)
        return if (code == CODE_REFRESH_TOKEN_EXPIRED) RenewResult.TokenExpired
        else RenewResult.ServerError(code, "Web 服务端拒绝续期")
    }

    private fun requestJson(url: String, cookies: String): JSONObject {
        return client.newCall(webRequest(url, cookies).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            JSONObject(response.body?.string() ?: throw IOException("Web 响应为空"))
        }
    }

    private fun webRequest(url: String, cookies: String): Request.Builder {
        return Request.Builder().url(url)
            .header("Cookie", cookies)
            .header("User-Agent", NetWorkUtil.USER_AGENT_WEB)
            .header("Referer", "https://www.bilibili.com/")
    }

    private fun refreshCsrf(path: String, cookies: String): String {
        val request = webRequest("https://www.bilibili.com/correspond/1/" + path, cookies).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("correspond HTTP " + response.code)
            val html = response.body?.string() ?: throw IOException("correspond 响应为空")
            Jsoup.parse(html).select("#1-name").first()?.text() ?: ""
        }
    }

    private fun confirmRefresh(oldToken: String, cookies: String) {
        try {
            val body = FormBody.Builder().add("csrf", cookieValue(cookies, "bili_jct"))
                .add("refresh_token", oldToken).build()
            val request = webRequest(PASSPORT + "confirm/refresh", cookies).post(body).build()
            client.newCall(request).execute().use { response ->
                val root = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful || root.optInt("code", -1) != 0) {
                    Logu.w(TAG, "旧 Web 凭据确认失败；保留已保存的新凭据")
                }
            }
        } catch (e: IOException) {
            Logu.w(TAG, "确认续期网络异常：" + e.javaClass.simpleName)
        } catch (e: JSONException) {
            Logu.w(TAG, "确认续期响应异常：" + e.javaClass.simpleName)
        }
    }

    private fun cookieValue(cookies: String, name: String): String = parseCookies(cookies)[name] ?: ""

    private fun parseCookies(cookies: String): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        for (part in cookies.split(';')) {
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotEmpty()) result[pair[0]] = pair[1]
        }
        return result
    }

    private fun mergeCookies(old: String, updates: Map<String, String>): String {
        val cookies = parseCookies(old)
        cookies.putAll(updates)
        return cookies.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    @JvmStatic
    fun cookieInfo(): JSONObject = requestJson(PASSPORT + "cookie/info", WebRenewalState.capture().cookies)
        .getJSONObject("data")

    @JvmStatic
    fun checkAndRefresh(): Boolean = renewWeb().renewed()

    @JvmStatic
    fun getRefreshCsrf(CorrespondPath: String): String {
        if (CorrespondPath.isEmpty()) return ""
        return refreshCsrf(CorrespondPath, WebRenewalState.capture().cookies)
    }

    /** 旧接口也转统一入口，不允许绕开并发闸门及账号校验。 */
    @JvmStatic
    fun refreshCookie(RefreshCsrf: String): Boolean {
        if (RefreshCsrf.isEmpty()) return false
        return renewWeb().renewed()
    }

    @JvmStatic
    fun getCorrespondPath(timestamp: Long): String {
        return try {
            val publicKeyPEM = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB"
            val spec = X509EncodedKeySpec(Base64.decode(publicKeyPEM, Base64.DEFAULT))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaep)
            base16Encode(cipher.doFinal("refresh_$timestamp".toByteArray(Charsets.UTF_8)))
        } catch (e: java.security.GeneralSecurityException) {
            Logu.w(TAG, "Web 续期 RSA 失败：" + e.javaClass.simpleName)
            ""
        }
    }

    @JvmStatic
    fun base16Encode(src: ByteArray): String {
        val hex = "0123456789abcdef"
        return buildString(src.size * 2) {
            for (byte in src) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}
