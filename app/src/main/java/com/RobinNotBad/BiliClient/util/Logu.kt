package com.RobinNotBad.BiliClient.util

/*
 * 未完工的log
 */

import android.util.Log

object Logu {
    @JvmField var LOGV_ENABLED = false
    @JvmField var LOGD_ENABLED = false
    @JvmField var LOGI_ENABLED = false

    @JvmStatic
    fun v(s: String?) {
        if (!LOGV_ENABLED) return
        Log.v(getCaller(), s ?: "")
    }

    @JvmStatic
    fun i(s: String?) {
        if (!LOGI_ENABLED) return
        Log.i(getCaller(), s ?: "")
    }

    @JvmStatic
    fun d(s: String?) {
        if (!LOGD_ENABLED) return
        Log.d(getCaller(), s ?: "")
    }

    @JvmStatic
    fun w(s: String?) {
        Log.w(getCaller(), s ?: "")
    }

    @JvmStatic
    fun e(s: String?) {
        Log.e(getCaller(), s ?: "")
    }

    @JvmStatic
    fun wtf(s: String?) {
        Log.wtf(getCaller(), s ?: "")
    }

    @JvmStatic
    fun v(tag: String?, info: String?) {
        if (!LOGV_ENABLED) return
        Log.v(getCaller(), "$tag>$info")
    }

    @JvmStatic
    fun i(tag: String?, info: String?) {
        if (!LOGI_ENABLED) return
        Log.i(getCaller(), "$tag>$info")
    }

    @JvmStatic
    fun d(tag: String?, info: String?) {
        if (!LOGD_ENABLED) return
        Log.d(getCaller(), "$tag>$info")
    }

    @JvmStatic
    fun w(tag: String?, info: String?) {
        Log.w(getCaller(), "$tag>$info")
    }

    @JvmStatic
    fun e(tag: String?, info: String?) {
        Log.e(getCaller(), "$tag>$info")
    }

    @JvmStatic
    fun wtf(tag: String?, info: String?) {
        Log.wtf(getCaller(), "$tag>$info")
    }

    private fun getCaller(): String {
        val caller = Thread.currentThread().stackTrace[4]
        val name = caller.className
        var index = name.length
        while (index > 1) {
            if (name[index - 1] == '.') break
            index--
        }
        return name.substring(index) + ">" + caller.methodName
    }
}
