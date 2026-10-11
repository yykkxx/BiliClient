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
