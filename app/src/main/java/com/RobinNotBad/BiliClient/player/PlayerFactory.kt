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
