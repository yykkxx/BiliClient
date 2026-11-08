package com.RobinNotBad.BiliClient.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager.widget.ViewPager
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs

/**
 * 图片查看专用：图片内部平移优先，只在横向边界继续向外拖动时交给 ViewPager。
 *
 * 不能用 scale > minimumScale 一刀切：那会拒绝 PhotoView 已经交还的边界手势，永远翻不了页。
 * dispatch 阶段观察整个手势（包括子 View 禁止拦截期间），锁定纵向/多指直到全部抬起。
 * 真正交接时重置 ViewPager 的 DOWN 起点，图片内部已走过的距离不计入翻页位移/速度。
 */
class PhotoViewpager : ViewPager {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private val gesture = PhotoPagerGestureState(ViewConfiguration.get(context).scaledPagingTouchSlop.toFloat())
    private var gestureStartPage = 0
    private var forwardedPhoto: PhotoView? = null
    private var forwardedDownTime = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = safelyHandleTouch {
        val action = touchAction(ev.actionMasked)
        gesture.observeEvent(action, ev.x, ev.y, ev.pointerCount, ev.eventTime)
        if (action == PhotoPagerTouchAction.DOWN) {
            gestureStartPage = currentItem
            forwardedPhoto = null
        }
        if (gesture.shouldCancelPager()) {
            // 翻页已接管后再落下第二指：撤销翻页并把后续完整手势交还当前图片。
            cancelPagerDrag(ev)
            startForwardedPhotoGesture(ev)
        }
        if (shouldReleasePhotoIntercept(action)) {
            // PhotoView 2.3.0 在 ACTION_DOWN 里就调用 requestDisallowInterceptTouchEvent(true)
            // （tests/images/photoview-bytecode.txt 400-411 行）。ViewGroup 之后会跳过
            // onInterceptTouchEvent，翻页永远接不了手 —— 这就是「放大后滑不到下一张」的根因。
            // 拖到图片横向边界且继续向外时，先撤掉该标志，让原生拦截路径有机会接手；
            // 一旦接手，ViewGroup 会自己给 PhotoView 补发 ACTION_CANCEL。
            requestDisallowInterceptTouchEvent(false)
        }
        val photo = forwardedPhoto
        val handled = if (photo != null) dispatchToPhoto(photo, ev) else super.dispatchTouchEvent(ev)
        if (action == PhotoPagerTouchAction.END) {
            // 原生 onTouchEvent 必须先收到 UP 才能结算翻页，不能在分发前清空拖动阶段。
            cancelNativePreparation(ev)
            forwardedPhoto = null
        }
        handled
    }

    private fun touchAction(action: Int): PhotoPagerTouchAction = when (action) {
        MotionEvent.ACTION_DOWN -> PhotoPagerTouchAction.DOWN
        MotionEvent.ACTION_MOVE -> PhotoPagerTouchAction.MOVE
        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> PhotoPagerTouchAction.POINTER_CHANGE
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> PhotoPagerTouchAction.END
        else -> PhotoPagerTouchAction.OTHER
    }

