package com.RobinNotBad.BiliClient.activity.player

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.Locale

import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 播放器音频与倍速策略（v5.0.2-alpha10）。
 *
 * 为什么抽到这里：主播放器（[PlayerActivity]）与短视频播放器
 * （activity/video/story/StoryPlaybackController）各自写一份 ijk 选项，
 * alpha8 就出过「注释写着与主播放器一致、实际 soundtouch 写成 0」的分叉事故。
 * 音频相关的常量与判定从此只在这里维护一份。
 *
 * 三条结论的依据（完整证据链见 notes/player_audio_alpha10.md）：
 * 1. 上游 ff_ffplay.c 的守卫是「soundtouch_enable 且 pf_playback_rate 不等于 1.0」
 *    才会调用 ijk_soundtouch_translate，所以 1.0x 常开 soundtouch 不会产生任何处理，
 *    不必也不能按倍速去开关它（句柄只在 stream_open 创建，运行中切不安全）；
 * 2. packet-buffering 为 0 时 ffp_toggle_buffering_l 直接返回（不暂停等缓冲），
 *    音频队列一空就往输出填静音；倍速让音频包消耗翻倍，两者叠加就是「一句话少几个字」；
 * 3. SoundTouch 的 TDStretch 在 0.5~2.0 之外音质骤降，3.0x 会把音节成段丢掉。
 */
object PlayerAudioPolicy {

    /** 倍速档位上限，取 SoundTouch 稳定区间的上边界 */
    const val QUALITY_MAX_SPEED = 2.0F

    /** 长按画面时的临时倍速 */
    const val LONG_PRESS_SPEED = 2.0F

    /**
     * 偏好键：DASH 高音质伴音轨（实验功能，**默认开**）。
     * 为什么默认开：主播放器播的 durl 内嵌音轨恒为 44kbps HE-AAC（见 notes 第五节证据），
     * 那是「沉闷」的根因；DASH 音轨最高 204kbps，且任何失败都会自动回退主播放器原声。
     * 键定义放在这里而不是 util/SharedPreferencesUtil，避免与并行任务冲突。
     */
    const val PREF_DASH_AUDIO = "player_dash_audio"

    /** 是否启用 DASH 高音质伴音轨（默认开，播放器设置页可关） */
    @JvmStatic
    fun dashAudioEnabled(): Boolean = SharedPreferencesUtil.getBoolean(PREF_DASH_AUDIO, true)

    /** 倍速档位（主播放器与短视频共用；上限 2.0 的理由见类注释第 3 条） */
    val SPEED_VALUES = floatArrayOf(0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 1.75F, 2.0F)

    /** 主播放器的档位文案（形如 "x 1.5"） */
    val SPEED_TEXTS: Array<String> = Array(SPEED_VALUES.size) { "x " + speedNumber(SPEED_VALUES[it]) }

    /** 短视频播放器的档位文案（形如 "1.5x"） */
    val SPEED_LABELS: Array<String> = Array(SPEED_VALUES.size) { speedNumber(SPEED_VALUES[it]) + "x" }

    /** 把可能越界的历史档位索引夹到合法范围（旧版本有第 8 档 3.0x 的持久化进度） */
    fun clampIndex(index: Int): Int = index.coerceIn(0, SPEED_VALUES.size - 1)

    /** 取索引对应的倍速，越界自动夹取，永不返回 3.0 */
    fun speedAt(index: Int): Float = SPEED_VALUES[clampIndex(index)]

    /** 把任意倍速夹到策略允许范围（调用方可能来自持久化或长按常量） */
    fun clampSpeed(speed: Float): Float = speed.coerceIn(SPEED_VALUES[0], QUALITY_MAX_SPEED)

    /**
     * 设置 soundtouch，**必须在 prepare 之前调用**。
     *
     * 恒为 1：1.0x 时上游守卫让它根本不进 SoundTouch（类注释第 1 条），
     * 而大于 1.0x 时它是唯一保音调的变速实现，关掉会变调、听感更像吞字。
     */
    fun applySoundtouch(player: IjkMediaPlayer) {
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L)
    }

    /**
     * 缓冲/拉流策略，**必须在 prepare 之前调用**（运行中改会与读线程的门控状态打架）。
     *
     * @param speed 本次播放的初始倍速
     * @param pulseEnabled 用户是否在实验室里开了脉冲式拉流
     * @return 实际是否启用脉冲式（false 表示走了连续缓冲）
     */
    fun applyBufferingPolicy(player: IjkMediaPlayer, speed: Float, pulseEnabled: Boolean): Boolean {
        // 倍速一律用连续缓冲：脉冲式是「缺包填静音、不暂停」，倍速又把音频包消耗翻倍，
        // 两者叠加正是用户反馈的「一句话少几个字」（类注释第 2 条）。
        if (speed > 1.001F) {
            if (pulseEnabled)
                Logu.v("音频策略", "倍速 x$speed 下不启用脉冲拉流，改用连续缓冲")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            return false
        }
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering",
                if (pulseEnabled) 0L else 1L)
        if (pulseEnabled) {
            // 脉冲式：缓冲到该帧数之前不再读网络（read_thread 的门控阈值）
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 600L)
        }
        return pulseEnabled
    }

    /** 数字文案：0.5 记为 "0.5"，0.75 记为 "0.75"，1.0 记为 "1.0" */
    private fun speedNumber(value: Float): String =
        if (Math.abs(value * 10 - Math.round(value * 10)) < 0.01F)
            String.format(Locale.US, "%.1f", value)
        else
            String.format(Locale.US, "%.2f", value)
}
