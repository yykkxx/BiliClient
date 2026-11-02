package com.RobinNotBad.BiliClient.api.migration

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.AccountManager
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

    /**
     * 三级模式下「当前身份未登录时，按顺序借用哪个已登录端口」的优先级。
     *
     * 手机端（android appkey）排第一：它是与官方手机客户端一致的一派，兼容的接口最多；
     * HD 次之（本功能历史默认身份）；TV 最后。
     * 只影响「这次请求用谁的 token」，不会改动用户设置的当前身份。
     */
    private val BORROW_ORDER = arrayOf(
        AppRcmdApi.IDENTITY_PHONE, AppRcmdApi.IDENTITY_HD, AppRcmdApi.IDENTITY_TV
    )

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
     * 全 API 迁移开关。
     *
     * **v5.0.4 起默认开启**（用户要求「尽我所能迁移所有东西」）。之所以敢默认开，是因为
     * 三道护栏都在：① 任何端点失败（-101/-352/-400/HTTP/非JSON）自动回退 web；
     * ② 账号相关接口只在「APP 身份与 web 账号是同一个号」时才迁移（[isAccountMismatch]）；
     * ③ 非幂等写操作一旦可能发出就绝不重放。
     * 用户仍可在「设置 → 实验室 → 全 API 迁移」里一键关掉，关掉后行为与老版本完全一致。
     *
     * v5.1.1 起新增第二个开关来源：实验室「重构账号体系」选中**非 web 端口**时
     * 也启用路由（单端口模式，见 [singlePortIdentity]）。旧开关 [SharedPreferencesUtil.API_MIGRATION_ENABLED]
     * 的默认值与语义保持不动：它仍是 true，任何存量用户行为不变。
     */
    @JvmStatic
    fun isEnabled(): Boolean {
        // 旧开关（全 API 迁移）保持原样：默认值/读写键一律不动
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.API_MIGRATION_ENABLED, true)) return true
        // 实验室「重构账号体系」：选了非 web 端口才启用路由（选 web = 维持现状）
        return singlePortIdentity() != null
    }

    // ==================== 单端口模式（实验室「重构账号体系」，v5.1.1） ====================

    /** 端口取值，与 [SharedPreferencesUtil.ACCOUNT_REFACTOR_PORT] 的合法值一致 */
    const val PORT_WEB = "web"
    const val PORT_ANDROID = "android"
    const val PORT_HD = "hd"
    const val PORT_TV = "tv"

    /**
     * 实验室「重构账号体系」总开关（与端口无关；页面显隐用它判断）。
     * 只读 [SharedPreferencesUtil.LAB_ACCOUNT_REFACTOR]，不产生任何副作用。
     */
    @JvmStatic
    fun isAccountRefactorLabEnabled(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LAB_ACCOUNT_REFACTOR, false)
    }

    /**
     * 当前端口（归一化后）。web / android / hd / tv；历史脏数据或非法值一律按 web 处理
     * （web = 不进单端口模式，路由保持现状）。
     */
    @JvmStatic
    fun currentPort(): String {
        val raw = SharedPreferencesUtil.getString(SharedPreferencesUtil.ACCOUNT_REFACTOR_PORT, PORT_WEB) ?: ""
        return when (raw.trim().lowercase(Locale.ROOT)) {
            PORT_ANDROID -> PORT_ANDROID
            PORT_HD -> PORT_HD
            PORT_TV -> PORT_TV
            else -> PORT_WEB
        }
    }

    /** 端口的显示名（登录选项页 / 提示文案用） */
    @JvmStatic
    fun portDisplayName(port: String): String {
        return when (port) {
            PORT_ANDROID -> "Android手机"
            PORT_HD -> "HD"
            PORT_TV -> "TV"
            else -> "Web"
        }
    }

    /**
     * 单端口模式的身份：实验室开关开且端口**不是 web** 时返回对应身份，否则 null。
     *
     * - android → [AppRcmdApi.IDENTITY_PHONE]；hd → IDENTITY_HD；tv → IDENTITY_TV；
     * - 身份对象/请求头/签名/参数生成全部复用 [AppRcmdApi] 既有体系，token 读写也落在
     *   既有槽位 app_rcmd_{phone|hd|tv}_*，不新增存储；web 端口走既有 Cookie，互不转移；
     * - 单端口模式下 [ApiMigrationRules] 的 KEEP_WEB 条目（共 13 条）**仍然保持 web**：
     *   它们多为匿名可访问（弹幕 XML / 搜索联想 / pbp / 页面地址 / 埋点），
     *   登录协议两条在「不用 web 端口」时也用不到，维持 web 无损；
     * - 返回 null 时 [isEnabled] 不因本功能打开，行为与 5.1.0 完全一致。
     */
    @JvmStatic
    fun singlePortIdentity(): AppRcmdApi.RcmdIdentity? {
        if (!isAccountRefactorLabEnabled()) return null
        return when (currentPort()) {
            PORT_ANDROID -> AppRcmdApi.getIdentityById(AppRcmdApi.IDENTITY_PHONE)
            PORT_HD -> AppRcmdApi.getIdentityById(AppRcmdApi.IDENTITY_HD)
            PORT_TV -> AppRcmdApi.getIdentityById(AppRcmdApi.IDENTITY_TV)
            else -> null
        }
    }

    /** 是否处于单端口模式（[singlePortIdentity] 非 null 的便捷判断） */
    @JvmStatic
    fun isSinglePortMode(): Boolean {
        return singlePortIdentity() != null
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
        // 单端口模式（实验室「重构账号体系」）覆盖规则的 preferredIdentity；
        // v5.1.4 三级模式（全 API 迁移）下当前身份没登录时自动换成**已登录**的端口，
        // 见 [pickUsableIdentity]——否则「只登了 TV、web 又退出」会把账号接口全推回没 Cookie 的 web。
        val identity = pickUsableIdentity(rule.preferredIdentity)
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
     * 选出这次请求要用的端口身份（v5.1.4 三级模式的关键补丁）。
     *
     * # 为什么需要它
     *
     * v5.1.3 及以前的写法是 `singlePortIdentity() ?: AppRcmdApi.getIdentityById(rule.preferredIdentity)`
     * —— 身份默认跟随用户在「移动端推荐逻辑」里选的当前身份（出厂默认 HD）。
     * 于是三级模式下出现这个死结：**用户只登录了 TV 端、又把 Web 退出了**：
     *
     * 1. 当前身份是 HD → HD 没登录 → `accessToken` 为空；
     * 2. [mustKeepWebForAccount] 见 token 为空 → 判定「保持 web」；
     * 3. 可 web 已经被用户退出了，Cookie 是空的 → 请求必然失败。
     *
     * 用户的体感就是「退出 Web 登录后，收藏/历史/稍后再看/动态全不能用了」。
     * 端口明明登录着却没人去用它，这是纯粹的路由选择缺陷，不是账号体系的问题。
     *
     * # 选择顺序
     *
     * 1. **单端口模式**（实验室「重构账号体系」选了非 web 端口）：端口就是唯一账号，直接用它，
     *    与 v5.1.1 行为完全一致；
     * 2. 规则/设置指定的身份**已登录**：用它（第一级、第二级、以及三级下已登录用户，
     *    行为全部保持不变——这条保证本次改动对存量用户零影响）；
     * 3. **三级模式**（全 API 迁移开关打开）且指定身份未登录：按 手机 → HD → TV 挑第一个已登录的。
     *    为什么是这个顺序：手机端 appkey 与官方手机客户端一致，兼容的接口最多；
     *    HD/TV 次之。这里只影响「挑谁」，不影响用户设置；
     * 4. 都不满足：返回指定身份（即维持原行为，账号接口照旧回退 web）。
     *
     * # 不做的事
     *
     * - **不修改** `app_rcmd_identity`（用户选的当前身份保持不变，只是本次请求临时借另一个端口的 token）；
     * - **不打印**任何 token / cookie，日志只有身份 id。
     *
     * @param preferredIdentity 规则声明的首选身份 id，null = 跟随当前身份
     */
    private fun pickUsableIdentity(preferredIdentity: String?): AppRcmdApi.RcmdIdentity {
        // 1. 单端口模式：端口就是唯一账号（v5.1.1 既有行为，不动）
        singlePortIdentity()?.let { return it }

        val preferred = AppRcmdApi.getIdentityById(preferredIdentity)
        // 2. 指定身份已登录：一切都按原样（存量用户零影响）
        if (AppRcmdApi.isIdentityLogin(preferred.id)) return preferred

        // 3. 三级模式才有资格"借"别的端口；一/二级保持原行为
        if (!isThirdTier()) return preferred
        if (!isAccountMismatchSafeForBorrow()) return preferred

        // 4. 在已登录的端口里挑第一个（挑不到就维持 preferred，让上层按老规矩回退 web）
        return firstLoggedIdentity(preferred.id) ?: preferred
    }

    /**
     * 在 [BORROW_ORDER] 里找第一个已登录、且不是 [excludeId] 的端口身份。
     *
     * 抽成独立函数有两个原因：① [pickUsableIdentity] 的循环里 continue/break 太多会触发
     * detekt 的 LoopWithTooManyJumpStatements；② 「挑一个已登录端口」这个语义本身值得单独命名。
     *
     * @return null 表示没有任何其他端口已登录
     */
    private fun firstLoggedIdentity(excludeId: String): AppRcmdApi.RcmdIdentity? {
        val candidateId = BORROW_ORDER.firstOrNull {
            it != excludeId && AppRcmdApi.isIdentityLogin(it)
        } ?: return null
        Logu.d(TAG, "当前身份未登录，本次借用已登录端口：" + candidateId)
        return AppRcmdApi.getIdentityById(candidateId)
    }

    /**
     * 是否处于「全 API 迁移」这一级（第三级）。
     *
     * 注意**不能**用 `isEnabled()`：那个方法在单端口模式下也返回 true，
     * 而单端口模式在 [pickUsableIdentity] 第 1 步就返回了，这里只关心旧开关本身。
     */
    private fun isThirdTier(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.API_MIGRATION_ENABLED, true)
    }

    /**
     * 「借用」另一个端口的 token 是否安全。
     *
     * 只有当 web 没登录、或 web 与要借的端口是同一个人时才是安全的：
     * 否则用户在 web 登 A 号、在端口登 B 号，借 B 的 token 去发请求会让 A 号用户
     * 莫名其妙操作了 B 号的账号（收藏到 B 的收藏夹、给 B 的关注列表加人）。
     *
     * 这与 [isAccountMismatch] 的口径一致：两边都有 mid 且不相等才算串号。
     * web 没登录（mid=0）时没有"串号"可言——用户本来就没在用 web 那个号。
     */
    private fun isAccountMismatchSafeForBorrow(): Boolean {
        return !AccountManager.isWebLoggedIn()
    }

    /**
     * 账号相关接口是否必须保持 web（两个理由之一即保持）：
     * 1. `isLoginRequired` 但当前 APP 身份没登录 —— 匿名 APP 请求可能返回 code=0 却是空数据；
     * 2. APP 身份与 web 账号**不是同一个号** —— 否则会静默读写另一个账号的数据。
     *
     * 单端口模式（实验室「重构账号体系」）跳过第 2 条 [isAccountMismatch] 检查：
     * 端口账号就是唯一账号，web 没登录是预期状态（这正是本功能的意义）；
     * 即使 web 登了别的号，APP 通道请求也根本不读 web Cookie，不存在串号路径。
     * 第 1 条「无 token → 保持 web」分支**保留**：没登录的端口无法替登录接口，
     * 这类接口在未登录时本来就该保持 web（提示登录），绝不匿名硬闯。
     *
     * v5.1.4 补充：**web 没登录时也跳过第 2 条**。用户已经把 web 退出了，
     * 此时端口账号就是他的唯一账号，没有任何"串号"风险（见 [isAccountMismatchSafeForBorrow]）。
     * 否则「退出 web 登录 → 端口账号明明登录着 → 账号接口却全部回退到空 Cookie 的 web → 失败」。
     */
    private fun mustKeepWebForAccount(
        rule: ApiMigrationRules.Rule,
        identity: AppRcmdApi.RcmdIdentity,
        accessToken: String
    ): Boolean {
        if (!rule.isLoginRequired) return false
        if (accessToken.isEmpty()) return true
        if (isSinglePortMode()) return false
        if (!AccountManager.isWebLoggedIn()) return false
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

        // 写操作全部要求已登录（两种清单里的接口都依赖账号身份），且不能串号；
        // 单端口模式下身份被端口覆盖（同 buildGetPlan 的口径）；
        // 三级模式下若当前身份未登录则借用已登录端口（同 buildGetPlan，见 pickUsableIdentity）
        val identity = pickUsableIdentity(rule.preferredIdentity)
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
        return Verdict(isOk = true, reason = "")
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
