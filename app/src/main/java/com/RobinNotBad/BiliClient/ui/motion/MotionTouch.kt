package com.RobinNotBad.BiliClient.ui.motion

import android.view.MotionEvent
import android.view.View

import androidx.recyclerview.widget.RecyclerView

import kotlin.math.abs

/**
 * 列表卡片的按压反馈（v5.3.6）：按下时轻微内缩，松开 / 滑走时回弹。
 *
 * # 为什么用 RecyclerView 的统一触摸监听，而不是给每个 itemView 挂 OnTouchListener
 * 在 Adapter 里逐项挂监听器，会随 ViewHolder 复用反复挂 / 摘
 * （本项目 v5.0.2-alpha10 就为同类问题把点击监听器提到 onCreateViewHolder 里只挂一次）。
 * 而 `addOnItemTouchListener` 只需要挂一次，通过 `findChildViewUnder` 拿到手指下的那一项，
 * 零逐项开销。
 *
 * # 状态为什么必须「每列表一份」，不能用 object 的静态字段
 * 页面上可能同时存在多个列表（例如详情页的评论列表 + 相关推荐列表）。
 * 用静态字段会出现「在 A 列表按住、同时 B 列表弹回 A 上一次缩过的行」这种串台，
 * 表现为毫不相干的一行突然抖一下 —— 极难归因。
 * 所以状态（pressedView / downX / downY）全部封在**每个列表安装时创建的** [PressWatcher] 里。
 *
 * # 为什么按下就缩，但一开始滚动就弹回
 * 「按下即反馈」是手感基础，不能延迟；但用户拖动列表时手指划过的那几行绝不能被当成按压，
 * 否则整片列表会随滑动闪缩。所以用 [TOUCH_SLOP_DP] 的位移阈值判定滚动，
 * 一旦超过就立刻弹回并**放弃**本次按压（同一次手势里不再重新按下）。
 *
 * # 绝不拦截事件
 * `onInterceptTouchEvent` 一律返回 false，只观察不参与分发。
 * 返回 true 会吃掉点击事件，表现为「列表卡片点不进去」，是灾难级回归。
 */
object MotionTouch {

    /** RecyclerView → 已挂上的触摸监听器（弱引用键，页面销毁后可被回收） */
    private val touched = java.util.WeakHashMap<RecyclerView, RecyclerView.OnItemTouchListener>()

    /**
     * 滚动判定阈值（dp）。
     *
     * 取 6dp：比系统的 `ViewConfiguration.getScaledTouchSlop()`（通常 8dp）略小，
     * 因为手表屏幕小、手指位移本来就更短，用系统值会「想按压却按不出来」。
     */
    private const val TOUCH_SLOP_DP = 6f

