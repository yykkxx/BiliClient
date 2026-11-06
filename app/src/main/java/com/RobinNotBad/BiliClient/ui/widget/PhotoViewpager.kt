package com.RobinNotBad.BiliClient.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager
import com.github.chrisbanes.photoview.PhotoView

/**
 * 图片查看专用 ViewPager（v5.2.1 双指缩放优化）。
 *
 * # 背景：两个组件都要吃横向手势
 * PhotoView 负责「放大后拖着看图」，ViewPager 负责「左右翻页」，两者天然冲突。
 * 这里的全部逻辑只有一件事：**在什么情况下把横向手势让给图片、什么情况下让给翻页**。
 *
 * # 三层判断（顺序即优先级，改动前请想清楚）
 *
 * ## 第一层：多指手势一律不拦截
 * `pointerCount > 1` = 用户正在捏合缩放。这时候绝对不能翻页，否则表现为
 * "放大到一半突然跳页/回弹"（旧实现在某些机型上就是这样）。
 * 注意 ACTION_DOWN 一定是单指，所以每次手势开始 ViewPager 的内部状态都会正常复位，
 * 不会因为"一直不拦截"而在下一次单指滑动时失效。
 *
 * ## 第二层：当前页已经放大 → 不拦截
 * 图片处于放大态时，单指横向滑动应该**拖动图片**（看细节），而不是翻页。
 * 判据是当前页 `scale > minimumScale`（留一点容差，见 [SCALE_EPSILON]）。
 * 等图片拖到左右边界，PhotoView 自己的 `allowParentInterceptOnEdge` 会把翻页权还回来 ——
 * 所以"放大后拖到边缘再滑一次就能翻页"这条手感是自动成立的，不用在这里实现。
 *
 * ## 第三层：其余情况交给 ViewPager 正常判断
 * 1x 状态下的左右滑动就是翻页，这是用户最常用的行为。
 *
 * # 为什么要包 try/catch
 * 部分 ROM 在 `ACTION_POINTER_UP` 时 `pointerCount` 与 `actionIndex` 不同步，
 * 取指针坐标会抛 `IllegalArgumentException` / `ArrayIndexOutOfBoundsException`。
 * 这是历史遗留的机型兼容（v5.1.x 就踩过），保留。
 */
class PhotoViewpager : ViewPager {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 第一层：捏合中都别抢
        if (ev.pointerCount > 1) return false
        // 第二层：当前页已放大 → 让图片拖着看，不翻页
        if (isCurrentPageZoomed()) return false
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
        // onTouchEvent 里也要问一次：ViewPager 在"已经拦截过一次手势"后会走这条路，
        // 若此时图片恰好被放大（例如双击放大正在进行），继续翻页就会把图片的拖动手势抢走
        if (ev.pointerCount > 1) return false
        if (isCurrentPageZoomed()) return false
        return try {
            super.onTouchEvent(ev)
        } catch (ignored: IllegalArgumentException) {
            false
        } catch (ignored: ArrayIndexOutOfBoundsException) {
            false
        }
    }

    /**
     * 当前页是否处于放大态。
     *
     * 取不到当前页视图时返回 false（= 按未放大处理，允许翻页）：
     * 宁可翻页，也不要因为取不到视图把整个页面的横向手势锁死 —— 那样用户会觉得"卡住了"。
     *
     * 容差 [SCALE_EPSILON] 的必要性：`setScaleLevels` 的最小倍率在浮点运算后并非精确的 1.0f，
     * 而 `scale > minimumScale` 若不含容差，会在"刚刚回到 1x"时仍判定为放大，
     * 表现为"缩小回去了但翻不动页"。
     */
    private fun isCurrentPageZoomed(): Boolean {
        val page = currentPhotoView() ?: return false
        return try {
            page.scale > page.minimumScale + SCALE_EPSILON
        } catch (ignored: IllegalStateException) {
            // PhotoView 在未 attach 到窗口时取 scale 可能抛 IllegalStateException
            false
        }
    }

    /**
     * 找到"当前页"对应的 PhotoView。
     *
     * **不能用 `adapter.instantiateItem(...)`** 来找：那个方法是"创建并加入"语义，
     * 对 PagerAdapter 调它可能会新建视图、也可能把已存在的视图再 add 一次，
     * 在拦截手势的热路径上调用是**灾难**（每次滑动都建一个 View）。
     * 正确做法是在自己的子 View 里找 —— ViewPager 只保留当前页与相邻页，
     * 数量是 1~3 个，遍历成本可以忽略。
     *
     * 匹配依据是 Activity 给每页打的 `tag = 页索引`（见 ImageViewerActivity.createPhotoView）；
     * tag 对不上（例如将来别处复用了这个 ViewPager 却没打 tag）时退化为"任意一个 PhotoView"，
     * 因为本类只服务图片查看页，退化结果仍然是正确的页。
     */
    private fun currentPhotoView(): PhotoView? {
        for (i in 0 until childCount) {
            val pv = getChildAt(i) as? PhotoView ?: continue
            if ((pv.tag as? Int) == currentItem) return pv
        }
        // 退化：拿第一个 PhotoView（ViewPager 的 child 顺序与页序基本一致）
        for (i in 0 until childCount) {
            (getChildAt(i) as? PhotoView)?.let { return it }
        }
        return null
    }

    private companion object {
        /** 倍率判定容差：小于它就算"回到 1x"，允许翻页 */
        const val SCALE_EPSILON = 0.01f
    }
}
