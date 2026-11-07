package com.RobinNotBad.BiliClient.time

/**
 * 启动门禁判定：打开 App 时该放行 / 问日子 / 等倒计时 / 拦截（规格 §5.3 核心规则 2/4/5）。
 *
 * # 判定顺序（顺序本身就是需求，改动前请读完理由）
 * 1. **总开关关** → [Decision.PASS]
 * 2. **今天被 bypass**（长按 45 秒解除）→ [Decision.PASS]
 * 3. **还没选今天是什么日子** → [Decision.ASK_DAY_TYPE]
 * 4. **今天是休息日** → [Decision.PASS]（不拦、也不倒计时等待）
 * 5. **配额已耗尽** → [Decision.BLOCK]
 * 6. **21:30 之后** → [Decision.PASS]
 * 7. **需要等待** → [Decision.WAIT]
 * 8. 否则 → [Decision.PASS]
 *
 * ## 为什么第 4 步「休息日直接放行」必须显式写出来（v5.3.0 修的 bug）
 * 有人会想：休息日配额是 `Int.MAX_VALUE`，第 5 步的 BLOCK 本来就落不到它头上，
 * 于是「休息日不用管」——**这个推理漏掉了第 7 步**。
 * [waitSeconds] 是按 `levelOf(usedMsToday())`（绿 0 / 黄 5 / 红 10）算的，
 * 而**颜色分档与配额无关**：休息日用了 70 分钟照样是红档 → 返回 10 秒 →
 * 第 7 步返回 WAIT → 用户明明是「休息日、随便玩」，进 App 却还要盯 10 秒倒计时。
 * 这就是用户报的「休息日进入应用无需倒计时」。
 *
 * 所以休息日必须在**配额判定之后、等待判定之前**显式 PASS：
 * 它既不该被拦（配额无限），也不该被等（无配额压力，等待在这里没有任何意义）。
 * 同时 [waitSeconds] 是**独立入口**（倒计时页会单独调它），那边也要返回 0，
 * 否则「判定说 PASS、页面却拿着 10 秒自己等」这种自相矛盾的组合迟早出现。
 *
 * ## 为什么 ASK_DAY_TYPE 必须排在 BLOCK 之前（最容易搞反的一步）
 * 配额的值**取决于**日子类型：上学日 120 分钟、休息日无限制。
 * 若先判 BLOCK 再问日子，此时 [DailyPlan.dayType] 还是空，配额只能按「上学日」兜底
 * ——于是**休息日也会被按 120 分钟拦下来**，用户看到「配额耗尽」页却根本没选过上学日，
 * 连「今天是休息日」这个选项都还没出现，等于**按错误配额拦人**，而且他无法自救
 * （拦截页里没有「改日子类型」的出路）。
 *
 * 先问再判就没有这个问题：问完之后 [DailyPlan.dayType] 已是用户选的类型，
 * 紧接着的 BLOCK 判定用的是**正确**配额；休息日配额是 `Int.MAX_VALUE`，
 * 自然不会被判耗尽。但**「不被拦」不等于「能直接进」**——它还会撞上第 7 步的等待，
 * 所以第 4 步必须显式 PASS（见上一节，那是 v5.3.0 补的）。
 *
 * 这条是 v5.2.0 用血换来的结论（plan.md 第二十七轮「关键设计决策 1」：
 * 「每日询问必须排在配额判定之前，且放在启动页……先判定再问会按错误配额拦人」）。
 *
 * ## 为什么 21:30 之后是 PASS 而不是跳过等待
 * 两步等价（[waitSeconds] 本来就会算 0 秒），但**写成显式 PASS 更能表达意图**：
 * 夜里连「等待 0 秒」的页面都不该闪一下。同时它必须排在 BLOCK **之后**
 * —— 21:30 之后配额用完了**照样要拦**（规格 §5.3 规则 4/5：只有「等待」不看时段，
 * 「拦截」与时段无关）。夜里让人继续玩到天亮才是真正的问题。
 *
 * ## 为什么 bypass 排在第 2 位（连日子都不问）
 * 「长按 45 秒解除今天限制」的语义是**今天整个不管了**。如果还去问日子类型，
 * 用户刚被拦过一次、又被问一遍「今天上学吗」，体验荒谬；而且他大概率会随便点一个，
 * 让「明天重新问」时的历史数据变脏。放行就是放行。
 *
 * # 状态
 * 无状态：每次 [decide] 都从偏好现算。这样「页面里改了设置、返回时重新判定」
 * 天然生效，不需要任何失效通知机制。
 */
object Gate {

    /**
     * 门禁结论。
     *
     * - [PASS]：直接进主界面
     * - [ASK_DAY_TYPE]：先问上学日/休息日（[DayTypePromptActivity]）
     * - [WAIT]：进倒计时等待页（[CountdownGateActivity]），秒数见 [waitSeconds]
     * - [BLOCK]：进配额耗尽拦截页（[QuotaBlockActivity]）
     */
    enum class Decision {
        /** 放行 */
        PASS,

