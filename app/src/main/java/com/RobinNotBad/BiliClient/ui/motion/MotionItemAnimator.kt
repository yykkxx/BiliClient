package com.RobinNotBad.BiliClient.ui.motion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.animation.LinearInterpolator

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * 列表增删 / 移动 / 内容变化的过渡动画（v5.3.6）。
 *
 * # 为什么自己写一个，而不是直接用系统自带的 DefaultItemAnimator
 * 系统默认实现只会「淡入淡出 + 位移」，在手表这种小屏上几乎看不出来
 * （淡入淡出 250ms，圆屏只露出几行内容，用户感知到的是「突然变了」）。
 * 这里在同样的正确性骨架之上，把三类动画做出可辨识的差异：
 *
 * | 动作 | 观感 |
 * | --- | --- |
 * | 新增 | 从下方 8dp 微微上浮 + 淡入（「冒出来」） |
 * | 移除 | 向内轻微收缩 + 淡出（「收走」），而不是原地消失 |
 * | 移动 | 纯位移，让「插到前面」看得出来 |
 * | 内容变化 | 先淡到 0.35 再淡回，作为「这里换了内容」的提示 |
 *
 * # 正确性骨架照抄系统实现，这不是偷懒
 * RecyclerView 的 ItemAnimator 有严格契约：每个 ViewHolder 的生命周期回调
 * （`dispatchAddStarting/Finished`、`dispatchRemoveStarting/Finished` …）必须恰好配对，
 * 且**动画结束时必须把 alpha / translation / scale 复位**，否则被回收复用的 itemView
 * 会带着上一行的变形状态出现（表现为「某些卡片莫名其妙变小 / 半透明」，
 * 且只在特定滚动路径下出现，极难复现）。
 * 下面的 `resetView` 调用点与 `dispatchAnimationsFinished()` 的时机，
 * 都是 `DefaultItemAnimator` 里验证过多年的结构，不自行「优化」。
 *
 * # 完成回调不要再手写一份
 * `RecyclerView.ItemAnimator` 基类已经维护了 `mFinishedListeners`，
 * 只要在动画全部结束时调 `dispatchAnimationsFinished()` 就会自动回调
 * （`SwipeRefreshLayout` 收圈等依赖它）。自己再造一个 listener 列表
 * 会出现「基类列表里的回调永远不被触发」——外层就一直等不到动画结束。
 *
 * # 时长与开关
 * 所有时长走 [Motion.duration]；总开关关掉时本类根本不会被挂上
 * （见 [Motion.attach]），所以这里不需要再判一次开关。
 */
class MotionItemAnimator : SimpleItemAnimator() {

    init {
        // 内容变化时默认要同时 animate 旧视图和新视图两份；
        // 本实现只做单视图的「呼吸」，关掉可省掉一次 ViewOverlay 中转 ——
        // 手表内存紧张，少一层 overlay 少一次分配。
        @Suppress("DEPRECATION")
        setSupportsChangeAnimations(false)
    }

    private val pendingRemovals = ArrayList<RecyclerView.ViewHolder>()
    private val pendingAdditions = ArrayList<RecyclerView.ViewHolder>()
    private val pendingMoves = ArrayList<MoveInfo>()
    private val pendingChanges = ArrayList<ChangeInfo>()

    private val runningRemovals = ArrayList<RecyclerView.ViewHolder>()
    private val runningAdditions = ArrayList<RecyclerView.ViewHolder>()
    private val runningMoves = ArrayList<RecyclerView.ViewHolder>()
    private val runningChanges = ArrayList<RecyclerView.ViewHolder>()

    private val linear = LinearInterpolator()

    private class MoveInfo(
        val holder: RecyclerView.ViewHolder,
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int
    )

    private class ChangeInfo(
        val oldHolder: RecyclerView.ViewHolder,
        val newHolder: RecyclerView.ViewHolder
    )

    // ------------------------------------------------------------------ 对外契约

    override fun isRunning(): Boolean =
        hasPending() || hasRunning()

    private fun hasPending(): Boolean =
        pendingAdditions.isNotEmpty() || pendingRemovals.isNotEmpty() ||
            pendingMoves.isNotEmpty() || pendingChanges.isNotEmpty()

    private fun hasRunning(): Boolean =
        runningAdditions.isNotEmpty() || runningRemovals.isNotEmpty() ||
            runningMoves.isNotEmpty() || runningChanges.isNotEmpty()

