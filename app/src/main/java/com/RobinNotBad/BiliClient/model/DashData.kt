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
    }

    /**
     * 获取指定清晰度的视频流
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
