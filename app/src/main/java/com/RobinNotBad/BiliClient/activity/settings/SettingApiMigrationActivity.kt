package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.api.migration.ApiMigrationRules
import com.RobinNotBad.BiliClient.api.migration.ApiRouter
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 「全 API 迁移（实验）」状态页。
 *
 * 这里只做展示与开关：
 * - 开关写 [SharedPreferencesUtil.API_MIGRATION_ENABLED]，网络层
 *   [com.RobinNotBad.BiliClient.util.NetWorkUtil] 每次请求时读取（关掉立刻恢复原行为）；
 * - 身份/登录状态直接读 [AppRcmdApi] —— 与「移动端推荐逻辑」共用同一套三身份和 token 槽位，
 *   不另起存储；
 * - 命中/回退/最近失败端点由 [ApiRouter] 维护，可一键清空；
 * - 页面不发起任何网络请求，进入即可看状态。
 */
class SettingApiMigrationActivity : BaseActivity() {

    private var switchView: SwitchMaterial? = null
    private var identityText: TextView? = null
    private var statsText: TextView? = null
    private var coverageText: TextView? = null
    private var failText: TextView? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting_api_migration)
        setPageName("全 API 迁移")
        setTopbarExit()

        switchView = findViewById(R.id.apiMigSwitch)
        identityText = findViewById(R.id.apiMigIdentityText)
        statsText = findViewById(R.id.apiMigStatsText)
        coverageText = findViewById(R.id.apiMigCoverageText)
        failText = findViewById(R.id.apiMigFailText)

        switchView?.setOnCheckedChangeListener(switchListener)
        val clearCard = findViewById<MaterialCardView>(R.id.apiMigClear)
        clearCard.setOnClickListener { confirmClear() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 从「移动端推荐逻辑」页切换身份/登录后返回，状态要跟着变
        refreshStatus()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val identity = AppRcmdApi.getIdentity()
        val logged = AppRcmdApi.isLogin()
        val mid = AppRcmdApi.getIdentityMid(identity.id)
        val identitySb = StringBuilder()
        identitySb.append("当前客户端身份：").append(identity.displayName)
        identitySb.append(if (logged) "（已登录" else "（未登录")
        if (logged && mid > 0) identitySb.append("，uid ").append(mid)
        identitySb.append("）")
        identitySb.append("\nAPP 通道签名：").append(identity.feedMobiApp)
        identitySb.append(" / build ").append(identity.feedBuild)
        identitySb.append("\n身份跟随「移动端推荐逻辑」页的客户端身份设置")
        identityText?.text = identitySb.toString()

        val hits = ApiRouter.hitCount()
        val fallbacks = ApiRouter.fallbackCount()
        val attempts = hits + fallbacks
        val rate = if (attempts > 0) hits * 100L / attempts else 0L
        val statsSb = StringBuilder()
        statsSb.append("APP 通道命中：").append(hits).append(" 次")
        statsSb.append("\n回退 web：").append(fallbacks).append(" 次")
        statsSb.append("\n命中率：").append(rate).append("%")
        statsSb.append("\n本进程已尝试端点：").append(ApiRouter.attemptedCount()).append(" 个")
        val suspended = ApiRouter.suspendedEndpoints()
        statsSb.append("\n连续失败暂停中：").append(suspended.size).append(" 个（暂停 10 分钟）")
        statsSb.append("\n因暂停直接走 web：").append(ApiRouter.skipCount()).append(" 次")
        // 非幂等写操作（投币/发评论/发弹幕…）在 APP 通道响应不可用时不回退重发，单独计数
        statsSb.append("\n写操作不重放：").append(ApiRouter.noReplayCount()).append(" 次")
        statsText?.text = statsSb.toString()

        coverageText?.text = buildCoverageText()

        val failures = ApiRouter.recentFailures()
        if (failures.isEmpty()) {
            failText?.text = "暂无（APP 通道失败时会记录在这里，最多 8 条）"
        } else {
            failText?.text = failures.joinToString("\n")
        }

        // 程序化置位会触发监听器，先摘掉再恢复（与设置页其它开关的写法一致）
        val switch = switchView
        if (switch != null) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = ApiRouter.isEnabled()
            switch.setOnCheckedChangeListener(switchListener)
        }
    }

    /**
     * 覆盖情况文案。
     *
     * 手表圆屏太窄，所以做成「总览 + 按分类一行一条 + 保留项清单」的纯文本，
     * 不再加列表控件；内容全部来自 [ApiMigrationRules]，改规则表这里自动跟着变。
     */
    @SuppressLint("SetTextI18n")
    private fun buildCoverageText(): String {
        val sb = StringBuilder()
        sb.append("已迁移端点：").append(ApiMigrationRules.migratedCount()).append(" 条")
        sb.append("\n  · 同路径 APP 参数化：").append(ApiMigrationRules.appParamsCount())
        sb.append("\n  · 路径/主机改写：").append(ApiMigrationRules.pathOverrideCount())
        sb.append("\n  · 写操作：").append(ApiMigrationRules.writeCount())
        sb.append("（幂等 ").append(ApiMigrationRules.postSafeCount())
        sb.append(" / 不重放 ").append(ApiMigrationRules.postWriteCount()).append("）")
        sb.append("\n明确保持 web：").append(ApiMigrationRules.keepWebCount()).append(" 条（见下方清单）")

        val categories = ApiMigrationRules.countsByCategory()
        if (categories.isNotEmpty()) {
            sb.append("\n\n按分类：")
            for ((name, count) in categories) {
                sb.append("\n  ").append(name).append(" ").append(count)
            }
        }

        val keeps = ApiMigrationRules.keepWebItems()
        if (keeps.isNotEmpty()) {
            sb.append("\n\n保留 web（协议层无 APP 等价）：")
            for (item in keeps) {
                sb.append("\n  ").append(item)
            }
        }

        sb.append("\n\n未列出的 api.bilibili.com JSON 接口也会尝试 APP 通道；")
        sb.append("未登录当前身份时只迁移公共接口（视频信息/评论/番剧/搜索等），")
        sb.append("收藏/历史/动态/播放地址等保持 web，避免匿名空数据。")
        return sb.toString()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
                .setTitle("清空迁移统计")
                .setMessage("只会清空命中/回退次数与最近失败记录，不影响开关、登录状态和已暂停的端点之外的功能。")
                .setPositiveButton("清空") { _, _ ->
                    ApiRouter.resetStats()
                    refreshStatus()
                    MsgUtil.showMsg("已清空迁移统计")
                }
                .setNegativeButton("取消", null)
                .show()
    }

    private val switchListener =
            CompoundButton.OnCheckedChangeListener { _, isChecked ->
                SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.API_MIGRATION_ENABLED, isChecked)
                if (isChecked) {
                    // 重新打开时清掉上一轮的「连续失败暂停」，让端点重新尝试 APP 通道
                    ApiRouter.clearSuspensions()
                    if (AppRcmdApi.isLogin()) {
                        MsgUtil.showMsg("已开启全 API 迁移（" + AppRcmdApi.getIdentity().displayName + "），后续请求走 APP 通道")
                    } else {
                        MsgUtil.showMsg("已开启全 API 迁移；当前身份未登录，只有公共接口会走 APP 通道")
                    }
                } else {
                    MsgUtil.showMsg("已关闭全 API 迁移，恢复原有 web 请求")
                }
                refreshStatus()
            }
}
