package com.RobinNotBad.BiliClient.time

/**
 * 时间银行：把**未来若干天的时间借给今天用**（规格 §5.3 核心规则 6）。
 *
 * # 朴素模型（用户口径，刻意不复杂化）
 * - 只能借**未来**（[futureDates]，默认未来 7 天，**不含今天**）；
 * - **不能还**、**没有利息**、**没有复利**：借了就是那天少了，那天过完自动消化；
 * - 任意时刻「总借入」不超过 [TimePrefs.bankTotalLimitMin]（默认 300 分钟 = 5 小时）；
 * - 借了那天，那天的可用配额相应减少（[capacityMinutes]，**可以为负** → 那天直接不放行）；
 * - 总开关只有在「当前没有任何借入」时才允许关闭（[setEnabled]）。
 *
 * # 存储格式
 * 唯一一个键 [TimePrefs.BANK_BORROWS]：`"yyyy-MM-dd:min,yyyy-MM-dd:min"`
 * ```
 * 2026-11-07:30,2026-11-08:60
 * ```
 * 日期 = **被借走**的那天，数值 = 从那天借走的分钟数。
 * 日期定长 `yyyy-MM-dd`，字典序恰好等于时间序，所以「哪条已过去」直接比字符串即可。
 *
 * # 容错（硬要求）
 * 任何一段解析不出来就**跳过那一段**，整段坏掉就当「没有借入」，**绝不抛异常**。
 * 脏数据最多让用户多玩几分钟；反过来抛异常会把 App 挡在启动门外，代价完全不对等。
 *
 * # 「总借入」到底算什么（本实现在这里与规格书的字面表述不同，理由如下）
 * 规格 §5.3 说「任意时刻内，总借的时长不超过 5 小时」。字面两种读法：
 * - **全部历史记录**：借过就永远算。后果是累积到 5 小时后这个功能**永久失效**，
 *   而用户口径里没有「永久」，只有「不能还」。**不采用**。
 * - **尚未消化的（日期 >= 今天）**：那天过完，那笔额度已经在现实里被消化掉了
 *   —— 用户那天**真的少玩了**，债已经用「少玩」还清了。「不能还」约束的是
 *   「不能主动把这笔扣减撤销」，不是「永远记着」。**采用这个**。
 *
 * 于是 [borrowedTotalMinutes] 只累加 `date >= 今天` 的记录。
 * 边界细节：**今天的记录算在内**（那笔时间是今天正在生效的额度，当然占着上限；
 * 且它到明天零点自然消化，上限随之解封）。
 *
 * 这样一来「上限」永远不会把功能锁死，同时任意时刻的敞口上限严格 ≤ 5 小时。
 *
 * # 与 [DailyPlan] 的分工（方向相反，混用必错）
 * - **被借的那天变少**：这是 [capacityMinutes] 的职责，减的是 `borrowedFor(那天)`。
 * - **今天变多**：这是 [DailyPlan.quotaMinutesToday] 的职责，加的是
 *   [borrowedTotalMinutes]（未消化的借入总量；格式所限的近似，见那里的注释）。
 *
 * 规格 §5.5 把 `quotaMinutesToday` 写成「基准 − 今天被借走」，但今天永远不是借出方
 * （只能从未来借），那个减法恒等于基准、借时间毫无作用。已在 [DailyPlan] 里改成加法
 * 并在交付报告中单列。
 */
object TimeBank {

    // ==========================================================================================
    // 开关
    // ==========================================================================================

    /** 时间银行总开关（转发 [TimePrefs.isBankEnabled]，让 UI 只依赖本对象） */
    @JvmStatic
    fun isEnabled(): Boolean = TimePrefs.isBankEnabled()

    /**
     * 开关时间银行。
     *
     * **关闭时若有任何未消化的借入 → 返回 false 拒绝关闭**（规格 §5.3 规则 6：
     * 用户口径「只有在当前没有借的时候才可以关闭」）。理由：借入是**已生效的扣减**，
     * 关掉银行不会把它撤销（不能还），却会让用户以为「关了就不欠了」。
     * 拒绝关闭比「偷偷留着一笔看不见的扣减」诚实得多。
     *
     * 打开方向永远成功（没有前置条件）。
     *
     * @return true = 状态已按请求改变；false = 被拒绝（只有「关闭且有借入」这一种）
     */
    @JvmStatic
    fun setEnabled(on: Boolean): Boolean {
        if (!on && borrowedTotalMinutes() > 0) return false
        TimePrefs.setBankEnabled(on)
        return true
    }

