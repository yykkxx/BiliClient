package com.RobinNotBad.BiliClient.event

import com.google.android.material.snackbar.Snackbar

class SnackEvent {
    var message: String? = null
    var startTime: Long = 0
    var duration: Int = 0

    constructor()

    constructor(message: String?) {
        this.message = message
        this.startTime = System.currentTimeMillis()
        this.duration = Snackbar.LENGTH_SHORT
    }

    constructor(message: String?, duration: Int) {
        this.message = message
        this.startTime = System.currentTimeMillis()
        this.duration = duration
    }

    constructor(message: String?, startTime: Long) {
        this.message = message
        this.startTime = startTime
        this.duration = Snackbar.LENGTH_SHORT
    }

    constructor(message: String?, startTime: Long, duration: Int) {
        this.message = message
        this.startTime = startTime
        this.duration = duration
    }
}
