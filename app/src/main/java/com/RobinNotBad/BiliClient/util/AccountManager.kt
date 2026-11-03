package com.RobinNotBad.BiliClient.util

/**
 * 账号状态与页面逻辑之间的唯一桥梁（v5.1.4 账号解耦，v5.1.5 收敛到 AccountStore）。
 *
 * # 为什么要这个类
 *
 * v5.1.3 及以前，几乎每个页面都用
 * `SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L`
 * 判断「有没有登录」，而这个 mid 只表示 **web Cookie 登录**。于是出现用户的真实抱怨：
 *
 * - 退出 Web 登录 → 菜单里「我的」「动态」「消息」直接消失；
 * - 详情页「加入稍后再看」按钮被 `View.GONE` 藏掉；
 * - 点赞/投币/收藏先弹「还没有登录喵~」，明明 HD/TV/手机端已经登录；
 * - 启动页 `LauncherActivity` 直接把用户扔进登录页，进不了 App。
 *
 * 根因不是「登录没做对」，而是把「**某个通道的登录状态**」当成了「**用户有没有账号**」。
 * 这一层把两件事彻底分开：
 *
 * - **账号是谁** → [accountMid] / [isLoggedIn] / [displayName] / [avatarUrl]
 *   （四个端口各自的激活账号都算，按「其它页面 API 端口 → web → 其它」的顺序挑主账号）
 * - **页面怎么显示** → 页面只问 [isLoggedIn]，不再直接读 web 的 `mid` 键
 *
 * # v5.1.5 的账号模型
 *
 * 账号存储与切换全部收敛到 [AccountStore]（那里才有端口 / 多账号列表 / 激活槽），
 * 本类只做**只读投影**：页面问「有没有账号 / 我是谁 / 怎么提示」时用这里，
 * 具体存哪个账号、切哪个账号一律走 [AccountStore]，页面不直接碰凭据键。
 *
 * # 兼容性红线（延续 v5.1.1 的约定）
 *
 * - 本类**不写任何凭据**（展示缓存除外，见 [cacheWebProfile]）；
 * - 绝不把 web Cookie 写进 `app_rcmd_*` 槽位，也绝不反向转移；
 * - 所有方法都可以在任何线程调用（只读 SharedPreferences + 纯计算）。
 */
object AccountManager {

    // ==================== 基础事实 ====================

