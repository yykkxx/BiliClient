package com.RobinNotBad.BiliClient.time

/**
 * 使用记录存储：日总时长历史 + 页面停留排行（规格 §5.3 核心规则 7 的数据源）。
 *
 * # 存什么、为什么这么存
 * 两个键：
 * - [TimePrefs.LOG_DAYS]：`"yyyy-MM-dd:ms;yyyy-MM-dd:ms"` —— 最近 [KEEP_DAYS] 天的每日总时长
 * - [TimePrefs.LOG_PAGES]：`"yyyy-MM-dd|页名:ms;页名:ms"` —— **只保留当天**的分页停留
 *
 * 为什么不用 JSON / Gson：这两份数据是「扁平的 日期/页名 → 一个整数」，用 JSON 属于过度设计；
 * 更重要的是 `check_new_code.sh` 硬禁新增 `org.json.` 行，而 Gson 会引入反射与
 * 一整个序列化模型类，对「几十条整数」不值得。分列文本**可读、可 diff、解析失败只退化**，
 * 最坏情况是图表空一格，不会因为一处格式错就让整段数据作废。
 *
 * # 今日总时长的单一真源
 * 「今天用了多久」**只有** [DailyPlan.usedMsToday] 一个真源（它管跨天重置与累加）。
 * 本对象 [todayTotalMs] 只做转发，**不另记一份** —— 历史上账号态就吃过
 * 「两个地方各判一次、结果对不上」的亏（plan.md 第二十七轮）。
 *
 * # 容错取向（重要）
 * 任何一条坏数据**只丢那一条**，绝不抛异常。统计是锦上添花的功能，
 * 不能因为它让用户进不去 App。坏条目的典型形态：没有分隔符、数值不是数字、负数、
 * 页名为空、日期不是 `yyyy-MM-dd`。
 *
 * # 线程
 * 全部由生命周期回调（主线程）驱动，数据量极小（最多 [KEEP_DAYS] 条 + 当天若干页），
 * 直接同步读写偏好即可；不引入协程/线程，避免「退后台时异步还没写完进程就被杀」。
 */
object UsageLog {

    /**
     * 日总数历史保留天数。
     *
     * 默认 **60**：规格书给的是 60 天。界面主视图只要 7 天和「全部」，
     * 「全部」的上限就是它 —— 60 天足够看出「最近两个月是不是越用越多」，
     * 又不会让偏好里那串文本无限增长（60 条 × 约 18 字符 ≈ 1.1 KB，可忽略）。
     */
    const val KEEP_DAYS = 60

    /** 最近 7 天图表的天数（含今天） */
    const val RECENT_DAYS = 7

    /** 页面排行默认取的条数 */
    const val DEFAULT_PAGE_LIMIT = 8

    /** 一天毫秒数（图表换算用） */
    const val MS_PER_MINUTE = 60_000L

    /** 条目分隔符（日总数与分页内部共用） */
    private const val ITEM_SEP = ";"

    /** 字段分隔符 */
    private const val FIELD_SEP = ":"

    /** 分页键里的「日期 | 数据」分隔符（用 `|` 而不是 `:`，因为页名里可能有冒号） */
    private const val DAY_SEP = "|"

    // ==========================================================================================
    // 写入：日总数
    // ==========================================================================================

    /**
     * 覆盖写入某一天的日总时长（分钟换算前的毫秒）。
     *
     * **覆盖**而不是累加：调用方（[DailyPlan.addUsage] / [DailyPlan.rotateIfDayChanged]）
     * 传进来的都是「那一整天的最终值」。累加语义会造成重复计数 —— 归档补写时
     * 同一个值会被加两遍。
     *
     * [ms] 允许为 0（清空某天）；负数忽略。写入后自动裁剪到最近 [KEEP_DAYS] 天。
     */
    @JvmStatic
    fun addDayTotal(date: String, ms: Long) {
        if (!isValidDate(date) || ms < 0L) return
        val days = parseDays(TimePrefs.getText(TimePrefs.LOG_DAYS, ""))
        days[date] = ms
        TimePrefs.putText(TimePrefs.LOG_DAYS, encodeDays(trimDays(days)))
    }

