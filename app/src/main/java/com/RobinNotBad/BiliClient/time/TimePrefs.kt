package com.RobinNotBad.BiliClient.time

import android.content.SharedPreferences

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.Calendar

/**
 * v5.2.1「时间管理 2」的**偏好键唯一登记处** + 读写帮助函数 + 日期换算。
 *
 * # 为什么单独一个文件
 * 这套功能横跨 6 个纯逻辑对象和 6 个页面，如果各文件自己定义键字符串，并行开发时
 * 很容易出现「设置页写一个键、判定逻辑读另一个键」的静默失效（历史上 `usage_history`
 * 与 `usage_page_stats` 就这么错过一次）。所以所有 `tm2_` 键只在这里登记一次，
 * 其它文件一律引用 [TimePrefs] 的常量。
 *
 * # 与旧键的关系
 * 用户明确要求「先删除之前所有代码」，旧键（`lab_usage_limit` / `usage_limit_day` /
 * `usage_limit_ms` / `usage_stats_enable` / `time_bank_*` …）**一律不读、不写、不迁移**。
 * `tm2_` 前缀保证与旧键在物理上不冲突：即使旧键还躺在 SharedPreferences 里，
 * 新逻辑也永远看不到它们（数据全部丢弃，符合用户口径）。
 *
 * # 为什么日期换算也在这里
 * `time` 包内几乎每个对象都要问「今天是哪天」，而本文件**没有任何依赖**
 * （只依赖 SharedPreferencesUtil 与 Calendar），是最合适的落点。
 * 若把 `todayKey()` 放进 DailyPlan，就会出现 TimeBank ⇄ DailyPlan ⇄ UsageLog 的三向环；
 * 放在这里之后，包内依赖是单向的：TimePrefs ← 其它全部。
 *
 * # 时间基准的分工（全包统一，务必遵守）
 * - **日期边界（「今天是哪天」）用 `System.currentTimeMillis()` 派生的本地日历**：
 *   它会被用户手改系统时间影响——这正是我们**想要**的，因为「今天」在用户眼里就是
 *   他手机上显示的那一天；跨天重置、21:30 判断、日期键都必须跟随它。
 * - **时长计时（「用了多久」）用 `SystemClock.elapsedRealtime()`**：它不受改时间影响，
 *   用户把时钟往前拨 3 小时也不会凭空多出/少掉使用时长。见 [UsageTracker]。
 *
 * # 默认值总口径
 * 全部键在默认值下**不改变任何既有行为**：总开关默认关、时间银行默认关、使用记录默认空。
 * 用户必须主动打开才会生效（升级零行为变化，也不会莫名其妙把用户拦在门外）。
 */
object TimePrefs {

    // ==========================================================================================
    // 键名登记（规格书 §5.3 的键表，一个不多一个不少）
    // ==========================================================================================

    /**
     * 使用控制总开关。
     *
     * 默认 **false**：这是会拦人的功能（配额耗尽直接进不去主界面）。新装/升级默认打开
     * 等于替用户决定「你今天只能玩 2 小时」，风险不可接受，必须用户自己去设置里打开。
     */
    const val ENABLE = "tm2_enable"

    /**
     * 上学日每日配额（分钟）。
     *
     * 默认 **120**：用户原话「如果是上学日就 2h 限额」。这个数字是他自己给的，
     * 所以作为默认值而不是 60/180。
     */
    const val SCHOOL_QUOTA_MIN = "tm2_school_quota_min"

    /**
     * 今天的日子类型，取值见 [DailyPlan.DAY_SCHOOL] / [DailyPlan.DAY_REST]。
     *
     * 只有 [DAY_KEY] 等于今天时才算数（跨天自动失效 → 第二天重新问）。默认空 = 还没问。
     */
    const val DAY_TYPE = "tm2_day_type"

    /** [DAY_TYPE] 属于哪一天（`yyyy-MM-dd`）。默认空 = 从未做过判定。 */
    const val DAY_KEY = "tm2_day_key"

    /** 今天已用的前台毫秒数。默认 0。 */
    const val USED_MS = "tm2_used_ms"

