package com.RobinNotBad.BiliClient.model

/**
 * 高能进度条数据模型
 */
class HighEnergyData {
    @JvmField var stepSec: Int = 0 // 采样间隔时间（秒）
    @JvmField var tagStr: String? = null // 标签字符串（用途未明）
    @JvmField var events: FloatArray? = null // 顶点值列表（弹幕密度数据）
    @JvmField var debug: String? = null // 调试信息

    constructor()

    constructor(stepSec: Int, tagStr: String?, events: FloatArray?, debug: String?) {
        this.stepSec = stepSec
        this.tagStr = tagStr
        this.events = events
        this.debug = debug
    }

    /**
     * 检查是否有有效的高能数据
     */
    fun hasValidData(): Boolean {
        return events != null && events!!.isNotEmpty()
    }

    /**
     * 获取视频总时长（根据数据点数量和采样间隔估算）
     */
    fun getEstimatedDuration(): Int {
        val events = this.events ?: return 0
        if (events.isEmpty()) {
            return 0
        }
        return events.size * stepSec
    }
}
