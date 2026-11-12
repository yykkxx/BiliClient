package com.RobinNotBad.BiliClient.time.activity

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.TimePrefs
import com.RobinNotBad.BiliClient.time.UsageTracker
import com.google.android.material.button.MaterialButton

/**
 * 打开应用先问「今天是上学日还是休息日」（**不可取消**）。
 *
 * # 为什么必须不可取消
 * 配额的值取决于日子类型（上学日 = 有限额、休息日 = 无限制）。
 * 如果能点空白/按返回跳过，用户**每天都能靠「不选」绕过限额判定**，这个功能就形同虚设
 * （v5.2.0 的旧实现也是 setCancelable(false)，同样理由）。
 *
 * 本页是三处「不可取消」的实现之一（另两处是拦截页的返回键、倒计时页的自动放行）：
 * 1. `setFinishOnTouchOutside(false)`：点页面外面不关闭（本页是全屏 Activity，
 *    这个调用主要是防「未来某天被改成 Dialog 主题」时失效）；
 * 2. `onBackPressed` 直接吞掉返回键；
 * 3. `onKeyDown` 吞掉 MENU 键 —— BaseActivity 的 `onKeyDown` 对 `KEYCODE_MENU`
 *    会直接 `finish()`，不拦的话手表侧键/菜单键就能跳过本页。
 *
 * # 为什么选完就 finish 而不传结果给调用方
 * 启动页（LauncherActivity）的做法是「子页面返回后**重新问一次 Gate**」，
 * 而 Gate 是每次从偏好现算的无状态对象 —— 所以本页只要把类型写进偏好、
 * `setResult(RESULT_OK)` 然后 finish 就够了，不需要任何 result 数据契约。
 * 少一套契约就少一处会写错的地方。
 *
 * # 计时
 * 本页停留**不计入使用时长**（它是「还没开始用」的阶段，把它算进去等于
 * 「选了个日子就白扣几秒」）。做法是 onResume 调 [UsageTracker.pause]、
 * onPause 调 [UsageTracker.resume]，成对出现（计数器保证嵌套正确）。
 *
 * # v5.3.4：本页的偶发闪退（用户报「选择上学日/休息日页面偶发崩溃」）
 *
 * ## 真正的根因不在本页，而在异步 inflate 的交付路径
 * 选完之后启动页会重问 Gate → PASS → 进推荐页 → 推荐页立刻拉起教程页，
 * **教程页是全 App 在这条链上唯一使用 `asyncInflate` 的页面**，而
 * `AsyncLayoutInflaterX` 交付回调时既不兜异常、也不检查宿主是否已经销毁。
 * 教程页的回调里写着 `Objects.requireNonNull(TutorialHelper.loadTutorial(...))`
 * 和几处未经检查的强转，`loadTutorial` 解析失败时返回 null → NPE 从
 * `Handler.handleMessage` 里抛出去 → 主线程崩溃。表现就是「点了上学日/休息日就闪退」，
 * 且**偶发**（只有教程那一版要展示、且解析失败时才撞上）。
 * 修复在 [com.RobinNotBad.BiliClient.util.AsyncLayoutInflaterX]（交付前兜异常 + 判断宿主存活）。
 * 用户直觉说的「view/pool 的问题」，指向的正是这里：**不是 `Pools` 那个池子，
 * 而是「inflate 结果被交付到了一个已经不在的视图树上」**。
 *
 * ## 本页自己补的防御（属于「宁可不崩溃」的加固，不是根因）
 * 下面几条都是「就算出问题也不该让用户看到闪退」：
 * 1. [chooseDayType] 幂等（[answered] 守）：手表按钮小、连点两下会进两次，
 *    原来是两次 `setResult` + 两次 `finish()`；
 * 2. [chooseDayType] 全包 try/catch：写偏好 / 结算时长任何一步抛异常，
 *    原来会直接把点击事件处理器打穿（主线程崩溃），现在只打日志并照常退出本页
 *    —— 退不出去比选不上更糟（用户当天就再也进不了 App）；
 * 3. [safeFlush] 单独兜住结算：结算失败只丢几秒统计，绝不能影响「选日子」这个主流程；
 * 4. onPause 的 `resume()` 放在 `super.onPause()` **之前**（与时间管理另两个门禁页
 *    CountdownGateActivity / QuotaBlockActivity 一致）：在页面还是 resumed 状态时
 *    先把闸门放开，避免「super 里做了点什么之后再改状态」这种不确定顺序。
 *    这一条**不是崩溃原因**（原顺序也不会崩），只是统一口径。
 */
class DayTypePromptActivity : BaseActivity() {

