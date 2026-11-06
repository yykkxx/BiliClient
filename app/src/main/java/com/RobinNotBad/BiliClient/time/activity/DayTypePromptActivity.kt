package com.RobinNotBad.BiliClient.time.activity

import android.os.Bundle
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
 */
class DayTypePromptActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_type)
        setPageName(getString(R.string.tm2_page_day_type))
        // 点页面外部不关闭（配合 back 拦截，构成「不可取消」）
        setFinishOnTouchOutside(false)

        val quotaMin = TimePrefs.schoolQuotaMin()
        findViewById<TextView>(R.id.tm2_day_type_desc)?.text =
            getString(R.string.tm2_day_type_desc, quotaMin)
        findViewById<TextView>(R.id.tm2_day_type_school_desc)?.text =
            getString(R.string.tm2_day_type_school_desc, quotaMin)
        findViewById<TextView>(R.id.tm2_day_type_rest_desc)?.text =
            getString(R.string.tm2_day_type_rest_desc)

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
     */
    private fun chooseDayType(type: String) {
        DailyPlan.setDayType(type)
        // 记一次结算：让「刚才那几十秒的询问时间」明确不计入（pause 期间已排除，
        // 这里 flush 只是把 pause 前那一小段落盘，保证数字精确）
        UsageTracker.flush()
        setResult(RESULT_OK)
        finish()
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
}
