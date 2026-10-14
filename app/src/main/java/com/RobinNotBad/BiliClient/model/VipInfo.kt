package com.RobinNotBad.BiliClient.model


class VipInfo {
    @JvmField var isShortVip: Boolean = false
    @JvmField var isFreightOpen: Boolean = false
    @JvmField var level: Int = 0
    @JvmField var curExp: Long = 0
    @JvmField var nextExp: Long = 0
    @JvmField var isVip: Boolean = false
    @JvmField var isSeniorMember: Int = 0
    @JvmField var format060102: Int = 0
    @JvmField var isOverdueVip: Boolean = false
    @JvmField var vipStatus: Int = 0
    @JvmField var vipType: Int = 0
    @JvmField var keeptimeEnd: Long = 0
    @JvmField var vipDueDate: Long = 0
    @JvmField var vipIsAnnual: Boolean = false
    @JvmField var vipIsMonth: Boolean = false
    @JvmField var vipIsNewUser: Boolean = false
    @JvmField var bindPhone: String? = null
    @JvmField var taobaoAccount: String? = null
    @JvmField var privilegeList: List<Privilege>? = null

    class Privilege {
        @JvmField var type: Int = 0
        @JvmField var state: Int = 0
        @JvmField var expireTime: Long = 0
        @JvmField var vipType: Int = 0
        @JvmField var nextReceiveDays: Int = 0
        @JvmField var periodEndUnix: Long = 0
    }
}
