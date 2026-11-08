package com.RobinNotBad.BiliClient.api.gateway

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.api.CookieRefreshApi
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 一个**凭据通道**。
 *
 * # 设计前提：四端同级，没有主次
 *
 * Web（Cookie + csrf）与三个 APP 端口（access_key + appkey 签名）实现**同一个接口**，
 * 没有「web 是正统、APP 是实验」这种层级。[RequestExecutor] 只使用
 * **用户选中的那一个**通道（`AccountStore.mainPort()`），并且**不存在**「失败了换 web 重试」的分支 ——
 * 那条分支正是用户投诉的「去掉 Web 登录之后都是半残」的根源：
 * 表面上「有兜底」，实际上 web 未登录时兜底也是空的，用户等了一次注定失败的 RTT 之后仍然失败。
 *
 * # 这是 Kotlin-first 的 API（Java 侧是 getPort()，不是 port 字段）
 *
 * [port] 与 [isApp] 在接口里是普通 `val`（原因见下一节），所以编译成 Java 后是
 * `getPort()` / `isApp()` 这样的 getter 方法，**不是可以直接读的字段**。
 * 本仓库绝大多数调用是 Kotlin（属性语法无感），所以没有实际影响；
 * 但将来若有人从 Java 调用本接口，请按 `channel.getPort()` 写，不要期望 `.port` 字段。
 *
 * # 为什么接口属性不能写 `@JvmField`（实测记录，别改回去）
 *
 * 契约 `notes/gateway_contract_v521.md` §3 写的是：
 * ```kotlin
 * sealed interface AuthChannel {
 *     @JvmField val port: String
 *     @JvmField val isApp: Boolean
 * }
 * ```
 * 这个写法在 **Kotlin 1.7.20 下直接编译失败**（本项目用的就是这个版本）：
 * ```
 * error: this annotation is not applicable to target
 *        'member property without backing field or delegate'
 * ```
 * 接口成员没有 backing field，`@JvmField` 要求必须有。实测确认后改为普通 `val`，
 * 由实现类用构造参数提供 `override val`（`WebChannel` / `AppChannel` 都这么写，编译通过）。
 * 语义没有任何损失：`port`、`isApp` 依旧只读，也依旧只有 getter。
 *
 * # 为什么 `onAuthFailure` 返回 Boolean
 *
 * 它**只允许**决定「要不要触发本通道自己的凭据续期」（如 web 的 Cookie 刷新），
 * **不允许**回报「换个端口重试」。返回值语义是「我已尝试自愈，调用方可以重试同一个端口」，
 * 而不是「请切到别的端口」。见 [RequestExecutor] 的实现注释。
 */
sealed interface AuthChannel {

    /** 端口值，与 [AccountStore.PORT_WEB] / `PORT_ANDROID` / `PORT_HD` / `PORT_TV` 一致 */
    val port: String

    /** true = APP 端口（access_key 加 appkey 签名），false = web（Cookie 加 csrf） */
    val isApp: Boolean

    /** 该通道当前是否已登录 */
    fun isLoggedIn(): Boolean

    /** 该通道当前激活账号的 mid；未登录返回 0 */
    fun mid(): Long

    /** 生成该通道的请求头（web 是 webHeaders 语义，APP 是 appHeaders 语义） */
    fun headers(endpoint: ApiEndpoint): ArrayList<String>

    /**
     * 对参数做通道适配。
     *
     * - web：补 `csrf`，其余原样；
     * - APP：按 [ApiEndpoint] 丢弃 web 专用参数、改名、追加固定参数、按 [ApiEndpoint.appValueMap]
     *   转换参数值，最后补 `access_key` 与 appkey/ts/sign 签名。
     *
     * @param params 有序参数（LinkedHashMap，保序是为了签名结果稳定）
     * @return 最终要发出去的参数（有序）
     */
    fun adaptParams(
        endpoint: ApiEndpoint,
        params: LinkedHashMap<String, String>
    ): LinkedHashMap<String, String>

    /**
     * 请求成功后的钩子。
     *
     * web 通道用它检查 `cookie_refresh` 标志、触发主动续期；APP 通道无操作。
     * @param code 服务端返回的业务码
     */
    fun onSuccess(endpoint: ApiEndpoint, code: Int)

    /**
     * 鉴权失败（-101、-111 等）的钩子。
     *
     * **只允许**决定是否触发本通道的凭据续期，**不允许**回退到别的端口。
     * @return true 表示「已经尝试过自愈」，调用方可以选择重试**同一个通道**
     */
    fun onAuthFailure(endpoint: ApiEndpoint, code: Int): Boolean

