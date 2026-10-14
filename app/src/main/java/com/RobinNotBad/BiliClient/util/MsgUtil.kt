package com.RobinNotBad.BiliClient.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.DialogActivity
import com.RobinNotBad.BiliClient.activity.ShowTextActivity
import com.RobinNotBad.BiliClient.event.SnackEvent
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout

import org.greenrobot.eventbus.EventBus
import org.json.JSONException

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer

/**
 * Kotlin 重构版（原 MsgUtil.java）。
 * 所有方法保持 @JvmStatic，Java 端 MsgUtil.xxx(...) 的静态调用方式不变。
 */
object MsgUtil {
    private var toast: Toast? = null

    @JvmStatic
    fun showMsg(str: String?) {
        Logu.i(str)
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true)) {
            CenterThreadPool.runOnUiThread { EventBus.getDefault().postSticky(SnackEvent(str)) }
        } else {
            toast(str)
        }
    }

    @JvmStatic
    fun showMsgLong(str: String?) {
        Logu.i("long", str)
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true)) {
            CenterThreadPool.runOnUiThread { EventBus.getDefault().postSticky(SnackEvent(str, Snackbar.LENGTH_LONG)) }
        } else {
            toastLong(str)
        }
    }

    @JvmStatic
    fun toast(str: String?) {
        CenterThreadPool.runOnUiThread { toastInternal(str, BiliTerminal.context) }
    }

    @JvmStatic
    fun toastLong(str: String?) {
        CenterThreadPool.runOnUiThread { toastLongInternal(str, BiliTerminal.context) }
    }

    private fun toastInternal(str: String?, context: Context) {
        toast?.cancel()
        toast = Toast.makeText(context, str, Toast.LENGTH_SHORT)
        toast?.show()
    }

    private fun toastLongInternal(str: String?, context: Context) {
        toast?.cancel()
        toast = Toast.makeText(context, str, Toast.LENGTH_LONG)
        toast?.show()
    }

    @JvmStatic
    fun processSnackEvent(snackEvent: SnackEvent, view: View) {
        val currentTime = System.currentTimeMillis()

        val duration: Int
        if (snackEvent.duration > 0) duration = snackEvent.duration
        else if (snackEvent.duration == Snackbar.LENGTH_SHORT) duration = 1950
        else if (snackEvent.duration == Snackbar.LENGTH_INDEFINITE) duration = Integer.MAX_VALUE
        else duration = 2750

        val endTime = snackEvent.startTime + duration
        if (currentTime >= endTime) {
            EventBus.getDefault().removeStickyEvent(snackEvent)
        } else {
            createSnack(view, snackEvent.message, (endTime - currentTime).toInt())
                .show()
        }
    }

    @JvmStatic
    fun snackText(view: View, text: CharSequence?) {
        createSnack(view, text).show()
    }

    @JvmStatic
    fun snackTextLong(view: View, text: CharSequence?) {
        createSnack(view, text, Snackbar.LENGTH_LONG).show()
    }

    @JvmStatic
    fun createSnack(view: View, text: CharSequence?): Snackbar {
        return createSnack(view, text, Snackbar.LENGTH_SHORT)
    }

    @JvmStatic
    fun createSnack(view: View, text: CharSequence?, duration: Int): Snackbar {
        return createSnack(view, text, duration, null)
    }

    @SuppressLint("ClickableViewAccessibility", "RestrictedApi")
    @JvmStatic
    fun createSnack(view: View, text: CharSequence?, duration: Int, action: Action?): Snackbar {
        val snackbar = Snackbar.make(view, text ?: "", duration)
        snackbar.setBackgroundTint(Color.argb(0x85, 0x80, 0x80, 0x80))
        snackbar.setTextColor(Color.rgb(0xeb, 0xe0, 0xe2))
        val snackBarView = snackbar.getView()
        snackBarView.setOnTouchListener { _, _ -> false }
        snackBarView.setPadding(ToolsUtil.dp2px(6f), 0, 0, 0)
        val contentLayout = ((snackBarView as FrameLayout).getChildAt(0)) as SnackbarContentLayout

        if (action != null) snackbar.setAction(action.text, action.onClickListener)
        else if (duration == Snackbar.LENGTH_INDEFINITE || duration >= 5000) {
            snackbar.setAction("x") { snackbar.dismiss() }
            val actionView: Button = contentLayout.getActionView()
            //actionView.setTextSize(ToolsUtil.sp2px(13));
            actionView.setMinWidth(ToolsUtil.dp2px(30f))
            actionView.setMinimumWidth(ToolsUtil.dp2px(30f))
            actionView.setMaxWidth(ToolsUtil.dp2px(48f))
            actionView.setPadding(0, 0, ToolsUtil.dp2px(4f), 0)
        }

        val msgView: TextView = contentLayout.getMessageView()
        msgView.setMaxLines(16)
        msgView.setTextSize(13f)
        msgView.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD))
        msgView.setPadding(0, 0, ToolsUtil.dp2px(4f), 0)

        return snackbar
    }

    @JvmStatic
    fun err(e: Throwable) {
        err(null, e)
    }

    @JvmStatic
    fun err(desc: String?, e: Throwable) {
        val context = BiliTerminal.context
        if (desc != null) Log.e("debug-error", desc)
        e.printStackTrace()

        val output = StringBuilder(if (TextUtils.isEmpty(desc)) "" else desc + "\n")

        val e_str = e.toString()

        if (e is IOException) {
            showMsg(context.getString(R.string.err_network))
            return
        } else if (e is JSONException) {
            output.append(context.getString(R.string.err_json))

            if (SharedPreferencesUtil.getBoolean("dev_jsonerr_detailed", false)) {
                val writer: Writer = StringWriter()
                val printWriter = PrintWriter(writer)
                e.printStackTrace(printWriter)
                showText(output.toString(), writer.toString())
                return
            } else if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) {
                output.append(context.getString(R.string.err_login_first))
            } else if (e_str.contains("-352") || e_str.contains("22015") || e_str.contains("65056"))
                output.append(context.getString(R.string.err_rejected))
            else {
                output.append(e_str.replace("org.json.JSONException:", ""))
            }
        } else if (e is IndexOutOfBoundsException) {
            if (SharedPreferencesUtil.getBoolean("dev_recyclererr_detailed", false)) {
                val writer: Writer = StringWriter()
                val printWriter = PrintWriter(writer)
                e.printStackTrace(printWriter)
                showText(desc + "Adapter错误", writer.toString())
                return
            } else {
                output.append("遇到Adapter错误：\n无需上报，除非你在某个界面经常遇到")
            }
        } else if (e is SQLException) output.append(context.getString(R.string.err_sql))
        else {
            output.append("错误：")
            output.append(e_str)
        }

        showMsgLong(output.toString())
    }

    @JvmStatic
    fun showText(title: String?, text: String?) {
        val context = BiliTerminal.context
        val testIntent = Intent()
            .setClass(context, ShowTextActivity::class.java)
            .putExtra("title", title)
            .putExtra("content", text)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(testIntent)
    }

    @JvmStatic
    fun showDialog(title: String?, content: String?) {
        val context = BiliTerminal.context
        val intent = Intent(context, DialogActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("content", content)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @JvmStatic
    fun showDialog(title: String?, content: String?, wait_time: Int) {
        val context = BiliTerminal.context
        val intent = Intent(context, DialogActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("content", content)
        intent.putExtra("wait_time", wait_time)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    class Action {
        var text: String?
        var onClickListener: View.OnClickListener?

        constructor(text: String?, onClickListener: View.OnClickListener?) {
            this.text = text
            this.onClickListener = onClickListener
        }
    }
}
