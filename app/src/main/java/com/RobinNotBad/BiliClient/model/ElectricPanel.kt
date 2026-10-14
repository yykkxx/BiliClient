package com.RobinNotBad.BiliClient.model

import java.util.ArrayList

/**
 * 充电公示数据模型
 */
class ElectricPanel {
    @JvmField var count: Int = 0                           // 本月充电人数
    @JvmField var list: MutableList<ElectricUser>? = ArrayList()             // 本月充电用户列表
    @JvmField var total_count: Int = 0                     // 总计充电次数
    @JvmField var total: Int = 0                           // 总计充电次数（同total_count）
    @JvmField var special_day: Int = 0                     // 0 作用尚不明确

    constructor() {
        this.list = ArrayList()
    }

    constructor(count: Int, list: MutableList<ElectricUser>?, total_count: Int) {
        this.count = count
        this.list = list ?: ArrayList()
        this.total_count = total_count
        this.total = total_count
    }

    /**
     * 判断是否有充电数据
     */
    fun hasData(): Boolean {
        return count > 0 && list != null && !list!!.isEmpty()
    }
}
