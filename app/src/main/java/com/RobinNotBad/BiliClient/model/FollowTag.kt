package com.RobinNotBad.BiliClient.model

class FollowTag {
    @JvmField var tagid: Int = 0
    @JvmField var name: String? = null
    @JvmField var count: Int = 0

    constructor(tagid: Int, name: String?, count: Int) {
        this.tagid = tagid
        this.name = name
        this.count = count
    }
}
