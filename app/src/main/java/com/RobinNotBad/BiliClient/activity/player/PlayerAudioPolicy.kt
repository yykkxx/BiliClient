package com.RobinNotBad.BiliClient.activity.player

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.Locale

import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 播放器音频与倍速策略（v5.0.3-alpha11）。
 *
 * 为什么抽到这里：主播放器（[PlayerActivity]）与短视频播放器
 * （activity/video/story/StoryPlaybackController）各自写一份 ijk 选项，
 * alpha8 就出过「注释写着与主播放器一致、实际 soundtouch 写成 0」的分叉事故。
 * 音频相关的常量、档位与变速引擎判定从此只在这里维护一份。
 *
 * ## 本轮结论（完整证据链见 notes/player_audio_alpha11.md 第一节）
 *
 * 1. **「只要倍速就咬字不清」的主因是 App 层 SoundTouch 的时间拉伸**。
 *    ijk 在 `sdl_audio_callback` 里对每帧 PCM 调 `ijk_soundtouch_translate(handle, buf, rate, 1/rate, ...)`，
 *    该实现内部 `setPitch(1/rate)` + `setRate(rate)`，最终落到 TDStretch 的 `setTempo(rate)`
 *    （重叠相加 + 周期性跳段，`nominalSkip = tempo * (seekWindowLength - overlapLength)`）。
 *    TDStretch 的自动窗口参数只覆盖 0.5~2.0（`AUTOSEQ_TEMPO_TOP 2.0`），3.0x 被夹回 50ms 窗口后
 *    每帧只剩约三分之一原始波形经过重叠混合，摩擦音/爆破音被拼掉，听感就是「咬字不清」。
 *    1.0x 从头到尾不进这段代码（守卫是 `pf_playback_rate != 1.0f`），所以 1.0x 一直正常。
 *
 * 2. **关掉 soundtouch 后，倍速由 Android 框架做**（不是「不变速」）。
 *    上游 `sdl_audio_callback` 的守卫是 `if (!ffp->soundtouch_enable) SDL_AoutSetPlaybackRate(...)`；
 *    AudioTrack 后端把该调用落到 `AudioTrack.setPlaybackParams(PlaybackParams.speed)`
 *    （系统 Sonic 时间伸缩，App 层不丢样本），
 *    而 **OpenSL ES 后端的 `func_set_playback_rate` 是 NULL**，走 OpenSL 时该调用被静默丢弃，
 *    表现就是「设了倍速但语速不变」。因此「系统变速」引擎下**强制 AudioTrack**（opensles=0）。
 *
 * 3. **引擎只能在 prepare 之前决定**（这条与最初设想不同，是按源码更正的）：
 *    `soundtouch` 是 ffp 选项，`ffp->soundtouch_enable` 只在 `ffp_prepare_async_l` 里
 *    经 `av_opt_set_dict(ffp, &ffp->player_opts)` 消费一次；运行期 `setOption` 只是改字典
 *    （`ijkplayer.c` 的 `ijkmp_set_option` → `ffp_set_option` → `av_dict_set`），**不会**改这个字段。
 *    同时 SoundTouch 句柄只在 `stream_open` 且 `soundtouch_enable=1` 时创建。
 *    所以「运行中切 soundtouch」在本 .so 上是无效操作，本策略改为：
 *    **prepare 前按「即将使用的倍速」定引擎**，运行中要换引擎由页面层重建播放器
 *    （代价一次重缓冲，只在跨 2.0x 边界时发生）。
 */
object PlayerAudioPolicy {

    /** 倍速档位上限：3.0x（用户明确要求保留） */
    const val QUALITY_MAX_SPEED = 3.0F

