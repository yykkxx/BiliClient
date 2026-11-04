package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.os.Bundle

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter
import com.RobinNotBad.BiliClient.adapter.SwitchDisableGuard
import com.RobinNotBad.BiliClient.model.SettingSection
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StreamPolicyManager
import com.RobinNotBad.BiliClient.util.UsageLimitManager
import com.RobinNotBad.BiliClient.util.WifiPreloadManager

import java.util.ArrayList

/**
 * 实验室（实验性功能）设置页。
 *
 * v5.1.6：本页所有选项都补齐了完整的文字介绍（做什么 / 何时该开 / 有什么代价 / 何时生效）。
 * 文案统一放在 `res/values/strings.xml` 的「实验室」区块，命名前缀 `setting_lab_`，
 * 这样改文案不用动代码，也方便统一校对语气与口径。
 *
 * 说明：v5.1.4 的「API 迁移（三级）」「推荐 API 迁移」「全 API 迁移」「重构账号体系」整段
 * 已在 v5.1.5 由「设置 → 账号管理」取代（端口选择 + 多账号都收敛到那一页），
 * 这里不再保留任何与登录 API 相关的开关，避免留下无效入口。
 */
class SettingLaboratoryActivity : RefreshListActivity() {

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("实验室")

        val debugBuild = BiliTerminal.isDebugBuild()

        val sectionList = ArrayList<SettingSection>()

        addAvailabilitySections(sectionList)
        addDownloadSections(sectionList)
        addUiSections(sectionList)
        addPlayerSections(sectionList)
        addDebugSections(sectionList, debugBuild)

        // recyclerView 由 RefreshListActivity.onCreate 赋值，这里只做防御性取用（门禁禁止 !!）
        recyclerView?.setHasFixedSize(true)

        val adapter = SettingsAdapter(this, sectionList)
        setAdapter(adapter)

        setRefreshing(false)
    }

    /** 可用性：弹幕接口与私信未读标记 */
    private fun addAvailabilitySections(sectionList: ArrayList<SettingSection>) {
        sectionList.add(SettingSection("title", getString(R.string.setting_lab_title_available), "", "", ""))
        sectionList.add(SettingSection("switch", "新版弹幕获取方式", "new_danmaku_api",
                getString(R.string.desc_new_danmaku_api), "true"))
        sectionList.add(SettingSection("switch", "私信未读标记", SharedPreferencesUtil.PRIVATE_MSG_UNREAD_BADGE_ENABLE,
                getString(R.string.desc_private_msg_unread_badge_enable), "false"))
        // 每日使用时长限制：开启可直接开；**关闭需原地等 30 秒**（SwitchDisableGuard 拦截并倒计时）
        sectionList.add(SettingSection("switch", getString(R.string.setting_lab_usage_limit_name),
                UsageLimitManager.PREF_LAB_USAGE_LIMIT, getString(R.string.setting_lab_usage_limit_desc), "false",
                SwitchDisableGuard(UsageLimitManager.DISABLE_GUARD_SECONDS)))
    }

    /** 下载：旧版下载器 + 两个保存路径 */
    private fun addDownloadSections(sectionList: ArrayList<SettingSection>) {
        sectionList.add(SettingSection("title", getString(R.string.setting_lab_title_download), "", "", ""))
        sectionList.add(SettingSection("switch", "使用旧版下载器", "dev_download_old",
                getString(R.string.setting_lab_download_old), "false"))
        sectionList.add(SettingSection("input_string", "缓存路径", "save_path_video",
                getString(R.string.setting_lab_path_video), FileUtil.getVideoDownloadPath().toString()))
        sectionList.add(SettingSection("input_string", "图片下载路径", "save_path_pictures",
                getString(R.string.setting_lab_path_pictures), FileUtil.getPicturePath().toString()))
    }

    /** 界面显示：跑马灯、双列、拉流策略（策略影响观感与发热，归在这一组便于理解） */
    private fun addUiSections(sectionList: ArrayList<SettingSection>) {
        sectionList.add(SettingSection("title", "UI", "", "", ""))
        sectionList.add(SettingSection("switch", "文字跑马灯", "marquee_enable",
                getString(R.string.setting_lab_marquee), "true"))
        // 视频列表双列模式（#12）
        sectionList.add(SettingSection("switch", "视频列表双列模式", ListLayoutUtil.PREF_DUAL_COLUMN,
                getString(R.string.setting_lab_dual_column), "false"))
        // 拉流策略（#11，替代原「高温预缓存」）：慢速持续=稳（4G 耗电发热）；脉冲式=4G 不发热但可能偶卡
        sectionList.add(SettingSection("multi_choose", "拉流策略", StreamPolicyManager.PREF_STREAM_POLICY,
                getString(R.string.setting_lab_stream_policy),
                "1", Pair(arrayOf("慢速持续", "脉冲式"), intArrayOf(StreamPolicyManager.POLICY_CONTINUOUS, StreamPolicyManager.POLICY_PULSE))))
    }

    /** 播放器：短视频入口、WiFi 预加载、旋屏兼容、分段、媒体控件、互动调试 */
    private fun addPlayerSections(sectionList: ArrayList<SettingSection>) {
        sectionList.add(SettingSection("title", getString(R.string.setting_lab_title_player), "", "", ""))
        // 短视频模式（shortvideo 分支专属）：菜单显示「短视频」入口
        sectionList.add(SettingSection("switch", "短视频入口", "menu_story",
                getString(R.string.setting_lab_story_entry), "true"))
        // WiFi 预加载（#5）：进详情页即预取视频流前几 MB，点播放秒开
        sectionList.add(SettingSection("switch", "WiFi视频预加载", WifiPreloadManager.PREF_WIFI_PRELOAD,
                getString(R.string.setting_lab_wifi_preload), "false"))
        sectionList.add(SettingSection("switch", "播放器旋屏兼容方案", "dev_player_rotate_software",
                getString(R.string.setting_lab_rotate_software), "false"))
        sectionList.add(SettingSection("switch", "显示视频分段", "player_show_viewpoints",
                getString(R.string.setting_lab_viewpoints), "false"))
        sectionList.add(SettingSection("switch", "系统媒体控件", SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE,
                getString(R.string.setting_lab_media_session), "false"))
        sectionList.add(SettingSection("switch", "互动视频调试", "player_interaction_debug",
                getString(R.string.setting_lab_interaction_debug), "false"))
    }

    /** 调试：日志等级与详细报错 */
    private fun addDebugSections(sectionList: ArrayList<SettingSection>, debugBuild: Boolean) {
        sectionList.add(SettingSection("title", getString(R.string.setting_lab_title_debug), "", "", ""))
        // 标题下面紧跟一条只读说明（用 click 行承载长文案更稳，不引入新的行类型）
        sectionList.add(SettingSection("title", getString(R.string.setting_lab_debug_tip), "", "", ""))
        sectionList.add(SettingSection("switch", "允许Logu.v", "dev_logv",
                getString(R.string.setting_lab_logv), debugBuild.toString()))
        sectionList.add(SettingSection("switch", "允许Logu.d", "dev_logd", "", debugBuild.toString()))
        sectionList.add(SettingSection("switch", "允许Logu.i", "dev_logi", "", debugBuild.toString()))
        sectionList.add(SettingSection("switch", "详细显示数据解析报错", "dev_jsonerr_detailed",
                getString(R.string.setting_lab_jsonerr_detailed), debugBuild.toString()))
        sectionList.add(SettingSection("switch", "详细显示列表报错", "dev_recyclererr_detailed",
                getString(R.string.setting_lab_recyclererr_detailed), debugBuild.toString()))
    }
}