    companion object {
        /** 鉴权类业务码：这些表示「凭据没被认出来」，不是业务错误 */
        @JvmField
        val AUTH_FAILURE_CODES: Set<Int> = setOf(-101, -111, -400, -401, -403, -352)
    }
}

/**
 * Web 通道：Cookie（SESSDATA）加 csrf。
 *
 * 与三个 APP 端口**同级**，不是「其它端口的兜底」。它同样是「用户选中的一个端口」，
 * 所以在 APP 端口被选中时，这个通道**根本不会被实例化去发请求**。
 */
class WebChannel : AuthChannel {

    override val port: String = AccountStore.PORT_WEB

    override val isApp: Boolean = false

    override fun isLoggedIn(): Boolean = AccountStore.isPortLoggedIn(AccountStore.PORT_WEB)

    override fun mid(): Long = AccountStore.activeMid(AccountStore.PORT_WEB)

    /**
     * web 请求头。
     *
     * 直接返回 [NetWorkUtil.webHeaders]（同一实例）：那是全仓库共享的 ArrayList，
     * `NetWorkUtil.refreshHeaders()` 会原地刷新它的 Cookie 项。**必须返回同一个实例**，
     * 不能复制 —— 否则刷新后的 Cookie 不会体现在已缓存的请求头上（历史坑）。
     */
    override fun headers(endpoint: ApiEndpoint): ArrayList<String> = NetWorkUtil.webHeaders

    /**
     * web 参数适配：补 `csrf`。
     *
     * 为什么 csrf 必须补：B 站 web 端的写操作（点赞、投币、收藏、发评论、发动态）
     * 除了 Cookie 还要校验 `csrf`（就是 Cookie 里的 `bili_jct`），缺了会回 -111。
     * 参数里已经带了就不覆盖 —— 极少数调用方自己传过（如 CookieRefreshApi）。
     */
    override fun adaptParams(
        endpoint: ApiEndpoint,
        params: LinkedHashMap<String, String>
    ): LinkedHashMap<String, String> {
        if (!params.containsKey("csrf")) {
            val csrf = SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "") ?: ""
            if (csrf.isNotEmpty()) params["csrf"] = csrf
        }
        return params
    }

    /**
     * web 成功后：如果 `cookie_refresh` 标志为 true，说明这一轮还该刷新，主动续一次。
     *
     * 为什么放在成功回调而不是定时器里：B 站的 Cookie 刷新要求 `refresh_token` 仍然有效，
     * 而它只在「上一次刷新成功」后才会更新。挂在真实请求的成功路径上，
     * 可以保证「有活动才刷新」，不会在用户长期不打开 App 时把 token 轮换掉。
     *
     * **绝不打印凭据**：只记录「刷新了 / 没刷新」。
     */
    override fun onSuccess(endpoint: ApiEndpoint, code: Int) {
        if (code != 0) return
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, false)) return
        renewQuietly(endpoint)
    }

    /**
     * web 鉴权失败：尝试一次主动续期，成功则告诉调用方「可以重试同一个 web 通道」。
     *
     * **注意**：这里**不会**去用某个 APP 端口的 token。web 通道就是 web 通道，
     * 它失败了就是 web 凭据失效，该让用户重新登录 web，而不是偷偷换端口。
     */
    override fun onAuthFailure(endpoint: ApiEndpoint, code: Int): Boolean {
        if (!AuthChannel.AUTH_FAILURE_CODES.contains(code)) return false
        if (!isLoggedIn()) return false
        return renewQuietly(endpoint)
    }

    /**
     * 真正执行一次主动续期（v5.3.1：**转调唯一入口** [CookieRefreshApi.renewWeb]）。
     *
     * # 为什么这里只剩一行
     * v5.2.1~v5.3.0 有 4 条各自实现的续期路径，本函数是其中一条，步骤与
     * [CookieRefreshApi] 里的那份**逐行重复**，只多了 `AccountStore.persistActiveWeb()`。
     * 现在「缺 refresh_token 提前判定」「成功后回写账号槽」都在唯一入口内部做了，
     * 这里不需要再抄一遍 —— 抄两遍正是当初「有的路径回写、有的不回写」的成因。
     *
     * 续期是「尽力而为」：任何结局都不抛异常（一次点赞请求不该因为后台续期失败而报错）。
     * 结果只用于日志，[ApiEndpoint] 只用来标识是哪个请求触发的。
     */
    private fun renewQuietly(endpoint: ApiEndpoint): Boolean {
        val result = CookieRefreshApi.renewWeb()
        if (result.renewed()) {
            Logu.d(TAG, "web 凭据已主动续期（" + endpoint.id + "）")
        } else {
            // 只记结局类型，绝不记凭据内容
            Logu.d(TAG, "web 主动续期未成功（" + endpoint.id + "）：" + result.javaClass.simpleName)
        }
        return result.renewed()
    }

    companion object {
        private const val TAG = "gw-web-channel"
    }
}

