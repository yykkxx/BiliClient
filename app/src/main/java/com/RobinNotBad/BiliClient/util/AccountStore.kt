package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.api.AppRcmdApi

/**
 * 账号存储（v5.1.5「账号管理」唯一事实来源）。
 *
 * # 模型
 *
 * 两个**独立的 API 端口作用域**：
 * - 推荐页端口（`account_mgmt_rcmd_port`，页签 1）；
 * - 其它页面端口（`account_mgmt_main_port`，页签 2）。
 *
 * 每个端口（web / android / hd / tv）下可以有**多个账号实例并存**：
 * - 端口账号列表 `account_mgmt_list_<port>`（有序、去重、逗号分隔的 mid）；
 * - 每账号一套凭据槽 `account_mgmt_<port>_<mid>_*`。
 *
 * # 激活槽位保持原样（这是关键设计）
 *
 * 「当前激活账号」的凭据仍然写进**既有的激活槽位**，所以
 * `AppRcmdApi.getAccessToken(identityId)` / `AccountManager` / `NetWorkUtil.webHeaders`
 * 的读取路径完全不用改：
 * - web：`cookies` / `mid` / `csrf` / `refresh_token` / `cookie_refresh`；
 * - APP 端口 identity `x`：`app_rcmd_<x>_access_token|refresh_token|mid|cookies`，
 *   展示缓存 `app_rcmd_<x>_uname|face`。
 *
 * 一次切换（[setActive]）= 先把激活槽位快照回当前账号的 `account_mgmt_*` 槽，
 * 再把目标账号的槽写进激活槽位。用户切到哪个账号，普通页面读到的就是哪个账号。
 *
 * # 约束
 *
 * - 本对象**不发起网络请求**；写偏好之外，web 激活时额外调 [NetWorkUtil.refreshHeaders]。
 * - 旧键只在 [ensureMigrated] 里**只读一次**（然后由迁移逻辑写出新键），此后全 App 不再读写。
 * - 不打印任何凭据；日志只有端口与 mid 计数。
 */
object AccountStore {

    /** 端口值（沿用既有命名，不造新词） */
    const val PORT_WEB = "web"
    const val PORT_ANDROID = "android"
    const val PORT_HD = "hd"
    const val PORT_TV = "tv"

    private const val TAG = "account-store"

    /** 固定顺序：web, android, hd, tv */
    private val PORTS = listOf(PORT_WEB, PORT_ANDROID, PORT_HD, PORT_TV)

    /** 全部端口（固定顺序：web → android → hd → tv），页面展示用 */
    @JvmField
    val ALL_PORTS: List<String> = PORTS

    /** 三个 APP 端口与 AppRcmdApi 身份的一一对应（web 没有 APP 身份） */
    private val APP_PORT_IDENTITIES = mapOf(
        PORT_ANDROID to AppRcmdApi.IDENTITY_PHONE,
        PORT_HD to AppRcmdApi.IDENTITY_HD,
        PORT_TV to AppRcmdApi.IDENTITY_TV
    )

    /**
     * 旧版本键名（**只用于 [ensureMigrated] 一次性读取**，不要在任何其它地方引用）。
     * 常量已经从 `SharedPreferencesUtil` 删除，这里保留字面量做迁移。
     */
    private const val LEGACY_APP_RCMD_ENABLED = "app_rcmd_enabled"
    private const val LEGACY_APP_RCMD_IDENTITY = "app_rcmd_identity"
    private const val LEGACY_API_MIGRATION_ENABLED = "api_migration_enabled"
    private const val LEGACY_LAB_ACCOUNT_REFACTOR = "lab_account_refactor"
    private const val LEGACY_ACCOUNT_REFACTOR_PORT = "account_refactor_port"

    /** 旧版 web 昵称/头像缓存（v5.1.4 首次登录时写入），迁移时并入 web 账号槽 */
    private const val LEGACY_WEB_UNAME = "account_web_uname"
    private const val LEGACY_WEB_FACE = "account_web_face"

