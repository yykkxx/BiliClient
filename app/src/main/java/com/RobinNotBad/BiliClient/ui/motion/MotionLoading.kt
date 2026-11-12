package com.RobinNotBad.BiliClient.ui.motion

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 加载图「呼吸」脉冲（v5.3.6）。
 *
 * # 为什么给加载图加动效
 * 加载页（`activity_loading`）在慢网络下会停好几秒，静态图片会让人以为卡死了。
 * 一个缓慢的缩放呼吸（1.0 ↔ 1.06）足以传达「还在等」，且**几乎不耗电**：
 * 只改 `scaleX/scaleY` 两个属性，不触发布局、不需要重绘内容，由渲染线程直接合成。
 *
 * # 为什么不用透明度闪烁
 * 在纯黑背景 + 圆屏上，闪烁看起来像「信号不好的老电视」，
 * 长时间盯着会不舒服；呼吸缩放更接近"活着"的观感。
 * 而且呼吸的幅度小（6%），不会把图片推出圆屏边界。
 *
 * # 生命周期
 * 加载页一般在 1 秒内就被真实内容替换，所以这里的动画**必须能被打断**：
 * [stop] 在视图 detach 或页面销毁时被调用，取消 animator 并复位 scale。
 * 不复位会让复用同一个 View 的场景（例如 `asyncInflate` 的加载壳）带着 1.06 倍出现在成品页里。
 */
object MotionLoading {

    /** View → 正在跑的呼吸动画（弱引用键，页面销毁后可回收） */
    private val running = java.util.WeakHashMap<View, ObjectAnimator>()

    /** 呼吸幅度：1.0 → 1.06 再回来。幅度再大在小圆屏上会顶到边缘 */
    private const val PULSE_SCALE = 1.06f

    /**
     * 给加载图开始呼吸。
     *
     * 幂等：同一个 View 重复调用不会叠加动画。
     * 开关关闭时直接返回（不做任何视觉改变）。
     */
    @JvmStatic
    fun start(view: View?) {
        val target = view ?: return
        if (!Motion.loadingEnabled()) return
        if (running.containsKey(target)) return

        target.scaleX = 1f
        target.scaleY = 1f
        // 一个动画驱动「一个浮点值」，再同时写进 scaleX 与 scaleY。
        // 为什么不分别建两个 ObjectAnimator：两个 animator 即使同帧启动，
        // 也不保证每帧在同一时刻取到相位的值，x 与 y 会出现极小的相位差 ——
        // 屏幕上看到的是图片在「微微扭动」而不是匀速呼吸。
        val animator = ObjectAnimator.ofFloat(target, "scaleX", 1f, PULSE_SCALE, 1f).apply {
            duration = Motion.duration(Motion.DURATION_LOADING_PULSE)
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
        }
        // ObjectAnimator 已经会写 scaleX；这里只补 scaleY，保证两者同相。
        animator.addUpdateListener { animation ->
            val value = (animation.animatedValue as? Float) ?: return@addUpdateListener
            target.scaleY = value
        }
        running[target] = animator
        animator.start()
    }

    /** 停止呼吸并复位（幂等；视图已 detach / 页面销毁时调用） */
    @JvmStatic
    fun stop(view: View?) {
        val target = view ?: return
        val animator = running.remove(target) ?: return
        try {
            animator.cancel()
        } catch (ignored: Exception) {
            // 动画已结束 / 已取消：无需处理
        }
        target.scaleX = 1f
        target.scaleY = 1f
    }
}
