package com.RobinNotBad.BiliClient

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.multidex.MultiDex
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.UsageLimitManager
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

            // v5.1.5 账号管理：把旧版单账号凭据（web Cookie / 三端 token）与旧开关
            // 迁进「端口 × 多账号」模型（AccountStore.ensureMigrated，幂等，只读旧键一次）；
            // 随后 v5.0.0 的单 token → 独立槽位迁移（AppRcmdApi.ensureMigrated，同样是幂等的）。
            AccountStore.ensureMigrated()
            CenterThreadPool.run(Runnable { com.RobinNotBad.BiliClient.api.AppRcmdApi.touchMigration() })

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.DYNAMIC_UPDATE_CHECK_ENABLE, true) && AccountManager.isLoggedIn()) {
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

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.MESSAGE_UPDATE_CHECK_ENABLE, true) && AccountManager.isLoggedIn()) {
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

            // v5.1.6 实验室「每日使用时长限制」的前台时长统计。
            // 只在本 App 自己的 Activity 上触发（registerActivityLifecycleCallbacks 天然如此），
            // 用 0→1 / 1→0 的边沿判定合并 Activity 频繁切换（推荐↔详情↔播放）时的一段前台时间。
            registerActivityLifecycleCallbacks(ForegroundUsageTracker())
        }
    }

    /**
     * 实验室「每日使用时长限制」的前台时长统计（v5.1.6）。
     *
     * 只统计本 App 自己的 Activity（`registerActivityLifecycleCallbacks` 天然如此），
     * 用 [startedCount] 的 0→1 / 1→0 **边沿判定**把「推荐↔详情↔播放」这种频繁切换
     * 合并成一段连续前台时间，不会重复累加，也不会在 Activity 之间跳转时丢掉时长：
     * - 0→1（App 真正回到前台，第一个 Activity started）记下起点 [foregroundStartElapsed]；
     * - 1→0（最后一个 Activity stopped）一次性 [UsageLimitManager.addUsage] 整段时长。
     *
     * 回调里**只做偏好读写**（SharedPreferencesUtil 是 apply 语义），不碰网络/IO/数据库。
     */
    private class ForegroundUsageTracker : Application.ActivityLifecycleCallbacks {
        /** 处于 started 状态的 Activity 数量；只在 0/1 两个状态间判定边沿 */
        private var startedCount = 0

        /** 本轮前台的起点（SystemClock.elapsedRealtime，不受系统改时间影响）；0 = 未开始 */
        private var foregroundStartElapsed = 0L

        override fun onActivityStarted(activity: Activity) {
            startedCount += 1
            if (startedCount == 1) {
                foregroundStartElapsed = SystemClock.elapsedRealtime()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedCount <= 0) return
            startedCount -= 1
            // 还有别的 Activity 处于 started，说明只是页面切换，继续沿用同一个起点
            if (startedCount > 0) return
            val start = foregroundStartElapsed
            foregroundStartElapsed = 0L
            if (start > 0L) {
                UsageLimitManager.addUsage(SystemClock.elapsedRealtime() - start)
            }
        }

        // 其余回调与本功能无关，保持空实现（detekt 对 `= Unit` 不报 EmptyFunctionBlock）
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
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
