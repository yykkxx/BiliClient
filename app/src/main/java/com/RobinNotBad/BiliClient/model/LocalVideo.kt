package com.RobinNotBad.BiliClient.model

import java.util.ArrayList

class LocalVideo {
    @JvmField var cover: String? = null
    @JvmField var title: String? = null
    @JvmField var pageList: ArrayList<String>? = null
    @JvmField var videoFileList: ArrayList<String>? = null
    @JvmField var danmakuFileList: ArrayList<String>? = null
    @JvmField var sizeList: ArrayList<Long>? = null
    @JvmField var size: Long = 0

    constructor()

    fun calcTotalSize() {
        size = 0
        for (pageSize in sizeList!!) {
            size += pageSize
        }
    }
}
