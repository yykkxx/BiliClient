import android.content.MemoryPreferences
import com.RobinNotBad.BiliClient.api.AppTokenRefreshApi
import com.RobinNotBad.BiliClient.api.CookieRefreshApi
import com.RobinNotBad.BiliClient.util.AccountCredentialStore
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil as Prefs
import com.RobinNotBad.BiliClient.util.WebRenewalState as State
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** No production source copying/rewriting: run_jvm.sh compiles repository sources directly. */
private object Suite {
    private var passed = 0
    private var failed = 0
    val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
    var responder: (Request) -> Response = { response(it, "{\"code\":-400}") }
    private val fakeClient = OkHttpClient.Builder().dns(NetWorkUtil.Inet4Selector())
        .addInterceptor(Interceptor { chain ->
            requests.add(chain.request())
            responder(chain.request())
        }).build()

    fun response(request: Request, body: String, status: Int = 200, cookies: Map<String, String> = emptyMap()): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(status).message("fixture").body(body.toResponseBody())
        for ((name, value) in cookies) builder.addHeader("Set-Cookie", "$name=$value; Domain=.bilibili.com; Path=/; Secure")
        return builder.build()
    }

    private fun injectClient(target: Any) {
        val field = target.javaClass.getDeclaredField("client\$delegate").apply { isAccessible = true }
        val lazy = field.get(target) as Lazy<*>
        val original = lazy.value as OkHttpClient
        check(original.interceptors.isEmpty() && original.networkInterceptors.isEmpty())
        check(original.cookieJar === okhttp3.CookieJar.NO_COOKIES)
        check(!original.followRedirects && !original.retryOnConnectionFailure)
        lazy.javaClass.getDeclaredField("_value").apply { isAccessible = true }.set(lazy, fakeClient)
    }

    fun initialize() {
        injectClient(CookieRefreshApi)
        injectClient(AppTokenRefreshApi)
        println("PASS dedicated_clients_no_cookie_interceptor_no_cookiejar_no_redirect_no_retry")
        passed++
    }

    fun reset() {
        Prefs.sharedPreferences = MemoryPreferences()
        Prefs.putBoolean(Prefs.ACCOUNT_MGMT_MIGRATED, true)
        NetWorkUtil.refreshHook = null
        NetWorkUtil.refreshCount = 0
        CookieRefreshApi.javaClass.getDeclaredField("lastCheckElapsed").apply { isAccessible = true }
            .get(CookieRefreshApi).let { (it as AtomicLong).set(0L) }
        requests.clear()
        responder = { response(it, "{\"code\":-400}") }
    }

    fun test(name: String, body: () -> Unit) {
        reset()
        try { body(); passed++; println("PASS $name") }
        catch (error: Throwable) {
            failed++
            // Never print exception messages, response bodies, request bodies, or credential values.
            println("FAIL $name [${error.javaClass.simpleName}]")
        } finally { NetWorkUtil.refreshHook = null }
    }

    fun finish() {
        println("SUMMARY passed=$passed failed=$failed")
        if (failed != 0) kotlin.system.exitProcess(1)
    }
}

private fun cookies(mid: Long, version: String = "old") = "DedeUserID=$mid; SESSDATA=fixture-$version-session; bili_jct=fixture-$version-csrf"
private fun loginWeb(mid: Long = 1L, token: String = "fixture-web-old") =
    AccountStore.loginWeb(mid, cookies(mid), "fixture-old-csrf", token, "fixture-user", "fixture-face")
private fun loginApp(port: String = AccountStore.PORT_HD, mid: Long = 1L) =
    AccountStore.loginApp(port, mid, "fixture-app-old-access", "fixture-app-old-refresh", cookies(mid), "fixture-user", "fixture-face")
private fun value(key: String) = Prefs.getString(key, "") ?: ""
private fun fields(port: String, mid: Long = 1L) = AccountStore.credentialFields(port, mid)
private fun activeFields(port: String, mid: Long = 1L) =
    AccountCredentialStore.fieldsOf(port, mid, AccountCredentialStore.activeId(port, mid))
