package com.RobinNotBad.BiliClient.model

class ArticleLine {
    @JvmField var type: Int = 0
    @JvmField var content: String? = null
    @JvmField var extra: String? = null

    constructor()

    constructor(type: Int, content: String?, extra: String?) {
        this.type = type
        this.content = content
        this.extra = extra
    }
}
