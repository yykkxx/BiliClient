// 文件名由规格 §8 的写域表固定为 TimeBankDayTypeDialog.kt，而对外暴露的类型必须是
// TimeBankDayTypePrompt（其它代理已按这个名字接线）——两者对不上，故在此豁免命名规则。
@file:Suppress("MatchingDeclarationName")

package com.RobinNotBad.BiliClient.activity.time

import android.app.Activity
import android.content.DialogInterface
import android.util.Log
import android.view.KeyEvent

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TimeBankManager

/**
 * 每天第一次打开应用的「今天是上学日还是休息日」询问（v5.2.0 需求 3，规格 §3.1）。
 *
 * 用 [AlertDialog]（本仓库既有的 `androidx.appcompat.app.AlertDialog`），**不新建 Activity**：
 * 这是一个二选一的小问题，独立 Activity 会让启动流程多一次页面切换和一次结果传递，
 * 而 Dialog 只要一个回调就能继续原流程。
 *
 * # 三条设计判断（都会影响用户能不能绕过配额，所以写明白）
 * 1. **不可取消**（`setCancelable(false)`，返回键也拦）：点外部/按返回就能跳过的话，
 *    用户可以无限次「不选」来绕过配额判定 —— 那这个功能就形同虚设。
 * 2. **答完才写**：只有真点了「上学日」或「休息日」才写 `time_bank_day_type`；
 *    被系统回收/异常打断时保持「未判定」，下次启动重新问。这样「没答 = 再问」，
 *    不会因为一次闪退就把某天永久锁在错误配额上。
 * 3. **不需要弹的时候同步回调 [maybePrompt] 的 `onDone`**：调用方（启动页）把它当「询问阶段结束」
 *    的信号，直接同步回调可以省掉一条「我不用问」的分支，也让调用方的后续逻辑只有一条路径。
 *
 * 本对象**不持有 Activity 引用、不发异步任务**：`onDone` 在 Dialog 的按钮回调里同步执行
 * （AlertDialog 的按钮回调本来就在主线程），调用方拿到回调时 Activity 必然还活着。
 */
object TimeBankDayTypePrompt {

    private const val TAG = "time_bank_prompt"

    /**
     * 若时间银行开启且今天还没判定，就弹窗询问；已判定 / 关闭功能一天 / 开关关 → 什么都不做。
     *
     * @param activity 宿主页面（用它的 Context 与 `isFinishing`/`isDestroyed` 判断能否弹）
     * @param onDone 询问阶段结束（选完 / 本来就不用问）后调用一次；**可空**
     */
    @JvmStatic
    fun maybePrompt(activity: Activity, onDone: Runnable?) {
        if (!isBankEnabled()) {
            onDone?.run()
            return
        }
        if (TimeBankManager.hasDayTypeToday() || TimeBankManager.isDisabledToday()) {
            // 已经判定过，或今天已经选了「关闭此功能一天」→ 不再打扰
            onDone?.run()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            // 页面正在销毁：弹不出来也不能把 onDone 吞掉，让调用方继续往下走
            onDone?.run()
            return
        }
        showDialog(activity, onDone)
    }

    /** 真正的弹窗（不可取消） */
    private fun showDialog(activity: Activity, onDone: Runnable?) {
        val schoolQuota = TimeBankManager.schoolQuotaMin()
        val restQuota = TimeBankManager.restQuotaMin()
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.time_bank_day_type_title)
            .setMessage(
                activity.getString(
                    R.string.time_bank_day_type_message,
                    schoolQuota,
                    restQuota
                )
            )
            .setCancelable(false)
            .setPositiveButton(activity.getString(R.string.time_bank_day_type_school, schoolQuota)) { dialog, _ ->
                finishWith(dialog, TimeBankManager.DAY_SCHOOL, onDone)
            }
            .setNegativeButton(activity.getString(R.string.time_bank_day_type_rest, restQuota)) { dialog, _ ->
                finishWith(dialog, TimeBankManager.DAY_REST, onDone)
            }
            // 返回键一律无视：规格要求「不可取消，否则用户点外部就绕过配额判定」。
            // 只吞返回键（不要吞全部按键，否则圆屏/dpad 上的方向键就选不动按钮了）。
            .setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

        try {
            builder.show()
        } catch (e: Exception) {
            // 窗口 token 失效（例如 Activity 正在销毁）时 Builder.show() 会抛 BadTokenException。
            // 这里不能吞掉流程：直接按「未判定」继续，用户下次启动会被再问一次。
            Log.w(TAG, "show day type dialog failed", e)
            onDone?.run()
        }
    }

    /**
     * 写当天类型并关窗、回调。
     *
     * 顺序有意为之：**先写偏好再 dismiss** —— 若 dismiss 触发调用方重返前台/重新判定，
     * 那时 `hasDayTypeToday()` 必须已经是 true，否则会二次询问。
     */
    private fun finishWith(dialog: DialogInterface?, type: String, onDone: Runnable?) {
        TimeBankManager.setDayTypeToday(type)
        dialog?.dismiss()
        onDone?.run()
    }

    private fun isBankEnabled(): Boolean =
        SharedPreferencesUtil.getBoolean(TimeBankManager.PREF_ENABLE, false)
}
