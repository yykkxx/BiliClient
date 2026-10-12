package com.RobinNotBad.BiliClient.model

/**
 * 弹幕分段响应
 * 对应 bilibili.community.service.dm.v1.DmSegMobileReply
 *
 * Kotlin 重构版（原 DmSegMobileReply.java）。
 * elems 使用 @JvmField 暴露为 public 字段，保持 Java 端与库内反射访问兼容。
 */
class DmSegMobileReply(
    @JvmField var elems: MutableList<DanmakuElem> = ArrayList()
) {
    val isEmpty: Boolean get() = elems.isEmpty()
    val size: Int get() = elems.size
}
