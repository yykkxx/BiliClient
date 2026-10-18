package com.RobinNotBad.BiliClient.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

/**
 * 图片查看专用 ViewPager（#5 双指缩放优化）。
 *
 * 背景：图片查看页用 PhotoView 承载每张图，而 ViewPager 负责左右翻页。
 * 两者都要吃横向手势，天然冲突。原实现的做法是在 `dispatchTouchEvent` 里
 * 临时把 `requestDisallowInterceptTouchEvent` 置为 false 再调 super、然后置回 true，
 * 目的是绕开某些机型在抬指瞬间的指针越界崩溃。但这个写法有个副作用：
 * 把标志置 false 的瞬间，ViewPager 的 `onInterceptTouchEvent` 会被真正调用，
 * 于是**捏合缩放到一半时翻页逻辑可能把手势抢走**，表现为"放大一半突然跳页/回弹"。
 *
 * 现在的做法：
 * - 多指（`pointerCount > 1`）期间**直接拒绝拦截**，保证捏合手势完整地留在
 *   PhotoView 手里，缩放不会被打断；
 * - 单指时才交给 ViewPager 正常判断是否翻页（PhotoView 自己在缩放到边缘时通过
 *   `requestDisallowInterceptTouchEvent(false)` 把翻页权还给 ViewPager，因此
 *   "1x 时左右切图、放大后拖动看图"两种行为都保持正常）；
 * - 仍然兜住 `IllegalArgumentException` / `ArrayIndexOutOfBoundsException`（部分机型
 *   在 ACTION_POINTER_UP 时 `pointerCount` 与 `actionIndex` 不一致，取指针坐标会越界）。
 */
class PhotoViewpager : ViewPager {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 多指手势一律不拦截：让 PhotoView 安安静静地把这一轮缩放做完。
        // 注意 ACTION_DOWN 一定是单指，所以每次手势开始 ViewPager 都会正常复位内部状态。
        if (ev.pointerCount > 1) return false
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (ignored: IllegalArgumentException) {
            // 抬指瞬间的指针越界（某些 ROM 上 actionIndex 与 pointerCount 不同步）
            false
        } catch (ignored: ArrayIndexOutOfBoundsException) {
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (ignored: IllegalArgumentException) {
            false
        } catch (ignored: ArrayIndexOutOfBoundsException) {
            false
        }
    }
}
