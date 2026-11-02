package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.api.migration.ApiRouter

/**
 * 账号状态与页面逻辑之间的唯一桥梁（v5.1.4 账号解耦）。
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
 *   （web 与三端任一登录即算有账号，按优先级挑一个"主账号"）
 * - **页面怎么显示** → 页面只问 [isLoggedIn]，不再直接读 web 的 `mid` 键
 *
 * # 三级迁移模式下各自的表现
 *
 * | 级别 | 开关组合 | 账号来源 |
 * | --- | --- | --- |
 * | 一级 | 推荐 API 关 + 全 API 关 | 只有 web（与 5.1.0 完全一致的纯 web 行为） |
 * | 二级 | 推荐 API 开 + 全 API 关 | web 为主；web 没登录时用「移动端推荐逻辑」的当前身份 |
 * | 三级 | 全 API 开 | web 与所选端口任一登录即可，页面全部照常工作 |
 *
 * # 兼容性红线（延续 v5.1.1 的约定）
 *
 * - 本类**只读**既有键，不新增、不改写任何凭据存储；
 * - 绝不把 web Cookie 写进 `app_rcmd_*` 槽位，也绝不反向转移；
 * - 所有方法都可以在任何线程调用（只读 SharedPreferences + 纯计算）。
 */
object AccountManager {

    /**
     * 账号来源的优先级。
     *
     * 为什么 web 排第一：用户装了 App 又登录了 web 时，web 账号才是"我平时用 B 站的那个号"，
     * 而且 web 登录能拿到头像/昵称/硬币等完整资料（三端 token 只有 mid，没有昵称）。
     * 只有 web 没登录时，才退而用某个端口账号。
     */
    private const val SOURCE_WEB = "web"

    // ==================== 迁移级别（三级模型） ====================

    /** 一级：纯 Web，与老版本逐行为一致 */
    const val TIER_WEB_ONLY = 1

    /** 二级：只有推荐页走 APP 接口，其余全 Web */
    const val TIER_RECOMMEND = 2

    /** 三级：全接口走端口，账号与页面解耦 */
    const val TIER_FULL = 3

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
     * 当前用于「推荐页移动端逻辑」的身份 mid（未登录返回 0）。
     * 与 web 完全独立——这是既有的三端独立登录体系，这里只是把它读出来。
     */
    @JvmStatic
    fun appRcmdMid(): Long {
        val identity = AppRcmdApi.getIdentity()
        if (!AppRcmdApi.isIdentityLogin(identity.id)) return 0L
        val mid = AppRcmdApi.getIdentityMid(identity.id)
        return if (mid > 0L) mid else 0L
    }

    /**
     * 全部已登录端口的 mid 列表（web 在最前，其后是三端中已登录的）。
     * 用于「评论里判断哪条是我发的」这类**需要一个具体 uid** 的场景：
     * 只要这条评论/动态的作者 mid 命中列表里任何一个，就是我。
     *
     * 第一级（三级全关）时只返回 web mid，与老版本判断完全一致。
     */
    @JvmStatic
    fun selfMids(): MutableList<Long> {
        val list = ArrayList<Long>(2)
        val web = webMid()
        if (web > 0L) list.add(web)
        if (!multiChannelEnabled()) return list
        for (identityId in ALL_IDENTITIES) {
            if (!AppRcmdApi.isIdentityLogin(identityId)) continue
            val mid = AppRcmdApi.getIdentityMid(identityId)
            if (mid > 0L && !list.contains(mid)) list.add(mid)
        }
        return list
    }

    /** 判断某个 uid 是不是「我」（多通道下任一通道命中即算） */
    @JvmStatic
    fun isSelf(uid: Long): Boolean {
        if (uid <= 0L) return false
        return selfMids().contains(uid)
    }

