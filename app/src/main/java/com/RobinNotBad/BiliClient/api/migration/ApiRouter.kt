package com.RobinNotBad.BiliClient.api.migration

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「全 API 迁移（实验）」路由器。
 *
 * 调用链：`NetWorkUtil.get()/post()` → [planGet]/[planPost] → 成功则 [classify] 判定 →
 * 命中就返回 APP 通道响应；不命中/失败就由 NetWorkUtil 回退 web 原请求一次。
 *
 * 设计要点（与 [ApiMigrationRules] 的注释互补）：
 * - **零回归**：开关关闭时 [planGet]/[planPost] 立刻返回 null，NetWorkUtil 走原来的代码
 *   路径，行为与改动前完全一致；不新增线程、不新增存储读写以外的副作用。
 * - **身份跟随**：不用另一套 appkey/token，全部复用 `AppRcmdApi` 的三身份体系
 *   （HD / TV / 手机 的 appkey/appsec、UA、mobi_app、独立 token 槽位）。
 * - **未登录只走公共接口**：当前身份没有 token 时，只迁移「匿名 APP 与 web 数据一致」的
 *   公共接口；收藏 / 历史 / 动态 / 消息 / 播放地址等接口保持 web——它们匿名请求可能
 *   返回 code=0 但数据为空，属于回退检测兜不住的静默错误。
 * - **回退**：-101 / -352 / -400 / -401 / -403 / -404 / -412 / -500 / -504、HTTP 错误、
 *   非 JSON、网络异常都回退 web 一次，并累计命中/回退次数与最近失败端点。
 * - **自动停用**：同一端点在 5 分钟内连续失败 2 次 → 暂停该端点的 APP 通道 10 分钟，
 *   直接走 web，避免每次请求都先失败再回退、白等一个 RTT。
 * - **不打印凭据**：日志只记 host+path 与失败原因，绝不打印 access_key / cookie /
 *   refresh_token。
 */
object ApiRouter {

    // ==================== 偏好键 ====================

    private const val KEY_HITS = "api_migration_stat_hits"
    private const val KEY_FALLBACKS = "api_migration_stat_fallbacks"
    private const val KEY_SKIPS = "api_migration_stat_skips"
    private const val KEY_FAILURES = "api_migration_recent_failures"

    /** 最近失败端点最多保留条数（设置页展示用） */
    private const val MAX_FAILURES = 8

    /** 同一端点连续失败达到该次数后暂停 APP 通道 */
    private const val SUSPEND_AFTER_FAILURES = 2

    /** 连续失败判定的时间窗口：超过 5 分钟重新计数 */
    private const val FAILURE_WINDOW_MS = 5 * 60 * 1000L

    /** 暂停时长：10 分钟 */
    private const val SUSPEND_MS = 10 * 60 * 1000L

    /** 只读前 16KB 判断 code，够覆盖正常 JSON 的头部（code 字段总在最前面） */
    private const val PEEK_BYTES = 16L * 1024L

    /** 这些 code 代表「APP 通道没走通」，需要回退 web */
    private val RETRY_CODES = intArrayOf(-101, -352, -400, -401, -403, -404, -412, -500, -504)

    /**
     * 迁移时需要丢弃的参数：
     * - appkey/ts/sign/access_key/buvid 由本层按当前身份重新生成；
     * - mobi_app/platform/build/device_name 由身份覆盖；
     * - w_rid/wts 是 web 的 WBI 签名参数，APP 参数下会让服务端校验失配；
     * - csrf 是 web Cookie 登录的 CSRF，APP 通道用 access_key；
     * - jsonp/web_location/gaia_source/isGaiaAvoided 是 web 专用装饰参数。
     */
    private val DROPPED_PARAMS = hashSetOf(
            "appkey", "ts", "sign", "access_key", "buvid",
            "mobi_app", "platform", "build", "device", "device_name",
            "w_rid", "wts", "csrf", "jsonp", "web_location",
            "gaia_source", "isgaiaavoided"
    )

    private const val TAG = "api-migrate"

    private val STAT_LOCK = Any()

    private val CODE_REGEX = Regex("\"code\"\\s*:\\s*(-?\\d+)")

    private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    // ==================== 请求计划 ====================

    /**
     * 一次 APP 通道请求的完整描述。
     *
     * @param endpoint 原始 host+path（统计 / 失败列表用的稳定键，不含 query，避免泄露参数）
     */
    data class Plan(
        val method: String,
        val originalUrl: String,
        val appUrl: String,
        val headers: ArrayList<String>,
        val body: String?,
        val contentType: String?,
        val endpoint: String
    )