    /**
     * 「系统变速」（AudioTrack PlaybackParams）能真正生效的倍速上界 = **2.0x**。
     *
     * 这不是音质取舍，是平台硬限制（完整推导见 notes/player_speed_unified.md 第二节）：
     * AOSP `AudioTrack::setPlaybackRate` 会用 `isSampleRateSpeedAllowed_l(effectiveRate, effectiveSpeed)`
     * 校验「现有缓冲是否够快进」（`AudioTrack.cpp:1341` / `:3026-3045`，Android 10/11/13 一致），
     * 要求帧数随 speed **线性增长**：`k*(afFrameCount*speed + 2)`；
     * 而 ijk 建 AudioTrack 时只给了 `getMinBufferSize() * AUDIOTRACK_PLAYBACK_MAXSPEED`
     * （`android_audiotrack.c:203-214`，`:33` 该宏 = 2），即 `2*k*(afFrameCount + 2)`。
     * 两式相除得上限 `speed <= 2 + 2/afFrameCount`，与采样率/通道/设备均无关 —— 实测值
     * 2.0021（af=960）/ 2.0042（af=480）/ 2.0083（af=240）。
     * 超过它的 `setPlaybackRate` 返回 BAD_VALUE，Java 层抛 IllegalArgumentException，
     * 而 j4a 把异常吞掉（`AudioTrack.util.c` 的 `goto fail`）⇒ **静默失速**（设了倍速、语速不变）。
     * 所以 2.5x/3.0x 不可能走系统变速；要真正支持只能重编 .so 把该宏改大（需 NDK，本机没有）。
     */
    const val SYSTEM_ENGINE_MAX_SPEED = 2.0F

    /** 引擎：系统变速（AudioTrack PlaybackParams / Sonic，App 层不丢样本） */
    const val ENGINE_SYSTEM = 0

    /** 引擎：App 层 SoundTouch（TDStretch 时间拉伸，支持 3.0x 但咬字糊） */
    const val ENGINE_SOUNDTOUCH = 1

    /** 长按画面时的临时倍速 */
    const val LONG_PRESS_SPEED = 2.0F

    /**
     * 偏好键：双流高音质伴音轨（实验功能，**默认关**）。
     *
     * 为什么默认关：用户明确反馈「伴音轨额外占网络带宽，只要单音轨单文件」。
     * 关闭时主播放器播 durl 单文件（音视频混流、音轨 44kbps），**零额外连接、零额外带宽**；
     * 打开后才另起一路播 DASH AAC 音轨（204kbps），属于实验行为而非默认行为。
     * 键定义放在这里而不是 util/SharedPreferencesUtil，避免与并行任务冲突。
     */
    const val PREF_DASH_AUDIO = "player_dash_audio"

    /**
     * 历史偏好键：v5.0.3-alpha11 的「倍速算法」开关（**已从设置页移除，代码不再读取**）。
     *
     * 保留常量只为两件事：一是说明存在过这个键（老用户机器上可能还留着 true），
     * 二是升级说明里可以点名它已被忽略。引擎现在完全由 [engineFor] 内部判定，
     * 用户不需要、也不能再选 —— 这正是本轮「不用思考用哪个倍速模式」的要求。
     */
    const val PREF_SPEED_SOUNDTOUCH_LEGACY = "player_speed_soundtouch"

    /** 是否启用 DASH 高音质伴音轨（默认**关**：单音轨单文件，不重复下载） */
    @JvmStatic
    fun dashAudioEnabled(): Boolean = SharedPreferencesUtil.getBoolean(PREF_DASH_AUDIO, false)

    /**
     * 本进程内「系统变速实测无效」的标记。
     *
     * 由页面层的运行期校验在「设了倍速但进度没变快」时置位；
     * 置位后所有 prepare 直接选 SoundTouch，避免每次都撞一次哑火。
     */
    @Volatile
    private var systemEngineBroken = false

    @JvmStatic
    fun isSystemEngineBroken(): Boolean = systemEngineBroken

    @JvmStatic
    fun markSystemEngineBroken() {
        if (!systemEngineBroken) {
            systemEngineBroken = true
            Logu.w("音频策略", "系统变速实测无效（该机未响应 PlaybackParams.speed），改用 SoundTouch")
        }
    }

