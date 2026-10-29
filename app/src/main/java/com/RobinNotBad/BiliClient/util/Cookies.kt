package com.RobinNotBad.BiliClient.util

import androidx.annotation.NonNull
import java.util.HashMap

class Cookies(cookieString: String) {
    private val cookieMap: MutableMap<String, String> = HashMap()

    init {
        parseCookieString(cookieString)
    }

    /**
     * 解析 "k=v; k2=v2" 形式的 Cookie 串。
     *
     * v5.0.2-alpha10：仍然用 Kotlin 的 split(...) 解析（保证「分段 + 段数判定」语义与原来逐字一致，
     * 包括 "k=" 这种在 split 后只有 1 段而被跳过的边界行为），只去掉了 `.toTypedArray()` ——
     * 那是把 split 的 List 再拷一份成数组，对判断和取值都没有意义，纯属多余分配。
     * `Cookies(...)` 在 NetWorkUtil.getCookies() / putCookie() 里是每次新建快照，所以这个分配频率不低。
     */
    private fun parseCookieString(cookieString: String) {
        cookieMap.clear()
        for (cookie in cookieString.split("; ")) {
            val parts = cookie.split("=")
            if (parts.size == 2) {
                cookieMap[parts[0]] = parts[1]
            }
        }
    }

    operator fun set(key: String, value: String) {
        cookieMap[key] = value
    }

    operator fun get(key: String): String? {
        return cookieMap[key]
    }

    fun getOrDefault(key: String, defaultVal: String): String {
        val value = cookieMap[key]
        return value ?: defaultVal
    }

    fun containsKey(key: String): Boolean {
        return cookieMap.containsKey(key)
    }

    @NonNull
    override fun toString(): String {
        // v5.0.2-alpha10：按 map 大小预估容量，避免 StringBuilder 反复扩容（输出与原来逐字相同）
        val sb = StringBuilder(cookieMap.size * 16)
        for ((key, value) in cookieMap) {
            sb.append(key).append("=").append(value).append("; ")
        }
        return sb.toString()
    }
}
