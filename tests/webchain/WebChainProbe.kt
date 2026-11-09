import com.RobinNotBad.BiliClient.api.CookieRefreshApi
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil as Prefs
import com.RobinNotBad.BiliClient.util.WebRenewalState as State
import android.content.MemoryPreferences
import org.json.JSONObject // quality-allow：探针必须复用生产 CookieRefreshApi 的 requestJson（它返回 org.json.JSONObject），不是新写解析

/**
 * 真实网络链探针：**直接跑生产源码**（CookieRefreshApi / WebRenewalState / AccountStore）。
 *
 * 目的：把「B 站协议坏」与「本项目客户端写错」彻底区分开。
 * 只输出 code / 字段名 / 长度，**绝不输出任何凭据值**。
 *
 * 运行方式见同目录 run_probe.sh（凭据经环境变量传入，不落磁盘）。
 */
private fun cookieValue(cookies: String, name: String): String =
    cookies.split(';').map { it.trim().split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == name }?.get(1) ?: ""

private fun invokePrivate(name: String, vararg a: Any?): Any? {
    val types = a.map {
        when (it) {
            is String -> String::class.java
            is State.Snapshot -> State.Snapshot::class.java
            else -> Any::class.java
        }
    }.toTypedArray()
    val m = CookieRefreshApi.javaClass.getDeclaredMethod(name, *types)
    m.isAccessible = true
    return m.invoke(CookieRefreshApi, *a)
}

fun main() {
    val cookies = System.getenv("WEB_COOKIES") ?: error("WEB_COOKIES 未设置")
    val refreshToken = System.getenv("WEB_REFRESH_TOKEN") ?: ""
    val mid = cookieValue(cookies, "DedeUserID").toLongOrNull() ?: 0L

    println("== 生产代码真实链路探针（只打 code/字段名/长度）==")
    println("mid>0 = ${mid > 0}, cookies len=${cookies.length}, refresh_token len=${refreshToken.length}")

    Prefs.sharedPreferences = MemoryPreferences()
    Prefs.putBoolean(Prefs.ACCOUNT_MGMT_MIGRATED, true)
    AccountStore.loginWeb(mid, cookies, cookieValue(cookies, "bili_jct"), refreshToken, "probe", null)

    val snap = State.capture()
    println("snapshot: mid>0=${snap.mid > 0}, cid非空=${snap.credentialId.isNotEmpty()}, " +
            "cookies len=${snap.cookies.length}, token len=${snap.refreshToken.length}")

    // ① 生产 requestJson → cookie/info
    val info = invokePrivate("requestJson",
        "https://passport.bilibili.com/x/passport-login/web/cookie/info", snap.cookies) as JSONObject
    val data = info.optJSONObject("data")
    val keys = data?.keys()?.asSequence()?.toList() ?: emptyList()
    println("① cookie/info code=${info.optInt("code")} data.keys=$keys")
    val ts = data?.optLong("timestamp", 0L) ?: 0L
    println("   timestamp>0=${ts > 0} refresh=${data?.optBoolean("refresh")}")

    // ② 生产 getCorrespondPath（真实 RSA-OAEP-SHA256 + base16）
    val path = CookieRefreshApi.getCorrespondPath(ts)
    println("② getCorrespondPath len=${path.length} (期望 256)")

    // ③ 生产 refreshCsrf（private，真发 correspond 请求 + Jsoup 解析 #1-name）
    val csrf = invokePrivate("refreshCsrf", path, snap.cookies) as String
    println("③ refreshCsrf len=${csrf.length} (期望 32；0 = 取不到 → 就是「无法取得 Web refresh_csrf」)")

    // ④ 生产 submitRefresh（真发 cookie/refresh）
    if (csrf.isEmpty()) {
        println("④ 跳过 cookie/refresh：csrf 为空")
    } else {
        val r = invokePrivate("submitRefresh", snap, csrf)
        val extra = when (r) {
            is CookieRefreshApi.RenewResult.ServerError -> " code=${r.code} msg=${r.message}"
            is CookieRefreshApi.RenewResult.NetworkError -> " detail=${r.detail}"
            else -> ""
        }
        println("④ submitRefresh → ${r?.javaClass?.simpleName}$extra")
    }

    // ⑤ 生产默认入口 renewWeb()（跨端开关关闭时必须纯自续）
    val beforeRefresh = NetWorkUtil.refreshCount
    val r2 = CookieRefreshApi.renewWeb()
    println("⑤ renewWeb() 默认入口 → ${r2.javaClass.simpleName}" +
            " (跨端开关=${CookieRefreshApi.isCrossPortRepairEnabled()}, refreshHeaders 次数增量=${NetWorkUtil.refreshCount - beforeRefresh})")
    println("   renewed()=${r2.renewed()}")

    // ⑥ 登录态是否仍有效（确认「不是登录过期」）
    val nav = invokePrivate("requestJson", "https://api.bilibili.com/x/web-interface/nav", snap.cookies) as JSONObject
    val navData = nav.optJSONObject("data")
    println("⑥ nav code=${nav.optInt("code")} isLogin=${navData?.optBoolean("isLogin")} " +
            "mid>0=${(navData?.optLong("mid", 0L) ?: 0L) > 0}")

    println("== done ==")
}
