package com.RobinNotBad.BiliClient.model

class CustomText {
    @JvmField var type: Int = 0 //用于标识普通文本或换行
    @JvmField var text: String? = null //内容
    @JvmField var style: String? = null //文本样式
    @JvmField var color: String? = null //文本颜色(rgb字符串)

    constructor()
}
