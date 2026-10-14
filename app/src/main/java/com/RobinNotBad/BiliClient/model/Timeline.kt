package com.RobinNotBad.BiliClient.model

import java.io.Serializable
import java.util.ArrayList

class Timeline : Serializable {
    class DayInfo : Serializable {
        @JvmField var date: String? = null
        @JvmField var date_ts: Long = 0
        @JvmField var day_of_week: Int = 0
        @JvmField var episodes: List<Episode>? = null
        @JvmField var is_today: Int = 0
    }

    class Episode : Serializable {
        @JvmField var cover: String? = null
        @JvmField var delay: Int = 0
        @JvmField var delay_id: Long = 0
        @JvmField var delay_index: String? = null
        @JvmField var delay_reason: String? = null
        @JvmField var ep_cover: String? = null
        @JvmField var episode_id: Long = 0
        @JvmField var pub_index: String? = null
        @JvmField var pub_time: String? = null
        @JvmField var pub_ts: Long = 0
        @JvmField var published: Int = 0
        @JvmField var follows: String? = null
        @JvmField var plays: String? = null
        @JvmField var season_id: Long = 0
        @JvmField var square_cover: String? = null
        @JvmField var title: String? = null
    }
}
