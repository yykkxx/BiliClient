package com.RobinNotBad.BiliClient.util

/**
 * 播放器「时间跳转」输入解析（从 PlayerActivity#parseTimeInput 抽出）。
 *
 * 支持两类输入，总秒数统一返回 Int（非法输入返回 -1）：
 *
 * 1. **纯数字**（`^\d+$`）—— 右对齐 MMSS：末两位是秒，前面是分（可为空 = 0），
 *    总秒数 = 分 × 60 + 秒，因此秒数超 60 会自然进位到分：
 *      - `28`   → 28 秒          （1~2 位 = 纯秒）
 *      - `9`    → 9 秒
 *      - `308`  → 3 分 08 秒 = 188
 *      - `4325` → 43 分 25 秒 = 2605
 *      - `2890` → 28 分 90 秒 = 29 分 30 秒 = 1770
 *      - `10530`→ 105 分 30 秒 = 6330（分可以 >99，小时不必做）
 *
 * 2. **带分隔符** —— 行为与既有实现逐字一致：分隔符为空白 / 中文冒号 / 英文冒号
 *    （可混用），从前往后按 60 进位累加（`5:30` / `5 30` / `5：30` 均 = 330，
 *    `1:02:03` = 3723）。
 *
 * 非法输入（空串、无数字、段数 >3、段含负数或非数字）一律返回 -1，
 * 调用点据此走原有失败路径。超 Int 范围的极端输入饱和到 [Int.MAX_VALUE]，
 * 调用点随后会按时长截断，与旧实现的「跳到片尾」表现一致。
 */
object TimeInputParser {

    private val DIGITS_ONLY = Regex("^\\d+$")

    private val SEPARATOR = Regex("[\\s:：]+")

    @JvmStatic
    fun parse(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return -1

        // 纯数字：右对齐 MMSS —— 末两位 = 秒（1 位时即自身），前面 = 分（可为空 = 0）
        if (DIGITS_ONLY.matches(text)) {
            val secLen = if (text.length >= 2) 2 else 1
            val sec = text.substring(text.length - secLen).toLong()
            val min = if (text.length > secLen) text.substring(0, text.length - secLen).toLong() else 0L
            return saturate(min * 60 + sec)
        }

        // 既有分隔符逻辑（原样保留，勿改语义）
        val parts = text.split(SEPARATOR).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 3) return -1

        var total = 0L
        for (part in parts) {
            val value = part.trim().toLongOrNull() ?: return -1
            if (value < 0) return -1
            total = total * 60 + value
        }
        return saturate(total)
    }

    private fun saturate(total: Long): Int =
        if (total > Int.MAX_VALUE) Int.MAX_VALUE else total.toInt()
}