private fun oauthBody(mid: Long = 1L, cookieMid: Long = mid): String = """{"code":0,"data":{"token_info":{"mid":$mid,"access_token":"fixture-app-new-access","refresh_token":"fixture-app-new-refresh"},"cookie_info":{"cookies":[{"name":"DedeUserID","value":"$cookieMid"},{"name":"SESSDATA","value":"fixture-new-session"},{"name":"bili_jct","value":"fixture-new-csrf"}]}}}"""
private fun appResult(mid: Long = 1L): AppTokenRefreshApi.Result.Success {
    Suite.responder = { Suite.response(it, oauthBody(mid)) }
    return AppTokenRefreshApi.refreshWebCookiesFromAppToken(mid) as AppTokenRefreshApi.Result.Success
}
private fun handshake(beforeRefresh: (() -> Unit)? = null, returnedMid: Long = 1L) {
    Suite.responder = { request ->
        when {
            request.url.encodedPath.endsWith("cookie/info") -> Suite.response(request, "{\"code\":0,\"data\":{\"refresh\":false,\"timestamp\":1794130000000}}")
            request.url.encodedPath.startsWith("/correspond/") -> Suite.response(request, "<div id=\"1-name\">fixture-refresh-csrf</div>")
            request.url.encodedPath.endsWith("cookie/refresh") -> {
                beforeRefresh?.invoke()
                Suite.response(request, "{\"code\":0,\"data\":{\"refresh_token\":\"fixture-web-new\"}}",
                    cookies = mapOf("DedeUserID" to returnedMid.toString(), "SESSDATA" to "fixture-new-session", "bili_jct" to "fixture-new-csrf"))
            }
            request.url.encodedPath.endsWith("confirm/refresh") -> Suite.response(request, "{\"code\":0}")
            else -> error("unexpected fixture path")
        }
    }
}
private fun checkCurrentUnchanged(old: State.Snapshot) {
    check(State.isCurrent(old))
    check(value(Prefs.cookies) == old.cookies)
}
private fun await(latch: CountDownLatch) = check(latch.await(5, TimeUnit.SECONDS))

