package com.RobinNotBad.BiliClient.util

import okhttp3.Dns
import java.net.InetAddress

/**
 * 真实网络探针用的 NetWorkUtil stub。
 *
 * 与 `tests/auth/stubs` 的区别只有两处，且都是**为了让真实请求能发出去**：
 * 1. [Inet4Selector] 用真实 IPv4 解析（本机 `getaddrinfo(AF_UNSPEC)` 会失败，必须强制 AF_INET，
 *    这是 v5.3.0 起就记录在案的坑）；
 * 2. [USER_AGENT_WEB] 用与生产完全一致的真实 UA，否则 B 站可能返回不同的页面版本。
 *
 * 仍然**不**提供 CookieSaveInterceptor / CookieJar —— 续期客户端本来就不该有它们（这是安全边界）。
 */
object NetWorkUtil {
    const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Safari/537.36"

    var refreshCount = 0
    var refreshHook: (() -> Unit)? = null

    class Inet4Selector : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            InetAddress.getAllByName(hostname).filter { it is java.net.Inet4Address }.ifEmpty {
                // 本机 AF_UNSPEC 解析会失败/超时，拿不到 IPv4 时直接失败，不要回退到 IPv6 干等
                throw java.net.UnknownHostException("no IPv4 address for $hostname")
            }
    }

    fun getInfoFromCookie(name: String, cookie: String): String =
        cookie.split(';').map { it.trim().split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }?.get(1) ?: ""

    fun refreshHeaders() {
        refreshCount++
        refreshHook?.invoke()
    }
}

/**
 * 探针专用日志：**只允许打印长度/字段名**。
 *
 * 这里不做「自动脱敏字符串」的黑魔法 —— 因为探针里我明确只传长度和字段名。
 * 生产代码的 Logu 在 `tests/auth/stubs` 里是全静音的，那套仍用于断言型回归。
 */
object Logu {
    val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    fun d(tag: String, message: String) { lines.add("D/$tag $message") }
    fun w(tag: String, message: String) { lines.add("W/$tag $message") }
}
