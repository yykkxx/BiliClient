package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.os.Build
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

    /** 一档表冠滚动的基准步长系数（再乘以系统滚动因子）。 */
    private const val BASE_STEP = 2

    /** 单档位移上限，避免设置过大时一档直接跨半页。 */
    private const val MAX_STEP_PX = 120

    /** 是否为本应用要处理的“表冠滚动”事件。 */
    fun isRotaryScroll(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        return (event.source and InputDevice.SOURCE_CLASS_MASK) == InputDevice.SOURCE_ROTARY_ENCODER
    }

    /**
     * 计算一档表冠滚动的像素增量（正数向下 / 内容前进）。
     *
     * @param prefKey 灵敏度偏好键；读取不到或不大于 0 时按 1.0 处理
     */
    fun scrollDelta(context: Context, prefKey: String, axisValue: Float): Int {
        val multiple = SharedPreferencesUtil.getFloat(prefKey, 0f).let { if (it > 0f) it else 1f }
        val factor = if (context.resources.displayMetrics.density > 0f)
            context.resources.displayMetrics.density else 1f
        val raw = -axisValue * factor * BASE_STEP * multiple
        return Math.round(raw.coerceIn(-MAX_STEP_PX.toFloat(), MAX_STEP_PX.toFloat()))
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