    /**
     * 主账号 mid：优先 web，其次三端任一（仅当 [multiChannelEnabled]）。
     *
     * **这是页面该用的"我是谁"**。返回 0 表示真的一个号都没登录。
     */
    @JvmStatic
    fun accountMid(): Long {
        val web = webMid()
        if (web > 0L) return web
        if (!multiChannelEnabled()) return 0L
        val rcmd = appRcmdMid()
        if (rcmd > 0L) return rcmd
        for (identityId in ALL_IDENTITIES) {
            if (!AppRcmdApi.isIdentityLogin(identityId)) continue
            val mid = AppRcmdApi.getIdentityMid(identityId)
            if (mid > 0L) return mid
        }
        return 0L
    }

    /** 主账号来源：`web` 或某个身份 id（hd/tv/phone），没有账号时返回空串 */
    @JvmStatic
    fun accountSource(): String {
        if (isWebLoggedIn()) return SOURCE_WEB
        if (!multiChannelEnabled()) return ""
        if (appRcmdMid() > 0L) return AppRcmdApi.getIdentity().id
        for (identityId in ALL_IDENTITIES) {
            if (AppRcmdApi.isIdentityLogin(identityId) && AppRcmdApi.getIdentityMid(identityId) > 0L) {
                return identityId
            }
        }
        return ""
    }

    // ==================== 三级模式 ====================

