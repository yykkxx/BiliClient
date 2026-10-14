package com.RobinNotBad.BiliClient.model

import org.json.JSONObject

class ApiResult {
    @JvmField var code: Int = 0
    @JvmField var message: String? = null
    @JvmField var offset: Long = 0
    @JvmField var timestamp: Long = 0
    @JvmField var business: String? = ""
    @JvmField var isBottom: Boolean = false
    @JvmField var result: Any? = null

    constructor()

    constructor(code: Int, message: String?) {
        this.code = code
        this.message = message
    }

    constructor(fullResult: JSONObject) {
        this.code = fullResult.optInt("code", -1)
        this.message = fullResult.optString("message", "json_format_error")
    }

    fun fromJson(fullResult: JSONObject): ApiResult {
        this.code = fullResult.optInt("code", -1)
        this.message = fullResult.optString("message", "json_format_error")
        return this
    }

    fun setOffset(timestamp: Long, offset: Long, business: String?): ApiResult {
        if (timestamp != 0L) this.timestamp = timestamp
        this.offset = offset
        this.business = business
        return this
    }

    fun setBottom(isBottom: Boolean): ApiResult {
        this.isBottom = isBottom
        return this
    }

    fun setResult(result: Any?): ApiResult {
        this.result = result
        return this
    }
}
