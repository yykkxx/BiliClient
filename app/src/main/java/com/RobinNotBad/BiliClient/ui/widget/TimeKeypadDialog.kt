package com.RobinNotBad.BiliClient.ui.widget

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TimeInputParser
import com.RobinNotBad.BiliClient.util.ToolsUtil

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 「跳转到指定时间」的**屏幕内数字键盘**对话框（v5.2.1 起，v5.3.0 补高度适配）。
 *
 * # 为什么不再用系统键盘
 * 旧实现是 `EditText` + `AlertDialog`，靠系统输入法弹键盘。在**手表/圆屏**上这有三个硬伤：
 * 1. **键盘会把对话框顶飞**：圆屏可用面积本来就小，输入法一弹，数字行被挤出屏幕外看不见自己打了什么；
 * 2. **打不了冒号**：`TimeInputParser` 支持 `1:30` / `1：02：03` 这类格式，但系统数字键盘没有 `:`，
 *    用户被迫切到符号页 —— 在 1.5 英寸屏上基本不可用；
 * 3. **系统手势冲突**：部分 wear ROM 的输入法面板会吃掉返回手势与表冠滚动。
 *
 * 所以改成**自己画 3×4 数字键盘**（数字 + 冒号 + 退格），
 * 上方实时显示"已输入 → 会跳到哪里"，用户不用猜格式对不对。
 *
 * # 圆屏适配：v5.2.1 的 bug 与 v5.3.0 的修法
 * **现象**：用户报「时间跳转界面的数字显示到屏幕下面去了」。
 * **根因**：v5.2.1 只约束了**宽度**（`屏宽 / √2`），高度仍是 `WRAP_CONTENT` + `Gravity.CENTER`。
 * 内容是「标题 + 输入行 + 预览 + 4 行按键 + 动作行」，自然高度约 **240~280dp**，
 * 而真实设备的整屏只有 **233dp**（466px / 320dpi，见 notes/notes_story_proposal.md:26）——
 * 超出部分被 `Gravity.CENTER` 上下对半裁掉，下半截（最后一行按键 + 取消/跳转）就跑到屏幕外了。
 * **所以这不是「宽度不够」，是「高度从来没被约束过」。**
 *
 * **为什么不能简单套一个内接正方形**：圆的内接正方形边长 = 直径 / √2 ≈ 165dp，
 * 而内容最少也要 210dp 以上（12 个按键 + 3 行文字）。在 165×165 里根本放不下 ——
 * 硬塞只能把按键压到 15dp 高，点都点不准。内接正方形是「宽高相等」的特例，本身就很浪费。
 *
 * **实际做法**（三道防线）：
 * 1. **每行按圆弦收窄**：面板宽度取内接正方形宽（165dp，按键能到 ~50dp 宽），
 *    高度一直撑到接近整个圆；靠近上下两端的行，按它所在高度的**圆弦长度**收窄左右边距。
 *    这样四角永远在圆内，而中部的键盘又不会被白削窄。
 * 2. **按键高度自适应**：在 [KEY_MIN_DP] ~ [KEY_MAX_DP] 之间从大到小试，取第一个「装得下」的档位
 *    （写死 42dp 的常量已删除）；字号跟着按键高度走，避免字顶出按键。
 * 3. **兜底可滚动**：以上都不成立时（极小的屏/超大字体），高度夹进可见区并套 [RotaryScrollView]，
 *    宁可让用户滚一下，也绝不裁切 —— 裁切是「看不见也点不到」，滚动至少还能操作。
 *    这种情况下标题先让位（`GONE`）：标题只是提示，输入行和键盘才是必须能看见的。
 *
 * 圆屏判定不靠「屏幕接近正方形」这种猜测（那样会把方屏手机也当圆屏、压成一小坨），
 * 而是用系统自己声明的 `Configuration.SCREENLAYOUT_ROUND_YES`，再加上用户手动的
 * `player_ui_round` 开关兜底。方屏手机走矩形分支，宽度仍是旧的 `屏宽 × 0.707`，观感与 v5.2.1 一致。
 *
 * # 为什么自带 `TimeInputParser`
 * 输入内容与旧版**完全同格式**（`90` / `1:30` / `1：02：03` / `5 30`），因为解析仍走
 * [TimeInputParser.parse]。本类只负责"怎么让用户把字符敲进去"，不重新定义时间格式 ——
 * 否则用户在旧版存的习惯、笔记里的示例就全对不上了。
 */
