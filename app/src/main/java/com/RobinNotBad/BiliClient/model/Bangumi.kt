package com.RobinNotBad.BiliClient.model

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList

class Bangumi {
    @JvmField var info: Info? = null
    @JvmField var sectionList: ArrayList<Section>? = null

    class Info {
        @JvmField var media_id: Long = 0
        @JvmField var season_id: Long = 0
        @JvmField var type: Int = 0
        @JvmField var count: Int = 0
        @JvmField var score: Float = 0f
        @JvmField var title: String? = null
        @JvmField var cover: String? = null
        @JvmField var cover_horizontal: String? = null
        @JvmField var type_name: String? = null
        @JvmField var area_name: String? = null
        @JvmField var indexShow: String? = null

        // 新增字段
        @JvmField var evaluate: String? = null // 简介
        @JvmField var staff: String? = null // 制作人员信息
        @JvmField var record: String? = null // 备案号
        @JvmField var subtitle: String? = null // 副标题
        @JvmField var publish: Publish? = null // 发布时间信息
        @JvmField var styles: ArrayList<String>? = null // 标签
        @JvmField var stat: Stat? = null // 状态数
        @JvmField var up_info: UpInfo? = null // UP主信息
        @JvmField var series: Series? = null // 系列信息
        @JvmField var seasons: ArrayList<Season>? = null // 同系列所有季信息
    }

    class Publish {
        @JvmField var is_finish: Int = 0 // 完结状态 0：未完结 1：已完结
        @JvmField var is_started: Int = 0 // 是否发布 0：未发布 1：已发布
        @JvmField var pub_time: String? = null // 发布时间
        @JvmField var pub_time_show: String? = null // 发布时间文字介绍
    }

    class Stat {
        @JvmField var favorites: Int = 0 // 收藏数
        @JvmField var series_follow: Int = 0 // 系列追番数
        @JvmField var views: Int = 0 // 播放数
        @JvmField var vt: Int = 0 // 虚拟观看数
    }

    class UpInfo {
        @JvmField var mid: Long = 0
        @JvmField var name: String? = null
        @JvmField var avatar: String? = null
    }

    class Series {
        @JvmField var series_id: Long = 0
        @JvmField var series_title: String? = null
    }

    class Season {
        @JvmField var media_id: Long = 0
        @JvmField var season_id: Long = 0
        @JvmField var season_title: String? = null
        @JvmField var cover: String? = null
        @JvmField var badge: String? = null
    }

    class Section {
        @JvmField var id: Long = 0
        @JvmField var type: Int = 0
        @JvmField var title: String? = null
        @JvmField var episodeList: ArrayList<Episode>? = null

        constructor()
    }

    class Episode {
        @JvmField var id: Long = 0
        @JvmField var aid: Long = 0
        @JvmField var cid: Long = 0
        @JvmField var title: String? = null
        @JvmField var title_long: String? = null
        @JvmField var cover: String? = null
        @JvmField var badge: String? = null //标记（如会员/限免）

        constructor()

        fun toPlayerData(): PlayerData {
            val data = PlayerData(PlayerData.TYPE_BANGUMI)
            data.aid = aid
            data.cid = cid
            data.title = title
            data.mid = SharedPreferencesUtil.getLong("mid", 0)
            return data
        }
    }
}
