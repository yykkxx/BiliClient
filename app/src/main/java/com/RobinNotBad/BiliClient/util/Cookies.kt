package com.RobinNotBad.BiliClient.util

import androidx.annotation.NonNull
import java.util.HashMap

class Cookies(cookieString: String) {
    private val cookieMap: MutableMap<String, String> = HashMap()

    init {
        parseCookieString(cookieString)
    }

    private fun parseCookieString(cookieString: String) {
        cookieMap.clear()
        val cookies = cookieString.split("; ").toTypedArray()
        for (cookie in cookies) {
            val parts = cookie.split("=").toTypedArray()
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
        val sb = StringBuilder()
        for ((key, value) in cookieMap) {
            sb.append(key).append("=").append(value).append("; ")
        }
        return sb.toString()
    }
}