    /** 响应判定结果：ok=true 表示 APP 通道可用，false 时 reason 说明为什么要回退 */
    data class Verdict(val isOk: Boolean, val reason: String)

    /** 全 API 迁移开关（实验室，默认关） */
    @JvmStatic
    fun isEnabled(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.API_MIGRATION_ENABLED, false)
    }

    /**
     * 为一次 GET 请求生成 APP 通道计划。
     * @return null = 不迁移（开关关闭 / 未命中映射 / 端点被暂停），调用方保持原行为
     */
    @JvmStatic
    fun planGet(url: String?): Plan? {
        if (!isEnabled()) return null
        return try {
            buildGetPlan(url)
        } catch (e: Exception) {
            // 路由层自身出错时必须静默退回 web，绝不影响正常请求
            Logu.d(TAG, "planGet 失败：" + e.javaClass.simpleName)
            null
        }
    }

    /**
     * 为一次 POST 请求生成 APP 通道计划。
     * 只有 [ApiMigrationRules.Rule.isPostSafe] 的幂等写操作才会被迁移；其余写操作保持 web。
     */
    @JvmStatic
    fun planPost(url: String?, body: String?, contentType: String?): Plan? {
        if (!isEnabled()) return null
        if (contentType == null || !contentType.contains("x-www-form-urlencoded", ignoreCase = true)) return null
        return try {
            buildPostPlan(url, body)
        } catch (e: Exception) {
            Logu.d(TAG, "planPost 失败：" + e.javaClass.simpleName)
            null
        }
    }

    private fun buildGetPlan(url: String?): Plan? {
        val originalUrl = url ?: return null
        val httpUrl = originalUrl.toHttpUrlOrNull() ?: return null
        val host = httpUrl.host.lowercase(Locale.ROOT)
        val path = httpUrl.encodedPath
        val rule = ApiMigrationRules.find(host, path)
        if (rule != null && rule.kind == ApiMigrationRules.Kind.KEEP_WEB) return null
        // 未命中显式规则时，只对默认 APP 主机做尝试（「尽量覆盖所有 API」）
        if (rule == null && !ApiMigrationRules.allowsDefaultAppParams(host)) return null

        val endpoint = endpointOf(host, path)
        if (isSuspended(endpoint)) {
            bumpLong(KEY_SKIPS)
            return null
        }

        // 未登录当前客户端身份时：只有明确标记为公共接口的规则才迁移。
        // 原因见 ApiMigrationRules.Rule.isLoginRequired：匿名 APP 请求对收藏/历史/动态等
        // 可能返回 code=0 但数据为空，这种「静默空数据」无法靠回退检测兜住。
        val accessToken = AppRcmdApi.getAccessToken()
        if (accessToken.isEmpty() && (rule == null || rule.isLoginRequired)) return null

        val targetPath = if (rule != null && rule.kind == ApiMigrationRules.Kind.PATH_OVERRIDE) {
            rule.targetPath ?: path
        } else path

        val params = LinkedHashMap<String, String>()
        for (name in httpUrl.queryParameterNames) {
            if (DROPPED_PARAMS.contains(name.lowercase(Locale.ROOT))) continue
            params[name] = httpUrl.queryParameter(name) ?: ""
        }
        addIdentityParams(params, accessToken)

        val formData = NetWorkUtil.FormData().setUrlParam(true)
        for ((key, value) in params) formData.put(key, value)
        // 复用 AppRcmdApi 的签名算法（当前身份的 appkey / appsec / ts / sign）
        AppRcmdApi.sign(formData)

        val appUrl = "https://" + host + targetPath + formData.toString()
        return Plan("GET", originalUrl, appUrl, AppRcmdApi.appHeaders(), null, null, endpoint)
    }

    private fun buildPostPlan(url: String?, body: String?): Plan? {
        val originalUrl = url ?: return null
        val httpUrl = originalUrl.toHttpUrlOrNull() ?: return null
        val host = httpUrl.host.lowercase(Locale.ROOT)
        val path = httpUrl.encodedPath
        val rule = ApiMigrationRules.find(host, path) ?: return null
        if (rule.kind == ApiMigrationRules.Kind.KEEP_WEB || !rule.isPostSafe) return null

        val endpoint = endpointOf(host, path)
        if (isSuspended(endpoint)) {
            bumpLong(KEY_SKIPS)
            return null
        }

        // 写操作全部要求已登录（幂等清单里的接口都依赖账号身份）
        val accessToken = AppRcmdApi.getAccessToken()
        if (rule.isLoginRequired && accessToken.isEmpty()) return null

        val params = parseFormBody(body)
        for (name in httpUrl.queryParameterNames) {
            if (DROPPED_PARAMS.contains(name.lowercase(Locale.ROOT))) continue
            params[name] = httpUrl.queryParameter(name) ?: ""
        }
        addIdentityParams(params, accessToken)

        val formData = NetWorkUtil.FormData().setUrlParam(false)
        for ((key, value) in params) formData.put(key, value)
        AppRcmdApi.sign(formData)

        return Plan("POST", originalUrl, "https://" + host + path, AppRcmdApi.appHeaders(),
                formData.toString(), "application/x-www-form-urlencoded", endpoint)
    }

    /** 按当前客户端身份补 APP 公共参数（appkey/ts/sign 由 [AppRcmdApi.sign] 追加） */
    private fun addIdentityParams(params: LinkedHashMap<String, String>, accessToken: String) {
        val identity = AppRcmdApi.getIdentity()
        params["mobi_app"] = identity.feedMobiApp
        params["platform"] = "android"
        params["build"] = identity.feedBuild.toString()
        params["device_name"] = identity.feedDeviceName
        params["buvid"] = AppRcmdApi.getBuvid()
        if (accessToken.isNotEmpty()) params["access_key"] = accessToken
    }

    private fun parseFormBody(body: String?): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        if (body.isNullOrEmpty()) return params
        for (pair in body.split("&")) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val key = decodeFormComponent(if (index >= 0) pair.substring(0, index) else pair)
            val value = decodeFormComponent(if (index >= 0) pair.substring(index + 1) else "")
            if (key.isNotEmpty()) params[key] = value
        }
        return params
    }

    private fun decodeFormComponent(raw: String): String {
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            // 极端情况下（手工构造的畸形 %xx）解码会抛异常，保留原值继续
            Logu.d(TAG, "参数解码失败：" + e.javaClass.simpleName)
            raw
        }
    }

    private fun endpointOf(host: String, path: String): String {
        return host + path
    }

    // ==================== 响应判定 ====================

    /**
     * 判断 APP 通道响应是否可用。
     *
     * - HTTP 非 2xx、非 JSON、无 code 字段：回退；
     * - code=0：正常；
     * - code 属于 [RETRY_CODES]（未登录 / 风控 / 参数错）：回退；
     * - 其它业务错误码（如 12006 评论不存在、53013 隐私设置）：与通道无关，
     *   直接把 APP 响应交给上层，由原有逻辑处理，不浪费一次 web 请求。
     */
    @JvmStatic
    fun classify(response: Response): Verdict {
        if (!response.isSuccessful) return Verdict(isOk = false, reason = "HTTP " + response.code)
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return Verdict(isOk = false, reason = "非JSON响应")

        val text = try {
            response.peekBody(PEEK_BYTES).string()
        } catch (e: Exception) {
            return Verdict(isOk = false, reason = "响应读取失败：" + e.javaClass.simpleName)
        }
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return Verdict(isOk = false, reason = "响应不是JSON对象")
        val match = CODE_REGEX.find(trimmed) ?: return Verdict(isOk = false, reason = "响应缺少code字段")
        val code = match.groupValues[1].toIntOrNull() ?: return Verdict(isOk = false, reason = "code解析失败")
        if (code == 0) return Verdict(isOk = true, reason = "")
        for (retryCode in RETRY_CODES) {
            if (code == retryCode) return Verdict(isOk = false, reason = "code=" + code)
        }
        return Verdict(isOk = true, reason = "")
    }

    // ==================== 统计 ====================

    private class FailureState {
        @JvmField var consecutive = 0
        @JvmField var lastFailureAt = 0L
    }

    private val failureStates = HashMap<String, FailureState>()
    private val suspendedUntil = HashMap<String, Long>()
    private val attemptedEndpoints = HashSet<String>()

    /** 记录一次 APP 通道命中 */
    @JvmStatic
    fun recordHit(plan: Plan) {
        bumpLong(KEY_HITS)
        synchronized(STAT_LOCK) {
            attemptedEndpoints.add(plan.endpoint)
            failureStates.remove(plan.endpoint)
            suspendedUntil.remove(plan.endpoint)
        }
    }

    /**
     * 记录一次回退，并决定是否暂停该端点。
     * 注意：只记录 host+path 与原因，不记录 query（可能含 keyword 等用户输入）。
     */
    @JvmStatic
    fun recordFallback(plan: Plan, reason: String) {
        bumpLong(KEY_FALLBACKS)
        Logu.d(TAG, "回退 " + plan.endpoint + "：" + reason)
        synchronized(STAT_LOCK) {
            attemptedEndpoints.add(plan.endpoint)
            val now = System.currentTimeMillis()
            val state = failureStates.getOrPut(plan.endpoint) { FailureState() }
            if (now - state.lastFailureAt > FAILURE_WINDOW_MS) state.consecutive = 0
            state.consecutive++
            state.lastFailureAt = now
            if (state.consecutive >= SUSPEND_AFTER_FAILURES) {
                suspendedUntil[plan.endpoint] = now + SUSPEND_MS
                state.consecutive = 0
                Logu.d(TAG, "暂停 APP 通道 10 分钟：" + plan.endpoint)
            }

            val recent = loadRecentFailures().toMutableList()
            recent.add(0, formatFailure(plan.endpoint, reason, now))
            while (recent.size > MAX_FAILURES) recent.removeAt(recent.size - 1)
            SharedPreferencesUtil.putString(KEY_FAILURES, recent.joinToString("\n"))
        }
    }

    private fun formatFailure(endpoint: String, reason: String, time: Long): String {
        val timeText = synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date(time)) }
        return timeText + "  " + endpoint + "  · " + reason
    }

    private fun loadRecentFailures(): List<String> {
        val raw = SharedPreferencesUtil.getString(KEY_FAILURES, "") ?: ""
        if (raw.isEmpty()) return ArrayList()
        val list = ArrayList<String>()
        for (line in raw.split("\n")) {
            if (line.isNotEmpty()) list.add(line)
        }
        return list
    }

    private fun isSuspended(endpoint: String): Boolean {
        synchronized(STAT_LOCK) {
            val until = suspendedUntil[endpoint] ?: return false
            if (until > System.currentTimeMillis()) return true
            suspendedUntil.remove(endpoint)
            return false
        }
    }

    @JvmStatic
    fun hitCount(): Long = SharedPreferencesUtil.getLong(KEY_HITS, 0L)

    @JvmStatic
    fun fallbackCount(): Long = SharedPreferencesUtil.getLong(KEY_FALLBACKS, 0L)

    @JvmStatic
    fun skipCount(): Long = SharedPreferencesUtil.getLong(KEY_SKIPS, 0L)

    /** 本进程内已经尝试过的端点数量（重启归零，仅作展示） */
    @JvmStatic
    fun attemptedCount(): Int {
        synchronized(STAT_LOCK) {
            return attemptedEndpoints.size
        }
    }

    /** 当前被暂停的端点（最多展示用，重启归零） */
    @JvmStatic
    fun suspendedEndpoints(): List<String> {
        synchronized(STAT_LOCK) {
            val now = System.currentTimeMillis()
            val list = ArrayList<String>()
            for ((endpoint, until) in suspendedUntil) {
                if (until > now) list.add(endpoint)
            }
            list.sort()
            return list
        }
    }

    /** 最近失败端点（持久化，最新在前） */
    @JvmStatic
    fun recentFailures(): List<String> {
        synchronized(STAT_LOCK) {
            return loadRecentFailures()
        }
    }

    /**
     * 只清除「连续失败暂停」状态（不清空命中/回退统计）。
     * 用户重新打开开关时调用：立刻重新尝试被暂停的端点。
     */
    @JvmStatic
    fun clearSuspensions() {
        synchronized(STAT_LOCK) {
            suspendedUntil.clear()
            failureStates.clear()
        }
    }

    /** 清空全部统计与暂停状态（设置页「清空统计」） */
    @JvmStatic
    fun resetStats() {
        synchronized(STAT_LOCK) {
            SharedPreferencesUtil.putLong(KEY_HITS, 0L)
            SharedPreferencesUtil.putLong(KEY_FALLBACKS, 0L)
            SharedPreferencesUtil.putLong(KEY_SKIPS, 0L)
            SharedPreferencesUtil.putString(KEY_FAILURES, "")
            failureStates.clear()
            suspendedUntil.clear()
            attemptedEndpoints.clear()
        }
    }

    private fun bumpLong(key: String) {
        synchronized(STAT_LOCK) {
            SharedPreferencesUtil.putLong(key, SharedPreferencesUtil.getLong(key, 0L) + 1L)
        }
    }
}
