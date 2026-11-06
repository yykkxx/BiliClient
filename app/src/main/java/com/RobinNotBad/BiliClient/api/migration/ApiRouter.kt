package com.RobinNotBad.BiliClient.api.migration

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import okhttp3.HttpUrl
// quality-allow：这里只需要在 16KB 的响应前缀里做「字段存在性」判定，
// 不是业务数据解析（业务解析仍在各 Api 对象里）。为一次浅层路径检查引入 Gson/序列化
// 或新建 model 得不偿失，故允许在本文件用轻量 org.json。
import org.json.JSONException // quality-allow：仅用于区分「前缀不是完整 JSON」这一预期分支
import org.json.JSONObject // quality-allow：仅做 16KB 前缀里的字段存在性判定，不是业务数据解析
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APP 通道路由器（「设置 → 账号管理 → 其它页面 API」选非 Web 端口时启用）。
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

    /**
     * v5.2.1「APP 通道响应不可用但未回退 web」的计数键。
     *
     * 为什么新开一个键而不是继续用 [KEY_FALLBACKS]：那个键的语义是「回退发生了」，
     * 而 v5.2.1 之后**回退这条路已经不存在**。把新语义塞进旧键会让历史统计与现在
     * 混在一起没法读（用户会看到「回退 30 次」但实际一次都没回退过）。
     * 旧键保留只增不清，是为了不破坏已有数据。
     */
    private const val KEY_NO_FALLBACK = "api_migration_stat_no_fallback"
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

    /**
     * 风控/校验类字段（小写，精确匹配）。
     *
     * 背景见 [riskControlShellReason]：APP 通道被风控时回 `code=0` + `data:{v_voucher}`，
     * 上游解析 `seid`/`result` 必然抛异常。这里列出「已知不代表业务数据」的键。
     */
    private val RISK_CONTROL_KEYS = hashSetOf(
        "v_voucher", "v_score", "risk_level", "gaia_source", "is_risk"
    )

    /**
     * 风控类字段前缀（小写）：服务端既有 `gaia_*`（风控埋点）也有 `v_*`（校验票据）命名。
     * 用前缀覆盖同族新字段，避免每次服务端改名都要发版。
     */
    private val RISK_CONTROL_PREFIXES = arrayOf("gaia_", "v_voucher_", "v_score_", "risk_")

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
        val allowBinary: Boolean = false,
        /**
         * 登录态接口（v5.1.2 加固，源自「手机端 token 被部分接口拒绝 → 鉴权失败弹窗」实测）：
         * - 任何非 0 code（未被 [acceptedCodes] 放行）一律回退 web —— 凭据不可信时 web 永远是更对的选择；
         * - 回包 `data` 必须存在（presence-only 校验）—— 拦住 code=0 但 data 缺失/为 null 的畸形回包。
         * 这两条只影响判定，不改变请求构造。
         */
        val loginRequired: Boolean = false
    )

    /** 响应判定结果：ok=true 表示 APP 通道可用，false 时 reason 说明为什么要回退 */
    data class Verdict(val isOk: Boolean, val reason: String)

    /**
     * 是否启用 APP 通道路由。
     *
     * 唯一的开关来源是「账号管理 → 其它页面 API」里选的端口（v5.1.5）：
     * - 端口 = Web → 不路由，一切走 web（与 5.1.0 的纯 web 行为逐行为一致）；
     * - 端口 = Android手机 / HD / TV → 所有接口先走该端口的 APP 通道，失败自动回退 web。
     *
     * 推荐页端口（页签 1）不影响这里，推荐页有自己的分支（见 RecommendApi）。
     */
    @JvmStatic
    fun isEnabled(): Boolean {
        return AccountStore.mainIdentity() != null
    }

    /**
     * 本次请求使用的端口身份：账号管理里选的**其它页面端口**对应的身份。
     *
     * web 端口时返回 null（[isEnabled] 为 false，planGet/planPost 直接放弃迁移）。
     * 不再有「跟随某条规则的 preferredIdentity」或「借用别的已登录端口」——
     * 用户在账号管理里显式选定了端口，语义就是「所有接口都走这一端」，没有歧义。
     */
    private fun routingIdentity(): AppRcmdApi.RcmdIdentity? = AccountStore.mainIdentity()

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

        // 身份一律取「账号管理 → 其它页面 API」里选的端口（v5.1.5）。
        // 不再看规则里的 preferredIdentity，也不存在「借用别的端口 token」：
        // 用户在账号管理里显式选定端口，语义就是所有接口都走这一端。
        val identity = routingIdentity() ?: return null
        val accessToken = AppRcmdApi.getAccessToken(identity.id)
        // 未登录当前客户端身份时：只有明确标记为公共接口的规则才迁移。
        // 原因见 ApiMigrationRules.Rule.isLoginRequired：匿名 APP 请求对收藏/历史/动态等
        // 可能返回 code=0 但数据为空，这种「静默空数据」无法靠回退检测兜住。
        // 当前端口没有激活账号时，账号相关接口一律保持 web（见 mustKeepWebForAccount）
        if (mustKeepWebForAccount(rule, accessToken)) return null

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
                allowBinary = rule.allowBinary,
                loginRequired = rule.isLoginRequired)
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
     * 账号相关接口是否必须保持 web。
     *
     * 唯一理由：`isLoginRequired` 但当前端口的激活账号没有 token。
     * 匿名 APP 请求对收藏/历史/动态这类接口可能回 code=0 但数据为空——这种「静默空数据」
     * 回退检测抓不住，所以没登录就老老实实回 web（那里要么有 Cookie，要么同样提示登录）。
     *
     * v5.1.5 去掉了「串号」判定：端口由用户在账号管理里显式选定，
     * APP 通道根本不读 web Cookie，不存在读写另一个账号的路径。
     */
    private fun mustKeepWebForAccount(
        rule: ApiMigrationRules.Rule,
        accessToken: String
    ): Boolean {
        return rule.isLoginRequired && accessToken.isEmpty()
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

        // 写操作全部要求已登录（两种清单里的接口都依赖账号身份）；身份同样取账号管理里选的端口
        val identity = routingIdentity() ?: return null
        val accessToken = AppRcmdApi.getAccessToken(identity.id)
        if (mustKeepWebForAccount(rule, accessToken)) return null

        val target = resolveTarget(host, path, rule)
        val targetHost = target.first
        val targetPath = target.second

        val params = parseFormBody(body)
        // 与 GET 的 queryParams() 口径一致：web 专用参数（尤其 csrf）不应带给 APP 端点。
        // 表单体里的 csrf 多为 web 登录的 bili_jct（web 未登录时是空串）；APP 通道用 access_key，
        // 文档明确 csrf 只在 Cookie 方式下必要，带一个空 csrf 过去只会给服务端留「字段存在但为空」的口径。
        params.keys.removeAll { DROPPED_PARAMS.contains(it.lowercase(Locale.ROOT)) }
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
                allowBinary = rule.allowBinary,
                loginRequired = rule.isLoginRequired)
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
        val codeVerdict = verdictForCode(trimmed, code, plan, accepted)
        if (codeVerdict != null) return codeVerdict
        // 内容校验不再只服务于「放行的业务码」：code=0 同样要验。
        //
        // 为什么：迁移层原先只看 code，只要 APP 通道回 code=0 就当成可用并**直接返回给调用方**。
        // 但 APP 端点和 web 端点的响应结构并不保证永远一致（有的接口在登录态、风控态、
        // 灰度态下字段会变），一旦结构漂移，上游各 Api 的 JSON 解析会在后台线程抛异常；
        // Kotlin 的 @Throws 不强制捕获，后台线程未捕获异常 = 进程崩溃（用户看到的就是闪退）。
        // 规则只要声明了 requiredDataFields，无论 code 是多少都必须过一遍内容校验，
        // 缺字段就回退 web —— 宁可多一次请求，也不能把「形状不对的响应」交给解析层。
        if (plan != null) {
            val missing = firstMissingField(trimmed, plan.requiredDataFields, plan.loginRequired)
            if (missing != null) return Verdict(isOk = false, reason = "缺少字段 " + missing)
        }
        // 风控空壳兜底（v5.1.6）：`code=0` + `data` 只有 v_voucher 这类校验票据，
        // 一个业务字段都没有。这种回包必须回退 web，否则上层解析必然抛异常
        // （「Android 端口搜索报 No value for seid」就是这条路径）。
        // 放在最后一道：只有前面所有 code / 字段校验都放行了才走到这里。
        val riskShell = riskControlShell(trimmed)
        if (riskShell != null) return Verdict(isOk = false, reason = riskShell)
        return Verdict(isOk = true, reason = "")
    }

    /**
     * 风控空壳判定（解析 JSON 后再交给 [riskControlShellReason]）。
     *
     * 单独抽一层的原因：`classifyJsonBody` 已被 detekt 盯上圈复杂度，
     * 再加 try/catch 会顶破上限。
     *
     * 解析失败时**放行**（返回 null）而不是判失败：这里只读到响应体前 16KB 前缀，
     * 大响应的 JSON 可能本来就被截断；那种情况下前面的 `firstMissingField` 已经因为
     * 「不是完整 JSON」回退过 web 了，重复判失败没有额外价值，还容易误伤正常回包。
     * 刻意不记录原异常：这只是「前缀无法解析成完整 JSON」的预期分支，不是错误路径。
     */
    private fun riskControlShell(trimmed: String): String? {
        return try {
            riskControlShellReason(JSONObject(trimmed))
        } catch (ignored: JSONException) {
            // 前缀不是完整 JSON（预期分支，见上方注释）：不视为风控壳。
            // 变量名 ignored 是 detekt SwallowedException 的豁免约定，与仓库其它位置写法一致。
            null
        }
    }

    /**
     * 按 code 判定 APP 通道响应是否要回退（从 [classifyJsonBody] 拆出，控制圈复杂度）。
     * @return null = 该 code 不触发回退，交给后续内容校验/放行
     */
    private fun verdictForCode(trimmed: String, code: Int, plan: Plan?, accepted: Boolean): Verdict? {
        val retryable = RETRY_CODES.contains(code) && !accepted
        if (retryable) return Verdict(isOk = false, reason = "code=" + code)
        // 登录态接口加固（v5.1.2）：任何非 0 code 都回退 web。
        // 背景：手机端 token 是「TV 密钥签发」的，部分严格校验的接口会拒绝
        // 「TV 签发 token + Android appkey」组合，返回「鉴权失败，请联系账号管理员」
        // 这类非 0 code（不在 RETRY_CODES 里）且无 data —— 原逻辑会直接把它交给上层，
        // 上层 getJSONObject("data") 抛异常 → 详情页弹错误框。登录接口依赖凭据，
        // 凭据可疑时回退 web（匿名或 web 登录态）永远比把错误抛给用户更合理。
        val loginPlan = plan != null && plan.loginRequired
        if (loginPlan && code != 0 && !accepted) {
            return Verdict(isOk = false, reason = "code=" + code + "(登录接口回退)")
        }
        // 通用错误壳兜底（v5.1.3）：非 0 code 且回包没有 data 的「错误壳」一律回退 web。
        // 背景：三例同构翻车（三连状态 relation / 视频标签 tags / 收藏列表）——设备 token 被
        // 服务端拒绝时公共接口也会返回「错误壳」（code≠0 且无 data），原逻辑直接交上层，
        // 上层 getJSONArray/getJSONObject("data") 抛 JSONException → 页面弹错误框。
        // 回退 web 要么拿到正常数据，要么拿到同样的错误壳（调用方本就按错误处理），不会更差。
        val plainPlan = plan != null && plan.requiredDataFields == null
        if (plainPlan && code != 0 && !accepted) {
            val missing = firstMissingField(trimmed, null, true)
            if (missing != null) return Verdict(isOk = false, reason = "code=" + code + "(错误壳回退)")
        }
        return null
    }

    /**
     * 检查 [fields] 里每个「点号路径」是否都能在回包的 `data` 里找到。
     * @param presenceOnly true 时只校验「`data` 字段存在且非 null」，不做字段下钻
     *        （登录态接口的通用底线：data 都没有的回包必然不可用）
     * @return 第一个缺失的路径；全部存在返回 null
     */
    private fun firstMissingField(jsonText: String, fields: List<String>?, presenceOnly: Boolean = false): String? {
        if (!presenceOnly && (fields == null || fields.isEmpty())) return null
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            // 只读到前缀时 JSON 可能被截断，这时无法做字段校验 → 按「缺字段」回退 web
            return "响应不是完整JSON(" + e.javaClass.simpleName + ")"
        }
        if (!root.has("data") || root.isNull("data")) return "data"
        if (presenceOnly) return null
        val checked = fields ?: return null
        return missingDataShape(root, checked)
    }

    /**
     * 风控空壳判定（v5.1.6，修「Android 端口搜索报 No value for seid」）。
     *
     * B 站风控在「疑似异常流量」时会对 `search/all/v2`、`search/type` 等接口返回
     * **code=0 但 `data` 只有一个 `v_voucher`** 的壳（实测复现：连续请求 12 次全部命中，
     * 同一关键词走 web WBI 通道则正常带 seid/result）。这类回包：
     * - 通过 `code=0` 检查 → 被当成「APP 通道可用」直接交给调用方；
     * - 上游 `SearchApi` 解析 `data.getString("seid")` → 抛 `JSONException: No value for seid`；
     * - 后台线程未捕获 → 用户看到的现象就是「搜索直接报错，一条结果都没有」。
     *
     * 所以：`data` 非空但**除风控字段外没有任何业务字段**时，一律判定「不可用」回退 web。
     * 不写死只看 v_voucher：服务端可能换字段名（如 `v_voucher`/`gaia_*`），
     * 这里用「白名单式风控键 + 没有其它业务键」的组合判定，比逐个枚举更耐演化。
     *
     * @return 命中风控壳时返回原因文案；正常回包返回 null
     */
    private fun riskControlShellReason(root: JSONObject): String? {
        val data = root.optJSONObject("data") ?: return null
        if (data.length() == 0) return null
        val keys = data.keys()
        var business = 0
        while (keys.hasNext()) {
            val key = keys.next().lowercase(Locale.ROOT)
            if (!isRiskControlKey(key)) business++
        }
        return if (business == 0) "风控空壳(仅" + data.keys().next() + ")" else null
    }

    /**
     * 风控/校验类字段名（小写）。这些键出现不代表有业务数据；
     * 只有当 `data` 里**全是**这类键时才算风控壳。
     */
    private fun isRiskControlKey(key: String): Boolean {
        if (RISK_CONTROL_KEYS.contains(key)) return true
        for (prefix in RISK_CONTROL_PREFIXES) {
            if (key.startsWith(prefix)) return true
        }
        return false
    }

    /**
     * `data` 既可能是对象（详情类接口）也可能是数组（列表类接口：相关推荐 / TAG / 分P）。
     *
     * 以前这里直接 `getJSONObject("data")`，列表接口会抛异常并被当成「不是完整 JSON」，
     * 结果就是「只要给列表接口配了 requiredDataFields 就永远回退 web」。
     * 现在先分流：数组只验非空，对象再逐字段下钻。
     */
    private fun missingDataShape(root: JSONObject, fields: List<String>): String? {
        val array = root.optJSONArray("data")
        if (array != null) return if (array.length() == 0) "data(空数组)" else null
        val obj = root.optJSONObject("data") ?: return "data(既不是对象也不是数组)"
        return firstMissingSubField(obj, fields)
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

    /**
     * 记录一次「APP 通道响应不可用，但**不回退 web**」（v5.2.1 新增，替代 GET/POST 的回退）。
     *
     * ## 为什么需要它而不是复用 [recordNoReplay]
     * `recordNoReplay` 的语义是「**非幂等写操作**已在途、不敢重放」，它不带「端点被暂停」逻辑，
     * 因为写操作退回 web 反而更糟。而 GET（含幂等读）失败时，旧行为是回退 + 累计失败 +
     * 连续 2 次就暂停该端点 10 分钟。
     * v5.2.1 删掉回退后，**仍然需要「暂停」这个止损机制**吗？——需要，但语义变了：
     * 现在不再有「回退到 web」这条路，暂停只会让端点**完全不可用**，那反而更糟
     * （用户会看到功能直接消失）。所以本函数**只统计、不暂停**：
     * 失败次数与最近失败原因仍要能看见（排查必需），但绝不主动关掉任何端点。
     *
     * ## 统计口径
     * - `KEY_FALLBACKS` 继续计「回退次数」，但 v5.2.1 起它**只会由旧版本残留增长**；
     * - 本函数累加 `KEY_NO_FALLBACK`（新键），设置页/诊断页展示「APP 通道失败（无回退）次数」。
     * 保留旧键是为了不破坏历史统计的连续性（用户已有数据不该凭空清零）。
     */
    @JvmStatic
    fun recordNoFallback(plan: Plan, reason: String) {
        bumpLong(KEY_NO_FALLBACK)
        Logu.d(TAG, "不回退 " + plan.endpoint + "：" + reason)
        synchronized(STAT_LOCK) {
            attemptedEndpoints.add(plan.endpoint)
            val now = System.currentTimeMillis()
            val recent = loadRecentFailures().toMutableList()
            recent.add(0, formatFailure(plan.endpoint, reason, now))
            while (recent.size > MAX_FAILURES) recent.removeAt(recent.size - 1)
            SharedPreferencesUtil.putString(KEY_FAILURES, recent.joinToString("\n"))
        }
    }

    /** APP 通道响应不可用但**未回退**的次数（v5.2.1 起的主要失败计数） */
    @JvmStatic
    fun noFallbackCount(): Long = SharedPreferencesUtil.getLong(KEY_NO_FALLBACK, 0L)

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
