package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.activity.time.TimeBankActivity
import com.RobinNotBad.BiliClient.activity.time.UsageStatsActivity
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter
import com.RobinNotBad.BiliClient.adapter.SwitchDisableGuard
import com.RobinNotBad.BiliClient.model.SettingSection
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.TimeBankManager
import com.RobinNotBad.BiliClient.util.TimeMgmtPrefs
import com.RobinNotBad.BiliClient.util.UsageLimitManager
import com.RobinNotBad.BiliClient.util.UsageStatsManager

import java.util.ArrayList

/**
 * 设置 → 时间管理（v5.2.0）。
 *
 * ## 为什么单独一页
 * 用户要求「以上的所有功能都有单独的开关以及单独的配置项，在设置-时间管理里面」。
 * 五组功能（统计 / 时间银行 / 提醒 / 倒计时页面 / 旧版限制）加起来十来行，
 * 塞进「偏好设置」会把那页撑爆、也找不到入口，所以做独立子页 + 主页一张卡片。
 *
 * ## 与实验室的关系
 * 「每日使用时长限制」原本在实验性功能里，本轮**搬到这里**（实验室里那行已删），
 * 偏好键仍是 `lab_usage_limit`（键名不动 → 老用户的开关键值原样生效，升级零迁移）。
 * 关闭仍需原地等 30 秒，沿用 [SwitchDisableGuard]。
 *
 * ## 全是增量行
 * 每行都是本套件的新键（默认值见 [TimeMgmtPrefs] 的注释），默认值下行为与本版之前一致。
 */
class SettingTimeActivity : RefreshListActivity() {

    /** 清空统计的二次确认计数（第一击提示、第二击执行） */
    private var clearStatsClick = 0

    /** 重置时间银行的二次确认计数 */
    private var resetBankClick = 0

    @SuppressLint("MissingInflatedId", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName(getString(R.string.time_mgmt_page_name))

        val sectionList = ArrayList<SettingSection>()

        addStatsSections(sectionList)
        addTimeBankSections(sectionList)
        addReminderSections(sectionList)
        addCountdownSections(sectionList)
        addLegacySections(sectionList)
        addActionSections(sectionList)

        recyclerView?.setHasFixedSize(true)

        val adapter = SettingsAdapter(this, sectionList)
        setAdapter(adapter)

        setRefreshing(false)
    }

    /** 使用时长统计：总开关 + 独立入口 */
    private fun addStatsSections(list: ArrayList<SettingSection>) {
        list.add(SettingSection("title", getString(R.string.time_mgmt_group_stats), "", "", ""))
        list.add(
            SettingSection(
                "switch", getString(R.string.time_mgmt_stats_switch),
                TimeMgmtPrefs.STATS_ENABLE, getString(R.string.time_mgmt_stats_desc), "true"
            )
        )
        list.add(
            SettingSection(
                "click", getString(R.string.time_mgmt_stats_view), "",
                getString(R.string.time_mgmt_stats_view_desc), "",
                Runnable { openPage(UsageStatsActivity::class.java) }
            )
        )
    }

    /** 时间银行：开关 + 图形化入口 + 四个配额配置 + 重置 */
    private fun addTimeBankSections(list: ArrayList<SettingSection>) {
        list.add(SettingSection("title", getString(R.string.time_mgmt_group_bank), "", "", ""))
        list.add(
            SettingSection(
                "switch", getString(R.string.time_mgmt_bank_switch),
                TimeMgmtPrefs.BANK_ENABLE, getString(R.string.time_mgmt_bank_desc), "false"
            )
        )
        list.add(
            SettingSection(
                "click", getString(R.string.time_mgmt_bank_open), "",
                getString(R.string.time_mgmt_bank_open_desc), "",
                Runnable { openPage(TimeBankActivity::class.java) }
            )
        )
        list.add(
            SettingSection(
                "input_int", getString(R.string.time_mgmt_bank_school),
                TimeMgmtPrefs.BANK_SCHOOL_QUOTA, getString(R.string.time_mgmt_bank_school_desc), "120"
            )
        )
        list.add(
            SettingSection(
                "input_int", getString(R.string.time_mgmt_bank_rest),
                TimeMgmtPrefs.BANK_REST_QUOTA, getString(R.string.time_mgmt_bank_rest_desc), "300"
            )
        )
        list.add(
            SettingSection(
                "input_int", getString(R.string.time_mgmt_bank_borrow),
                TimeMgmtPrefs.BANK_BORROW_LIMIT, getString(R.string.time_mgmt_bank_borrow_desc), "300"
            )
        )
        list.add(
            SettingSection(
                "input_int", getString(R.string.time_mgmt_bank_wait),
                TimeMgmtPrefs.BANK_WAIT_CLOSE_SEC, getString(R.string.time_mgmt_bank_wait_desc), "120"
            )
        )
        list.add(
            SettingSection(
                "click", getString(R.string.time_mgmt_bank_reset), "",
                getString(R.string.time_mgmt_bank_reset_desc), "",
                Runnable { confirmResetTimeBank() }
            )
        )
    }