    /**
     * 是否已经回答过（幂等开关）。
     *
     * 为什么必须幂等：MaterialButton 的点击在水手表小屏上很容易连点；
     * 而且 `setResult` + `finish()` 之后按钮并不会立刻消失（要等主线程空转一圈），
     * 完全有时间被点第二次。两次 `finish()` 本身不崩，但会打出两条
     * ActivityResult 回调、让启动页那套 `gateGuardDepth` 多走一跳。
     */
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_type)
        setPageName(getString(R.string.tm2_page_day_type))
        // 点页面外部不关闭（配合 back 拦截，构成「不可取消」）
        setFinishOnTouchOutside(false)

        // 文案填充整体兜住：本页是**不可取消**的，若它自己在 onCreate 里抛异常，
        // 用户既进不了主界面、也没法选日子 —— 那比「说明文字没显示出来」严重得多。
        // 按钮的挂载放在 catch 之后，保证即使文案那一步失败，两个按钮照样可点。
        val quotaMin = try {
            TimePrefs.schoolQuotaMin()
        } catch (e: Exception) {
            Log.w(TAG, "read schoolQuotaMin failed, fall back to default", e)
            TimePrefs.DEFAULT_SCHOOL_QUOTA_MIN
        }
        try {
            findViewById<TextView>(R.id.tm2_day_type_desc)?.text =
                getString(R.string.tm2_day_type_desc, quotaMin)
            findViewById<TextView>(R.id.tm2_day_type_school_desc)?.text =
                getString(R.string.tm2_day_type_school_desc, quotaMin)
            findViewById<TextView>(R.id.tm2_day_type_rest_desc)?.text =
                getString(R.string.tm2_day_type_rest_desc)
        } catch (e: Exception) {
            // 缺字/格式串写错都只影响有没有说明文字，不影响「能不能选」
            Log.w(TAG, "fill prompt texts failed (page still usable)", e)
        }

        findViewById<MaterialButton>(R.id.tm2_day_type_school)?.setOnClickListener {
            chooseDayType(DailyPlan.DAY_SCHOOL)
        }
        findViewById<MaterialButton>(R.id.tm2_day_type_rest)?.setOnClickListener {
            chooseDayType(DailyPlan.DAY_REST)
        }
    }

    override fun onResume() {
        super.onResume()
        // 询问阶段不该扣使用时长（成对：本页 onPause 里 resume）
        UsageTracker.pause()
    }

    override fun onPause() {
        // 顺序与另两个门禁页保持一致：先放开暂停闸门，再交给 super
        UsageTracker.resume()
        super.onPause()
    }

    /**
     * 选完：先写偏好，再 setResult 并 finish。
     *
     * 顺序**先写后 finish**：调用方（启动页）会在结果回调里立刻重问 Gate，
     * 那时 `hasDayTypeToday()` 必须已经是 true，否则会被再问一遍。
     *
     * 用 [DailyPlan.setDayType]（逻辑层会校验取值、原子写两个键），
     * 不在本页直接写偏好 —— 那样就绕过了「只接受 school/rest」的校验。
     *
     * 全程兜异常：写偏好/结算统计任何一步失败都**不能**把点击处理器打穿
     * （那是一次主线程崩溃，而用户只是想选个日子）。
     */
    private fun chooseDayType(type: String) {
        if (answered) {
            Log.i(TAG, "already answered, ignore duplicated tap: $type")
            return
        }
        answered = true

        try {
            DailyPlan.setDayType(type)
        } catch (e: Exception) {
            // 写不进去也照常退出：退不出去的话用户当天就再也进不了 App（见类注释）
            Log.e(TAG, "setDayType failed: $type", e)
        }
        // 记一次结算：让「刚才那几十秒的询问时间」明确不计入（pause 期间已排除，
        // 这里 flush 只是把 pause 前那一小段落盘，保证数字精确）
        safeFlush()
        setResult(RESULT_OK)
        if (!isDestroyed && !isFinishing) finish()
    }

    /**
     * 结算使用时长，失败只打日志。
     *
     * 这一句是纯统计，失败了最多丢几十毫秒的页面停留；为了它把「选日子」这个
     * 决定当天配额的主流程带崩，代价完全不对等。
     */
    private fun safeFlush() {
        try {
            UsageTracker.flush()
        } catch (e: Exception) {
            Log.w(TAG, "flush usage failed (ignored, statistics only)", e)
        }
    }

    /**
     * 吞掉返回键（**不可取消**的核心）。
     *
     * 不调 `super.onBackPressed()`：那会走 BaseActivity 的实现，最终 finish 本页。
     * 也不用提示 toast —— 页面底部那行小字已经说明了「不能取消」，再弹一次很吵。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 有意不分派：本页必须被回答才能离开
    }

    /**
     * 吞掉 MENU 键。
     *
     * BaseActivity.onKeyDown 里对 `KEYCODE_MENU` 会直接 `finish()`（那是全局的「按菜单退出」约定）。
     * 本页若照原样走，手表侧键一按就能跳过询问 → 又变回「可以不选」。
     * 其它按键正常放行（方向键要能移动焦点去点按钮）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        /** 日志标签：本页的偶发问题排查靠它过滤 */
        const val TAG = "day-type-prompt"
    }
}
