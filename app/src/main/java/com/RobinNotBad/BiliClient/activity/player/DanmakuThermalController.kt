package com.RobinNotBad.BiliClient.activity.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 弹幕帧率温控（#8）。
 *
 * 依据设备温度动态调整弹幕层重绘帧率，降低高温下的 GPU/CPU 开销：
 * - 温度 > 36℃  → 15 FPS（间隔 66ms）
 * - 32~36℃      → 30 FPS（间隔 33ms）
 * - 温度 < 32℃  → 60 FPS（间隔 16ms）
 * - 取不到温度   → 回退默认 30 FPS（33ms）
 *
 * 开关（“弹幕温控”）打开后忽略用户在设置里填写的弹幕刷新间隔；
 * 关闭时完全沿用原设置项行为。
 */
class DanmakuThermalController(
    private val context: Context,
    /** 采样回调：参数为重绘间隔毫秒 */
    private val onIntervalChanged: (Int) -> Unit
) {

    companion object {
        /** 温控开关设置键 */
        const val PREF_THERMAL_ENABLE = "player_danmaku_thermal"

        /** 采样周期：温度变化不快，10 秒足够且开销可忽略 */
        private const val SAMPLE_INTERVAL_MS = 10_000L

        const val INTERVAL_FPS_60 = 16
        const val INTERVAL_FPS_30 = 33
        const val INTERVAL_FPS_15 = 66

        private const val TEMP_HOT = 36f
        private const val TEMP_WARM = 32f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** 最近一次算出的间隔，避免重复回调 */
    private var lastInterval = -1

    private val sampleTask = object : Runnable {
        override fun run() {
            if (!running) return
            applyTemperature()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    /** 是否启用温控。关闭时调用方应继续使用用户设置的固定间隔。 */
    fun isEnabled(): Boolean =
        SharedPreferencesUtil.getBoolean(PREF_THERMAL_ENABLE, false)

    fun start() {
        if (!isEnabled()) return
        running = true
        handler.removeCallbacks(sampleTask)
        handler.post(sampleTask)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(sampleTask)
    }

    /**
     * 读取一次设备温度并按区间换算重绘间隔。
     *
     * 温度来源优先级：
     * 1. 电池温度（ACTION_BATTERY_CHANGED 粘性广播的 EXTRA_TEMPERATURE，单位 0.1℃）；
     * 2. PowerManager 热状态（部分设备不提供电池温度，只能分级，用作兜底）。
     * 两者都拿不到时回退默认 30 FPS。
     */
    fun currentInterval(): Int {
        val temperature = readTemperatureCelsius()
            ?: return INTERVAL_FPS_30

        return when {
            temperature > TEMP_HOT -> INTERVAL_FPS_15
            temperature >= TEMP_WARM -> INTERVAL_FPS_30
            else -> INTERVAL_FPS_60
        }
    }

    /**
     * 读取电池温度（摄氏度）。
     *
     * 注意：BatteryManager.BATTERY_PROPERTY_TEMPERATURE 并不是公开 API，
     * 电池温度只能通过 ACTION_BATTERY_CHANGED 的粘性广播读取
     * （EXTRA_TEMPERATURE，单位 0.1℃）。这里传 null 给 registerReceiver，
     * 只取粘性值、不注册监听，因此不存在泄漏。
     */
    private fun readTemperatureCelsius(): Float? {
        try {
            val intent: Intent? = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (intent != null) {
                val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tenths != Int.MIN_VALUE) {
                    return tenths / 10f
                }
            }
        } catch (e: Exception) {
            Logu.w("弹幕温控", "读取电池温度失败: " + e.message)
        }

        // 兜底：按系统的热状态分级（API 29+）
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                when (powerManager.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_SEVERE,
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> return TEMP_HOT + 1f

                    PowerManager.THERMAL_STATUS_MODERATE -> return TEMP_WARM + 1f
                }
            }
        } catch (e: Exception) {
            Logu.w("弹幕温控", "读取热状态失败: " + e.message)
        }

        return null
    }

    private fun applyTemperature() {
        val interval = currentInterval()
        if (interval == lastInterval) return
        lastInterval = interval
        onIntervalChanged(interval)
    }
}
