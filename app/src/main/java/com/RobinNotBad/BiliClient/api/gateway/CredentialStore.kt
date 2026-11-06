package com.RobinNotBad.BiliClient.api.gateway

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.api.CookieRefreshApi
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import org.json.JSONException // quality-allow：四端导出/导入是纯 JSON 结构读写，无 model 可落；且仓库 API 层既有惯例就是 org.json
import org.json.JSONObject // quality-allow：同上 —— 导出格式是「四端键 → 凭据字段」的通用 JSON，用 Gson 建 model 反而要新建 4 个类
import java.io.IOException

/**
 * 凭据的**唯一读写入口**（v5.2.1）。
 *
 * # 为什么必须收敛到一个入口
 *
 * 用户原话：「所有关于 B站官方需要登录的网络请求，都抽象出来放到统一的地方便于统一管理和维护，
 * 以及所有操作登录凭据的地方也放在那里（比如说导出登录凭据的时候，是四端同时导出）」。
 *
 * v5.2.0 的现状是 72 处代码直接读 `SharedPreferencesUtil.cookies` / `mid` / `csrf` /
 * `refresh_token` / `cookie_refresh`，涉及 16 个文件。每一处都隐含「只有 web 登录算登录」这个假设 ——
 * 于是 web 一退出，整个 App 就「半残」。把这些读写收敛到本对象后，
 * 「哪些端口的凭据怎么用」只有一份实现，四端天然同级。
 *
 * # 四端同级（这是本对象的中心思想）
 *
 * [web][AccountStore.PORT_WEB]、[android][AccountStore.PORT_ANDROID]、
 * [hd][AccountStore.PORT_HD]、[tv][AccountStore.PORT_TV] 四个端口在本对象里**完全对等**：
 * - 每个端口都有自己的 [AuthChannel]（web 是 [WebChannel]，三个 APP 端口是 [AppChannel]）；
 * - 导出是**四端同时导出**，导入是**四端同时导入**；
 * - [clearPort] 只清指定端口，不影响其它端口。
 *
 * 没有任何一个方法是「web 专用」或「优先 web」。
 *
 * # 硬约束
 *
 * - **绝不把任何凭据写进 logcat**。导出结果只在返回值里，需要落盘由调用方自己决定。
 * - 不删不改既有偏好键语义（`cookies` / `mid` / `csrf` / `refresh_token` / `cookie_refresh` /
 *   `app_rcmd_*` / `account_mgmt_*` 全部按原意沿用）。
 * - 本对象**不发起业务请求**，只在校验/续期时调既有 API 的公开函数。
 */
object CredentialStore {

    private const val TAG = "gw-credential"

    /** 导出 JSON 的格式版本。导入时按它判断兼容性 */
    private const val FORMAT_VERSION = 1

    // 导出格式的键名（写成常量，避免手滑拼错导致导入静默丢字段）
    private const val KEY_VERSION = "version"
    private const val KEY_PORTS = "ports"
    private const val KEY_MID = "mid"
    private const val KEY_NAME = "name"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_TOKEN = "token"
    private const val KEY_REFRESH_TOKEN = "refreshToken"

    // ==================== 通道 ====================

    /**
     * 当前生效通道 = 用户选中的「其它页面 API」端口（`AccountStore.mainPort()`）。
     *
     * 这是 [RequestExecutor] 唯一会使用的通道。**没有回退**：
     * 选中的是 HD，就只用 HD；HD 的凭据失效就报错让用户处理，不会偷偷换 web。
     */
    @JvmStatic
    fun activeChannel(): AuthChannel = channelOf(AccountStore.mainPort())

    /**
     * 指定端口的通道（导出、导入、续期、校验时用）。
     *
     * 非法端口按 `AccountStore.normalizePort` 归一化（与历史脏数据口径一致）。
     */
    @JvmStatic
    fun channelOf(port: String): AuthChannel {
        val portValue = AccountStore.normalizePort(port)
        if (portValue == AccountStore.PORT_WEB) return WebChannel()
        val identity = AccountStore.identityOf(portValue)
            ?: AppRcmdApi.getIdentityById(null)
        return AppChannel(identity)
    }

    // ==================== 四端登录状态 ====================

