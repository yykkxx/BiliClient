package com.RobinNotBad.BiliClient

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.activity.CatchActivity

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
        val writer: Writer = StringWriter()
        val printWriter = PrintWriter(writer)
        throwable.printStackTrace(printWriter)

        try {
            val intent = Intent(context, CatchActivity::class.java)
            intent.putExtra("stack", writer.toString())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) //这句是安卓4必须有的
            context!!.startActivity(intent)
        } catch (t: Throwable) {
            t.printStackTrace()
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
