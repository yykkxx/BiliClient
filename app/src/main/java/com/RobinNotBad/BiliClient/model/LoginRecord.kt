package com.RobinNotBad.BiliClient.model

class LoginRecord(mid: Long, deviceName: String?, loginType: String?, loginTime: String?, location: String?, ip: String?) {
    @JvmField var mid: Long = mid
    @JvmField var deviceName: String? = deviceName
    @JvmField var loginType: String? = loginType
    @JvmField var loginTime: String? = loginTime
    @JvmField var location: String? = location
    @JvmField var ip: String? = ip
}
