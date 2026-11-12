package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

import com.RobinNotBad.BiliClient.BiliTerminal

import java.io.IOException

/**
 * 「特定 WiFi 下不发任何网络请求」守卫（v5.3.4 实验室功能，需求 8）。
 *
 * # 用户需求原文
 * 「实验室增加功能，连接到特定 WiFi（用户填写）之后不使用网络，即不进行任何请求，
 * 直接弹出页面提示，点击继续之后才继续请求」
 *
 * # 为什么需要它
 * 这不是一个"省流量"功能，而是**避免在特定网络上暴露使用痕迹**的场景需求：
 * 例如在学校/公司的 WiFi 下不希望出现 B 站请求。所以它的语义是
 * **「在用户明确点『继续』之前，一个请求都不许发出去」** —— 是硬拦截，不是提示。
 *
 * # 拦截点为什么选在 [NetWorkUtil] 的 executeGet/executePost
 * 全 App 的 HTTP 最终都汇聚到这两个私有方法（`ApiRouter` 只是在外层做通道选择，
 * 它自己不发请求）。在它们入口处拦一次，等于覆盖 100% 的出站请求，
 * 不需要去改几十个 Api 对象。**这比逐个 API 打补丁可靠得多**。
 *
 * # 为什么抛 [BlockedByWifiGuardException] 而不是返回空响应
 * 返回空响应会让上层把「被拦截」误判成「服务端返回了空」，
 * 于是可能弹出「解析失败」「接口异常」这类误导性提示，甚至触发重试风暴。
 * 抛一个**可识别**的异常，调用方能区分「这是守卫拦的，不是网络故障」，
 * 从而不弹错误框、不重试。
 *
 * 注意：本异常继承 `IOException`，因为几乎所有调用方都 `catch (IOException)`，
 * 这样即使某个调用方没有专门识别它，行为也只是"网络错误"（不崩溃），
 * 而识别了的调用方可以静默处理。
 *
 * # 状态管理
 * - [PREF_SSIDS]：用户填写的 SSID 列表（换行/逗号/分号分隔），空 = 功能关闭；
 * - [PREF_ENABLED]：总开关（默认 false，绝不默认开启 —— 那会让所有用户突然上不了网）；
 * - [continueUntilMs]：用户点「继续」后的放行截止时间（进程内内存，不持久化）。
 *
 * **为什么不把「已点继续」持久化**：一旦持久化，用户下次连上这个 WiFi
 * 就再也不会被提示，功能等于失效。每次重新连上（或重启应用）都应重新询问。
 * 这里用内存变量，进程重启即恢复拦截，符合安全语义。
 */
object WifiGuard {

    private const val TAG = "wifi-guard"

    /** 总开关：默认关闭（开启后连上指定 WiFi 将不发出任何请求） */
    const val PREF_ENABLED = "lab_wifi_guard_enabled"

    /** 用户填写的 SSID 列表（分隔符见 [splitSsids]） */
    const val PREF_SSIDS = "lab_wifi_guard_ssids"

    /**
     * 用户点了「继续」之后，放行多久（毫秒）。
     *
     * 取 10 分钟的理由：用户点继续通常是为了「这次就看一会」，
     * 时间太长等于永久放行（功能失效），太短会反复弹（烦人）。
     * 到期后自动重新拦截，不需要用户手动操作 —— 也让「忘了这回事」不会变成风险。
     */
    private const val CONTINUE_WINDOW_MS = 10 * 60 * 1000L

    /** 放行截止时间（0 = 未放行）。内存变量，进程重启即失效（理由见类注释） */
    @Volatile
    private var continueUntilMs: Long = 0L

    /**
     * 用户点过「继续」的回调：由提示页在用户确认后设置。
     * 用 `@Volatile` 是因为它可能被主线程写、网络线程读。
     */
    @Volatile
    var onUserContinued: (() -> Unit)? = null

    /**
     * 守卫被触发时的回调：用于拉起提示页。
     *
     * **只会被调用一次直到下次放行**（避免几十个并发请求各弹一次页面）。
     * 调用方（提示页所属的 Application/Activity）负责真正展示 UI。
     */
    @Volatile
    var onBlocked: (() -> Unit)? = null

    /** 防重入：已经通知过 UI 就不再重复通知 */
    @Volatile
    private var notifyPending = false

    /** 功能是否开启（开关打开且至少填了一个 SSID） */
    @JvmStatic
    fun isEnabled(): Boolean {
        if (!SharedPreferencesUtil.getBoolean(PREF_ENABLED, false)) return false
        return splitSsids(SharedPreferencesUtil.getString(PREF_SSIDS, "") ?: "").isNotEmpty()
    }