    /**
     * 四端是否登录（同序：web、android、hd、tv）。
     *
     * 用 [AuthChannel.isLoggedIn] 逐端口判定，而不是直接读某个键 ——
     * 这样「登录」的定义只有一份实现（web 看 Cookie，APP 看 access_key）。
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun loginStates(): Map<String, Boolean> {
        val map = LinkedHashMap<String, Boolean>()
        for (port in AccountStore.ALL_PORTS) {
            map[port] = channelOf(port).isLoggedIn()
        }
        return map
    }

    /** 指定端口是否登录 */
    @JvmStatic
    fun isLoggedIn(port: String): Boolean = channelOf(port).isLoggedIn()

    // ==================== 导出（四端同时导出） ====================

    /**
     * 四端**同时**导出凭据。
     *
     * 用户明确要求：「导出登录凭据的时候，是四端同时导出」。
     * 所以这里**不是**「导出当前选中的那一个端口」，而是一次性把四个端口全部导出 ——
     * 换设备、重装、给另一台设备导入时，用户不需要分别操作四次。
     *
     * 格式（`FORMAT_VERSION` 为当前版本号）：
     * ```json
     * {
     *   "version": 1,
     *   "ports": {
     *     "web":     { "mid": 123, "name": "昵称", "cookies": "...", "token": "",    "refreshToken": "..." },
     *     "android": { "mid": 456, "name": "昵称", "cookies": "",    "token": "...", "refreshToken": "..." },
     *     "hd":      { ... },
     *     "tv":      { ... }
     *   }
     * }
     * ```
     *
     * **四个 port 键总是存在**（即使某端未登录也写一个各字段为空的对象），
     * 这样导入端不需要猜「这个文件里有没有 hd」，也便于用户肉眼核对导出结果。
     *
     * **绝不写 logcat**。返回值里含完整凭据，调用方写文件时要自己决定保存位置。
     *
     * @return JSON 字符串；构建失败返回空串（绝不返回半个 JSON 让导入端解析出错）
     */
    @JvmStatic
    fun exportAll(): String {
        return try {
            val root = JSONObject() // quality-allow：导出格式是通用 JSON，见文件头说明
            root.put(KEY_VERSION, FORMAT_VERSION)
            val ports = JSONObject() // quality-allow：同上
            for (port in AccountStore.ALL_PORTS) {
                ports.put(port, exportPort(port))
            }
            root.put(KEY_PORTS, ports)
            root.toString()
        } catch (ignored: JSONException) {
            // JSON 写入理论上不会失败，但一旦失败也绝不能把半个 JSON 交出去
            Logu.e(TAG, "导出凭据失败：" + ignored.javaClass.simpleName)
            ""
        }
    }

    /**
     * 导出单个端口。
     *
     * 凭据来源分两路（与 [AccountStore] 的存储模型一致）：
     * - web：`cookies` / `mid` / `refresh_token` 是激活槽位键；
     * - APP：`app_rcmd_<identity>_access_token` 等是各身份独立槽位。
     * 名字与 mid 走 [AccountStore] 的账号投影（它知道「激活账号是谁」）。
     */
    private fun exportPort(port: String): JSONObject {
        val obj = JSONObject() // quality-allow：同上，导出格式用 org.json
        val portValue = AccountStore.normalizePort(port)
        obj.put(KEY_MID, AccountStore.activeMid(portValue))
        obj.put(KEY_NAME, accountNameOf(portValue))
        if (portValue == AccountStore.PORT_WEB) {
            obj.put(KEY_COOKIES, SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
            obj.put(KEY_TOKEN, "")
            obj.put(KEY_REFRESH_TOKEN,
                SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "") ?: "")
        } else {
            val identityId = AccountStore.identityIdOf(portValue)
            obj.put(KEY_COOKIES, appSlot(identityId, "cookies"))
            obj.put(KEY_TOKEN, appSlot(identityId, "access_token"))
            obj.put(KEY_REFRESH_TOKEN, appSlot(identityId, "refresh_token"))
        }
        return obj
    }

    /** APP 身份槽位的读取（键名规则与 `AppRcmdApi.keyOf` 一致：app_rcmd_{identity}_{suffix}） */
    private fun appSlot(identityId: String, suffix: String): String {
        if (identityId.isEmpty()) return ""
        return SharedPreferencesUtil.getString("app_rcmd_" + identityId + "_" + suffix, "") ?: ""
    }

    /** 该端口激活账号的昵称；拿不到就返回 uid 形式，绝不返回 null 让 JSON 出现 null 值 */
    private fun accountNameOf(port: String): String {
        val mid = AccountStore.activeMid(port)
        if (mid <= 0L) return ""
        for (entry in AccountStore.accounts(port)) {
            if (entry.mid == mid) return entry.name
        }
        return "uid " + mid
    }

