package com.RobinNotBad.BiliClient.util

import android.content.SharedPreferences

/**
 * 被 luern0313 创建于 2020/5/4.
 * #以下代码部分来源于腕上哔哩的开源项目，有修改。感谢开源者做出的贡献！
 *
 * Kotlin 重构版（原 SharedPreferencesUtil.java）。
 * 常量与 sharedPreferences 字段使用 @JvmField 保持 Java 端直接字段访问兼容；
 * 方法使用 @JvmStatic 保持 Java 端静态调用方式不变。
 */
object SharedPreferencesUtil {
    @JvmField
    val LINK_ENABLE = "link_enable"

    @JvmField
    val RCMD_API_NEW_PARAM = "rcmd_api_new_param"

    // ==================== 账号管理（v5.1.5：端口 × 多账号） ====================
    // 取代 v5.1.1~v5.1.4 的「登录选项」/「三级迁移」/「实验室重构账号体系」三套实验开关。
    // 这里只放**键名常量**，全部读写逻辑收敛在 util/AccountStore（唯一事实来源）。
    // 旧键（app_rcmd_enabled / app_rcmd_identity / app_rcmd_use_web / api_migration_enabled /
    // lab_account_refactor / account_refactor_port）已删除常量：只由 AccountStore.ensureMigrated()
    // 用字面量一次性读取做迁移，此后全 App 不再读写它们。

    // 推荐页 API 端口（页签 1）：web / android / hd / tv
    const val ACCOUNT_MGMT_RCMD_PORT = "account_mgmt_rcmd_port"

    // 其它页面 API 端口（页签 2）：web / android / hd / tv
    const val ACCOUNT_MGMT_MAIN_PORT = "account_mgmt_main_port"

    // 当前激活的 web 账号 mid（空 = 无激活账号）
    const val ACCOUNT_MGMT_WEB_ACTIVE = "account_mgmt_web_active"

    // 某个 APP 端口的激活账号 mid：account_mgmt_app_active_<identityId>
    const val ACCOUNT_MGMT_APP_ACTIVE_PREFIX = "account_mgmt_app_active_"

    // 端口下的账号 mid 有序列表：account_mgmt_list_<port> = "mid1,mid2,..."
    const val ACCOUNT_MGMT_LIST_PREFIX = "account_mgmt_list_"

    // 账号凭据槽：account_mgmt_<port>_<mid>_cookies|csrf|access_token|refresh_token|name|face
    const val ACCOUNT_MGMT_ACCOUNT_PREFIX = "account_mgmt_"

    // 迁移完成哨兵（幂等）
    const val ACCOUNT_MGMT_MIGRATED = "account_mgmt_migrated"

    // ==================== 三端独立登录（v5.0.0 重构） ====================
    // 每个身份（hd / tv / phone）各有一套独立 token，可同时登录、互不干扰；
    // 禁止跨端转移：web Cookie 不再能转换成任何端 token，三端之间也不共用。
    // 槽位键由 AppRcmdApi.keyOf(identity, suffix) 动态生成：app_rcmd_{hd|tv|phone}_*

    @JvmField
    val APP_RCMD_BUVID = "app_rcmd_buvid"

    // ==================== 旧版本（v5.0.0-alpha 时代）单 token 键，仅用于迁移 ====================
    @JvmField
    val APP_RCMD_ACCESS_TOKEN = "app_rcmd_access_token"

    @JvmField
    val APP_RCMD_REFRESH_TOKEN = "app_rcmd_refresh_token"

    @JvmField
    val APP_RCMD_MID = "app_rcmd_mid"

    @JvmField
    val APP_RCMD_COOKIES = "app_rcmd_cookies"

    // 旧版本已存 token 属于哪个身份（迁移用；迁移完成后不再读写）
    @JvmField
    val APP_RCMD_TOKEN_IDENTITY = "app_rcmd_token_identity"

    @JvmField
    val MENU_SORT = "menu_sort"

    @JvmField
    val ASYNC_INFLATE_ENABLE = "async_inflate_enable"

    @JvmField
    val LOAD_TRANSITION = "load_transition"

    @JvmField
    val SNACKBAR_ENABLE = "snackbar_enable"

    @JvmField
    val STRICT_URL_MATCH = "strict_url_match"

    @JvmField
    val NO_VIP_COLOR = "no_vip_color"

