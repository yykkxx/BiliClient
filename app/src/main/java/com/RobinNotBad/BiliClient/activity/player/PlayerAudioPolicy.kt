package com.RobinNotBad.BiliClient.activity.player

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.Locale

import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 播放器音频与倍速策略。
 *
 * ## 为什么要回到「一套 SoundTouch 管到底」
 *
 * v5.0.2-alpha10 ~ v5.0.4-alpha2 这段时间，倍速被改成了「双引擎」：
 * ≤2.0x 走 Android 框架的系统变速（AudioTrack PlaybackParams / Sonic），
 * >2.0x 走 App 层 SoundTouch，跨 2.0x 还要重建播放器换引擎，外加一个运行期校验器。
 * 这套方案的出发点是「系统变速咬字更清楚」，但真机上用户感受到的结果是：
 * **只要开倍速就吞字 / 咬字不清**，而且 2.0x 与 3.0x 之间频繁切换会被迫重载、重新缓冲。
 *
 * 对照取证（本轮）：把 ijkplayer 升级前后两版 .so 的符号表拉出来比对 ——
 * 旧版 FFmpeg 3.4 / `Lavf/57.83.100`、新版 FFmpeg 4.0 / `Lavf/58.12.100`，
 * 但**两版 SoundTouch 的符号集完全一致（都是 35 个 TDStretch 相关符号）**，
 * **两版 IjkMediaPlayer 的 JNI 符号集也完全一致（各 35 个，名字逐一相同）**。
 * 也就是说 ijkplayer 升级本身没有改变变速实现，「吞字」来自升级之后新增的这套引擎切换逻辑，
 * 而不是 .so。
 *
 * 所以这里回到**ijkplayer 升级前、播放器重构前**的做法，也就是用户实测「用着挺好」的那一套：
 * - `soundtouch=1` 恒开（时间伸缩交给 App 层，音频样本不丢），
 * - 倍速只走 [IjkMediaPlayer.setSpeed] 一条路径，0.5x ~ 3.0x 全档位共用，
 * - **不判定引擎、不校验、不重建播放器**，切档就是一次 setSpeed，零重缓冲。
 *
 * 主播放器（[PlayerActivity]）与短视频播放器
 * （activity/video/story/StoryPlaybackController）共用本类，避免两边各写一份选项导致分叉
 * （alpha8 就出过「注释写着与主播放器一致、实际 soundtouch 写成 0」的事故）。
 */
object PlayerAudioPolicy {

    /** 倍速档位上限：3.0x（用户明确要求保留） */
    const val QUALITY_MAX_SPEED = 3.0F

    /** 长按画面时的临时倍速 */
    const val LONG_PRESS_SPEED = 2.0F

    /**
     * 偏好键：双流高音质伴音轨（实验功能，**默认关**）。
     *
     * 为什么默认关：用户明确反馈「伴音轨额外占网络带宽，只要单音轨单文件」。
     * 关闭时主播放器播 durl 单文件（音视频混流、音轨 44kbps），**零额外连接、零额外带宽**；
     * 打开后才另起一路播 DASH AAC 音轨（204kbps），属于实验行为而非默认行为。
     */
    const val PREF_DASH_AUDIO = "player_dash_audio"

    /**
     * 历史偏好键：v5.0.3-alpha11 的「倍速算法」开关（**已从设置页移除，代码不再读取**）。
     *
     * 保留常量只为了说明存在过这个键（老用户机器上可能还留着 true）。
     * 变速算法现在恒定是 SoundTouch，用户不需要、也不能再选。
     */
    const val PREF_SPEED_SOUNDTOUCH_LEGACY = "player_speed_soundtouch"

    /**
     * 是否启用 DASH 高音质伴音轨（**默认开**）。
     *
     * 为什么默认开：这是「音质沉闷」的根治项。实测（2026-10-31，aid=80433022/cid=137649199）：
     * - durl（fnval=1）单文件里内嵌的音轨**恒为 44kbps HE-AAC（mp4a.40.5）**，qn=16/64 都一样；
     * - 同一视频的 DASH（fnval=16）音轨有三档：30216=44kbps / 30232=103kbps / 30280=204kbps，
     *   都是 mp4a.40.2（标准 AAC）。所以用户听到的「闷」就是 44kbps 那一档。
     *
     * 开启后：画面继续用 durl（长宽比/看门狗/回退逻辑全部不动），
     * 声音换由另起的单音轨播放器播 DASH 最高带宽 AAC（204kbps），并跟随主播放器时钟同步；
     * 任何一步失败都会立刻回退主播放器原声（绝不允许没声音）。
     * 代价是多下一份音频流量（约 204kbps），用户已知悉并接受 —— 音质优先。
     */
    @JvmStatic
    fun dashAudioEnabled(): Boolean = SharedPreferencesUtil.getBoolean(PREF_DASH_AUDIO, true)

