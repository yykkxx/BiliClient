package com.RobinNotBad.BiliClient.model

import org.json.JSONArray
import org.json.JSONObject

class PrivateMessage {

    @JvmField var content: JSONObject? = JSONObject()

    @JvmField var content_array: JSONArray? = JSONArray()
    @JvmField var type: Int = 0
    @JvmField var timestamp: Long = 0
    @JvmField var uid: Long = 0
    @JvmField var name: String? = ""
    @JvmField var msgId: Long = 0
    @JvmField var msgSeqno: Long = 0
    @JvmField var msg_source: Int = 0

    constructor(uid: Long, type: Int, content: JSONObject?, timestamp: Long, name: String?, msgId: Long, msgSeqno: Long) {
        this.uid = uid
        this.type = type
        this.content = content
        this.timestamp = timestamp
        this.name = name
        this.msgId = msgId
        this.msgSeqno = msgSeqno
    }

    constructor()

    companion object {
        const val TYPE_TEXT: Int = 1

        const val TYPE_VIDEO: Int = 7

        const val TYPE_PIC: Int = 2

        const val TYPE_RETRACT: Int = 5

        const val TYPE_FACE: Int = 6

        const val TYPE_NOMAL_CARD: Int = 10

        const val TYPE_PIC_CARD: Int = 13

        const val TYPE_TEXT_WITH_VIDEO: Int = 16

        const val TYPE_SYSTEM: Int = 18
    }
}
