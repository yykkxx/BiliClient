package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.os.Bundle

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter
import com.RobinNotBad.BiliClient.model.SettingSection
import android.content.Intent
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StreamPolicyManager

import java.util.ArrayList

class SettingLaboratoryActivity : RefreshListActivity() {

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("实验室")

        val debugBuild = BiliTerminal.isDebugBuild()

        val sectionList = ArrayList<SettingSection>()

        //移动端推荐逻辑（实验性，独立登录 + 只影响推荐页）
        sectionList.add(SettingSection("title", "实验性功能", "", "", ""))
        sectionList.add(SettingSection("click", "移动端推荐逻辑", "",
                "使用移动端（APP端）推荐接口替换推荐页内容，需要在进入的页面里扫码登录后开启", "false",
                Runnable {
                    startActivity(Intent(this, SettingAppRcmdActivity::class.java))
                }))

        sectionList.add(SettingSection("title", "可用性", "", "", ""))
        sectionList.add(SettingSection("switch", "新版弹幕获取方式", "new_danmaku_api",
                getString(R.string.desc_new_danmaku_api), "true"))
        sectionList.add(SettingSection("switch", "私信未读标记", SharedPreferencesUtil.PRIVATE_MSG_UNREAD_BADGE_ENABLE,
                getString(R.string.desc_private_msg_unread_badge_enable), "false"))

        sectionList.add(SettingSection("title", "下载", "", "", ""))
        sectionList.add(SettingSection("switch", "使用旧版下载器", "dev_download_old",
                getString(R.string.setting_lab_download_old), "false"))
        sectionList.add(SettingSection("input_string", "缓存路径", "save_path_video",
                getString(R.string.setting_lab_path_video), FileUtil.getVideoDownloadPath().toString()))
        sectionList.add(SettingSection("input_string", "图片下载路径", "save_path_pictures",
                getString(R.string.setting_lab_path_pictures), FileUtil.getPicturePath().toString()))

        sectionList.add(SettingSection("title", "UI", "", "", ""))
        sectionList.add(SettingSection("switch", "文字跑马灯", "marquee_enable", getString(R.string.setting_lab_marquee),
                "true"))
        // 视频列表双列模式（#12）
        sectionList.add(SettingSection("switch", "视频列表双列模式", ListLayoutUtil.PREF_DUAL_COLUMN,
                getString(R.string.setting_lab_dual_column), "false"))
        // 拉流策略（#11，替代原"高温预缓存"）：慢速持续=稳（4G 耗电发热）；脉冲式=4G 不发热但可能偶卡
        sectionList.add(SettingSection("multi_choose", "拉流策略", StreamPolicyManager.PREF_STREAM_POLICY,
                "①慢速持续拉取：稳定，4G 耗电、发热\n②脉冲式：快速拉一段就停，循环往复——4G 不发热，但可能偶尔卡顿",
                "1", Pair(arrayOf("慢速持续", "脉冲式"), intArrayOf(StreamPolicyManager.POLICY_CONTINUOUS, StreamPolicyManager.POLICY_PULSE))))

        sectionList.add(SettingSection("title", "播放器", "", "", ""))
        sectionList.add(SettingSection("switch", "播放器旋屏兼容方案", "dev_player_rotate_software",
                "在极少数手表上（如小米手表），系统旋屏存在显示不全的问题。打开此开关，播放器将会使用软件旋屏方法。", "false"))
        sectionList.add(SettingSection("switch", "显示视频分段", "player_show_viewpoints",
                "显示视频的章节看点信息，可快速跳转到指定章节", "false"))
        sectionList.add(SettingSection("switch", "系统媒体控件", SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE,
                getString(R.string.setting_lab_media_session), "false"))
        sectionList.add(SettingSection("switch", "互动视频调试", "player_interaction_debug",
                "在互动视频播放时，在左侧倍速按钮上方显示调试按钮，可以查看和修改互动视频的变量", "false"))

        sectionList.add(SettingSection("title", "调试", "", "", ""))
        sectionList.add(SettingSection("switch", "允许Logu.v", "dev_logv", getString(R.string.setting_lab_logv),
                debugBuild.toString()))
        sectionList.add(SettingSection("switch", "允许Logu.d", "dev_logd", "", debugBuild.toString()))
        sectionList.add(SettingSection("switch", "允许Logu.i", "dev_logi", "", debugBuild.toString()))
        sectionList.add(SettingSection("switch", "详细显示数据解析报错", "dev_jsonerr_detailed",
                getString(R.string.setting_lab_jsonerr_detailed), debugBuild.toString()))
        sectionList.add(SettingSection("switch", "详细显示列表报错", "dev_recyclererr_detailed",
                getString(R.string.setting_lab_recyclererr_detailed), debugBuild.toString()))

        recyclerView!!.setHasFixedSize(true)

        val adapter = SettingsAdapter(this, sectionList)
        setAdapter(adapter)

        setRefreshing(false)
    }

}