    /** [USED_MS] 属于哪一天（`yyyy-MM-dd`）。不等于今天就当 0（并归档旧的）。默认空 = 从未记录。 */
    const val USED_DAY = "tm2_used_day"

    /**
     * 「长按 45 秒解除今天限制」写下的日期。
     *
     * 等于今天 → 今天完全放行。默认空 = 今天没被解除。次日自然失效（不需要定时任务）。
     */
    const val BYPASS_DAY = "tm2_bypass_day"

    /**
     * 「不等待」档的上界（分钟）。默认 **30**：用户原话「0~30 分钟不等待」。
     * 它同时是**绿色/黄色**的分界（颜色与等待在需求里是同一组分档）。
     */
    const val COUNTDOWN_NO_WAIT_MIN = "tm2_no_wait_min"

    /** 中档等待秒数。默认 **5**：用户原话「30 分钟到 60 分钟等待 5 秒」。 */
    const val COUNTDOWN_MID_WAIT_SEC = "tm2_mid_wait_sec"

    /** 高档等待秒数。默认 **10**：用户原话「60 分钟以上等待 10 秒」。 */
    const val COUNTDOWN_HIGH_WAIT_SEC = "tm2_high_wait_sec"

    /**
     * 超过这个时刻（当天的第几分钟）不再做倒计时等待。默认 **1290 = 21:30**。
     *
     * 用户先说「9:30 以后」后确认为**晚上 21:30**。注意只影响「等待」，
     * 不影响「配额耗尽拦截」——夜里配额用完照样拦（规格 §5.3 核心规则 4/5）。
     */
    const val NO_COUNTDOWN_AFTER = "tm2_no_countdown_after_min"

    /**
     * 关闭使用控制总开关前必须等待的秒数。默认 **120**：用户原话
     * 「这个开关关闭的时候需要进行倒计时 120s」。开方向立即生效，不受它影响。
     */
    const val TOGGLE_OFF_WAIT_SEC = "tm2_toggle_off_wait_sec"

    /** 把上学日配额**调大**前必须等待的秒数。默认 **120**：「更改变大的时候需要 120 秒倒计时」。调小立即生效。 */
    const val QUOTA_INCREASE_WAIT_SEC = "tm2_quota_increase_wait_sec"

    /**
     * 时间银行总开关。
     *
     * 默认 **false**：借钱花的行为必须用户主动打开；且它依赖总开关，
     * 两个都默认关时整套时间管理对用户完全无感。
     */
    const val BANK_ENABLE = "tm2_bank_enable"

    /**
     * 借入记录，格式 `"yyyy-MM-dd:min,yyyy-MM-dd:min"`（日期 = **被借走**的那天）。
     * 默认空串 = 没有任何借入。解析容错见 [TimeBank]。
     */
    const val BANK_BORROWS = "tm2_bank_borrows"

    /**
     * 任意时刻「总借入」上限（分钟）。默认 **300 = 5 小时**：用户原话
     * 「任意时刻内，总借的时长不超过 5 小时」。
     */
    const val BANK_TOTAL_LIMIT_MIN = "tm2_bank_total_limit_min"

    /** 可借的天数窗口（不含今天）。默认 **7**：用户原话「向将来的 7 天内借时间给今天」。 */
    const val BANK_MAX_DAYS = "tm2_bank_max_days"

    /** 日总时长历史，格式 `"yyyy-MM-dd:ms;..."`，只保留最近 [UsageLog.KEEP_DAYS] 天。默认空 = 无记录。 */
    const val LOG_DAYS = "tm2_log_days"

    /** 页面停留，格式 `"yyyy-MM-dd|页名:ms;页名:ms"`，**只保留当天**。默认空 = 无记录。 */
    const val LOG_PAGES = "tm2_log_pages"

    /**
     * 「长按解除今天限制」需要的秒数。默认 **45**：用户原话
     * 「长按配额耗尽界面的某一个按钮 45 秒来解除当天限制」。
     */
    const val LONG_PRESS_RELEASE_SEC = "tm2_long_press_release_sec"

