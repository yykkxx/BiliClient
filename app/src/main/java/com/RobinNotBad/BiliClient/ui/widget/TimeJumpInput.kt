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
 *
 * # v5.3.4：为什么要多一个 `onDismissed`
 * 用户报障：「数字小键盘跳转时间之后，直接关闭数字小键盘以及数字小键盘上一级的弹窗，
 * 回到视频界面，不需要用户再点一遍关掉弹窗」。
 *
 * 数字键盘这一层本来就 `dismiss()` 了，真正留在屏幕上的是**播放器侧的「调节进度」卡片
 * 和它背后那层 `#70000000` 遮罩** —— 用户点完"跳转"还得再点一次遮罩才能看见视频。
 *
 * 卡片属于播放器布局，[TimeJumpInput] 不应该知道它（否则这层「只管选输入方式」的
 * 抽象就被污染了）。所以这里只负责**告诉调用方「输入界面已经消失了」**，
 * 由调用方决定收起自己的卡片：
 * - `onConfirm`：用户确认，带着目标毫秒数（原有的语义，未变）；
 * - `onDismissed`：输入界面消失（**确认 / 取消 / 返回键 / 点外部**四条路径都会触发）。
 *
 * **为什么用 `onDismissed` 而不是把"收卡片"写进 `onConfirm`**：
 * 只挂 `onConfirm` 只能覆盖"确认"这一条路，用户按「取消」或返回键时卡片和遮罩仍会留在
 * 屏幕上，症状与本次报障一模一样（残留卡片要再点一次）。而"动画面消失 = 该收起卡片"
 * 才是真正想要的语义，且它天然对两种实现都成立（`Dialog.dismiss()` 与
 * `AlertDialog` 的按钮自动关闭都会走 `OnDismissListener`）。
 */
object TimeJumpInput {

    /**
     * 按实验室开关决定弹哪种输入；两种都通过 [onConfirm] 回调。
     *
     * @param activity    宿主 Activity（系统键盘模式需要它当 Context 弹 AlertDialog）
     * @param durationMs  视频总时长（毫秒）；0 = 未知，此时不把输入夹到时长内
     * @param onConfirm   用户确认后回调目标毫秒数（调用方负责 seek 与提示）
     * @param onDismissed 输入界面消失后回调：确认 / 取消 / 返回键 / 点外部都会触发，
     *                    且**保证在自己的 Dialog 关掉之后**才回调。调用方用它收起上一级的
     *                    卡片与遮罩，让用户直接回到视频界面。
     *                    它只报"消失了"这件事，**不区分**是确认还是取消 ——
     *                    需要区分时看 [onConfirm] 有没有被调用过。
     */
    @JvmStatic
    fun show(
        activity: Activity,
        durationMs: Long,
        onConfirm: (targetMs: Long) -> Unit,
        onDismissed: (() -> Unit)? = null
    ) {
        if (useKeypad()) {
            showKeypad(activity, durationMs, onConfirm, onDismissed)
        } else {
            showSystemInput(activity, durationMs, onConfirm, onDismissed)
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
     * 屏幕内数字键盘（[TimeKeypadDialog]）。
     *
     * `dismiss()` 由该类自己的 `confirm()` / 「取消」按钮 / 返回键 / 点外部触发，
     * 我们只在外面挂一个 [android.content.DialogInterface.OnDismissListener]，
     * 一次性覆盖这四条路径 —— 不需要（也不应该）在对话框内部写播放器的事。
     */
    private fun showKeypad(
        activity: Activity,
        durationMs: Long,
        onConfirm: (targetMs: Long) -> Unit,
        onDismissed: (() -> Unit)?
    ) {
        val dialog = TimeKeypadDialog(activity, durationMs, onConfirm)
        if (onDismissed != null) dialog.setOnDismissListener { onDismissed() }
        dialog.show()
    }

    /**
     * 旧实现：`EditText` + 系统输入法。
     *
     * 复刻 v5.2.0 的行为（提示文案、夹到时长、失败留在对话框里改），
     * 这样「关掉开关」= 回到用户熟悉的那一版，而不是一个没见过的第三种界面。
     *
     * v5.3.4 起与数字键盘路径**顺序对齐**：先 `dismiss()` 关掉自己（顺带触发
     * `onDismissed`），再 `onConfirm` 让调用方去 seek。
     * 之前靠 `setPositiveButton` 的自动关闭，`onConfirm` 会先跑、弹窗后关，
     * 两条路径的时序不一致，遮罩会在 seek 那一帧还压在上面。
     */
    private fun showSystemInput(
        activity: Activity,
        durationMs: Long,
        onConfirm: (targetMs: Long) -> Unit,
        onDismissed: (() -> Unit)?
    ) {
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

        val dialog = AlertDialog.Builder(activity)
            .setTitle("跳转到指定时间")
            .setView(container)
            // 回调的第一个参数就是 AlertDialog 自己，所以不用在外面先建好变量再回填
            .setPositiveButton("跳转") { dialogInterface, _ ->
                val seconds = TimeInputParser.parse(editText.text.toString())
                if (seconds < 0) {
                    // 格式不对：只提示、不跳转。
                    //
                    // 注意这里**做不到**「留在对话框里让用户改」：AlertDialog 的
                    // setPositiveButton 监听器跑完之后，框架**无条件**再发一次
                    // dismiss（`ButtonHandler` 收到 POSITIVE 就 sendMessage(MSG_DISMISS_DIALOG)），
                    // 直接从监听器里 return 挡不住它 —— 这是历史行为（v5.2.0 起就是这样），
                    // 本版只把语义写清楚，没有改它。
                    // 对本次需求无影响：格式错也走 dismiss → onDismissed → 收卡片，
                    // 与「取消」一致性更好（用户不用再点一次遮罩）。
                    MsgUtil.showMsg("时间格式不对，示例：90、1:30、5 30、1：02：03")
                    return@setPositiveButton
                }
                var targetMs = seconds * 1000L
                if (targetMs < 0) targetMs = 0
                if (durationMs > 0 && targetMs > durationMs) targetMs = durationMs
                // 显式 dismiss 再回调：与数字键盘路径的「先关窗、再 seek」时序对齐。
                // 这里不 dismiss 的话框架照样会关，但时机会晚于 onConfirm ——
                // 于是 seek 那一帧遮罩还压在画面上（那正是本次要修的观感）。
                dialogInterface.dismiss()
                onConfirm(targetMs)
            }
            .setNegativeButton("取消", null)
            .create()
        if (onDismissed != null) dialog.setOnDismissListener { onDismissed() }
        dialog.show()
    }
}
