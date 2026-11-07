package com.RobinNotBad.BiliClient.ui.widget

import android.app.Activity
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TimeInputParser
import com.RobinNotBad.BiliClient.util.ToolsUtil

/**
 * 「跳转到指定时间」的**唯一入口**（v5.3.0）。
 *
 * # 为什么要有这一层
 * v5.2.1 把输入方式写死成屏幕内数字键盘（[TimeKeypadDialog]），播放器直接 `new` 它。
 * 但屏幕键盘和系统键盘各有适用场景：
 * - **屏幕内数字键盘**（默认）：不弹输入法、不被顶出圆屏、自带冒号键，小屏/圆屏/watch 上体验最好；
 * - **系统输入法**：能整段粘贴（`1:30` 从剪贴板直接粘）、能按用户自己的输入法习惯打字，
 *   大屏手机/平板上反而更顺手。
 *
 * 用户明确要求做成实验室开关。所以这里把「选哪种」收敛到一处：
 * 播放器只调 [show]，不关心具体实现，也不用各自记偏好键。
 *
 * # 默认值为什么是「屏幕内数字键盘」
 * v5.2.1 的既有行为就是屏幕键盘。默认 `true` 保证**升级零感知**：
 * 老用户不碰开关时，表现与上一版完全一致；只有主动关掉才回到旧实现。
 *
 * # 回退实现为什么这么写
 * 关闭开关时复刻 **v5.2.0 的旧实现**（`EditText` + `AlertDialog`），
 * 因为那正是用户记忆里的「系统键盘输入」，不是新发明的界面：
 * - `inputType = TYPE_CLASS_TEXT`：必须允许冒号，否则 `1:30` / `1:02:03` 打不出来
 *   （用 `TYPE_CLASS_NUMBER` 会让系统数字键盘没有 `:`，那是这次要绕开的老问题）；
 * - 输入框只做「打字」，解析仍旧走 [TimeInputParser]，
 *   保证两种模式的时间语义**逐字一致**（`90` / `1:30` / `1：02：03` / `5 30`）。
 */
object TimeJumpInput {

    /**
     * 按实验室开关决定弹哪种输入；两种都通过 [onConfirm] 回调。
     *
     * @param activity   宿主 Activity（系统键盘模式需要它当 Context 弹 AlertDialog）
     * @param durationMs 视频总时长（毫秒）；0 = 未知，此时不把输入夹到时长内
     * @param onConfirm  用户确认后回调目标毫秒数（调用方负责 seek 与提示）
     */
    @JvmStatic
    fun show(activity: Activity, durationMs: Long, onConfirm: (targetMs: Long) -> Unit) {
        if (useKeypad()) {
            TimeKeypadDialog(activity, durationMs, onConfirm).show()
        } else {
            showSystemInput(activity, durationMs, onConfirm)
        }
    }

    /**
     * 当前是否用屏幕内数字键盘。
     *
     * 默认 `true`（见类注释）。偏好键统一登记在 [SharedPreferencesUtil.PLAYER_TIME_JUMP_KEYPAD]，
     * 实验室开关与本方法读写的是同一个键。
     */
    @JvmStatic
    fun useKeypad(): Boolean =
        SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PLAYER_TIME_JUMP_KEYPAD, true)

    /**
     * 旧实现：`EditText` + 系统输入法。
     *
     * 复刻 v5.2.0 的行为（提示文案、夹到时长、失败留在对话框里改），
     * 这样「关掉开关」= 回到用户熟悉的那一版，而不是一个没见过的第三种界面。
     */
    private fun showSystemInput(activity: Activity, durationMs: Long, onConfirm: (targetMs: Long) -> Unit) {
        val editText = EditText(activity).apply {
            hint = "例如 90、1:30、5 30、1：02：03"
            isSingleLine = true
            // 必须是 TEXT 而不是 NUMBER：数字键盘没有冒号，打不出 `1:30`
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
        }
        val container = FrameLayout(activity)
        val padding = ToolsUtil.dp2px(12f)
        container.setPadding(padding, padding, padding, padding)
        container.addView(
            editText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(activity)
            .setTitle("跳转到指定时间")
            .setView(container)
            .setPositiveButton("跳转") { _, _ ->
                val seconds = TimeInputParser.parse(editText.text.toString())
                if (seconds < 0) {
                    MsgUtil.showMsg("时间格式不对，示例：90、1:30、5 30、1：02：03")
                    return@setPositiveButton
                }
                var targetMs = seconds * 1000L
                if (targetMs < 0) targetMs = 0
                if (durationMs > 0 && targetMs > durationMs) targetMs = durationMs
                onConfirm(targetMs)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
