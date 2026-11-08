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
}
