package com.RobinNotBad.BiliClient.api

import android.util.Base64

import android.os.SystemClock

import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONObject
import org.jsoup.Jsoup

import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Objects

import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.json.JSONException
import java.io.IOException

/*
这里是Cookie刷新逻辑
https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E8%8E%B7%E5%8F%96refresh-csrf
 */
object CookieRefreshApi {

    private const val TAG = "cookie-refresh"

    // ==========================================================================================
    // v5.3.1 Web 续期：唯一入口 + 结构化结果
    // ==========================================================================================
    //
    // ## 为什么必须重做（用户原话：「web 续期必须修复」）
    //
    // v5.2.1 / v5.3.0 的续期**从来没有成功过**，而且失败原因全被压成一个 false，
    // 用户看到的就是「点了续期，提示『可能 Cookie 仍有效，或 refresh_token 已失效』」——
    // 等于什么都没说。2026-11-08 用真实凭据（`app_api/cookies.txt`）实测，根因有三层：
    //
    // ### 根因 1：`refresh_token` 是协议硬前提，而多数 web 登录拿不到它
    // 实测（python 复现整条链路，见 notes/ 本轮记录）：
    // - `POST .../cookie/refresh` 且 `refresh_token` 传空  → `code=-400`「请求错误」
    // - 同上，`refresh_token` 传假值                    → `code=86032`「refreshToken已过期」
    // 也就是说：**没有 refresh_token 时服务端连「跳过」都不给，直接 -400**。
    // 而扫码登录的回包里 `refresh_token` 是**可选字段**，历史凭据/导出的凭据常常没有它。
    // 旧代码在这种情况下仍然把整条链路跑一遍（4 次网络请求），最后拿到一个 -400 吞掉，
    // 用户完全不知道「这个号根本续不了，只能重新扫码」。
    // → 现在**发请求前**就检查，缺 token 直接返回 [RenewResult.NoRefreshToken]。
    //
    // ### 根因 2：续期成功后没有回写账号槽，等于白续
    // 旧代码里 4 条续期路径只有 2 条调了 `AccountStore.persistActiveWeb()`：
    // - 调了的：`CredentialStore.renewWeb()`、`AuthChannel.renewQuietly()`
    // - **没调的**：`CookieRefreshApi.checkAndRefresh()`（启动时走这条）、
    //              `RecommendActivity.checkCookieRefresh()`（进推荐页走这条）
    // 而「启动时续期」恰恰是最常走的路径。续期成功后新 Cookie 只落在激活槽位，
    // 账号槽里还是旧的 → 切一次号 / 重启一次进程，新 Cookie 就没了，还得再续一次。
    // → 现在统一入口内部**成功即回写**（见 [renewWeb]），调用方不需要记得这件事。
    //
    // ### 根因 3：4 条路径各写一遍，行为不一致
    // 见下面 [renewWeb] 的 KDoc。
    //
    // ## 顺带修掉的第 4 个问题：`validateWeb()` 形同虚设
    // `CredentialStore.validateWeb()` 用 `cookie/info` 的 `data.isLogin` 判登录态，
    // 但实测该接口的 `data` **只有 `refresh` 和 `timestamp` 两个键，根本没有 `isLogin`**
    // → `else true` 分支永远命中 → web 凭据永远被判定「有效」。
    // 改由 `CredentialStore` 用 `x/web-interface/nav` 的 `data.isLogin` 判定（那边是本轮写域）。

    /**
     * 一次续期尝试的**结构化结果**。
     *
     * # 为什么不能再用 Boolean
     * 旧接口返回 Boolean，调用方对 `false` 的解释只能靠猜：「不需要续」和「续失败」都是 false，
     * 于是提示文案只能写成「可能 Cookie 仍有效，或 refresh_token 已失效」这种什么都不说的话。
     * 用户点完按钮仍然不知道下一步该干什么（是等一会儿再试？还是重新扫码？）。
     *
     * 现在每一种结局都是独立分支，调用方可以给出**可执行**的提示：
     * - [NoRefreshToken] / [TokenExpired] → 只能重新扫码登录（重试没有意义）
     * - [NetworkError] / [ServerError]    → 可以稍后重试（登录态没坏）
     * - [NotNeeded]                       → 什么都不用做
     * - [Success]                         → 已经续好，直接继续用
     *
     * # 为什么用 sealed class 而不是 enum + 附加字段
     * `TokenExpired` / `ServerError` 要带服务端 code（86032 / -400 / -101…），
     * enum 装不下；而 `when` 对 sealed class 是**穷尽检查**的 —— 以后新增一种结局，
     * 所有 `when` 点会直接编译失败，逼着人处理，不会像 enum + else 那样悄悄漏掉。
     */
    sealed class RenewResult {
        /** 服务端明确表示「当前 Cookie 还很新，不需要续期」 */
        object NotNeeded : RenewResult()

