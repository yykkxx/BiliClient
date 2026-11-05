package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

import com.RobinNotBad.BiliClient.util.PageUsage

/**
 * 今日「各页面停留排行」的横向条形图（v5.2.0 需求 2 的「在哪个页面停留最久」）。
 *
 * ## 呈现取舍
 * 用户要看的是「哪一页最久、差多少」，所以：
 * - **横向条**（不是饼图）—— 页面名很长（「视频详情」「评论区」），横向排版才能直接把名字写在左边；
 * - 比例条长度 = 占今日总停留的百分比，不是绝对时长 —— 这样第 1 名和第 8 名的差距一眼可比；
 * - 每行右侧再写绝对时长，两者互补（长度看相对，文字看绝对）。
 *
 * ## 性能
 * 画笔一次性建好；`onDraw` 里不 new 对象、不拼字符串（时长文本在 [setItems] 时预算）。
 * 列表最多 8 条（[com.RobinNotBad.BiliClient.util.UsageStatsManager.MAX_PAGE_RANK]），
 * 所以直接全量绘制，不进 RecyclerView。
 */
class PageUsageBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var items: List<PageUsage> = emptyList()

    /** 每行的时长文本（预计算，onDraw 直接用） */
    private var valueTexts: Array<String> = emptyArray()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BAR_COLOR
    }

    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEXT
        textSize = sp2px(12f)
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEXT_STRONG
        textSize = sp2px(11f)
        textAlign = Paint.Align.RIGHT
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = TRACK_COLOR
    }

    private val rect = RectF()

    /** 名字列宽（按屏幕宽度取比例，避免长短名导致条形起点跳动） */
    private val nameColumnWidth: Float
        get() = width * NAME_COLUMN_RATIO

    /**
     * 设置排行数据（**调用方应已按时长降序**；本视图不再排序，避免每次 set 都排一遍）。
     */
    fun setItems(source: List<PageUsage>) {
        items = source
        valueTexts = Array(source.size) { index ->
            formatMs(source[index].ms) + " · " + percentText(source[index].percent)
        }
        // 高度随条目数变化：每行固定高，没有数据时给一行高度装空态
        val rows = if (items.isEmpty()) 1 else items.size
        layoutParams = layoutParams?.also { it.height = (rows * rowHeight() + dp2px(4f)).toInt() }
        requestLayout()
        invalidate()
    }

    private fun rowHeight(): Float = sp2px(ROW_HEIGHT_SP)

    private fun formatMs(ms: Long): String {
        val totalSeconds = if (ms > 0L) ms / 1000L else 0L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) "${hours}h${minutes}m" else "${minutes}m${seconds}s"
    }

    private fun percentText(percent: Float): String {
        val p = (percent * 100f)
        // 不足 1% 也给个最小可见值，避免一排全是 0%
        val shown = if (p < 1f && p > 0f) "<1" else String.format(java.util.Locale.US, "%.0f", p)
        return "$shown%"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rowH = rowHeight()
        if (items.isEmpty()) {
            canvas.drawText(EMPTY_TEXT_HINT, dp2px(2f), rowH * 0.6f, namePaint)
            return
        }

        val nameWidth = nameColumnWidth
        val valueWidth = sp2px(NAME_COLUMN_RATIO.let { 68f })
        val trackLeft = nameWidth
        val trackRight = width - valueWidth
        if (trackRight <= trackLeft) return

        for (index in items.indices) {
            val item = items[index]
            val centerY = rowH * index + rowH * 0.5f
            val textBase = centerY + namePaint.textSize / 3f

            // 页面名（左）
            canvas.drawText(item.name, dp2px(2f), textBase, namePaint)

            // 轨道 + 比例条
            val barTop = centerY - BAR_HEIGHT / 2f
            val barBottom = centerY + BAR_HEIGHT / 2f
            rect.set(trackLeft, barTop, trackRight, barBottom)
            canvas.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, trackPaint)

            val ratio = item.percent.coerceIn(0f, 1f)
            rect.set(trackLeft, barTop, trackLeft + (trackRight - trackLeft) * ratio, barBottom)
            canvas.drawRoundRect(rect, BAR_RADIUS, BAR_RADIUS, barPaint)

            // 时长 + 百分比（右）
            valueTexts.getOrNull(index)?.let { text ->
                canvas.drawText(text, width - dp2px(2f), textBase, valuePaint)
            }
        }
    }

    private fun dp2px(dp: Float): Float = dp * resources.displayMetrics.density

    private fun sp2px(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    private companion object {
        /** 每行高度（sp），够放 12sp 文字 */
        const val ROW_HEIGHT_SP = 26f

        /** 名字列宽占整体宽度的比例 */
        const val NAME_COLUMN_RATIO = 0.34f

        /** 比例条高度（dp） */
        const val BAR_HEIGHT = 10f
        const val BAR_RADIUS = 5f

        const val BAR_COLOR = 0xFF71A8FF.toInt()
        const val TRACK_COLOR = 0x22FFFFFF
        const val COLOR_TEXT = 0xFFC9CEDA.toInt()
        const val COLOR_TEXT_STRONG = 0xFFE6EAF2.toInt()

        /** 空态文案（不走 strings.xml，因为这个 View 不该依赖 Activity 的资源 ID 顺序） */
        const val EMPTY_TEXT_HINT = "今天还没有页面停留记录"
    }
}
