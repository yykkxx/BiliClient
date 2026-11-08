package com.RobinNotBad.BiliClient.util

import okhttp3.Dns
import java.net.InetAddress

// Test-only stand-in for the project's real IPv4-only DNS rule. No network is contacted by tests.
class NetWorkUtil {
    class Inet4Selector : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).filter { it.address.size == 4 }
    }
}