        /** 续期成功，且新凭据**已经回写**账号槽与激活槽位 */
        object Success : RenewResult()

        /**
         * web 登录没有 `refresh_token`，协议层无法续期 —— 只能重新扫码。
         *
         * 这是实测最容易命中的分支：扫码登录回包里 `refresh_token` 是可选的，
         * 历史版本导出的凭据、手动导入的凭据往往都没有它。
         */
        object NoRefreshToken : RenewResult()

        /** 服务端说 refresh_token 已过期（实测 `code=86032`）—— 也只能重新扫码 */
        object TokenExpired : RenewResult()

        /**
         * 服务端拒绝了这次续期。
         *
         * @param code 服务端业务码（实测传空 refresh_token 时是 -400，其它鉴权失败常见 -101 / -352）
         * @param message 服务端的 message（**不含任何凭据**，可安全展示/记录）
         */
        data class ServerError(val code: Int, val message: String) : RenewResult()

        /** 网络/解析异常。登录态**没坏**，下次再试即可 */
        data class NetworkError(val detail: String) : RenewResult()

        /** 本次是否真的把凭据换新了（只有 [Success] 是 true） */
        fun renewed(): Boolean = this is Success
    }

    /**
     * 节流记录：上次真正检查 `cookie/info` 的时刻（SystemClock.elapsedRealtime）
     *
     * `AtomicLong` 而不是 `@Volatile Long`：进程启动时 `BiliTerminal` 与
     * `RecommendActivity` 可能在两个线程上同时触发续期检查，
     * 「读-判-写」三步必须原子，否则节流会被击穿成两次请求。
     */
    private val lastCheckElapsed = java.util.concurrent.atomic.AtomicLong(0L)

    /** 主动续期的默认间隔：30 分钟。Cookie 有效期是数天级别，这个粒度足够且不打扰网络。 */
    private const val RENEW_INTERVAL_MS = 30L * 60L * 1000L

    /** 服务端「refresh_token 已过期」的业务码（实测确认） */
    private const val CODE_REFRESH_TOKEN_EXPIRED = 86032

    /** 服务端「appID 不匹配」的业务码（实测：用 android appkey 刷 hd/tv 签发的 token 时回这个） */
    private const val CODE_APP_ID_MISMATCH = 86033

    /**
     * 前台自动续期入口（幂等 + 节流）。**调用方在后台线程调**（它会发网络请求）。
     *
     * # v5.3.1 两处修正
     *
     * ## 1. 去掉「主端口必须是 web」这道门槛
     * 旧代码第一行是 `if (AccountStore.mainPort() != AccountStore.PORT_WEB) return false`。
     * 语义是「用户没把其它页面 API 选成 web，就没有 Cookie 可续」——
     * 但这是**错的**：web 端口的 Cookie 与「其它页面走哪个端口」是两件事。
     * 推荐页、web-only 端点、CDN 鉴权都仍然依赖 web Cookie；
     * 用户一旦把其它页面选成 android/hd/tv，web Cookie 就在无人续期的状态下慢慢烂掉，
     * 等到某次操作失败才暴露（这正是「web 续期必须修复」用户体感的一部分）。
     * → 真正的门槛是「**web 端口已登录**」，与主端口选择无关。
     *
     * ## 2. 返回值语义变清晰
     * 旧代码返回 Boolean 且把「没到时间」和「续期失败」都压成 false。
     * 现在返回 [RenewResult]，「没到时间」单独用 [RenewResult.NotNeeded] 表达
     * （调用方通常应静默，不要弹提示）。
     *
     * @param force true = 忽略 30 分钟节流立刻检查（用户手动点「立即续期」时用）
     */
    @JvmStatic
    fun renewIfNeededOnForeground(force: Boolean): RenewResult {
        // 门槛是「web 端口有没有登录」，而不是「当前主端口是不是 web」（理由见上面 KDoc）
        if (!AccountStore.isPortLoggedIn(AccountStore.PORT_WEB)) {
            Logu.d(TAG, "web 端口未登录，跳过自动续期")
            return RenewResult.NotNeeded
        }
        if (!force && !shouldCheckNow()) {
            Logu.d(TAG, "距上次检查不足 " + (RENEW_INTERVAL_MS / 60000L) + " 分钟，跳过")
            return RenewResult.NotNeeded
        }
        return renewWeb()
    }

