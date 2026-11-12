package com.RobinNotBad.BiliClient.ui.motion

import android.os.SystemClock
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

/**
 * 动效总闸门（v5.3.6「丰富动效」）。
 *
 * # 为什么需要一个统一的闸门，而不是各页面自己判断
 * 动效散落在页面切换、列表增删、进场、加载、按压反馈五处，
 * 如果每处各自读偏好，就一定会出现「开关关了但某个地方还在动」——
 * 这类「半残」是本项目历史上反复被用户点名的观感问题。
 * 所以所有动效**只允许**通过本对象取开关值与时长，任何一个地方都不许再读
 * `ui_motion_*` 键名（键名只在本文件出现一次）。
 *
 * # 为什么还要看系统的「移除动画」设置
 * 系统无障碍里的「移除动画」会把 `ANIMATOR_DURATION_SCALE` 置 0。
 * 用户开了它却还能看到我们自绘的动画，体验上等于开关失效；
 * 更糟的是某些省电场景系统会把它调得很低，此时我们的动画会显得「卡顿发涩」。
 * 所以：**系统关闭动画时，本模块整体静默**（等价于总开关关闭），
 * 这一条不提供开关，因为它表达的是用户的系统级意愿。
 *
 * # 默认值为什么是「开」
 * 用户的需求是「丰富动效动画视觉效果（有开关可以打开或者关闭）」——
 * 默认关闭等于升级后什么都看不到，用户无从判断要不要留。
 * 但每个动效都做了成本约束（时长上限、只动首屏几项、可被系统设置整体关掉），
 * 且总开关就在 设置 → 界面设置 的第一屏，随时可关。
 */
object Motion {

    /** 总开关（设置 → 界面设置 → 动效） */
    const val PREF_ENABLE = "ui_motion_enable"

    /** 列表动效：进场淡入、增删移动过渡 */
    const val PREF_LIST = "ui_motion_list"

    /** 页面切换动效：进页滑入、退页滑出 */
    const val PREF_PAGE = "ui_motion_page"

    /** 加载动效：加载图呼吸脉冲 */
    const val PREF_LOADING = "ui_motion_loading"

    /** 触摸反馈：卡片 / 按钮按下轻微缩放 */
    const val PREF_TOUCH = "ui_motion_touch"

    // ---------------- 时长基准（毫秒） ----------------
    // 数值按手表小屏调过：超过 300ms 在圆屏上会明显「拖」，
    // 低于 120ms 又看不出过渡感。全部集中在这里，改手感只改这一处。

    /** 页面进入动画时长 */
    const val DURATION_PAGE_IN = 220L

    /** 页面退出动画时长 */
    const val DURATION_PAGE_OUT = 180L

    /** 列表项新增 */
    const val DURATION_ITEM_ADD = 200L

    /** 列表项移除 */
    const val DURATION_ITEM_REMOVE = 160L

    /** 列表项内容变化 / 位置移动 */
    const val DURATION_ITEM_CHANGE = 180L

    /** 进场淡入单项时长 */
    const val DURATION_ENTRANCE = 260L

    /** 进场相邻两项的间隔（错峰，形成「依次浮现」而不是「整片闪现」） */
    const val ENTRANCE_STAGGER = 32L

    /** 进场最多动几项：只做首屏可见范围，超出的部分不做，避免长列表一次性拉起几十个动画 */
    const val ENTRANCE_MAX_ITEMS = 10

    /** 进场时初始下沉距离（dp） */
    const val ENTRANCE_TRANSLATE_DP = 12f

    /** 加载图脉冲一个来回 */
    const val DURATION_LOADING_PULSE = 1400L

    /** 按压缩放下沉时长 */
    const val DURATION_TOUCH_DOWN = 90L

    /** 按压缩放回弹时长 */
    const val DURATION_TOUCH_UP = 120L

    /** 按压时缩到多少（0.96 = 轻微内缩，不夸张；再小会在圆屏上缩出明显空洞感） */
    const val TOUCH_SCALE = 0.96f

    /**
     * 系统动画时长倍率缓存。
     *
     * 读一次要走 `Settings.Global` 的 ContentProvider，页面里调得比较频繁
     * （每次刷新结束都会问一次开关），所以缓存 2 秒：
     * 足够覆盖一次列表刷新里的多次查询，又能让用户在系统设置里改完动画后
     * 两秒内看到我们的表现跟着变。
     */
    private var cachedScale = 1f
    private var cachedAt = 0L

    /** 系统当前是否允许动画（`ANIMATOR_DURATION_SCALE` != 0） */
    @JvmStatic
    fun systemAnimationsAllowed(): Boolean = systemScale() > 0f

