package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.util.AccountCredentialStore
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException // quality-allow：oauth2 协议边界，不输出响应中的凭据
import org.json.JSONObject // quality-allow：oauth2 协议边界，不输出响应中的凭据
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 仅由用户明确授权的 Web 修复调用；候选端口必须与 Web 是同一 mid。
 *
 * 本对象**只会**被 [CookieRefreshApi.renewWeb(allowCrossPort=true)] 调用，
 * 默认路径（独立开关关着且没有单次确认）根本不会走到这里。
 */
object AppTokenRefreshApi {
    private const val REFRESH_URL = "https://passport.bilibili.com/x/passport-login/oauth2/refresh_token"
    private val client by lazy {
        // 不复用会自动写入 Web Cookie 的拦截器，校验通过前所有响应仅存在局部变量。
        OkHttpClient.Builder().dns(NetWorkUtil.Inet4Selector())
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(16, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).followRedirects(false)
            .retryOnConnectionFailure(false).build()
    }

    /** 借用端点的一份只读快照：[credentialId] 是乐观锁来源，见 [Result.Success.sourceCredentialId]。 */
    private class Candidate(
        val port: String,
        val mid: Long,
        val credentialId: String ?,
        val fields: Map<String, String>
    )

    sealed class Result {
        /**
         * @param oldAccessToken / oldRefreshToken 用于比较借用端 token 是否已被换走
         * @param sourceCredentialId 发起请求时借用端**激活凭据 id**。
         *   F1：同一 mid 下可能存在多份内容完全相同的凭据（用户复制/导入），
         *   只比较 token 无法区分「被切到了哪一份」——此时写入会覆盖用户刚选中的另一份。
         *   因此提交阶段必须连同 credentialId 一起比较。
         */
        class Success(
            val cookies: String,
            val accessToken: String,
            val refreshToken: String,
            val port: String,
            val mid: Long,
            val oldAccessToken: String,
            val oldRefreshToken: String,
            val sourceCredentialId: String
        ) : Result()
        object NoAppIdentity : Result()
        data class Rejected(val lastCode: Int) : Result()
        data class NetworkError(val detail: String) : Result()
    }

    /** 借用候选端口顺序：HD 优先、其次 TV。绝不包含 Android/Web。 */
    private val BORROW_PORTS = listOf(AccountStore.PORT_HD, AccountStore.PORT_TV)

    /** 不读任何其它账号的 token；没有同账号 HD/TV 时明确失败。 */
    fun refreshWebCookiesFromAppToken(expectedMid: Long): Result {
        if (expectedMid <= 0L) return Result.NoAppIdentity
        var last: Result = Result.NoAppIdentity
        for (candidate in borrowCandidates(expectedMid)) {
            last = refreshOne(candidate)
            if (last is Result.Success || last is Result.NetworkError) return last
        }
        return last
    }

    /**
     * 本账号可借用的候选（同 mid、且确有 `refresh_token`），按 [BORROW_PORTS] 顺序。
     *
     * 把「跳过不可用端口」的条件收进 filter 后，调用方的循环里不再需要
     * `continue`/`break`（detekt `LoopWithTooManyJumpStatements`），行为与逐条 continue 等价：
     * mid 不匹配或没有 token 的端口本来就不会产生 `last`。
     */
    private fun borrowCandidates(expectedMid: Long): List<Candidate> =
        BORROW_PORTS.mapNotNull { captureCandidate(it, expectedMid) }
            .filter { !it.fields["refresh_token"].isNullOrBlank() }

    /**
     * 在**同一账号锁内**捕获借用端候选：字段与激活凭据 id 必须一致地快照。
     *
     * 若「读字段」与「读 activeId」分两次加锁，中间插进的凭据切换会让 CAS 比较的
     * 是一组互不匹配的旧值（F1 的成因之一）。非同一 mid 时返回 null。
     */
    private fun captureCandidate(port: String, expectedMid: Long): Candidate? {
        return synchronized(AccountStore) {
            if (AccountStore.activeMid(port) != expectedMid) return@synchronized null
            Candidate(
                port, expectedMid,
                AccountCredentialStore.activeId(port, expectedMid),
                AccountStore.credentialFields(port, expectedMid)
            )
        }
    }

