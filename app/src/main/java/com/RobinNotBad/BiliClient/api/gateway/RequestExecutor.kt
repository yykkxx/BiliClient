package com.RobinNotBad.BiliClient.api.gateway

import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder

/**
 * **唯一发请求的地方**（v5.2.1）。
 *
 * # 核心语义变化：失败绝不回退到别的端口
 *
 * 旧 `NetWorkUtil` + [com.RobinNotBad.BiliClient.api.migration.ApiRouter] 的行为是：
 * 命中 APP 通道 → 响应「不可用」（-101 / 非 JSON / HTTP 错误）→ **用 web 头重发同一条请求**。
 * 这看起来像「兜底」，实际上是「半残」的直接来源：
 * web 未登录时回退带的是空 Cookie，必然再失败一次 —— 用户看到「明明 HD 端口登录着，功能却报 -101」，
 * 白等一个 RTT 还把排查方向引偏。
 *
 * 用户原话：「四端分开，去掉回退 Web 的功能。」所以本对象：
 * - **选定通道 = [CredentialStore.activeChannel]**（就是用户选的 `AccountStore.mainPort()`）；
 * - 该通道就是**唯一通道**，请求只发一次，**不发到别的端口**；
 * - 响应不可用 → 判定成鉴权失败就抛 [AuthFailureException]，否则**原样返回**让上层按业务码提示；
 * - 网络异常 → 原样抛 [IOException]，不吞不换通道。
 *
 * 代码里**没有**、也不允许出现任何形如
 * `try { appRequest() } catch (e) { webRequest() }` 或
 * `if (code == -101) return webChannel.execute(...)` 的分支。
 *
 * # 本轮的范围声明（重要，别误读）
 *
 * **本对象尚未接管 `NetWorkUtil` 的调用路径。** `NetWorkUtil.get/post` 目前仍在走
 * `ApiRouter.planGet/planPost`（那是 `gw-wire` / `gw-route` 的收敛点，Lead 已先把那边的
 * 回退语义删掉了，现在是「坏响应原样返回」）。
 *
 * 也就是说：本轮 `RequestExecutor` 提供的是一个**结构化更强、语义更明确**的统一入口
 * （查 catalog、选通道、参数适配、结构化异常），但全仓库现有调用点还没有切过来。
 * 这是**下一轮的收敛点**，本轮不声称已经接管。
 */
object RequestExecutor {

    private const val TAG = "gw-executor"

    /**
     * 结构化鉴权失败，区别于网络异常。
     *
     * 为什么单独建一个异常类型：调用方需要**区分**「凭据问题」与「网络问题」，
     * 前者该提示用户登录/换账号，后者该提示重试。旧代码只能靠解析 `code` 字符串，
     * 各处写法不一致（有的看 -101，有的看 -111，有的干脆 catch 全部）。
     *
     * @param code 服务端业务码（-101 未登录、-111 csrf 失效等）
     * @param endpointId 出问题的端点 id（来自 [ApiCatalog]，便于日志定位与埋点统计）
     */
    class AuthFailureException(
        @JvmField val code: Int,
        @JvmField val endpointId: String
    ) : Exception("鉴权失败 code=" + code + " endpoint=" + endpointId) {
        /** 中文提示（UI 可直接用） */
        fun describe(): String {
            return "该端口凭据已失效（code=" + code + "，端点 " + endpointId + "），请重新登录该端口"
        }
    }

    // ==================== GET ====================

