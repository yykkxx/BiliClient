package com.RobinNotBad.BiliClient.util

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout

/**
 * 主题色调切换（#10）。
 *
 * 本项目的颜色大量直接写在布局里，整体替换 Theme 的改动面过大且容易出问题。
 * 这里采用轻量方案：把“主题色”持久化到 `ui_theme_color`，并在页面创建后
 * 把主题色应用到语义明确的控件上——Tab 指示器与选中文字、进度条着色、
 * 原本用主题粉描边的卡片、以及链接色文字。
 *
 * 默认值与原 @color/pink (#FBFB8787) 保持一致，因此不设置时行为不变。
 */
object ThemeUtil {

    /** 主题色设置键 */
    const val PREF_THEME_COLOR = "ui_theme_color"

    /** 默认主题色（与原 @color/pink 一致） */
    const val DEFAULT_COLOR = 0xFFFBFB8787.toInt()

    /** 原粉色系的几个“等价色”，用于识别需要跟随主题的既有控件 */
    private val LEGACY_ACCENT_COLORS = intArrayOf(
        0xFFFBFB8787.toInt(), // pink
        0xFF99FB8799.toInt(), // low_pink
        0xFF66CCFF.toInt()    // link
    )

    /** 预设主题色：名称 → ARGB */
    @JvmField
    val PRESETS: Map<String, Int> = linkedMapOf(
        "默认粉" to 0xFFFBFB8787.toInt(),
        "天依蓝" to 0xFF66CCFF.toInt(),
        "哔哩粉" to 0xFFFB7299.toInt(),
        "薄荷绿" to 0xFF4CD9A0.toInt(),
        "温度橙" to 0xFFFF9A3C.toInt(),
        "优雅紫" to 0xFF9B7EDE.toInt(),
        "极简白" to 0xFFEBE0E2.toInt()
    )

    @JvmField
    val NAMES: Array<String> = PRESETS.keys.toTypedArray()

    /** 当前主题色 */
    @JvmStatic
    fun getColor(): Int = SharedPreferencesUtil.getInt(PREF_THEME_COLOR, DEFAULT_COLOR)

    @JvmStatic
    fun setColor(color: Int) {
        SharedPreferencesUtil.putInt(PREF_THEME_COLOR, color)
    }

    /** 预设色 → 覆盖主题样式名（#8） */
    private val OVERLAY_STYLES: Map<Int, Int> = mapOf(
        0xFFFBFB8787.toInt() to R.style.ThemeOverlay_Accent_Default,
        0xFF66CCFF.toInt() to R.style.ThemeOverlay_Accent_Blue,
        0xFFFB7299.toInt() to R.style.ThemeOverlay_Accent_BiliPink,
        0xFF4CD9A0.toInt() to R.style.ThemeOverlay_Accent_Mint,
        0xFFFF9A3C.toInt() to R.style.ThemeOverlay_Accent_Orange,
        0xFF9B7EDE.toInt() to R.style.ThemeOverlay_Accent_Purple,
        0xFFEBE0E2.toInt() to R.style.ThemeOverlay_Accent_White
    )

    /**
     * 取当前主题色对应的覆盖主题样式；自定义色（不在预设里）返回 0。
     * 在 Activity super.onCreate 之前调用 setTheme 生效。
     */
    @JvmStatic
    fun overlayStyle(): Int = OVERLAY_STYLES[getColor()] ?: 0

    /** 按名称设置主题色；名称未知时返回 false。 */
    @JvmStatic
    fun setColorByName(name: String?): Boolean {
        val color = PRESETS[name] ?: return false
        setColor(color)
        return true
    }

    /** 当前主题色名称（设置页展示用）。 */
    @JvmStatic
    fun getColorName(): String {
        val current = getColor()
        for ((name, color) in PRESETS) {
            if (color == current) return name
        }
        return "自定义"
    }

    /** 取带透明度的变体。 */
    @JvmStatic
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /**
     * 把主题色应用到 Activity 的视图树。
     * 在 `setContentView` 之后调用。
     */
    @JvmStatic
    fun applyTo(activity: Activity) {
        val color = getColor()
        // 状态栏底色跟随主题（系统状态栏在手表上通常隐藏，这里只是兜底）
        activity.window?.statusBarColor = color
        val root = activity.window?.decorView?.findViewById<View>(android.R.id.content) ?: return
        applyToView(root, color)
    }

    private fun isLegacyAccent(color: Int): Boolean = LEGACY_ACCENT_COLORS.contains(color)

    /**
     * 非主题色相关的旧色值 → 当前主题色的映射（#8）：
     * 把代码里散落的"点赞红/直播红"等粉系强调色统一染成主题色，
     * 让主题切换对全应用的观感真正可见。
     */
    @JvmStatic
    fun mapLegacyColor(color: Int): Int = when (color) {
        0xFFCF4B5F.toInt() -> getColor()        // Color.rgb(207,75,95) 系列红
        0xFFFE679A.toInt() -> getColor()        // 点赞红
        else -> color
    }

    private fun applyToView(view: View, color: Int) {
        when (view) {
            is TabLayout -> {
                view.setSelectedTabIndicatorColor(color)
                view.setTabTextColors(0x99EBE0E2.toInt(), color)
            }

            is ProgressBar -> {
                view.progressTintList = ColorStateList.valueOf(color)
                view.indeterminateTintList = ColorStateList.valueOf(color)
            }

            is MaterialCardView -> {
                // 仅改动原本就是粉色系（选中态）描边的卡片
                if (isLegacyAccent(view.strokeColor)) view.strokeColor = color
            }

            is TextView -> {
                // 仅改动原本就是主题色/链接色的文字
                if (isLegacyAccent(view.currentTextColor)) view.setTextColor(color)
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyToView(view.getChildAt(i), color)
            }
        }
    }

    /** 供纯色背景的 View 使用：把粉色底改成主题色。 */
    @JvmStatic
    fun tintBackgroundIfAccent(view: View, color: Int) {
        val bg = view.background
        if (bg is ColorDrawable && isLegacyAccent(bg.color)) {
            view.setBackgroundColor(color)
        }
    }
}
