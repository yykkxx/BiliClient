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

    /**
     * `cookie/refresh` 回 `-400` 的语义（v5.3.3 实测确定，**不是**「通用参数错」）。
     *
     * 穷举 10 组 `refresh_token` 取值后的错误码分布（见 notes/web_renew_v533.md §4）：
     * - 缺该键 / 空串 → **-400**
     * - 格式合法的 32 位 hex 假值 → `86032 refreshToken已过期`
     * - `bili_ticket` / `buvid3` / `buvid4`（长度或字符集不是 32hex）→ `-500`
     * - `sid` / `b_lsid` / `DedeUserID` → `-400`
     *
     * 也就是说：**只有「token 这一项不可接受」时才回 -400/-500，其它参数（csrf / refresh_csrf /
     * source）错会是别的码**。`refresh_csrf` 与 csrf 的有效性可以单独确认 —— 本机实测同一次调用里
     * `refresh_csrf` 是服务端签发的真值，换成格式合法的假 token 立刻从 -400 变成 86032，
     * 说明请求构造被判为合法、只是走到了「查这个 token」这一步。
     *
     * 所以 -400 与「缺 token」是**同一个可执行结论**：本机没有可用的 web refresh_token。
     */
    private const val CODE_REFRESH_PARAM_REJECTED = -400

    /** `-500` 同 -400：token 值不合法（字符集/长度都不像 token）。 */
    private const val CODE_REFRESH_TOKEN_MALFORMED = -500

    /**
     * 本地合成码：`correspond/1/<path>` 取不到 `refresh_csrf`（**不是**服务端返回值）。
     *
     * 用 `-1` 而不是 `-400`：-400 现在被明确解释成「token 不可用」，
     * 而这一层失败与凭据**无关**（换网络通常就好），两者绝不能在 UI 上显示成同一句话。
     */
    private const val CODE_CORRESPOND_UNREACHABLE = -1

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
     * 「本机没有 web `refresh_token`」时的**自举**尝试（v5.3.4 新增，登录成功后自动调用）。
     *
     * # 为什么不能并进 [renewWeb]
     * [renewWeb] 在 token 为空时返回 [RenewResult.NoRefreshToken] 且**一个请求都不发**，
     * 这是 tests/auth 的回归契约（`default_false_missing_web_token_no_cross_no_network`
     * 断言 `Suite.requests.isEmpty()`）。自举必须在拿到 Cookie 之后**显式**发起，
     * 所以它是独立入口，不改变 [renewWeb] 的既有语义。
     *
     * # 自举到底能拿到什么（不吹牛）
     * B 站没有任何端点能「凭空签发」web refresh_token（v5.3.3 穷举 10 类 + 本轮再否定 6 个，
     * 见 notes/auth_refresh_v534.md §1.6）。唯一还可能存在 token 的通道是
     * `correspond/1/<path>` 页的 `#1-value` —— 官方登录页经 iframe 把 web refresh_token
     * 注入这个 DOM 节点，主站再把它存进 `localStorage["ac_time_value"]`。
     * 我们既无 iframe 也无 localStorage，但只要**服务端**在该页里塞了值，就能捡到。
     *
     * 所以本函数做两件事，且**如实**报告结果：
     * 1. `cookie/info` 确认登录态还活着（顺便拿 correspond 需要的 timestamp）；
     * 2. 取 `correspond` 页 → 若 `#1-value` 有值，补进凭据槽 → **立刻**跑完整续期链路。
     *
     * 捡不到就返回 [RenewResult.NoRefreshToken]，**不谎称成功、也不把锅甩给用户**
     * （文案指向「已尝试自举」+ 跨端修复/导入，见 strings_web_renew.xml）。
     */
    @JvmStatic
    fun bootstrapMissingToken(): RenewResult {
        if (!inFlight.compareAndSet(false, true)) return RenewResult.Busy
        return try {
            lastCheckElapsed.set(SystemClock.elapsedRealtime())
            val snapshot = WebRenewalState.capture()
            if (!WebRenewalState.isCurrent(snapshot)) return RenewResult.AccountChanged
            when {
                snapshot.mid <= 0L || snapshot.cookies.isBlank() -> RenewResult.NotNeeded
                // 已经有 token：这次登录本来就不需要自举，直接走正常续期。
                snapshot.refreshToken.isNotBlank() -> doRenewWeb(snapshot)
                else -> probeCorrespondForToken(snapshot)
            }
        } catch (e: JSONException) {
            Logu.w(TAG, "Web 自举响应格式异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError("响应格式异常")
        } catch (e: IOException) {
            Logu.w(TAG, "Web 自举网络异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError(e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            Logu.w(TAG, "Web 自举异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError(e.javaClass.simpleName)
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * 走 `cookie/info` → `correspond` 两步，尝试把 `#1-value` 里的 token 捡回来。
     *
     * 注意**不能用 [WebRenewalState.isCurrent] 判活**：捡到 token 时它会主动改写凭据槽，
     * 快照里的 `refreshToken` 立刻就不等了（这是预期行为，不是切号）。这里只比较
     * 「账号 + Cookie 有没有变」，见 [WebRenewalState.isSameLogin]。
     */
    private fun probeCorrespondForToken(snapshot: WebRenewalState.Snapshot): RenewResult {
        val root = requestJson(PASSPORT + "cookie/info", snapshot.cookies)
        val code = root.optInt("code", -1)
        if (code != 0) return rejection(root)
        val timestamp = root.optJSONObject("data")?.optLong("timestamp", 0L) ?: 0L
        if (timestamp <= 0L) return RenewResult.ServerError(0, "缺少服务器时间戳")
        val path = getCorrespondPath(timestamp)
        if (path.isEmpty()) return RenewResult.NetworkError("RSA 加密失败")
        // refreshCsrf 内部会读 #1-value 并调 adoptRefreshTokenIfMissing（槽为空才写）。
        val csrf = refreshCsrf(path, snapshot.cookies)
        val probed = WebRenewalState.capture()
        if (!WebRenewalState.isSameLogin(snapshot, probed)) return RenewResult.AccountChanged
        if (probed.refreshToken.isNotBlank()) {
            Logu.d(TAG, "Web 自举成功取到 refresh_token，转入完整续期")
            return doRenewWeb(probed)
        }
        // 取不到 refresh_csrf 是独立故障（换网络通常就好），不能和「没有 token」说成同一件事。
        if (csrf.isBlank()) return RenewResult.ServerError(CODE_CORRESPOND_UNREACHABLE, "无法取得 Web refresh_csrf")
        Logu.d(TAG, "Web 自举未取到 refresh_token（服务端该页未下发）")
        return RenewResult.NoRefreshToken
    }

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
        // 取不到 refresh_csrf 时**不能**继续提交：实测缺这一项服务端回 -400，
        // 而那个 -400 会被上层解释成「token 失效」，把真正的成因（correspond 页取不到值）掩盖掉。
        // 这里单独报出，提示文案指向「换网络/升级」而不是「重新登录」。
        if (csrf.isBlank()) return RenewResult.ServerError(CODE_CORRESPOND_UNREACHABLE, "无法取得 Web refresh_csrf")
        return submitRefresh(snapshot, csrf)
    }

    /**
     * 提交 `cookie/refresh`。
     *
     * # `csrf` 必须传（v5.3.3 实测推翻过一个想当然的改动）
     * B 站官方 `token-iframe` 的 `data` 里看起来只有 `refresh_csrf` / `source` / `refresh_token`
     * 三个字段，很容易误以为不用 `csrf`。**但那是假象**：官方的 `reqwest` 包装
     * `client.with(new Raw()).request(...)` 会由拦截器统一补 `csrf`。
     *
     * 本轮用真实凭据实测过：去掉 `csrf` 后，`cookie/info` → `correspond` → `refresh_csrf`
     * 全链仍然正常，但 `cookie/refresh` 从 `-400` 变成 **`-111`**，
     * 也就是说参数校验挂在 `csrf` 上、根本没走到查 token 那一步。
     * 加回去立刻恢复 `-400`（= 走到查 token 了）。所以这里**必须**保留 `csrf`。
     *
     * 这与 v5.3.2 的行为一致，只是现在有了实测依据，注释不再需要猜。
     */
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
            if (root.optInt("code", -1) != 0) return refreshRejection(root)
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

    /** `cookie/info` 的拒绝：这一层没有任何 token 参与，-400 只能是请求本身有问题。 */
    private fun rejection(root: JSONObject): RenewResult {
        val code = root.optInt("code", -1)
        return if (code == CODE_REFRESH_TOKEN_EXPIRED) RenewResult.TokenExpired
        else RenewResult.ServerError(code, "Web 服务端拒绝续期")
    }

    /**
     * 把 `cookie/refresh` 的业务码翻译成**可执行**的结果（v5.3.3 修正）。
     *
     * 关键修正：`-400` / `-500` 映射成 [RenewResult.TokenExpired]（= 「这份 refresh_token 用不了」），
     * **不再**落到 [RenewResult.ServerError]（= 「服务端拒绝，原因未知」）。
     *
     * 实测依据（notes/web_renew_v533.md §4，10 组取值穷举）：在 `csrf` / `refresh_csrf` / `source`
     * 全部正确的前提下，只有 `refresh_token` 这一项不可接受时服务端才回 -400/-500；
     * 把 token 换成格式合法的 32 位 hex 假值，错误码立刻从 `-400` 变成 `86032`。
     * 也就是说 -400/-500 的确切含义是「本机这个 web refresh_token 服务端不认」。
     *
     * 为什么必须收敛到 TokenExpired 而不是新增状态：
     * 1. 两者的**下一步动作完全相同**（重新登录 / 跨端修复 / 保留现状），UI 分支没必要分叉；
     * 2. `SettingAccountActivity` 对 `RenewResult` 有**穷尽 when**，它是本轮写域外的文件，
     *    新增 sealed 分支会直接编译失败 —— 不越界的前提下，把语义并进已有状态是唯一正解；
     * 3. v5.3.2 显示「服务端拒绝(码 -400)」，用户既不知道原因也不知道该做什么；
     *    显示「refresh_token 已失效，请重新登录或开启跨端修复」才是可执行的。
     */
    private fun refreshRejection(root: JSONObject): RenewResult {
        val code = root.optInt("code", -1)
        return when (code) {
            CODE_REFRESH_TOKEN_EXPIRED -> RenewResult.TokenExpired
            CODE_REFRESH_PARAM_REJECTED -> RenewResult.TokenExpired
            CODE_REFRESH_TOKEN_MALFORMED -> RenewResult.TokenExpired
            else -> RenewResult.ServerError(code, "Web 服务端拒绝续期")
        }
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

    /**
     * 从 `correspond/1/<path>` 页面取 `refresh_csrf`。
     *
     * 选择器 `#1-name` 逐字对齐 B 站官方 `token-iframe` 实现（chunk 895：
     * `n = function(){ var e = q("1-name"); e && (r.value = e) }`），本轮实测该页
     * HTTP 200 且 `#1-name` 有 32 字符（notes/web_renew_v533.md §3）。
     *
     * **为什么同时读 `#1-value`**：官方把 token 放在两个位置 ——
     * `#1-value`（DOM 通道，登录页经 iframe 注入）与 `localStorage["ac_time_value"]`（已登录浏览器）。
     * 我们这一侧既没有 iframe 也没有 localStorage，`#1-value` 是唯一可能拿到
     * 「服务端顺手塞进来的 token」的通道。实测本机它是空的（`len=0`），所以这只是**机会性兜底**：
     * 非空时顺手写回凭据槽，等于多一条自愈路径；空则完全不影响原有行为。
     *
     * 注意：`#1-value` 里的值属于凭据，**不得**进任何日志。
     */
    private fun refreshCsrf(path: String, cookies: String): String {
        val request = webRequest("https://www.bilibili.com/correspond/1/" + path, cookies).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("correspond HTTP " + response.code)
            val html = response.body?.string() ?: throw IOException("correspond 响应为空")
            val doc = Jsoup.parse(html)
            adoptCorrespondValue(doc.select("#1-value").first()?.text() ?: "")
            doc.select("#1-name").first()?.text() ?: ""
        }
    }

    /**
     * `correspond` 页若带了 `#1-value`（= 服务端下发的 web refresh_token），把它补进凭据槽。
     *
     * 只写「当前 Web 账号槽 + 激活槽」的 refresh_token，且**只在该槽为空时**写 ——
     * 已有的 token 比页面里的更可信（页面里的可能是给别的会话用的）。
     * 失败一律吞掉：这是锦上添花的通道，绝不能因为它让主流程续期失败。
     */
    private fun adoptCorrespondValue(value: String) {
        val token = value.trim()
        if (token.isEmpty()) return
        try {
            WebRenewalState.adoptRefreshTokenIfMissing(token)
        } catch (e: RuntimeException) {
            Logu.w(TAG, "correspond #1-value 回填失败：" + e.javaClass.simpleName)
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
