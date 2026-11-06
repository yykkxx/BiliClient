package com.RobinNotBad.BiliClient.player

import android.content.Context

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 播放器引擎工厂（5.1.2 双引擎，见 notes/player_lib_512.md）。
 *
 * 全仓库的 IjkMediaPlayer 构造点统一改走这里：
 * - 默认（偏好关闭）：IjkPlayer——与 5.1.1 完全一致的 loadLibrariesOnce + new IjkMediaPlayer()
 *   路径，loadLibrariesOnce 幂等（已加载时直接返回），默认分支没有其他任何逻辑；
 * - 偏好开启：Media3Player 实验内核（差异全部封装在适配器内）。
 *
 * 切换在「下次创建播放器」时生效：点播进页/切清晰度/切 P/短视频切换都会新建实例，
 * 因此对用户来说就是"下次播放生效"。
 */
object PlayerFactory {

    /**
     * 用户是否**选择**了 Media3 内核（不做任何运行期判断，纯读偏好）。
     *
     * 存在的意义：`PlayerActivity` 需要知道「当前是不是 media3」来决定要不要启用
     * 「失败自动切 ijk」的兜底 —— 而它不能自己读偏好（那会让「内核判断」散成两处）。
     */
    @JvmStatic
    fun isMedia3Selected(): Boolean =
        SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PLAYER_MEDIA3, false)

    /**
     * 强制创建一个 ijk 播放器（**忽略偏好**）。
     *
     * 用途只有一个：Media3 起播失败时**兜底重试**。
     * 为什么必须绕过偏好：此时用户的偏好仍然是「用 Media3」，但我们已知这台设备/
     * 这个视频用 Media3 播不了 —— 偏好是"意向"，能不能播是"事实"，事实优先。
     * 用户下次正常打开别的视频仍按偏好走 Media3，不会被这一次兜底永久改变。
     */
    @JvmStatic
    fun createIjkFallback(): IMediaPlayer {
        IjkMediaPlayer.loadLibrariesOnce(null)
        return IjkMediaPlayer()
    }

    @JvmStatic
    fun create(context: Context): IMediaPlayer {
        return if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PLAYER_MEDIA3, false)) {
            Media3Player(context)
        } else {
            IjkMediaPlayer.loadLibrariesOnce(null)
            IjkMediaPlayer()
        }
    }
}
