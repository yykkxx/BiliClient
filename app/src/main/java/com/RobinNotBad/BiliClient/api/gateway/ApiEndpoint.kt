package com.RobinNotBad.BiliClient.api.gateway

/**
 * 端点对凭据的要求（决定「这个接口在非 web 端口上能不能做」）。
 *
 * 这些取值不是猜的，来自 `notes/audit_login_v521.md` 与 bilibili-API-collect 的
 * 「认证方式」字段：B 站 REST 接口只有三种态度 ——
 * 不看登录（[NONE]）、只认 Cookie（[WEB_COOKIE]）、只认或也认 access_key（[APP_TOKEN] 与 [EITHER]）。
 *
 * **为什么 [WEB_COOKIE] 要单独存在，而不是靠「有没有 appHost」推断**：
 * 投币与点赞这两条最关键的写操作**同路径**也接受 APP 签名参数，但服务端会**拒绝**（回 -101），
 * 必须换到 `app.bilibili.com` 的专用端点才行。
 * 「仅可 Cookie」是关于**协议**的事实，必须显式登记，不能由路径形状推导出来。
 */
enum class AuthRequirement {
    /** 不需要登录：匿名也能拿到与登录后同结构的数据 */
    NONE,

    /** **仅可** Web Cookie（SESSDATA）。补 access_key 同路径也无效（审计 P0-1、P0-2、P0-3） */
    WEB_COOKIE,

    /** **仅可** APP access_key。web Cookie 打这个端点不认 */
    APP_TOKEN,

    /** 两种凭据都认（绝大多数业务接口属于这类） */
    EITHER,

    /** 协议层不可能迁移：登录握手、gRPC-Web、非 B 站、静态文件、已证实无 APP 等价 */
    IMPOSSIBLE
}

/**
 * 一个 B 站官方端点的**完整描述**（纯数据、无副作用）。
 *
 * # 为什么要有这个类
 *
 * v5.2.0 之前，「这个接口该用哪套凭据、要不要签名、参数怎么改名、失败了能不能重试」
 * 这四件事散落在 `NetWorkUtil`、[com.RobinNotBad.BiliClient.api.migration.ApiRouter]、
 * 各 `XxxApi` 的判断里，谁也不知道全集是什么。
 * 结果是用户投诉的那句「去掉 Web 登录之后都是半残」——没人能列出「到底还差哪些」。
 *
 * 把端点抽成数据后：
 * 1. 要改行为只动一行数据，不用改请求层逻辑；
 * 2. [ApiCatalog.coverageReport] 可以**枚举**当前端口缺什么（可检查的证据，不是口号）；
 * 3. 四端（web、android、hd、tv）走同一张表，天然同级。
 *
 * # 与旧 [com.RobinNotBad.BiliClient.api.migration.ApiMigrationRules.Rule] 的关系
 *
 * 旧 `Rule` 描述「web 请求 → APP 请求怎么改写」；本类描述「端点本身是什么」。
 * 后者包含前者（[appCapable]、[appHost]、[appPath]、[renameParams]、[extraParams]、[dropParams]），
 * 但补上了旧表没有的四件事：**谁能用**（[auth]）、**能不能重试**（[idempotent]）、
 * **参数值的语义映射**（[appValueMap]，用来表达 APP 端点 `like=0` 是点赞这种反义）、
 * **是否已实测**（[verifiedOnApp]，见审计 P0-8）。
 *
 * # 参数表的两段说明
 *
 * 前 12 个参数来自契约 `notes/gateway_contract_v521.md` §1，一个不改。
 * 其后的扩展参数全部**带默认值**并且追加在末尾 —— 因此契约里的 12 参数写法、
 * 以及任何旧的构造调用都仍然合法；需要时也能用命名参数直接传。
 *
 * @param id 稳定标识（如 `video.coin.add`）。**不要用 URL 当 id** —— URL 会变，统计与历史要靠 id。
 * @param host 精确 host（小写）
 * @param path 精确路径；单独一个星号表示该 host 全部路径；以「斜杠加星号」结尾表示前缀匹配
 * @param method `GET` 或 `POST`
 * @param auth 凭据要求（见 [AuthRequirement]）
 * @param appHost APP 等价端点的 host；null 表示与 [host] 相同
 * @param appPath APP 等价端点的 path；null 表示与 [path] 相同（即「同路径 APP 参数化」）
 * @param renameParams web 参数名 → APP 参数名（如 `mid` → `vmid`、`id` → `room_id`）
 * @param extraParams APP 通道要追加的固定参数
 * @param dropParams APP 通道要丢掉的参数（如 `csrf`、`w_rid`）
 * @param binaryResponse 响应是二进制（protobuf、deflate），判定时只看 HTTP 状态与非空
 * @param note 中文备注：这条端点的坑（**用户会读**）
 * @param appCapable 该端点能否在 APP 通道上使用（同路径 APP 参数化，或有路径改写）。
 *   必须显式给，因为「同路径参数化」的 appHost/appPath 都是 null，只看它们会误判成「没有 APP 等价」，
 *   而一百三十六条规则里绝大多数正是这种。
 * @param category 设置页「按分类展示」用
 * @param loginRequired 是否必须已登录。未登录时 APP 侧可能回 code=0 但 data 为空，属静默错误
 * @param idempotent 写操作是否幂等。false 表示**绝不重放**（投币、发评论、发私信），
 *   因为重放会造成重复扣币、重复发送这类不可撤销的副作用
 * @param requiredDataFields `data` 里必须存在的点号路径（内容校验，防「code=0 加空 data」的风控壳）
 * @param acceptedCodes 允许当成「通道可用」的业务码（如 `nav` 匿名回 -101 但 `wbi_img` 完整）
 * @param appOnly 仅可 APP：web 端点即使补 access_key 也无效，web 通道**不能**用它（投币、点赞）
 * @param appValueMap APP 通道的参数**值**映射：`参数名 → (web 参数值 → APP 参数值)`。
 *   典型：点赞 web 端 `like=1` 是点赞、`like=2` 是取消；APP 端 `like=0` 是点赞、`like=1` 是取消。
 *   这种「同一个参数名、相反的值语义」用改名解决不了，必须显式映射，
 *   否则用户点「点赞」在 APP 端口上会变成取消（审计 P0-2 明确点名这条坑）。
 * @param surrogateId 该端点「仅可 Web」、但同一**功能**由另一个 APP 端点兜住时，填那个端点的 id。
 *   用途：让覆盖报告说清「这条本身用不了，但功能没丢，由谁提供」，
 *   而不是含混地回一句「回退 web」（那句话正是用户明确要求删掉的语义）
 * @param webEquivalentId APP 专用端点的 web 等价端点 id（与 [surrogateId] 反向指向），
 *   让覆盖报告把一对端点归并成「同一功能，web 与 APP 双入口」
 * @param appHeaders APP 通道额外请求头（极少数端点需要，如番剧的 referer）。
 *   默认空表，绝大多数端点不需要
 * @param splitInto 协议层**确实不存在**对应端点、只能拆成多次调用完成的端点，填要拆成的那几次调用的 id。
 *   目前只有一键三连：服务端没有 APP 侧三连端点，只能本地串点赞、投币、收藏三次再拼结果，
 *   拆解后原子性无法保证，UI 必须能显示「部分成功」文案
 * @param verifiedOnApp 该端点的 APP 通道**是否已实测验证**。
 *   这个参数是审计 P0-8 的直接产物：旧规则表里三十多条 note 写着「实测 -101（通道被接受）」，
 *   但 -101 的官方定义是「账号未登录」，即服务端根本没识别出登录态，
 *   它**不**表示「服务端接受了这个通道」，所以那些端点其实从未被真正验证过。
 *   true 表示有 code=0 且结构正确的实测证据；false 表示只知道「按规则能改写」
 * @param impossibleReason [AuthRequirement.IMPOSSIBLE] 的**具体理由**（写清楚为什么不可能，用户会读）
 */
