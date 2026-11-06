package com.RobinNotBad.BiliClient.time.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.time.DailyPlan
import com.RobinNotBad.BiliClient.time.TimeBank
import com.RobinNotBad.BiliClient.time.TimeFormat
import com.RobinNotBad.BiliClient.time.TimePrefs

/**
 * 设置 → 时间管理的入口页（三个小栏：使用控制 / 时间银行 / 使用记录）。
 *
 * # 为什么它不是设置列表
 * 用户原话是「这个栏目再分成多个小栏」，每栏点进去都是一整套独立功能（有开关、有图形、有滑动操作）。
 * 塞进 [com.RobinNotBad.BiliClient.adapter.SettingsAdapter] 的 click 行只能显示一行文字，
 * 小屏上既难点中也看不出「这一栏做了什么」。所以做成三张卡片，与设置主页的
 * 「播放器设置 / 界面设置」卡片同一种视觉语言。
 *
 * # 为什么状态要在这里显示一遍
 * 用户打开这一页最常见的疑问是「这功能到底开没开、今天用了多少」。
 * 每张小卡右侧写一行当前值（开关状态 / 已借分钟 / 今日已用），
 * 不用点进去就能确认，减少了「进去看一眼再退出来」的来回。
 *
 * # 数据刷新时机
 * 只在本页 [onResume] 刷新：所有值都从偏好现算（[DailyPlan] / [TimeBank] 都是无状态对象），
 * 从子页面返回时天然拿到最新值，不需要任何回调或 EventBus。
 */
class TimeMainActivity : BaseActivity() {

    private var summaryView: TextView? = null
    private var controlStateView: TextView? = null
    private var bankStateView: TextView? = null
    private var recordStateView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_main)
        setPageName(getString(R.string.tm2_page_main))
        // 顶栏的返回箭头由 BaseActivity.onStart 的 setTopbarExit() 挂上，这里不用重复设置

        summaryView = findViewById(R.id.tm2_main_summary)
        controlStateView = findViewById(R.id.tm2_control_state)
        bankStateView = findViewById(R.id.tm2_bank_state)
        recordStateView = findViewById(R.id.tm2_record_state)

        bindCard(R.id.tm2_card_control, UsageControlActivity::class.java)
        bindCard(R.id.tm2_card_bank, TimeBankActivity::class.java)
        bindCard(R.id.tm2_card_record, UsageRecordActivity::class.java)
    }

    override fun onResume() {
        super.onResume()
        // 从子页面返回后开关/配额的当前值可能已变，每次回前台重算一遍最省心
        refreshState()
    }

    /**
     * 给卡片挂点击跳转。
     *
     * 卡片本身可能没有 id（findViewById 返回 null）时不崩：时间管理入口页是附属功能，
     * 一张卡片因为布局被裁剪而消失不该让整页闪退（历史上 setContentView 后强解引用吃过亏）。
     */
    private fun bindCard(cardId: Int, target: Class<*>) {
        val card: View = findViewById(cardId) ?: return
        card.setOnClickListener {
            startActivity(Intent(this, target))
        }
    }

    /** 刷新顶部摘要 + 三张卡的状态小字 */
    private fun refreshState() {
        val enabled = DailyPlan.isEnabled()
        val usedMs = DailyPlan.usedMsToday()

        summaryView?.text = if (enabled) {
            getString(R.string.tm2_main_summary_on, TimeFormat.duration(usedMs))
        } else {
            getString(R.string.tm2_main_summary_off)
        }
        // 摘要的颜色跟着「今天用得是不是该停了」走；未开启时用普通次要色（不暗示任何档位）
        if (enabled) {
            summaryView?.setTextColor(TimeFormat.levelColor(this, DailyPlan.levelOf(usedMs)))
        } else {
            summaryView?.setTextColor(getColor(R.color.tm2_text_secondary))
        }

        controlStateView?.text = if (enabled) {
            getString(R.string.tm2_main_control_state_on, TimePrefs.schoolQuotaMin())
        } else {
            getString(R.string.tm2_main_control_state_off)
        }

        val bankEnabled = TimeBank.isEnabled()
        bankStateView?.text = if (bankEnabled) {
            // 「已借 / 上限」两个数一起给：只写一个数字用户判断不了还剩多少额度
            getString(
                R.string.tm2_main_bank_state_on,
                TimeBank.borrowedTotalMinutes(),
                TimePrefs.bankTotalLimitMin()
            )
        } else {
            getString(R.string.tm2_main_bank_state_off)
        }

        recordStateView?.text = getString(R.string.tm2_main_record_state, TimeFormat.duration(usedMs))
    }
}