    /** web 是否登录（读既有 `mid` 键，语义与历史版本完全一致） */
    @JvmStatic
    fun isWebLoggedIn(): Boolean {
        return SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) > 0L
    }

    /** web 账号的 mid（未登录返回 0） */
    @JvmStatic
    fun webMid(): Long {
        val mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
        return if (mid > 0L) mid else 0L
    }

    /**
     * 推荐页当前端口的激活账号 mid（未登录 / 端口为 Web 时返回 0）。
     */
    @JvmStatic
    fun appRcmdMid(): Long {
        val port = AccountStore.rcmdPort()
        if (port == AccountStore.PORT_WEB) return 0L
        return AccountStore.activeMid(port)
    }

    /**
     * 全部端口全部账号的 mid 列表（web 在最前，其后是三个 APP 端口）。
     * 用于「评论里判断哪条是我发的」这类**需要一个具体 uid** 的场景：
     * 只要这条评论/动态的作者 mid 命中列表里任何一个，就是我。
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun selfMids(): MutableList<Long> {
        return ArrayList(AccountStore.allMids())
    }

    /** 判断某个 uid 是不是「我」（任一端口任一账号命中即算） */
    @JvmStatic
    fun isSelf(uid: Long): Boolean {
        if (uid <= 0L) return false
        return selfMids().contains(uid)
    }

    /**
     * 主账号 mid。
     *
     * **这是页面该用的"我是谁"**：优先「其它页面 API」端口上的激活账号，
     * 其次 web 激活账号，再扫其它端口。返回 0 表示真的一个号都没登录。
     */
    @JvmStatic
    fun accountMid(): Long {
        return AccountStore.primaryMid()
    }

    /** 主账号来源：端口值（web / android / hd / tv），没有账号时返回空串 */
    @JvmStatic
    fun accountSource(): String {
        return AccountStore.primaryPort()
    }

    // ==================== 页面口径 ====================

    /**
     * **页面判断"用户有没有账号"的唯一入口**（替代散落各处的 `mid == 0L` 判断）。
     *
     * v5.1.5：四个端口（Web / Android手机 / HD / TV）各自的激活账号都算「有账号」。
     * 退出 Web 登录但某个端口登录着时仍返回 true，页面不会再把「我的」「稍后再看」
     * 「点赞」藏起来——账号与页面彻底解耦。
     */
    @JvmStatic
    fun isLoggedIn(): Boolean {
        return AccountStore.hasAnyAccount()
    }

    /**
     * 是否能执行「需要登录的写操作」（点赞/投币/收藏/评论/稍后再看…）。
     *
     * 目前与 [isLoggedIn] 相同：只要任意端口有账号，就允许用户尝试，
     * 请求层（[ApiRouter]）会按「账号管理 → 其它页面 API」选的端口发出去，
     * 真失败再按业务码提示。
     * 单独留一个方法名，是为了将来如果某些操作需要"特定端口"时有落点。
     */
    @JvmStatic
    fun canWrite(): Boolean {
        return isLoggedIn()
    }

    // ==================== 展示信息（昵称 / 头像） ====================

    /**
     * 主账号昵称（「我的」页面头部用）。
     *
     * 昵称来自该账号登录/拉资料时写入的展示缓存（[AccountStore.primaryName]），
     * 没有昵称时返回 `uid 123` 占位，**不返回空串**（避免页面出现一个空白名字）。
     */
    @JvmStatic
    fun displayName(): String {
        val name = AccountStore.primaryName()
        return name.ifEmpty { "已登录" }
    }

    /** 主账号头像 url（没有就返回空串，由页面用自己的占位图） */
    @JvmStatic
    fun avatarUrl(): String {
        return AccountStore.primaryFace()
    }

    /**
     * 记录主账号（当前激活账号）的昵称/头像。
     *
     * v5.1.5：展示缓存按「端口 + mid」存（[AccountStore.updateProfile]），
     * 同一个端口登了多个号时各自一份，切号不会串名字。
     */
    @JvmStatic
    fun cacheWebProfile(name: String?, face: String?) {
        val port = AccountStore.primaryPort()
        val mid = AccountManager.accountMid()
        if (port.isEmpty() || mid <= 0L) return
        AccountStore.updateProfile(port, mid, name, face)
    }

    /** 记录某个端口当前激活账号的昵称/头像（扫码登录成功时调用） */
    @JvmStatic
    fun cacheIdentityProfile(identityId: String, name: String?, face: String?) {
        val port = AccountStore.portOfIdentity(identityId)
        val mid = AccountStore.activeMid(port)
        if (port.isEmpty() || mid <= 0L) return
        AccountStore.updateProfile(port, mid, name, face)
    }

    /** 清掉 web 侧的展示缓存（退出 web 登录时调用；不碰其它端口与其他账号） */
    @JvmStatic
    fun clearWebProfile() {
        val mid = AccountStore.activeMid(AccountStore.PORT_WEB)
        if (mid > 0L) AccountStore.clearProfile(AccountStore.PORT_WEB, mid)
    }

    // ==================== 文案（给页面统一口径，避免各处自己拼） ====================

    /**
     * 统一的操作拦截提示。页面用 [isLoggedIn] 判断后，需要告诉用户"去登录"时用这个，
     * 保证全 App 文案一致。
     *
     * v5.1.5 起只有一种口径：任一端口登录都能用，且可以完全不登 Web——
     * 具体走哪个端口由用户在「账号管理」里决定，文案直接把用户送到那个页面。
     */
    @JvmStatic
    fun loginHint(): String {
        return LOGIN_HINT
    }

    /** 统一的"去登录"提示文案（[loginHint] 返回它，便于其它页面复用同一句） */
    private const val LOGIN_HINT = "还没有登录，请先在「设置 → 账号管理」里选择端口并登录账号"

    /**
     * 当前账号状况的一句话描述（账号管理页展示用）。
     * 形如「Web 已登录（uid 123）＋ TV 已登录」。
     */
    @JvmStatic
    fun describe(): String {
        val sb = StringBuilder()
        for (port in AccountStore.allPorts()) {
            if (sb.isNotEmpty()) sb.append("　")
            sb.append(AccountStore.portDisplayName(port)).append("：")
            val count = AccountStore.accountCount(port)
            val active = AccountStore.activeMid(port)
            if (count <= 0) {
                sb.append("未登录")
            } else {
                sb.append(count).append(" 个账号")
                if (active > 0L) sb.append("（当前 uid ").append(active).append("）")
            }
        }
        return sb.toString()
    }

}
