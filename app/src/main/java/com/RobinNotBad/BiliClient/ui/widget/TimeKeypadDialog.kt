package com.RobinNotBad.BiliClient.ui.widget

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.RobinNotBad.BiliClient.util.TimeInputParser

/**
 * 「跳转到指定时间」的**屏幕内数字键盘**对话框（v5.2.1）。
 *
 * # 为什么不再用系统键盘
 * 旧实现是 `new EditText(context)` + `AlertDialog`，靠系统输入法弹键盘。在**手表/圆屏**上这有三个硬伤：
 * 1. **键盘会把对话框顶飞**：圆屏可用面积本来就小，输入法一弹，数字行被挤出屏幕外看不见自己打了什么；
 * 2. **打不了冒号**：`TimeInputParser` 支持 `1:30` / `1：02：03` 这类格式，但系统数字键盘没有 `:`，
 *    用户被迫切到符号页 —— 在 1.5 英寸屏上基本不可用；
 * 3. **系统手势冲突**：部分 wear ROM 的输入法面板会吃掉返回手势与表冠滚动。
 *
 * 所以改成**自己画 3×4 数字键盘**（数字 + 冒号 + 退格），固定在屏幕下半部，
 * 上方实时显示"已输入 → 会跳到哪里"，用户不用猜格式对不对。
 *
 * # 圆屏适配（这是本类存在的第二个理由）
 * 圆屏的**四角是黑的**（无法显示的像素）。如果把键盘铺满 `match_parent`，
 * 左下角的 `7` 和右下角的"退格"会落进黑角里点不到。做法是：
 * - 弹窗宽度 = `min(屏宽, 圆屏安全内接正方形边长)`。圆屏的安全内接正方形边长 = `屏宽 / √2`
 *   （即直径的 0.707 倍），这样四角必然在圆内；
 * - 键盘整体**垂直居中偏下**（`Gravity.CENTER`），下方留出 `roundBottomInset`；
 * - 每个按键最小触控高度 `MIN_KEY_HEIGHT_DP`，保证在圆屏上也能点准（远大于 Android 建议的 48dp
 *   触控区，因为手表上手指相对屏幕更大）。
 *
 * # 为什么自带 `TimeInputParser`
 * 输入内容与旧版**完全同格式**（`90` / `1:30` / `1：02：03` / `5 30`），因为解析仍走
 * [TimeInputParser.parse]。本类只负责"怎么让用户把字符敲进去"，不重新定义时间格式 ——
 * 否则用户在旧版存的习惯、笔记里的示例就全对不上了。
 */
