package com.RobinNotBad.BiliClient.api.migration

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import okhttp3.HttpUrl
// quality-allow：这里只需要在 16KB 的响应前缀里做「字段存在性」判定，
// 不是业务数据解析（业务解析仍在各 Api 对象里）。为一次浅层路径检查引入 Gson/序列化
// 或新建 model 得不偿失，故允许在本文件用轻量 org.json。
import org.json.JSONObject // quality-allow：仅做 16KB 前缀里的字段存在性判定，不是业务数据解析
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
    private const val KEY_NO_REPLAY = "api_migration_stat_no_replay"
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

    /**
     * 这些 code 代表「APP 通道没走通」，需要回退 web。
     *
     * - -799：请求过于频繁。APP 通道被限流时回退 web 才是用户想要的结果
     *   （实测 UP 主相关端点连查几次就会 -799，不回退会把限流错误直接抛给用户）。
     */
    private val RETRY_CODES = intArrayOf(-101, -352, -400, -401, -403, -404, -412, -500, -504, -799)

    /**
     * 迁移时需要丢弃的参数：
     * - appkey/ts/sign/access_key/buvid 由本层按当前身份重新生成；
     * - mobi_app/platform/build/device_name 由身份覆盖；
     * - w_rid/wts 是 web 的 WBI 签名参数，APP 参数下会让服务端校验失配；
     * - csrf 是 web Cookie 登录的 CSRF，APP 通道用 access_key；
     * - jsonp/web_location/gaia_source/isGaiaAvoided 是 web 专用装饰参数；
     * - dm_img_list/dm_img_str/dm_cover_img_str/dm_img_inter 是 `DmImgParamUtil` 给 web
     *   补的浏览器指纹参数（配合 WBI 使用），带到 APP 端点反而像伪造请求，一并丢弃。
     */
    private val DROPPED_PARAMS = hashSetOf(
            "appkey", "ts", "sign", "access_key", "buvid",
            "mobi_app", "platform", "build", "device", "device_name",
            "w_rid", "wts", "csrf", "jsonp", "web_location",
            "gaia_source", "isgaiaavoided",
            "dm_img_list", "dm_img_str", "dm_cover_img_str", "dm_img_inter"
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
        val endpoint: String,
        /**
         * 失败后是否允许回退 web **重发一次**。
         * - GET：[ApiMigrationRules.Rule] 里全是幂等读，恒为 true；
         * - 幂等写操作（isPostSafe）：true；
         * - 非幂等写操作（isPostWrite）：false —— 一旦请求可能已经发出，重放会造成
         *   重复投币 / 重复发评论 / 重复发私信这类不可撤销的副作用，宁可报错也不重发。
         */
        val replayable: Boolean = true,
        /** 允许当成「通道可用」的业务码（见 Rule.acceptedCodes） */
        val acceptedCodes: IntArray? = null,
        /** 回包 `data` 里必须存在的点号路径（见 Rule.requiredDataFields） */
        val requiredDataFields: List<String>? = null,
        /** 允许二进制响应（见 Rule.allowBinary） */
        val allowBinary: Boolean = false
    )

    /** 响应判定结果：ok=true 表示 APP 通道可用，false 时 reason 说明为什么要回退 */
    data class Verdict(val isOk: Boolean, val reason: String)

    /**
     * 全 API 迁移开关。
     *
     * **v5.0.4 起默认开启**（用户要求「尽我所能迁移所有东西」）。之所以敢默认开，是因为
     * 三道护栏都在：① 任何端点失败（-101/-352/-400/HTTP/非JSON）自动回退 web；
     * ② 账号相关接口只在「APP 身份与 web 账号是同一个号」时才迁移（[isAccountMismatch]）；
     * ③ 非幂等写操作一旦可能发出就绝不重放。
     * 用户仍可在「设置 → 实验室 → 全 API 迁移」里一键关掉，关掉后行为与老版本完全一致。
     */
    @JvmStatic
    fun isEnabled(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.API_MIGRATION_ENABLED, true)
    }

    /**
     * 当前 APP 身份与 web 登录账号是否**不是同一个号**。
     *
     * `AppRcmdApi` 的四个登录体系（web Cookie / HD / TV / 手机）互相独立，用户完全可能
     * 在 web 登 A 号、在 TV 身份登 B 号。这时如果把「收藏夹 / 历史 / 关注」这类
     * **账号相关**接口迁到 APP 通道，就会静默返回 B 号的数据（甚至把点赞投到 B 号）——
     * 这是回退检测兜不住的错误，比「多一个 RTT」严重得多。
     *
     * 规则：两边都有 mid 且不相等 → 判定为不一致，账号相关接口一律保持 web。
     * 只要有一边没登录（mid=0）就不算不一致（登录态检查另有 isLoginRequired 把关）。
     *
     * @param identity 本次请求将要使用的身份
     */
    @JvmStatic
    fun isAccountMismatch(identity: AppRcmdApi.RcmdIdentity): Boolean {
        val webMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
        if (webMid <= 0L) return false
        val appMid = AppRcmdApi.getIdentityMid(identity.id)
        if (appMid <= 0L) return false
        return webMid != appMid
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
        val rule = migratableRule(host, path) ?: return null

        val endpoint = endpointOf(host, path)
        if (isSuspended(endpoint)) {
            bumpLong(KEY_SKIPS)
            return null
        }

        // 规则可以定点指定身份（preferredIdentity）；默认跟随用户在设置里选的当前身份。
        val identity = AppRcmdApi.getIdentityById(rule.preferredIdentity)
        val accessToken = AppRcmdApi.getAccessToken(identity.id)
        // 未登录当前客户端身份时：只有明确标记为公共接口的规则才迁移。
        // 原因见 ApiMigrationRules.Rule.isLoginRequired：匿名 APP 请求对收藏/历史/动态等
        // 可能返回 code=0 但数据为空，这种「静默空数据」无法靠回退检测兜住。
        // 未登录 / 串号两种情况下，账号相关接口一律保持 web（见 mustKeepWebForAccount）
        if (mustKeepWebForAccount(rule, identity, accessToken)) return null

        val target = resolveTarget(host, path, rule)
        val targetHost = target.first
        val targetPath = target.second

        val params = queryParams(httpUrl)
        applyParamRewrite(params, rule)
        addIdentityParams(params, accessToken, identity)

        val formData = NetWorkUtil.FormData().setUrlParam(true)
        for ((key, value) in params) formData.put(key, value)
        // 复用 AppRcmdApi 的签名算法（身份的 appkey / appsec / ts / sign）
        AppRcmdApi.sign(formData, identity)

        val appUrl = "https://" + targetHost + targetPath + formData.toString()
        return Plan("GET", originalUrl, appUrl, AppRcmdApi.appHeaders(identity), null, null, endpoint,
                acceptedCodes = rule.acceptedCodes,
                requiredDataFields = rule.requiredDataFields,
                allowBinary = rule.allowBinary)
    }

    /**
     * 按规则解析目标 host + path。只有 [ApiMigrationRules.Kind.PATH_OVERRIDE] 才换端点，
     * 且 `targetHost`/`targetPath` 为 null 时保持原值。
     */
    private fun resolveTarget(host: String, path: String, rule: ApiMigrationRules.Rule): Pair<String, String> {
        if (rule.kind != ApiMigrationRules.Kind.PATH_OVERRIDE) return Pair(host, path)
        return Pair(rule.targetHost ?: host, rule.targetPath ?: path)
    }

    /**
     * 账号相关接口是否必须保持 web（两个理由之一即保持）：
     * 1. `isLoginRequired` 但当前 APP 身份没登录 —— 匿名 APP 请求可能返回 code=0 却是空数据；
     * 2. APP 身份与 web 账号**不是同一个号** —— 否则会静默读写另一个账号的数据。
     */
    private fun mustKeepWebForAccount(
        rule: ApiMigrationRules.Rule,
        identity: AppRcmdApi.RcmdIdentity,
        accessToken: String
    ): Boolean {
        if (!rule.isLoginRequired) return false
        if (accessToken.isEmpty()) return true
        return isAccountMismatch(identity)
    }

    /**
     * 按规则做参数改名与额外参数注入（PATH_OVERRIDE 换 APP 专用端点时经常需要）：
     * - `mid` → `vmid`（app.bilibili.com/x/v2/space/archive）
     * - `id` → `room_id`（直播 getDanmuInfo）
     */
    private fun applyParamRewrite(params: LinkedHashMap<String, String>, rule: ApiMigrationRules.Rule) {
        val rename = rule.renameParams
        if (rename != null) {
            for ((from, to) in rename) {
                val value = params.remove(from) ?: continue
                params[to] = value
            }
        }
        val extra = rule.extraParams
        if (extra != null) {
            for ((key, value) in extra) params[key] = value
        }
    }

    /**
     * 解析该请求应使用的迁移规则。
     *
     * 返回 null 表示「不要走 APP 通道」；返回 [ApiMigrationRules.DEFAULT_GUEST_RULE] 表示
     * 未命中显式规则、但该 host 允许按默认策略尝试（只对默认 APP 主机开放）。
     */
    private fun migratableRule(host: String, path: String): ApiMigrationRules.Rule? {
        val rule = ApiMigrationRules.find(host, path)
        if (rule != null && rule.kind == ApiMigrationRules.Kind.KEEP_WEB) return null
        if (rule != null) return rule
        return if (ApiMigrationRules.allowsDefaultAppParams(host)) ApiMigrationRules.DEFAULT_GUEST_RULE else null
    }

    /** URL query 参数（保序），剔除会让 APP 签名/语义混乱的键 */
    private fun queryParams(httpUrl: HttpUrl): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        for (name in httpUrl.queryParameterNames) {
            if (DROPPED_PARAMS.contains(name.lowercase(Locale.ROOT))) continue
            params[name] = httpUrl.queryParameter(name) ?: ""
        }
        return params
    }

    private fun buildPostPlan(url: String?, body: String?): Plan? {
        val originalUrl = url ?: return null
        val httpUrl = originalUrl.toHttpUrlOrNull() ?: return null
        val host = httpUrl.host.lowercase(Locale.ROOT)
        val path = httpUrl.encodedPath
        val rule = ApiMigrationRules.find(host, path) ?: return null
        if (rule.kind == ApiMigrationRules.Kind.KEEP_WEB || !rule.isPostRule()) return null

        val endpoint = endpointOf(host, path)
        if (isSuspended(endpoint)) {
            bumpLong(KEY_SKIPS)
            return null
        }

        // 写操作全部要求已登录（两种清单里的接口都依赖账号身份），且不能串号
        val identity = AppRcmdApi.getIdentityById(rule.preferredIdentity)
        val accessToken = AppRcmdApi.getAccessToken(identity.id)
        if (mustKeepWebForAccount(rule, identity, accessToken)) return null

        val target = resolveTarget(host, path, rule)
        val targetHost = target.first
        val targetPath = target.second

        val params = parseFormBody(body)
        params.putAll(queryParams(httpUrl))
        applyParamRewrite(params, rule)
        addIdentityParams(params, accessToken, identity)

        val formData = NetWorkUtil.FormData().setUrlParam(false)
        for ((key, value) in params) formData.put(key, value)
        AppRcmdApi.sign(formData, identity)

        // 只有幂等写操作允许失败回退重发；非幂等写操作（isPostWrite）绝不重放。
        return Plan("POST", originalUrl, "https://" + targetHost + targetPath,
                AppRcmdApi.appHeaders(identity),
                formData.toString(), "application/x-www-form-urlencoded", endpoint,
                replayable = rule.isPostSafe,
                acceptedCodes = rule.acceptedCodes,
                requiredDataFields = rule.requiredDataFields,
                allowBinary = rule.allowBinary)
    }

    /** 按身份补 APP 公共参数（appkey/ts/sign 由 [AppRcmdApi.sign] 追加） */
    private fun addIdentityParams(
        params: LinkedHashMap<String, String>,
        accessToken: String,
        identity: AppRcmdApi.RcmdIdentity
    ) {
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
    fun classify(response: Response): Verdict = classify(response, null)

    /**
     * 判断 APP 通道响应是否可用（带规则上下文版）。
     *
     * 相比无参版本多三件事：
     * 1. [Plan.allowBinary]：二进制端点只看 HTTP 状态 + 响应体非空（弹幕 seg.so 的 protobuf）；
     * 2. [Plan.acceptedCodes]：规则允许的业务码（如 nav 的 -101）不再算失败；
     * 3. [Plan.requiredDataFields]：放行之后再做内容校验，字段缺失照样回退 web。
     */
    @JvmStatic
    fun classify(response: Response, plan: Plan?): Verdict {
        if (!response.isSuccessful) return Verdict(isOk = false, reason = "HTTP " + response.code)
        if (plan != null && plan.allowBinary) return classifyBinary(response)
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("json", ignoreCase = true)) return Verdict(isOk = false, reason = "非JSON响应")
        return classifyJsonBody(response, plan)
    }

    /** 二进制响应判定：只要拿到非空 body 就算通道可用 */
    private fun classifyBinary(response: Response): Verdict {
        val size = try {
            response.peekBody(PEEK_BYTES).bytes().size
        } catch (e: Exception) {
            return Verdict(isOk = false, reason = "响应读取失败：" + e.javaClass.simpleName)
        }
        return if (size > 0) Verdict(isOk = true, reason = "") else Verdict(isOk = false, reason = "空响应体")
    }

    /** 读取响应体并按 code 判定（与 [classify] 拆开，避免单个函数返回点过多） */
    private fun classifyJsonBody(response: Response, plan: Plan?): Verdict {
        val text = try {
            response.peekBody(PEEK_BYTES).string()
        } catch (e: Exception) {
            return Verdict(isOk = false, reason = "响应读取失败：" + e.javaClass.simpleName)
        }
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return Verdict(isOk = false, reason = "响应不是JSON对象")
        val match = CODE_REGEX.find(trimmed)
        val code = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (code == null) return Verdict(isOk = false, reason = "响应缺少 code 字段")
        val accepted = plan != null && plan.acceptedCodes != null && plan.acceptedCodes.contains(code)
        val retryable = RETRY_CODES.contains(code) && !accepted
        if (retryable) return Verdict(isOk = false, reason = "code=" + code)
        if (accepted && plan != null) {
            // 放行了业务码，必须再确认「该拿到的数据真的在」，避免静默空数据被当成成功
            val missing = firstMissingField(trimmed, plan.requiredDataFields)
            if (missing != null) return Verdict(isOk = false, reason = "缺少字段 " + missing)
        }
        return Verdict(isOk = true, reason = "")
    }

    /**
     * 检查 [fields] 里每个「点号路径」是否都能在回包的 `data` 里找到。
     * @return 第一个缺失的路径；全部存在返回 null
     */
    private fun firstMissingField(jsonText: String, fields: List<String>?): String? {
        if (fields == null || fields.isEmpty()) return null
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            // 只读到前缀时 JSON 可能被截断，这时无法做字段校验 → 按「缺字段」回退 web
            return "响应不是完整JSON(" + e.javaClass.simpleName + ")"
        }
        if (!root.has("data") || root.isNull("data")) return "data"
        return firstMissingSubField(root.getJSONObject("data"), fields)
    }

    /** 逐段下钻检查点号路径 */
    private fun firstMissingSubField(data: JSONObject, fields: List<String>): String? {
        for (field in fields) {
            if (!hasJsonPath(data, field)) return field
        }
        return null
    }

    /** 检查 `a.b.c` 形式的路径在 obj 里是否存在且非 null */
    private fun hasJsonPath(obj: JSONObject, path: String): Boolean {
        var current: JSONObject = obj
        val parts = path.split(".")
        for (i in parts.indices) {
            val key = parts[i]
            if (!current.has(key) || current.isNull(key)) return false
            if (i == parts.lastIndex) return true
            current = current.optJSONObject(key) ?: return false
        }
        return true
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

    /**
     * 记录一次「非幂等写操作走 APP 通道但响应不可用」的失败。
     *
     * 与 [recordFallback] 的区别：**没有回退**，也不会暂停该端点的 APP 通道
     * （暂停会让写操作全部退回 web，反而把非幂等写操作放到老通道上，逻辑上无益）。
     * 上层会收到 APP 通道的错误响应/异常，由业务层提示用户，绝不重放造成重复副作用。
     */
    @JvmStatic
    fun recordNoReplay(plan: Plan, reason: String) {
        bumpLong(KEY_NO_REPLAY)
        Logu.d(TAG, "不重放 " + plan.endpoint + "：" + reason)
        synchronized(STAT_LOCK) {
            attemptedEndpoints.add(plan.endpoint)
            val now = System.currentTimeMillis()
            val recent = loadRecentFailures().toMutableList()
            recent.add(0, formatFailure(plan.endpoint, reason, now))
            while (recent.size > MAX_FAILURES) recent.removeAt(recent.size - 1)
            SharedPreferencesUtil.putString(KEY_FAILURES, recent.joinToString("\n"))
        }
    }

    /** 非幂等写操作「不重放」次数（设置页展示） */
    @JvmStatic
    fun noReplayCount(): Long = SharedPreferencesUtil.getLong(KEY_NO_REPLAY, 0L)

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
            SharedPreferencesUtil.putLong(KEY_NO_REPLAY, 0L)
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