        /** 需要先问「今天上学日还是休息日」 */
        ASK_DAY_TYPE,

        /** 需要倒计时等待 */
        WAIT,

        /** 配额耗尽，拦截 */
        BLOCK
    }

    /**
     * 判定当前该做什么。顺序见类注释，**不要重排**。
     *
     * 1. 总开关关 → PASS
     * 2. 今天已 bypass → PASS
     * 3. 今天还没选日子 → ASK_DAY_TYPE
     * 4. 今天是休息日 → PASS（不拦、不等；理由见类注释第 4 步那一节）
     * 5. 配额耗尽 → BLOCK
     * 6. 21:30 之后 → PASS
     * 7. 需要等待（秒数 > 0）→ WAIT
     * 8. PASS
     */
    @JvmStatic
    fun decide(): Decision {
        // 1. 功能没开就完全不管（默认口径：升级零行为变化）
        if (!DailyPlan.isEnabled()) return Decision.PASS
        // 2. 今天已被长按解除 → 今天整个不管，连日子都不问（理由见类注释）
        if (DailyPlan.isBypassedToday()) return Decision.PASS
        // 3. 还没选今天是什么日子 → 先问。必须在配额判定之前，否则按错误配额拦人
        if (!DailyPlan.hasDayTypeToday()) return Decision.ASK_DAY_TYPE
        // 4. 休息日 → 直接放行。它既不会被第 5 步拦（配额 Int.MAX_VALUE），
        //    也不该被第 7 步等 —— 而第 7 步只看颜色档、与配额无关，休息日用了 70 分钟
        //    就是红档 10 秒。这条不显式写出来，休息日照样要倒计时（v5.3.0 修的 bug）。
        if (DailyPlan.isRestDay()) return Decision.PASS
        // 5. 配额耗尽 → 拦。这一步用的配额已是「用户选完日子之后」的正确配额
        if (DailyPlan.isExhaustedToday()) return Decision.BLOCK
        // 6. 21:30 之后不做倒计时等待（只跳过等待，不影响上面的拦截）
        if (DailyPlan.isInNoCountdownWindow()) return Decision.PASS
        // 7. 需要等待就等（0~30min 不等 / 30~60min 5 秒 / >=60min 10 秒）
        if (waitSeconds() > 0) return Decision.WAIT
        // 8. 其它情况放行
        return Decision.PASS
    }

    /**
     * 本次门禁需要等待的秒数（[Decision.WAIT] 时由倒计时页读它）。
     *
     * 用 [DailyPlan.waitSecondsFor] 从**今日已用**派生，不在这里另算一套阈值
     * ——否则「颜色变黄了但没等待」这类自相矛盾的组合迟早会出现。
     *
     * ## 两个「返回 0」的例外（都是同一种坑：本函数是独立入口）
     * [decide] 已经先把这两种情况判成 PASS 了，但倒计时页是**单独**读本函数拿秒数的，
     * 如果这里不挡住，页面会拿到非 0 值并真的等下去 —— 判定说放行、页面照样拦，
     * 用户看到的就是「明明该进却还要等」。
     *
     * 1. **21:30 之后** → 0（夜间不再加摩擦）；
     * 2. **休息日** → 0（v5.3.0 新增）。这条最容易被漏掉，因为「休息日不会被拦」是真的，
     *    但颜色档是按「今日已用」算的、与配额无关：休息日用了 70 分钟就是红档 → 原本返回
     *    10 秒 → 休息日照样要倒计时。这正是用户报的那个 bug。
     */
    @JvmStatic
    fun waitSeconds(): Int {
        if (DailyPlan.isInNoCountdownWindow()) return 0
        // 休息日不等待：理由同上，这一条与 decide 第 4 步互为兜底
        if (DailyPlan.isRestDay()) return 0
        return DailyPlan.waitSecondsFor(DailyPlan.usedMsToday())
    }

    // ==========================================================================================
    // 给 UI 的派生信息（都是纯转发/组合，放在这里是为了让页面只依赖一个对象）
    // ==========================================================================================

    /** 当前该显示的今日已用毫秒（转发；页面头部「你今天已用 X」用） */
    @JvmStatic
    fun usedMsToday(): Long = DailyPlan.usedMsToday()

    /** 当前的颜色分档（0 绿 / 1 黄 / 2 红；页面按它取色） */
    @JvmStatic
    fun levelToday(): Int = DailyPlan.levelOf(DailyPlan.usedMsToday())

    /**
     * 今天是否还能用（配额没耗尽）。
     *
     * 拦截页用它判断「用户是不是刚刚借到时间了，可以放行」——借完时间后配额变大，
     * 这个值会从 false 变 true，页面据此自动放行（不需要用户再点一次）。
     */
    @JvmStatic
    fun canUseNow(): Boolean = !DailyPlan.isExhaustedToday()
}