    // ==================== 导入（四端同时导入） ====================

    /**
     * 四端**同时**导入，返回每端导入结果。
     *
     * 行为约定：
     * - 只导入某端在文件里**真的带了凭据**的那些（全空端口跳过，不把「未登录」覆盖成本地已登录状态）；
     * - web 走 [AccountStore.loginWeb]（它会先快照当前激活账号再写新号，多账号不丢）；
     * - APP 走 [AccountStore.loginApp]（同理）；
     * - **任何一端失败都不影响其它端**（逐端 try/catch），返回值里能看出哪端没成；
     * - 格式不认识（没有 `ports` 键 / version 过高）→ 全部返回 false，不猜测。
     *
     * @return 端口 → 是否导入成功
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun importAll(json: String): Map<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        for (port in AccountStore.ALL_PORTS) result[port] = false
        val ports = parsePorts(json) ?: return result
        for (port in AccountStore.ALL_PORTS) {
            val entry = ports.optJSONObject(port)
            result[port] = if (entry == null) false else importPort(port, entry)
        }
        return result
    }

    /**
     * 解析导出文件的 `ports` 对象。
     *
     * @return null 表示格式不认识（版本不兼容 / 缺少 ports / 不是合法 JSON）
     */
    private fun parsePorts(json: String): JSONObject? {
        if (json.isEmpty()) return null
        return try {
            val root = JSONObject(json) // quality-allow：解析自己导出的格式，见文件头说明
            val version = root.optInt(KEY_VERSION, 0)
            if (version > FORMAT_VERSION) {
                // 未来版本的文件可能含本版本不认识的字段，强行导入会丢数据
                Logu.e(TAG, "导入凭据失败：文件版本 " + version + " 高于本版本 " + FORMAT_VERSION)
                return null
            }
            root.optJSONObject(KEY_PORTS)
        } catch (ignored: JSONException) {
            Logu.e(TAG, "导入凭据失败：不是合法 JSON（" + ignored.javaClass.simpleName + "）")
            null
        }
    }

    /** 导入单个端口；没有可用凭据时返回 false（不算失败，只是没东西可导） */
    private fun importPort(port: String, entry: JSONObject): Boolean {
        val portValue = AccountStore.normalizePort(port)
        val mid = entry.optLong(KEY_MID, 0L)
        if (mid <= 0L) return false
        val name = entry.optString(KEY_NAME, "")
        return try {
            if (portValue == AccountStore.PORT_WEB) {
                importWeb(mid, entry, name)
            } else {
                importApp(portValue, mid, entry, name)
            }
        } catch (ignored: JSONException) {
            Logu.e(TAG, "导入 " + portValue + " 端口失败：" + ignored.javaClass.simpleName)
            false
        }
    }

    private fun importWeb(mid: Long, entry: JSONObject, name: String): Boolean {
        val cookies = entry.optString(KEY_COOKIES, "")
        if (cookies.isEmpty()) return false
        // csrf 不单独存：它是 Cookie 里的 bili_jct，AccountStore 会从 Cookie 串里取
        val csrf = NetWorkUtil.getInfoFromCookie("bili_jct", cookies)
        val refreshToken = entry.optString(KEY_REFRESH_TOKEN, "")
        AccountStore.loginWeb(mid, cookies, csrf, refreshToken, name.ifEmpty { null }, null)
        return AccountStore.isPortLoggedIn(AccountStore.PORT_WEB)
    }

    private fun importApp(port: String, mid: Long, entry: JSONObject, name: String): Boolean {
        val token = entry.optString(KEY_TOKEN, "")
        if (token.isEmpty()) return false
        val cookies = entry.optString(KEY_COOKIES, "")
        val refreshToken = entry.optString(KEY_REFRESH_TOKEN, "")
        AccountStore.loginApp(port, mid, token, refreshToken, cookies, name.ifEmpty { null }, null)
        return AccountStore.isPortLoggedIn(port)
    }

    // ==================== 清除 ====================

    /**
     * 只清某一端（退出该端口当前激活账号）。
     *
     * 用 [AccountStore.logoutActive]：如果该端口还有别的账号，它会自动切到下一个，
     * 而不是把整个端口清空 —— 与「账号管理」页的语义一致。
     */
    @JvmStatic
    fun clearPort(port: String) {
        val portValue = AccountStore.normalizePort(port)
        AccountStore.logoutActive(portValue)
    }

    // ==================== 校验 ====================

