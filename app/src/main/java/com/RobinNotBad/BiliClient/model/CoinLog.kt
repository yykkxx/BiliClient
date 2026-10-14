package com.RobinNotBad.BiliClient.model

class CoinLog {
    @JvmField var time: String? = null
    @JvmField var delta: Int = 0
    @JvmField var reason: String? = null

    constructor(time: String?, delta: Int, reason: String?) {
        this.time = time
        this.delta = delta
        this.reason = reason
    }
}
