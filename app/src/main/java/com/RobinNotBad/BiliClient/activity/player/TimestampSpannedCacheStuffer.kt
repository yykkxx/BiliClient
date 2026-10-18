package com.RobinNotBad.BiliClient.activity.player

import android.graphics.Canvas
import android.graphics.Paint

import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.android.SpannedCacheStuffer

/**
 * 时间戳弹幕绘制填充器（#2）。
 *
 * 解决的问题：跳转时间弹幕原本没有任何视觉标识，用户根本不知道哪条弹幕可以点，
 * 只能靠一个巨大的 Toast 去提示（反而更干扰观看）。
 *
 * 现在把弹幕文本里的"时间戳"子串标成**蓝色 + 下划线**，一眼可辨；
 * 普通弹幕仍然走 [master.flame.danmaku.danmaku.model.android.SimpleTextCacheStuffer]
 * 的快速路径（`danmaku.obj == null` 时直接返回），不会因为引入富文本而拖慢绘制。
 *
 * 唯一需要额外处理的点是描边：父类 [SpannedCacheStuffer] 在富文本（`obj != null`）
 * 时**跳过**描边，只剩一层彩色文字，在浅色画面上几乎看不清。这里补上描边绘制，
 * 颜色/粗细沿用播放器原本的 stroke 配置，观感与其它弹幕一致。
 */
class TimestampSpannedCacheStuffer : SpannedCacheStuffer() {

    override fun drawStroke(
        danmaku: BaseDanmaku,
        lineText: String?,
        canvas: Canvas,
        left: Float,
        top: Float,
        paint: Paint
    ) {
        // 普通弹幕：沿用父类（即 SimpleTextCacheStuffer）的原生描边路径
        if (danmaku.obj == null) {
            super.drawStroke(danmaku, lineText, canvas, left, top, paint)
            return
        }
        // 富文本：父类不描边，这里按纯文本再描一次轮廓。
        // 此时 paint 已是 STROKE 样式、颜色为 textShadowColor，与普通弹幕一致。
        val text = lineText ?: danmaku.text?.toString() ?: return
        canvas.drawText(text, left, top, paint)
    }
}
