package com.RobinNotBad.BiliClient

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.multidex.MultiDex
import com.RobinNotBad.BiliClient.activity.WifiGuardActivity
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
import com.RobinNotBad.BiliClient.util.WifiGuard
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

            // v5.3.4 实验室功能（需求 8）：受保护 WiFi 守卫的 UI 挂载点。
            // WifiGuard 只在网络线程做判定（不许碰 UI），真正弹页面必须回主线程；
            // 这里把「被拦截」的回调接到一个主线程投递上。
            installWifiGuardUi()

            // Web 续期由下面前台生命周期边沿立即触发，不再仅在进程创建时检查。
        }
    }

    /**
     * 把 [WifiGuard.onBlocked] 接到主线程并拉起 [WifiGuardActivity]。
     *
     * # 为什么必须切主线程
     * `shouldBlock()` 是在 **OkHttp 的网络线程**里被 `executeGet/executePost` 调用的，
     * 而 `startActivity` 只能从主线程发起（否则 `CalledFromWrongThreadException`，
     * 这正是 v5.3.3 播放器崩溃的同型错误）。所以这里用 `Handler(Looper.getMainLooper())` 投递。
     *
     * # 为什么不用 CoroutineScopes.launchUi
     * 这个回调可能在任何时刻被触发（包括 App 刚启动、协程调度器还没热起来），
     * 而 `Handler` 是这里最轻、最不依赖初始化顺序的选择。守卫本身也不该依赖
     * 业务层的协程作用域（那会形成 util → util 的隐式循环依赖）。
     */
    private fun installWifiGuardUi() {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        // onBlocked 会在网络线程被调用；这里投递到主线程再 startActivity。
        // 「已经在主线程就直接执行」的分支省掉了 —— 网络线程必然不是主线程，
        // 而多一条分支就多一处可能写错的地方。
        WifiGuard.onBlocked = {
            mainHandler.post {
                try {
                    // 用 Application 上下文启动（此时可能一个 Activity 都没有）
                    WifiGuardActivity.launch(this)
                } catch (e: Throwable) {
                    // 守卫页面拉不起来时**不能抛**：那会把网络线程一起带走。
                    // 此时请求仍被拦截（shouldBlock 已返回 true），只是用户看不到提示页 ——
                    // 退化为"静默断网"，比崩溃好。
                    android.util.Log.w("wifi-guard", "拉起 WiFi 守卫页面失败：" + e.javaClass.simpleName)
                }
            }
        }
        // onUserContinued 不需要挂 UI 回调：放行后页面自己就 finish 了。
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
            // 每次真正进入 APP（包括后台返回）直接续期；普通页间切换不重复。
            CenterThreadPool.run(Runnable {
                CookieRefreshApi.renewIfNeededOnForeground(true)
            })
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