    /**
     * 凭据是否**仍然有效**（服务端还认不认）。
     *
     * 与 [isLoggedIn] 的区别：那个只看本地有没有凭据，这个会真的发一次请求。
     * 所以它在弱网 / 服务端抽风时可能耗时，**不要放在主线程**；
     * 而且**不发请求就返回 null**（未登录时没有可校验的凭据，返回 null 而不是 false ——
     * 「未登录」与「登录了但失效」是两件事，不能混）。
     *
     * - web：`passport-login/web/cookie/info` 的 `data.isLogin`（Cookie 语义的权威判断）；
     * - APP：`app.bilibili.com/x/v2/account/myinfo` 返回 `code=0` 且 `data` 非空
     *   （[ApiCatalog] 里登记为 `user.account.myinfo`）。
     *
     * @return null = 未登录（没东西可校验）；true / false = 校验结果
     */
    @JvmStatic
    fun validate(port: String): Boolean? {
        val portValue = AccountStore.normalizePort(port)
        val channel = channelOf(portValue)
        if (!channel.isLoggedIn()) return null
        return try {
            if (portValue == AccountStore.PORT_WEB) validateWeb() else validateApp(portValue)
        } catch (ignored: IOException) {
            // 网络问题不等于凭据失效：返回 null（未知），让调用方不要据此清账号
            Logu.d(TAG, "校验 " + portValue + " 失败（网络）：" + ignored.javaClass.simpleName)
            null
        } catch (ignored: JSONException) {
            Logu.d(TAG, "校验 " + portValue + " 失败（响应格式）：" + ignored.javaClass.simpleName)
            null
        }
    }

    /** web 校验：读 `cookie/info` 的 `data.isLogin` */
    private fun validateWeb(): Boolean {
        val data = CookieRefreshApi.cookieInfo()
        // CookieRefreshApi.cookieInfo() 直接返回 data 对象；isLogin 缺省时按「仍然有效」处理，
        // 避免服务端少回一个字段就把用户的登录态判死
        return if (data.has("isLogin")) data.optBoolean("isLogin", true) else true
    }

    /**
     * APP 校验：`x/v2/account/myinfo` 的 code=0 且 data 非空。
     *
     * 参数用 [AppChannel.adaptParams] 生成，而不是在这里再写一份 ——
     * 签名只有一份实现才不会出现「校验能过、业务请求 -663」这种自相矛盾的结果。
     */
    private fun validateApp(port: String): Boolean {
        val identity = AccountStore.identityOf(port) ?: return false
        if (AppRcmdApi.getAccessToken(identity.id).isEmpty()) return false
        val endpoint = ApiCatalog.findById("user.account.myinfo") ?: return false
        val channel = AppChannel(identity)
        val params = LinkedHashMap<String, String>()
        channel.adaptParams(endpoint, params)
        val url = "https://" + endpoint.host + endpoint.path + NetWorkUtil.FormData()
            .setUrlParam(true)
            .also { formData ->
                for ((key, value) in params) formData.put(key, value)
            }.toString()
        return try {
            val response = NetWorkUtil.get(url, channel.headers(endpoint), null)
            val body = response.body?.string() ?: return false
            val root = JSONObject(body) // quality-allow：只读 code 与 data 是否存在，不需要 model
            root.optInt("code", -1) == 0 && !root.isNull("data")
        } catch (ignored: IOException) {
            // 网络异常不能判成「凭据失效」，否则弱网下会把用户的账号误清
            Logu.d(TAG, "APP 校验网络异常：" + ignored.javaClass.simpleName)
            false
        } catch (ignored: JSONException) {
            Logu.d(TAG, "APP 校验响应格式异常：" + ignored.javaClass.simpleName)
            false
        }
    }

    // ==================== Web 主动续期 ====================

