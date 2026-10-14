package com.RobinNotBad.BiliClient.model

class ViewPoint {
    @JvmField var content: String?
    @JvmField var from: Int = 0
    @JvmField var to: Int = 0
    @JvmField var type: Int = 0
    @JvmField var imgUrl: String?
    @JvmField var logoUrl: String?

    constructor(content: String?, from: Int, to: Int, type: Int, imgUrl: String?, logoUrl: String?) {
        this.content = content
        this.from = from
        this.to = to
        this.type = type
        this.imgUrl = imgUrl
        this.logoUrl = logoUrl
    }
}
