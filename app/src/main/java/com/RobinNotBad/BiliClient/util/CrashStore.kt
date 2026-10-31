package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.os.Build
import android.util.Log

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场存储。
 *
 * ## 为什么需要它
 *
 * 全局处理器 [com.RobinNotBad.BiliClient.ErrorCatch] 的做法是：先 `startActivity` 打开
 * [com.RobinNotBad.BiliClient.activity.CatchActivity] 展示堆栈，紧接着 `killProcess`。
 * 进程往往在 CatchActivity 真正绘制出来之前就被杀掉，用户只看到「闪退一下」，
 * 堆栈也随进程一起消失 —— 事后完全没法定位。
 *
 * 这里在杀进程之前把崩溃现场落盘，并在**下一次启动**时补一次展示（见 LauncherActivity），
 * 用户随手截个图就能把堆栈反馈出来。
 *
 * ## 设计约束
 *
 * 这是崩溃处理链的一环，**自身绝不能抛异常**：所有磁盘/SharedPreferences 操作都包在
 * try 里，失败了只写一行日志。文件同时写一份到应用专属外部目录，方便导出。
 */
object CrashStore {

    private const val TAG = "crash-store"

    /** 单独的偏好文件：崩溃时主偏好可能正被写坏，不复用 */
    private const val PREF = "crash_store"

    /** 上次「已向用户展示过」的时间戳 */
    private const val KEY_ACK = "ack_time"

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    /** 单条记录上限，避免一次巨量堆栈把文件写到几十 MB */
    private const val MAX_LEN = 32 * 1024

    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /**
     * 记录一次崩溃，返回整理好的堆栈文本（可直接塞给 CatchActivity）。
     *
     * @param thread 崩溃线程，可为 null（拿不到时就写 unknown）
     * @param throwable 崩溃异常，可为 null
     */
    fun record(context: Context?, thread: Thread?, throwable: Throwable?): String {
        val text = buildText(context, thread, throwable)
        write(context, text)
        // 同时打进 logcat：真机接日志时能直接捞到，不必先找文件
        Log.e(TAG, "recorded crash\n" + text)
        return text
    }

    /**
     * 取「还没给用户看过」的上次崩溃；取到就立刻标记为已展示。
     * @return 堆栈文本；没有新崩溃时返回 null
     */
    fun takeUnacknowledged(context: Context?): String? {
        if (context == null) return null
        val text = readLastCrash(context) ?: return null
        if (isAcknowledged(context)) return null
        markAcknowledged(context)
        return text
    }

    /** 只读取上次崩溃，不改「已展示」状态（设置页/调试入口用） */
    fun peekLastCrash(context: Context?): String? = context?.let { readLastCrash(it) }

    private fun write(context: Context?, text: String) {
        if (context == null) return
        writeTo(File(File(context.filesDir, DIR), FILE), text)
        val external = externalDir(context)
        if (external != null) writeTo(File(File(external, DIR), FILE), text)
    }

    private fun externalDir(context: Context): File? {
        return try {
            context.getExternalFilesDir(null)
        } catch (e: Exception) {
            Log.w(TAG, "getExternalFilesDir failed", e)
            null
        }
    }

    private fun writeTo(file: File, text: String) {
        try {
            val dir = file.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            file.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "write crash file failed: " + file.name, e)
        }
    }

    private fun readLastCrash(context: Context): String? {
        return try {
            val file = File(File(context.filesDir, DIR), FILE)
            if (!file.exists() || file.length() == 0L) {
                null
            } else {
                file.readText().ifBlank { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read crash file failed", e)
            null
        }
    }

    private fun isAcknowledged(context: Context): Boolean {
        return try {
            val ack = prefs(context).getLong(KEY_ACK, 0L)
            val file = File(File(context.filesDir, DIR), FILE)
            ack > 0L && file.lastModified() <= ack
        } catch (e: Exception) {
            Log.w(TAG, "read ack failed", e)
            // 读不到确认时间时按「已确认」处理：宁可少弹一次，也不要在启动时反复打断用户
            true
        }
    }

    private fun markAcknowledged(context: Context) {
        try {
            prefs(context).edit().putLong(KEY_ACK, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "write ack failed", e)
        }
    }

    private fun prefs(context: Context) =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun buildText(context: Context?, thread: Thread?, throwable: Throwable?): String {
        val sb = StringBuilder(MAX_LEN / 4)
        val time = SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date())
        sb.append("时间：").append(time).append('\n')
        sb.append("线程：").append(thread?.name ?: "unknown").append('\n')
        sb.append("版本：").append(versionText(context)).append('\n')
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        sb.append(" / Android ").append(Build.VERSION.RELEASE)
        sb.append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("---------- 堆栈 ----------\n")
        sb.append(stackText(throwable))
        return if (sb.length > MAX_LEN) sb.substring(0, MAX_LEN) else sb.toString()
    }

    private fun stackText(throwable: Throwable?): String {
        if (throwable == null) return "(throwable is null)"
        val buffer = StringWriter()
        // PrintWriter 默认不自动 flush；close/use 时会 flush 到 StringWriter
        PrintWriter(buffer).use { throwable.printStackTrace(it) }
        return buffer.toString()
    }

    private fun versionText(context: Context?): String {
        if (context == null) return "unknown"
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            (info.versionName ?: "?") + " (" + info.versionCode + ")"
        } catch (e: Exception) {
            Log.w(TAG, "read version failed", e)
            "unknown"
        }
    }
}