    // ==========================================================================================
    // 默认值（与上面的键一一对应；写成常量是为了让「默认值」只在编译期出现一次）
    // ==========================================================================================

    /** 总开关默认关（理由见 [ENABLE]） */
    const val DEFAULT_ENABLE = false

    /** 上学日默认 2 小时（理由见 [SCHOOL_QUOTA_MIN]） */
    const val DEFAULT_SCHOOL_QUOTA_MIN = 120

    /** 不等待档默认 30 分钟（理由见 [COUNTDOWN_NO_WAIT_MIN]） */
    const val DEFAULT_NO_WAIT_MIN = 30

    /** 中档默认 5 秒 */
    const val DEFAULT_MID_WAIT_SEC = 5

    /** 高档默认 10 秒 */
    const val DEFAULT_HIGH_WAIT_SEC = 10

    /** 默认 21:30（写成 21*60+30 而不是 1290，方便日后核对） */
    const val DEFAULT_NO_COUNTDOWN_AFTER_MIN = 21 * 60 + 30

    /** 关闭总开关默认等 120 秒 */
    const val DEFAULT_TOGGLE_OFF_WAIT_SEC = 120

    /** 配额调大默认等 120 秒 */
    const val DEFAULT_QUOTA_INCREASE_WAIT_SEC = 120

    /** 时间银行默认关 */
    const val DEFAULT_BANK_ENABLE = false

    /** 总借入上限默认 300 分钟（5 小时） */
    const val DEFAULT_BANK_TOTAL_LIMIT_MIN = 300

    /** 借入窗口默认 7 天 */
    const val DEFAULT_BANK_MAX_DAYS = 7

    /** 长按解除默认 45 秒 */
    const val DEFAULT_LONG_PRESS_RELEASE_SEC = 45

    // ==========================================================================================
    // 取值上下界（防止设置页输入 0 / 负数 / 天文数字把判定算成负数或溢出）
    // ==========================================================================================

    /** 单日配额上限 = 1440 分钟 = 一整天：再大也没有意义（休息日走「无限制」哨兵值） */
    private const val MAX_SCHOOL_QUOTA_MIN = 24 * 60

    /** 「不等待」档上界 = 60 分钟：红色档固定从 60 分钟起，第一档不能越过它，否则黄档永不可达 */
    private const val MAX_NO_WAIT_MIN = 60

    /** 等待秒数上界 = 1 小时：再长就是用户输入错了，按 1 小时兜底 */
    private const val MAX_WAIT_SEC = 60 * 60

    /** 21:30 这类「当天第几分钟」的上界 = 23:59 */
    private const val MAX_MINUTE_OF_DAY = 24 * 60 - 1

    /** 总借入上限的上界 = 30 天 × 1440 分钟（借入窗口最大 30 天时的绝对上限），防呆 */
    private const val MAX_BANK_TOTAL_LIMIT_MIN = 30 * 24 * 60

    /** 借入窗口上界 = 30 天：规格是 7 天，但允许调大到一个月；再大就没有实际意义 */
    private const val MAX_BANK_MAX_DAYS = 30

    /** 长按秒数上界 = 600 秒（10 分钟）：再长用户会以为卡死 */
    private const val MAX_LONG_PRESS_RELEASE_SEC = 10 * 60

    // ==========================================================================================
    // 底层读写（唯一碰 SharedPreferencesUtil 的地方）
    // ==========================================================================================

    /**
     * 取偏好实例；[SharedPreferencesUtil.sharedPreferences] 由 `BiliTerminal.onCreate` 赋值。
     *
     * 做成可空是为了「理论上在 onCreate 之前被调用」时退化成默认值而不是崩溃
     * （时间管理是附属功能，绝不能因为它让 App 起不来）。
     */
    private fun prefs(): SharedPreferences? = SharedPreferencesUtil.getSharedPreferences()