    /**
     * 读取系统的动画时长倍率。
     *
     * 任何异常都按 1.0（允许动画）处理：
     * - `BiliTerminal.context` 在 onCreate 之前是占位 Application，
     *   `contentResolver` 不可用会直接抛异常；
     * - 个别精简系统没有这个 Settings 项。
     * 这两种情况下「读不到就当成正常」比「读不到就全站关掉动效」安全得多 ——
     * 后者会让动效功能在某些设备上看起来完全失效，用户只会认为坏了。
     */
    private fun systemScale(): Float {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt < SYSTEM_SCALE_CACHE_MS) return cachedScale
        cachedAt = now
        cachedScale = try {
            Settings.Global.getFloat(
                BiliTerminal.context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ).coerceAtLeast(0f)
        } catch (ignored: Exception) {
            1f
        }
        return cachedScale
    }

    /**
     * 安全读布尔偏好。
     *
     * `SharedPreferencesUtil` 的底层 `sharedPreferences` 在 BiliTerminal.onCreate 之前为空，
     * 直接读会 NPE（历史实现用 `!!`）。动效属于纯观感增强，读失败时回落到默认值
     * 比抛异常中断整个页面创建合理。
     */
    private fun prefBoolean(key: String, def: Boolean): Boolean {
        return try {
            SharedPreferencesUtil.getBoolean(key, def)
        } catch (ignored: Exception) {
            def
        }
    }

    /** 总闸门：用户开着动效 **且** 系统没有要求移除动画 */
    @JvmStatic
    fun isEnabled(): Boolean = prefBoolean(PREF_ENABLE, true) && systemAnimationsAllowed()

    /** 列表动效（进场 + 增删移动）是否可用 */
    @JvmStatic
    fun listEnabled(): Boolean = isEnabled() && prefBoolean(PREF_LIST, true)

    /** 页面切换动效是否可用 */
    @JvmStatic
    fun pageEnabled(): Boolean = isEnabled() && prefBoolean(PREF_PAGE, true)

    /** 加载动效是否可用 */
    @JvmStatic
    fun loadingEnabled(): Boolean = isEnabled() && prefBoolean(PREF_LOADING, true)

    /** 触摸反馈是否可用 */
    @JvmStatic
    fun touchEnabled(): Boolean = isEnabled() && prefBoolean(PREF_TOUCH, true)

    /**
     * 按系统倍率折算出的实际动画时长。
     *
     * 系统把动画调慢（例如开发者选项里设为 10x）时，我们的动画也应当跟着变慢 ——
     * 否则同一屏里系统动画很慢、我们的很快，观感上像是卡住了。
     * 上限 3 倍：再长就变成「点了没反应」，宁可短一点也不让人等。
     */
    @JvmStatic
    fun duration(baseMs: Long): Long {
        val scale = systemScale().coerceIn(0f, 3f)
        return (baseMs * scale).toLong()
    }

    /** dp → px 的薄封装（动效里到处要用，避免每处都 import ToolsUtil） */
    @JvmStatic
    fun dp(value: Float): Int = ToolsUtil.dp2px(value)

    /**
     * 统一缓动曲线。
     *
     * 用 `DecelerateInterpolator`（先快后慢）而不是线性：
     * 线性动画在小屏上会显得「机械」，减速曲线更接近真实物理的减速手感，
     * 也是 Material 动效规范里 recommended 的进入曲线。
     */
    @JvmStatic
    fun easeOut(): Interpolator = DecelerateInterpolator()

    /**
     * 给一个列表挂上本模块的动效（一次性完成两件事，避免调用方漏挂）。
     *
     * 1. 替换 `ItemAnimator` 为 [MotionItemAnimator]（增删移动过渡）；
     * 2. 装上进场错峰与按压反馈（由 [MotionEntrance] / [MotionTouch] 各自挂监听器）。
     *
     * 幂等判据用「itemAnimator 已经是我们的实现」而不是 View 的 tag：
     * `View.setTag(int, Object)` 要求 key 是应用资源 id（高字节非 0），
     * 而 `View.generateViewId()` 产生的高字节是 0，传进去会直接抛
     * IllegalArgumentException；自己造一个稳定 id 又得改 values 资源。
     * 用实例类型判断零成本、也不会与 adapter 自己写在 itemView 上的 tag 冲突。
     */
    @JvmStatic
    fun attach(list: RecyclerView?) {
        val recyclerView = list ?: return
        if (recyclerView.itemAnimator is MotionItemAnimator) return
        recyclerView.itemAnimator = MotionItemAnimator()
        MotionEntrance.install(recyclerView)
        MotionTouch.install(recyclerView)
    }

    private const val SYSTEM_SCALE_CACHE_MS = 2000L
}