class ApiEndpoint(
    @JvmField val id: String,
    @JvmField val host: String,
    @JvmField val path: String,
    @JvmField val method: String,
    @JvmField val auth: AuthRequirement,
    @JvmField val appHost: String? = null,
    @JvmField val appPath: String? = null,
    @JvmField val renameParams: Map<String, String> = emptyMap(),
    @JvmField val extraParams: Map<String, String> = emptyMap(),
    @JvmField val dropParams: Set<String> = emptySet(),
    @JvmField val binaryResponse: Boolean = false,
    @JvmField val note: String = "",

    // —— v5.2.1 扩展参数（全部带默认值，追加在末尾，旧写法一字不改）——
    @JvmField val appCapable: Boolean = false,
    @JvmField val category: String = "其他",
    @JvmField val loginRequired: Boolean = false,
    @JvmField val idempotent: Boolean = true,
    @JvmField val requiredDataFields: List<String>? = null,
    @JvmField val acceptedCodes: List<Int> = emptyList(),
    @JvmField val appOnly: Boolean = false,
    @JvmField val appValueMap: Map<String, Map<String, String>> = emptyMap(),
    @JvmField val surrogateId: String = "",
    @JvmField val webEquivalentId: String = "",
    @JvmField val appHeaders: Map<String, String> = emptyMap(),
    @JvmField val splitInto: List<String> = emptyList(),
    @JvmField val verifiedOnApp: Boolean = false,
    @JvmField val impossibleReason: String = ""
) {

    /** 该端点的 APP 等价形态是否可用（同路径参数化，换端点，或仅可 APP 的专用端点） */
    fun canUseApp(): Boolean = auth == AuthRequirement.APP_TOKEN || appCapable

    /** 该端点的 web 通道是否可用（仅可 APP 的端点对 web Cookie 无效） */
    fun canUseWeb(): Boolean = !appOnly

    /** 该端点是否需要登录 */
    fun needsLogin(): Boolean {
        if (loginRequired) return true
        return auth == AuthRequirement.WEB_COOKIE || auth == AuthRequirement.APP_TOKEN
    }

    /**
     * 该端点是否命中给定 host 与 path。
     *
     * path 必须传**未解码**的 URL path（`HttpUrl.encodedPath` 口径），
     * 与旧 [com.RobinNotBad.BiliClient.api.migration.ApiMigrationRules.Rule.matches] 完全一致。
     */
    fun matches(candidateHost: String, candidatePath: String): Boolean {
        if (!host.equals(candidateHost, ignoreCase = true)) return false
        return when {
            path == ALL_PATHS -> true
            path.endsWith(ALL_PATHS) -> candidatePath.startsWith(path.substring(0, path.length - 1))
            else -> path == candidatePath
        }
    }

    /** 日志与报告用的短标识（不含 query，避免泄露参数） */
    fun endpointKey(): String = host + path

    override fun toString(): String = id + " [" + method + " " + host + path + "] auth=" + auth

    companion object {
        /**
         * 通配符：单独一个星号表示匹配该 host 全部路径；以「斜杠加星号」结尾表示前缀匹配。
         * 单独提常量是为了避免散落的魔法字符串。
         */
        const val ALL_PATHS: String = "*"

        const val METHOD_GET: String = "GET"
        const val METHOD_POST: String = "POST"
    }
}