    /** 用户填写的 SSID 列表 */
    @JvmStatic
    fun configuredSsids(): List<String> =
        splitSsids(SharedPreferencesUtil.getString(PREF_SSIDS, "") ?: "")

    /**
     * 解析用户填写的 SSID 文本。
     *
     * 分隔符同时支持换行、英文逗号、中文逗号、分号 —— 用户不会去记"该用哪个"，
     * 而拒绝多写一种分隔符只会让人困惑。空行与首尾空格一律忽略。
     */
    @JvmStatic
    fun splitSsids(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split('\n', '\r', ',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 当前连接的 WiFi SSID（拿不到返回 null）。
     *
     * # 为什么需要 `@SuppressLint("HardwareIds")` 与权限说明
     * `WifiManager.connectionInfo` 在 Android 8.1+ 需要定位权限才能读到**真实 SSID**
     * （已连接时读到 `"<unknown ssid>"` 表示权限不足）。本项目 targetSdk 30，
     * 已声明的权限见 AndroidManifest（ACCESS_FINE_LOCATION）。
     * 读不到时本函数返回 null，守卫**不拦截**（宁可漏拦也不误拦到所有网络）。
     */
    @JvmStatic
    fun currentSsid(context: Context?): String? {
        val ctx = context ?: return null
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo ?: return null
            val ssid = info.ssid ?: return null
            // 系统用这两串占位符表示「不知道」：<unknown ssid>（权限不足）/ 0x（十六进制失败）
            if (ssid.isBlank()) return null
            if (ssid == "<unknown ssid>" || ssid == "0x") return null
            // 系统返回的 SSID 带英文双引号，去掉才是用户填写的形式
            ssid.trim().trim('"').takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            Log.w(TAG, "读取当前 SSID 失败：" + e.javaClass.simpleName)
            null
        }
    }

    /**
     * 当前是否应该拦截请求。
     *
     * 判定顺序（每一步都能提前返回 false，避免做无谓的 WiFi 查询）：
     * 1. 功能没开 → 不拦（绝大多数用户走这条）；
     * 2. 还在放行窗口内 → 不拦；
     * 3. 读不到当前 SSID → 不拦（**宁可漏拦，不能误拦全部网络**）；
     * 4. 当前 SSID 不在用户填写的列表里 → 不拦；
     * 5. 否则 → 拦，并（最多一次）通知 UI 弹提示页。
     */
    @JvmStatic
    fun shouldBlock(host: String? = null): Boolean {
        if (!isEnabled()) return false
        if (System.currentTimeMillis() < continueUntilMs) return false

        val ssid = currentSsid(BiliTerminal.context) ?: return false
        val matched = configuredSsids().any { it.equals(ssid, ignoreCase = true) }
        if (!matched) return false

        // 命中：通知 UI（只通知一次，直到用户点继续）
        if (!notifyPending) {
            notifyPending = true
            try {
                onBlocked?.invoke()
            } catch (e: Throwable) {
                Log.w(TAG, "通知守卫提示页失败：" + e.javaClass.simpleName)
            }
        }
        Log.i(TAG, "已拦截请求（SSID 命中）" + if (host != null) "，目标域名已隐藏" else "")
        return true
    }

    /** 用户点了「继续」：放行 [CONTINUE_WINDOW_MS]（默认 10 分钟） */
    @JvmStatic
    fun userContinued() {
        continueUntilMs = System.currentTimeMillis() + CONTINUE_WINDOW_MS
        notifyPending = false
        try {
            onUserContinued?.invoke()
        } catch (e: Throwable) {
            Log.w(TAG, "继续回调失败：" + e.javaClass.simpleName)
        }
        Log.i(TAG, "用户已选择继续，放行 10 分钟")
    }

    /** 断开/切换 WiFi、或用户改了设置时调用：立刻恢复拦截状态 */
    @JvmStatic
    fun resetContinue() {
        continueUntilMs = 0L
        notifyPending = false
    }

    /**
     * 「被 WiFi 守卫拦截」的专用异常。
     *
     * 继承 [IOException] 的理由见类注释（所有调用方都 catch IOException，
     * 不会因为多了一种异常而崩溃）。
     */
    class BlockedByWifiGuardException : IOException(
        "已连接到受保护的 WiFi，请求被拦截（在提示页点「继续」后才会恢复联网）"
    )
}
