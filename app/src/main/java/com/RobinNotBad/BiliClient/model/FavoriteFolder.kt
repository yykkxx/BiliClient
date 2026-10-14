package com.RobinNotBad.BiliClient.model

class FavoriteFolder {
    @JvmField var id: Long = 0L
    @JvmField var mediaId: Long = 0L
    @JvmField var name: String? = null
    @JvmField var cover: String? = null
    @JvmField var videoCount: Int = 0
    @JvmField var maxCount: Int = 0
    @JvmField var isDefault: Boolean = false

    constructor()
}
