package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter
import com.RobinNotBad.BiliClient.model.SettingSection

import java.util.ArrayList

open class SettingTerminalPlayerActivity : RefreshListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("内置播放器设置")

        val sectionList = ArrayList<SettingSection>()
        sectionList.add(SettingSection("switch", "长按倍速", "player_longclick", "", "true"))
        sectionList.add(SettingSection("switch", "双击快进快退", "player_doubletap_seek", "", "false"))
        sectionList.add(SettingSection("input_int", "快进快退秒数", "player_doubletap_seek_seconds", "", "10"))
        sectionList.add(SettingSection("switch", "洗脑循环", "player_loop", "", "false"))
        sectionList.add(SettingSection("switch", "熄屏继续播放", "player_background", "", "false"))
        sectionList.add(SettingSection("switch", "默认横屏", "player_autolandscape", "", "false"))
        sectionList.add(SettingSection("switch", "从历史位置播放", "player_from_last",
                getString(R.string.desc_fromlast),
                "true"))
        sectionList.add(SettingSection("switch", "显示实时人数", "player_show_online",
                getString(R.string.desc_showonline),
                "false"))
        sectionList.add(SettingSection("switch", "听视频模式", "player_audio_only",
                getString(R.string.desc_audio_only), "false"))
        sectionList.add(SettingSection("switch", "视频可缩放", "player_scale",
                getString(R.string.desc_scale), "true"))
        sectionList.add(SettingSection("switch", "缩放时可移动", "player_doublemove",
                getString(R.string.desc_doublemove),
                "true"))

        sectionList.add(SettingSection("divider", "", "", "", ""))

        sectionList.add(SettingSection("choose", "显示方式", "player_display",
                getString(R.string.desc_display),
                "false",
                arrayOf("TextureView", "SurfaceView")))
        sectionList.add(SettingSection("choose", "解码方式", "player_codec",
                getString(R.string.desc_videocodec), "true",
                arrayOf("硬件解码", "软件解码")))
        sectionList.add(SettingSection("choose", "音频输出", "player_audio",
                getString(R.string.desc_audiocodec), "false",
                arrayOf("OpenSles", "AudioTrack")))
        sectionList.add(SettingSection("switch", "低延迟优先", "player_low_latency",
                getString(R.string.desc_low_latency), "false"))

        sectionList.add(SettingSection("divider", "", "", "", ""))

        sectionList.add(SettingSection("switch", "显示高能进度条", "player_high_energy",
                getString(R.string.desc_player_high_energy), "false"))
        sectionList.add(SettingSection("switch", "弹幕允许重叠", "player_danmaku_allowoverlap", "", "true"))
        sectionList.add(SettingSection("switch", "合并重复弹幕", "player_danmaku_mergeduplicate", "",
                "false"))
        sectionList.add(SettingSection("switch", "强制为滚动弹幕", "player_danmaku_forceR2L",
                getString(R.string.desc_danmaku_force_r2l), "false"))
        sectionList.add(SettingSection("switch", "显示直播弹幕发送者", "player_danmaku_showsender",
                getString(R.string.desc_danmaku_showsender), "true"))
        sectionList.add(SettingSection("input_int", "弹幕最大行数", "player_danmaku_maxline", "", "10"))
        sectionList.add(SettingSection("input_float", "弹幕字号大小", "player_danmaku_size", "", "0.7"))
        sectionList.add(SettingSection("input_float", "弹幕不透明度", "player_danmaku_transparency", "",
                "0.5"))
        sectionList.add(SettingSection("input_float", "弹幕速度", "player_danmaku_speed", "", "1.0"))
        sectionList.add(SettingSection("input_int", "弹幕刷新间隔(ms)", "player_danmaku_refreshrate",
                getString(R.string.desc_danmaku_refreshrate), "33"))
        sectionList.add(SettingSection("switch", "弹幕温控", "player_danmaku_thermal",
                getString(R.string.desc_danmaku_thermal), "false"))

        sectionList.add(SettingSection("divider", "", "", "", ""))

        sectionList.add(SettingSection("switch", "自动弹出字幕选择", "player_subtitle_autoshow",
                getString(R.string.desc_subtitle_autoshow), "true"))
        sectionList.add(SettingSection("switch", "允许仅AI字幕", "player_subtitle_ai_allowed",
                getString(R.string.desc_subtitle_ai_allowed), "false"))

        sectionList.add(SettingSection("divider", "", "", "", ""))

        sectionList.add(SettingSection("input_float", "字幕校准", "player_subtitle_delta",
                "将字幕提前/退后一段时间，从而与视频对齐", "0.3"))

        sectionList.add(SettingSection("divider", "", "", "", ""))

        sectionList.add(SettingSection("switch", "显示旋转按钮", "player_ui_showRotateBtn", "", "true"))
        sectionList.add(SettingSection("switch", "显示弹幕按钮", "player_ui_showDanmakuBtn", "", "true"))
        sectionList.add(SettingSection("switch", "显示清晰度按钮", "player_ui_showQualityBtn",
                "", "true"))
        sectionList.add(SettingSection("switch", "显示分P按钮", "player_ui_showPageBtn",
                "", "true"))
        sectionList.add(SettingSection("input_float", "互动选项字体大小", "player_interaction_choice_size", "", "17.0"))

        recyclerView!!.setHasFixedSize(true)

        val adapter = SettingsAdapter(this, sectionList)
        setAdapter(adapter)

        setRefreshing(false)

    }

}
