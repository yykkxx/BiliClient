package com.RobinNotBad.BiliClient.model

/**
 * 充电用户数据模型
 */
class ElectricUser {
    @JvmField var uname: String? = null        // 充电用户昵称
    @JvmField var avatar: String? = null       // 充电用户头像url
    @JvmField var mid: Long = 0L               // 充电对象mid
    @JvmField var pay_mid: Long = 0L           // 充电用户mid
    @JvmField var rank: Int = 0                // 充电用户排名
    @JvmField var trend_type: Int = 0          // 0 作用尚不明确
    @JvmField var message: String? = null      // 充电留言
    @JvmField var msg_hidden: Int = 0          // 0 作用尚不明确
    @JvmField var vip_info: VipInfo? = null    // 充电用户会员信息

    constructor()

    constructor(uname: String?, avatar: String?, mid: Long, pay_mid: Long, rank: Int, message: String?) {
        this.uname = uname
        this.avatar = avatar
        this.mid = mid
        this.pay_mid = pay_mid
        this.rank = rank
        this.message = message
    }

    /**
     * 充电用户会员信息
     */
    class VipInfo {
        @JvmField var vipDueMsec: Long = 0L     // 大会员过期时间
        @JvmField var vipStatus: Int = 0        // 大会员状态
        @JvmField var vipType: Int = 0          // 大会员类型 0:无 1:月大会员 2:年度及以上大会员

        constructor()

        constructor(vipDueMsec: Long, vipStatus: Int, vipType: Int) {
            this.vipDueMsec = vipDueMsec
            this.vipStatus = vipStatus
            this.vipType = vipType
        }
    }
}
