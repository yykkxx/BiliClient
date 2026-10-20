package com.RobinNotBad.BiliClient

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.multidex.MultiDex
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import java.lang.ref.WeakReference

class BiliTerminal : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (!attached) {
            attached = true
            SharedPreferencesUtil.sharedPreferences = getSharedPreferences("default", MODE_PRIVATE)
            context = getFitDisplayContext(this)
            val errorCatch = ErrorCatch.getInstance()
            errorCatch.init(context)

            val debugBuild = isDebugBuild()
            Logu.LOGV_ENABLED = SharedPreferencesUtil.getBoolean("dev_logv", debugBuild)
            Logu.LOGD_ENABLED = SharedPreferencesUtil.getBoolean("dev_logd", debugBuild)
            Logu.LOGI_ENABLED = SharedPreferencesUtil.getBoolean("dev_logi", debugBuild)

            // v5.0.0 登录体系重构：老版本单 token 迁移到对应身份的独立槽位
            // （AppRcmdApi.ensureMigrated，幂等；读任何 app_rcmd 槽位前也会自动触发）
            CenterThreadPool.run(Runnable { com.RobinNotBad.BiliClient.api.AppRcmdApi.touchMigration() })

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE, true) && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) != 0L) {
                CenterThreadPool.run(Runnable {
                    try {
                        val updateBaseline = SharedPreferencesUtil.getLong("dynamic_update_baseline", 0)
                        val updateNum = DynamicApi.checkDynamicUpdate("all", updateBaseline)
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, updateNum)
                    } catch (e: Exception) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
                    }
                })
            }

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE, true) && SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) != 0L) {
                CenterThreadPool.run(Runnable {
                    try {
                        val messageUnread = MessageApi.checkMessageUnread()
                        val privateMsgUnread = MessageApi.checkPrivateMsgUnread()
                        val totalUnread = messageUnread + privateMsgUnread
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, totalUnread)
                    } catch (e: Exception) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0)
                    }
                })
            }
        }
    }

    companion object {
        // Java 侧语义是"attach 前为 null"，但大量已迁移的 Kotlin 调用端把它当非空用。
        // 用显式延迟初始化标记 + onCreate 赋值保持行为；访问早于 onCreate 属未定义行为（与原 Java 一致）。
        @SuppressLint("StaticFieldLeak")
        @JvmField
        var contextReady = false

        @SuppressLint("StaticFieldLeak")
        @JvmField
        var context: Context = createContextPlaceholder()

        private fun createContextPlaceholder(): Context {
            // 仅作为占位（与 Java null 等价的"未初始化"状态标记）；onCreate 中立即替换。
            return android.app.Application()
        }

        @JvmField
        var DPI_FORCE_CHANGE = false

        private var instance = WeakReference<InstanceActivity>(null)

        private var attached = false

        @JvmStatic
        fun setInstance(instanceActivity: InstanceActivity?) {
            instance = WeakReference(instanceActivity)
        }

        @JvmStatic
        fun getInstanceActivityOnTop(): InstanceActivity? {
            return instance.get()
        }

        /**
         * 重写attachBaseContext方法，用于调整应用内dpi
         * 尝试下这种风格代码是否会导致低版本设备异常
         *
         * @param old The origin context.
         */
        @JvmStatic
        fun getFitDisplayContext(old: Context): Context {
            val dpiTimes = SharedPreferencesUtil.getFloat("dpi", 1.0f)
            if (!DPI_FORCE_CHANGE && dpiTimes == 1.0f) return old
            return try {
                val displayMetrics = old.resources.displayMetrics
                val configuration = old.resources.configuration
                configuration.densityDpi = (displayMetrics.densityDpi * dpiTimes).toInt()
                old.createConfigurationContext(configuration)
            } catch (e: Exception) {
                //MsgUtil.err(e,old);
                old
            }
        }

        @JvmStatic
        @Throws(PackageManager.NameNotFoundException::class)
        fun getVersion(): Int {
            return context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }

        @JvmStatic
        fun isDebugBuild(): Boolean {
            return "debug" == BuildConfig.BUILD_TYPE
        }

        @JvmStatic
        fun jumpToVideo(context: Context, aid: Long) {
            TerminalContext.getInstance().enterVideoDetailPage(context, aid)
        }

        @JvmStatic
        fun jumpToVideo(context: Context, bvid: String?) {
            TerminalContext.getInstance().enterVideoDetailPage(context, bvid ?: "")
        }

        @JvmStatic
        fun jumpToArticle(context: Context, cvid: Long) {
            TerminalContext.getInstance().enterArticleDetailPage(context, cvid)
        }

        @JvmStatic
        fun jumpToUser(context: Context, mid: Long) {
            val intent = Intent()
            intent.setClass(context, UserInfoActivity::class.java)
            intent.putExtra("mid", mid)
            context.startActivity(intent)
        }
    }
}