    /** 账号槽的六个后缀 */
    private const val SUFFIX_COOKIES = "cookies"
    private const val SUFFIX_CSRF = "csrf"
    private const val SUFFIX_ACCESS_TOKEN = "access_token"
    private const val SUFFIX_REFRESH_TOKEN = "refresh_token"
    private const val SUFFIX_NAME = "name"
    private const val SUFFIX_FACE = "face"

    /** 端口下的账号（页面展示用） */
    class AccountEntry(
        @JvmField val port: String,
        @JvmField val mid: Long,
        @JvmField val name: String,
        @JvmField val face: String,
        @JvmField val active: Boolean
    )

    // ==================== 端口 ====================

    /** 全部端口（固定顺序：web → android → hd → tv） */
    @JvmStatic
    fun allPorts(): List<String> = PORTS

    /** 端口显示名 */
    @JvmStatic
    fun portDisplayName(port: String): String {
        return when (normalizePort(port)) {
            PORT_ANDROID -> "Android手机"
            PORT_HD -> "HD"
            PORT_TV -> "TV"
            else -> "Web"
        }
    }

    /** 是否是合法端口值 */
    @JvmStatic
    fun isValidPort(port: String?): Boolean {
        return port != null && PORTS.contains(port.trim().lowercase())
    }

    /** 归一化端口：非法值一律按 Web（与历史脏数据口径一致） */
    @JvmStatic
    fun normalizePort(port: String?): String {
        val raw = (port ?: "").trim().lowercase()
        return if (PORTS.contains(raw)) raw else PORT_WEB
    }

    /** 端口 → AppRcmdApi 身份；web 端口返回 null */
    @JvmStatic
    fun identityOf(port: String): AppRcmdApi.RcmdIdentity? {
        val identityId = identityIdOf(port)
        if (identityId.isEmpty()) return null
        return AppRcmdApi.getIdentityById(identityId)
    }

    /** 端口 → identityId（web 返回 ""） */
    @JvmStatic
    fun identityIdOf(port: String): String {
        return APP_PORT_IDENTITIES[normalizePort(port)] ?: ""
    }

    /** identityId → 端口（未知/空 id 按 web） */
    @JvmStatic
    fun portOfIdentity(identityId: String): String {
        val raw = identityId.trim().lowercase()
        for ((port, id) in APP_PORT_IDENTITIES) {
            if (id == raw) return port
        }
        return PORT_WEB
    }

    // ==================== 两个作用域（页签 1 / 页签 2） ====================

    /** 推荐页 API 端口 */
    @JvmStatic
    fun rcmdPort(): String {
        ensureMigrated()
        return normalizePort(SharedPreferencesUtil.getString(SharedPreferencesUtil.ACCOUNT_MGMT_RCMD_PORT, PORT_WEB))
    }

    @JvmStatic
    fun setRcmdPort(port: String) {
        if (!isValidPort(port)) return
        SharedPreferencesUtil.putString(SharedPreferencesUtil.ACCOUNT_MGMT_RCMD_PORT, normalizePort(port))
    }

    /** 其它页面 API 端口 */
    @JvmStatic
    fun mainPort(): String {
        ensureMigrated()
        return normalizePort(SharedPreferencesUtil.getString(SharedPreferencesUtil.ACCOUNT_MGMT_MAIN_PORT, PORT_WEB))
    }

    @JvmStatic
    fun setMainPort(port: String) {
        if (!isValidPort(port)) return
        SharedPreferencesUtil.putString(SharedPreferencesUtil.ACCOUNT_MGMT_MAIN_PORT, normalizePort(port))
    }

    /** 推荐页端口对应的 APP 身份；web 端口返回 null */
    @JvmStatic
    fun rcmdIdentity(): AppRcmdApi.RcmdIdentity? = identityOf(rcmdPort())

    /** 其它页面端口对应的 APP 身份；web 端口返回 null */
    @JvmStatic
    fun mainIdentity(): AppRcmdApi.RcmdIdentity? = identityOf(mainPort())

    // ==================== 账号列表 ====================

    /** 该端口下的全部账号（按列表顺序） */
    @JvmStatic
    @JvmSuppressWildcards
    fun accounts(port: String): List<AccountEntry> {
        ensureMigrated()
        val portValue = normalizePort(port)
        val active = activeMidValue(portValue)
        val result = ArrayList<AccountEntry>()
        for (mid in midsOf(portValue)) {
            result.add(AccountEntry(portValue, mid, accountName(portValue, mid), accountFace(portValue, mid), mid == active))
        }
        return result
    }

