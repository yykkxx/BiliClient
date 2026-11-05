package com.RobinNotBad.BiliClient.util

/**
 * v5.2.0「时间管理」套件的**偏好键登记处**（Lead 独占文件）。
 *
 * ## 为什么要单独一个文件
 * 这套功能横跨三个实现（统计 / 时间银行 / 倒计时页面），三个实现各自会定义自己对象里的常量。
 * 如果设置页去引用 `UsageStatsManager.PREF_REMINDER_ENABLE`，设置页就依赖了那个类的落地顺序，
 * 并行开发时很容易「设置页先写、那边还没建」。所以把所有键**集中登记在这里**，
 * 设置页（以及任何跨模块的地方）只引用本对象；各实现里的同名常量值与这里**必须一致**
 * （值就是字符串字面量，重复声明不会冲突，只是各自便于阅读）。
 *
 * ## 与旧键的关系
 * `lab_usage_limit` / `usage_limit_day` / `usage_limit_ms` 三个键是 v5.1.6 就有的，
 * **语义与默认值一律不动**（老用户升级上来行为原样），定义仍在 [UsageLimitManager]。
 * 本文件只登记 v5.2.0 新增的键。
 *
 * ## 默认值的统一口径
 * 全部新增键在默认值下，行为与 v5.1.6 **逐行为一致**：
 * - 统计默认**开**（用户要「有数据可看」，且它只记录、不干预使用，默认开最合理）；
 * - 倒计时独立页面默认**开**（它是需求 1 的「所有倒计时界面均为独立界面」的落地，默认开才符合需求）；
 * - 时间银行、定时提醒默认**关**（会改变使用行为/打扰用户，必须用户主动打开）。
 */
object TimeMgmtPrefs {

    // ---- 使用时长统计 ----
    /** 使用时长统计总开关（Bool，默认 true） */
    const val STATS_ENABLE = "usage_stats_enable"

    /** 单日分页停留，格式 `yyyy-MM-dd|页名:ms;页名:ms;...`（String，只留当天） */
    const val PAGE_STATS = "usage_page_stats"

    /** 多日总时长历史，格式 `yyyy-MM-dd:ms;...`（String，保留最近 30 天） */
    const val HISTORY = "usage_history"

    // ---- 定时提醒 ----
    /** 定时提醒开关（Bool，默认 false） */
    const val REMINDER_ENABLE = "usage_reminder_enable"

    /** 提醒间隔（Int 分钟，默认 15） */
    const val REMINDER_INTERVAL = "usage_reminder_interval_min"

    // ---- 倒计时独立页面 ----
    /** 倒计时用独立页面（Bool，默认 true） */
    const val COUNTDOWN_PAGE = "countdown_page_enable"

    // ---- 时间银行 ----
    /** 时间银行总开关（Bool，默认 false） */
    const val BANK_ENABLE = "time_bank_enable"

    /** 上学日每日配额（Int 分钟，默认 120） */
    const val BANK_SCHOOL_QUOTA = "time_bank_school_quota_min"

    /** 休息日每日配额（Int 分钟，默认 300） */
    const val BANK_REST_QUOTA = "time_bank_rest_quota_min"

    /** 同时预支总量上限（Int 分钟，默认 300 = 5 小时） */
    const val BANK_BORROW_LIMIT = "time_bank_borrow_limit_min"

    /** 超配额后等待多少秒可关闭本功能一天（Int 秒，默认 120） */
    const val BANK_WAIT_CLOSE_SEC = "time_bank_wait_close_sec"

    /** 当天类型：`"school"` / `"rest"`；空 = 尚未判定（String） */
    const val BANK_DAY_TYPE = "time_bank_day_type"

    /** `BANK_DAY_TYPE` 属于哪一天（`yyyy-MM-dd`；不等于今天即失效） */
    const val BANK_DAY_KEY = "time_bank_day_key"

    /** 已「关闭一天」的日期（等于今天则不限制）（String） */
    const val BANK_DISABLED_DAY = "time_bank_disabled_day"

    /** 预支记录，格式 `date:n,date:n,...`（String） */
    const val BANK_BORROWS = "time_bank_borrows"

    /** 当天类型取值：上学日 */
    const val DAY_SCHOOL = "school"

    /** 当天类型取值：休息日 */
    const val DAY_REST = "rest"
}
