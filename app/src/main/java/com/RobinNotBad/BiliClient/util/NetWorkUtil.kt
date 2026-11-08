package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.api.migration.ApiRouter
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Inflater
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 被 luern0313 创建于 2019/10/13.
 * #以下代码来源于腕上哔哩的开源项目，感谢开源者做出的贡献！
 *
 * Kotlin 重构版（原 NetWorkUtil.java）。
 * 所有方法 @JvmStatic、常量 @JvmField，保持 Java 调用端签名与行为不变。
 * uncompress 在原逻辑基础上增加了 needsInput 防护，修复数据异常时 inflate(0) 空转导致的卡死。
 */
object NetWorkUtil {

    private val INSTANCE = AtomicReference<OkHttpClient?>()

    /** 与原 synchronized(NetWorkUtil.class) 等价的锁对象 */
    private val COOKIE_LOCK = Any()

    /**
     * 连接池：弱网/4G 场景下复用 TCP 连接可以显著减少握手开销。
     * 默认 5 个空闲连接、5 分钟保活对本应用足够了。
     */
    private val CONNECTION_POOL = okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)

    class Inet4Selector : Dns {
        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> {
            val hosts = Dns.SYSTEM.lookup(hostname)
            val inet4Hosts = ArrayList<InetAddress>()
            for (host in hosts) {
                if (host.address.size == 4) inet4Hosts.add(host)
            }
            return inet4Hosts //筛选IPV4地址，IPV6请求有异常
        }
    }

    private fun getOrCreateClient(): OkHttpClient? {
        run {
            while (INSTANCE.get() == null) {
                INSTANCE.compareAndSet(
                    null,
                    setOkHttpSsl(OkHttpClient.Builder())
                        .followRedirects(false)
                        .addInterceptor { chain: Interceptor.Chain ->
                            val request = chain.request()
                            val response = chain.proceed(request)
                            val location = response.header("Location")
                            var isSslRedirect = false
                            try {
                                isSslRedirect = location != null && !request.isHttps &&
                                        URI(location).scheme.equals("https", ignoreCase = true) &&
                                        request.url.host.equals(URI(location).host, ignoreCase = true)
                            } catch (ignored: URISyntaxException) {
                            }

                            if (response.isRedirect && location != null) {
                                val handler = request.tag(RedirectHandler::class.java)
                                if (request.url.host == "b23.tv" && !isSslRedirect && handler != null) {
                                    handler.handleRedirect(location)
                                } else {
                                    val newRequest = request.newBuilder()
                                        .url(location)
                                        .build()
                                    return@addInterceptor chain.proceed(newRequest)
                                }
                            }
                            response
                        }
                        .addInterceptor(CookieSaveInterceptor())
                        // 弱网优化（#11）：复用连接、跳过无意义的压缩，
                        // 减少 4G 下的连接建立次数与 CPU 开销（连接握手是最费电的部分）
                        .addInterceptor(WeakNetworkInterceptor())
                        .dns(Inet4Selector())
                        .pingInterval(8, TimeUnit.SECONDS)
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(16, TimeUnit.SECONDS)
                        .connectionPool(CONNECTION_POOL)
                        .retryOnConnectionFailure(true)
                        .build()
                )
            }
            return INSTANCE.get()
        }
    }

    @JvmStatic
    fun getOkHttpInstance(): OkHttpClient {
        return getOrCreateClient()!!
    }

    @JvmStatic
    @Synchronized
    fun setOkHttpSsl(okhttpBuilder: OkHttpClient.Builder): OkHttpClient.Builder {
        //minSdk 29（Android 10）起系统 TLS 栈已足够完整，
        //旧版（API<=22）才需要的自建 SSL socket 工厂适配层已删除。
        return okhttpBuilder
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getJson(url: String?): JSONObject {
        val body = get(url)?.body ?: throw JSONException("在访问${url}时返回数据为空")
        body.use { return JSONObject(it.string()) }
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getJson(url: String?, headers: ArrayList<String>?): JSONObject {
        val body = get(url, headers)?.body ?: throw JSONException("在访问${url}时返回数据为空")
        body.use { return JSONObject(it.string()) }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?): Response {
        return get(url, webHeaders)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?, headers: ArrayList<String>?): Response {
        return get(url, headers, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?, headers: ArrayList<String>?, redirectHandler: RedirectHandler?): Response {
        // ============================================================================
        // v5.2.1：**去掉「回退 web」**（用户明确要求「四端分开，去掉回退Web的功能」）
        //
        // ## 旧行为（v5.2.0 及更早）
        // 端点命中 APP 通道 → 发 APP 请求 → 如果响应「不可用」（-101/非 JSON/HTTP 错误…）
        // → **再用 web 头重发同一条请求**。问题有三层：
        // 1. **半残的直接来源**：web 没登录时，回退带的是空 Cookie，必然再失败一次，
        //    用户看到的就是「明明端口登录着，功能却报 -101」。白等一个 RTT 还误导排查。
        // 2. **掩盖真实故障**：APP 通道真的有问题时，回退会让它「看起来能用」，
        //    故障被 web 的成功响应盖住，永远没人修。
        // 3. **语义混乱**：用户选的是「所有接口走这个端口」，代码却偷偷换了通道。
        //
        // ## 新行为
        // 命中 APP 通道后，**该通道就是唯一通道**：
        // - 响应可用 → 直接返回（与旧行为一致）；
        // - 响应不可用（业务码/结构/风控壳）→ **原样返回这个响应**，
        //   由上层各 Api 按 code 提示用户。不再重发、不再换通道。
        // - 网络异常 → **原样抛出** IOException，由上层按网络错误提示。
        //
        // 为什么「原样返回坏响应」比「抛异常」好：上层几十处调用都是
        // `val result = JSONObject(NetWorkUtil.get(url).body!!.string())` 然后看 `code`，
        // 返回真响应能让它们照常显示「-101 账号未登录」这类业务提示；
        // 若抛异常，这些调用点会落到 catch-all 里显示「网络异常」，反而更难懂。
        //
        // ## 什么时候仍然走 web
        // 只有 `planGet` 返回 null 时 —— 也就是**端点不在 APP 通道目录里**（KEEP_WEB：
        // passport 登录协议、grpc-web、非 B 站域名、静态文件等）或用户选的端口本来就是 web。
        // 这不是「回退」，是「这条端点本来就只有 web 实现」。
        // ============================================================================
        val plan = ApiRouter.planGet(url)
        if (plan != null) {
            val appResponse = executeGet(plan.appUrl, plan.headers, redirectHandler)
            val verdict = judge(plan, appResponse)
            if (verdict.isOk) {
                ApiRouter.recordHit(plan)
                return appResponse
            }
            // v5.3.1：回退做成**开关**（用户需求「回退 web 可以开关」）。
            //
            // 默认关闭 = 保持 v5.2.1~v5.3.0 的行为（坏响应原样交给调用方，让业务码自己说话）。
            // 打开后：用**原始 URL + 原始 headers（web 头）**重发一次。
            //
            // 为什么会有人想打开：只登了 web 的用户、或某端点在 APP 通道长期不可用（-352 风控等），
            // 关着就等于功能直接消失。代价（必须让用户知道）是「APP 通道的故障会被 web 的
            // 成功响应掩盖」，所以它是个**要用户自己承担**的选项，不是默认值。
            //
            // 安全边界由 ApiRouter.shouldFallbackToWeb 收口：非幂等写操作永远不回退。
            // GET 全是幂等读，所以这里返回 true 只取决于开关。
            if (ApiRouter.shouldFallbackToWeb(plan)) {
                ApiRouter.recordFallback(plan, verdict.reason)
                Logu.d("api-fallback", "APP 通道不可用，按开关回退 web：" + plan.endpoint)
                return executeGet(url, headers, redirectHandler)
            }
            // 不再回退：把这个坏响应交给调用方，让业务码自己说话
            ApiRouter.recordNoFallback(plan, verdict.reason)
            return appResponse
        }
        return executeGet(url, headers, redirectHandler)
    }

    /**
     * 迁移判定外包一层异常保护。
     *
     * [ApiRouter.classify] 自身已经尽量不外抛，但它要读响应体、解析 JSON、查规则字段；
     * 任何一处意外（响应体读取异常、规则表异常、URL 异常等）都会从这里直接冒到调用方的
     * 后台线程。Kotlin 的 @Throws 不强制捕获，后台线程未捕获异常会让整个进程崩溃 ——
     * 用户看到的就是一次闪退。判定失败最多多走一次 web 原请求，绝不能让它成为崩溃源。
     */
    private fun judge(plan: ApiRouter.Plan, response: Response): ApiRouter.Verdict {
        return try {
            ApiRouter.classify(response, plan)
        } catch (e: Exception) {
            ApiRouter.Verdict(isOk = false, reason = "判定异常：" + e.javaClass.simpleName)
        }
    }

    /**
     * 日志脱敏：APP 通道会把 access_key 放在 query / body 里，
     * 任何日志都不能打印 token / cookie（全 API 迁移的硬性要求）。
     * 只影响 Logu 的输出，不改变真正发送的数据。
     */
    private fun maskSensitive(text: String): String {
        return text.replace(SENSITIVE_PARAM_REGEX, "${'$'}1=***")
    }

    private val SENSITIVE_PARAM_REGEX =
            Regex("(access_key|refresh_token|csrf|SESSDATA|bili_jct)=[^&]*")

    /** 真正发起 GET 的原始实现（迁移层只在外面包一层，行为保持与历史版本一致） */
    @Throws(IOException::class)
    private fun executeGet(url: String?, headers: List<String>?, redirectHandler: RedirectHandler?): Response {
        Logu.d("get-url", maskSensitive(url ?: ""))
        val safeUrl = url ?: throw IOException("请求 URL 为空")
        val headerList = headers ?: emptyList()
        val client = getOkHttpInstance()
        val requestBuilder = Request.Builder().url(safeUrl).get()
        var i = 0
        while (i + 1 < headerList.size) {
            requestBuilder.addHeader(headerList[i], headerList[i + 1])
            i += 2
        }
        if (redirectHandler != null) requestBuilder.tag(RedirectHandler::class.java, redirectHandler)
        val request = requestBuilder.build()
        return client.newCall(request).execute()
    }

    /**
     * POST 请求（v5.2.1：**同样去掉回退 web**）。
     *
     * ## 与 GET 的差异：这里还多一层「不许制造重复副作用」
     * GET 天然幂等，旧行为只是「多发一次请求」。POST 是写操作，重放可能造成
     * **真实副作用重复**（投两个币、发两条一样的评论）。所以旧代码对 POST 的分类
     * （replayable / 非 replayable + 「铁定没发出去」判断）本身是对的，保留其**安全思想**，
     * 但把「回退 web」这一环彻底删掉：
     *
     * - 命中 APP 通道后，该通道就是唯一通道；
     * - 响应不可用（业务码/结构/风控壳）→ 原样返回给上层按 code 提示；
     * - 网络异常 → 原样抛出（`IOException`），由上层提示网络错误；
     * - **任何情况下都不再换用 web 重发**。
     *
     * 为什么这样更安全（而不是更不安全）：
     * 旧逻辑里「回退 web」对非幂等写操作本来就**几乎不可用**（可能已发送就不敢重放），
     * 而对幂等写操作（点赞/收藏/关注）它会让「APP 通道坏了」永远不被发现。
     * 删掉回退之后，「失败原因」第一次变得诚实：出问题就是出问题，不会假装成功。
     *
     * ## 「铁定没发出去」那套判断去哪了
     * 删了。它存在的唯一目的是「判断能不能安全重放」，而不回退之后就不存在要不要重放的决策。
     * 留着它只会变成一段永远 return false 的死代码，误导后来的人以为还有重放路径。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun post(url: String?, data: String?, headers: List<String>?, contentType: String?): Response {
        val plan = ApiRouter.planPost(url, data, contentType)
        if (plan != null) {
            val appResponse = executePost(plan.appUrl, plan.body, plan.headers, plan.contentType)
            val verdict = judge(plan, appResponse)
            if (verdict.isOk) {
                ApiRouter.recordHit(plan)
                return appResponse
            }
            // v5.3.1：POST 也受同一个开关控制，但**安全边界更严**。
            //
            // ApiRouter.shouldFallbackToWeb 会检查 plan.replayable：
            // - 幂等写（点赞/收藏/关注/稍后再看）→ 开关打开时允许回退重发；
            // - 非幂等写（投币/发评论/发动态/发弹幕/发私信）→ **永远 false**，
            //   即使开关打开也不回退。请求可能已经到达服务端，重发会造成
            //   重复投币/重复评论这类不可撤销的副作用。
            //
            // 注意：这里**不再需要** v5.2.0 那套「判断请求有没有真的发出去」的逻辑。
            // 那套逻辑存在的唯一目的是「决定能不能安全重放」，而现在「能不能重放」
            // 在规则表里已经静态确定了（isPostSafe / isPostWrite），运行时再猜一次
            // 只会引入不确定性。
            if (ApiRouter.shouldFallbackToWeb(plan)) {
                ApiRouter.recordFallback(plan, verdict.reason)
                Logu.d("api-fallback", "APP 通道不可用，按开关回退 web（幂等写）：" + plan.endpoint)
                return executePost(url, data, headers, contentType)
            }
            // 不回退：坏响应交给上层按业务码报错
            ApiRouter.recordNoFallback(plan, verdict.reason)
            return appResponse
        }
        return executePost(url, data, headers, contentType)
    }

    /** 真正发起 POST 的原始实现（迁移层只在外面包一层，行为保持与历史版本一致） */
    @Throws(IOException::class)
    private fun executePost(url: String?, data: String?, headers: List<String>?, contentType: String?): Response {
        Logu.d("post-url", maskSensitive(url ?: ""))
        Logu.d("post-data", maskSensitive(data ?: ""))
        val safeUrl = url ?: throw IOException("请求 URL 为空")
        val safeContentType = contentType ?: "application/json"
        val headerList = headers ?: emptyList()
        val client = getOkHttpInstance()
        val mediaType = (safeContentType + "; charset=utf-8").toMediaTypeOrNull()
        val body = (data ?: "").toRequestBody(mediaType)
        val requestBuilder = Request.Builder().url(safeUrl).post(body)
        var i = 0
        while (i + 1 < headerList.size) {
            val key = headerList[i]
            val value = headerList[i + 1]
            if (key.equals("Content-Type", ignoreCase = true)) requestBuilder.addHeader(key, safeContentType)
            else requestBuilder.addHeader(key, value)
            i += 2
        }
        val request = requestBuilder.build()
        return client.newCall(request).execute()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun post(url: String?, data: String?, headers: List<String>?): Response {
        return post(url, data, headers, "application/x-www-form-urlencoded")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun postJson(url: String?, data: String?, headers: List<String>?): Response {
        return post(url, data, headers, "application/json")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun postJson(url: String?, data: String?): Response {
        return post(url, data, webHeaders, "application/json")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun post(url: String?, data: String?): Response {
        return post(url, data, webHeaders)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readStream(inStream: InputStream): ByteArray {
        val outStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var len: Int
        while (inStream.read(buffer).also { len = it } != -1) {
            outStream.write(buffer, 0, len)
        }
        outStream.close()
        inStream.close()
        return outStream.toByteArray()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun uncompress(inputByte: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream(inputByte.size)
        try {
            val inflater = Inflater(true)
            inflater.setInput(inputByte)
            val buffer = ByteArray(4 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                outputStream.write(buffer, 0, count)
                // 防护：数据异常时 inflate 返回0且需要输入，原实现会在此空转卡死
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            }
            inflater.end()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return outputStream.toByteArray()
    }

    @JvmStatic
    fun getInfoFromCookie(name: String, cookie: String): String {
        val cookies = cookie.split("; ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (i in cookies) {
            if (i.contains("$name=")) return i.substring(name.length + 1)
        }
        return ""
    }

    /**
     * 把一条 `Set-Cookie` 规整成 `key=value`；不该保存时返回 null。
     *
     * 三条规则与原实现逐条对应（只是把两个 `continue` 变成一次「返回 null」）：
     * 1. 带了 `Domain` 但不属于 bilibili.com → 丢弃；
     * 2. 截掉第一个 `; ` 之后的属性段（Path/Expires/Secure…）；
     * 3. 没有 `=` 的畸形值 → 丢弃。
     *
     * F6：**不打印任何 Cookie 值**。
     */
    private fun normalizeSetCookie(raw: String): String? {
        val cookies = Cookies(raw)
        // 用 ?. 链式取值替代 !!：`Domain` 存在但值为 null 时按「没有域限制」处理，
        // 与原实现 `containsKey("Domain") && cookies["Domain"]!!.endsWith(...)` 的实际分支一致
        // （原写法在该情形会抛 NPE，而不是保存这条 Cookie）。
        val domain = cookies["Domain"]
        if (domain != null && !domain.endsWith("bilibili.com")) return null
        val semicolon = raw.indexOf("; ")
        val trimmed = if (semicolon != -1) raw.substring(0, semicolon) else raw
        if (trimmed.indexOf("=") <= 0) return null
        return trimmed
    }

    /**
     * 把一条 `key=value` 合并进旧 Cookie 列表：同 key 覆盖、否则追加。
     *
     * 同 key 判据沿用历史实现（前缀 `key=` 的 contains 匹配），行为未改。
     */
    private fun mergeCookie(oldCookies: ArrayList<String>, newCookie: String) {
        val equals = newCookie.indexOf("=") + 1
        if (equals == 0) return
        val key = newCookie.substring(0, equals) //key=
        val existing = oldCookies.indexOfFirst { it.contains(key) }
        if (existing >= 0) oldCookies[existing] = newCookie else oldCookies.add(newCookie)
    }

    private fun saveCookiesFromResponse(response: Response) {
        val newCookies = response.headers("Set-Cookie")

        //如果没有新cookies，直接返回
        if (newCookies.isEmpty()) return
        val cookiesStr = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        val oldCookies: ArrayList<String> =
            if (cookiesStr.isEmpty()) ArrayList() else ArrayList(listOf(*cookiesStr.split("; ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())) //转list

        for (newCookieIn in newCookies) { //对每一条新cookie遍历
            // 逐条判定与合并拆成两个纯函数：循环体里只剩一次「不该存就跳过」，
            // 既避免一条循环里多个 break/continue，也让「域过滤 + 去属性」能单独读。
            val newCookie = normalizeSetCookie(newCookieIn) ?: continue
            mergeCookie(oldCookies, newCookie)
        }

        val setCookies = StringBuilder()
        for (setCookie in oldCookies) {
            setCookies.append(setCookie).append("; ")
        }
        //如果一次setCookies都没有，就不要存了， 因为是个空字符串
        if (setCookies.length >= 2) {
            // F6：这里原来打印合并后的**完整 Cookie 串**（含 SESSDATA/bili_jct）。
            // 改成只记条数，排查「Cookie 有没有写进去」够用，且不泄露任何值。
            Logu.d("save-result", "已保存 Cookie：" + oldCookies.size + " 项")
            SharedPreferencesUtil.putString(
                SharedPreferencesUtil.cookies,
                setCookies.substring(0, setCookies.length - 2)
            )
            refreshHeaders()
        }
    }

    /**
     * 存储单个Cookie
     *
     * @param key 键
     * @param val 值
     */
    @JvmStatic
    fun putCookie(key: String, `val`: String) {
        synchronized(COOKIE_LOCK) {
            val cookies = Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
            cookies[key] = `val`
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies.toString())
            refreshHeaders()
        }
    }

    /**
     * 存储Cookies（覆盖写入）
     *
     * @param cookies cookies
     */
    @JvmStatic
    fun setCookies(cookies: Cookies?) {
        synchronized(COOKIE_LOCK) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies!!.toString())
            refreshHeaders()
        }
    }

    /**
     * 获取存储的Cookies
     *
     * @return 存储的Cookies
     */
    @JvmStatic
    fun getCookies(): Cookies {
        synchronized(COOKIE_LOCK) {
            return Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
        }
    }

    @JvmField
    val USER_AGENT_WEB: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Safari/537.36"

    /**
     * 读取持久化的 Cookie（供 [webHeaders] 静态初始化使用）。
     *
     * **为什么要有这个函数**：object 的属性在 `<clinit>` 里按声明顺序执行，
     * 这里是 NetWorkUtil 第一次被类加载时跑。如果那时 `BiliTerminal.onCreate`
     * 还没给 `SharedPreferencesUtil.sharedPreferences` 赋值（例如任何在
     * Application.onCreate 之前/更早的进程入口触碰本类），
     * `SharedPreferencesUtil.getString()` 内部的 `sharedPreferences!!`
     * 会抛 **ExceptionInInitializerError → 之后全变 NoClassDefFoundError**。
     * 拿不到就退化成「无 Cookie」，等 onCreate 后再由 [refreshHeaders] 补齐。
     */
    private fun initialCookie(): String {
        return try {
            SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        } catch (t: Throwable) {
            // 不能吞掉线索：静态初始化失败最容易被误判成「随机拿不到 Cookie」，
            // 这里把原因打出来（Logu.w 不受 LOGD_ENABLED 开关影响，release 也能看到）。
            Logu.w("网络头", "静态初始化时读取 Cookie 失败，先用空 Cookie：" + t.message)
            ""
        }
    }

    @JvmField
    val webHeaders: ArrayList<String> = object : ArrayList<String>() {
        init {
            add("Cookie")
            add(initialCookie())

            add("Origin")
            add("https://www.bilibili.com")

            add("Referer")
            add("https://www.bilibili.com/")

            add("User-Agent")
            add(USER_AGENT_WEB)

            add("Sec-Ch-Ua")
            add("\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")

            add("Sec-Ch-Ua-Platform")
            add("\"Windows\"")

            add("Sec-Ch-Ua-Mobile")
            add("?0")
        }
    }

    @JvmStatic
    fun refreshHeaders() {
        if (SharedPreferencesUtil.sharedPreferences == null) return
        webHeaders[1] = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
    }

    /**
     * web Cookie 请求头的值；**web 未登录（Cookie 为空）时返回 null**。
     *
     * 用途：媒体直链 / CDN 预加载 / 直播 WebSocket 这类请求里，Cookie 只有 web 登录后才有意义
     * （直链本身自带 `deadline`/`upsig` 签名，鉴权不靠 Cookie）。历史代码一律塞
     * `header("Cookie", "")`，等于给每个请求加一个**空 Cookie 头**——服务端侧无意义，
     * 还容易误导成「这里必须 web 登录才能用」。改为「非空才加」，语义更准确。
     *
     * 调用方写法：`NetWorkUtil.cookieHeaderOrNull()?.let { builder.header("Cookie", it) }`
     */
    @JvmStatic
    fun cookieHeaderOrNull(): String? {
        return (SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "").takeIf { it.isNotEmpty() }
    }

    class FormData {
        private val data: MutableMap<String, String> = HashMap()
        private var isUrlParam = false

        fun remove(key: String?): FormData {
            data.remove(key)
            return this
        }

        fun put(key: String?, value: Any?): FormData {
            data[key!!] = value.toString()
            return this
        }

        fun setUrlParam(isUrlParam: Boolean): FormData {
            this.isUrlParam = isUrlParam
            return this
        }

        override fun toString(): String {
            val sb = StringBuilder()
            if (isUrlParam) sb.append("?")
            try {
                for (key in data.keys) {
                    if (sb.length > if (isUrlParam) 1 else 0) {
                        sb.append("&")
                    }
                    sb.append(URLEncoder.encode(key, "UTF-8"))
                    sb.append("=")
                    sb.append(URLEncoder.encode(data[key], "UTF-8"))
                }
            } catch (e: UnsupportedEncodingException) {
                throw RuntimeException(e)
            }
            return sb.toString()
        }
    }

    interface RedirectHandler {
        fun handleRedirect(location: String?)
    }

    private class CookieSaveInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            saveCookiesFromResponse(response)
            return response
        }
    }
}