    /** 定时提醒：开关 + 间隔 */
    private fun addReminderSections(list: ArrayList<SettingSection>) {
        list.add(SettingSection("title", getString(R.string.time_mgmt_group_reminder), "", "", ""))
        list.add(
            SettingSection(
                "switch", getString(R.string.time_mgmt_reminder_switch),
                TimeMgmtPrefs.REMINDER_ENABLE, getString(R.string.time_mgmt_reminder_desc), "false"
            )
        )
        list.add(
            SettingSection(
                "input_int", getString(R.string.time_mgmt_reminder_interval),
                TimeMgmtPrefs.REMINDER_INTERVAL, getString(R.string.time_mgmt_reminder_interval_desc), "15"
            )
        )
    }

    /** 倒计时独立页面开关 */
    private fun addCountdownSections(list: ArrayList<SettingSection>) {
        list.add(SettingSection("title", getString(R.string.time_mgmt_group_countdown), "", "", ""))
        list.add(
            SettingSection(
                "switch", getString(R.string.time_mgmt_countdown_switch),
                TimeMgmtPrefs.COUNTDOWN_PAGE, getString(R.string.time_mgmt_countdown_desc), "true"
            )
        )
    }

    /** 旧版每日使用时长限制（从实验室搬来，键名与守卫都不变） */
    private fun addLegacySections(list: ArrayList<SettingSection>) {
        list.add(SettingSection("title", getString(R.string.time_mgmt_group_legacy), "", "", ""))
        list.add(
            SettingSection(
                "switch", getString(R.string.time_mgmt_legacy_switch),
                UsageLimitManager.PREF_LAB_USAGE_LIMIT, getString(R.string.time_mgmt_legacy_desc), "false",
                SwitchDisableGuard(UsageLimitManager.DISABLE_GUARD_SECONDS)
            )
        )
    }

    /** 数据操作：清空统计（两击确认，与设置主页「清空登录数据」的既有交互一致） */
    private fun addActionSections(list: ArrayList<SettingSection>) {
        list.add(SettingSection("title", getString(R.string.time_mgmt_group_actions), "", "", ""))
        list.add(
            SettingSection(
                "click", getString(R.string.time_mgmt_clear_stats), "",
                getString(R.string.time_mgmt_clear_stats_desc), "",
                Runnable { confirmClearStats() }
            )
        )
    }

    private fun openPage(clazz: Class<*>) {
        startActivity(Intent(this, clazz))
    }

    /**
     * 二次确认后清空统计。
     *
     * 为什么不用 AlertDialog：本页是设置列表，清空不可逆。两击确认（第一次提示、第二次执行）
     * 与设置主页「清空登录数据」的既有交互一致，用户不会因误点一个按钮就丢掉几周统计。
     */
    private fun confirmClearStats() {
        if (clearStatsClick > 0) {
            clearStatsClick = 0
            UsageStatsManager.clear()
            MsgUtil.showMsg(getString(R.string.time_mgmt_cleared))
        } else {
            clearStatsClick++
            MsgUtil.showMsg(getString(R.string.time_mgmt_confirm_clear))
        }
    }

    private fun confirmResetTimeBank() {
        if (resetBankClick > 0) {
            resetBankClick = 0
            TimeBankManager.resetBorrows()
            MsgUtil.showMsg(getString(R.string.time_mgmt_bank_reset_done))
        } else {
            resetBankClick++
            MsgUtil.showMsg(getString(R.string.time_mgmt_confirm_reset_bank))
        }
    }
}
