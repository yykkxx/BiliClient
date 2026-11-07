package com.RobinNotBad.BiliClient

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.multidex.MultiDex
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.CookieRefreshApi
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.time.UsageReminder
import com.RobinNotBad.BiliClient.time.UsageTracker
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

            // 时间管理（v5.2.1）的前台时长统计挂载点。
            // 只在本 App 自己的 Activity 上触发（registerActivityLifecycleCallbacks 天然如此），
            // 用 0→1 / 1→0 的边沿判定合并 Activity 频繁切换（推荐↔详情↔播放）时的一段前台时间。
            // 必须在 onCreate 里注册（而不是更晚）：要覆盖整个进程生命周期的第一个 Activity。
            registerActivityLifecycleCallbacks(ForegroundUsageTracker())

            // Web 主动续期（v5.2.1，用户需求「Web 增加主动续期」）。
            // 旧实现只在 RecommendActivity 里被动跑一次：用户长时间不进推荐页时 Cookie 到期
            // 无人续期，等到某次写操作失败才暴露。这里在进程启动后主动兜一次，
            // 内部按 30 分钟节流（见 CookieRefreshApi.renewIfNeededOnForeground 的注释），
            // 所以「频繁冷启动」也不会把网络请求放大成抖动源。
            // 必须放后台线程（会发网络请求），且抛异常一律吞掉：续期失败不该影响启动。
            CenterThreadPool.run(Runnable {
                try {
                    CookieRefreshApi.renewIfNeededOnForeground(false)
                } catch (ignored: Exception) {
                    // 续期是「锦上添花」：网络/结构异常都不该让 App 启动失败
                }
            })
        }
    }

    /**
     * 前台时长与页面停留统计的**生命周期桥**（v5.2.1 重写为 time/UsageTracker 的挂载点）。
     *
     * 这一层只做一件事：把 `ActivityLifecycleCallbacks` 的多 Activity 事件折叠成
     * 「App 整体进入前台 / 退到后台」两个边沿，交给 [UsageTracker]。
     *
     * - **0→1**（第一个 Activity started）：[UsageTracker.onForeground] 记下起点，
     *   紧接着 [UsageReminder.onForeground] 开始一轮 15 分钟提醒周期（v5.3.0 需求 6）；
     * - **1→0**（最后一个 Activity stopped）：[UsageTracker.onBackground] 一次性结算整段，
     *   同时 [UsageReminder.onBackground] 摘掉提醒回调（后台不计、也不弹）。
     *
     * 为什么用 startedCount 边沿而不是「每个 Activity 的 onResume/onPause 各记一次」：
     * 「推荐↔详情↔播放」来回切时后者会切成很多段，且段与段之间的空档（Activity 交接）
     * 要么漏记要么重复记；边沿判定只有真正离开 App 才结算，中间怎么跳都不影响总量。
     *
     * 这一层**不再做**的事（都搬进 [UsageTracker] 内部了，调用方不需要记得扣减）：
     * - 暂停时长扣减（倒计时页/拦截页/询问页不该算使用时长）：改由页面对
     *   [UsageTracker.pause] / [UsageTracker.resume] 成对调用，计数器在 tracker 内部结算；
     * - 日总量归档、跨午夜切分、页面停留结算，全部在 tracker 里。
     *
     * 回调里只做轻量调用（都是内存运算 + 偏好 apply 语义），不碰网络/IO，也不自己回主线程
     * （这些回调本来就在主线程）。
     *
     * ⚠️ **两个 onForeground 的调用顺序不能换**：[UsageTracker.onForeground] 会把
     * 「本轮前台净使用时长」归零，[UsageReminder.onForeground] 依赖那个 0 作为新周期的起点。
     * 反过来调的话，首轮周期会从一个已经走过一段的旧值开始算，提醒会偏早。
     */
    private class ForegroundUsageTracker : Application.ActivityLifecycleCallbacks {
        /** 处于 started 状态的 Activity 数量；只在 0/1 两个状态间判定边沿 */
        private var startedCount = 0

        override fun onActivityStarted(activity: Activity) {
            startedCount += 1
            if (startedCount != 1) return
            // 顺序有依赖：先让 tracker 清掉会话净时长，reminder 再按它起算新周期
            UsageTracker.onForeground()
            UsageReminder.onForeground()
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedCount <= 0) return
            startedCount -= 1
            // 还有别的 Activity 处于 started，说明只是页面切换，继续沿用同一个前台会话
            if (startedCount > 0) return
            // 先停提醒再结算：避免结算瞬间排上的那次 tick 在「已经退后台」之后才跑
            UsageReminder.onBackground()
            UsageTracker.onBackground()
        }

        // 页面名由 BaseActivity.setPageName 直接交给 UsageTracker，这里不需要 resume/pause 配对：
        // setCurrentPage 本身就会结算上一页并给新页起表（只接一处，顺序稳定）。

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
