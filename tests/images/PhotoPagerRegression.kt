package com.RobinNotBad.BiliClient.ui.widget

private var cases = 0

private fun regression(name: String, block: () -> Unit) {
    block()
    cases++
    println("PASS $name")
}

private fun state(): PhotoPagerGestureState = PhotoPagerGestureState(10f)

private fun PhotoPagerGestureState.event(
    action: PhotoPagerTouchAction, x: Float, y: Float = 0f, pointers: Int = 1, time: Long = 16L
) = observeEvent(action, x, y, pointers, time)

private fun PhotoPagerGestureState.move(x: Float, y: Float = 0f, pointers: Int = 1, time: Long = 16L) =
    event(PhotoPagerTouchAction.MOVE, x, y, pointers, time)

fun main() {
    regression("fit image can page in both horizontal directions") {
        for (x in listOf(-40f, 40f)) {
            val s = state()
            s.begin(0f, 0f)
            s.move(x)
            check(s.canPage(0f, 100f, 100f))
            check(s.canPage(20f, 80f, 100f))
        }
    }
    regression("zoomed interior stays with PhotoView") {
        val s = state()
        s.begin(0f, 0f)
        s.move(-40f)
        check(!s.canPage(-80f, 180f, 100f))
        s.move(40f)
        check(!s.canPage(-80f, 180f, 100f))
    }
    regression("left edge only releases an outward rightward drag") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        check(s.canPage(0f, 200f, 100f))
        s.move(20f)
        check(!s.canPage(0f, 200f, 100f))
    }
    regression("right edge only releases an outward leftward drag") {
        val s = state()
        s.begin(0f, 0f)
        s.move(-40f)
        check(s.canPage(-100f, 100f, 100f))
        s.move(-20f)
        check(!s.canPage(-100f, 100f, 100f))
    }
    regression("one-pixel edge rounding is tolerated, interior is not") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        check(s.canPage(-1f, 199f, 100f))
        check(!s.canPage(-1.01f, 198.99f, 100f))
        s.move(-40f)
        check(s.canPage(-99f, 101f, 100f))
        check(!s.canPage(-98.99f, 101.01f, 100f))
    }
    regression("touch slop and exactly two-to-one diagonal never page") {
        val s = state()
        s.begin(0f, 0f)
        s.move(10f)
        check(!s.canPage(0f, 100f, 100f))
        s.begin(0f, 0f)
        s.move(30f, 15f)
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("vertical starts lock the whole gesture") {
        val s = state()
        s.begin(0f, 0f)
        s.move(2f, 40f)
        check(!s.canPage(0f, 100f, 100f))
        s.move(200f, 40f)
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("vertical motion after interior horizontal panning cannot hand off") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        check(!s.canPage(-50f, 150f, 100f))
        s.move(41f, 40f)
        check(!s.canPage(0f, 200f, 100f))
        s.move(80f, 41f)
        check(s.canPage(0f, 200f, 100f))
    }
    regression("stationary edge does not page") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        s.move(40f)
        check(!s.canPage(0f, 200f, 100f))
    }
    regression("invalid display matrices and viewport cannot release") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            check(!s.canPage(0f, 200f, width))
        }
        for (left in listOf(Float.NaN, Float.NEGATIVE_INFINITY, 200f, 201f)) {
            check(!s.canPage(left, 200f, 100f))
        }
        for (right in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            check(!s.canPage(0f, right, 100f))
        }
    }
    regression("every move uses current matrix rather than stale zoom scale") {
        val s = state()
        s.begin(0f, 0f)
        s.move(-40f)
        check(!s.canPage(-80f, 180f, 100f))
        s.move(-60f)
        check(s.canPage(-100f, 100f, 100f))
    }
    regression("handoff anchor excludes hundreds of pixels of internal panning") {
        val s = state()
        s.event(PhotoPagerTouchAction.DOWN, 500f, time = 100L)
        s.move(300f, time = 116L)
        check(!s.canPage(-50f, 150f, 100f))
        s.move(250f, time = 132L)
        check(s.canPage(-100f, 100f, 100f))
        check(s.previousX == 300f)
        check(s.previousY == 0f)
        check(s.previousEventTime == 116L)
        s.prepareHandoff()
        check(s.handoffPending)
        check(!s.pagerDragging)
        s.recordNativeIntercept(false)
        check(s.handoffPending)
        check(!s.shouldSendPagerTouch())
        s.move(230f, time = 148L)
        s.recordNativeIntercept(true)
        check(s.shouldSendPagerTouch())
    }
    regression("new DOWN after cancelled preparation has a fresh anchor") {
        val s = state()
        s.begin(0f, 0f, 100L)
        s.move(40f, time = 116L)
        s.prepareHandoff()
        s.cancelHandoff()
        check(!s.handoffPending)
        check(!s.shouldSendPagerTouch())
        s.move(80f, time = 132L)
        check(s.previousX == 40f && s.previousEventTime == 116L)
        s.prepareHandoff()
        check(s.handoffPending)
    }
    regression("UP keeps native drag alive until dispatch finishes") {
        val s = state()
        s.begin(0f, 0f)
        s.move(-40f)
        s.prepareHandoff()
        s.recordNativeIntercept(true)
        val x = s.previousX
        s.event(PhotoPagerTouchAction.END, -60f, y = 100f)
        check(s.shouldSendPagerTouch())
        check(!s.shouldCancelPager())
        check(s.previousX == x)
        s.cancelHandoff()
        check(!s.pagerDragging && !s.handoffPending)
    }
    regression("CANCEL uses same deferred terminal cleanup, no page target invented") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        s.prepareHandoff()
        s.recordNativeIntercept(true)
        s.event(PhotoPagerTouchAction.END, 40f)
        check(s.shouldSendPagerTouch())
        s.cancelHandoff()
        check(!s.shouldSendPagerTouch())
    }
    regression("UP without interception never reaches native page settlement") {
        val s = state()
        s.begin(0f, 0f)
        s.move(5f)
        s.event(PhotoPagerTouchAction.END, 50f)
        check(!s.shouldSendPagerTouch())
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("POINTER_DOWN during preparation blocks handoff") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        s.prepareHandoff()
        s.event(PhotoPagerTouchAction.POINTER_CHANGE, 40f, pointers = 2)
        check(!s.shouldCancelPager())
        check(!s.shouldSendPagerTouch())
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("second finger after interception cancels pager before it can change active pointer") {
        val s = state()
        s.begin(0f, 0f)
        s.move(-40f)
        s.prepareHandoff()
        s.recordNativeIntercept(true)
        s.event(PhotoPagerTouchAction.POINTER_CHANGE, -40f, pointers = 2)
        check(s.shouldCancelPager())
        check(!s.shouldSendPagerTouch())
        s.cancelHandoff()
        check(!s.shouldCancelPager())
        s.move(-150f, pointers = 2)
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("remaining single finger after pinch stays blocked through UP") {
        val s = state()
        s.begin(0f, 0f)
        s.event(PhotoPagerTouchAction.POINTER_CHANGE, 0f, pointers = 2)
        s.event(PhotoPagerTouchAction.POINTER_CHANGE, 0f, pointers = 2)
        s.move(150f, pointers = 1)
        check(!s.canPage(0f, 100f, 100f))
        s.event(PhotoPagerTouchAction.END, 200f)
        check(s.pagingBlocked && !s.shouldSendPagerTouch())
        s.event(PhotoPagerTouchAction.DOWN, 0f)
        s.move(40f)
        check(!s.pagingBlocked && s.canPage(0f, 100f, 100f))
    }
    regression("missing POINTER_DOWN still blocks on observed multi-touch MOVE") {
        val s = state()
        s.begin(0f, 0f)
        s.move(100f, pointers = 2)
        check(s.pagingBlocked)
        s.move(200f, pointers = 1)
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("POINTER_UP alone cannot turn into a page drag") {
        val s = state()
        s.begin(0f, 0f)
        s.move(40f)
        s.event(PhotoPagerTouchAction.POINTER_CHANGE, 40f, pointers = 1)
        s.move(200f)
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("forwarded DOWN chooses existing pointer, including inserted index zero") {
        val s = state()
        check(s.forwardedDownPointerIndex(2, 1, true) == 0)
        check(s.forwardedDownPointerIndex(2, 0, true) == 1)
        check(s.forwardedDownPointerIndex(3, 2, true) == 0)
        check(s.forwardedDownPointerIndex(1, 0, false) == 0)
    }
    regression("new gesture clears native stage as well as axis lock") {
        val s = state()
        s.begin(0f, 0f)
        s.move(50f)
        s.prepareHandoff()
        s.recordNativeIntercept(true)
        s.blockPaging()
        s.event(PhotoPagerTouchAction.DOWN, 10f, 20f, time = 200L)
        check(!s.handoffPending && !s.pagerDragging && !s.pagingBlocked)
        check(s.previousX == 10f && s.previousY == 20f && s.previousEventTime == 200L)
        check(!s.canPage(0f, 100f, 100f))
    }
    regression("zoomed image releases only at its own edge, in the outward direction") {
        // 2.5x 放大后显示矩形比视口宽：内部横移不翻页；贴住左边界继续向右 / 贴住右边界继续向左才交还。
        val viewport = 100f
        val positions = listOf(-75f to 175f, -150f to 100f, 0f to 250f)
        for ((left, right) in positions) {
            for (direction in listOf(-1f, 1f)) {
                val s = state()
                s.begin(0f, 0f)
                s.move(40f * direction)
                val expected = (left >= 0f && direction > 0f) || (right <= viewport && direction < 0f)
                check(s.canPage(left, right, viewport) == expected) {
                    "left=$left right=$right direction=$direction"
                }
            }
        }
    }
    regression("zoomed interior pan never pages across the whole matrix") {
        // 1.2x / 2.5x / 8x 三档、每档 21 个平移位置：只要不贴边就必须留在图片里拖动。
        // （1.01x 在 100px 视口上只放大 1px，与 EDGE_EPSILON_PX 同级，按边界处理属预期行为，这里不取。）
        val viewport = 100f
        for (scale in listOf(1.2f, 2.5f, 8f)) {
            val width = viewport * scale
            for (position in 0..20) {
                val left = -(width - viewport) * position / 20f
                val right = left + width
                if (left >= -1f || right <= viewport + 1f) continue
                for (direction in listOf(-1f, 1f)) {
                    val s = state()
                    s.begin(0f, 0f)
                    s.move(40f * direction)
                    check(!s.canPage(left, right, viewport)) {
                        "scale=$scale left=$left right=$right direction=$direction"
                    }
                }
            }
        }
    }
    regression("portrait image whose width matches the viewport pages both ways") {
        // 竖图 FIT_CENTER：显示矩形宽度 ≤ 视口（左右边界等于/窄于视口）→ 没有内部横移空间，任一方向都直接翻页。
        for (imageWidth in listOf(30f, 70f, 100f)) {
            for (direction in listOf(-1f, 1f)) {
                val s = state()
                s.begin(0f, 0f)
                s.move(40f * direction)
                val left = (100f - imageWidth) / 2f
                check(s.canPage(left, left + imageWidth, 100f)) {
                    "imageWidth=$imageWidth direction=$direction"
                }
            }
        }
    }
    regression("horizontal edge property table across viewport sizes") {
        for (width in listOf(50f, 100f, 200f, 1000f)) {
            for (direction in listOf(-1f, 1f)) {
                for (position in 0..10) {
                    val s = state()
                    s.begin(0f, 0f)
                    s.move(40f * direction)
                    val left = -width * position / 10f
                    val right = left + width * 2f
                    val expected = if (direction > 0f) position == 0 else position == 10
                    check(s.canPage(left, right, width) == expected) {
                        "width=$width direction=$direction position=$position"
                    }
                }
            }
        }
    }
    println("$cases production Kotlin/JVM regressions passed (includes zoomed/edge matrices); no Android UI executed")
}
