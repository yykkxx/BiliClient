package com.RobinNotBad.BiliClient.adapter

/**
 * switch 行的「关闭需等待」守卫（可选能力，放进 `SettingSection.extra`）。
 *
 * 只在**关闭方向**生效：用户把开关从「开」拨到「关」时，不立刻写入 false，
 * 而是把开关顶回「开」并在原地显示 [seconds] 秒倒计时，倒计时结束才真正关闭。
 * 打开方向（关 → 开）永远直接生效，不受这个守卫影响。
 *
 * 与既有的 `extra: Runnable`（开关切换副作用）互斥使用：`SettingsAdapter.SwitchHolder`
 * 先按类型判断，是 [SwitchDisableGuard] 走守卫逻辑，是 `Runnable` 走原副作用逻辑。
 * 两者都要的话，让守卫内部带一个 [onDisableConfirmed]（在关闭真正生效后执行）。
 *
 * 不带守卫的行行为与旧版逐行一致。
 */
class SwitchDisableGuard(
    @JvmField val seconds: Int,
    @JvmField val onDisableConfirmed: Runnable? = null
)