    /**
     * 拒绝关闭时可显示的中文理由（按钮点了没反应很难受，必须告诉用户为什么）。
     *
     * 放在逻辑层而不是 UI 层：这句话依赖「还欠多少」这个数据，
     * UI 自己拼会重复计算一次 `borrowedTotalMinutes`，容易与判定不一致。
     */
    @JvmStatic
    fun disableBlockedReason(): String {
        val outstanding = borrowedTotalMinutes()
        if (outstanding <= 0) return ""
        return "还有 $outstanding 分钟没消化完，消化完才能关闭时间银行"
    }

    // ==========================================================================================
    // 查询
    // ==========================================================================================

    /**
     * 「总借入」分钟数 = `date >= 今天` 的全部记录之和。
     *
     * 这是 [TimePrefs.bankTotalLimitMin] 的比较对象。口径与边界细节见类注释
     * 「总借入到底算什么」。
     */
    @JvmStatic
    fun borrowedTotalMinutes(): Int {
        val today = TimePrefs.todayKey()
        val total = parseBorrows(TimePrefs.getText(TimePrefs.BANK_BORROWS, ""))
            .entries
            .filter { it.key >= today }
            .sumOf { it.value.toLong() }
        return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** 某一天被借走了多少分钟；无记录或日期形状不合法返回 0 */
    @JvmStatic
    fun borrowedFor(date: String): Int {
        if (!isDateShape(date)) return 0
        return parseBorrows(TimePrefs.getText(TimePrefs.BANK_BORROWS, ""))[date] ?: 0
    }

    /** 今天被借进来多少分钟（= [borrowedFor] 今天；界面「今天已借总量环」用它） */
    @JvmStatic
    fun borrowedInTodayMinutes(): Int = borrowedFor(TimePrefs.todayKey())

    /**
     * 某一天的**可用配额**（分钟）= 该日基准配额 − 该日被借走量。
     *
     * **可以为负**（规格明确要求「可为负 → 那天直接不放行」）。这里**不做
     * `coerceAtLeast(0)`** —— 夹到 0 会让「这天为什么一打开就被拦」这条信息消失，
     * 也让「欠得比配额还多」这种状态无法在界面上解释。
     *
     * 基准配额怎么取（**已知近似**）：今天走 [DailyPlan.baseQuotaMinutesToday]
     * （今天是否休息日已经知道）；未来某天的日子类型要当天才会问，只能按今天推断。
     * 规格没要求预测未来，而提前问「下周三是不是上学日」显然更荒谬。
     * 今天若是休息日（[DailyPlan.QUOTA_UNLIMITED]），未来也按无限制算 ——
     * 此时借入不会让那天变负（`Int.MAX_VALUE − 300` 仍是极大值），与「无限制」语义自洽。
     */
    @JvmStatic
    fun capacityMinutes(date: String): Int {
        if (!isDateShape(date)) return 0
        val base = baseQuotaMinutesFor(date).toLong()
        val borrowed = borrowedFor(date).toLong()
        return (base - borrowed).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 那天**还能再借出**多少分钟（给「每根柱子上可借额度」用）。
     *
     * 取两个上限的**最小值**：
     * 1. 那天自己的剩余配额（不能借成负配额）；
     * 2. 今天总借入上限的余额（不能承诺一个必定失败的数）。
     *
     * **对规格 §5.5 的注释有一处修正**：规格写
     * `availableFor(date) = 额度 - 已借 - 那天已被借走`，按字面会把「那天已被借走」
     * 减两次（它本来就在「已借」里），得到的数会偏小、且与 [canBorrowFrom] 的校验结果
     * 不一致（界面显示可借 0、实际能借 30）。所以这里按上面两条真实的约束取小。
     */
    @JvmStatic
    fun availableFor(date: String): Int {
        if (!isDateShape(date)) return 0
        val dayLeft = capacityMinutes(date).toLong()
        val totalLeft = remainingBorrowCapacityMin().toLong()
        return dayLeft.coerceAtMost(totalLeft).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 今天还剩多少「借入额度」= 上限 − 总借入，不小于 0。
     *
     * 单独暴露是因为界面要画「今天已借总量环」，需要「已借 / 上限」两个数；
     * 用 [availableFor] 反推（上限 − available）在休息日会被无限制值污染。
     */
    @JvmStatic
    fun remainingBorrowCapacityMin(): Int =
        (TimePrefs.bankTotalLimitMin().toLong() - borrowedTotalMinutes().coerceAtLeast(0).toLong())
            .coerceAtLeast(0L)
            .toInt()

    /**
     * 未来可借的日期列表（`yyyy-MM-dd`，升序，**不含今天**）。
     *
     * 天数取 [TimePrefs.bankMaxDays]（默认 7）。规格写「向将来的 7 天内借」，
     * 语义是**今天之后**的 7 天（明天 … 第 7 天）。含今天没有意义：
     * 从今天借给今天等于什么都没做，却会白占一份「总借入」额度。
     */
    @JvmStatic
    fun futureDates(): List<String> {
        val days = TimePrefs.bankMaxDays()
        val result = ArrayList<String>(days)
        for (offset in 1..days) result.add(TimePrefs.offsetDateKey(offset))
        return result
    }

    // ==========================================================================================
    // 借入
    // ==========================================================================================

    /**
     * 检查「能不能借 [minutes] 分钟」，不写入。目标日期默认取**第一个**可借日。
     *
     * 规格 §5.5 的签名就是这个（只有分钟数）。但界面在**每根柱子上**都要判断
     * 「这天能不能借、为什么不能」，只有分钟数没日期根本回答不了，所以另给
     * [canBorrowFrom]；本函数保留是为了契约兼容（内部转调，语义一致）。
     */
    @JvmStatic
    fun canBorrow(minutes: Int): Pair<Boolean, String> =
        canBorrowFrom(futureDates().firstOrNull() ?: "", minutes)

    /**
     * 带目标日期的可行性检查。返回 `Pair(是否可行, 可读理由)` —— 理由文案给 UI 直接显示。
     *
     * 校验顺序：先便宜后贵、先最根本后细节。
     * 1. 分钟数 > 0；
     * 2. 日期形状合法；
     * 3. 银行开关是开的；
     * 4. 目标日期在可借窗口内；
     * 5. 总借入不超上限；
     * 6. 那天自己还够借。
     *
     * 第 6 条容易被漏掉：若允许「从那天借走的量 > 那天配额」，那天就成了负配额日
     * ——虽然 [capacityMinutes] 允许负值，但那是给**脏数据**留的容错，
     * 不该由正常操作制造出来。
     */
    @JvmStatic
    fun canBorrowFrom(targetDate: String, minutes: Int): Pair<Boolean, String> {
        if (minutes <= 0) return false to "请选择要借的分钟数"
        if (!isDateShape(targetDate)) return false to "日期格式不对"
        if (!isEnabled()) return false to "时间银行未开启"
        if (!futureDates().contains(targetDate)) {
            return false to "只能向未来 ${TimePrefs.bankMaxDays()} 天内借"
        }
        val limit = TimePrefs.bankTotalLimitMin()
        val used = borrowedTotalMinutes()
        if (used.toLong() + minutes.toLong() > limit.toLong()) {
            val left = (limit - used).coerceAtLeast(0)
            return false to "最多还能借 $left 分钟（总上限 $limit 分钟）"
        }
        val dayLeft = remainingForDay(targetDate)
        if (minutes > dayLeft) return false to "那天最多还能借 $dayLeft 分钟"
        return true to "可以借 $minutes 分钟"
    }

    /**
     * 从 [targetDate] 借 [minutes] 分钟给今天；成功写回并返回 `Pair(true, 成功文案)`。
     *
     * 先校验再写（[canBorrowFrom]），**任何失败都不落盘** —— 避免「写进去了才发现超限」
     * 这种需要回滚的路径（回滚本身又是一处可能出错的代码）。
     *
     * 返回的理由与 [canBorrowFrom] 完全一致，UI 不需要再调一次校验。
     */
    @JvmStatic
    fun borrow(targetDate: String, minutes: Int): Pair<Boolean, String> {
        val check = canBorrowFrom(targetDate, minutes)
        if (!check.first) return check
        val borrows = parseBorrows(TimePrefs.getText(TimePrefs.BANK_BORROWS, ""))
        val next = (borrows[targetDate] ?: 0).toLong() + minutes.toLong()
        borrows[targetDate] = next.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        TimePrefs.putText(TimePrefs.BANK_BORROWS, encodeBorrows(borrows))
        return true to "已从 $targetDate 借入 $minutes 分钟"
    }

    /**
     * 清除全部借入记录。
     *
     * **刻意不暴露给普通界面**：规格明确「不能还」。它只给「重置时间银行」这类
     * 明确带二次确认的调试/重置入口用；日常界面不该出现这个操作。
     */
    @JvmStatic
    fun resetBorrows() = TimePrefs.putText(TimePrefs.BANK_BORROWS, "")

    // ==========================================================================================
    // 内部
    // ==========================================================================================

    /**
     * 某一天的基准配额（分钟，不含借出）。
     *
     * 今天：跟着 [DailyPlan] 走（可能已判定为休息日 → 无限制）。
     * 未来：按今天的类型推断（见 [capacityMinutes] 的「已知近似」）。
     */
    private fun baseQuotaMinutesFor(date: String): Int =
        if (date == TimePrefs.todayKey()) {
            DailyPlan.baseQuotaMinutesToday()
        } else if (DailyPlan.isRestDay()) {
            DailyPlan.QUOTA_UNLIMITED
        } else {
            TimePrefs.schoolQuotaMin()
        }

    /**
     * 那天自身的剩余可借量（只看那一天，不看总上限）。
     *
     * 与 [availableFor] 的区别：[availableFor] 还要与「今天总上限余额」取小；
     * 这里只回答「这一天自己的配额还剩多少」，用于「不能借成负配额」的校验。
     */
    private fun remainingForDay(date: String): Int {
        val base = baseQuotaMinutesFor(date).toLong()
        val borrowed = borrowedFor(date).toLong()
        return (base - borrowed).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    // ==========================================================================================
    // 解析 / 序列化（纯函数；解析失败一律降级，不抛异常）
    // ==========================================================================================

    /**
     * 解析 `date:min,date:min,...` → 有序 Map（按日期升序）。
     *
     * 坏一条丢一条（[parseBorrowEntry] 返回 null 就 continue）。同日多条**累加合并**
     * （防御历史脏数据；写入侧永远写的是合并过的值）。
     *
     * **不过滤已过去的日期**：过滤发生在 [borrowedTotalMinutes] 这类聚合里，
     * 而不是解析里 —— 解析保持「看到的都读出来」，这样 [borrowedFor] 对任何日期都准确。
     */
    private fun parseBorrows(raw: String): LinkedHashMap<String, Int> {
        val result = LinkedHashMap<String, Int>()
        if (raw.isEmpty()) return result
        for (item in raw.split(',')) {
            val parsed = parseBorrowEntry(item) ?: continue
            val merged = (result[parsed.first] ?: 0).toLong() + parsed.second.toLong()
            result[parsed.first] = merged.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        return LinkedHashMap(result.entries.sortedBy { it.key }.associate { it.key to it.value })
    }

    /**
     * 解析单条 `date:min`；返回 null 的四种情况（每条对应一类脏数据）：
     * - 空段（尾部多余逗号、连续逗号）；
     * - 没有冒号 / 冒号在首或尾（只有日期或只有数字）；
     * - 日期不是 `yyyy-MM-dd` 形状；
     * - 分钟数不是正整数。
     */
    private fun parseBorrowEntry(item: String): Pair<String, Int>? {
        val entry = item.trim()
        if (entry.isEmpty()) return null
        val colon = entry.indexOf(':')
        if (colon <= 0 || colon >= entry.length - 1) return null
        val date = entry.substring(0, colon).trim()
        if (!isDateShape(date)) return null
        val minutes = entry.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (minutes <= 0) return null
        return date to minutes
    }

    /** Map → `date:min,date:min`（按日期升序，稳定可 diff） */
    private fun encodeBorrows(borrows: Map<String, Int>): String =
        borrows.entries.sortedBy { it.key }.joinToString(",") { it.key + ":" + it.value }

    /**
     * 只校验 `yyyy-MM-dd` 的**形状**（长度 10、第 4/7 位是 `-`、其余是数字）。
     *
     * 不校验日历合法性（不判 2 月 31 日）：这里只需要「能当 Map 键且字典序等于时间序」。
     * 为此去 parse 成 `LocalDate` 会引入异常路径，而日历非法值的后果只是
     * 「那天永远不会来」，不会造成任何误判。
     */
    private fun isDateShape(text: String): Boolean {
        if (text.length != 10 || text[4] != '-' || text[7] != '-') return false
        for (index in 0..9) {
            if (index == 4 || index == 7) continue
            if (!text[index].isDigit()) return false
        }
        return true
    }
}