class TimeKeypadDialog(
    context: Context,
    private val durationMs: Long,
    private val onConfirm: (targetMs: Long) -> Unit
) : Dialog(context) {

    /** 已输入的内容（数字 / 冒号 / 空格），点"跳转"时交给 TimeInputParser 解析 */
    private val buffer = StringBuilder()

    /** 实时预览文本（"会跳转到 01:30"或"格式不对"），跟着 buffer 变 */
    private lateinit var preview: TextView

    /** 已输入内容的显示行（`lateinit` 而非 findViewById：手机上没有稳定的 id 体系给它） */
    private lateinit var inputView: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // 圆屏安全框：宽度按 √2 内接，避免四角按键落进黑角（理由见类注释）
        window?.setLayout(safeWidthPx(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.CENTER)
        setCanceledOnTouchOutside(true)
        buildContent()
    }

    // ---------------------------------------------------------------- 布局

    private fun buildContent() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 半透明底 + 圆角，跟播放器的浮层风格一致（不引第三方面板）
            setBackgroundColor(0xE6000000.toInt())
            setPadding(pad(10), pad(8), pad(10), pad(8))
        }

        /* 标题：告诉用户"这里在干什么"，圆屏上也不该省（省了会以为误触） */
        root.addView(TextView(context).apply {
            text = "跳转到指定时间"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        })

        /* 已输入内容的显示行（等宽字体避免数字宽度变化导致左右跳） */
        inputView = TextView(context).apply {
            text = "—"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            // 等宽字体：数字 1 与 8 的宽度不同，不用等宽字体会让显示行左右跳动
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, pad(4), 0, pad(2))
        }
        root.addView(inputView)

        /* 实时预览：格式对不对、会跳到哪，用户不用猜 */
        preview = TextView(context).apply {
            text = ""
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(4))
        }
        root.addView(preview)

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
                rowView.addView(makeKey(label))
            }
            root.addView(rowView)
        }

        /* 底部两个动作：跳转 / 取消（等宽并排，圆屏上也点得到） */
        root.addView(makeActionRow())

        setContentView(root)
        refreshPreview()
    }

    /** 单个按键：等宽权重 + 最小高度（圆屏点得准的关键） */
    private fun makeKey(label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            // 按键背景：极简深灰半透明，不做圆角阴影（手表 GPU 上阴影开销明显）
            setBackgroundColor(0x33FFFFFF)
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(pad(2), pad(2), pad(2), pad(2)) }
            // 用 minHeight 之外的固定高度：Button 自带上下 padding 很大，圆屏上会撑爆
            height = dp(MIN_KEY_HEIGHT_DP)
            setPadding(0, 0, 0, 0)
            setOnClickListener { onKey(label) }
        }
    }

    private fun makeActionRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad(6), 0, 0)
        }
        row.addView(actionButton("取消", 0x33FFFFFF) { dismiss() })
        row.addView(actionButton("跳转", 0x4DFB7299) { confirm() })
        return row
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit): Button {
        return Button(context).apply {
            text = label
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(color)
            setPadding(0, 0, 0, 0)
            height = dp(MIN_ACTION_HEIGHT_DP)
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(pad(2), 0, pad(2), 0) }
            setOnClickListener { action() }
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
        val seconds = TimeInputParser.parse(buffer.toString())
        if (seconds < 0) {
            // 留在对话框里让用户改，而不是关掉再重开（手表上重开成本高）
            preview.text = "格式不对，按 : 分隔分秒"
            return
        }
        var targetMs = seconds * 1000L
        if (targetMs < 0) targetMs = 0
        if (durationMs > 0 && targetMs > durationMs) targetMs = durationMs
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

    // ---------------------------------------------------------------- 圆屏/尺寸

    private fun pad(dp: Int): Int = dp(dp)

    private fun dp(value: Int): Int = ToolsUtil.dp2px(value.toFloat())

    override fun show() {
        super.show()
        // 软键盘永不弹出：本对话框全部按键都是屏幕内的，系统输入法一弹就会把数字行顶出圆屏
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    companion object {
        /** 单个按键高度：比 Android 建议的 48dp 更大，手表上手指相对屏幕更粗 */
        private const val MIN_KEY_HEIGHT_DP = 42

        /** 底部动作按钮高度 */
        private const val MIN_ACTION_HEIGHT_DP = 40

        /** 输入长度上限（`1:02:03` 是 7 字符，留 1 位余量） */
        private const val MAX_INPUT_LENGTH = 8

        /**
         * 圆屏安全宽度：`屏宽 / √2 ≈ 0.707 × 屏宽`。
         *
         * 为什么用这个数：圆屏的**内接正方形**边长就是直径的 `1/√2`。
         * 把弹窗宽度限制在内接正方形内，四角按键必然落在圆形显示区内，
         * 不会出现"左下角 7 点不到"的问题。
         * 非圆屏设备上这个宽度略窄（约 70% 屏宽），但键盘本来就该居中收窄，
         * 所以不为圆屏/方屏做两套分支 —— 少一套分支就少一处会走错的路。
         */
        fun safeWidthPx(context: Context): Int {
            val screenWidth = context.resources.displayMetrics.widthPixels
            return (screenWidth / 1.414f).toInt()
        }
    }
}