    override fun runPendingAnimations() {
        val hasRemovals = pendingRemovals.isNotEmpty()
        val hasMoves = pendingMoves.isNotEmpty()
        val hasChanges = pendingChanges.isNotEmpty()
        val hasAdditions = pendingAdditions.isNotEmpty()
        // 拆成一次 hasPending() 判断：detekt 的 ComplexCondition 会把连续的 && 也计入复杂度
        if (!hasPending()) return

        // 顺序是有讲究的：先收走旧的，再挪位，再换内容，最后让新的冒出来。
        // 反过来写的话，新项会先出现在旧项还没腾出的位置上，视觉上会重叠跳舞。
        if (hasRemovals) {
            val list = ArrayList(pendingRemovals)
            pendingRemovals.clear()
            runRemove(list)
        }
        if (hasMoves) {
            val list = ArrayList(pendingMoves)
            pendingMoves.clear()
            runMoves(list)
        }
        if (hasChanges) {
            val list = ArrayList(pendingChanges)
            pendingChanges.clear()
            runChanges(list)
        }
        if (hasAdditions) {
            val list = ArrayList(pendingAdditions)
            pendingAdditions.clear()
            runAdditions(list)
        }
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        pendingRemovals.add(holder)
        dispatchRemoveStarting(holder)
        return true
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        endAnimation(holder)
        // 先摆成「还没出现」的样子，等 runPendingAnimations 里真正开始动
        holder.itemView.alpha = 0f
        pendingAdditions.add(holder)
        dispatchAddStarting(holder)
        return true
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        val view = holder.itemView
        val startX = fromX + view.translationX.toInt()
        val startY = fromY + view.translationY.toInt()
        endAnimation(holder)
        val dx = toX - startX
        val dy = toY - startY
        if (dx == 0 && dy == 0) {
            // 没有实际位移：必须立刻 dispatch finished，否则 RecyclerView 会一直等这个 holder
            dispatchMoveFinished(holder)
            return false
        }
        // 用 translation 表达位移，动画结束后必须归零（见 resetView）
        if (dx != 0) view.translationX = (-dx).toFloat()
        if (dy != 0) view.translationY = (-dy).toFloat()
        pendingMoves.add(MoveInfo(holder, startX, startY, toX, toY))
        return true
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder?,
        newHolder: RecyclerView.ViewHolder?,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        val oldItem = oldHolder ?: newHolder ?: return false
        val newItem = newHolder ?: oldItem
        if (oldItem === newItem) return animateMove(oldItem, fromX, fromY, toX, toY)

        endAnimation(oldItem)
        pendingChanges.add(ChangeInfo(oldItem, newItem))
        dispatchChangeStarting(newItem, false)
        // 让「旧内容」先隐去，动画开始时再淡回来（单视图呼吸，不做双视图交叉）
        oldItem.itemView.alpha = 0.35f
        newItem.itemView.alpha = 0.35f
        return true
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        val view = item.itemView
        view.animate().cancel()

        val pendingMove = pendingMoves.firstOrNull { it.holder === item }
        if (pendingMove != null) {
            pendingMoves.remove(pendingMove)
            dispatchMoveFinished(item)
        }
        val pendingChange = pendingChanges.firstOrNull {
            it.newHolder === item || it.oldHolder === item
        }
        if (pendingChange != null) {
            pendingChanges.remove(pendingChange)
            dispatchChangeFinished(pendingChange.newHolder, false)
            if (pendingChange.oldHolder !== pendingChange.newHolder) {
                dispatchChangeFinished(pendingChange.oldHolder, false)
            }
        }
        if (pendingRemovals.remove(item)) dispatchRemoveFinished(item)
        if (pendingAdditions.remove(item)) dispatchAddFinished(item)

        runningRemovals.remove(item)
        runningAdditions.remove(item)
        runningMoves.remove(item)
        runningChanges.remove(item)

        resetView(view)

        if (!isRunning) dispatchAnimationsFinished()
    }

