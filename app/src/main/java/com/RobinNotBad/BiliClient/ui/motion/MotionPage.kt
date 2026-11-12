package com.RobinNotBad.BiliClient.ui.motion

import android.app.Activity

import com.RobinNotBad.BiliClient.R

/**
 * 页面切换动效（v5.3.6）。
 *
 * # 为什么挂在 onPause 上统一套，而不是每个 `startActivity` 后面加一行
 * 全 App 有 200+ 处 `startActivity`，逐处加既改不完，也一定会漏。
 * 而**任何**页面切换都必然经过「当前页 onPause」，在这里统一套过渡动画，
 * 一次覆盖全部跳转，调用方一行都不用改。
 *
 * # 为什么要区分「进」与「退」
 * 手表屏幕小，动效的唯一作用是**表达层级关系**：
 * 新页面从右侧滑入 = 更深一层；返回时向右滑出 = 回到上一层。
 * 两个方向都从右侧来，用户就分不清自己是进了还是退了 —— 那种动画还不如不做。
 * 所以 [BaseActivity] 里重写了 `startActivity` / `finish`，
 * 在真正离开页面之前把方向记下来（[Direction]），onPause 时据此选动画。
 *
 * # 硬约束：`overridePendingTransition` 必须在 `startActivity` / `finish` 之后调用
 * onPause 恰好发生在两者之后，时机天然正确。
 *
 * # 开关关闭时的行为
 * 不调用 `overridePendingTransition`，恢复成系统默认（无动画），与旧版逐帧一致。
 * 这里**不能**改成「调用一个时长为 0 的动画」——
 * `overridePendingTransition(0, 0)` 仍会走一次窗口动画事务，某些 ROM 上会产生一帧黑屏。
 */
object MotionPage {

    /** 页面跳转方向（由调用方在 startActivity / finish 时登记） */
    enum class Direction {
        /** 进入更深一层：新页从右滑入 */
        PUSH,

        /** 返回上一层：当前页向右滑出 */
        POP,

        /** 未登记（第一次 create、被系统回收后重建等），不做动画 */
        NONE
    }

    /** 当前页面待处理的跳转方向（每个 Activity 实例一份） */
    private val directions = java.util.WeakHashMap<Activity, Direction>()

    /**
     * 登记方向。
     *
     * # 合并规则（这是本类唯一需要小心的地方）
     * 真实代码里常见的写法是 `startActivity(intent); finish()`（「替换当前页」）。
     * 如果两条都按字面登记，先 PUSH 后 POP，最终会播成「退回去」的方向 ——
     * 而用户的意图明显是「往前走，把旧页换掉」。
     * 所以：**PUSH 不会被随后的 POP 覆盖**，只有在本页还没有任何方向时 finish 才算 POP。
     * 反过来 `finish()` 之后再 `startActivity` 仍然会是 PUSH（后者无条件覆盖），
     * 这也是正确的：流程上是「先关旧页再开新页」，观感仍是前进。
     */
    @JvmStatic
    fun mark(activity: Activity?, direction: Direction) {
        if (activity == null) return
        if (direction == Direction.POP && directions[activity] == Direction.PUSH) return
        directions[activity] = direction
    }

    /** 每页呈现时清空方向，保证「这一次离开」与上一次离开互不影响 */
    @JvmStatic
    fun reset(activity: Activity?) {
        if (activity == null) return
        directions.remove(activity)
    }

    /**
     * 声明「本页自己管动画」，跳过本模块。
     *
     * 用于已有专属过渡动画的页面：`InstanceActivity.menuClick` 给菜单页预设了
     * `anim_activity_in_down`（从上往下滑出），这是既有观感，不能被动效模块覆盖。
     * 因为 `overridePendingTransition` 是**后写生效**，如果不显式跳过，
     * 我们 onPause 里的调用会把那个下滑动画顶掉。
     */
    @JvmStatic
    fun skip(activity: Activity?) {
        mark(activity, Direction.NONE)
    }

    /**
     * 在 onPause 里套用过渡动画。
     *
     * 取用即清除：方向是「这一次离开」的语义，
     * 不清掉的话页面下次因其他原因 onPause（例如弹对话框）会重放一次错误方向的动画。
     */
    @JvmStatic
    fun apply(activity: Activity?) {
        val target = activity ?: return
        val direction = directions.remove(target) ?: Direction.NONE
        if (direction == Direction.NONE) return
        if (!Motion.pageEnabled()) return
        try {
            when (direction) {
                Direction.PUSH -> target.overridePendingTransition(R.anim.motion_page_in, 0)
                Direction.POP -> target.overridePendingTransition(0, R.anim.motion_page_out)
                Direction.NONE -> Unit
            }
        } catch (ignored: Exception) {
            // 窗口已销毁 / ROM 拒绝动画：动效失败不应影响页面切换本身
        }
    }
}
