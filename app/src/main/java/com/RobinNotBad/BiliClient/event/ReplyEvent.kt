package com.RobinNotBad.BiliClient.event

import com.RobinNotBad.BiliClient.model.Reply

class ReplyEvent {
    var type: Int = 0
    var message: Reply? = null
    var pos: Int = 0
    var oid: Long = 0

    constructor(type: Int, message: Reply?, oid: Long) {
        this.type = type
        this.message = message
        this.oid = oid
    }

    constructor(type: Int, message: Reply?, pos: Int, oid: Long) {
        this.type = type
        this.message = message
        this.pos = pos
        this.oid = oid
    }
}