    /**
     * **累加**某一天的日总时长（在已有值上加，而不是覆盖）。
     *
     * 只有「跨午夜结算」需要它：那时手上只有「午夜前那一截」的时长，
     * 而 [TimePrefs.LOG_DAYS] 里存的是那一整天截至上次结算的总数。
     * 用覆盖语义（[addDayTotal]）会把昨天的 3 小时写成刚过去的 10 分钟 —— 真实的数据丢失。
     *
     * 正常累加路径（[DailyPlan.addUsage]）必须用覆盖语义，因为传进来的是整天的最终值；
     * 两者语义不同，所以拆成两个公开函数，**不要合并**。
     */
    @JvmStatic
    fun accumulateDayTotal(date: String, deltaMs: Long) {
        if (!isValidDate(date) || deltaMs <= 0L) return
        val days = parseDays(TimePrefs.getText(TimePrefs.LOG_DAYS, ""))
        val next = (days[date] ?: 0L) + deltaMs
        days[date] = next.coerceIn(0L, Long.MAX_VALUE)
        TimePrefs.putText(TimePrefs.LOG_DAYS, encodeDays(trimDays(days)))
    }

    /**
     * 裁剪到最近 [KEEP_DAYS] 天。
     *
     * 按**日期字符串排序**后取末尾 —— `yyyy-MM-dd` 定长，字典序恰好等于时间序，
     * 所以不需要解析成日期对象。取末尾而不是开头：要保留的是最近的。
     */
    private fun trimDays(days: LinkedHashMap<String, Long>): Map<String, Long> {
        if (days.size <= KEEP_DAYS) return days
        return days.entries
            .sortedBy { it.key }
            .takeLast(KEEP_DAYS)
            .associate { it.key to it.value }
    }

    // ==========================================================================================
    // 写入：页面停留
    // ==========================================================================================

    /**
     * 累加一页的停留毫秒。
     *
     * 跨天时**丢掉旧的分页数据**（规格：分页只保留当天）。不保留历史分页的理由：
     * 「最近 7 天页面排行」（[topPages]）用的也是过滤后的当天桶，
     * 保留旧桶只会让偏好无限增长；真要跨天统计留在服务端才有意义，本项目没有服务端。
     *
     * [deltaMs] <= 0 忽略；页名会被净化（见 [sanitizeName]）。
     */
    @JvmStatic
    fun addPageUsage(pageName: String, deltaMs: Long) {
        if (deltaMs <= 0L) return
        val name = sanitizeName(pageName)
        if (name.isEmpty()) return
        val today = TimePrefs.todayKey()
        // 「只保留当天」：读的时候日期不等于今天就当空，于是旧桶自然被这次写入替换掉
        val storedDay = storedPageDay()
        val pages = if (storedDay == today) parsePages(pageBody()) else LinkedHashMap()
        pages[name] = (pages[name] ?: 0L) + deltaMs
        TimePrefs.putText(TimePrefs.LOG_PAGES, today + DAY_SEP + encodePages(pages))
    }

    /**
     * 页名净化：把分隔符换成下划线。
     *
     * 不净化的话，一个含 `;` 或 `:` 的页名会把一条记录拆成两条（解析侧会当成坏数据丢掉，
     * 表现为「某个页面明明待了很久却不在排行里」）。
     */
    private fun sanitizeName(name: String): String =
        name.replace(DAY_SEP, "_").replace(ITEM_SEP, "_").replace(FIELD_SEP, "_").trim()

    // ==========================================================================================
    // 读取
    // ==========================================================================================

