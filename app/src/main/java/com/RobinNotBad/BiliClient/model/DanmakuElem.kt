package com.RobinNotBad.BiliClient.model

/**
 * 弹幕元素
 * 对应 bilibili.community.service.dm.v1.DanmakuElem
 *
 * Kotlin 重构版（原 DanmakuElem.java）。
 * 字段全部使用 @JvmField 暴露为 public 字段，保持 Java 端与弹幕库内的同名字段访问兼容。
 */
class DanmakuElem(
    @JvmField var id: Long = 0L,             // 弹幕 dmid
    @JvmField var progress: Int = 0,         // 视频内弹幕出现时间（毫秒）
    @JvmField var mode: Int = 0,             // 弹幕类型 1-3:普通 4:底部 5:顶部 6:逆向 7:高级 8:代码 9:BAS
    @JvmField var fontsize: Int = 0,         // 弹幕字号 18:小 25:标准 36:大
    @JvmField var color: Int = 0,            // 弹幕颜色（十进制 RGB888）
    @JvmField var midHash: String? = null,   // 发送者 mid 的 HASH
    @JvmField var content: String? = null,   // 弹幕内容
    @JvmField var ctime: Long = 0L,          // 弹幕发送时间（时间戳）
    @JvmField var weight: Int = 0,           // 权重 0-10
    @JvmField var action: String? = null,    // 动作
    @JvmField var pool: Int = 0,             // 弹幕池 0:普通 1:字幕 2:特殊
    @JvmField var idStr: String? = null,     // 弹幕 dmid 字符串形式
    @JvmField var attr: Int = 0,             // 弹幕属性位
    @JvmField var animation: String? = null  // 动画
)
