package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.activity.player.PlayerDanmakuController
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter
import com.RobinNotBad.BiliClient.model.SettingSection
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList

open class SettingTerminalPlayerActivity : RefreshListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("内置播放器设置")

        val sectionList = ArrayList<SettingSection>()

        //默认清晰度（#4）：从主设置页移入播放器设置，Chip 高亮当前选中清晰度
        val qnNames = SettingQualityActivity.qnMap.keys.toTypedArray()
        val qnValues = SettingQualityActivity.qnMap.values.toIntArray()
        sectionList.add(SettingSection("multi_choose", "默认清晰度", "play_qn",
                "点按切换默认清晰度，选中的清晰度会高亮显示",
                "16", Pair(qnNames, qnValues)))

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
        // 5.1.2 双播放内核：ChooseHolder 语义 = 偏好 true → strings[0]（chocola）、
        // false → strings[1]（vanilla），默认值 false 必须与 strings[1] 的语义对齐，
        // 所以「IjkPlayer」放第二位（默认选中），「Media3（实验）」放第一位。
        sectionList.add(SettingSection("choose", "播放内核", SharedPreferencesUtil.PLAYER_MEDIA3,
                getString(R.string.desc_player_engine), "false",
                arrayOf("Media3（实验）", "IjkPlayer")))
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
        // #2：弹幕时间跳转开关。开启时弹幕里的时间戳会标成蓝色下划线，
        // 点击即可跳到该时间；关闭后既不标记也不响应点击。
        sectionList.add(SettingSection("switch", "弹幕时间跳转",
                PlayerDanmakuController.PREF_TIME_JUMP,
                getString(R.string.desc_danmaku_timejump), "true"))

        sectionList.add(SettingSection("divider", "", "", "", ""))

        sectionList.add(SettingSection("switch", "自动弹出字幕选择", "player_subtitle_autoshow",
                getString(R.string.desc_subtitle_autoshow), "true"))
        sectionList.add(SettingSection("switch", "允许仅AI字幕", "player_subtitle_ai_allowed",
                getString(R.string.desc_subtitle_ai_allowed), "false"))

        sectionList.add(SettingSection("divider", "", "", "", ""))

        // 字幕校准默认值 v5.3.0 从 0.3 改成 0（用户报障「字幕和视频不对应」的直接成因之一）。
        //
        // 为什么 0.3 是错的：B 站字幕的 from/to 已经是**绝对秒**，播放器里
        // `computeSubtitle(curr_sec + subtitle_delta)` 加正数等于让字幕**整体提前 300ms**。
        // 300ms 在字幕上是肉眼可见的错位（一句 2 秒的字幕，前 300ms 显示的是上一句）。
        // 这个 0.3 当初大概是某人手动校准后忘了归位留下的，对**没手动校准过的用户**
        // 它就是纯粹的偏置 —— 默认值必须是「不偏移」。
        //
        // 已经把 0.3 写进偏好的老用户不受影响（值还在），他们本来就是要那个偏移。
        sectionList.add(SettingSection("input_float", "字幕校准", "player_subtitle_delta",
                "将字幕提前/退后一段时间，从而与视频对齐（单位秒，正数=字幕提前；默认 0 不偏移）", "0.0"))

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