    /**
     * 执行一次 GET。
     *
     * @param url 原始 URL（含 query）。端点从 [ApiCatalog] 按 host + path 查；
     *   **查不到就按原样透传**（契约 §5 的降级策略：不能因为漏登记一条规则就让功能挂掉）
     * @param headers 调用方自带的请求头；会与通道生成的请求头合并（通道的在后，覆盖同名）
     * @return okhttp Response（保持与 `NetWorkUtil.get` 一致的返回类型，调用方零改动）
     * @throws AuthFailureException 鉴权失败（且本通道无法自愈）
     * @throws IOException 网络异常
     */
    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?, headers: ArrayList<String>?): Response {
        val rawUrl = url ?: throw IOException("请求 URL 为空")
        val httpUrl = rawUrl.toHttpUrlOrNull()
            ?: return executeRawGet(rawUrl, headers)
        val endpoint = ApiCatalog.find(httpUrl.host, httpUrl.encodedPath)
        val channel = CredentialStore.activeChannel()
        if (endpoint == null) {
            // 未登记端点：按原样透传（不猜、不改写、不换通道）
            return executeRawGet(rawUrl, headers)
        }
        if (endpoint.auth == AuthRequirement.IMPOSSIBLE) {
            // 协议层不可能用 APP 通道的端点（passport 登录握手、gRPC、静态文件、非 B 站域名）。
            // 这不是「回退」—— 这条端点本来就只有它自己的实现，见 ApiEndpoint.impossibleReason
            return executeRawGet(rawUrl, headers)
        }
        val targetUrl = buildAppGetUrl(endpoint, channel, httpUrl)
        val merged = mergeHeaders(channel.headers(endpoint), headers)
        val response = executeRawGet(targetUrl, merged)
        return judgeOrReturn(endpoint, channel, response)
    }

    // ==================== POST ====================

    /**
     * 执行一次 POST。
     *
     * **写操作的安全边界**：端点登记了 `idempotent = false`（投币、发评论、发私信…）时，
     * 本对象**绝不重发**。即使响应看起来有问题，也把响应原样交给上层 ——
     * 因为「可能已经发出去了」，重发会造成重复投币这类不可撤销的副作用。
     * 这与旧 `NetWorkUtil.post` 的 `replayable` 判定目标一致，但语义更明确。
     *
     * @param data 已编码好的请求体
     * @param contentType 内容类型；APP 通道要求 `application/x-www-form-urlencoded`
     */
    @JvmStatic
    @Throws(IOException::class)
    fun post(
        url: String?,
        data: String?,
        headers: List<String>?,
        contentType: String?
    ): Response {
        val rawUrl = url ?: throw IOException("请求 URL 为空")
        val httpUrl = rawUrl.toHttpUrlOrNull()
            ?: return executeRawPost(rawUrl, data, headers, contentType)
        val endpoint = ApiCatalog.find(httpUrl.host, httpUrl.encodedPath)
        val channel = CredentialStore.activeChannel()
        if (endpoint == null || endpoint.auth == AuthRequirement.IMPOSSIBLE) return executeRawPost(rawUrl, data, headers, contentType)
        // APP 通道只处理表单体：JSON body 的端点（如发动态）在 catalog 里登记为 IMPOSSIBLE，
        // 走到这里说明某条表单端点被误传了 JSON —— 按原样发出去，让服务端自己报错，不要静默改写
        if (channel.isApp && !isFormContent(contentType)) {
            return executeRawPost(rawUrl, data, headers, contentType)
        }
        return executeChannelPost(endpoint, channel, rawUrl, data, headers, contentType)
    }

    /** POST 的通道分支（从 [post] 拆出，控制圈复杂度） */
    private fun executeChannelPost(
        endpoint: ApiEndpoint,
        channel: AuthChannel,
        rawUrl: String,
        data: String?,
        headers: List<String>?,
        contentType: String?
    ): Response {
        val httpUrl = rawUrl.toHttpUrlOrNull() ?: return executeRawPost(rawUrl, data, headers, contentType)
        val params = LinkedHashMap<String, String>()
        params.putAll(parseFormBody(data))
        params.putAll(queryParams(httpUrl))
        // web 通道 POST 不需要换端点（appPath 是给 APP 用的），只有 APP 通道才改写
        channel.adaptParams(endpoint, params)
        val targetUrl = if (channel.isApp) appBase(endpoint) else httpUrl.toString()
        val body = encodeParams(params)
        val merged = mergeHeaders(channel.headers(endpoint), headers?.let { ArrayList(it) })
        val response = executeRawPost(targetUrl, body, merged, CONTENT_TYPE_FORM)
        return judgeOrReturn(endpoint, channel, response)
    }

    private fun isFormContent(contentType: String?): Boolean {
        if (contentType == null) return false
        return contentType.contains("x-www-form-urlencoded", ignoreCase = true)
    }

    // ==================== 目标 URL 构造 ====================

    /** APP 通道的 GET URL：host/path 换过去 + 参数适配 + 签名（已由 adaptParams 完成） */
    private fun buildAppGetUrl(endpoint: ApiEndpoint, channel: AuthChannel, httpUrl: okhttp3.HttpUrl): String {
        if (!channel.isApp) return httpUrl.toString()
        val params = queryParams(httpUrl)
        channel.adaptParams(endpoint, params)
        val base = appBase(endpoint)
        return base + encodeParams(params)
    }

    /** 目标 base（`https://host + path`），APP 通道按 endpoint 的 appHost/appPath 改写 */
    private fun appBase(endpoint: ApiEndpoint): String {
        val host = endpoint.appHost ?: endpoint.host
        val path = endpoint.appPath ?: endpoint.path
        return "https://" + host + path
    }

    /** 从原始 URL 取 query 参数（保留顺序；不做丢弃，丢弃交给 channel.adaptParams） */
    private fun queryParams(httpUrl: okhttp3.HttpUrl): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        for (name in httpUrl.queryParameterNames) {
            params[name] = httpUrl.queryParameter(name) ?: ""
        }
        return params
    }

    /** 有序参数 → query 串（带前导问号；空参数返回空串） */
    private fun encodeParams(params: LinkedHashMap<String, String>): String {
        val formData = NetWorkUtil.FormData().setUrlParam(true)
        for ((key, value) in params) formData.put(key, value)
        return formData.toString()
    }

    /** 表单体解析（与 `ApiRouter.parseFormBody` 口径一致，含 URL 解码） */
    private fun parseFormBody(body: String?): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        if (body.isNullOrEmpty()) return params
        for (pair in body.split("&")) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val key = decodeComponent(if (index >= 0) pair.substring(0, index) else pair)
            val value = decodeComponent(if (index >= 0) pair.substring(index + 1) else "")
            if (key.isNotEmpty()) params[key] = value
        }
        return params
    }

    private fun decodeComponent(raw: String): String {
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (ignored: IllegalArgumentException) {
            // 手工构造的畸形百分号编码：保留原值继续（与 ApiRouter 一致）
            Logu.d(TAG, "参数解码失败：" + ignored.javaClass.simpleName)
            raw
        }
    }

    // ==================== 请求头合并 ====================

    /**
     * 合并请求头（调用方的在前，通道的在后）。
     *
     * 为什么通道的在后：同名头以**后出现者**为准（OkHttp 的 addHeader 语义），
     * 而通道生成的 Cookie / app-key / User-Agent 必须覆盖调用方可能传的旧值 ——
     * 否则切端口后仍带着上一个端口的 Cookie，服务端会把账号认错。
     */
    private fun mergeHeaders(channelHeaders: ArrayList<String>, callerHeaders: ArrayList<String>?): ArrayList<String> {
        if (callerHeaders.isNullOrEmpty()) return channelHeaders
        val merged = ArrayList<String>(callerHeaders.size + channelHeaders.size)
        merged.addAll(callerHeaders)
        merged.addAll(channelHeaders)
        return merged
    }

    // ==================== 判定 ====================

    /**
     * 判定响应并返回。
     *
     * **不做任何跨端口动作**：
     * - 鉴权类业务码 → 先给通道一次自愈机会（[AuthChannel.onAuthFailure]）；
     *   但**自愈只在同一个通道内**（web 刷新 Cookie / APP 无操作），不会换端口；
     * - 其它情况（正常、业务错误、风控壳、非 JSON）→ **原样返回**，让上层按 code 提示用户。
     *
     * 为什么「原样返回」而不是抛异常：上层几十处调用都是
     * `JSONObject(NetWorkUtil.get(url).body.string())` 然后看 `code`。
     * 返回真响应能让它们照常显示「-101 账号未登录」这类业务提示；
     * 若抛异常，那些调用点会落到 catch-all 里显示「网络异常」，反而更难懂。
     */
    private fun judgeOrReturn(endpoint: ApiEndpoint, channel: AuthChannel, response: Response): Response {
        if (!response.isSuccessful) {
            Logu.d(TAG, "HTTP " + response.code + " " + endpoint.id)
            return response
        }
        if (endpoint.binaryResponse) return response
        val code = readBusinessCode(response)
        if (code == null) return response
        channel.onSuccess(endpoint, code)
        if (AuthChannel.AUTH_FAILURE_CODES.contains(code)) {
            // 只允许本通道自愈一次；绝不允许换端口
            val renewed = channel.onAuthFailure(endpoint, code)
            Logu.d(TAG, "鉴权失败 " + endpoint.id + " code=" + code + " 自愈=" + renewed)
            throw AuthFailureException(code, endpoint.id)
        }
        return response
    }

    /**
     * 读回包里的 `code`（只读前 16KB 前缀，够覆盖正常 JSON 的头部）。
     *
     * 用 Regex 而不是 `JSONObject`：前缀可能被截断，JSONObject 会直接抛；
     * 而这里只需要 code，正则足够且不会因为截断把一个正常响应判死。
     */
    private fun readBusinessCode(response: Response): Int? {
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return null
        val text = try {
            response.peekBody(PEEK_BYTES).string()
        } catch (ignored: IOException) {
            Logu.d(TAG, "读取响应前缀失败：" + ignored.javaClass.simpleName)
            return null
        }
        val match = CODE_REGEX.find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** 读取响应前缀的字节上限（正常 JSON 的 code 字段总在最前面） */
    private const val PEEK_BYTES = 16L * 1024L

    private val CODE_REGEX = Regex("\"code\"\\s*:\\s*(-?\\d+)")

    // ==================== 裸请求（不经通道） ====================

    /**
     * 不经通道适配、直接发 GET。
     *
     * 三个用途：
     * 1. 端点不在 catalog 里（未登记的端点，按原样透传）；
     * 2. 端点是 IMPOSSIBLE（登录握手、静态文件、非 B 站域名 —— 它们本来就只有一种形态）；
     * 3. URL 解析失败（畸形 URL，交给 OkHttp 自己报）。
     */
    @Throws(IOException::class)
    private fun executeRawGet(url: String, headers: ArrayList<String>?): Response {
        Logu.d(TAG, "GET " + maskSensitive(url))
        val client = NetWorkUtil.getOkHttpInstance()
        val builder = Request.Builder().url(url).get()
        addHeaders(builder, headers)
        return client.newCall(builder.build()).execute()
    }

    /** 不经通道适配、直接发 POST（同上三种用途） */
    @Throws(IOException::class)
    private fun executeRawPost(
        url: String,
        data: String?,
        headers: List<String>?,
        contentType: String?
    ): Response {
        Logu.d(TAG, "POST " + maskSensitive(url))
        val client = NetWorkUtil.getOkHttpInstance()
        val safeContentType = contentType ?: CONTENT_TYPE_FORM
        val body = (data ?: "").toRequestBody((safeContentType + "; charset=utf-8").toMediaTypeOrNull())
        val builder = Request.Builder().url(url).post(body)
        addHeaders(builder, headers?.let { ArrayList(it) })
        return client.newCall(builder.build()).execute()
    }

    /**
     * 逐对写请求头。
     *
     * `Content-Type` 特殊处理：OkHttp 已经由 RequestBody 定了 Content-Type，
     * 再 addHeader 会变成两个同名头（服务端可能取到错的那个），所以遇到就跳过。
     */
    private fun addHeaders(builder: Request.Builder, headers: List<String>?) {
        val list = headers ?: return
        var i = 0
        while (i + 1 < list.size) {
            val key = list[i]
            val value = list[i + 1]
            if (!key.equals("Content-Type", ignoreCase = true)) builder.addHeader(key, value)
            i += 2
        }
    }

    // ==================== 日志脱敏 ====================

    /**
     * 日志脱敏。
     *
     * 规格书 §0.4 硬要求「不打印任何凭据」。APP 通道把 `access_key` 放在 query 里，
     * web 通道的 Cookie 在请求头里，任何日志都必须先过这里。
     * **只影响日志输出，不改变真正发送的数据。**
     */
    private fun maskSensitive(text: String): String {
        return text.replace(SENSITIVE_REGEX, "$1=***")
    }

    private val SENSITIVE_REGEX = Regex(
        "(access_key|refresh_token|csrf|SESSDATA|bili_jct)=[^&]*",
        RegexOption.IGNORE_CASE
    )

    /** 表单 Content-Type 常量（APP 通道唯一支持的写操作形态） */
    private const val CONTENT_TYPE_FORM = "application/x-www-form-urlencoded"

    // ==================== 通道自检（给设置页 / 报告用） ====================

    /**
     * 当前选中的通道摘要（中文，不含任何凭据）。
     *
     * 给设置页「账号管理」与覆盖报告用：用户能一眼看到「现在到底是哪个端口在干活」。
     */
    @JvmStatic
    fun activeChannelSummary(): String {
        val channel = CredentialStore.activeChannel()
        val portName = AccountStore.portDisplayName(channel.port)
        val loginText = if (channel.isLoggedIn()) "已登录" else "未登录"
        val kind = if (channel.isApp) "APP 通道（access_key 加 appkey 签名）" else "Web 通道（Cookie 加 csrf）"
        return portName + " · " + kind + " · " + loginText
    }

    /**
     * 快速自检：当前通道能不能跑「任意端口全功能」。
     *
     * 返回未可用端点的数量与样例（最多 5 条），**不打印凭据**。
     * 这是给报告直接引用的一句话证据，避免「已实现」这种无法验证的说法。
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun selfCheck(): String {
        val port = AccountStore.mainPort()
        val summary = ApiCatalog.coverageSummary(port)
        val unavailable = ArrayList<String>()
        for (row in ApiCatalog.coverageReport(port)) {
            if (!row.available && unavailable.size < 5) unavailable.add(row.endpointId)
        }
        val sample = if (unavailable.isEmpty()) "无" else unavailable.joinToString("、")
        return summary.describe() + "；不可用样例：" + sample
    }
}
