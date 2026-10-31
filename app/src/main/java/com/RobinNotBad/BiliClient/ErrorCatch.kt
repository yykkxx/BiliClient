package com.RobinNotBad.BiliClient

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.activity.CatchActivity
import com.RobinNotBad.BiliClient.util.CrashStore

import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer

class ErrorCatch : Thread.UncaughtExceptionHandler {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(@NonNull thread: Thread, @NonNull throwable: Throwable) {
        val ctx = context
        // 先落盘，再展示。
        // 原因：下面的 killProcess 会立刻杀死进程，CatchActivity 常常还没绘制出来就没了，
        // 用户只看到「闪退一下」、堆栈也没留下，事后完全没法定位。
        // 落盘之后可以在下次启动时补展示一次（LauncherActivity 里取未读记录）。
        val stack = try {
            CrashStore.record(ctx, thread, throwable)
        } catch (t: Throwable) {
            // 连落盘都失败时退回内存堆栈，至少 CatchActivity 还能显示。
            // t 本身也要打出来：落盘失败的原因（磁盘满/权限）对排查同样有价值。
            t.printStackTrace()
            val writer: Writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            writer.toString()
        }

        if (ctx != null) {
            try {
                val intent = Intent(ctx, CatchActivity::class.java)
                intent.putExtra("stack", stack)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) //这句是安卓4必须有的
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        throwable.printStackTrace()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @JvmField
        var instance: ErrorCatch? = null

        @JvmStatic
        fun getInstance(): ErrorCatch {
            if (instance == null) instance = ErrorCatch()
            return instance!!
        }
    }
}
