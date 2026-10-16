package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 表冠（旋转输入）滚动处理的公共实现。
 *
 * 修复的问题（视频详情第一页滚轮划一下直接跳到底部）：
 * NestedScrollView / ScrollView 自身不会消费 SOURCE_ROTARY_ENCODER 的
 * ACTION_SCROLL 事件，事件被系统当成方向键交给焦点搜索处理——
 * 于是焦点一次性跑到页面里最后一个可聚焦控件上，容器随之滚到底。
 * RecyclerView 自己实现了 rotary 滚动，所以评论区/视频列表本来就是正常的。
 */
internal object RotaryScrollHelper {

    /** 一档表冠滚动的基准步长系数（再乘以系统滚动因子）。
     *  #5：由 2 降到 1——手表屏幕上微微转一下不应该滚大半屏。 */
    private const val BASE_STEP = 1

    /** 单档位移上限，避免设置过大时一档直接跨半页。
     *  #5：120px（约半屏）压到 40px（约 1/6 屏），转多档才会走远。 */
    private const val MAX_STEP_PX = 40

    /** 是否为本应用要处理的“表冠滚动”事件。 */
    fun isRotaryScroll(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        return (event.source and InputDevice.SOURCE_CLASS_MASK) == InputDevice.SOURCE_ROTARY_ENCODER
    }

    /**
     * 计算一档表冠滚动的像素增量（正数向下 / 内容前进）。
     *
     * #5：快速旋转时事件会高频到达（每秒十几档），不做限速的话
     * 平滑滚动叠加起来视觉上就是"微微一转滑出去很远"。
     * 这里对相邻事件的位移做节流：同一时间片内只允许有限的累计步进，
     * 多余的丢弃（表冠快速多转时宁可少滚也不失控）。
     *
     * @param prefKey 灵敏度偏好键；读取不到或不大于 0 时按 1.0 处理
     */
    fun scrollDelta(context: Context, prefKey: String, axisValue: Float): Int {
        val multiple = SharedPreferencesUtil.getFloat(prefKey, 0f).let { if (it > 0f) it else 1f }
        val factor = if (context.resources.displayMetrics.density > 0f)
            context.resources.displayMetrics.density else 1f
        val raw = -axisValue * factor * BASE_STEP * multiple

        // 限速：以 33ms 为一片（约一帧），每片最多 MAX_STEP_PX，
        // 快速连转时线性衰减而不是无脑累加
        val now = SystemClock.uptimeMillis()
        if (now - lastThrottleAt > THROTTLE_WINDOW_MS) {
            // 新的时间片，重置配额
            quotaLeft = MAX_STEP_PX
        }
        lastThrottleAt = now
        val allowed = Math.round(raw.coerceIn(-MAX_STEP_PX.toFloat(), MAX_STEP_PX.toFloat()))
            .let { step ->
                if (quotaLeft <= 0) 0
                else {
                    val take = if (Math.abs(step) <= quotaLeft) step else
                        Math.signum(step.toFloat()).toInt() * quotaLeft
                    quotaLeft -= Math.abs(take)
                    take
                }
            }
        return allowed
    }

    /** 节流窗口（毫秒），约两帧 */
    private const val THROTTLE_WINDOW_MS = 33L
    private var lastThrottleAt = 0L
    private var quotaLeft = MAX_STEP_PX

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
