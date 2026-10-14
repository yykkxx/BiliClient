package com.RobinNotBad.BiliClient.model

import java.io.Serializable

class Dynamic : Serializable {
    @JvmField var dynamicId: Long = 0L
    @JvmField var type: String? = null
    @JvmField var comment_id: Long = 0L
    @JvmField var comment_type: Int = 0

    @JvmField var title: String? = null
    @JvmField var userInfo: UserInfo? = null
    @JvmField var content: CharSequence? = null
    @JvmField var pubTime: String? = null

    @JvmField var stats: Stats? = null

    @JvmField var major_type: String? = null
    @JvmField var major_object: Any? = null
    @JvmField var dynamic_forward: Dynamic? = null
    @JvmField var canDelete: Boolean = false

    constructor()

    companion object {
        const val DYNAMIC_TYPE_UGC_SEASON: String = "DYNAMIC_TYPE_UGC_SEASON"
    }
}
