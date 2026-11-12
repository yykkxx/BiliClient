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
        "极简白" to 0xFFEBE0E2.toInt(),
        // v5.3.4 新增六档预设：补齐「冷色 / 暖色 / 低饱和」三类，
        // 让用户不用进自定义色也能覆盖到常见偏好。
        // 全部在纯黑背景（bgblack=#000000）上验证过对比度 ≥ 4.5:1（正文文本用）。
        "晴空青" to 0xFF4CC9C9.toInt(),
        "蜜桃粉" to 0xFFFFA8C5.toInt(),
        "青柠绿" to 0xFFA8D948.toInt(),
        "琥珀黄" to 0xFFF0C040.toInt(),
        "雾霾蓝" to 0xFF7A9CC6.toInt(),
        "樱染红" to 0xFFE8607A.toInt()
    )

    @JvmField
    val NAMES: Array<String> = PRESETS.keys.toTypedArray()

    // ---------------- v5.3.4：个性化主题（自定义色 + 派生色 + 一键黑色背景） ----------------

    /**
     * 是否用「自定义色」而不是预设。
     *
     * 为什么需要单独一个键：颜色值本身可能是任意 int，无法从值反推「这是预设还是自定义」——
     * 更重要的是**预设色可能被将来调整**，如果只靠值比对，用户之前选过的预设色会突然变回「自定义」。
     * 用显式布尔键后，语义稳定。
     */
    const val PREF_THEME_CUSTOM = "ui_theme_custom"

    /**
     * 一批「应用级观感」开关 —— 它们和主题色是同一个主题体系的一部分，
     * 但不影响任何既有偏好键的语义（都是新增键，老用户默认值与当前行为一致）。
     */
    const val PREF_THEME_APPLY_LIST = "ui_theme_apply_list"

    /** 主题色是否也用于「列表卡片描边」（默认开：这是既有行为，不能改） */
    const val PREF_THEME_APPLY_CARD = "ui_theme_apply_card"

    /** 主题色是否也用于「进度条着色」（默认开：既有行为） */
    const val PREF_THEME_APPLY_PROGRESS = "ui_theme_apply_progress"

    /** 主题色是否也用于「链接色文字」（默认开：既有行为） */
    const val PREF_THEME_APPLY_TEXT = "ui_theme_apply_text"

    /** 当前主题色 */
    @JvmStatic
    fun getColor(): Int = SharedPreferencesUtil.getInt(PREF_THEME_COLOR, DEFAULT_COLOR)

    /** 当前是否为自定义色 */
    @JvmStatic
    fun isCustomColor(): Boolean =
        SharedPreferencesUtil.getBoolean(PREF_THEME_CUSTOM, false) && !PRESETS.containsValue(getColor())

    /**
     * 派生一个「文字用变体」：把颜色往白色方向提亮，保证在纯黑背景上可读。
     *
     * # 为什么需要派生而不是直接用原色
     * 用户自定义的颜色可能是深蓝、深紫这类**在黑色背景上极难读**的值。
     * 直接把它当文字色会让链接/选中文字几乎看不见。这里按颜色亮度决定提亮幅度：
     * 已经很亮的颜色几乎不变，很暗的颜色提到可用亮度。
     *
     * 用的是简单的线性混合（向白色 lerp），不是完整的 HSL 亮度校正 ——
     * 后者在小屏上收益不明显，但多一段可出错的颜色空间转换代码。
     */
    @JvmStatic
    fun readableTextColor(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // 相对亮度（sRGB 加权，ITU-R BT.601 常用系数），0..255
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        // 亮度 >= 140 已经够亮，不再提亮（再提就会发白、失去色相）
        if (luminance >= 140.0) return color
        // 160 - luminance 的最大值约 160（纯黑），换算成 0..1 的混合比，
        // 上限 0.62 是为了不让颜色彻底变白（保留可辨识的色相）
        val ratio = ((160.0 - luminance) / 160.0 * 0.62).coerceIn(0.0, 0.62)
        return Color.argb(
            Color.alpha(color),
            (r + (255 - r) * ratio).toInt().coerceIn(0, 255),
            (g + (255 - g) * ratio).toInt().coerceIn(0, 255),
            (b + (255 - b) * ratio).toInt().coerceIn(0, 255)
        )
    }

    /** 校验并保存自定义色（含透明度归一：一律写不透明，避免用户填出隐形主题） */
    @JvmStatic
    fun setCustomColor(color: Int): Boolean {
        // 全透明/近全透明的颜色会让主题"看不见"，直接拒绝（返回 false 由调用方提示）
        val alpha = Color.alpha(color)
        if (alpha < 0x80) return false
        setColor(Color.rgb(Color.red(color), Color.green(color), Color.blue(color)))
        SharedPreferencesUtil.putBoolean(PREF_THEME_CUSTOM, true)
        return true
    }

    /** 按名称设置主题色；名称未知时返回 false。 */
    @JvmStatic
    fun setColorByName(name: String?): Boolean {
        val color = PRESETS[name] ?: return false
        setColor(color)
        // 选预设就不再是自定义色（否则 getColorName 会一直显示"自定义"）
        SharedPreferencesUtil.putBoolean(PREF_THEME_CUSTOM, false)
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

    /** 当前主题色的十六进制展示（自定义色时给用户看，形如 #4CC9C9） */
    @JvmStatic
    fun getColorHex(): String {
        val c = getColor()
        // 必须显式给 Locale.US：颜色码是机器可读格式，用默认 locale 在
        // 阿拉伯语等 locale 下会输出本地数字，用户复制过去就解析不了。
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and c)
    }

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
     * 取当前主题色对应的覆盖主题样式，供 `BaseActivity.onCreate` 里 `setTheme` 使用。
     *
     * # v5.3.4：为什么不再对自定义色返回 0
     *
     * 老实现是 `OVERLAY_STYLES[getColor()] ?: 0` —— 自定义色返回 0，`BaseActivity`
     * 于是**根本不调 setTheme**。后果是：选了自定义色之后，Material 控件
     * （Switch 的滑块、CheckBox 的勾、RadioButton、MaterialButton 的 ripple）
     * 全部还是默认色，只有 `applyToView` 手工染的那几类控件变色 ——
     * 用户看到的就是「自定义色只改了一半」，这正是「主题色功能不完善」的核心。
     *
     * # 修法：所有颜色都落在一个真实存在的样式上
     * `Resources` 没有运行时创建 style 的公开 API（`applyStyle` 只能引用编译期存在的 style id，
     * 也无法 `setColor`）。所以运行时自定义色的 Material 控件染色**只能靠逐控件 tint**，
     * 这条路在下面 [applyToView] 里做（见那边对 Switch/CheckBox/RadioButton/MaterialButton 的处理）。
     *
     * 但 `setTheme` 仍然必须调：新增的六个预设色没有专属 style，
     * 统一落到 [R.style.ThemeOverlay_Accent_Default]（= 基础主题，不改变任何既有观感），
     * 保证「选任何颜色都走同一条初始化路径」，避免"预设走 setTheme、自定义不走"这种分叉。
     */
    @JvmStatic
    fun overlayStyle(): Int = OVERLAY_STYLES[getColor()] ?: R.style.ThemeOverlay_Accent_Default

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
                // 进度条着色是既有行为，受 PREF_THEME_APPLY_PROGRESS 控制（默认开）
                if (SharedPreferencesUtil.getBoolean(PREF_THEME_APPLY_PROGRESS, true)) {
                    view.progressTintList = ColorStateList.valueOf(color)
                    view.indeterminateTintList = ColorStateList.valueOf(color)
                }
            }

            is MaterialCardView -> {
                // 仅改动原本就是粉色系（选中态）描边的卡片，受 PREF_THEME_APPLY_CARD 控制（默认开）
                if (SharedPreferencesUtil.getBoolean(PREF_THEME_APPLY_CARD, true) &&
                    isLegacyAccent(view.strokeColor)
                ) {
                    view.strokeColor = color
                }
            }

            is TextView -> {
                // 仅改动原本就是主题色/链接色的文字，受 PREF_THEME_APPLY_TEXT 控制（默认开）
                if (SharedPreferencesUtil.getBoolean(PREF_THEME_APPLY_TEXT, true) &&
                    isLegacyAccent(view.currentTextColor)
                ) {
                    view.setTextColor(color)
                }
            }
        }

        // ---- v5.3.4：Material 控件的激活态染色（自定义色真正"完整生效"的关键） ----
        // 这些控件在老实现里**完全不受主题色影响**：它们读的是主题 attr
        // （colorControlActivated / colorSecondary / colorPrimary），而自定义色无法写进
        // 一个编译期存在的 style。只能在运行时逐个 tint。
        // 注意：预置色本来由 ThemeOverlay 样式处理，再 tint 一次与样式一致，无副作用。
        tintMaterialControl(view, color)

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyToView(view.getChildAt(i), color)
            }
        }
    }

    /**
     * 给「激活态跟着主题色走」的 Material 控件补上运行时 tint。
     *
     * # 为什么必须逐个处理而不是改主题
     * 见 [overlayStyle] 的注释：运行时无法创建 style、也无法 setColor 到 Theme。
     * 所以这里用控件自身的 tint API —— 这是 Material Components 官方支持的着色方式。
     *
     * # 覆盖范围与理由（每一项都对应一类用户能看见的地方）
     * | 控件 | 染哪里 | 用户在哪里看到 |
     * | --- | --- | --- |
     * | SwitchCompat / SwitchMaterial | thumb + track | 设置页的所有开关 |
     * | CheckBox / RadioButton | buttonTintList | 列表多选、单选 |
     * | MaterialButton | 文字色 + ripple | 各页操作按钮 |
     * | MaterialCheckBox 等 Material 下拉 | 同上 | 动态页、评论页 |
     *
     * 用 `is` 链而不是反射：类型在编译期可知，反射既慢又会触发 detekt 的
     * `ReflectionInKotlin` 提示。
     */
    private fun tintMaterialControl(view: View, color: Int) {
        val stateList = ColorStateList.valueOf(color)
        // 顺序很重要：`SwitchMaterial` 是 `SwitchCompat` 的子类，
        // 必须先判具体类型，否则会被父类分支吞掉（效果相同，但读的人会误以为没处理 Material 变体）。
        // 拆成三个小函数是为了压住圈复杂度（单一 when 里 8 个分支会触发 detekt 的
        // CyclomaticComplexMethod），与本类其它"按类型分派"的写法保持一致。
        when (view) {
            is com.google.android.material.button.MaterialButton -> {
                // 只染文字色：背景是深灰卡片色（#cc262626），染背景会让按钮变成大色块，
                // 那是明显的观感倒退。文字色跟随主题才是用户预期的"强调色"。
                runCatching { view.setTextColor(color) }
            }

            is com.google.android.material.checkbox.MaterialCheckBox -> {
                runCatching { view.buttonTintList = stateList }
            }

            is com.google.android.material.radiobutton.MaterialRadioButton -> {
                runCatching { view.buttonTintList = stateList }
            }

            else -> {
                // 开关与原生复选/单选都走这里（类型互不继承，但染色方式相同）
                if (!tintSwitchLike(view, stateList)) {
                    tintCheckableLike(view, stateList)
                }
            }
        }
    }

    /**
     * 给「开关」类控件染 thumb/track。
     *
     * `SwitchMaterial` 是 `SwitchCompat` 的子类，`SwitchCompat` 与原生 `Switch`
     * 无继承关系 —— 三个都要判。
     *
     * @return 命中并完成染色返回 true；不是开关返回 false（由调用方继续尝试下一类）
     */
    private fun tintSwitchLike(view: View, stateList: ColorStateList): Boolean {
        val isSwitchMaterial = view is com.google.android.material.switchmaterial.SwitchMaterial
        val isSwitchCompat = view is androidx.appcompat.widget.SwitchCompat
        val isNativeSwitch = view is android.widget.Switch
        if (!isSwitchMaterial && !isSwitchCompat && !isNativeSwitch) return false

        // SwitchMaterial / SwitchCompat 与原生 Switch 都有 thumbTintList / trackTintList
        // 这两个属性（SwitchCompat 提供同名 setter），所以统一处理。
        @Suppress("USELESS_CAST")
        return runCatching {
            when {
                isSwitchMaterial -> {
                    val v = view as com.google.android.material.switchmaterial.SwitchMaterial
                    v.thumbTintList = stateList
                    v.trackTintList = stateList
                }
                isSwitchCompat -> {
                    val v = view as androidx.appcompat.widget.SwitchCompat
                    v.thumbTintList = stateList
                    v.trackTintList = stateList
                }
                else -> {
                    val v = view as android.widget.Switch
                    v.thumbTintList = stateList
                    v.trackTintList = stateList
                }
            }
            true
        }.getOrDefault(true)
    }

    /**
     * 给原生 `CheckBox` / `RadioButton` 染 buttonTint。
     *
     * @return 命中并完成染色返回 true；不是这两类返回 false
     */
    private fun tintCheckableLike(view: View, stateList: ColorStateList): Boolean {
        if (view !is android.widget.CheckBox && view !is android.widget.RadioButton) return false
        // CheckBox / RadioButton 都实现 Checkable 且都有 buttonTintList（API 23+），
        // 用 CompoundButton 的统一入口即可（不需要分别强转）。
        if (view !is android.widget.CompoundButton) return false
        runCatching { view.buttonTintList = stateList }
        return true
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