    /**
     * 今天的总毫秒。
     *
     * **转发** [DailyPlan.usedMsToday]，不另记一份（理由见类注释「单一真源」）。
     * 界面要「今天用了多久」一律走这里或 [DailyPlan.usedMsToday]，两者等价。
     */
    @JvmStatic
    fun todayTotalMs(): Long = DailyPlan.usedMsToday()

    /** 某天的总毫秒（等于今天时走实时值）；无记录返回 0 */
    @JvmStatic
    fun dayTotalMs(date: String): Long {
        if (date == TimePrefs.todayKey()) return todayTotalMs()
        return parseDays(TimePrefs.getText(TimePrefs.LOG_DAYS, ""))[date] ?: 0L
    }

    /**
     * 最近 7 天（**含今天**）的 `日期 → 毫秒`，按下标升序：`list[0]` 是 6 天前，`list[6]` 是今天。
     *
     * 今天这一格用 [todayTotalMs] 覆盖历史里的旧值 —— 历史是在结算时才写的，
     * 用户正开着图表页时历史里今天还停在「上次结算」的值，直接用会让最后一根柱子明显偏矮。
     *
     * 没有数据的日期也会返回一条 `日期 to 0`（柱状图需要连续的 7 根柱子，缺一根会挤在一起）。
     */
    @JvmStatic
    fun last7Days(): List<Pair<String, Long>> {
        val history = parseDays(TimePrefs.getText(TimePrefs.LOG_DAYS, ""))
        val today = TimePrefs.todayKey()
        val result = ArrayList<Pair<String, Long>>(RECENT_DAYS)
        // 从 6 天前走到今天（含今天），index 0 = 最早
        for (offset in RECENT_DAYS - 1 downTo 0) {
            val date = TimePrefs.offsetDateKey(-offset)
            val ms = if (date == today) todayTotalMs() else (history[date] ?: 0L)
            result.add(date to ms)
        }
        return result
    }

