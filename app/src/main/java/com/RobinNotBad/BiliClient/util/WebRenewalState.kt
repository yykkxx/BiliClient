package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.api.AppTokenRefreshApi

/**
 * 网络只拿不可变快照；完成时比较账号、凭据和 Cookie，绝不覆盖途中切换/退出的账号。
 *
 * 快照比较是本模块唯一的安全边界：所有写回动作都必须先通过 [matches]，
 * 否则「续期请求在飞、用户切了号」就会把旧账号的新凭据写进新账号。
 */
object WebRenewalState {
    class Snapshot(val mid: Long, val credentialId: String, val cookies: String, val refreshToken: String)

    fun capture(): Snapshot = synchronized(AccountStore) {
        val mid = AccountStore.activeMid(AccountStore.PORT_WEB)
        Snapshot(
            mid,
            AccountCredentialStore.activeId(AccountStore.PORT_WEB, mid),
            SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "",
            SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "") ?: ""
        )
    }

    /**
     * 当前 Web 登录**能不能自续**——给 UI 直接展示的一句话结论（v5.3.3 新增）。
     *
     * # 为什么必须把这个暴露给用户
     * 用户本轮的原话是「我重新登录了 web 账号，但是仍然无法自己续期自己」。
     * 在 v5.3.2 里这件事**完全不可见**：凭据有没有 `refresh_token`、
     * 是「本地没有」还是「服务端不认」，用户只能点一次续期按钮、看一条失败提示之后才知道。
     * 而这恰恰是本轮实测出来的核心事实（notes/web_renew_v533.md §0/§6）：
     * **B 站已把扫码登录页换成新域名，新登录页前端代码里 `refresh_token` 出现 0 次** ——
     * 于是「重新扫码」这个 v5.3.2 给出的唯一建议，很可能**根本解决不了问题**。
     *
     * 与其让用户反复扫码试错，不如把状态摆在设置页上：一眼看出「没有 token」，
     * 并且知道下一步该点哪个（跨端修复 / 重新登录官方网页版后导出导入）。
     *
     * # 为什么单独一个枚举而不是复用 RenewResult
     * 这是**静态状态**（读本地槽位即可，零网络），RenewResult 是**一次续期的结果**。
     * 把两者混在一起会让「续期失败」和「本来就不能续」在 UI 上无法区分。
     */
    enum class SelfRenewalCapability {
        /** 该端口还没登录 */
        NotLoggedIn,

        /** 已登录、有 `refresh_token`：能自续（token 是否已过期要真发一次才知道） */
        Ready,

        /** 已登录但本地没有 `refresh_token`：自续协议**不可能**成功，只能重新登录或跨端修复 */
        MissingToken
    }

    /** 零网络、纯本地读取，可在 UI 线程直接调用。 */
    fun selfRenewalCapability(): SelfRenewalCapability = synchronized(AccountStore) {
        val snapshot = capture()
        when {
            snapshot.mid <= 0L || snapshot.cookies.isEmpty() -> SelfRenewalCapability.NotLoggedIn
            snapshot.refreshToken.isBlank() -> SelfRenewalCapability.MissingToken
            else -> SelfRenewalCapability.Ready
        }
    }

    /** `refresh_token` 的长度（**只用于展示「有没有/多长」，绝不返回内容**）。 */
    fun refreshTokenLength(): Int = synchronized(AccountStore) {
        capture().refreshToken.length
    }

    fun isCurrent(expected: Snapshot): Boolean = synchronized(AccountStore) {
        matches(capture(), expected)
    }

    /**
     * 快照是否仍然有效（逐字段比较，命中即返回）。
     *
     * 单独拆出来是因为「四个字段一起 ||」会被 detekt 判为 `ComplexCondition`；
     * 拆成顺序 early-return 后语义完全等价（任一字段不同 → 不匹配）。
     */
    private fun matches(current: Snapshot, expected: Snapshot): Boolean {
        if (current.mid != expected.mid) return false
        if (current.credentialId != expected.credentialId) return false
        if (current.cookies != expected.cookies) return false
        return current.refreshToken == expected.refreshToken
    }

    /**
     * 跨端修复的提交（借用端 oauth 结果 → Web 凭据）。
     *
     * 四道校验缺一不可：
     * 1. 结果 mid 必须等于快照 mid（不跨账号）；
     * 2. 借用端口只能是 HD/TV（绝不接受 Android，也不接受伪造的 Web）；
     * 3. 借用端口当前激活账号仍是同 mid；
     * 4. **F1**：借用端激活凭据 id + 两个 token 必须与发起请求时捕获的一致 ——
     *    同一 mid 下可能存在多份内容相同的凭据，只比 token 会覆盖用户刚切到的那一份。
     *
     * 注意：Web 的 `refresh_token` 槽写成空串是**有意**的。oauth2 的 token 属于借用端口，
     * 若写进 Web 槽，下次自续期就会拿别的端口的 token 去换 Web Cookie（用户明确禁止），
     * 而且会让「Web 完全没有 token」这个可判断状态变得不可靠。
     */
    fun applyCrossPort(expected: Snapshot, result: AppTokenRefreshApi.Result.Success): Boolean =
        synchronized(AccountStore) {
            if (result.mid != expected.mid || result.mid <= 0L) return@synchronized false
            if (result.port != AccountStore.PORT_HD && result.port != AccountStore.PORT_TV) {
                return@synchronized false
            }
            if (AccountStore.activeMid(result.port) != result.mid) return@synchronized false
            if (AccountCredentialStore.activeId(result.port, result.mid) != result.sourceCredentialId) {
                return@synchronized false
            }
            val fields = AccountStore.credentialFields(result.port, result.mid)
            if (fields["access_token"] != result.oldAccessToken) return@synchronized false
            if (fields["refresh_token"] != result.oldRefreshToken) return@synchronized false
            if (!apply(expected, result.cookies, "")) return@synchronized false
            val updated = fields.toMutableMap()
            updated["access_token"] = result.accessToken
            updated["refresh_token"] = result.refreshToken
            updated["cookies"] = result.cookies
            updated["csrf"] = NetWorkUtil.getInfoFromCookie("bili_jct", result.cookies)
            AccountStore.writeCredentialFields(result.port, result.mid, updated)
            AccountCredentialStore.updateActiveFromAccount(result.port, result.mid)
            true
        }

    /**
     * 自续成功后的落地：新 Cookie + 新 token 同时写入激活槽位、账号槽与当前凭据。
     *
     * @param token 新的 Web `refresh_token`；跨端修复时传空串（理由见 [applyCrossPort]）
     */
    fun apply(expected: Snapshot, cookies: String, token: String): Boolean = synchronized(AccountStore) {
        if (!matches(capture(), expected)) return@synchronized false
        val prefs = SharedPreferencesUtil.sharedPreferences ?: return@synchronized false
        val csrf = NetWorkUtil.getInfoFromCookie("bili_jct", cookies)
        prefs.edit()
            .putString(SharedPreferencesUtil.cookies, cookies)
            .putString(SharedPreferencesUtil.csrf, csrf)
            .putString(SharedPreferencesUtil.refresh_token, token)
            .putBoolean(SharedPreferencesUtil.cookie_refresh, true)
            .apply()
        AccountStore.persistActiveWeb()
        // 协议会注销旧 refresh_token：更新当前凭据而不是不断堆积已经无效的「续期快照」。
        AccountCredentialStore.updateActiveFromAccount(AccountStore.PORT_WEB, expected.mid)
        NetWorkUtil.refreshHeaders()
        true
    }

    /**
     * `correspond` 页 `#1-value` 里若带了 web refresh_token，补进当前 Web 账号（v5.3.3）。
     *
     * # 这是什么通道
     * B 站官方前端把 web `refresh_token` 放在两个地方（`token-iframe` chunk 895 的真实代码）：
     * `#1-value`（登录成功后由登录页经 iframe 注入这个 DOM 节点）与
     * `localStorage["ac_time_value"]`（浏览器自己存）。
     *
     * 本 App 既没有 iframe 也没有 localStorage，所以只能指望服务端在 `correspond` 页里
     * 顺手把值塞进 `#1-value`。**实测本机它是空的**（len=0），因此这只是**机会性自愈**：
     * 一旦某天 B 站真的下发了，用户不用重新扫码就能恢复自续能力。
     *
     * # 两条硬约束
     * 1. **只在当前槽为空时写**。已有 token 比页面里的更可信 —— 页面里的可能是给
     *    另一个会话签发的，拿它覆盖会让本来能续的号变成不能续。
     * 2. **不触发 `saveCurrent` / 不新增凭据条目**。这里只是补一个字段，
     *    不是一次新的登录；凭空造出「凭据快照」会让账号管理页出现重复条目。
     *
     * @return 是否真的写入了（供日志/回归断言用；不写入时完全无副作用）
     */
    fun adoptRefreshTokenIfMissing(token: String): Boolean = synchronized(AccountStore) {
        if (token.isBlank()) return@synchronized false
        // 先快照当前状态：会话已失效（mid<=0 或 Cookie 为空）时不写，
        // 否则会把 token 写到「已经没有登录态」的账号槽里，制造半死状态。
        val current = capture()
        if (current.mid <= 0L || current.cookies.isEmpty()) return@synchronized false
        // 已有 token 就不覆盖（理由见上）。
        if (current.refreshToken.isNotBlank()) return@synchronized false
        SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, token)
        AccountStore.persistActiveWeb()
        // 只镜像已有凭据的字段，不新增条目。
        AccountCredentialStore.updateActiveFromAccount(AccountStore.PORT_WEB, current.mid)
        NetWorkUtil.refreshHeaders()
        Logu.d("web-renewal", "已从 correspond #1-value 回填缺失的 Web refresh_token")
        true
    }
}