    /** 该端口的账号数量 */
    @JvmStatic
    fun accountCount(port: String): Int {
        ensureMigrated()
        return midsOf(normalizePort(port)).size
    }

    /** 该端口当前激活账号 mid；0 = 该端口没有激活账号 */
    @JvmStatic
    fun activeMid(port: String): Long {
        ensureMigrated()
        return activeMidValue(normalizePort(port))
    }

    /** 该端口是否已有激活账号 */
    @JvmStatic
    fun isPortLoggedIn(port: String): Boolean = activeMid(port) > 0L

    /** 任意端口是否有账号 */
    @JvmStatic
    fun hasAnyAccount(): Boolean {
        ensureMigrated()
        for (port in PORTS) {
            if (activeMidValue(port) > 0L) return true
        }
        return false
    }

    /**
     * 切换激活账号。
     *
     * @return false 表示该端口下不存在这个 mid（或参数非法），此时不做任何写入
     */
    @JvmStatic
    fun setActive(port: String, mid: Long): Boolean {
        ensureMigrated()
        if (!isValidPort(port) || mid <= 0L) return false
        val portValue = normalizePort(port)
        if (!midsOf(portValue).contains(mid)) return false
        synchronized(this) {
            snapshotActiveSlots(portValue)
            writeActiveSlots(portValue, mid)
        }
        if (portValue == PORT_WEB) NetWorkUtil.refreshHeaders()
        return true
    }

    /** 账号相关的「我是谁」：优先 mainPort 激活账号，其次 web 激活，再扫其它端口 */
    @JvmStatic
    fun primaryMid(): Long = primaryPortAndMid().second

    /** 主账号所属端口；"" = 无账号 */
    @JvmStatic
    fun primaryPort(): String = primaryPortAndMid().first

    @JvmStatic
    fun primaryName(): String {
        val found = primaryPortAndMid()
        if (found.first.isEmpty() || found.second <= 0L) return ""
        val name = accountName(found.first, found.second)
        return name.ifEmpty { "uid " + found.second }
    }

    @JvmStatic
    fun primaryFace(): String {
        val found = primaryPortAndMid()
        if (found.first.isEmpty() || found.second <= 0L) return ""
        return accountFace(found.first, found.second)
    }

    /** 全部端口全部账号的 mid（去重，web 优先） */
    @JvmStatic
    @JvmSuppressWildcards
    fun allMids(): List<Long> {
        ensureMigrated()
        val list = ArrayList<Long>()
        for (port in PORTS) {
            for (mid in midsOf(port)) {
                if (!list.contains(mid)) list.add(mid)
            }
        }
        return list
    }

