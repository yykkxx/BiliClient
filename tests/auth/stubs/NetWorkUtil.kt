package com.RobinNotBad.BiliClient.util
import okhttp3.Dns
import java.net.InetAddress
object NetWorkUtil {
    const val USER_AGENT_WEB = "fixture-web-ua"
    var refreshCount = 0
    var refreshHook: (() -> Unit)? = null
    class Inet4Selector : Dns {
        // Fail closed if the fake application interceptor is accidentally bypassed; no external traffic.
        override fun lookup(hostname: String): List<InetAddress> = throw java.net.UnknownHostException("external network forbidden in auth JVM suite")
    }
    fun getInfoFromCookie(name: String, cookie: String): String = cookie.split(';').map { it.trim().split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == name }?.get(1) ?: ""
    fun refreshHeaders() { refreshCount++; refreshHook?.invoke() }
}
object Logu {
    // Deliberately suppress all target logs, including exception messages and fixture credentials.
    fun d(tag: String, message: String) = Unit
    fun w(tag: String, message: String) = Unit
}
