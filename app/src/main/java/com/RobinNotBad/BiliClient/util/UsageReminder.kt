package com.RobinNotBad.BiliClient.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * 「每过 15 分钟弹一次 Toast 显示今天已用多少时间」（v5.2.0 需求 4）。
 *
 * ## 生命周期约定（由 `BiliTerminal` 驱动）
 * - App 回到前台（0→1）：[startForegroundReminder]
 * - App 退到后台（1→0）：[stopForegroundReminder]
 *
 * 两处成对调用，本对象自己持有 [reminderJob]，重复 start 会先取消旧的，
 * 重复 stop 是幂等的——所以即使生命周期回调顺序异常也不会出现「两个提醒在跑」。
 *
 * ## 为什么不用 `Timer` / `postDelayed`
 * 历史审计点名了裸 `Timer` 与 `Thread.sleep`：它们不可取消、会持有引用、页面销毁后还能跑。
 * 这里用 `CoroutineScopes.launchUi` + `delay`：挂起不占线程、可取消、天然回到主线程
 * （`MsgUtil.toast` 内部也是回主线程发，所以这里不需要额外调度）。
 *
 * ## 暂停期间不提醒
 * 倒计时页与配额拦截页（`TimeTracking` 暂停中）不该弹「今天已使用 X」——
 * 用户正在等一个强制倒计时，这时候再提醒一次只是干扰。所以每拍先查 [TimeTracking.isPaused]。
 *
 * ## 间隔可配
 * [PREF_REMINDER_INTERVAL]（分钟，默认 15，范围 1~120）。用户中途改了间隔，
 * 下一次 `startForegroundReminder` 生效（不需要重启 App：退后台再回来就会重新读）。
 */
object UsageReminder {

    /** 提醒开关（Bool，默认 false） */
    const val PREF_REMINDER_ENABLE = "usage_reminder_enable"

    /** 提醒间隔（Int 分钟，默认 15） */
    const val PREF_REMINDER_INTERVAL = "usage_reminder_interval_min"

    /** 默认间隔：15 分钟 */
    const val DEFAULT_INTERVAL_MIN = 15

    /** 间隔下限（分钟）：太短会变成骚扰 */
    const val MIN_INTERVAL_MIN = 1

    /** 间隔上限（分钟）：太长基本等于没有提醒 */
    const val MAX_INTERVAL_MIN = 120

    private const val MS_PER_MINUTE = 60_000L

    /** 当前跑着的提醒协程；null = 没在跑 */
    @Volatile
    private var reminderJob: Job? = null

    /** 提醒是否开启（默认关：它会主动打扰用户，必须用户自己打开） */
    @JvmStatic
    fun isEnabled(): Boolean = SharedPreferencesUtil.getBoolean(PREF_REMINDER_ENABLE, false)

    /**
     * 提醒间隔（分钟），已做范围收敛。
     *
     * 为什么要在读的时候收敛：设置页是 `input_int` 自由输入，用户可能填 0 或 9999；
     * 填 0 会让 `delay(0)` 变成忙循环（疯狂弹 Toast），填 9999 等于没提醒。
     * 收敛在这里做，比在设置页做更可靠（设置页漏校验也不会炸）。
     */
    @JvmStatic
    fun intervalMinutes(): Int {
        val stored = SharedPreferencesUtil.getInt(PREF_REMINDER_INTERVAL, DEFAULT_INTERVAL_MIN)
        return stored.coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN)
    }

    /** App 回到前台：开启提醒循环（未开启则什么都不做） */
    @JvmStatic
    fun startForegroundReminder() {
        stopForegroundReminder()
        if (!isEnabled()) return
        val intervalMs = intervalMinutes() * MS_PER_MINUTE
        reminderJob = CoroutineScopes.launchUi {
            while (true) {
                delay(intervalMs)
                // 暂停中（倒计时页 / 拦截页）不打扰
                if (TimeTracking.isPaused()) continue
                MsgUtil.toast("今天已使用 " + UsageLimitManager.formatUsed(UsageLimitManager.todayUsedMs()))
            }
        }
    }

    /** App 退到后台：停掉提醒（幂等） */
    @JvmStatic
    fun stopForegroundReminder() {
        reminderJob?.cancel()
        reminderJob = null
    }
}