    /** 倍速档位（主播放器与短视频共用） */
    val SPEED_VALUES = floatArrayOf(0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 1.75F, 2.0F, 3.0F)

    /** 主播放器的档位文案（形如 "x 1.5"） */
    val SPEED_TEXTS: Array<String> = Array(SPEED_VALUES.size) { "x " + speedNumber(SPEED_VALUES[it]) }

    /** 短视频播放器的档位文案（形如 "1.5x"） */
    val SPEED_LABELS: Array<String> = Array(SPEED_VALUES.size) { speedNumber(SPEED_VALUES[it]) + "x" }

    /** 把可能越界的历史档位索引夹到合法范围 */
    fun clampIndex(index: Int): Int = index.coerceIn(0, SPEED_VALUES.size - 1)

    /** 取索引对应的倍速，越界自动夹取 */
    fun speedAt(index: Int): Float = SPEED_VALUES[clampIndex(index)]

    /** 把任意倍速夹到策略允许范围（调用方可能来自持久化或长按常量） */
    fun clampSpeed(speed: Float): Float = speed.coerceIn(SPEED_VALUES[0], QUALITY_MAX_SPEED)

    /**
     * prepare 之前设置音频选项（**必须在 setDataSource / prepareAsync 之前**）。
     *
     * 这里只做一件事：**恒定开启 SoundTouch**。时间伸缩由 ijk 内部的 SoundTouch
     * （TDStretch）完成，音频样本不丢，因此 0.5x~3.0x 全档位都用同一条路径，
     * 不存在「换引擎」这件事。
     *
     * @param player 目标播放器
     * @param speed 本次播放即将使用的倍速（仅用于日志；引擎不再随倍速变化）
     * @param openslesPref 用户是否在设置里选了 OpenSL ES 输出（原样尊重，不再强制 AudioTrack）
     */
    @JvmStatic
    fun applyPrepareOptions(player: IjkMediaPlayer, speed: Float, openslesPref: Boolean) {
        // 输出后端按用户设置（旧版行为），SoundTouch 恒开。
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles",
                if (openslesPref) 1L else 0L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L)
        Logu.v("音频策略", "prepare soundtouch=1 speed=" + speed)
    }

    /**
     * 运行中应用倍速（所有 setSpeed 调用点的统一入口）。
     *
     * 与旧版一致：夹取后直接下发，不做引擎判定、不做重建。
     *
     * @return 实际下发的倍速（已夹取）
     */
    @JvmStatic
    fun applySpeed(player: IjkMediaPlayer, speed: Float): Float {
        val clamped = clampSpeed(speed)
        try {
            player.setSpeed(clamped)
        } catch (e: Exception) {
            Logu.d("音频策略", "设置倍速失败：" + e.message)
        }
        return clamped
    }

    /**
     * 缓冲/拉流策略，**必须在 prepare 之前调用**（运行中改会与读线程的门控状态打架）。
     *
     * 与旧版一致：完全按用户的脉冲式开关走，**不因倍速改变**。
     * 倍速下音频包消耗更快，但缓冲区本来就按设备内存给了 20MB~40MB，
     * 这在旧版（用户认为「用着挺好」的那一版）就是这个配置。
     *
     * @param speed 本次播放的初始倍速（仅用于日志）
     * @param pulseEnabled 用户是否在实验室里开了脉冲式拉流
     * @return 实际是否启用脉冲式（供调用方决定是否补 reconnect_delay_max）
     */
    @JvmStatic
    fun applyBufferingPolicy(player: IjkMediaPlayer, speed: Float, pulseEnabled: Boolean): Boolean {
        // 脉冲式：缓冲到该帧数之前不再读网络（read_thread 的门控阈值），
        // 同时关掉 packet-buffering（缺包直接填静音、不暂停等缓冲）—— 这是旧版的原始语义。
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering",
                if (pulseEnabled) 0L else 1L)
        if (pulseEnabled) {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 600L)
        }
        Logu.v("音频策略", "拉流 脉冲式=" + pulseEnabled + " speed=" + speed)
        return pulseEnabled
    }

    /** 数字文案：0.5 记为 "0.5"，0.75 记为 "0.75"，1.0 记为 "1.0" */
    private fun speedNumber(value: Float): String =
            if (Math.abs(value * 10 - Math.round(value * 10)) < 0.01F)
                String.format(Locale.US, "%.1f", value)
            else
                String.format(Locale.US, "%.2f", value)
}