class TimeKeypadDialog(
    /** 宿主 Activity：用它取可见区来算安全区（Dialog 自己的窗口尺寸正是要算的东西，不能用） */
    private val hostActivity: Activity,
    private val durationMs: Long,
    private val onConfirm: (targetMs: Long) -> Unit
) : Dialog(hostActivity) {

    /** 已输入的内容（数字 / 冒号 / 空格），点"跳转"时交给 TimeInputParser 解析 */
    private val buffer = StringBuilder()

    /**
     * 「跳转」是否已经确认过（v5.3.4 的幂等闸门）。
     *
     * 为什么需要：[confirm] 里先 `dismiss()` 再 `onConfirm`，但 `dismiss()` 只是把
     * 窗口收起，**已经在消息队列里的下一次点击不会消失**。手指连点两下「跳转」
     * 就会 seek 两次、弹两个 toast；本版还给播放器挂了 `OnDismissListener` 收卡片，
     * 重复触发会让这套收尾跑两遍。加一个布尔闸门比给调用方加去重简单得多。
     */
    private var confirmed = false

    /** 实时预览文本（"会跳转到 01:30"或"格式不对"），跟着 buffer 变 */
    private lateinit var preview: TextView

    /** 已输入内容的显示行（`lateinit` 而非 findViewById：手机上没有稳定的 id 体系给它） */
    private lateinit var inputView: TextView

    /** 标题行：可用高度实在不够时会被隐藏（见类注释第 3 点） */
    private lateinit var titleView: TextView

    /** 内容根容器：所有尺寸计算都以它的 `measuredHeight` 为准，不靠估算 */
    private lateinit var content: LinearLayout

    /**
     * 所有需要按圆弦收窄的「行」，按从上到下的顺序。
     * 用显式列表而不是 `content.getChildAt(i)`：万一以后插入新行，这里的顺序不会悄悄错位。
     */
    private val rows = ArrayList<View>(ROW_COUNT)

    /** 12 个数字键 + 冒号 + 退格，统一调整高度与字号 */
    private val keyButtons = ArrayList<Button>(12)

    /** 取消 / 跳转两个动作键 */
    private val actionButtons = ArrayList<Button>(2)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setGravity(Gravity.CENTER)
        setCanceledOnTouchOutside(true)
        buildContent()
    }

    // ---------------------------------------------------------------- 布局

    private fun buildContent() {
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 半透明底 + 圆角，跟播放器的浮层风格一致（不引第三方面板）
            setBackgroundColor(0xE6000000.toInt())
            setPadding(pad(CONTENT_PAD_H_DP), pad(CONTENT_PAD_V_DP), pad(CONTENT_PAD_H_DP), pad(CONTENT_PAD_V_DP))
        }

        /* 标题：告诉用户"这里在干什么"；放不下时会被 GONE 掉 */
        titleView = TextView(context).apply {
            text = "跳转到指定时间"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }
        addRow(titleView)

        /* 已输入内容的显示行（等宽字体避免数字宽度变化导致左右跳） */
        inputView = TextView(context).apply {
            text = "—"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            // 等宽字体：数字 1 与 8 的宽度不同，不用等宽字体会让显示行左右跳动
            typeface = Typeface.MONOSPACE
            setPadding(0, pad(2), 0, pad(2))
        }
        addRow(inputView)

        /* 实时预览：格式对不对、会跳到哪，用户不用猜 */
        preview = TextView(context).apply {
            text = ""
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(2))
        }
        addRow(preview)

        /* 3×4 键盘：1~9、冒号、0、退格 */
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(":", "0", "⌫")
        )
        for (row in keys) {
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (label in row) {
                val key = makeKey(label)
                keyButtons.add(key)
                rowView.addView(key)
            }
            addRow(rowView)
        }

        /* 底部两个动作：跳转 / 取消（等宽并排，圆屏上也点得到） */
        addRow(makeActionRow())

        // 三行文字全部强制单行 + 省略号。这不是美观问题，而是**算法正确性的前提**：
        // 按圆弦收窄会改行的左右边距，一旦文字因此换行，行高就会变，
        // 而 nailRowsToCircle 已经按旧行高算完了坐标 —— 高度一变，底部就会重新跑到圆外。
        forceSingleLine(titleView)
        forceSingleLine(inputView)
        forceSingleLine(preview)

        // 套一层 ScrollView：高度真的不够时能滚，而不是像旧版那样被裁掉 ——
        // 裁掉是「看不见也点不到」，滚动至少还能操作。顺手用 RotaryScrollView 支持表冠。
        val scroller = RotaryScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 这里必须写全 ViewGroup.LayoutParams：apply 的接收者是 RotaryScrollView，
            // 裸写 LayoutParams 解析不到（它不是 ViewGroup 内部类的作用域）
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scroller)
        refreshPreview()
    }

    /** 把一行加进内容区，同时登记到 [rows] 供后面按弦收窄 */
    private fun addRow(row: View) {
        rows.add(row)
        content.addView(row)
    }

    /** 文字行强制单行，保证收窄时行高恒等（理由见 [buildContent] 里的注释） */
    private fun forceSingleLine(view: TextView) {
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.END
        view.maxLines = 1
    }

    /** 单个按键：等宽权重；高度与字号由 [applyKeyHeight] 统一算（不写死 dp） */
    private fun makeKey(label: String): Button {
        return Button(context).apply {
            text = label
            textSize = KEY_TEXT_MAX_SP
            setTextColor(0xFFFFFFFF.toInt())
            // 按键背景：极简深灰半透明，不做圆角阴影（手表 GPU 上阴影开销明显）
            setBackgroundColor(0x33FFFFFF.toInt())
            // Button 自带 minHeight/padding 很大，圆屏上会撑爆，这里全部清零由外层尺寸接管
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(KEY_MAX_DP), 1f)
                .apply { setMargins(pad(KEY_GAP_DP), pad(KEY_GAP_DP), pad(KEY_GAP_DP), pad(KEY_GAP_DP)) }
            setOnClickListener { onKey(label) }
        }
    }

    private fun makeActionRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad(ACTION_ROW_GAP_DP), 0, 0)
        }
        row.addView(actionButton("取消", 0x33FFFFFF) { dismiss() })
        row.addView(actionButton("跳转", 0x4DFB7299) { confirm() })
        return row
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            textSize = ACTION_TEXT_MAX_SP
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(color)
            setPadding(0, 0, 0, 0)
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(ACTION_MAX_DP), 1f)
                .apply { setMargins(pad(KEY_GAP_DP), 0, pad(KEY_GAP_DP), 0) }
            setOnClickListener { action() }
            actionButtons.add(this)
        }
    }

    // ---------------------------------------------------------------- 输入逻辑

    /**
     * 处理一次按键。
     *
     * `⌫` 删最后一个字符；其它字符**直接追加**。
     * 不做"自动补冒号"之类的智能处理：用户可能是 `90`（纯秒）也可能是 `130`（1 分 30 秒），
     * 自动插入冒号会把前者破坏掉。真正的语义由 `TimeInputParser.parse` 在点跳转时判断。
     */
    private fun onKey(label: String) {
        if (label == "⌫") {
            if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
        } else {
            // 上限 8 字符：`1:02:03` 是 7 字符，留一位余量。再长就是误触/无意义输入
            if (buffer.length < MAX_INPUT_LENGTH) buffer.append(label)
        }
        refreshPreview()
    }

    /**
     * 刷新「已输入」与「预览」两行。
     *
     * 预览分三态，比旧版"点了才知道格式错"好：
     * - 空输入 → 提示格式示例（用户第一次打开就知道怎么打）；
     * - 格式合法 → 显示解析出的 `mm:ss`（并夹到视频时长内，所见即所得）；
     * - 格式非法 → 直接说"格式不对"，用户不用等点跳转才被弹回来。
     */
    private fun refreshPreview() {
        inputView.text = if (buffer.isEmpty()) "—" else buffer.toString()
        val seconds = TimeInputParser.parse(buffer.toString())
        preview.text = when {
            buffer.isEmpty() -> "示例：90 · 1:30 · 1:02:03"
            seconds < 0 -> "格式不对，按 : 分隔分秒"
            else -> {
                var targetMs = seconds * 1000L
                if (durationMs > 0 && targetMs > durationMs) targetMs = durationMs
                "将跳转到 " + formatTime(targetMs)
            }
        }
    }

    private fun confirm() {
        // 幂等：确认只算一次。手指在「跳转」上连点两下时，第一次 dismiss() 之后
        // 第二次点击仍可能已经在队列里，重复回调会让播放器 seek + toast 两次。
        // （v5.3.4 起 onConfirm 之外还挂了播放器的 onDismissed，重复触发的代价更明显）
        if (confirmed) return
        val seconds = TimeInputParser.parse(buffer.toString())
        if (seconds < 0) {
            // 留在对话框里让用户改，而不是关掉再重开（手表上重开成本高）
            preview.text = "格式不对，按 : 分隔分秒"
            return
        }
        confirmed = true
        var targetMs = seconds * 1000L
        if (targetMs < 0) targetMs = 0
        if (durationMs > 0 && targetMs > durationMs) targetMs = durationMs
        // 先关自己再回调：调用方拿到回调时输入界面已经不在了（否则会出现
        // 「seek 完对话框还盖着、要再点一次才消失」的一帧）
        dismiss()
        onConfirm(targetMs)
    }

    /** 毫秒 → `mm:ss`（超过 1 小时给 `h:mm:ss`，与播放器进度条口径一致） */
    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    // ---------------------------------------------------------------- 尺寸计算

    override fun show() {
        super.show()
        // 软键盘永不弹出：本对话框全部按键都是屏幕内的，系统输入法一弹就会把数字行顶出圆屏
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        // 必须放在 show() 之后：只有窗口真的显示过了，宿主 decorView 的可见区才有真实值
        applyWindowSize()
    }

    /**
     * 算出并应用窗口尺寸。
     *
     * 先试「带标题」，装不下再隐藏标题重试（标题只是提示，输入行和键盘才是必须的）。
     * 两次都装不下才走兜底滚动。
     */
    private fun applyWindowSize() {
        val bounds = visibleBounds()
        val availW = (bounds.width() - dp(EDGE_MARGIN_DP) * 2).coerceAtLeast(dp(MIN_PANEL_DP))
        val availH = (bounds.height() - dp(EDGE_MARGIN_DP) * 2).coerceAtLeast(dp(MIN_PANEL_DP))
        val round = isRoundUi()

        val metrics = fitMetrics(round, availW, availH)
            ?: run {
                // 放不下：让标题让位再试一次
                titleView.visibility = View.GONE
                fitMetrics(round, availW, availH)
            }
            ?: fallbackMetrics(round, availW, availH)

        window?.setLayout(metrics.widthPx, metrics.heightPx)
    }

    /** 从大到小试按键高度，返回第一个通过校验的方案；全都不行返回 null */
    private fun fitMetrics(round: Boolean, availW: Int, availH: Int): Metrics? {
        var keyDp = KEY_MAX_DP
        while (keyDp >= KEY_MIN_DP) {
            val metrics = candidateFor(keyDp, round, availW, availH)
            if (metrics != null) return metrics
            keyDp -= KEY_STEP_DP
        }
        return null
    }

    /**
     * 试探一个按键高度是否可行。
     *
     * 先量出内容的**真实高度**（量一次远胜按字号估算），再：
     * - 方屏：只要求高度不超可见区（宽度沿用旧版 `屏宽 × 0.707`，手机上观感不变）；
     * - 圆屏：要求高度不超直径，且**每一行按圆弦收窄后仍够宽**（下面这条最容易被忘掉）。
     */
    private fun candidateFor(keyDp: Int, round: Boolean, availW: Int, availH: Int): Metrics? {
        val keyHeightPx = dp(keyDp)
        val actionHeightPx = dp(keyDp.coerceIn(ACTION_MIN_DP, ACTION_MAX_DP))
        applyKeyHeight(keyHeightPx)
        applyActionHeight(actionHeightPx)
        resetRowMargins()

        val panelWidth = if (round) {
            // 圆屏：宽度取内接正方形，保证中部的 3 列按键足够宽
            min(availW, (min(availW, availH) / SQRT2).toInt())
        } else {
            (availW * WIDTH_FRACTION).toInt()
        }
        if (panelWidth <= 0) return null
        val height = measureContentHeight(panelWidth)

        if (!round) {
            if (height > availH) return null
            if (keyWidthFor(panelWidth) < dp(KEY_MIN_WIDTH_DP)) return null
            return Metrics(panelWidth, height, keyHeightPx, actionHeightPx)
        }

        val diameter = min(availW, availH)
        if (height > diameter) return null
        // 关键：收窄之后不再用「面板宽」判断按键够不够宽，而是用**每一行自己的弦宽**
        if (!nailRowsToCircle(panelWidth, height, diameter)) return null
        return Metrics(panelWidth, height, keyHeightPx, actionHeightPx)
    }

    /**
     * 按圆弦收窄每一行的左右边距，并返回「收窄后每一行是否都还够宽」。
     *
     * 面板是一个**放在圆里**的矩形：只要四角在圆内，中间一定也在圆内。
     * 但整块矩形一起缩会把中部的按键白白削窄，所以逐行处理 ——
     * 每行按它自己的上/下沿到圆心距离取圆弦，只有超出弦长的行才缩进。
     *
     * 行高在收窄前后不变（只改左右 margin，且所有行都是固定高度/强制单行），
     * 所以量一次就够，不会陷入「改宽度 → 变高 → 再改」的循环。
     */
    private fun nailRowsToCircle(panelWidth: Int, panelHeight: Int, diameter: Int): Boolean {
        val radius = diameter / 2.0
        val centerY = panelHeight / 2.0
        var top = content.paddingTop.toDouble()
        var allFit = true

        for ((index, row) in rows.withIndex()) {
            val rowHeight = if (row.measuredHeight > 0) row.measuredHeight.toDouble() else row.height.toDouble()
            val bottom = top + rowHeight
            // 该行离圆心最远的那条边，决定它最多能有多宽（圆弦长度）
            val farEdge = maxOf(kotlin.math.abs(top - centerY), kotlin.math.abs(bottom - centerY))
            val chord = 2.0 * sqrt((radius * radius - farEdge * farEdge).coerceAtLeast(0.0))
            val allowed = min(panelWidth.toDouble(), chord).toInt()
            applyRowInsets(row, panelWidth, allowed)
            if (allowed < minWidthForRow(index)) allFit = false
            top = bottom
        }
        return allFit
    }

    /**
     * 某一行至少要有多宽才「还能用」。
     *
     * 不看这条就会退化成一个真实的坑：圆屏最底部那行的圆弦只有几十 dp，
     * 如果只按面板宽算按键宽度，会得出「够宽」的错误结论，
     * 实际渲染时却把「取消/跳转」压成两个点不到的窄条。
     */
    private fun minWidthForRow(index: Int): Int = when {
        // 键盘行：3 个键各不小于 KEY_MIN_WIDTH_DP，再加键间外边距
        isKeyRow(index) -> dp(KEY_GAP_TOTAL_DP) + COLUMN_COUNT * dp(KEY_MIN_WIDTH_DP)
        // 动作行：取消/跳转各不小于 ACTION_MIN_WIDTH_DP（标准最小触控宽度）
        index == ACTION_ROW_INDEX -> 2 * dp(ACTION_MIN_WIDTH_DP) + 2 * dp(KEY_GAP_DP) * 2
        // 文字行：至少容得下几字符，否则「—」「00:00」会被省略号吃掉
        else -> dp(TEXT_MIN_WIDTH_DP)
    }

    /** 把一行缩到 [allowedWidth]：左右各让出 (面板宽 − 允许宽) / 2 */
    private fun applyRowInsets(row: View, panelWidth: Int, allowedWidth: Int) {
        val params = row.layoutParams as? LinearLayout.LayoutParams ?: return
        val inset = ((panelWidth - allowedWidth).coerceAtLeast(0)) / 2
        if (params.leftMargin == inset && params.rightMargin == inset) return
        params.leftMargin = inset
        params.rightMargin = inset
        row.layoutParams = params
    }

    /** 清掉上一轮试探留下的缩进，保证每次试探都是从「满宽」开始量 */
    private fun resetRowMargins() {
        for (row in rows) {
            clearRowMargin(row)
        }
    }

    /**
     * 把一行的左右边距清零。
     *
     * 从 [resetRowMargins] 的循环体拆出来，是为了避免「一个循环里两个 continue」
     * （detekt LoopWithTooManyJumpStatements）：`as?` 失败与「本来就没有边距」是两个独立的
     * 提前退出条件，各自独立成函数后循环体只剩一次调用，读起来也不用在脑子里模拟两条跳出路径。
     */
    private fun clearRowMargin(row: View) {
        val params = row.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.leftMargin == 0 && params.rightMargin == 0) return
        params.leftMargin = 0
        params.rightMargin = 0
        row.layoutParams = params
    }

    /**
     * 兜底：按键取最小档，高度夹进可见区，剩下的交给 ScrollView 滚（**绝不裁切**）。
     *
     * 注意这里**仍然要按圆弦收窄**：兜底只是「放不下所以可以滚」，
     * 不代表可以把内容铺到黑角里去 —— 滚动的区域本身也得留在圆内，否则滚出来照样点不到。
     */
    private fun fallbackMetrics(round: Boolean, availW: Int, availH: Int): Metrics {
        val keyHeightPx = dp(KEY_MIN_DP)
        val actionHeightPx = dp(ACTION_MIN_DP)
        applyKeyHeight(keyHeightPx)
        applyActionHeight(actionHeightPx)
        resetRowMargins()
        val diameter = min(availW, availH)
        val panelWidth = if (round) {
            min(availW, (diameter / SQRT2).toInt())
        } else {
            (availW * WIDTH_FRACTION).toInt()
        }
        val natural = measureContentHeight(panelWidth)
        // 兜底时内容可能比圆还高，此时按「整个面板高」取弦会把中间行也削得极窄，
        // 所以用夹进可见区后的高度来算 —— 滚动只在面板内部发生，圆内可见部分始终够宽。
        val panelHeight = min(natural, availH)
        if (round) nailRowsToCircle(panelWidth, panelHeight, diameter)
        return Metrics(panelWidth, panelHeight, keyHeightPx, actionHeightPx)
    }

    /** 内容在给定宽度下的真实高度（含内边距）：量一次比按字号估算靠谱 */
    private fun measureContentHeight(widthPx: Int): Int {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return content.measuredHeight
    }

    /**
     * 单个数字键的可用宽度（扣掉内容左右内边距与按键间距后三等分）。
     *
     * 为什么必须检查它：圆屏上越靠边的行越窄，不设下限会算出
     * 「高 24dp、宽 9dp」这种根本点不到的条状按键。
     */
    private fun keyWidthFor(containerWidth: Int): Int {
        val usable = containerWidth - content.paddingLeft - content.paddingRight -
            dp(KEY_GAP_TOTAL_DP)
        return usable / COLUMN_COUNT
    }

    private fun applyKeyHeight(heightPx: Int) {
        // 字号跟着按键高度走：圆屏上按键可能只有 24dp 高，
        // 还写死 17sp（行高约 22dp）会把字顶出按键、看起来像被裁掉。
        val textSp = (heightPx / context.resources.displayMetrics.scaledDensity * TEXT_HEIGHT_RATIO)
            .coerceIn(KEY_TEXT_MIN_SP, KEY_TEXT_MAX_SP)
        for (button in keyButtons) {
            setViewHeight(button, heightPx)
            button.textSize = textSp
        }
    }

    private fun applyActionHeight(heightPx: Int) {
        val textSp = (heightPx / context.resources.displayMetrics.scaledDensity * TEXT_HEIGHT_RATIO)
            .coerceIn(KEY_TEXT_MIN_SP, ACTION_TEXT_MAX_SP)
        for (button in actionButtons) {
            setViewHeight(button, heightPx)
            button.textSize = textSp
        }
    }

    private fun setViewHeight(view: View, heightPx: Int) {
        val params = view.layoutParams ?: return
        if (params.height == heightPx) return
        params.height = heightPx
        view.layoutParams = params
    }

    /**
     * 可见区：优先用宿主 Activity 的 `getWindowVisibleDisplayFrame`（已扣掉状态栏/导航栏），
     * 拿不到时退回 `displayMetrics`（此时只是可能多算系统栏，内容仍会被窗口边界兜住）。
     */
    private fun visibleBounds(): Rect {
        val rect = Rect()
        hostActivity.window?.decorView?.getWindowVisibleDisplayFrame(rect)
        if (rect.width() > 0 && rect.height() > 0) return rect
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * 是否按圆屏算安全区。
     *
     * 用系统声明的 `SCREENLAYOUT_ROUND_YES` 而不是「屏幕接近正方形」这种猜测 ——
     * 方屏手机（同样是正方形）也满足「宽≈高」，猜错会把它压成窄窄一小坨。
     * 再加上用户手动的「圆屏适配」开关兜底：有些 wear ROM 不声明 ROUND_YES。
     */
    private fun isRoundUi(): Boolean {
        if (SharedPreferencesUtil.getBoolean(PREF_ROUND_UI, false)) return true
        val screenLayout = context.resources.configuration.screenLayout
        return (screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK) ==
            Configuration.SCREENLAYOUT_ROUND_YES
    }

    /** 是否是键盘行（决定要不要校验按键宽度）；rows 顺序见 [buildContent] */
    private fun isKeyRow(index: Int): Boolean = index >= FIRST_KEY_ROW && index < FIRST_KEY_ROW + KEY_ROW_COUNT

    private fun pad(dp: Int): Int = dp(dp)

    private fun dp(value: Int): Int = ToolsUtil.dp2px(value.toFloat())

    /** 一次尺寸计算结果（px；宽度=窗口宽，高度=窗口高，按键/动作高度已写进各自 LayoutParams） */
    private data class Metrics(
        val widthPx: Int,
        val heightPx: Int,
        val keyHeightPx: Int,
        val actionHeightPx: Int
    )

    companion object {
        /**
         * 按键高度下限。
         *
         * 为什么是 24：在**真实设备**（466px / 320dpi ≈ 233dp 直径圆屏）上，
         * 「标题 + 输入行 + 预览 + 4 行按键 + 动作行」压到 24dp 时内容约 225dp，
         * 刚好留在直径内；再高就必然超出圆屏，只能靠滚动（那就不是「一眼看全」了）。
         * 24dp 在手表上约 4mm，配合整行宽触控区仍然点得准。
         */
        private const val KEY_MIN_DP = 24

        /** 按键高度上限：手表上手指相对屏幕粗，再大就挤掉别的行 */
        private const val KEY_MAX_DP = 44

        /** 每次试探的按键高度步进 */
        private const val KEY_STEP_DP = 2

        /** 单个数字键的宽度下限（低于它就是点不到的细条，此时降一档按键高度重试） */
        private const val KEY_MIN_WIDTH_DP = 30

        /** 动作键的宽度下限：Android 的 48dp 触控区在手表上按比例略收一点，仍远大于 44dp 标准 */
        private const val ACTION_MIN_WIDTH_DP = 44

        /** 文字行（标题/输入/预览）至少要容得下的宽度，否则会被省略号吃成「…」 */
        private const val TEXT_MIN_WIDTH_DP = 70

        /** 动作按钮高度区间 */
        private const val ACTION_MIN_DP = 26
        private const val ACTION_MAX_DP = 38

        /** 按键文字占按键高度的比例，以及字号上下限 */
        private const val TEXT_HEIGHT_RATIO = 0.5f
        private const val KEY_TEXT_MIN_SP = 11f
        private const val KEY_TEXT_MAX_SP = 17f
        private const val ACTION_TEXT_MAX_SP = 14f

        /** 按键上下左右的外边距（dp） */
        private const val KEY_GAP_DP = 2

        /** 三个按键的左右外边距合计（dp）：算可用宽度时扣掉 */
        private const val KEY_GAP_TOTAL_DP = 12

        /** 动作行与键盘之间的间隙（dp） */
        private const val ACTION_ROW_GAP_DP = 4

        /** 键盘列数 / 行数 */
        private const val COLUMN_COUNT = 3
        private const val KEY_ROW_COUNT = 4

        /** 内容左右 / 上下内边距（dp） */
        private const val CONTENT_PAD_H_DP = 8
        private const val CONTENT_PAD_V_DP = 6

        /** 窗口与屏幕边缘的留白（dp） */
        private const val EDGE_MARGIN_DP = 2

        /** 可用区小到离谱时的下限，避免算出 0 或负数尺寸 */
        private const val MIN_PANEL_DP = 60

        /** 方屏上沿用 v5.2.1 的宽度比例：屏宽 × 0.707 */
        private const val WIDTH_FRACTION = 0.707f

        private const val SQRT2 = 1.4142f

        /** 总行数：标题 + 输入行 + 预览 + 4 行键盘 + 动作行 */
        private const val ROW_COUNT = 8

        /** 第一行键盘在 [rows] 里的下标（标题0 / 输入1 / 预览2 → 键盘从 3 开始） */
        private const val FIRST_KEY_ROW = 3

        /** 动作行（取消/跳转）在 [rows] 里的下标：标题0 / 输入1 / 预览2 / 键盘3~6 / 动作7 */
        private const val ACTION_ROW_INDEX = 7

        /** 输入长度上限（`1:02:03` 是 7 字符，留 1 位余量） */
        private const val MAX_INPUT_LENGTH = 8

        /** 「圆屏适配」偏好键：与播放器/其它页面共用的同一个键 */
        private const val PREF_ROUND_UI = "player_ui_round"
    }
}
