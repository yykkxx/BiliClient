package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.os.Bundle

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter
import com.RobinNotBad.BiliClient.model.SettingSection
import com.RobinNotBad.BiliClient.api.StoryApi
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StreamPolicyManager
import com.RobinNotBad.BiliClient.util.WifiGuard
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
        addNetworkGuardSections(sectionList)
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
        // v5.2.0：「每日使用时长限制」已搬到「设置 → 时间管理」（那里同时有统计 / 时间银行 /
        // 提醒 / 倒计时页面）。偏好键仍是 lab_usage_limit，老用户开关状态原样生效，
        // 这里不保留第二个入口，避免同一开关出现在两处、状态互相看起来「没生效」。
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
        // 短视频信息流端点（v5.3.4，需求6 第四项）
        // 页内也有入口（长按短视频页标题），这里再放一行是为了「想找的时候找得到」——
        // 页内弹层是可发现性问题，设置页是完整性；两处读写同一个偏好键。
        // labels 顺序必须与 StoryApi.endpointIds 一一对应，改那边记得同步改这里。
        sectionList.add(SettingSection("multi_choose", "短视频信息流端点",
                StoryApi.PREF_STORY_ENDPOINT,
                "短视频页用哪个接口拉内容。\n「自动」= 老版本行为（短视频接口失败时自动降级推荐流）。\n选具体端点时只走那一条，失败会把原因显示在页面上（不静默换源，否则『我明明选了 X』无法排查）。\n切换后短视频页会重新加载内容。",
                StoryApi.ENDPOINT_AUTO.toString(),
                Pair(arrayOf("自动（失败降级）", "短视频接口", "APP 推荐流", "网页推荐流"),
                        StoryApi.endpointIds)))
        // 时间跳转的输入方式（v5.3.0，#3/#4）：
        // 开着 = 播放器内自绘的屏幕数字键盘（v5.2.1 的默认行为，圆屏不弹系统键盘）；
        // 关掉 = 回退旧实现（EditText + 系统输入法，能打冒号，但输入法会把内容顶出圆屏）。
        // 文案写在本文件而不是 strings.xml：这一行是本轮新增的、还没进资源的实验项，
        // 与同文件里「文字跑马灯」等老行的写法保持一致即可，避免为一个开关新增资源键。
        sectionList.add(SettingSection("switch", "时间跳转用屏幕数字键盘",
                SharedPreferencesUtil.PLAYER_TIME_JUMP_KEYPAD,
                "关闭后改用系统输入法打字（可以打冒号）；打开则用播放器内自绘的数字键盘，不弹系统键盘",
                "true"))
    }

    /**
     * 网络守卫（v5.3.4，需求 8）：特定 WiFi 下不发出任何网络请求。
     *
     * 三段式：总开关 + 说明 + SSID 列表（用 `input_string` 行承载，天然支持多行文本）。
     *
     * # 为什么默认关闭
     * 这个功能一旦开启且填了 SSID，用户在那个网络下**完全无法联网**（直到点「继续」）。
     * 默认开启等于给所有用户突然断网 —— 必须是用户显式打开。
     *
     * # 为什么 SSID 用 `input_string` 而不是新做一个列表编辑页
     * `input_string` 行已支持多行文本输入（`cell_setting_input`），
     * 而本功能需要的正是"填几个名字"，用现成行类型零成本；
     * 新做一页要动布局/Adapter/manifest 三处，收益不匹配。
     */
    private fun addNetworkGuardSections(sectionList: ArrayList<SettingSection>) {
        sectionList.add(SettingSection("title", "网络", "", "", ""))
        sectionList.add(SettingSection("switch", getString(R.string.lab_wifi_guard_title),
                WifiGuard.PREF_ENABLED, getString(R.string.lab_wifi_guard_desc), "false"))
        sectionList.add(SettingSection("input_string", "受保护的 WiFi 名称",
                WifiGuard.PREF_SSIDS, getString(R.string.lab_wifi_guard_edit_desc), ""))
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