    /**
     * 当前所处的迁移级别（1 / 2 / 3）。**这是全 App 唯一的级别判定入口**。
     *
     * 用户需求原文把迁移分成三级，这里的语义与之逐字对应：
     *
     * - **一级**（[TIER_WEB_ONLY]）：推荐 API 迁移关 + 全 API 迁移关
     *   → 纯纯用 Web，与老版本 5.1.3 **逐行为一致**；
     * - **二级**（[TIER_RECOMMEND]）：推荐 API 迁移开 + 全 API 关
     *   → 只有推荐页走 APP 接口，**其余全部走 Web**；
     * - **三级**（[TIER_FULL]）：全 API 迁移开
     *   → 全部接口走所选端口，**账号逻辑与页面逻辑彻底解耦**。
     *
     * 为什么二级不算上端口账号：二级的定义是「推荐用迁移 API，其他的全用 Web」。
     * 既然账号相关接口仍走 Web，那「我的/稍后再看/收藏」就必须依赖 Web 登录——
     * 此时若放开门槛让按钮可见，用户点下去只会拿到一个空 Cookie 的失败请求，
     * **「可见但点了必失败」比「隐藏」体验更差**。所以二级保守维持 Web 口径。
     *
     * 单端口模式（实验室「重构账号体系」选了非 web 端口）无条件按三级处理：
     * 那时端口就是唯一账号，不放行反而整个 App 都用不了。
     */
    @JvmStatic
    fun currentTier(): Int {
        // 实验室单端口模式：端口就是唯一账号，等同于三级
        if (ApiRouter.isSinglePortMode()) return TIER_FULL
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.API_MIGRATION_ENABLED, true)) return TIER_FULL
        // 全 API 关：看推荐 API 迁移开没开，区分一级/二级
        return if (AppRcmdApi.isEnabled()) TIER_RECOMMEND else TIER_WEB_ONLY
    }

    /** 级别显示名（设置页 / 状态页用） */
    @JvmStatic
    fun tierName(tier: Int): String {
        return when (tier) {
            TIER_FULL -> "三级（全 API 迁移）"
            TIER_RECOMMEND -> "二级（仅推荐 API 迁移）"
            else -> "一级（纯 Web）"
        }
    }

    /**
     * 是否允许「非 web 通道的账号」也算作当前账号。
     *
     * **只有三级才为 true**。这是「第一级/第二级必须与老版本一致」的保证线，
     * 也是页面层做显隐判断时能保持诚实的前提（见 [currentTier] 的说明）。
     */
    @JvmStatic
    fun multiChannelEnabled(): Boolean {
        return currentTier() == TIER_FULL
    }

    /**
     * **页面判断"用户有没有账号"的唯一入口**（替代散落各处的 `mid == 0L` 判断）。
     *
     * 语义：
     * - 开了任意一级迁移 → 「任一通道登录即可」，退出 web 但 HD/TV/手机还登录着时仍返回 true，
     *   页面不会再把「我的」「稍后再看」「点赞」藏起来；
     * - 三级全关（第一级）→ 退化成「web 登录即账号」，与老版本完全一致。
     */
    @JvmStatic
    fun isLoggedIn(): Boolean {
        if (isWebLoggedIn()) return true
        if (!multiChannelEnabled()) return false
        return appRcmdMid() > 0L || anyIdentityLoggedIn()
    }

    private fun anyIdentityLoggedIn(): Boolean {
        for (identityId in ALL_IDENTITIES) {
            if (AppRcmdApi.isIdentityLogin(identityId) && AppRcmdApi.getIdentityMid(identityId) > 0L) return true
        }
        return false
    }

    /**
     * 是否能执行「需要登录的写操作」（点赞/投币/收藏/评论/稍后再看…）。
     *
     * 目前与 [isLoggedIn] 相同：只要任意通道有账号，就允许用户尝试，
     * 请求层（[ApiRouter]）会挑一个能用的通道发出去，真失败再按业务码提示。
     * 单独留一个方法名，是为了将来如果某些操作需要"特定通道"时有落点。
     */
    @JvmStatic
    fun canWrite(): Boolean {
        return isLoggedIn()
    }

    // ==================== 展示信息（昵称 / 头像） ====================

    /**
     * 主账号昵称（「我的」页面头部用）。
     *
     * web 登录直接用缓存的昵称；只有端口账号时，昵称来自该端的登录返回
     * （`app_rcmd_*_uname`，v5.1.4 起在扫码登录成功时顺带写入）。
     * 都没有就返回占位文案，**不返回空串**（避免页面出现一个空白名字）。
     */
    @JvmStatic
    fun displayName(): String {
        if (isWebLoggedIn()) {
            val name = SharedPreferencesUtil.getString(KEY_WEB_UNAME, "") ?: ""
            if (name.isNotEmpty()) return name
        }
        for (identityId in identityPriority()) {
            if (!AppRcmdApi.isIdentityLogin(identityId)) continue
            val name = SharedPreferencesUtil.getString(keyOf(identityId, "uname"), "") ?: ""
            if (name.isNotEmpty()) return name
        }
        return "已登录"
    }

    /** 主账号头像 url（没有就返回空串，由页面用自己的占位图） */
    @JvmStatic
    fun avatarUrl(): String {
        if (isWebLoggedIn()) {
            val face = SharedPreferencesUtil.getString(KEY_WEB_FACE, "") ?: ""
            if (face.isNotEmpty()) return face
        }
        for (identityId in identityPriority()) {
            if (!AppRcmdApi.isIdentityLogin(identityId)) continue
            val face = SharedPreferencesUtil.getString(keyOf(identityId, "face"), "") ?: ""
            if (face.isNotEmpty()) return face
        }
        return ""
    }

    /**
     * 用户名缓存键。web 用新键（v5.1.4 首次登录时写入），端口用各自身份槽位。
     * 之所以不读旧键：旧版本从未缓存过昵称，读旧键只会得到空。
     */
    private const val KEY_WEB_UNAME = "account_web_uname"
    private const val KEY_WEB_FACE = "account_web_face"

    /** 记录 web 登录成功后的昵称/头像（登录/刷新资料时调用；只写新键，不动任何凭据） */
    @JvmStatic
    fun cacheWebProfile(name: String?, face: String?) {
        if (!name.isNullOrEmpty()) SharedPreferencesUtil.putString(KEY_WEB_UNAME, name)
        if (!face.isNullOrEmpty()) SharedPreferencesUtil.putString(KEY_WEB_FACE, face)
    }

    /** 记录某个端口的昵称/头像（扫码登录成功时调用） */
    @JvmStatic
    fun cacheIdentityProfile(identityId: String, name: String?, face: String?) {
        if (!name.isNullOrEmpty()) SharedPreferencesUtil.putString(keyOf(identityId, "uname"), name)
        if (!face.isNullOrEmpty()) SharedPreferencesUtil.putString(keyOf(identityId, "face"), face)
    }

    /** 清掉 web 侧的展示缓存（退出 web 登录时调用） */
    @JvmStatic
    fun clearWebProfile() {
        SharedPreferencesUtil.removeValue(KEY_WEB_UNAME)
        SharedPreferencesUtil.removeValue(KEY_WEB_FACE)
    }

    // ==================== 文案（给页面统一口径，避免各处自己拼） ====================

    /**
     * 统一的操作拦截提示。页面用 [isLoggedIn] 判断后，需要告诉用户"去登录"时用这个，
     * 保证全 App 文案一致，且不再出现「还没有登录喵~」这种在三级模式下会误伤的说法。
     *
     * 文案**按级别区分**，因为各模式下用户该做的事不一样：
     * - 一级/二级：账号就是 Web 登录，得去登录 Web；
     * - 三级：任一端口登录都能用，而且可以完全不登 Web。
     * 让提示直接告诉用户"在哪一级该登什么"，比一句放之四海皆准的废话有用。
     */
    @JvmStatic
    fun loginHint(): String {
        return when (currentTier()) {
            TIER_FULL -> "还没有登录，请先在「设置 → 登录选项」登录任一端口（无需登录 Web）"
            TIER_RECOMMEND -> "还没有登录，请先登录 Web 账号（推荐页接口还需在「推荐 API 迁移」里单独扫码）"
            else -> "还没有登录，请先登录 Web 账号"
        }
    }

    /**
     * 当前账号状况的一句话描述（设置页/登录选项页展示用）。
     * 形如「Web 已登录（uid 123）＋ TV 已登录」。
     */
    @JvmStatic
    fun describe(): String {
        val sb = StringBuilder()
        sb.append(if (isWebLoggedIn()) "Web 已登录（uid " + webMid() + "）" else "Web 未登录")
        for ((identityId, name) in IDENTITY_NAMES) {
            sb.append("　").append(name).append(" ")
            if (AppRcmdApi.isIdentityLogin(identityId)) {
                val mid = AppRcmdApi.getIdentityMid(identityId)
                sb.append(if (mid > 0L) "已登录（uid $mid）" else "已登录")
            } else {
                sb.append("未登录")
            }
        }
        return sb.toString()
    }

    // ==================== 内部 ====================

    private val ALL_IDENTITIES = arrayOf(
        AppRcmdApi.IDENTITY_PHONE, AppRcmdApi.IDENTITY_HD, AppRcmdApi.IDENTITY_TV
    )

    private val IDENTITY_NAMES = arrayOf(
        AppRcmdApi.IDENTITY_PHONE to "手机",
        AppRcmdApi.IDENTITY_HD to "HD",
        AppRcmdApi.IDENTITY_TV to "TV"
    )

    /**
     * 挑展示信息时的身份顺序：当前选中的身份优先，其余按 手机 → HD → TV。
     * 用「当前身份优先」是因为用户选了哪端，页面就该显示哪端的号，符合直觉。
     */
    private fun identityPriority(): List<String> {
        val current = AppRcmdApi.getIdentity().id
        val list = ArrayList<String>(ALL_IDENTITIES.size)
        list.add(current)
        for (identityId in ALL_IDENTITIES) {
            if (identityId != current) list.add(identityId)
        }
        return list
    }

    /** 端口槽位键：app_rcmd_{identity}_{suffix}（与 AppRcmdApi 的命名保持一致） */
    private fun keyOf(identityId: String, suffix: String): String {
        return "app_rcmd_" + identityId + "_" + suffix
    }
}
