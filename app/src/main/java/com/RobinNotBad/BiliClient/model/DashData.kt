package com.RobinNotBad.BiliClient.model

import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList

/**
 * DASH格式数据
 */
class DashData {
    @JvmField var duration: Int = 0 // 视频长度（秒）
    @JvmField var minBufferTime: Double = 0.0 // 最小缓冲时间
    @JvmSuppressWildcards @JvmField var videoStreams: List<DashVideoStream> = ArrayList() // 视频流列表
    @JvmSuppressWildcards @JvmField var audioStreams: List<DashAudioStream> = ArrayList() // 音频流列表
    @JvmField var dolbyAudio: DashAudioStream? = null // 杜比全景声音频
    @JvmField var flacAudio: DashAudioStream? = null // 无损音轨音频

    constructor()

    companion object {
        /**
         * 从JSON对象解析DASH数据
         */
        @JvmStatic
        fun fromJson(json: JSONObject): DashData {
            val dashData = DashData()
            dashData.duration = json.optInt("duration", 0)
            dashData.minBufferTime = json.optDouble("minBufferTime",
                    json.optDouble("min_buffer_time", 1.5))

            // 解析视频流
            dashData.videoStreams = DashVideoStream.fromJsonArray(json.optJSONArray("video"))

            // 解析音频流
            dashData.audioStreams = DashAudioStream.fromJsonArray(json.optJSONArray("audio"))

            // 解析杜比全景声
            val dolbyObj = json.optJSONObject("dolby")
            if (dolbyObj != null && dolbyObj.optInt("type", 0) > 0) {
                var dolbyAudioObj = dolbyObj.optJSONObject("audio")
                if (dolbyAudioObj == null && dolbyObj.has("audio") && !dolbyObj.isNull("audio")) {
                    // audio可能是数组，取第一个
                    dolbyAudioObj = dolbyObj.optJSONArray("audio").optJSONObject(0)
                }
                if (dolbyAudioObj != null) {
                    dashData.dolbyAudio = DashAudioStream.fromJson(dolbyAudioObj)
                }
            }

            // 解析无损音轨
            val flacObj = json.optJSONObject("flac")
            if (flacObj != null && flacObj.optBoolean("display", false)) {
                val flacAudioObj = flacObj.optJSONObject("audio")
                if (flacAudioObj != null) {
                    dashData.flacAudio = DashAudioStream.fromJson(flacAudioObj)
                }
            }

            return dashData
        }

        // ==================== 编码标识常量（v5.2.1 设备可解优先选流） ====================
        //
        // 这些值来自 B 站 DASH 接口的 `codecid` 字段，是**协议事实**而不是实现细节，
        // 所以放在 model 层（与 Android API 无关），调用方只负责「设备能不能解」的判定。

        /**
         * 编码优先级：AVC 最兼容 → HEVC 次之（多数中高端设备可硬解）→ AV1 最后（支持面最窄）。
         *
         * 注意 `0` 也是合法值（部分老接口/本地文件不返回 codecid），所以它单独排在最后，
         * 用「未知编码」对待 —— 不能因为不认识就直接丢掉。
         */
        /** AVC / H.264 */
        const val CODEC_AVC = 7

        /** HEVC / H.265 */
        const val CODEC_HEVC = 12

        /** AV1 */
        const val CODEC_AV1 = 13

        /** codecid 缺失时的占位（B 站部分接口不返回该字段） */
        const val CODEC_UNKNOWN = 0

        @JvmField
        val CODEC_PRIORITY = intArrayOf(CODEC_AVC, CODEC_HEVC, CODEC_AV1, CODEC_UNKNOWN)

        /**
         * `codecid` → `MediaCodecList` 里对应的 MIME 类型。
         *
         * 调用方用它查「设备能不能硬解」；映射放在 model 层是因为它描述的是
         * **B 站协议值的含义**，与 Android API 无关。
         * @return MIME；未知 codecid 返回 null（调用方应视为"不可判定"，按不支持处理）
         */
        @JvmStatic
        fun mimeOf(codecid: Int): String? = when (codecid) {
            CODEC_AVC -> "video/avc"
            CODEC_HEVC -> "video/hevc"
            CODEC_AV1 -> "video/av01"
            else -> null
        }

    }

    /**
     * 获取指定清晰度的视频流（**旧签名，行为一字不改**）。
     *
     * 只按 qn 匹配，找到第一个就返回；找不到返回第一个流（最高清晰度）。
     * ijk 内核、以及所有历史调用点都走这里，所以**不要**在这里加编码过滤 ——
     * ijk（ffmpeg 软解）能解 AV1/HEVC，加过滤反而会让它选到更差的流。
     */
    fun getVideoStream(qn: Int): DashVideoStream? {
        for (stream in videoStreams) {
            if (stream.id == qn) {
                return stream
            }
        }
        // 如果找不到，返回第一个（最高清晰度）
        return if (videoStreams.isEmpty()) null else videoStreams[0]
    }