    /** 该端口激活账号的 access_token（web 用 Cookie，返回 ""） */
    @JvmStatic
    fun activeAccessToken(port: String): String {
        ensureMigrated()
        val portValue = normalizePort(port)
        if (portValue == PORT_WEB) return ""
        val identityId = identityIdOf(portValue)
        if (identityId.isEmpty()) return ""
        val token = SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_ACCESS_TOKEN), "") ?: ""
        if (token.isNotEmpty()) return token
        val mid = activeMidValue(portValue)
        if (mid <= 0L) return ""
        return SharedPreferencesUtil.getString(accountKey(portValue, mid, SUFFIX_ACCESS_TOKEN), "") ?: ""
    }

    // ==================== 登录 / 退出 ====================

    /** web 扫码登录成功：写入该 mid 的账号槽并置为 web 激活账号 */
    @JvmStatic
    fun loginWeb(mid: Long, cookies: String, csrf: String, refreshToken: String, name: String?, face: String?) {
        ensureMigrated()
        if (mid <= 0L || cookies.isEmpty()) return
        synchronized(this) {
            // 先把「正在用的那个 web 账号」的最新凭据存回它自己的槽位，
            // 否则同一个端口登录第二个号时，第一个号刷新过的 Cookie 会丢。
            snapshotActiveSlots(PORT_WEB)
            addToPortList(PORT_WEB, mid)
            writeAccountSlot(PORT_WEB, mid, cookies, csrf, "", refreshToken,
                    name ?: accountName(PORT_WEB, mid), face ?: accountFace(PORT_WEB, mid))
            writeActiveSlots(PORT_WEB, mid)
        }
        NetWorkUtil.refreshHeaders()
    }

    /**
     * APP 端口扫码登录成功：写入该 mid 的账号槽并置为该端口的激活账号。
     * @param port 必须是 web 以外的合法端口（web 扫码走 LoginActivity / loginWeb）
     */
    @JvmStatic
    fun loginApp(
        port: String,
        mid: Long,
        accessToken: String,
        refreshToken: String,
        cookies: String,
        name: String?,
        face: String?
    ) {
        ensureMigrated()
        if (!isValidPort(port) || mid <= 0L || accessToken.isEmpty()) return
        val portValue = normalizePort(port)
        if (portValue == PORT_WEB) return
        synchronized(this) {
            // 同上：先快照该端口当前激活账号，再切到新号（同端口多账号互不覆盖）
            snapshotActiveSlots(portValue)
            addToPortList(portValue, mid)
            writeAccountSlot(portValue, mid, cookies, "", accessToken, refreshToken,
                    name ?: accountName(portValue, mid), face ?: accountFace(portValue, mid))
            writeActiveSlots(portValue, mid)
        }
    }

    /** 更新账号的昵称/头像缓存（拉取到资料后调用；空值不覆盖） */
    @JvmStatic
    fun updateProfile(port: String, mid: Long, name: String?, face: String?) {
        ensureMigrated()
        if (!isValidPort(port) || mid <= 0L) return
        val portValue = normalizePort(port)
        if (!midsOf(portValue).contains(mid)) return
        synchronized(this) {
            if (!name.isNullOrEmpty()) SharedPreferencesUtil.putString(accountKey(portValue, mid, SUFFIX_NAME), name)
            if (!face.isNullOrEmpty()) SharedPreferencesUtil.putString(accountKey(portValue, mid, SUFFIX_FACE), face)
            if (activeMidValue(portValue) == mid) writeActiveDisplayCache(portValue, mid)
        }
    }

    /**
     * 清掉某账号的展示缓存（昵称/头像）。退出 web 登录时用：账号凭据已随槽位清掉，
     * 纯展示信息也要一起清，避免下次登录前「我的」页还显示上一个号的昵称。
     */
    @JvmStatic
    fun clearProfile(port: String, mid: Long) {
        ensureMigrated()
        if (!isValidPort(port) || mid <= 0L) return
        val portValue = normalizePort(port)
        synchronized(this) {
            SharedPreferencesUtil.removeValue(accountKey(portValue, mid, SUFFIX_NAME))
            SharedPreferencesUtil.removeValue(accountKey(portValue, mid, SUFFIX_FACE))
            if (activeMidValue(portValue) == mid) writeActiveDisplayCache(portValue, mid)
        }
    }

    /** 退出指定账号；若它是激活账号则自动切到该端口的下一个账号 */
    @JvmStatic
    fun logout(port: String, mid: Long) {
        ensureMigrated()
        if (!isValidPort(port) || mid <= 0L) return
        val portValue = normalizePort(port)
        synchronized(this) {
            val mids = midsOf(portValue).toMutableList()
            if (!mids.remove(mid)) return
            clearAccountSlot(portValue, mid)
            putPortList(portValue, mids)
            if (activeMidValue(portValue) == mid) {
                if (mids.isEmpty()) clearActiveSlots(portValue) else writeActiveSlots(portValue, mids.first())
            }
        }
        if (portValue == PORT_WEB) NetWorkUtil.refreshHeaders()
    }

    /** 退出该端口当前激活账号 */
    @JvmStatic
    fun logoutActive(port: String) {
        val mid = activeMid(port)
        if (mid > 0L) logout(port, mid)
    }

    /** 退出全部账号（各端口清空账号槽与激活槽位） */
    @JvmStatic
    fun logoutAll() {
        ensureMigrated()
        synchronized(this) {
            for (port in PORTS) {
                for (mid in midsOf(port)) clearAccountSlot(port, mid)
                putPortList(port, emptyList())
                clearActiveSlots(port)
            }
        }
        NetWorkUtil.refreshHeaders()
    }

    /**
     * 把当前激活的 web 会话快照回它的账号槽（多账号并存用）。
     *
     * web 扫码页打开时调用：老版本 `cookie_refresh` 会把刷新后的 Cookie 直接写进激活槽位，
     * 这里在切号/复用之前先把最新值存回账号槽，避免刷新结果丢失或串到别的账号。
     * 没有激活 web 账号（或 Cookie 为空）时什么都不做。
     */
    @JvmStatic
    fun persistActiveWeb() {
        ensureMigrated()
        synchronized(this) {
            snapshotActiveSlots(PORT_WEB)
        }
    }

    // ==================== 迁移（只跑一次） ====================

    @Volatile
    private var migrated = false

    /**
     * 幂等迁移：旧单账号凭据 → 该端口第一个账号并置为激活；旧开关只读一次，
     * 用来给两个端口键一个合理初值。旧「实验室账号体系」两键直接作废删除。
     */
    @JvmStatic
    fun ensureMigrated() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            // 偏好还没初始化（进程入口早于 BiliTerminal.onCreate）时不要动任何键，留待下次
            if (SharedPreferencesUtil.sharedPreferences == null) return
            try {
                migrateLocked()
            } catch (e: Exception) {
                Logu.d(TAG, "账号迁移失败：" + e.javaClass.simpleName)
            }
            migrated = true
        }
    }

    private fun migrateLocked() {
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.ACCOUNT_MGMT_MIGRATED, false)) {
            removeLegacyLabKeys()
            return
        }
        migrateLegacyWeb()
        for (port in APP_PORT_IDENTITIES.keys) migrateLegacyApp(port)
        migratePortScopes()
        removeLegacyLabKeys()
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.ACCOUNT_MGMT_MIGRATED, true)
    }

    /** 旧 web 单账号：`mid>0` 且 `cookies` 非空 → 迁成 web 端口第一个账号 */
    private fun migrateLegacyWeb() {
        val mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
        if (mid <= 0L) return
        val cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        if (cookies.isEmpty()) return
        val csrf = SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "") ?: ""
        val refreshToken = SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "") ?: ""
        val name = SharedPreferencesUtil.getString(LEGACY_WEB_UNAME, "") ?: ""
        val face = SharedPreferencesUtil.getString(LEGACY_WEB_FACE, "") ?: ""
        writeAccountSlot(PORT_WEB, mid, cookies, csrf, "", refreshToken, name, face)
        addToPortList(PORT_WEB, mid)
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.ACCOUNT_MGMT_WEB_ACTIVE, mid)
    }

    /** 旧 APP 单账号：`app_rcmd_<x>_access_token` 非空 → 迁成该端口第一个账号 */
    private fun migrateLegacyApp(port: String) {
        val identityId = identityIdOf(port)
        if (identityId.isEmpty()) return
        val accessToken = SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_ACCESS_TOKEN), "") ?: ""
        if (accessToken.isEmpty()) return
        val mid = SharedPreferencesUtil.getLong(slotKey(identityId, "mid"), 0L)
        if (mid <= 0L) return
        val cookies = SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_COOKIES), "") ?: ""
        val refreshToken = SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_REFRESH_TOKEN), "") ?: ""
        val name = SharedPreferencesUtil.getString(slotKey(identityId, "uname"), "") ?: ""
        val face = SharedPreferencesUtil.getString(slotKey(identityId, "face"), "") ?: ""
        writeAccountSlot(port, mid, cookies, "", accessToken, refreshToken, name, face)
        addToPortList(port, mid)
        SharedPreferencesUtil.putLong(appActiveKey(identityId), mid)
    }

    /**
     * 旧开关 → 两个端口键的初值（**只读不写旧键**）：
     * - `app_rcmd_enabled`(旧) 为 true 且旧身份可解析 → 推荐页端口 = 对应端口
     *   （旧身份键缺失时旧代码默认 HD，这里按 HD 处理，保持行为不变）；
     * - `api_migration_enabled`(旧) 为 true 且旧身份键**有值** → 其它页面端口 = 对应端口；
     * - 否则两者 web。
     */
    private fun migratePortScopes() {
        val legacyIdentity = (SharedPreferencesUtil.getString(LEGACY_APP_RCMD_IDENTITY, "") ?: "").trim().lowercase()
        val legacyPort = if (legacyIdentity.isEmpty()) PORT_WEB else portOfIdentity(legacyIdentity)
        val rcmdEnabled = SharedPreferencesUtil.getBoolean(LEGACY_APP_RCMD_ENABLED, false)
        val rcmdPort = if (rcmdEnabled) {
            if (legacyIdentity.isEmpty()) PORT_HD else legacyPort
        } else {
            PORT_WEB
        }
        val migrationEnabled = SharedPreferencesUtil.getBoolean(LEGACY_API_MIGRATION_ENABLED, false)
        val mainPort = if (migrationEnabled && legacyIdentity.isNotEmpty()) legacyPort else PORT_WEB
        SharedPreferencesUtil.putString(SharedPreferencesUtil.ACCOUNT_MGMT_RCMD_PORT, rcmdPort)
        SharedPreferencesUtil.putString(SharedPreferencesUtil.ACCOUNT_MGMT_MAIN_PORT, mainPort)
    }

    /** 旧「实验室账号体系」两键：不迁移、不读取，直接清掉保持干净 */
    private fun removeLegacyLabKeys() {
        SharedPreferencesUtil.removeValue(LEGACY_LAB_ACCOUNT_REFACTOR)
        SharedPreferencesUtil.removeValue(LEGACY_ACCOUNT_REFACTOR_PORT)
    }

    // ==================== 内部：账号列表读写 ====================

    private fun listKey(port: String): String = SharedPreferencesUtil.ACCOUNT_MGMT_LIST_PREFIX + port

    /** 该端口的 mid 列表（有序、去重、只保留 >0） */
    private fun midsOf(port: String): List<Long> {
        val raw = SharedPreferencesUtil.getString(listKey(port), "") ?: ""
        if (raw.isEmpty()) return emptyList()
        val result = ArrayList<Long>()
        for (part in raw.split(",")) {
            val mid = part.trim().toLongOrNull() ?: continue
            if (mid > 0L && !result.contains(mid)) result.add(mid)
        }
        return result
    }

    private fun putPortList(port: String, mids: List<Long>) {
        SharedPreferencesUtil.putString(listKey(port), mids.joinToString(","))
    }

    /** 追加到端口账号列表末尾（已存在则不重复） */
    private fun addToPortList(port: String, mid: Long) {
        val mids = midsOf(port).toMutableList()
        if (!mids.contains(mid)) mids.add(mid)
        putPortList(port, mids)
    }

    // ==================== 内部：账号槽 / 激活槽 ====================

    private fun accountKey(port: String, mid: Long, suffix: String): String {
        return SharedPreferencesUtil.ACCOUNT_MGMT_ACCOUNT_PREFIX + port + "_" + mid + "_" + suffix
    }

    /** 账号槽位键：app_rcmd_{identity}_{suffix}（与 AppRcmdApi 命名保持一致） */
    private fun slotKey(identityId: String, suffix: String): String {
        return "app_rcmd_" + identityId + "_" + suffix
    }

    private fun appActiveKey(identityId: String): String {
        return SharedPreferencesUtil.ACCOUNT_MGMT_APP_ACTIVE_PREFIX + identityId
    }

    private fun accountName(port: String, mid: Long): String {
        return SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_NAME), "") ?: ""
    }

    private fun accountFace(port: String, mid: Long): String {
        return SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_FACE), "") ?: ""
    }

    private fun writeAccountSlot(
        port: String,
        mid: Long,
        cookies: String,
        csrf: String,
        accessToken: String,
        refreshToken: String,
        name: String,
        face: String
    ) {
        if (cookies.isNotEmpty()) SharedPreferencesUtil.putString(accountKey(port, mid, SUFFIX_COOKIES), cookies)
        if (csrf.isNotEmpty()) SharedPreferencesUtil.putString(accountKey(port, mid, SUFFIX_CSRF), csrf)
        if (accessToken.isNotEmpty()) SharedPreferencesUtil.putString(accountKey(port, mid, SUFFIX_ACCESS_TOKEN), accessToken)
        if (refreshToken.isNotEmpty()) SharedPreferencesUtil.putString(accountKey(port, mid, SUFFIX_REFRESH_TOKEN), refreshToken)
        if (name.isNotEmpty()) SharedPreferencesUtil.putString(accountKey(port, mid, SUFFIX_NAME), name)
        if (face.isNotEmpty()) SharedPreferencesUtil.putString(accountKey(port, mid, SUFFIX_FACE), face)
    }

    private fun clearAccountSlot(port: String, mid: Long) {
        for (suffix in ACCOUNT_SUFFIXES) {
            SharedPreferencesUtil.removeValue(accountKey(port, mid, suffix))
        }
    }

    private val ACCOUNT_SUFFIXES = listOf(
        SUFFIX_COOKIES, SUFFIX_CSRF, SUFFIX_ACCESS_TOKEN, SUFFIX_REFRESH_TOKEN, SUFFIX_NAME, SUFFIX_FACE
    )

    /** 当前激活账号 mid（web 用 `mid` 键，APP 端口用 `app_rcmd_<x>_mid`） */
    private fun activeMidValue(port: String): Long {
        if (port == PORT_WEB) return webActiveMid()
        val identityId = identityIdOf(port)
        if (identityId.isEmpty()) return 0L
        val slotMid = SharedPreferencesUtil.getLong(slotKey(identityId, "mid"), 0L)
        if (slotMid > 0L) return slotMid
        // 激活槽位被外部清空（如历史页面直接清键）时，用账号管理镜像键兜底，但必须有凭据才算
        val mirror = SharedPreferencesUtil.getLong(appActiveKey(identityId), 0L)
        if (mirror <= 0L) return 0L
        val token = SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_ACCESS_TOKEN), "") ?: ""
        return if (token.isNotEmpty()) mirror else 0L
    }

    private fun webActiveMid(): Long {
        val slotMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
        if (slotMid > 0L) return slotMid
        val mirror = SharedPreferencesUtil.getLong(SharedPreferencesUtil.ACCOUNT_MGMT_WEB_ACTIVE, 0L)
        if (mirror <= 0L) return 0L
        val cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        return if (cookies.isNotEmpty()) mirror else 0L
    }

    /**
     * 把激活槽位里的最新凭据快照回该管道的当前账号槽。
     * 没有激活账号/凭据为空时什么都不做（避免用空值覆盖已有账号槽）。
     */
    private fun snapshotActiveSlots(port: String) {
        val active = activeMidValue(port)
        if (active <= 0L) return
        if (port == PORT_WEB) {
            snapshotWebSlot(active)
        } else {
            snapshotAppSlot(port, active)
        }
    }

    /** web 激活账号：Cookie 非空时快照回账号槽（空值绝不覆盖已有账号槽） */
    private fun snapshotWebSlot(active: Long) {
        val cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        if (cookies.isEmpty()) return
        addToPortList(PORT_WEB, active)
        writeAccountSlot(
            PORT_WEB, active, cookies,
            SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "") ?: "",
            "", SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "") ?: "",
            accountName(PORT_WEB, active), accountFace(PORT_WEB, active)
        )
    }

    /** APP 端口激活账号：token 非空时快照回账号槽，并把激活槽位的昵称/头像一并回写 */
    private fun snapshotAppSlot(port: String, active: Long) {
        val identityId = identityIdOf(port)
        val token = SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_ACCESS_TOKEN), "") ?: ""
        if (token.isEmpty()) return
        addToPortList(port, active)
        writeAccountSlot(
            port, active,
            SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_COOKIES), "") ?: "",
            "", token,
            SharedPreferencesUtil.getString(slotKey(identityId, SUFFIX_REFRESH_TOKEN), "") ?: "",
            accountName(port, active), accountFace(port, active)
        )
        // 激活槽位里的展示缓存也回写账号槽，避免切号后昵称丢失
        val uname = SharedPreferencesUtil.getString(slotKey(identityId, "uname"), "") ?: ""
        val face = SharedPreferencesUtil.getString(slotKey(identityId, "face"), "") ?: ""
        if (uname.isNotEmpty()) writeAccountSlot(port, active, "", "", "", "", uname, "")
        if (face.isNotEmpty()) writeAccountSlot(port, active, "", "", "", "", "", face)
    }

    /** 把目标账号的槽写进激活槽位（web：cookies/mid/csrf/refresh_token/cookie_refresh） */
    private fun writeActiveSlots(port: String, mid: Long) {
        if (port == PORT_WEB) {
            val cookies = SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_COOKIES), "") ?: ""
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies)
            SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, mid)
            SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf,
                    SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_CSRF), "") ?: "")
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token,
                    SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_REFRESH_TOKEN), "") ?: "")
            SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.cookie_refresh, cookies.isNotEmpty())
            SharedPreferencesUtil.putLong(SharedPreferencesUtil.ACCOUNT_MGMT_WEB_ACTIVE, mid)
            return
        }
        val identityId = identityIdOf(port)
        if (identityId.isEmpty()) return
        SharedPreferencesUtil.putString(slotKey(identityId, SUFFIX_ACCESS_TOKEN),
                SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_ACCESS_TOKEN), "") ?: "")
        SharedPreferencesUtil.putString(slotKey(identityId, SUFFIX_REFRESH_TOKEN),
                SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_REFRESH_TOKEN), "") ?: "")
        SharedPreferencesUtil.putLong(slotKey(identityId, "mid"), mid)
        SharedPreferencesUtil.putString(slotKey(identityId, SUFFIX_COOKIES),
                SharedPreferencesUtil.getString(accountKey(port, mid, SUFFIX_COOKIES), "") ?: "")
        writeActiveDisplayCache(port, mid)
        SharedPreferencesUtil.putLong(appActiveKey(identityId), mid)
    }

    /** 激活槽位的展示缓存（app_rcmd_<x>_uname / _face） */
    private fun writeActiveDisplayCache(port: String, mid: Long) {
        val identityId = identityIdOf(port)
        if (identityId.isEmpty()) return
        SharedPreferencesUtil.putString(slotKey(identityId, "uname"), accountName(port, mid))
        SharedPreferencesUtil.putString(slotKey(identityId, "face"), accountFace(port, mid))
    }

    /** 清空某端口的激活槽位（该端口没有账号时） */
    private fun clearActiveSlots(port: String) {
        if (port == PORT_WEB) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, "")
            SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, 0L)
            SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, "")
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, "")
            SharedPreferencesUtil.removeValue(SharedPreferencesUtil.cookie_refresh)
            SharedPreferencesUtil.putLong(SharedPreferencesUtil.ACCOUNT_MGMT_WEB_ACTIVE, 0L)
            return
        }
        val identityId = identityIdOf(port)
        if (identityId.isEmpty()) return
        for (suffix in ACTIVE_APP_SUFFIXES) {
            SharedPreferencesUtil.removeValue(slotKey(identityId, suffix))
        }
        SharedPreferencesUtil.putLong(appActiveKey(identityId), 0L)
    }

    private val ACTIVE_APP_SUFFIXES = listOf(
        SUFFIX_ACCESS_TOKEN, SUFFIX_REFRESH_TOKEN, "mid", SUFFIX_COOKIES, "uname", "face"
    )

    // ==================== 内部：主账号 ====================

    /** 主账号 (port, mid)；没有账号时 ("", 0) */
    private fun primaryPortAndMid(): Pair<String, Long> {
        ensureMigrated()
        val main = mainPort()
        val mainMid = activeMidValue(main)
        if (mainMid > 0L) return Pair(main, mainMid)
        val webMid = activeMidValue(PORT_WEB)
        if (webMid > 0L) return Pair(PORT_WEB, webMid)
        for (port in PORTS) {
            if (port == PORT_WEB) continue
            val mid = activeMidValue(port)
            if (mid > 0L) return Pair(port, mid)
        }
        return Pair("", 0L)
    }
}
