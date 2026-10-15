package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.VideoCard

/**
 * 高温预缓存（#22，实验室功能）。
 *
 * 思路：设备温度已经偏高时，与其让射频/CPU 长时间零星工作（每次请求都要唤醒射频、
 * 反复握手，发热反而更持续），不如**短时间内集中把接下来要看的视频信息/图片缓存好**，
 * 然后一段时间内完全不用网络，让设备凉下来。
 *
 * 实现上：
 * 1. 高温（>36℃）时，一次性把推荐列表前若干条视频的 playurl 解析好；
 * 2. 记录“本次预缓存完成时间”，在设定的静默窗口内不再发起新的预缓存；
 * 3. 全程受实验室开关控制，默认关闭。
 */
object ThermalPrefetchManager {

    /** 实验室开关：高温时短时间大量缓存（#22） */
    const val PREF_THERMAL_PREFETCH = "lab_thermal_prefetch"

    /** 触发预缓存的温度阈值（摄氏度） */
    private const val TRIGGER_TEMP = 36f

    /** 预缓存条目数量上限（避免一次拉太多反而更热） */
    private const val MAX_PREFETCH_COUNT = 6

    /** 静默窗口：预缓存完成后这段时间内不再碰网络（毫秒） */
    private const val QUIET_WINDOW_MS = 10 * 60 * 1000L

    /** 上次完成预缓存的时间戳 */
    @Volatile
    private var lastPrefetchAt = 0L

    /** 是否启用 */
    fun isEnabled(): Boolean = SharedPreferencesUtil.getBoolean(PREF_THERMAL_PREFETCH, false)

    /**
     * 在合适的时机（推荐页加载完成后）尝试预缓存。
     *
     * @param list 当前列表（一般是推荐列表）
     */
    @JvmStatic
    fun tryPrefetch(context: Context, list: List<VideoCard>?) {
        if (!isEnabled() || list.isNullOrEmpty()) return

        // 静默窗口内不重复预缓存
        if (System.currentTimeMillis() - lastPrefetchAt < QUIET_WINDOW_MS) return

        val temperature = readTemperatureCelsius(context) ?: return
        if (temperature <= TRIGGER_TEMP) return

        lastPrefetchAt = System.currentTimeMillis()
        Logu.d("高温预缓存", "温度 " + temperature + "℃，开始集中预缓存")

        CenterThreadPool.run(Runnable {
            var count = 0
            for (card in list) {
                if (count >= MAX_PREFETCH_COUNT) break
                if (card.type != "video" || card.aid <= 0) continue
                try {
                    val playerData = PlayerData()
                    playerData.aid = card.aid
                    playerData.cid = card.cid
                    playerData.title = card.title
                    playerData.mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
                    playerData.qn = SharedPreferencesUtil.getInt("play_qn", 16)
                    // 只解析播放信息，不进播放页；解析结果进了 timeStamp 缓存，
                    // 用户真的点进去时可复用，等于省掉一次网络请求
                    PlayerApi.getVideo(playerData, false)
                    count++
                } catch (e: Exception) {
                    Logu.w("高温预缓存", "预缓存失败: " + e.message)
                }
            }
            Logu.d("高温预缓存", "预缓存完成，共 " + count + " 条，进入静默窗口")
        })
    }

    /** 供 UI 展示：距离下一次可预缓存还要多久（秒）。 */
    @JvmStatic
    fun quietRemainSeconds(): Long {
        val elapsed = System.currentTimeMillis() - lastPrefetchAt
        val remain = QUIET_WINDOW_MS - elapsed
        return if (remain > 0) remain / 1000 else 0
    }

    /** 手动清空静默窗口（调试/用户手动触发用）。 */
    @JvmStatic
    fun resetQuietWindow() {
        lastPrefetchAt = 0L
    }

    private fun readTemperatureCelsius(context: Context): Float? {
        return try {
            val intent: Intent? = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (intent == null) null
            else {
                val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tenths == Int.MIN_VALUE) null else tenths / 10f
            }
        } catch (e: Exception) {
            null
        }
    }
}