    private fun refreshOne(candidate: Candidate): Result {
        val identity = AccountStore.identityOf(candidate.port) ?: return Result.NoAppIdentity
        val oldRefresh = candidate.fields["refresh_token"] ?: return Result.NoAppIdentity
        val request = buildOauthRequest(identity, oldRefresh)
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Result.Rejected(response.code)
                val root = JSONObject(response.body?.string() ?: throw IOException("oauth2 响应为空"))
                val code = root.optInt("code", -1)
                if (code != 0) return Result.Rejected(code)
                parseSuccess(candidate, root)
            }
        } catch (e: IOException) {
            Result.NetworkError(e.javaClass.simpleName)
        } catch (e: JSONException) { // quality-allow：协议异常，不回写任何凭据
            Result.NetworkError(e.javaClass.simpleName)
        }
    }

    /** oauth2 的 appkey/ts/refresh_token 三段明文 + `appsec` 拼接后做 MD5 签名。 */
    private fun buildOauthRequest(identity: AppRcmdApi.RcmdIdentity, oldRefresh: String): Request {
        val params = sortedMapOf(
            "appkey" to identity.feedAppKey,
            "ts" to (System.currentTimeMillis() / 1000L).toString(),
            "refresh_token" to oldRefresh
        )
        val signingText = params.entries.joinToString("&") { it.key + "=" + it.value } + identity.feedAppSec
        val sign = CookieRefreshApi.base16Encode(
            MessageDigest.getInstance("MD5").digest(signingText.toByteArray(Charsets.UTF_8))
        )
        val form = FormBody.Builder()
        for ((key, value) in params) form.add(key, value)
        form.add("sign", sign)
        return Request.Builder().url(REFRESH_URL).post(form.build())
            .header("User-Agent", identity.ua).build()
    }

    private fun parseSuccess(candidate: Candidate, root: JSONObject): Result {
        val data = root.optJSONObject("data") ?: return Result.Rejected(0)
        val token = data.optJSONObject("token_info") ?: return Result.Rejected(0)
        if (token.optLong("mid", 0L) != candidate.mid) return Result.Rejected(-101)
        val cookies = parseCookies(data) ?: return Result.Rejected(0)
        val accessToken = token.optString("access_token", "")
        val refreshToken = token.optString("refresh_token", "")
        if (!isComplete(cookies, candidate.mid, accessToken, refreshToken)) return Result.Rejected(-101)
        return Result.Success(
            cookies.entries.joinToString("; ") { it.key + "=" + it.value },
            accessToken, refreshToken, candidate.port, candidate.mid,
            candidate.fields["access_token"] ?: "", candidate.fields["refresh_token"] ?: "",
            candidate.credentialId ?: ""
        )
    }

    /** 把 `cookie_info.cookies[]` 摊平成 name→value；结构缺失返回 null（与「空 map」区分）。 */
    private fun parseCookies(data: JSONObject): Map<String, String>? {
        val array = data.optJSONObject("cookie_info")?.optJSONArray("cookies") ?: return null
        val cookies = linkedMapOf<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name", "")
            val value = item.optString("value", "")
            if (name.isNotBlank() && value.isNotBlank()) cookies[name] = value
        }
        return cookies
    }

    /**
     * 新凭据必须自洽：账号、会话与 CSRF 都指向同一个 mid。
     *
     * 拆成独立函数只是为了可读（原 Condition 复杂度 5 > 阈值 4），语义与拆分前逐条一致。
     */
    private fun isComplete(
        cookies: Map<String, String>,
        mid: Long,
        accessToken: String,
        refreshToken: String
    ): Boolean {
        if (cookies["DedeUserID"]?.toLongOrNull() != mid) return false
        if (cookies["SESSDATA"].isNullOrBlank()) return false
        if (cookies["bili_jct"].isNullOrBlank()) return false
        return accessToken.isNotBlank() && refreshToken.isNotBlank()
    }
}