    /** 倍速档位（主播放器与短视频共用；恢复 3.0x，3.0x 由引擎判定自动切 SoundTouch） */
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
     * 该倍速应该用哪个引擎（**必须在 prepare 之前决定**）。唯一的判定入口。
     *
     * 规则（用户完全不用选，这就是「单一算法覆盖 0.5x~3.0x」的实现）：
     * 1. `speed > 2.0x` ⇒ SoundTouch。因为系统变速在 >2.0x 会被平台缓冲区校验拒绝并静默失速
     *    （见 [SYSTEM_ENGINE_MAX_SPEED] 的推导），而 TDStretch 没有倍速上限，是唯一真能跑 3.0x 的实现；
     * 2. `speed <= 2.0x` ⇒ 系统变速（AudioTrack PlaybackParams/Sonic，App 层不丢样本，咬字最清楚）；
     * 3. 若本进程实测过「系统变速在本机无效」([markSystemEngineBroken])，则一律 SoundTouch 兜底。
     */
    @JvmStatic
    fun engineFor(speed: Float): Int {
        if (speed > SYSTEM_ENGINE_MAX_SPEED + 0.001F) return ENGINE_SOUNDTOUCH
        if (systemEngineBroken) return ENGINE_SOUNDTOUCH
        return ENGINE_SYSTEM
    }

    /** 引擎的可读名字（日志/提示用） */
    @JvmStatic
    fun engineName(engine: Int): String =
            if (engine == ENGINE_SYSTEM) "系统变速" else "SoundTouch"

    /**
     * prepare 之前设置音频选项（**必须在 setDataSource / prepareAsync 之前**）。
     *
     * @param player 目标播放器
     * @param speed 本次播放即将使用的倍速（决定引擎）
     * @param openslesPref 用户是否在设置里选了 OpenSL ES 输出
     * @return 实际使用的引擎（页面层要记住它，运行中要换引擎就重建播放器）
     */
    @JvmStatic
    fun applyPrepareOptions(player: IjkMediaPlayer, speed: Float, openslesPref: Boolean): Int {
        val engine = engineFor(speed)
        if (engine == ENGINE_SYSTEM) {
            // 系统变速靠 SDL_AoutSetPlaybackRate，落到 AudioTrack 的 PlaybackParams；
            // OpenSL ES 的 func_set_playback_rate 是 NULL，必须强制 AudioTrack。
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 0L)
            if (openslesPref) {
                Logu.w("音频策略", "系统变速需要 AudioTrack 输出，已忽略设置里的 OpenSL ES")
            }
        } else {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles",
                    if (openslesPref) 1L else 0L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L)
        }
        Logu.v("音频策略", "prepare 引擎=" + engineName(engine) + " speed=" + speed)
        return engine
    }

    /**
     * 运行中应用倍速（所有 setSpeed 调用点的统一入口）。
     *
     * **注意**：这里不会尝试切换 `soundtouch` 选项，因为该选项只在 prepare 时被消费
     * （见类注释第 3 条），运行期 setOption 是不会生效的哑操作。
     * 要换引擎必须由页面层按 [engineFor] 与已知引擎比对后**重建播放器**。
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
     * 该倍速是否需要换引擎（页面层据此决定是否重建播放器）。
     *
     * 只有跨 2.0x 边界（2.0x ↔ 3.0x 档）才会为 true —— 这是 [SYSTEM_ENGINE_MAX_SPEED]
     * 那条平台限制的直接后果，不是实现偷懒；同引擎之间的任意切档（0.5x~2.0x 全档）都不会重载。
     */
    @JvmStatic
    fun needsEngineRebuild(currentEngine: Int, speed: Float): Boolean =
            engineFor(speed) != currentEngine

    /**
     * 缓冲/拉流策略，**必须在 prepare 之前调用**（运行中改会与读线程的门控状态打架）。
     *
     * 依据：`packet-buffering=0` 时 `ffp_toggle_buffering_l()` 第一行就 return，
     * 音频队列一空就往输出 `memset` 静音；倍速让音频包消耗翻倍，两者叠加就是「一句话少几个字」。
     *
     * @param speed 本次播放的初始倍速
     * @param pulseEnabled 用户是否在实验室里开了脉冲式拉流
     * @return 实际是否启用脉冲式（false 表示走了连续缓冲）
     */
    @JvmStatic
    fun applyBufferingPolicy(player: IjkMediaPlayer, speed: Float, pulseEnabled: Boolean): Boolean {
        if (speed > 1.001F) {
            if (pulseEnabled)
                Logu.v("音频策略", "倍速 x$speed 下不启用脉冲拉流，改用连续缓冲")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            // 倍速消耗更快：把 read_thread 的「够播」门槛调高，减少回调线程撞空队列的概率。
            // 该选项对应 ffp->dcc.min_frames（默认 50000，允许区间 2..50000）。
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 50000L)
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