    /** 给列表装上按压反馈（幂等） */
    @JvmStatic
    fun install(list: RecyclerView) {
        if (touched.containsKey(list)) return
        val watcher = PressWatcher()
        touched[list] = watcher
        list.addOnItemTouchListener(watcher)
        // 兜底：滚动状态一旦变化就强制弹回。
        //
        // 为什么除了触摸监听还要这一层：滚动开始时 RecyclerView 会拦截手势并清空 touch target，
        // 之后 MOVE / UP 走的是 `RecyclerView.onTouchEvent` → `listener.onTouchEvent` 这条路径。
        // 那条路径在不同版本 / 不同场景（嵌套滚动、手指移出列表、被父容器接管）下
        // 未必保证把 UP 送到我们这里。一旦漏掉，被缩到 0.96 的那一行会**永久停在缩小状态** ——
        // 这是可见且难恢复的坏观感。而「滚动状态变化」是 RecyclerView 一定会发出的信号，
        // 用它做兜底，等于给这条路径加了一道保险。settle() 本身幂等，重复调用无副作用。
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) watcher.settlePublic()
            }
        })
    }

    /** 单个列表的按压状态与手势判定（每个列表一份，理由见类注释） */
    private class PressWatcher : RecyclerView.SimpleOnItemTouchListener() {

        private var pressedView: View? = null
        private var downX = 0f
        private var downY = 0f

        /** 本次手势是否已判定为滚动（判定后同一次手势不再触发按压） */
        private var cancelled = false

        /**
         * **必须返回 false**。
         *
         * 返回 true 会被 RecyclerView 当成「父级要拦截」：
         * `RecyclerView.onInterceptTouchEvent` 一旦发现某个 listener 返回 true，
         * 就 `cancelScroll()` 并自己消费事件，**子 View 从此收不到这个手势**——
         * 表现为「列表里的卡片点不进去了」。这是灾难级回归，绝对不能这么做。
         *
         * # 那为什么还要在 [onTouchEvent] 里再处理一遍
         * 这是本类最容易被写错的地方。Android 的 ViewGroup 分发规则是：
         * 只有 `mFirstTouchTarget != null`（有子 View 接住了 DOWN）时才会继续调
         * `onInterceptTouchEvent`。而用户一旦开始**滚动**，RecyclerView 会拦截手势、
         * 清空 touch target，此后 MOVE / UP **不再经过 onInterceptTouchEvent**。
         * 于是「按下 → 滚动」这条路径上我们收不到 UP，
         * 被缩到 0.96 的那一行会**永久停在缩小状态**。
         *
         * 而滚动发生后的事件会进入 `RecyclerView.onTouchEvent`，
         * 它内部再回调 `listener.onTouchEvent(this, e)`。所以在两处都处理，
         * 才能保证「点一下就松」和「按住后滚动」两条路径都能收到收尾事件。
         * 两处重复收到同一个 MOVE/UP 是安全的：[onMove] 有 cancelled 闸门，
         * [settle] 在 pressedView 为空时直接返回（幂等）。
         */
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            handle(rv, e)
            return false
        }

        /**
         * 只在 RecyclerView 已自己消费事件（即滚动中）时被调用。
         *
         * 注意签名：`SimpleOnItemTouchListener` 的这个方法返回 **Unit**（不是 Boolean），
         * 也就是「观察者」语义 —— 它没有能力也不应该在这里消费事件。
         */
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            handle(rv, e)
        }

        private fun handle(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> onDown(rv, e)
                MotionEvent.ACTION_MOVE -> onMove(e)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> settle()
            }
        }

        private fun onDown(rv: RecyclerView, e: MotionEvent) {
            // 上一个按压没正常收尾（手指移出列表等）：先放开
            settle()
            cancelled = false
            downX = e.x
            downY = e.y
            if (!Motion.touchEnabled()) return
            val child = rv.findChildViewUnder(e.x, e.y) ?: return
            pressedView = child
            child.animate()
                .scaleX(Motion.TOUCH_SCALE)
                .scaleY(Motion.TOUCH_SCALE)
                .setDuration(Motion.duration(Motion.DURATION_TOUCH_DOWN))
                .setInterpolator(Motion.easeOut())
                .start()
        }

        private fun onMove(e: MotionEvent) {
            if (cancelled) return
            val slop = Motion.dp(TOUCH_SLOP_DP).toFloat()
            if (abs(e.x - downX) <= slop && abs(e.y - downY) <= slop) return
            // 判定为滚动：立刻弹回，且本次手势不再当作按压
            cancelled = true
            settle()
        }

        /** 把按住的项弹回原状（幂等） */
        private fun settle() {
            val child = pressedView ?: return
            pressedView = null
            child.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(Motion.duration(Motion.DURATION_TOUCH_UP))
                .setInterpolator(Motion.easeOut())
                .start()
        }

        /**
         * 供外部（滚动状态监听）强制弹回。
         *
         * 同时置上 [cancelled]：调用方只会在「已经开始滚动」时调这里，
         * 此时本次手势本来就不该再当作按压。
         * 下一次 `ACTION_DOWN` 会在 [onDown] 里把它复位成 false，
         * 所以不会出现「滚动过一次之后就再也按不出反馈」的粘滞。
         */
        fun settlePublic() {
            cancelled = true
            settle()
        }
    }
}