    /**
     * 这一帧是否要先撤掉子 View 的「禁止父级拦截」标志。
     *
     * 拆成独立函数就是因为这四个条件放在一起已经超过 detekt 的复杂度阈值；
     * 语义一个字都没变：移动帧 + 没有处于交还子 View 的转发状态 + Pager 尚未接管 + 当前帧可以翻页。
     */
    private fun shouldReleasePhotoIntercept(action: PhotoPagerTouchAction): Boolean {
        if (action != PhotoPagerTouchAction.MOVE) return false
        if (forwardedPhoto != null) return false
        if (gesture.pagerDragging) return false
        return canHandOffToPager()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = safelyHandleTouch {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 放大时也必须给原生 Pager DOWN；防止旧指针/拖动状态污染下一次手势。
                if (super.onInterceptTouchEvent(ev)) cancelPagerDrag(ev)
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 若 Pager 已接管，保留原生指针/速度直到 onTouchEvent 处理终止事件。
                if (!gesture.pagerDragging) cancelNativePreparation(ev)
                false
            }
            MotionEvent.ACTION_MOVE -> interceptHorizontalEdgeDrag(ev)
            else -> false
        }
    }

    private fun interceptHorizontalEdgeDrag(ev: MotionEvent): Boolean {
        if (!canHandOffToPager()) {
            if (gesture.handoffPending) cancelNativePreparation(ev)
            return false
        }
        if (!gesture.handoffPending) {
            // DOWN 本身不会清空 VelocityTracker，先 CANCEL，避免内部平移速度污染翻页。
            cancelNativePreparation(ev)
            val down = singlePointerDown(ev, 0, gesture.previousX, gesture.previousY, gesture.previousEventTime)
            try {
                super.onInterceptTouchEvent(down)
            } finally {
                down.recycle()
            }
            gesture.prepareHandoff()
        }
        val intercepted = withNativeEvent(ev) { super.onInterceptTouchEvent(it) }
        gesture.recordNativeIntercept(intercepted)
        return gesture.pagerDragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean = safelyHandleTouch {
        if (gesture.pagingBlocked) return@safelyHandleTouch true
        // 图片尚未加载时可能没有子 View 消费 DOWN；Pager 自己消费的路径也使用同一边界策略。
        if (!gesture.pagerDragging && ev.actionMasked == MotionEvent.ACTION_MOVE) interceptHorizontalEdgeDrag(ev)
        if (gesture.shouldSendPagerTouch()) return@safelyHandleTouch withNativeEvent(ev) { super.onTouchEvent(it) }
        if (ev.actionMasked == MotionEvent.ACTION_UP) performClick()
        true
    }

    private inline fun withNativeEvent(ev: MotionEvent, block: (MotionEvent) -> Boolean): Boolean {
        // 交接帧的合并历史可能仍含图片内部平移；只给 Pager 当前采样，避免重设 DOWN 后倒灌旧速度。
        val sample = MotionEvent.obtainNoHistory(ev)
        return try {
            block(sample)
        } finally {
            sample.recycle()
        }
    }

    private fun canHandOffToPager(): Boolean {
        val page = currentPhotoView() ?: return false
        val viewportWidth = (page.width - page.paddingLeft - page.paddingRight).toFloat()
        val rect = page.displayRect
        // 加载失败/未完成仍能滑去下一张；不能拿相邻页的缩放矩阵代替当前页。
        val left = rect?.left ?: 0f
        val right = rect?.right ?: viewportWidth
        return gesture.canPage(left, right, viewportWidth)
    }

    private fun cancelNativePreparation(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            super.onInterceptTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        gesture.cancelHandoff()
    }

    private fun cancelPagerDrag(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            super.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        snapBackToStart(ev)
        cancelNativePreparation(ev)
    }

    private fun snapBackToStart(ev: MotionEvent) {
        setCurrentItem(gestureStartPage, false)
        // 同页 setCurrentItem 会直接返回；CANCEL 的回弹动画必须显式终止，否则双指下图片仍在移动。
        val down = singlePointerDown(ev, 0, ev.getX(0), ev.getY(0), ev.eventTime)
        try {
            super.onTouchEvent(down) // 原生 DOWN 终止 Scroller。
            super.onInterceptTouchEvent(down) // 已完成的 Scroller 收尾为 IDLE。
        } finally {
            down.recycle()
        }
        val photo = currentPhotoView() ?: return
        scrollTo(photo.left - paddingLeft, 0)
        onPageScrolled(gestureStartPage, 0f, 0)
    }

    private fun startForwardedPhotoGesture(ev: MotionEvent) {
        val photo = currentPhotoView() ?: return
        // 新指可能插入 index 0；DOWN 必须用仍在屏上的旧指，且保留它真实的 pointerId。
        val index = gesture.forwardedDownPointerIndex(
            ev.pointerCount, ev.actionIndex, ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        )
        val down = singlePointerDown(ev, index, ev.getX(index), ev.getY(index), ev.eventTime)
        try {
            dispatchToPhoto(photo, down)
        } finally {
            down.recycle()
        }
        forwardedDownTime = ev.eventTime
        forwardedPhoto = photo
    }

    private fun singlePointerDown(ev: MotionEvent, index: Int, x: Float, y: Float, time: Long): MotionEvent {
        val properties = MotionEvent.PointerProperties()
        ev.getPointerProperties(index, properties)
        val coords = MotionEvent.PointerCoords()
        ev.getPointerCoords(index, coords)
        coords.x = x
        coords.y = y
        // 简化 obtain(x, y) 会强制 pointerId=0；之后 findPointerIndex 会找不到实际非零首指。
        return MotionEvent.obtain(
            time, time, MotionEvent.ACTION_DOWN, 1, arrayOf(properties), arrayOf(coords),
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags, ev.source, ev.flags
        )
    }

    private fun dispatchToPhoto(photo: PhotoView, ev: MotionEvent): Boolean {
        // downTime 是只读属性（android.jar 只有 getDownTime）：
        // 交接后的手势必须沿用原手势的 downTime，否则 PhotoView 会把每一帧都当成新手势的开始，
        // 单击/双击/拖动全部失效。用完整 obtain 重载携带它，而不是改字段。
        val downTime = if (forwardedPhoto === photo) forwardedDownTime else ev.downTime
        val properties = Array(ev.pointerCount) { MotionEvent.PointerProperties() }
        val coords = Array(ev.pointerCount) { MotionEvent.PointerCoords() }
        for (i in 0 until ev.pointerCount) {
            ev.getPointerProperties(i, properties[i])
            ev.getPointerCoords(i, coords[i])
        }
        val local = MotionEvent.obtain(
            downTime, ev.eventTime, ev.action, ev.pointerCount, properties, coords,
            ev.metaState, ev.buttonState, ev.xPrecision, ev.yPrecision, ev.deviceId, ev.edgeFlags,
            ev.source, ev.flags
        )
        local.offsetLocation((scrollX - photo.left).toFloat(), (scrollY - photo.top).toFloat())
        return try {
            photo.dispatchTouchEvent(local)
        } finally {
            local.recycle()
        }
    }

    private fun currentPhotoView(): PhotoView? {
        for (i in 0 until childCount) {
            val photo = getChildAt(i) as? PhotoView ?: continue
            if ((photo.tag as? Int) == currentItem) return photo
        }
        return null
    }

    private inline fun safelyHandleTouch(block: () -> Boolean): Boolean = try {
        block()
    } catch (ignored: IllegalArgumentException) {
        // 个别 ROM 抬指时 actionIndex/pointerCount 不同步，保留历史兼容兜底。
        false
    } catch (ignored: ArrayIndexOutOfBoundsException) {
        false
    }
}

