package com.RobinNotBad.BiliClient.model


class MessageCard {

    @JvmField var id: Long = 0
    @JvmField var user: List<UserInfo?>? = null
    @JvmField var timeStamp: Long = 0
    @JvmField var timeDesc: String? = ""
    @JvmField var content: String? = null
    @JvmField var videoCard: VideoCard? = null
    @JvmField var replyInfo: Reply? = null
    @JvmField var dynamicInfo: Reply? = null
    @JvmField var subjectId: Long = 0
    @JvmField var businessId: Int = 0
    @JvmField var itemType: String? = null
    @JvmField var getType: Int = 0
    @JvmField var sourceId: Long = 0
    @JvmField var rootId: Long = 0
    @JvmField var targetId: Long = 0

    class Cursor {
        @JvmField val is_end: Boolean
        @JvmField val id: Long
        @JvmField val time: Long

        constructor(is_end: Boolean, id: Long, time: Long) {
            this.is_end = is_end
            this.id = id
            this.time = time
        }
    }

    companion object {
        const val GET_TYPE_REPLY: Int = 0

        const val GET_TYPE_AT: Int = 1

        const val GET_TYPE_LIKE: Int = 2
    }
}
