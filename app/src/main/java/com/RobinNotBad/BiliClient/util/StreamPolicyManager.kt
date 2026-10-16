package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.VideoCard

/**
 * 拉流策略（#11，实验室功能，替代原"高温预缓存"）。
 *
 * 播放视频时可选择两种策略：
 *
 * ① 慢速持续拉取（原行为）
 *    - ijkplayer 自带的 packet-buffering：边播边持续缓冲；
 *    - 优点：稳定，几乎不会断流；
 *    - 缺点：4G 下射频长时间工作，耗电、发热。
 *
 * ② 快速拉一大段 → 停止请求 → 循环（脉冲式缓冲）
 *    - 利用 ijkplayer 的 "min-frames"/"max-buffer-size" + "packet-buffering"
 *      组合：缓冲到上限后解析线程自然停住，播放消耗缓冲期间不再有网络请求，
 *      消耗到下限再快速补一段；
 *    - 优点：4G 射频间歇工作，不易发热；
 *    - 缺点：缓冲触底时可能短暂卡顿，稳定性不如①。
 *
 * 策略在播放器 setOption 阶段生效，运行中切换需重新进入播放页。
 */
object StreamPolicyManager {

    /** 实验室设置键：1=慢速持续（默认），2=脉冲式 */
    const val PREF_STREAM_POLICY = "lab_stream_policy"

    const val POLICY_CONTINUOUS = 1
    const val POLICY_PULSE = 2

    @JvmStatic
    fun policy(): Int = SharedPreferencesUtil.getInt(PREF_STREAM_POLICY, POLICY_CONTINUOUS)

    @JvmStatic
    fun isPulse(): Boolean = policy() == POLICY_PULSE

    /** 策略名称（设置页/提示用） */
    @JvmStatic
    fun name(): String = if (isPulse()) "脉冲式（快速拉一段→停→循环）" else "慢速持续拉取（默认）"
}