fun main() {
    Suite.initialize()
    Suite.test("default_false_missing_web_token_no_cross_no_network") {
        loginWeb(token = ""); loginApp()
        check(!CookieRefreshApi.isCrossPortRepairEnabled())
        check(CookieRefreshApi.renewWeb() is CookieRefreshApi.RenewResult.NoRefreshToken)
        check(Suite.requests.isEmpty())
    }
    Suite.test("api_fallback_switch_does_not_authorize_cross_repair") {
        loginWeb(token = ""); loginApp(); Prefs.putBoolean(Prefs.API_WEB_FALLBACK, true)
        check(CookieRefreshApi.renewWeb() is CookieRefreshApi.RenewResult.NoRefreshToken)
        check(Suite.requests.isEmpty())
    }
    Suite.test("explicit_false_overrides_enabled_cross_switch") {
        loginWeb(token = ""); loginApp(); Prefs.putBoolean(Prefs.WEB_CROSS_PORT_REPAIR, true)
        check(CookieRefreshApi.renewWeb(false) is CookieRefreshApi.RenewResult.NoRefreshToken)
        check(Suite.requests.isEmpty())
    }
    for (code in listOf(86032, -101, -400)) Suite.test("allow_false_rejection_${code}_no_oauth") {
        loginWeb(); loginApp(); Suite.responder = { Suite.response(it, "{\"code\":$code}") }
        check(!CookieRefreshApi.renewWeb(false).renewed())
        check(Suite.requests.size == 1 && Suite.requests.none { it.url.encodedPath.contains("oauth2") })
    }
    Suite.test("allow_true_ordinary_error_cannot_cross") {
        loginWeb(); loginApp()
        check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.ServerError)
        check(Suite.requests.size == 1)
    }
    for (code in listOf(86032, -101)) Suite.test("explicit_allow_eligible_${code}_crosses_same_mid") {
        loginWeb(); loginApp()
        Suite.responder = { request -> Suite.response(request,
            if (request.url.encodedPath.contains("oauth2")) oauthBody() else "{\"code\":$code}") }
        check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.CrossPortSuccess)
        check(Suite.requests.size == 2)
        check(value(Prefs.refresh_token).isEmpty())
    }
    Suite.test("response_after_switch_never_starts_oauth") {
        loginWeb(2L); loginWeb(1L); loginApp()
        Suite.responder = { request ->
            if (request.url.encodedPath.contains("oauth2")) Suite.response(request, oauthBody())
            else {
                AccountStore.setActive(AccountStore.PORT_WEB, 2L)
                Suite.response(request, "{\"code\":-101}")
            }
        }
        check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.AccountChanged)
        check(Suite.requests.size == 1 && Suite.requests.none { it.url.encodedPath.contains("oauth2") })
    }
    Suite.test("logged_out_allow_true_has_no_network") {
        loginApp()
        check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.NotNeeded)
        check(Suite.requests.isEmpty())
    }
    Suite.test("network_failure_does_not_cross_and_inflight_releases") {
        loginWeb(); loginApp(); val old = State.capture()
        Suite.responder = { throw IOException("fixture failure") }
        check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.NetworkError)
        check(Suite.requests.size == 1); checkCurrentUnchanged(old)
        Suite.responder = { Suite.response(it, "{\"code\":-400}") }
        check(CookieRefreshApi.renewWeb(false) !is CookieRefreshApi.RenewResult.Busy)
    }
    Suite.test("malformed_response_does_not_cross") {
        loginWeb(); loginApp(); Suite.responder = { Suite.response(it, "not-json") }
        check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.NetworkError)
        check(Suite.requests.size == 1)
    }
    Suite.test("force_foreground_and_button_renew_even_when_refresh_false") {
        loginWeb(); val cid = AccountCredentialStore.activeId(AccountStore.PORT_WEB, 1L)
        handshake()
        check(CookieRefreshApi.renewIfNeededOnForeground(true) is CookieRefreshApi.RenewResult.Success)
        check(Suite.requests.size == 4)
        check(value(Prefs.refresh_token) == "fixture-web-new")
        check(fields(AccountStore.PORT_WEB) == activeFields(AccountStore.PORT_WEB))
        check(AccountCredentialStore.activeId(AccountStore.PORT_WEB, 1L) == cid)
        check(AccountCredentialStore.credentialCount(AccountStore.PORT_WEB, 1L) == 1)
        val confirm = Suite.requests.last()
        check(confirm.url.encodedPath.endsWith("confirm/refresh"))
        val form = confirm.body as FormBody
        check((0 until form.size).any { form.name(it) == "refresh_token" && form.value(it) == "fixture-web-old" })
        check(confirm.header("Cookie") == cookies(1L, "new"))
        val count = Suite.requests.size
        check(CookieRefreshApi.renewIfNeededOnForeground(false) is CookieRefreshApi.RenewResult.NotNeeded)
        check(Suite.requests.size == count)
        check(CookieRefreshApi.renewWeb(false) is CookieRefreshApi.RenewResult.Success)
    }
    Suite.test("confirm_failure_keeps_new_web_credentials") {
        loginWeb(); handshake(); val normal = Suite.responder
        Suite.responder = { if (it.url.encodedPath.endsWith("confirm/refresh")) throw IOException("fixture") else normal(it) }
        check(CookieRefreshApi.renewWeb(false) is CookieRefreshApi.RenewResult.Success)
        check(value(Prefs.refresh_token) == "fixture-web-new")
        check(fields(AccountStore.PORT_WEB) == activeFields(AccountStore.PORT_WEB))
    }
    Suite.test("server_returned_different_web_mid_rejected") {
        loginWeb(); val old = State.capture(); handshake(returnedMid = 2L)
        check(CookieRefreshApi.renewWeb(false) is CookieRefreshApi.RenewResult.ServerError)
        checkCurrentUnchanged(old)
        check(Suite.requests.none { it.url.encodedPath.endsWith("confirm/refresh") })
    }
    Suite.test("response_after_account_switch_does_not_touch_new_account_or_confirm") {
        loginWeb(2L); loginWeb(1L)
        handshake(beforeRefresh = { AccountStore.setActive(AccountStore.PORT_WEB, 2L) })
        check(CookieRefreshApi.renewWeb(false) is CookieRefreshApi.RenewResult.AccountChanged)
        check(AccountStore.activeMid(AccountStore.PORT_WEB) == 2L)
        check(value(Prefs.cookies) == cookies(2L))
        check(Suite.requests.none { it.url.encodedPath.endsWith("confirm/refresh") })
        check(Suite.requests.all { it.header("Cookie") == cookies(1L) })
    }
    Suite.test("busy_gate_prevents_parallel_renewal") {
        loginWeb(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Suite.responder = { entered.countDown(); await(release); Suite.response(it, "{\"code\":-400}") }
        val result = AtomicReference<CookieRefreshApi.RenewResult>()
        val worker = Thread { result.set(CookieRefreshApi.renewWeb(false)) }; worker.start(); await(entered)
        try { check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.Busy); check(Suite.requests.size == 1) }
        finally { release.countDown(); worker.join(5000) }
        check(!worker.isAlive && result.get() is CookieRefreshApi.RenewResult.ServerError)
    }
    Suite.test("authorized_snapshot_switch_before_io_rejects_without_network") {
        loginWeb(1L, ""); loginApp(mid = 1L); val consent = State.capture()
        loginWeb(2L, ""); loginApp(mid = 2L)
        check(CookieRefreshApi.renewWeb(true, consent) is CookieRefreshApi.RenewResult.AccountChanged)
        check(Suite.requests.isEmpty())
    }
    Suite.test("authorized_snapshot_changed_cookie_before_io_rejects_without_network") {
        loginWeb(); val consent = State.capture(); Prefs.putString(Prefs.cookies, cookies(1L, "changed"))
        check(CookieRefreshApi.renewWeb(true, consent) is CookieRefreshApi.RenewResult.AccountChanged)
        check(Suite.requests.isEmpty())
    }
    for (change in listOf("mid", "credential", "cookies", "token", "logout")) Suite.test("web_CAS_rejects_$change") {
        loginWeb(); val old = State.capture()
        when (change) {
            "mid" -> loginWeb(2L)
            "credential" -> {
                val cid = checkNotNull(AccountCredentialStore.importCredential(AccountStore.PORT_WEB, 1L, fields(AccountStore.PORT_WEB), "duplicate", 0L))
                check(AccountCredentialStore.setActive(AccountStore.PORT_WEB, 1L, cid))
            }
            "cookies" -> Prefs.putString(Prefs.cookies, cookies(1L, "changed"))
            "token" -> Prefs.putString(Prefs.refresh_token, "fixture-changed")
            "logout" -> AccountStore.logoutActive(AccountStore.PORT_WEB)
        }
        val current = State.capture()
        check(!State.apply(old, cookies(1L, "new"), "fixture-web-new"))
        checkCurrentUnchanged(current)
    }
    Suite.test("same_mid_explicit_cross_updates_only_borrower_web_token_empty_all_mirrors") {
        loginWeb(); loginApp(); loginApp(AccountStore.PORT_TV)
        val tv = fields(AccountStore.PORT_TV); val expected = State.capture(); val result = appResult()
        check(State.applyCrossPort(expected, result))
        check(value(Prefs.refresh_token).isEmpty())
        check(fields(AccountStore.PORT_WEB)["refresh_token"].isNullOrEmpty())
        check(activeFields(AccountStore.PORT_WEB)["refresh_token"].isNullOrEmpty())
        check(fields(AccountStore.PORT_HD)["refresh_token"] == "fixture-app-new-refresh")
        check(fields(AccountStore.PORT_HD) == activeFields(AccountStore.PORT_HD))
        check(tv == fields(AccountStore.PORT_TV))
        loginWeb(2L); check(AccountStore.setActive(AccountStore.PORT_WEB, 1L))
        check(value(Prefs.refresh_token).isEmpty())
    }
    Suite.test("enabled_switch_permits_real_cross_api_flow") {
        loginWeb(token = ""); loginApp(); Prefs.putBoolean(Prefs.WEB_CROSS_PORT_REPAIR, true)
        Suite.responder = { Suite.response(it, oauthBody()) }
        check(CookieRefreshApi.renewWeb() is CookieRefreshApi.RenewResult.CrossPortSuccess)
        check(Suite.requests.size == 1 && Suite.requests.single().header("Cookie") == null)
        check(value(Prefs.refresh_token).isEmpty())
    }
    Suite.test("different_mid_borrowers_never_sent_oauth") {
        loginWeb(1L, ""); loginApp(mid = 2L); loginApp(AccountStore.PORT_TV, 3L)
        check(CookieRefreshApi.renewWeb(true) is CookieRefreshApi.RenewResult.NoRefreshToken)
        check(Suite.requests.isEmpty())
    }
    for (mutation in listOf("mid", "token", "credential")) Suite.test("borrower_CAS_rejects_$mutation") {
        loginWeb(); loginApp(); val expected = State.capture(); val result = appResult()
        when (mutation) {
            "mid" -> loginApp(mid = 2L)
            "token" -> AccountStore.writeCredentialFields(AccountStore.PORT_HD, 1L, fields(AccountStore.PORT_HD) + ("refresh_token" to "fixture-changed"))
            "credential" -> {
                val cid = checkNotNull(AccountCredentialStore.importCredential(AccountStore.PORT_HD, 1L, fields(AccountStore.PORT_HD), "duplicate", 0L))
                check(AccountCredentialStore.setActive(AccountStore.PORT_HD, 1L, cid))
            }
        }
        check(!State.applyCrossPort(expected, result))
        checkCurrentUnchanged(expected)
    }
    Suite.test("cross_state_rejects_result_for_different_mid") {
        loginWeb(1L); loginApp(mid = 2L); val expected = State.capture(); val result = appResult(2L)
        check(!State.applyCrossPort(expected, result)); checkCurrentUnchanged(expected)
    }
    Suite.test("cross_state_rejects_android_port_forgery") {
        loginWeb(); loginApp(); loginApp(AccountStore.PORT_ANDROID)
        val expected = State.capture(); val result = appResult()
        result.javaClass.getDeclaredField("port").apply { isAccessible = true }.set(result, AccountStore.PORT_ANDROID)
        check(!State.applyCrossPort(expected, result)); checkCurrentUnchanged(expected)
    }
    for (mismatch in listOf("token-mid", "cookie-mid")) Suite.test("oauth_parse_rejects_$mismatch") {
        loginWeb(); loginApp(); val expected = State.capture()
        Suite.responder = { Suite.response(it, if (mismatch == "token-mid") oauthBody(2L, 1L) else oauthBody(1L, 2L)) }
        check(AppTokenRefreshApi.refreshWebCookiesFromAppToken(1L) is AppTokenRefreshApi.Result.Rejected)
        checkCurrentUnchanged(expected)
    }
    Suite.test("credential_switch_missing_tokens_clears_previous_pair") {
        loginWeb(); val cid = checkNotNull(AccountCredentialStore.importCredential(AccountStore.PORT_WEB, 1L,
            mapOf("cookies" to cookies(1L, "imported"), "csrf" to "fixture-imported-csrf"), "no-token", 0L))
        check(AccountCredentialStore.setActive(AccountStore.PORT_WEB, 1L, cid))
        check(value(Prefs.refresh_token).isEmpty() && fields(AccountStore.PORT_WEB)["refresh_token"].isNullOrEmpty())
    }
    Suite.test("app_relogin_without_refresh_does_not_mix_old_refresh") {
        loginApp()
        AccountStore.loginApp(AccountStore.PORT_HD, 1L, "fixture-second-access", "", "", null, null)
        check(fields(AccountStore.PORT_HD)["refresh_token"].isNullOrEmpty())
        check(fields(AccountStore.PORT_HD)["cookies"].isNullOrEmpty())
        check(activeFields(AccountStore.PORT_HD)["refresh_token"].isNullOrEmpty())
    }
    Suite.test("login_renewal_interleaving_preserves_previous_credential") {
        loginWeb(); val cid = AccountCredentialStore.activeId(AccountStore.PORT_WEB, 1L)
        val original = AccountCredentialStore.fieldsOf(AccountStore.PORT_WEB, 1L, cid)
        NetWorkUtil.refreshHook = {
            NetWorkUtil.refreshHook = null
            check(State.apply(State.capture(), cookies(1L, "renewed-during-login"), "fixture-third-web"))
        }
        AccountStore.loginWeb(1L, cookies(1L, "second-login"), "fixture-second-csrf", "fixture-second-web", null, null)
        check(AccountCredentialStore.fieldsOf(AccountStore.PORT_WEB, 1L, cid) == original)
    }
    Suite.test("setActive_logout_TOCTOU_does_not_activate_deleted_account") {
        loginWeb(1L); loginWeb(2L); check(AccountStore.setActive(AccountStore.PORT_WEB, 1L))
        val read = CountDownLatch(1); val release = CountDownLatch(1); val first = AtomicBoolean(true)
        val memory = Prefs.sharedPreferences as MemoryPreferences
        memory.afterStringRead = { key ->
            if (key == Prefs.ACCOUNT_MGMT_LIST_PREFIX + AccountStore.PORT_WEB && first.compareAndSet(true, false)) {
                read.countDown(); await(release)
            }
        }
        val result = AtomicReference<Boolean>(); val error = AtomicReference<Throwable>()
        val worker = Thread { try { result.set(AccountStore.setActive(AccountStore.PORT_WEB, 2L)) } catch (e: Throwable) { error.set(e) } }
        worker.start(); await(read)
        try { AccountStore.logout(AccountStore.PORT_WEB, 2L) }
        finally { release.countDown(); worker.join(5000); memory.afterStringRead = null }
        check(!worker.isAlive && error.get() == null)
        check(result.get() == false && AccountStore.activeMid(AccountStore.PORT_WEB) == 1L)
    }
    Suite.test("profile_update_between_capture_apply_preserves_account_profile_and_new_credentials") {
        loginWeb(); val expected = State.capture()
        AccountStore.updateProfile(AccountStore.PORT_WEB, 1L, "fixture-new-name", "fixture-new-face")
        check(State.apply(expected, cookies(1L, "new"), "fixture-web-new"))
        check(AccountStore.nameOf(AccountStore.PORT_WEB, 1L) == "fixture-new-name")
        check(AccountStore.faceOf(AccountStore.PORT_WEB, 1L) == "fixture-new-face")
        check(fields(AccountStore.PORT_WEB) == activeFields(AccountStore.PORT_WEB))
    }
    Suite.finish()
}
