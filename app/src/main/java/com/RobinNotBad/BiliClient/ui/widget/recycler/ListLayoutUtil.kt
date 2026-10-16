package com.RobinNotBad.BiliClient.ui.widget.recycler

import android.content.Context

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 列表布局方式的统一选择逻辑。
 *
 * 优先级：
 * 1. 页面强制单列（forceSingleColumn）——直接单列；
 * 2. 实验室「视频列表双列模式」（#2）——**仅视频列表页**可用（信息流布局），
 *    调用 [createVideoCard] 的页面才生效；
 * 3. 其余页面一律单列。
 *
 * 原来的三档逻辑（双列 / 横屏三列 / 单列共用一个开关栈）已按 #2 收敛：
 * 双列只属于视频列表，横屏模式（含三列）已删除。
 */
object ListLayoutUtil {

    /** 双列模式设置键（实验室，仅视频列表页生效） */
    const val PREF_DUAL_COLUMN = "lab_video_dual_column"

    /** 普通列表页：一律单列 */
    @JvmStatic
    fun create(context: Context, forceSingleColumn: Boolean): RecyclerView.LayoutManager {
        return CustomLinearManager(context)
    }

    /** 双列模式是否开启（视频卡片 adapter 选布局用，#2） */
    @JvmStatic
    fun isDualColumnVideo(): Boolean =
        SharedPreferencesUtil.getBoolean(PREF_DUAL_COLUMN, false)

    /**
     * 视频卡片列表页：双列开关打开时用两列网格（信息流布局），
     * 否则单列。
     */
    @JvmStatic
    fun createVideoCard(context: Context, forceSingleColumn: Boolean): RecyclerView.LayoutManager {
        if (forceSingleColumn) return CustomLinearManager(context)
        return if (SharedPreferencesUtil.getBoolean(PREF_DUAL_COLUMN, false))
            CustomGridManager(context, 2)
        else
            CustomLinearManager(context)
    }
}