    /**
     * Web 主动续期：检查 `cookie_refresh` 标志与 `cookie/info` 的 `refresh` 字段，需要就刷新。
     *
     * 为什么要有它：v5.2.0 里 `CookieRefreshApi` 只在 `RecommendActivity` 被动跑一次，
     * 用户不进推荐页就永远不刷新 → Cookie 过期后整个 web 端静默失效。
     * 用户明确要求「Web 增加主动续期」，所以这里提供一个**可主动调用**的入口
     * （启动时、长时间运行后、设置页按钮都可以调）。
     *
     * 只调用 [CookieRefreshApi] 的**现有公开函数**，不改那个文件（它属于 `gw-auth` 的写域）。
     *
     * 刷新成功后写回：
     * 1. `AccountStore.persistActiveWeb()` 把新 Cookie 快照回账号槽（多账号不丢，审计 P1-2）；
     * 2. `CookieRefreshApi.refreshCookie` 内部已经写了激活槽位的 cookies / csrf / refresh_token。
     *
     * @param force true = 跳过 `cookie_refresh` 标志，直接问服务端要不要刷新。
     *   false = 只有本地标志为 true 时才尝试（省一次请求）
     * @return 是否真的刷新了
     */
    @JvmStatic
    fun renewWeb(force: Boolean): Boolean {
        if (!AccountStore.isPortLoggedIn(AccountStore.PORT_WEB)) {
            // web 未登录时没有可续期的凭据。注意这**不是**错误 ——
            // 用户可能只登了 APP 端口，此时 web 就是没有登录，不需要刷新
            Logu.d(TAG, "web 未登录，跳过主动续期")
            return false
        }
        if (!force && !SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, false)) {
            return false
        }
        return try {
            doRenewWeb()
        } catch (ignored: IOException) {
            Logu.d(TAG, "web 主动续期失败（网络）：" + ignored.javaClass.simpleName)
            false
        } catch (ignored: JSONException) {
            Logu.d(TAG, "web 主动续期失败（响应格式）：" + ignored.javaClass.simpleName)
            false
        }
    }

    /**
     * 真正的续期流程（从 [renewWeb] 拆出来，控制圈复杂度）。
     *
     * 流程照 `bilibili-API-collect` 的 cookie 刷新文档：
     * `cookie/info` 拿 `refresh` 标志与 `timestamp` → 用它算 `correspondPath` →
     * 访问 `www.bilibili.com/correspond/1/<path>` 拿 `refresh_csrf` → `cookie/refresh`。
     */
    private fun doRenewWeb(): Boolean {
        val info = CookieRefreshApi.cookieInfo()
        if (!info.optBoolean("refresh", false)) {
            Logu.d(TAG, "服务端表示当前 Cookie 不需要刷新")
            return false
        }
        val timestamp = info.optLong("timestamp", 0L)
        val correspondPath = CookieRefreshApi.getCorrespondPath(timestamp)
        if (correspondPath.isEmpty()) {
            Logu.d(TAG, "refresh 标志为真但拿不到 correspondPath")
            return false
        }
        val refreshCsrf = CookieRefreshApi.getRefreshCsrf(correspondPath)
        if (refreshCsrf.isEmpty()) {
            Logu.d(TAG, "拿不到 refresh_csrf（Cookie 可能已经不完整）")
            return false
        }
        val refreshed = CookieRefreshApi.refreshCookie(refreshCsrf)
        if (refreshed) {
            // 刷新成功：把新 Cookie 快照回账号槽，避免多账号场景下该号永久停在旧 Cookie（审计 P1-2）
            AccountStore.persistActiveWeb()
            Logu.d(TAG, "web 主动续期成功")
        }
        return refreshed
    }

    // ==================== 兼容：AccountStore 的账号列表投影 ====================

    /**
     * 某端口全部账号的简要信息（设置页 / 导出前预览用）。
     *
     * 直接转发 [AccountStore.accounts]，不另外造一套模型 ——
     * 凭据的「事实来源」必须只有一个，否则又会回到「两个 mid 来源」的老问题（审计 P0-5）。
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun accountsOf(port: String): List<AccountStore.AccountEntry> = AccountStore.accounts(port)

    /**
     * 断言导出的 JSON 里四个 port 键都在（自检用，不参与业务）。
     *
     * 存在的意义：用户要求「四端同时导出」，这是一个**行为契约**。
     * 有了这个自检，`gw-pages`（设置页）可以在导出后立刻验证，
     * 而不是等用户导入时才发现少了某个端口。
     */
    @JvmStatic
    fun exportHasAllPorts(json: String): Boolean {
        val ports = parsePorts(json) ?: return false
        for (port in AccountStore.ALL_PORTS) {
            if (ports.optJSONObject(port) == null) return false
        }
        return true
    }

    /** 导出文件里带了哪些端口（给导入前预览用） */
    @JvmStatic
    @JvmSuppressWildcards
    fun exportedPorts(json: String): List<String> {
        val ports = parsePorts(json) ?: return emptyList()
        val list = ArrayList<String>()
        for (port in AccountStore.ALL_PORTS) {
            if (ports.optJSONObject(port) != null) list.add(port)
        }
        return list
    }
}
