package com.RobinNotBad.BiliClient.model

import java.io.Serializable

class MedalInfo : Serializable {
    @JvmField var target_id: Long = 0
    @JvmField var level: Int = 0
    @JvmField var medal_name: String? = null
    @JvmField var medal_color_start: Int = 0
    @JvmField var medal_color_end: Int = 0
    @JvmField var medal_color_border: Int = 0
    @JvmField var guard_level: Int = 0
    @JvmField var wearing_status: Int = 0
    @JvmField var medal_id: Long = 0
    @JvmField var intimacy: Int = 0
    @JvmField var next_intimacy: Int = 0
    @JvmField var today_feed: Int = 0
    @JvmField var day_limit: Int = 0
    @JvmField var guard_icon: String? = null
    @JvmField var honor_icon: String? = null
    @JvmField var target_name: String? = null
    @JvmField var target_icon: String? = null
    @JvmField var link: String? = null
    @JvmField var live_status: Int = 0
    @JvmField var offical: Int = 0
    @JvmField var uinfo_medal_name: String? = null
    @JvmField var uinfo_medal_level: Int = 0
    @JvmField var uinfo_medal_color_start: Int = 0
    @JvmField var uinfo_medal_color_end: Int = 0
    @JvmField var uinfo_medal_color_border: Int = 0
    @JvmField var uinfo_medal_color: Int = 0
    @JvmField var uinfo_medal_id: Long = 0
    @JvmField var uinfo_medal_typ: Int = 0
    @JvmField var uinfo_medal_is_light: Int = 0
    @JvmField var uinfo_medal_ruid: Long = 0
    @JvmField var uinfo_medal_guard_level: Int = 0
    @JvmField var uinfo_medal_score: Int = 0
    @JvmField var uinfo_medal_guard_icon: String? = null
    @JvmField var uinfo_medal_honor_icon: String? = null
    @JvmField var v2_medal_color_start: String? = null
    @JvmField var v2_medal_color_end: String? = null
    @JvmField var v2_medal_color_border: String? = null
    @JvmField var v2_medal_color_text: String? = null
    @JvmField var v2_medal_color_level: String? = null
    @JvmField var user_receive_count: Int = 0
}
