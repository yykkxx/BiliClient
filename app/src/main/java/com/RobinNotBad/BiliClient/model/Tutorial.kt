package com.RobinNotBad.BiliClient.model


class Tutorial {
    @JvmField var name: String? = null
    @JvmField var description: String? = null
    @JvmField var imgid: String? = null
    @JvmField var type: Int = 0
    @JvmField var content: List<CustomText>? = null
}
