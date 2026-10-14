package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.util.Log
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.R
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

//2023-07-25

object ToolsUtil {

    @JvmStatic
    fun dp2px(dpValue: Float): Int {
        val scale = BiliTerminal.context!!.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    @JvmStatic
    fun sp2px(spValue: Float): Int {
        val fontScale = BiliTerminal.context!!.resources.displayMetrics.scaledDensity
        return (spValue * fontScale + 0.5f).toInt()
    }

    @JvmStatic
    fun md5(plainText: String): String {
        val secretBytes: ByteArray
        try {
            val md = MessageDigest.getInstance("MD5")
            md.update(plainText.toByteArray())
            secretBytes = md.digest()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("没有md5这个算法！")
        }
        val md5code = StringBuilder(BigInteger(1, secretBytes).toString(16))
        for (i in 0 until 32 - md5code.length) {
            md5code.insert(0, "0")
        }
        return md5code.toString()
    }

    @JvmStatic
    fun getUpdateLog(context: Context): String {
        val str = StringBuilder()
        val logItems = context.resources.getStringArray(R.array.update_log_items)
        for (i in logItems.indices)
            str.append("\n").append(i + 1).append(".").append(logItems[i])
        return str.toString()
    }

    @JvmStatic
    fun isDebugBuild(): Boolean {
        return BuildConfig.BETA
    }

    @JvmStatic
    fun getRgb888(color: Int): Int {
        val stringBuilder = StringBuilder()
        stringBuilder.append((color shr 16) and 0xff)
        stringBuilder.append((color shr 8) and 0xff)
        stringBuilder.append(color and 0xff)
        Log.e("颜色", stringBuilder.toString())
        return stringBuilder.toString().toInt()
    }
}