// PHOTO_PAGER_LOGIC_START：专属回归直接编译此段生产逻辑，不使用 Python 镜像。
internal enum class PhotoPagerTouchAction { DOWN, MOVE, POINTER_CHANGE, END, OTHER }

/** 纯手势策略及交接阶段：END 分发完才复位，边界按当前帧真实显示矩形判断。 */
internal class PhotoPagerGestureState(private val touchSlop: Float) {
    private enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL }

    private var axis = Axis.UNDECIDED
    private var originX = 0f
    private var originY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var deltaX = 0f
    private var deltaY = 0f

    var previousX = 0f
        private set
    var previousY = 0f
        private set
    var pagingBlocked = false
        private set
    var previousEventTime = 0L
        private set
    private var lastEventTime = 0L
    var handoffPending = false
        private set
    var pagerDragging = false
        private set

    fun observeEvent(action: PhotoPagerTouchAction, x: Float, y: Float, pointerCount: Int, time: Long) {
        when (action) {
            PhotoPagerTouchAction.DOWN -> begin(x, y, time)
            PhotoPagerTouchAction.MOVE -> observe(x, y, pointerCount, time)
            PhotoPagerTouchAction.POINTER_CHANGE -> blockPaging()
            // UP/CANCEL 不再更新坐标/轴向，也不清拖动标志；原生 UP 仍需结算翻页。
            PhotoPagerTouchAction.END, PhotoPagerTouchAction.OTHER -> Unit
        }
    }

    fun begin(x: Float, y: Float, time: Long = 0L) {
        originX = x
        originY = y
        lastX = x
        lastY = y
        previousX = x
        previousY = y
        deltaX = 0f
        deltaY = 0f
        axis = Axis.UNDECIDED
        pagingBlocked = false
        previousEventTime = time
        lastEventTime = time
        cancelHandoff()
    }

    fun observe(x: Float, y: Float, pointerCount: Int, time: Long = lastEventTime) {
        previousEventTime = lastEventTime
        lastEventTime = time
        previousX = lastX
        previousY = lastY
        deltaX = x - lastX
        deltaY = y - lastY
        lastX = x
        lastY = y
        if (pointerCount > 1) blockPaging()
        if (axis != Axis.UNDECIDED || pagingBlocked) return
        val horizontalDistance = abs(x - originX)
        val verticalDistance = abs(y - originY)
        axis = when {
            horizontalDistance > touchSlop && horizontalDistance > verticalDistance * 2f -> Axis.HORIZONTAL
            verticalDistance > touchSlop -> Axis.VERTICAL
            else -> Axis.UNDECIDED
        }
    }

    fun blockPaging() {
        pagingBlocked = true
    }

    fun prepareHandoff() {
        handoffPending = true
    }

    fun recordNativeIntercept(intercepted: Boolean) {
        pagerDragging = intercepted
    }

    fun cancelHandoff() {
        handoffPending = false
        pagerDragging = false
    }

    fun shouldCancelPager(): Boolean = pagingBlocked && pagerDragging

    fun shouldSendPagerTouch(): Boolean = pagerDragging && !pagingBlocked

    fun forwardedDownPointerIndex(pointerCount: Int, actionIndex: Int, isPointerDown: Boolean): Int =
        if (isPointerDown && pointerCount > 1 && actionIndex == 0) 1 else 0

    fun canPage(imageLeft: Float, imageRight: Float, viewportWidth: Float): Boolean {
        if (pagingBlocked || axis != Axis.HORIZONTAL) return false
        // 内部横移后改为上下拖动时，不能因已有 HORIZONTAL 锁和少量横向抖动交接翻页。
        if (abs(deltaX) <= abs(deltaY) * 2f) return false
        if (!viewportWidth.isFinite() || viewportWidth <= 0f) return false
        if (!imageLeft.isFinite() || !imageRight.isFinite() || imageRight <= imageLeft) return false
        if (imageRight - imageLeft <= viewportWidth + EDGE_EPSILON_PX) return true
        return if (deltaX > 0f) {
            imageLeft >= -EDGE_EPSILON_PX
        } else {
            imageRight <= viewportWidth + EDGE_EPSILON_PX
        }
    }

    private companion object {
        const val EDGE_EPSILON_PX = 1f
    }
}
// PHOTO_PAGER_LOGIC_END
