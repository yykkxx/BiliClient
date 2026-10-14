package com.RobinNotBad.BiliClient.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList

/**
 * DASH音频流信息
 */
class DashAudioStream {
    @JvmField var id: Int = 0 // 音质代码
    @JvmField var baseUrl: String? = null // 默认流URL
    @JvmField var backupUrl: MutableList<String> = ArrayList() // 备用流URL
    @JvmField var bandwidth: Long = 0L // 所需最低带宽（Byte）
    @JvmField var mimeType: String? = null // 格式mimetype类型
    @JvmField var codecs: String? = null // 音频类型
    @JvmField var codecid: Int = 0 // 编码标识（音频流恒为0）

    constructor()

    companion object {
        /**
         * 从JSON对象解析音频流
         */
        @JvmStatic
        fun fromJson(json: JSONObject): DashAudioStream {
            val stream = DashAudioStream()
            stream.id = json.optInt("id", 0)
            stream.baseUrl = json.optString("baseUrl", json.optString("base_url", ""))
            stream.bandwidth = json.optLong("bandwidth", 0)
            stream.mimeType = json.optString("mimeType", json.optString("mime_type", ""))
            stream.codecs = json.optString("codecs", "")
            stream.codecid = json.optInt("codecid", 0)

            // 解析备用URL
            var backupUrlArray = json.optJSONArray("backupUrl")
            if (backupUrlArray == null) {
                backupUrlArray = json.optJSONArray("backup_url")
            }
            if (backupUrlArray != null) {
                for (i in 0 until backupUrlArray.length()) {
                    stream.backupUrl.add(backupUrlArray.optString(i))
                }
            }

            return stream
        }

        /**
         * 从JSON数组解析音频流列表
         */
        @JvmStatic
        fun fromJsonArray(jsonArray: JSONArray?): List<DashAudioStream> {
            val streams = ArrayList<DashAudioStream>()
            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    streams.add(fromJson(jsonArray.getJSONObject(i)))
                }
            }
            return streams
        }
    }

    /**
     * 获取描述信息（用于调试）
     */
    fun getDescription(): String {
        return String.format("ID:%d, codec:%s, bandwidth:%d",
                id, codecs, bandwidth)
    }

    /**
     * 获取音质名称
     */
    fun getQualityName(): String {
        return when (id) {
            30216 -> "64K"
            30232 -> "132K"
            30280 -> "192K"
            30250 -> "杜比全景声"
            30251 -> "Hi-Res无损"
            else -> "音频"
        }
    }
}