    /**
     * 全部有记录的日期（`日期 → 毫秒`，按日期升序）。
     *
     * 「全部」的边界就是 [KEEP_DAYS]：更早的数据已经被裁剪掉了，
     * 所以这里不需要额外的时间窗口过滤，直接把存储里的读出来即可。
     */
    @JvmStatic
    fun allDays(): List<Pair<String, Long>> {
        val history = parseDays(TimePrefs.getText(TimePrefs.LOG_DAYS, ""))
        val today = TimePrefs.todayKey()
        // 今天若在历史里，用实时值覆盖（同 last7Days 的理由）
        val merged = LinkedHashMap(history)
        if (merged.containsKey(today) || todayTotalMs() > 0L) merged[today] = todayTotalMs()
        return merged.entries
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

    /**
     * 今天的页面停留排行：`页名 → 毫秒`，**按毫秒降序**。
     *
     * 分页数据日期不等于今天时返回空 —— 与「只保留当天」的写入策略一致。
     */
    @JvmStatic
    fun todayPages(): List<Pair<String, Long>> {
        if (storedPageDay() != TimePrefs.todayKey()) return emptyList()
        val pages = parsePages(pageBody())
        return pages.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    /**
     * 最近 [days] 天的页面停留排行（`页名 → 毫秒`，降序，最多 [limit] 条）。
     *
     * **已知限制（诚实说明）**：分页数据只保留当天，所以 `days` 参数目前只影响
     * 「桶的时间窗」这一个语义 —— 实际上只有当天有分页数据，`days >= 1` 都返回当天。
     * 参数保留是因为规格书 §5.5 的签名里有它，UI 代理按契约传值即可；
     * 若将来改成保留多天分页，本函数的语义不需要改，只换内部数据源。
     * 这一点已在交付报告里单列，请 UI 代理注意：想显示「7 天页面排行」时
     * 拿到的是当天数据，文案别写成「最近 7 天」。
     *
     * [limit] <= 0 时用 [DEFAULT_PAGE_LIMIT]。
     */
    @JvmStatic
    fun topPages(days: Int, limit: Int): List<Pair<String, Long>> {
        val effectiveLimit = if (limit <= 0) DEFAULT_PAGE_LIMIT else limit
        // days <= 0 没有任何意义（不是「不限制」），按「只有今天」处理
        val window = days.coerceAtLeast(1)
        val today = TimePrefs.todayKey()
        val result = LinkedHashMap<String, Long>()
        // 窗口内的日期里，只有今天可能有分页数据；把扫描写出来是为了将来多天存储时不用改结构
        for (offset in (window - 1) downTo 0) {
            val date = TimePrefs.offsetDateKey(-offset)
            if (date != today) continue
            for ((name, ms) in parsePages(pageBody())) {
                result[name] = (result[name] ?: 0L) + ms
            }
        }
        return result.entries
            .sortedByDescending { it.value }
            .take(effectiveLimit)
            .map { it.key to it.value }
    }

    // ==========================================================================================
    // 页面停留的「计时控制」转发（§5.5 契约把这些挂在 UsageLog 上）
    //
    // §5.5 的 UsageLog 契约里列了 setCurrentPage / pauseCurrent / resumeCurrent / flush，
    // 而真正的实现必须在 UsageTracker（它才是持有内存状态的对象）。
    // 这里做**纯转发**，不复制任何状态：这样 UI 代理按 §5.5 写在 UsageLog 上、
    // 按任务书写在 UsageTracker 上，两种写法都能编译且行为完全一致。
    // ==========================================================================================

    /**
     * 记录当前页面名（`BaseActivity.setPageName` 调）。转发 [UsageTracker.setCurrentPage]。
     */
    @JvmStatic
    fun setCurrentPage(name: String?) = UsageTracker.setCurrentPage(name)

    /**
     * 进入「不计入使用时长」的页面（倒计时 / 拦截 / 日子询问页）。转发 [UsageTracker.pause]。
     *
     * **必须与 [resumeCurrent] 成对调用**（onResume / onPause）。
     */
    @JvmStatic
    fun pauseCurrent() = UsageTracker.pause()

    /** 离开「不计入使用时长」的页面。转发 [UsageTracker.resume]。 */
    @JvmStatic
    fun resumeCurrent() = UsageTracker.resume()

    /** 结算到此刻（退后台前 / 想立刻落盘时）。转发 [UsageTracker.flush]，幂等。 */
    @JvmStatic
    fun flush() = UsageTracker.flush()

    // ==========================================================================================
    // 清空
    // ==========================================================================================

    /**
     * 清空使用记录（设置页「清空使用记录」）：历史 + 分页 + 内存里尚未结算的停留。
     *
     * **刻意不动今日累计**（`USED_MS`）：它是配额判定的输入，不是「记录」。
     * 若把它一起清零，用户就能靠「清空记录」白嫖掉今天的配额 ——
     * 那等于给了一个绕开限制的按钮。想重置今日用量走 [DailyPlan.resetTodayUsage]
     * （明确标注为「重置今天」的入口，界面上该带二次确认）。
     *
     * 清内存态是必须的：否则当前会话攒着的那一截停留会在下次结算时又写回来，
     * 用户会觉得「清不干净」。
     */
    @JvmStatic
    fun clearAll() {
        TimePrefs.remove(TimePrefs.LOG_DAYS)
        TimePrefs.remove(TimePrefs.LOG_PAGES)
        UsageTracker.reset()
    }

    // ==========================================================================================
    // 解析 / 序列化（纯函数；解析失败一律降级，不抛异常）
    // ==========================================================================================

    /** 当天分页串里的「日期」部分；格式不对返回空串（= 视为无数据） */
    private fun storedPageDay(): String {
        val raw = pageBody()
        val index = raw.indexOf(DAY_SEP)
        return if (index <= 0) "" else raw.substring(0, index)
    }

    /** 分页原始串（含日期前缀） */
    private fun pageBody(): String = TimePrefs.getText(TimePrefs.LOG_PAGES, "")

    /**
     * 解析 `yyyy-MM-dd:ms;...` → 有序 Map。
     *
     * 坏一条丢一条：`[parseDayEntry]` 返回 null 就 continue，不影响其它条目。
     * 同日出现多条时**后者覆盖前者**（写入侧是覆盖语义，重复出现说明是脏数据）。
     */
    private fun parseDays(raw: String): LinkedHashMap<String, Long> {
        val result = LinkedHashMap<String, Long>()
        if (raw.isEmpty()) return result
        for (entry in raw.split(ITEM_SEP)) {
            val parsed = parseDayEntry(entry) ?: continue
            result[parsed.first] = parsed.second
        }
        return result
    }

    /**
     * 解析单条 `yyyy-MM-dd:ms`。
     *
     * 用 [lastIndexOf] 找分隔符：日期里没有冒号，但万一页名/脏数据带冒号，
     * 从右往左找能保住左边那段完整。返回 null 的四个条件都在下面一行一条写明。
     */
    private fun parseDayEntry(entry: String): Pair<String, Long>? {
        val index = entry.lastIndexOf(FIELD_SEP)
        if (index <= 0 || index >= entry.length - 1) return null
        val date = entry.substring(0, index).trim()
        if (!isValidDate(date)) return null
        val value = entry.substring(index + 1).trim().toLongOrNull() ?: return null
        if (value < 0L) return null
        return date to value
    }

    /** 解析 `页名:ms;页名:ms` → 有序 Map（同名累加；坏一条丢一条） */
    private fun parsePages(body: String): LinkedHashMap<String, Long> {
        val result = LinkedHashMap<String, Long>()
        if (body.isEmpty()) return result
        for (entry in body.split(ITEM_SEP)) {
            val parsed = parsePageEntry(entry) ?: continue
            result[parsed.first] = (result[parsed.first] ?: 0L) + parsed.second
        }
        return result
    }

    /**
     * 解析单条 `页名:ms`。
     *
     * 用 [lastIndexOf] 找冒号：页名本身可能含冒号（净化只处理写入侧，
     * 已存在的脏数据仍需读得回来）。数值必须为正（0 停留的页面没有排行意义）。
     */
    private fun parsePageEntry(entry: String): Pair<String, Long>? {
        val index = entry.lastIndexOf(FIELD_SEP)
        if (index <= 0 || index >= entry.length - 1) return null
        val name = entry.substring(0, index).trim()
        if (name.isEmpty()) return null
        val value = entry.substring(index + 1).trim().toLongOrNull() ?: return null
        if (value <= 0L) return null
        return name to value
    }

    /** Map → `date:ms;...`（按日期升序，稳定可 diff） */
    private fun encodeDays(days: Map<String, Long>): String =
        days.entries.sortedBy { it.key }.joinToString(ITEM_SEP) { it.key + FIELD_SEP + it.value }

    /** Map → `页名:ms;...`（按当前 Map 顺序，即写入/解析顺序） */
    private fun encodePages(pages: Map<String, Long>): String =
        pages.entries.joinToString(ITEM_SEP) { it.key + FIELD_SEP + it.value }

    /**
     * 严格校验 `yyyy-MM-dd` 的**形状**（长度、分隔符位置、数字）。
     *
     * 只校验形状不校验日历合法性（不判 2 月 31 日）：这里只需要「能当 Map 键且字典序等于时间序」，
     * 形状对了就满足；为了判日历合法性去 parse 成日期对象反而会引入异常路径。
     */
    private fun isValidDate(text: String): Boolean {
        if (text.length != 10 || text[4] != '-' || text[7] != '-') return false
        for (index in 0..9) {
            if (index == 4 || index == 7) continue
            if (!text[index].isDigit()) return false
        }
        return true
    }
}