/**
 * APP 通道：access_key 加 appkey/ts/sign。
 *
 * 三个 APP 端口（android / hd / tv）都用这一个类，差别只在 [identity]
 * （决定 appkey、appsec、UA、mobi_app、build、device_name）。这也是「四端同级」的具体体现：
 * 不是给 web 写一套、再给 APP 写三套，而是一套抽象加三个身份。
 *
 * @param identity `AppRcmdApi.RcmdIdentity`，由 `AccountStore.identityOf(port)` 解析而来
 */
class AppChannel(@JvmField val identity: AppRcmdApi.RcmdIdentity) : AuthChannel {

    override val port: String = AccountStore.portOfIdentity(identity.id)

    override val isApp: Boolean = true

    /**
     * 只看 token 是否非空。
     *
     * 为什么不用 `AccountStore.isPortLoggedIn`：那个判断还要求 mid > 0，
     * 而 token 有效但 mid 尚未回填（登录响应解析中途）时，不该判定「没登录」把请求拦掉。
     * token 是 APP 通道唯一真正需要的凭据。
     */
    override fun isLoggedIn(): Boolean = accessToken().isNotEmpty()

    override fun mid(): Long = AppRcmdApi.getIdentityMid(identity.id)

    /** 该通道当前激活的 access_token；空串表示未登录 */
    fun accessToken(): String = AppRcmdApi.getAccessToken(identity.id)

    /**
     * APP 请求头。
     *
     * 直接用 [AppRcmdApi.appHeaders]（buvid、env、app-key、UA、trace-id…），
     * 不在这里另造一套，避免两处 UA 不一致被服务端识别成不同客户端。
     */
    override fun headers(endpoint: ApiEndpoint): ArrayList<String> {
        val list = AppRcmdApi.appHeaders(identity)
        // 极少数端点需要额外头（如番剧的 referer），登记在 ApiEndpoint.appHeaders 里
        for ((key, value) in endpoint.appHeaders) {
            list.add(key)
            list.add(value)
        }
        return list
    }

    /**
     * APP 参数适配。顺序很重要，错一步签名就对不上：
     *
     * 1. **先丢** web 专用参数（`csrf`、`w_rid`、`wts`、浏览器指纹等）—— 见 [ApiCatalog.APP_DROP_PARAMS]；
     * 2. **再改名**（`mid` → `vmid`、`id` → `room_id`）；
     * 3. **再按值映射**（点赞 `like` 的 1/2 → 0/1，这是 APP 端点语义相反的那批）；
     * 4. **再补固定参数**（如直播 feed 那套 channel/version/device）；
     * 5. **最后**补身份参数（`mobi_app`/`platform`/`build`/`device_name`/`buvid`/`access_key`），
     *    然后调 [AppRcmdApi.sign] 追加 `appkey`/`ts`/`sign`。
     *
     * 签名必须最后做：`AppRcmdApi.sign` 会把**当时已有的全部参数**按字典序拼起来算 MD5，
     * 追加在它之后的参数不会进入签名，服务端校验就会失配。
     */
    override fun adaptParams(
        endpoint: ApiEndpoint,
        params: LinkedHashMap<String, String>
    ): LinkedHashMap<String, String> {
        dropWebOnlyParams(endpoint, params)
        renameParams(endpoint, params)
        remapValues(endpoint, params)
        params.putAll(endpoint.extraParams)
        addIdentityParams(params)
        signParams(params)
        return params
    }

    /** 第 1 步：丢掉 web 专用参数 */
    private fun dropWebOnlyParams(endpoint: ApiEndpoint, params: LinkedHashMap<String, String>) {
        val drop = endpoint.dropParams
        if (drop.isNotEmpty()) {
            params.keys.removeAll { drop.contains(it.lowercase()) }
        }
        // 即使端点没登记 dropParams，这四类也必须丢（否则签名或服务端校验必然失配）：
        // csrf 是 web 写操作的凭据；w_rid/wts 是 WBI 签名；access_key/appkey/ts/sign 由本通道自己生成。
        params.keys.removeAll {
            val key = it.lowercase()
            key == "csrf" || key == "w_rid" || key == "wts" ||
                    key == "access_key" || key == "appkey" || key == "ts" || key == "sign"
        }
    }

