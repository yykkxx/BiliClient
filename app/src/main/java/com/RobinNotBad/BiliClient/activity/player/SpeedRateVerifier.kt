package com.RobinNotBad.BiliClient.activity.player

import android.os.Handler
import android.os.Looper

import com.RobinNotBad.BiliClient.util.Logu

/**
 * 「系统变速」是否真的生效的运行期校验（v5.0.3-alpha11）。
 *
 * ## 为什么需要它
 * 「系统变速」引擎（`soundtouch=0` + AudioTrack）依赖 ijk 调
 * `AudioTrack.setPlaybackParams(PlaybackParams.setSpeed(x))`。这条链路有三处不确定性：
 * 1. J4A 是通过 Java 层反射/接口调用，异常被 `__catchAll` 吞掉 —— 失败时**没有任何报错**，
 *    表现就是「倍速设了但语速没变」；
 * 2. 各机型 AudioTrack 对 `PlaybackParams.speed` 的合法区间不同，超范围可能被夹取或抛异常；
 * 3. 部分精简系统根本没有 Sonic 时间伸缩实现。
 *
 * 所以不能盲信：设成系统变速后实测「进度推进速率」，不对就立刻报告，
 * 由页面层标记该机不可用并重载成 SoundTouch（见 [PlayerAudioPolicy.markSystemEngineBroken]）。
 *
 * ## 量法
 * 倍速播放时 `getCurrentPosition()` 是按媒体时间推进的，所以
 *   observed = 位置增量(ms) / 墙钟增量(ms) ≈ 实际倍速。
 * 系统变速没生效时 observed ≈ 1.0（还是原速）。
 * 判据取 1.0 与目标倍速的一半处（`1 + (speed - 1) * 0.5`），
 * 这样既不会被起播抖动误判，也能在「完全没生效」时稳稳失败。
 *
 * 全部回调都在主线程 Handler 上，避免在 native 回调线程里做 UI 动作。
 */
class SpeedRateVerifier {

    companion object {
        private const val TAG = "倍速校验"

        /** 起速后先等这么久再取基准（躲开起播/切速瞬间的位置抖动） */
        private const val WARMUP_MS = 1500L

        /** 取基准后观察这么久 */
        private const val SAMPLE_MS = 2500L

        /** 位置增量至少要有这么多毫秒才算样本有效（防止暂停/卡顿导致分母为 0） */
        private const val MIN_WALL_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var running = false

    /**
     * 开始一次校验。
     *
     * @param speed 目标倍速（>1.05 才有意义）
     * @param positionProvider 取当前位置（毫秒）；返回负数表示「当前不适合采样」（未 prepared/已暂停/正在 seek）
     * @param onResult true=系统变速生效，false=没生效（或采样失败）
     */
    fun verify(speed: Float, positionProvider: () -> Long, onResult: (Boolean) -> Unit) {
        if (running) {
            Logu.v(TAG, "已有校验在进行，跳过")
            return
        }
        if (speed < 1.05F) {
            // 1.0x 附近无法区分「生效」与「没生效」（两者都应约等于 1），不采样
            return
        }
        running = true
        val threshold = 1.0F + (speed - 1.0F) * 0.5F
        handler.postDelayed({ startSample(speed, threshold, positionProvider, onResult) }, WARMUP_MS)
    }

    /** 页面退出时调用，避免回调打到已销毁的页面 */
    fun cancel() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun startSample(speed: Float, threshold: Float,
                            positionProvider: () -> Long, onResult: (Boolean) -> Unit) {
        if (!running) return
        val pos0 = positionProvider()
        if (pos0 < 0) {
            finish(null, onResult)
            return
        }
        val wall0 = System.currentTimeMillis()
        handler.postDelayed({
            if (!running) return@postDelayed
            val pos1 = positionProvider()
            if (pos1 < 0) {
                finish(null, onResult)
                return@postDelayed
            }
            val wall = System.currentTimeMillis() - wall0
            if (wall < MIN_WALL_MS) {
                finish(null, onResult)
                return@postDelayed
            }
            val observed = (pos1 - pos0).toFloat() / wall.toFloat()
            val ok = observed >= threshold
            Logu.v(TAG, "目标 x" + speed + " 实测 x" + observed + "（阈值 " + threshold + "）→ " +
                    if (ok) "生效" else "未生效")
            finish(ok, onResult)
        }, SAMPLE_MS)
    }

    private fun finish(ok: Boolean?, onResult: (Boolean) -> Unit) {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (ok == null) {
            Logu.d(TAG, "采样无效（未 prepared / 暂停 / 位置未推进），本次不下结论")
            return
        }
        onResult(ok)
    }
}
