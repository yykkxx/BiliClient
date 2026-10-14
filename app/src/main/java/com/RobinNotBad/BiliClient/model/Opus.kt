package com.RobinNotBad.BiliClient.model

import java.util.ArrayList

class Opus {
    @JvmField var id: Long = 0
    @JvmField var type: Int = 0
    @JvmField var commentId: Long = 0
    @JvmField var commentType: Int = 0
    @JvmField var title: String = ""
    @JvmField var cover: String = ""
    @JvmField var content: String = ""
    @JvmField var pubTime: String = ""
    @JvmField var upInfo: UserInfo? = null
    @JvmField var stats: Stats? = null
    @JvmField var topImages: ArrayList<String>? = null
    @JvmField var paragraphs: Array<OpusParagraph?>? = null


    @JvmField var parsedId: Long = 0

    constructor(type: Int, id: Long) {
        this.type = type
        this.parsedId = id
    }

    constructor()

    companion object {
        const val TYPE_DYNAMIC: Int = 1

        const val TYPE_ARTICLE: Int = 2

        const val TYPE_DYNAMIC_OLD_STYLE: Int = 3
    }
}