    /** 统一的写入封装：一个 Editor 一次提交，避免「写了一半」的中途状态 */
    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val preferences = prefs() ?: return
        val editor = preferences.edit()
        block(editor)
        editor.apply()
    }

    /**
     * 读布尔；偏好未就绪时返回 [def]。
     *
     * ## v5.3.4：为什么要 try/catch
     * `SharedPreferences.getBoolean` 在**键存的是别的类型**时会抛
     * `ClassCastException`（`SharedPreferencesImpl` 内部直接强转），不是返回默认值。
     * 触发条件真实存在：旧版本/手工改过的偏好里同一个键可能是 String 或 Int，
     * 而 `tm2_*` 这套键在 v5.2.1 删旧实现时**刻意不迁移**（见类注释「与旧键的关系」），
     * 于是「同名前缀、不同历史类型」这种脏数据完全可能在真机偏好里躺着。
     *
     * 后果的量级：这些读取都在**启动路径**上（`Gate.decide` → `DailyPlan.*` → `TimePrefs.*`），
     * 抛出去就是打开 App 立刻闪退，而且用户自己完全无法自救（清数据才能恢复）。
     * 对比之下「读不到就用默认值」最坏只是配额按默认 120 分钟算 —— 代价完全不对等。
     *
     * 所以四个类型化读取统一兜住 CCE，命中时打一条日志并返回默认值。
     */
    @JvmStatic
    fun getBool(key: String, def: Boolean): Boolean = guardedRead(key, def) {
        prefs()?.getBoolean(key, def) ?: def
    }

    /** 写布尔 */
    @JvmStatic
    fun putBool(key: String, value: Boolean) = edit { it.putBoolean(key, value) }

    /** 读整数；偏好未就绪/类型不符时返回 [def]（理由见 [getBool]） */
    @JvmStatic
    fun getInt(key: String, def: Int): Int = guardedRead(key, def) {
        prefs()?.getInt(key, def) ?: def
    }

    /** 写整数 */
    @JvmStatic
    fun putInt(key: String, value: Int) = edit { it.putInt(key, value) }

    /** 读长整数；偏好未就绪/类型不符时返回 [def]（理由见 [getBool]） */
    @JvmStatic
    fun getLong(key: String, def: Long): Long = guardedRead(key, def) {
        prefs()?.getLong(key, def) ?: def
    }

    /** 写长整数 */
    @JvmStatic
    fun putLong(key: String, value: Long) = edit { it.putLong(key, value) }

    /**
     * 读字符串；null 一律折成 [def]（偏好里存过 null 的历史脏数据不会扩散成 NPE）。
     *
     * 同样兜住 CCE：键若是被别的版本写成了非字符串类型，强转会在 `getString` 里抛
     * （理由与后果见 [getBool]）。
     */
    @JvmStatic
    fun getText(key: String, def: String): String = guardedRead(key, def) {
        prefs()?.getString(key, def) ?: def
    }

    /** 写字符串 */
    @JvmStatic
    fun putText(key: String, value: String) = edit { it.putString(key, value) }

    /** 删键（清空使用记录用） */
    @JvmStatic
    fun remove(key: String) = edit { it.remove(key) }

    /**
     * 类型化读取的统一兜底（[getBool] 的注释里写了为什么必须有它）。
     *
     * 只吞 `ClassCastException`：那正是「键的历史类型与本次读取不一致」这一种可预期情况。
     * 其它异常（例如偏好被系统判为损坏）也一并降级 —— 时间管理的读路径不值得为任何一种
     * 异常把用户挡在 App 外面，能读到多少算多少。
     */
    private inline fun <T> guardedRead(key: String, def: T, block: () -> T): T =
        try {
            block()
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "pref read failed, fall back to default: $key", e)
            def
        }

    // ==========================================================================================
    // 原子多键写（跨键语义必须一起生效，否则会留下「键写了值没写」的半截状态）
    // ==========================================================================================

    /**
     * 一次写完「今天是什么日子」的两个键。
     *
     * 必须原子：[DailyPlan.dayType] 的判据是「DAY_KEY 等于今天」+「DAY_TYPE 是合法值」。
     * 若分两次 apply，中间进程被杀就会留下 `DAY_KEY=今天` 但 `DAY_TYPE` 还是昨天的值，
     * 于是今天的类型被误判成昨天那个 —— 上学日/休息日配额差 3 倍以上。
     */
    @JvmStatic
    fun putDayType(dayKey: String, dayType: String) = edit {
        it.putString(DAY_KEY, dayKey)
        it.putString(DAY_TYPE, dayType)
    }

    /**
     * 一次写完「今天用了多久」的两个键。
     *
     * 同样必须原子：[DailyPlan.usedMsToday] 先看 `USED_DAY` 再看 `USED_MS`。
     * 若「日期已推进到今天、但毫秒数还是昨天的大数」，用户会在早上看到「今天已用 6 小时」
     * 而被直接拦在门外 —— 这是最不能接受的失效方式。
     */
    @JvmStatic
    fun putUsage(dayKey: String, usedMs: Long) = edit {
        it.putString(USED_DAY, dayKey)
        it.putLong(USED_MS, usedMs)
    }

    // ==========================================================================================
    // 带类型/夹紧的访问器（判定逻辑只从这里取值，保证「脏输入不会算出负数配额」）
    // ==========================================================================================

    /** 使用控制总开关是否打开 */
    @JvmStatic
    fun isEnabled(): Boolean = getBool(ENABLE, DEFAULT_ENABLE)

    /** 写使用控制总开关（「关闭要等 120 秒」由 UI 用 [toggleOffWaitSec] 自己守，逻辑层不拦） */
    @JvmStatic
    fun setEnabled(on: Boolean) = putBool(ENABLE, on)

    /** 上学日配额（分钟），夹到 `1..1440`：0 会让用户当天一打开就被拦，属于输入错误而不是有效配置 */
    @JvmStatic
    fun schoolQuotaMin(): Int = getInt(SCHOOL_QUOTA_MIN, DEFAULT_SCHOOL_QUOTA_MIN).coerceIn(1, MAX_SCHOOL_QUOTA_MIN)

    /** 写上学日配额（同样夹紧后才落盘，避免把非法值写进偏好） */
    @JvmStatic
    fun setSchoolQuotaMin(minutes: Int) = putInt(SCHOOL_QUOTA_MIN, minutes.coerceIn(1, MAX_SCHOOL_QUOTA_MIN))

    /** 「不等待」档上界（分钟），夹到 `1..60`（理由见 [MAX_NO_WAIT_MIN]） */
    @JvmStatic
    fun noWaitMin(): Int = getInt(COUNTDOWN_NO_WAIT_MIN, DEFAULT_NO_WAIT_MIN).coerceIn(1, MAX_NO_WAIT_MIN)

    /** 中档等待秒数，夹到 `0..3600`（0 = 不等待，是合法配置） */
    @JvmStatic
    fun midWaitSec(): Int = getInt(COUNTDOWN_MID_WAIT_SEC, DEFAULT_MID_WAIT_SEC).coerceIn(0, MAX_WAIT_SEC)

    /** 高档等待秒数，夹到 `0..3600` */
    @JvmStatic
    fun highWaitSec(): Int = getInt(COUNTDOWN_HIGH_WAIT_SEC, DEFAULT_HIGH_WAIT_SEC).coerceIn(0, MAX_WAIT_SEC)

    /** 「之后不再倒计时」的时刻（当天第几分钟），夹到 `0..1439` */
    @JvmStatic
    fun noCountdownAfterMin(): Int =
        getInt(NO_COUNTDOWN_AFTER, DEFAULT_NO_COUNTDOWN_AFTER_MIN).coerceIn(0, MAX_MINUTE_OF_DAY)

    /** 关闭总开关的等待秒数，夹到 `1..3600`（0 秒等于没有这道闸，不允许） */
    @JvmStatic
    fun toggleOffWaitSec(): Int = getInt(TOGGLE_OFF_WAIT_SEC, DEFAULT_TOGGLE_OFF_WAIT_SEC).coerceIn(1, MAX_WAIT_SEC)

    /** 配额调大的等待秒数，夹到 `1..3600` */
    @JvmStatic
    fun quotaIncreaseWaitSec(): Int =
        getInt(QUOTA_INCREASE_WAIT_SEC, DEFAULT_QUOTA_INCREASE_WAIT_SEC).coerceIn(1, MAX_WAIT_SEC)

    /** 时间银行总开关（关闭受限的判定在 [TimeBank.setEnabled]） */
    @JvmStatic
    fun isBankEnabled(): Boolean = getBool(BANK_ENABLE, DEFAULT_BANK_ENABLE)

    /** 写时间银行总开关（只给 [TimeBank.setEnabled] 用，绕不过「有借入不能关」的检查） */
    @JvmStatic
    fun setBankEnabled(on: Boolean) = putBool(BANK_ENABLE, on)

    /** 总借入上限（分钟），夹到 `1..43200` */
    @JvmStatic
    fun bankTotalLimitMin(): Int =
        getInt(BANK_TOTAL_LIMIT_MIN, DEFAULT_BANK_TOTAL_LIMIT_MIN).coerceIn(1, MAX_BANK_TOTAL_LIMIT_MIN)

    /** 借入窗口天数，夹到 `1..30` */
    @JvmStatic
    fun bankMaxDays(): Int = getInt(BANK_MAX_DAYS, DEFAULT_BANK_MAX_DAYS).coerceIn(1, MAX_BANK_MAX_DAYS)

    /** 长按解除秒数，夹到 `1..600` */
    @JvmStatic
    fun longPressReleaseSec(): Int =
        getInt(LONG_PRESS_RELEASE_SEC, DEFAULT_LONG_PRESS_RELEASE_SEC).coerceIn(1, MAX_LONG_PRESS_RELEASE_SEC)

    // ==========================================================================================
    // 日期换算（包内共用；无依赖实现）
    //
    // 全部走 `Calendar.getInstance()`：它读系统默认时区，也就是用户在设置里选的那个时区。
    // **绝不能用 UTC** —— 中国用户 UTC+8，用 UTC 算「今天」会在 08:00 之前把日期算成昨天，
    // 跨天重置与 21:30 判断全部错位 8 小时。
    // ==========================================================================================

    /** 今天（设备本地时区）的 `yyyy-MM-dd`。字典序恰好等于时间序，所以可以直接用字符串比大小。 */
    @JvmStatic
    fun todayKey(): String = dateKeyOf(System.currentTimeMillis())

    /**
     * 相对今天偏移 [days] 天的日期键（负数 = 过去）。
     *
     * 用 `Calendar.add(DAY_OF_YEAR, n)` 而不是「毫秒加 n×86400000」：后者在夏令时切换那天
     * 会偏一小时，从而算出错误的日期。
     */
    @JvmStatic
    fun offsetDateKey(days: Int): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = System.currentTimeMillis()
        calendar.add(Calendar.DAY_OF_YEAR, days)
        return dateKeyOf(calendar.timeInMillis)
    }

    /**
     * 今天 00:00:00.000 的**墙钟**毫秒（`System.currentTimeMillis()` 坐标系）。
     *
     * 只用于「把一段已经过去的时长按午夜切开」（见 [UsageTracker]）：
     * elapsedRealtime 与墙钟是两个坐标系，没有别的办法把两者对上。
     * 用 `set(HOUR_OF_DAY, 0)` 而不是「减去时分秒毫秒」，夏令时那天才正确。
     */
    @JvmStatic
    fun startOfTodayWall(): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = System.currentTimeMillis()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * 任意墙钟毫秒 → 本地日期键。
     *
     * [UsageTracker] 结算跨午夜的时长时需要问「这一段是从哪天开始的」，所以必须公开；
     * 它同时也是 [todayKey] / [offsetDateKey] 的实现基础。
     */
    @JvmStatic
    fun dateKeyOfWall(wallMs: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = wallMs
        val year = calendar.get(Calendar.YEAR).toString().padStart(4, '0')
        val month = (calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "$year-$month-$day"
    }

    /** 内部别名，让上面的调用点读起来短一点 */
    private fun dateKeyOf(wallMs: Long): String = dateKeyOfWall(wallMs)

    /** 日志标签（[guardedRead] 兜住的读取失败靠它过滤） */
    private const val TAG = "time-prefs"
}
