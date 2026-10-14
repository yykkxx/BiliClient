package com.RobinNotBad.BiliClient.model.message

class MessageSettingItem(
    @JvmField var key: String,
    @JvmField var title: String,
    @JvmField var desc: String,
    @JvmField var type: Int,
    @JvmField var value: Boolean,
    @JvmField var options: Array<String>?
) {
    companion object {
        const val TYPE_SWITCH = 0
        const val TYPE_CHOOSE = 1
    }
}