    /**
     * 获取指定清晰度的视频流，**优先选设备能硬解的编码**（v5.2.1 / Media3 专用）。
     *
     * # 为什么需要这个重载（不是"顺手优化"，是修的 bug）
     * B 站 DASH 同一清晰度（同一个 `id`）会有**多条**流，按 `codecid` 区分：
     * `7` = AVC/H.264、`12` = HEVC/H.265、`13` = AV1。
     * 旧 [getVideoStream] 只按 `id` 匹配、返回**第一条**，而 B 站返回顺序通常是
     * AVC 在前，但不保证 —— 一旦命中 AV1/HEVC，不支持该编码的设备硬解就会
     * **黑屏有声音 / 完全不出画面**（编解码器初始化失败，但数据加载一切正常）。
     *
     * ijk 能播是因为 ffmpeg **软解**兜住了；Media3 没有集成 FFmpeg 扩展，
     * media3 自带的 `MediaCodecAudioRenderer/MediaCodecVideoRenderer` 只有硬解，
     * 硬解不支持就直接 `onPlayerError`。这正是用户说的「正常加载、有网速、有弹幕，但就是不能播」。
     *
     * # 选择策略（顺序即优先级）
     * 1. 在**同 qn** 的候选里，优先选 `avc`（H.264 兼容性最好，几乎所有设备都能硬解）；
     * 2. 没有 avc 就选设备支持的 `hevc`；
     * 3. 再退 `av1`；
     * 4. 同编码有多条（不同码率）时取 `bandwidth` 最高的一条（画质最好且设备扛得住）；
     * 5. **该 qn 一条都不可解** → 降级到「任何清晰度里第一个设备可解的流」；
     * 6. 全都不行 → 退化成旧行为（返回第一条），让 Media3 去报错、由上层兜底切 ijk。
     *
     * @param qn 想要的清晰度代码
     * @param codecSupported 编码是否被设备支持的回调（由调用方用 `MediaCodecList` 实现；
     *   放在参数里而不是本类直接查，是为了让 `model` 层不依赖 `android.media`，
     *   也便于单测与在非 Android 环境编译）。
     * @return 选中的流；`videoStreams` 为空时返回 null
     */
    fun getVideoStream(qn: Int, codecSupported: (codecid: Int) -> Boolean): DashVideoStream? {
        if (videoStreams.isEmpty()) return null
        // 1) 同 qn 里按编码优先级挑
        val sameQn = videoStreams.filter { it.id == qn }
        pickBest(sameQn, codecSupported)?.let { return it }
        // 2) 该 qn 完全不可解（或该 qn 不存在）→ 在全部流里找设备可解的
        pickBest(videoStreams, codecSupported)?.let { return it }
        // 3) 一条可解的都没有 → 退化成旧行为，让 Media3 报错、上层兜底切 ijk
        return videoStreams[0]
    }

    /**
     * 在一组候选流里按「avc → hevc → av1 → 其它」的优先级选，同编码取码率最高的。
     *
     * 为什么不是「取分辨率最高的」：调用方已经在 [getVideoStream] 里按 qn 圈定了清晰度，
     * 这里再按分辨率排序会破坏用户的选择（他点了 1080P，不该被换成 4K）。
     */
    private fun pickBest(
        candidates: List<DashVideoStream>,
        codecSupported: (Int) -> Boolean
    ): DashVideoStream? {
        for (preferred in CODEC_PRIORITY) {
            val matched = candidates
                .filter { it.codecid == preferred && codecSupported(it.codecid) }
                // 同编码多条（不同码率）：取码率最高的一条
                .maxByOrNull { it.bandwidth }
            if (matched != null) return matched
        }
        return null
    }

    /**
     * 获取最高质量的音频流
     */
    fun getBestAudioStream(): DashAudioStream? {
        // 优先返回无损音轨
        if (flacAudio != null) {
            return flacAudio
        }
        // 其次返回杜比全景声
        if (dolbyAudio != null) {
            return dolbyAudio
        }
        // 返回最高码率的普通音频流
        if (!audioStreams.isEmpty()) {
            var best = audioStreams[0]
            for (stream in audioStreams) {
                if (stream.bandwidth > best.bandwidth) {
                    best = stream
                }
            }
            return best
        }
        return null
    }

    /**
     * 是否有有效的音频流
     */
    fun hasAudio(): Boolean {
        return !audioStreams.isEmpty() || dolbyAudio != null || flacAudio != null
    }

    /**
     * 是否有有效的视频流
     */
    fun hasVideo(): Boolean {
        return !videoStreams.isEmpty()
    }
}