    override fun endAnimations() {
        // 倒序取空，避免「边遍历边 remove」导致漏项
        while (pendingMoves.isNotEmpty()) {
            val info = pendingMoves.removeAt(pendingMoves.size - 1)
            dispatchMoveFinished(info.holder)
            resetView(info.holder.itemView)
        }
        while (pendingRemovals.isNotEmpty()) {
            val holder = pendingRemovals.removeAt(pendingRemovals.size - 1)
            dispatchRemoveFinished(holder)
            resetView(holder.itemView)
        }
        while (pendingAdditions.isNotEmpty()) {
            val holder = pendingAdditions.removeAt(pendingAdditions.size - 1)
            dispatchAddFinished(holder)
            resetView(holder.itemView)
        }
        while (pendingChanges.isNotEmpty()) {
            val info = pendingChanges.removeAt(pendingChanges.size - 1)
            dispatchChangeFinished(info.newHolder, false)
            if (info.oldHolder !== info.newHolder) dispatchChangeFinished(info.oldHolder, false)
            resetView(info.newHolder.itemView)
            resetView(info.oldHolder.itemView)
        }

        while (runningMoves.isNotEmpty()) {
            val holder = runningMoves.removeAt(runningMoves.size - 1)
            holder.itemView.animate().cancel()
            resetView(holder.itemView)
            dispatchMoveFinished(holder)
        }
        while (runningRemovals.isNotEmpty()) {
            val holder = runningRemovals.removeAt(runningRemovals.size - 1)
            holder.itemView.animate().cancel()
            resetView(holder.itemView)
            dispatchRemoveFinished(holder)
        }
        while (runningAdditions.isNotEmpty()) {
            val holder = runningAdditions.removeAt(runningAdditions.size - 1)
            holder.itemView.animate().cancel()
            resetView(holder.itemView)
            dispatchAddFinished(holder)
        }
        while (runningChanges.isNotEmpty()) {
            val holder = runningChanges.removeAt(runningChanges.size - 1)
            holder.itemView.animate().cancel()
            resetView(holder.itemView)
            dispatchChangeFinished(holder, false)
        }

        if (isRunning) dispatchAnimationsFinished()
    }

    // ------------------------------------------------------------------ 具体动画

    private fun runRemove(list: ArrayList<RecyclerView.ViewHolder>) {
        runningRemovals.addAll(list)
        list.forEach { holder ->
            val view = holder.itemView
            view.animate()
                .alpha(0f)
                // 轻微内收：比原地淡出更有「被收走」的动作感
                .scaleX(REMOVE_SHRINK)
                .scaleY(REMOVE_SHRINK)
                .setDuration(Motion.duration(Motion.DURATION_ITEM_REMOVE))
                .setInterpolator(Motion.easeOut())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        resetView(view)
                        runningRemovals.remove(holder)
                        dispatchRemoveFinished(holder)
                        if (!isRunning) dispatchAnimationsFinished()
                    }
                })
                .start()
        }
    }

    private fun runAdditions(list: ArrayList<RecyclerView.ViewHolder>) {
        runningAdditions.addAll(list)
        list.forEachIndexed { index, holder ->
            val view = holder.itemView
            // 从下方一点点冒出来：位移量小（8dp）是为了在圆屏上不越界
            view.translationY = Motion.dp(ADD_RISE_DP).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(Motion.duration(Motion.DURATION_ITEM_ADD))
                // 错峰：一次插入多条时依次落下，比整片一起淡入自然
                .setStartDelay(index.coerceAtMost(MAX_ADD_STAGGER) * ADD_STAGGER_MS)
                .setInterpolator(Motion.easeOut())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        resetView(view)
                        runningAdditions.remove(holder)
                        dispatchAddFinished(holder)
                        if (!isRunning) dispatchAnimationsFinished()
                    }
                })
                .start()
        }
    }

    private fun runMoves(list: ArrayList<MoveInfo>) {
        runningMoves.addAll(list.map { it.holder })
        list.forEach { info ->
            val view = info.holder.itemView
            view.animate()
                .translationX(0f)
                .translationY(0f)
                .setDuration(Motion.duration(Motion.DURATION_ITEM_CHANGE))
                .setInterpolator(Motion.easeOut())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        resetView(view)
                        runningMoves.remove(info.holder)
                        dispatchMoveFinished(info.holder)
                        if (!isRunning) dispatchAnimationsFinished()
                    }
                })
                .start()
        }
    }

    private fun runChanges(list: ArrayList<ChangeInfo>) {
        list.forEach { info ->
            runningChanges.add(info.newHolder)
            val view = info.newHolder.itemView
            view.animate()
                .alpha(1f)
                .setDuration(Motion.duration(Motion.DURATION_ITEM_CHANGE))
                .setInterpolator(linear)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        resetView(view)
                        runningChanges.remove(info.newHolder)
                        dispatchChangeFinished(info.newHolder, false)
                        if (!isRunning) dispatchAnimationsFinished()
                    }
                })
                .start()
        }
    }

    /**
     * 把 view 恢复到「原始形态」。
     *
     * 这是整个类的正确性底座：itemView 会被回收复用，
     * 任何一次漏复位都会让后面某一行的卡片带着半透明 / 变形出现。
     */
    private fun resetView(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private companion object {
        /** 新增项初始下沉距离（dp） */
        const val ADD_RISE_DP = 8f

        /** 移除时收缩到原大小的多少 */
        const val REMOVE_SHRINK = 0.88f

        /** 连续插入时最多让前几项错峰 */
        const val MAX_ADD_STAGGER = 5

        /** 错峰间隔 */
        const val ADD_STAGGER_MS = 40L
    }
}
