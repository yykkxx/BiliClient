package com.RobinNotBad.BiliClient.model

import org.json.JSONObject

class PrivateMsgSession {
    @JvmField var talkerUid: Long = 0
    @JvmField var unread: Int = 0
    @JvmField var contentType: Int = 0
    @JvmField var content: JSONObject? = null

    constructor()
}