    /** 第 2 步：参数改名（保序：删掉旧键后按字典序插回，签名只认最终集合） */
    private fun renameParams(endpoint: ApiEndpoint, params: LinkedHashMap<String, String>) {
        if (endpoint.renameParams.isEmpty()) return
        for ((from, to) in endpoint.renameParams) {
            val value = params.remove(from) ?: continue
            params[to] = value
        }
    }

    /**
     * 第 3 步：参数值语义映射。
     *
     * 最典型的是点赞：web 传 `like=1`（点赞）或 `like=2`（取消），
     * APP 端点要的是 `like=0`（点赞）或 `like=1`（取消）。映射登记在
     * [ApiEndpoint.appValueMap]，调用方只管传 web 语义，这里统一翻译 ——
     * **调用方不要自己写 0/1**，写错了用户点「点赞」会变成取消（审计 P0-2）。
     */
    private fun remapValues(endpoint: ApiEndpoint, params: LinkedHashMap<String, String>) {
        if (endpoint.appValueMap.isEmpty()) return
        for ((param, mapping) in endpoint.appValueMap) {
            remapOne(param, mapping, params)
        }
    }

    /** 单个参数的值映射（从 [remapValues] 拆出：循环体里不留 continue，便于阅读与 detekt 通过） */
    private fun remapOne(
        param: String,
        mapping: Map<String, String>,
        params: LinkedHashMap<String, String>
    ) {
        val current = params[param] ?: return
        val mapped = mapping[current] ?: return
        params[param] = mapped
    }

    /** 第 5 步之一：补 APP 身份参数（与 [ApiRouter.addIdentityParams] 口径一致） */
    private fun addIdentityParams(params: LinkedHashMap<String, String>) {
        params["mobi_app"] = identity.feedMobiApp
        params["platform"] = PLATFORM_ANDROID
        params["build"] = identity.feedBuild.toString()
        params["device_name"] = identity.feedDeviceName
        params["buvid"] = AppRcmdApi.getBuvid()
        val token = accessToken()
        if (token.isNotEmpty()) params["access_key"] = token
    }

    /** 第 5 步之二：签名（必须最后做，见 [adaptParams] 的注释） */
    private fun signParams(params: LinkedHashMap<String, String>) {
        val formData = NetWorkUtil.FormData().setUrlParam(false)
        for ((key, value) in params) formData.put(key, value)
        AppRcmdApi.sign(formData, identity)
        // sign() 会追加 appkey/ts/sign，把它们回写到有序 map 里，保证 URL 与 body 都用同一份参数
        val signed = formData.toString()
        params.clear()
        if (signed.isNotEmpty()) {
            for (pair in signed.split("&")) {
                val index = pair.indexOf('=')
                if (index <= 0) continue
                params[pair.substring(0, index)] = pair.substring(index + 1)
            }
        }
    }

    /**
     * APP 通道没有「续期凭据」这件事本身。
     *
     * access_key 的续期走 `AppRcmdApi` 的 refresh_token 流程（扫码协议那一侧），
     * 不属于「一次业务请求成功后顺手做」的范畴，所以这里刻意留空 ——
     * 这也正好体现四端同级：**web 有 web 的续期方式，APP 有 APP 的**，互不代劳。
     */
    override fun onSuccess(endpoint: ApiEndpoint, code: Int) {
        // 故意留空：APP 通道的业务请求成功与凭据续期无关
    }

    /**
     * APP 鉴权失败：**不**做任何跨端口动作，也**不**假装自愈。
     *
     * 为什么这里返回 false：APP 的 access_key 续期是独立协议（passport-tv-login 的 refresh），
     * 一次业务请求失败时在后台偷偷刷新 token 会让「失败原因」变得不可解释。
     * 正确做法是把 `AuthFailureException` 抛给调用方，由上层提示用户重新登录该端口。
     *
     * **绝不**在这里改成 `return CredentialStore.channelOf(PORT_WEB)` 之类 ——
     * 那正是用户要求删掉的「回退 web」。
     */
    override fun onAuthFailure(endpoint: ApiEndpoint, code: Int): Boolean {
        if (AuthChannel.AUTH_FAILURE_CODES.contains(code)) {
            Logu.d(TAG, "APP 通道鉴权失败（" + endpoint.id + " code=" + code + "），不跨端口重试")
        }
        return false
    }

    companion object {
        private const val TAG = "gw-app-channel"
        private const val PLATFORM_ANDROID = "android"
    }
}
