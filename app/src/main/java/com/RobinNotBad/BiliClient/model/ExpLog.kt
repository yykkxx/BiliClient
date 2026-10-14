package com.RobinNotBad.BiliClient.model

class ExpLog {
    @JvmField var delta: Int = 0
    @JvmField var time: String? = null
    @JvmField var reason: String? = null

    constructor(delta: Int, time: String?, reason: String?) {
        this.delta = delta
        this.time = time
        this.reason = reason
    }
}
