package com.RobinNotBad.BiliClient.ui.widget.recycler

import android.content.Context

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 列表布局方式的统一选择逻辑。
 *
 * 优先级：
 * 1. 页面强制单列（force_single_column）——直接单列；
 * 2. 实验室「双列模式」开关（#12）——两列；
 * 3. 横屏模式——三列（原行为）；
 * 4. 否则单列。
 *
 * 抽成工具类是为了让 Activity 与 Fragment 两套基类共用同一份逻辑，
 * 以前两边各写一份，加开关时很容易只改到一处。
 */
object ListLayoutUtil {

    /** 双列模式设置键（实验室） */
    const val PREF_DUAL_COLUMN = "lab_video_dual_column"

    /** 横屏模式设置键 */
    private const val PREF_LANDSCAPE = "ui_landscape"

    fun create(context: Context, forceSingleColumn: Boolean): RecyclerView.LayoutManager {
        if (forceSingleColumn) return CustomLinearManager(context)

        if (SharedPreferencesUtil.getBoolean(PREF_DUAL_COLUMN, false)) {
            return CustomGridManager(context, 2)
        }

        return if (SharedPreferencesUtil.getBoolean(PREF_LANDSCAPE, false))
            CustomGridManager(context, 3)
        else
            CustomLinearManager(context)
    }
}