    /**
     * 节流判定 + 打点（原子）。
     *
     * `compareAndSet` 保证并发调用里**只有一个**线程拿到「本次该检查」的权利，
     * 其余直接返回 false —— 否则两个线程会各发一轮 4 个请求。
     */
    private fun shouldCheckNow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val last = lastCheckElapsed.get()
            if (last > 0L && now - last < RENEW_INTERVAL_MS) return false
            if (lastCheckElapsed.compareAndSet(last, now)) return true
        }
    }

    /**
     * **唯一**的 web 续期实现（v5.3.1）。所有入口都必须调它，不许再各写一遍。
     *
     * # 完整链路（每一步失败都有独立结局）
     * 1. `GET passport.bilibili.com/x/passport-login/web/cookie/info`
     *    → 拿 `data.refresh`（要不要续）与 `data.timestamp`（算 correspondPath 用）
     * 2. RSA-OAEP(SHA-256) 加密 `refresh_<timestamp>` → hex = `correspondPath`
     * 3. `GET www.bilibili.com/correspond/1/<correspondPath>` → 解析 `#1-name` = `refresh_csrf`
     * 4. `POST passport.bilibili.com/x/passport-login/web/cookie/refresh`
     *    → 回 `data.refresh_token`（新的），且响应头 `Set-Cookie` 带新的 SESSDATA/bili_jct
     *    （新 Cookie 由 `NetWorkUtil` 的 `CookieSaveInterceptor` 自动落库）
     * 5. `POST .../cookie/confirm/refresh` 让**旧** Cookie 立即失效
     * 6. 把新凭据**回写**账号槽（[AccountStore.persistActiveWeb]）
     *
     * # 为什么第 6 步必须在这里做（根因 2）
     * 见文件头注释：`CookieRefreshApi.checkAndRefresh()` 与
     * `RecommendActivity.checkCookieRefresh()` 都漏了这一步，
     * 而它们恰恰是「启动时」和「进推荐页时」两条最常走的路径。
     * 放在唯一入口里，调用方**不可能**再漏。
     *
     * @return 结构化结果，见 [RenewResult]
     */
    @JvmStatic
    fun renewWeb(): RenewResult {
        return try {
            doRenewWeb()
        } catch (e: JSONException) {
            // 回包结构异常（最常见的原因是 web Cookie 已经彻底失效：
            // `cookie/info` 回 -101 且没有 data，`getJSONObject("data")` 抛 JSONException）。
            //
            // v5.3.1：这种情况**也要试一次 APP 端口回退** —— 用户的 web Cookie 烂掉了，
            // 但 HD/TV 端口可能还活着，借它们的 refresh_token 能一次性把 web 救回来
            // （实测可行）。不试的话，用户就只能重新扫码。
            //
            // 注意仍然**不清登录态**：不能因为一次解析失败就把用户踢出去。
            Logu.w(TAG, "续期回包结构异常：" + e.javaClass.simpleName + "，尝试 APP 端口回退")
            renewViaAppToken("web Cookie 可能已失效")
        } catch (e: IOException) {
            // 网络异常：登录态没坏，下次再试
            Logu.w(TAG, "续期网络异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError(e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            // RSA / 解析等运行期异常：同样不能清登录态
            Logu.w(TAG, "续期异常：" + e.javaClass.simpleName)
            RenewResult.NetworkError(e.javaClass.simpleName)
        }
    }

    /** [renewWeb] 的本体（拆出来控制圈复杂度，detekt ComplexMethod） */
    @Throws(IOException::class, JSONException::class)
    private fun doRenewWeb(): RenewResult {
        val info = cookieInfo()
        if (!info.optBoolean("refresh", false)) {
            Logu.d(TAG, "服务端表示当前 Cookie 不需要续期")
            return RenewResult.NotNeeded
        }

        // 根因 1 的修复点：**发请求之前**就判断，缺 token 时一次网络都不用发。
        // 旧代码在这里会白跑 3 个请求，最后拿到 -400，还把原因吞掉。
        val refreshToken = SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "") ?: ""
        if (refreshToken.isEmpty()) {
            // v5.3.1：没有 web refresh_token 不再等于「只能重新扫码」——
            // 借已登录 APP 端口的 refresh_token 仍能换回一整套新 web Cookie（实测可行，
            // 见 AppTokenRefreshApi 的类注释）。这是本机凭据（web 无 token）唯一能救的路。
            Logu.d(TAG, "web 没有 refresh_token，改走 APP 端口回退")
            return renewViaAppToken("web 缺 refresh_token")
        }

        val correspondPath = getCorrespondPath(info.optLong("timestamp", 0L))
        if (correspondPath.isEmpty()) {
            Logu.e(TAG, "拿不到 correspondPath（RSA 加密失败）")
            return RenewResult.NetworkError("correspondPath")
        }

        val refreshCsrf = getRefreshCsrf(correspondPath)
        if (refreshCsrf.isEmpty()) {
            // 拿不到 refresh_csrf 通常意味着 Cookie 已不完整（缺 SESSDATA / bili_jct）
            Logu.e(TAG, "拿不到 refresh_csrf（Cookie 可能不完整）")
            return RenewResult.ServerError(-1, "refresh_csrf 为空")
        }

        return submitRefresh(refreshCsrf, refreshToken)
    }

    /**
     * 真正发 `cookie/refresh` 并按结果分支。
     *
     * 单独拆出来是因为它已经有 3 个出口（成功 / token 过期 / 其它服务端错误），
     * 和上面的前置检查混在一起会超 detekt 的复杂度阈值。
     */
    @Throws(IOException::class, JSONException::class)
    private fun submitRefresh(refreshCsrf: String, refreshToken: String): RenewResult {
        val csrf = NetWorkUtil.getInfoFromCookie(
            "bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
        val body = "csrf=" + csrf + "&refresh_csrf=" + refreshCsrf +
                "&source=main_web&refresh_token=" + refreshToken
        val response = NetWorkUtil.post(
            "https://passport.bilibili.com/x/passport-login/web/cookie/refresh",
            body, NetWorkUtil.webHeaders)
        val text = response.body?.string() ?: ""
        val result = JSONObject(text) // quality-allow：只读 code/refresh_token 两个字段，且这就是本对象职责
        val code = result.optInt("code", -1)

        if (code == CODE_REFRESH_TOKEN_EXPIRED) {
            // 86032：web 自己的 refresh_token 过期。
            // v5.3.1：**不再直接判死刑** —— 先借 APP 端口的 refresh_token 试一次
            // （实测该路径能换回全新 web Cookie）。只有回退也失败才真的需要重新扫码。
            Logu.d(TAG, "web refresh_token 已过期（86032），改走 APP 端口回退")
            return renewViaAppToken("web refresh_token 过期")
        }
        if (code != 0) {
            // -400（refresh_token 为空/格式错）、-101（未登录）、-352（风控）等
            val msg = result.optString("message", "")
            Logu.e(TAG, "续期被服务端拒绝：code=" + code + " msg=" + msg)
            return RenewResult.ServerError(code, msg)
        }

        val newRefreshToken = result.optJSONObject("data")?.optString("refresh_token", "") ?: ""
        if (newRefreshToken.isEmpty()) {
            // code=0 但没有新 token：协议异常，按服务端错误处理（不能当成功）
            Logu.e(TAG, "续期回包 code=0 但缺少新 refresh_token")
            return RenewResult.ServerError(0, "回包缺少 refresh_token")
        }

        // 第 5 步：让**旧**的 refresh_token 失效。
        // ⚠ 顺序陷阱（v5.3.1 修）：confirm 接口要的正是**旧** token，而下面就要把
        // refresh_token 覆盖成新的。所以 confirm **必须排在写新值之前** ——
        // 写反了的话 confirm 会拿着刚写进去的新 token 去注销，服务端回 -400，
        // 旧 token 留在服务端继续可用（不致命，但语义就错了）。
        // 失败**不回滚**：新 Cookie / 新 token 已经生效，回滚反而会把用户踢下线。
        confirmRefresh(refreshToken)

        // 新 Cookie（SESSDATA / bili_jct 等）已由 CookieSaveInterceptor 落库；
        // 新 refresh_token 还没人写过，这里补上。
        // **绝不打印 token 内容**（v5.3.0 曾把新 token 原样打进日志，已删）
        SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, newRefreshToken)

        // 第 6 步（根因 2 的修复点）：新凭据回写账号槽。缺了这一步，切号/重启就丢。
        persistAfterRenew()

        Logu.d(TAG, "web 凭据续期成功（已回写账号槽）")
        return RenewResult.Success
    }

    /**
     * **借 APP 端口的 refresh_token 给 web 换一整套新 Cookie**（v5.3.1 的核心修复）。
     *
     * # 为什么需要它（这是「web 续期无法使用」的真正解法）
     * web 自身的续期协议有两个死穴，实测都无法绕过：
     * 1. 没有 `refresh_token` 时服务端直接 `-400`，连「跳过」都不给；
     * 2. 扫码登录回包里 `refresh_token` 是**可选**字段，历史凭据/导入的凭据普遍没有它；
     * 3. 想重新拿一个 web `refresh_token` 也做不到 —— `sso/list` 实测返回**空数组**，
     *    没有任何「用现有 Cookie 换新 refresh_token」的端点。
     *
     * 但实测发现一条可用的路：**APP 端口的 `oauth2/refresh_token` 在续 APP token 时，
     * 会一并下发一整套全新的 web Cookie**（SESSDATA / bili_jct / DedeUserID / sid…）。
     * 完整证据见 [AppTokenRefreshApi] 的类注释（含 nav 校验、csrf 校验、连续 3 轮链式刷新）。
     *
     * # 拿到新 Cookie 之后做什么
     * 1. 写进激活槽位（`SharedPreferencesUtil.cookies` / `csrf` / `mid`）——
     *    这样**当前正在跑的进程**立刻用上新凭据；
     * 2. `NetWorkUtil.refreshHeaders()` 让共享请求头的 Cookie 项同步；
     * 3. 回写账号槽 + 存一份凭据（[persistAfterRenew]），切号/重启不丢；
     * 4. 顺带把该 APP 端口自己的新 token 写回它自己的槽位
     *    （服务端会一起轮换，不写回去下次那个端口就续不动了）。
     *
     * # 安全性
     * - 换回来的**就是 web Cookie**，之后所有 web 请求仍然只走 web 端口，没有「偷偷换端口」；
     * - 凭据来源是同一个 mid（实测回包 `token_info.mid` 与 web `DedeUserID` 一致）；
     * - 全程不打印任何 token / Cookie 内容。
     *
     * @param reason 仅用于日志，说明是哪条路径触发的回退
     */
    private fun renewViaAppToken(reason: String): RenewResult {
        return when (val result = AppTokenRefreshApi.refreshWebCookiesFromAppToken()) {
            is AppTokenRefreshApi.Result.Success -> applyAppTokenCookies(result, reason)
            is AppTokenRefreshApi.Result.NoAppIdentity -> {
                Logu.e(TAG, reason + "：且没有可借的 APP 端口 token，只能重新扫码登录")
                RenewResult.NoRefreshToken
            }
            is AppTokenRefreshApi.Result.Rejected -> {
                Logu.e(TAG, reason + "：APP 端口回退也被拒（code=" + result.lastCode + "）")
                // 86032 / 86033 这类都指向「凭据彻底失效」，与网络失败区分开
                if (result.lastCode == CODE_REFRESH_TOKEN_EXPIRED ||
                    result.lastCode == CODE_APP_ID_MISMATCH) {
                    RenewResult.TokenExpired
                } else {
                    RenewResult.ServerError(result.lastCode, result.lastMessage)
                }
            }
            is AppTokenRefreshApi.Result.NetworkError -> {
                Logu.w(TAG, reason + "：APP 端口回退网络异常")
                RenewResult.NetworkError(result.detail)
            }
        }
    }

    /**
     * 把 [AppTokenRefreshApi] 换回来的新 Cookie 落到所有该落的地方。
     *
     * 顺序有讲究：**先写偏好 → 再刷请求头 → 最后回写账号槽**。
     * 反过来的话 `persistAfterRenew()` 会把旧账号槽内容当成「当前凭据」再存一遍。
     */
    private fun applyAppTokenCookies(
        result: AppTokenRefreshApi.Result.Success,
        reason: String
    ): RenewResult {
        val newCookies = result.cookies
        val newCsrf = NetWorkUtil.getInfoFromCookie("bili_jct", newCookies)
        val newMid = NetWorkUtil.getInfoFromCookie("DedeUserID", newCookies).trim().toLongOrNull() ?: 0L

        // 激活槽位（当前进程立刻生效）
        SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, newCookies)
        if (newCsrf.isNotEmpty()) SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, newCsrf)
        if (newMid > 0L) SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, newMid)
        SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.cookie_refresh, true)
        NetWorkUtil.refreshHeaders()

        // 该 APP 端口自己的新 token 写回它自己的槽位：
        // 服务端每次刷新都会轮换 access/refresh，不写回去下次这个端口就续不动了。
        persistAppIdentityTokens(result)

        // 账号槽 + 多凭据
        persistAfterRenew()

        Logu.d(TAG, "已借 " + result.identityId + " 端口的 refresh_token 续上 web 凭据（" + reason + "）")
        return RenewResult.Success
    }

    /** 把 APP 端口轮换后的新 token 写回该身份槽位（不打印内容） */
    private fun persistAppIdentityTokens(result: AppTokenRefreshApi.Result.Success) {
        try {
            if (result.accessToken.isNotEmpty()) {
                SharedPreferencesUtil.putString(
                    "app_rcmd_" + result.identityId + "_access_token", result.accessToken)
            }
            if (result.refreshToken.isNotEmpty()) {
                SharedPreferencesUtil.putString(
                    "app_rcmd_" + result.identityId + "_refresh_token", result.refreshToken)
            }
        } catch (e: Exception) {
            Logu.w(TAG, "写回 APP 端口 token 失败：" + e.javaClass.simpleName)
        }
    }

    /**
     * 让**旧**的 refresh_token 立即失效（失败只记日志，不回滚，理由见 [submitRefresh]）。
     *
     * @param oldToken 续期**前**那个 refresh_token。必须由调用方显式传入，
     *   不能在这里读偏好 —— 读到时它可能已经被换成新值了（顺序陷阱见 [submitRefresh]）。
     */
    private fun confirmRefresh(oldToken: String) {
        try {
            val cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
            val csrf = NetWorkUtil.getInfoFromCookie("bili_jct", cookies)
            val code = JSONObject(
                NetWorkUtil.post(
                    "https://passport.bilibili.com/x/passport-login/web/confirm/refresh",
                    "csrf=" + csrf + "&refresh_token=" + oldToken,
                    NetWorkUtil.webHeaders
                ).body?.string() ?: ""
            ).optInt("code", -1) // quality-allow：同 submitRefresh，只读 code
            if (code != 0) Logu.w(TAG, "确认刷新返回：" + code + "（不影响本次续期结果）")
        } catch (e: Exception) {
            Logu.w(TAG, "确认刷新失败：" + e.javaClass.simpleName + "（不影响本次续期结果）")
        }
    }

    /**
     * 续期成功后把新凭据回写账号槽。
     *
     * # 为什么还要动「多凭据」那一层
     * v5.3.0 起同一个 (端口, 账号) 可以并存多份凭据（[com.RobinNotBad.BiliClient.util.AccountCredentialStore]）。
     * 续期换新的是**当前正在用的那一份**，所以：
     * 1. `persistActiveWeb()` 把激活槽位刷进账号槽（`account_mgmt_web_<mid>_*`）；
     * 2. `saveCurrent()` 让凭据层把账号槽的新内容同步进「当前激活凭据」。
     *
     * 为什么用 `saveCurrent` 而不是「直接改激活凭据的字段」：`saveCurrent` 内部有
     * 「内容与默认凭据相同时复用 C_DEFAULT、否则新建一条」的去重逻辑。
     * 续期后的内容是**新**的（Cookie 变了），正常情况下会走「新建一条」分支 ——
     * 于是用户会看到凭据列表里多出一条「续期 mm-dd HH:mm」。
     * 这正是想要的语义：**旧凭据仍然保留可切回**（万一续期后的 Cookie 反而有问题），
     * 而不是被就地覆盖。用户随时可以在账号管理里切回旧的那份。
     */
    private fun persistAfterRenew() {
        try {
            AccountStore.persistActiveWeb()
            val mid = AccountStore.activeMid(AccountStore.PORT_WEB)
            if (mid > 0L) {
                com.RobinNotBad.BiliClient.util.AccountCredentialStore
                    .saveCurrent(AccountStore.PORT_WEB, mid, "续期 " + shortNow())
            }
        } catch (e: Exception) {
            // 回写失败不影响「本次续期已经生效」这个事实：内存里的 Cookie 与 token 都是新的，
            // 只是持久化层没跟上。下次前台续期会再刷一次。
            Logu.w(TAG, "续期回写账号槽失败：" + e.javaClass.simpleName)
        }
    }

    /** `MM-dd HH:mm` 当前时刻（凭据默认名用，与 AccountStore.shortNow 口径一致） */
    private fun shortNow(): String {
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    /**
     * 是否需要续期（只问服务端，不执行续期）。
     *
     * 保留给「账号校验」类调用方：校验登录态要用 `nav` 的 `isLogin`（见 CredentialStore），
     * 而 `cookie/info` 只回答「要不要续」，**不含 `isLogin`**（实测）。
     *
     * @throws IOException 网络异常
     * @throws JSONException 回包结构异常
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun cookieInfo(): JSONObject {
        val url = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        val result = NetWorkUtil.getJson(url)
        return result.getJSONObject("data")
    }

    /** 兼容旧调用方：只要布尔结果的续期（内部转调 [renewWeb]） */
    @JvmStatic
    fun checkAndRefresh(): Boolean = renewWeb().renewed()

    @JvmStatic
    fun getCorrespondPath(timestamp: Long): String { //时间戳要精准到毫秒！
        /*
        https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E7%94%9F%E6%88%90correspondpath%E7%AE%97%E6%B3%95
         */
        try {
            val publicKeyPEM = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB"
            val x509EncodedKeySpec = X509EncodedKeySpec(Base64.decode(publicKeyPEM, Base64.DEFAULT))
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(x509EncodedKeySpec)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            val oaepParameterSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParameterSpec)
            val data = "refresh_$timestamp"
            return base16Encode(cipher.doFinal(data.toByteArray()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    @Throws(IOException::class)
    @JvmStatic
    fun getRefreshCsrf(CorrespondPath: String): String {
        if (Objects.equals(CorrespondPath, "")) return ""
        val url = "https://www.bilibili.com/correspond/1/" + CorrespondPath
        val response = NetWorkUtil.get(url)
        val body = response.body ?: return ""
        val document = Jsoup.parse(body.string())
        if (document.select("#1-name").size > 0)
            return document.select("#1-name").get(0).text()
        return ""
    }

    /**
     * 只做「刷新 + 确认」两步，**不做前置检查**（旧签名，保留给需要精细控制的调用方）。
     *
     * v5.3.1 起**不推荐**直接调它：它不会检查 refresh_token 是否存在、
     * 也不会在成功后回写账号槽。新代码请统一用 [renewWeb]。
     * 保留它只是为了不让历史调用点编译失败（目前只有 `AuthChannel` 的旧路径）。
     *
     * @return 是否刷新成功
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun refreshCookie(RefreshCsrf: String): Boolean {
        if (Objects.equals(RefreshCsrf, "")) return false
        val token = SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "") ?: ""
        if (token.isEmpty()) return false
        val result = submitRefresh(RefreshCsrf, token)
        return result.renewed()
    }

    @JvmStatic
    fun base16Encode(src: ByteArray): String {
        val strbuf = StringBuilder(src.size * 2)

        for (i in src.indices) {
            if ((src[i].toInt() and 0xff) < 0x10)
                strbuf.append("0")

            strbuf.append(java.lang.Long.toString((src[i].toInt() and 0xff).toLong(), 16))
        }

        return strbuf.toString()
    }
}