    @JvmField
    val NO_MEDAL = "no_medal"

    @JvmField
    val REPLY_MARQUEE_NAME = "reply_marquee_name"

    @JvmField
    val NEW_DANMAKU_API = "new_danmaku_api"

    @JvmField
    val DYNAMIC_UPDATE_CHECK_ENABLE = "dynamic_update_check_enable"

    @JvmField
    val DYNAMIC_UPDATE_NUM = "dynamic_update_num"

    @JvmField
    val MESSAGE_UPDATE_CHECK_ENABLE = "message_update_check_enable"

    @JvmField
    val MESSAGE_UPDATE_NUM = "message_update_num"

    @JvmField
    val PRIVATE_MSG_UNREAD_BADGE_ENABLE = "private_msg_unread_badge_enable"

    @JvmField
    val PRIVATE_MSG_AUTO_READ_ENABLE = "private_msg_auto_read_enable"

    @JvmField
    val FOLLOW_GROUP_MODE = "follow_group_mode"

    @JvmField
    val NIGHT_REMINDER_ENABLE = "night_reminder_enable"

    @JvmField
    val SEARCH_DEFAULT_CONTENT_ENABLE = "search_default_content_enable"

    @JvmField
    val PLAYER_MEDIA_SESSION_ENABLE = "player_media_session_enable"

    // 5.1.2 双播放引擎：true=Media3 实验内核，false=IjkPlayer（默认，行为与 5.1.1 一致）。
    // 全新命名键（player_media3），只新增不改旧键；切换在下次创建播放器时生效（PlayerFactory）。
    const val PLAYER_MEDIA3 = "player_media3"

    @JvmField
    val RECENT_UP_DISPLAY_ENABLE = "recent_up_display_enable"

    @JvmField
    var cookies = "cookies"

    @JvmField
    var mid = "mid"

    @JvmField
    var csrf = "csrf"

    @JvmField
    var access_key = "access_key"

    @JvmField
    var refresh_token = "refresh_token"

    @JvmField
    var setup = "setup"

    @JvmField
    var last_version = "last_version"

    @JvmField
    var player = "player"

    @JvmField
    var padding_horizontal = "padding_horizontal"

    @JvmField
    var padding_vertical = "padding_vertical"

    @JvmField
    var cookie_refresh = "cookie_refresh"

    @JvmField
    var search_history = "search_history"

    @JvmField
    var cover_play_enabled = "cover_play_enabled"

    @JvmField
    var tutorial_version = "tutorial_version"

    // 由 BiliTerminal.onCreate 中赋值（Java 端直接字段访问）
    @JvmField
    var sharedPreferences: SharedPreferences? = null

    @JvmStatic
    fun getSharedPreferences(): SharedPreferences? {
        return sharedPreferences
    }

    @JvmStatic
    fun getString(key: String?, def: String?): String? {
        return sharedPreferences!!.getString(key, def)
    }

    @JvmStatic
    fun putString(key: String?, value: String?) {
        sharedPreferences!!.edit().putString(key, value).apply()
    }

    @JvmStatic
    fun getInt(key: String?, def: Int): Int {
        return sharedPreferences!!.getInt(key, def)
    }

    @JvmStatic
    fun putInt(key: String?, value: Int) {
        sharedPreferences!!.edit().putInt(key, value).apply()
    }

    @JvmStatic
    fun getLong(key: String?, def: Long): Long {
        return sharedPreferences!!.getLong(key, def)
    }

    @JvmStatic
    fun putLong(key: String?, value: Long) {
        sharedPreferences!!.edit().putLong(key, value).apply()
    }

    @JvmStatic
    fun getBoolean(key: String?, def: Boolean): Boolean {
        return sharedPreferences!!.getBoolean(key, def)
    }

    @JvmStatic
    fun putBoolean(key: String?, value: Boolean) {
        sharedPreferences!!.edit().putBoolean(key, value).apply()
    }

    @JvmStatic
    fun putFloat(key: String?, value: Float) {
        sharedPreferences!!.edit().putFloat(key, value).apply()
    }

    @JvmStatic
    fun getFloat(key: String?, def: Float): Float {
        return sharedPreferences!!.getFloat(key, def)
    }

    @JvmStatic
    fun removeValue(key: String?) {
        sharedPreferences!!.edit().remove(key).apply()
    }
}
