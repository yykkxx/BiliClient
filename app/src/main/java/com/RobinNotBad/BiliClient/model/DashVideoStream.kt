package com.RobinNotBad.BiliClient.model

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList

/**
 * DASH视频流信息
 */
class DashVideoStream {
    @JvmField var id: Int = 0 // 清晰度代码
    @JvmField var baseUrl: String? = null // 默认流URL
    @JvmField var backupUrl: MutableList<String> = ArrayList() // 备用流URL
    @JvmField var bandwidth: Long = 0L // 所需最低带宽（Byte）
    @JvmField var mimeType: String? = null // 格式mimetype类型
    @JvmField var codecs: String? = null // 编码类型
    @JvmField var width: Int = 0 // 视频宽度（像素）
    @JvmField var height: Int = 0 // 视频高度（像素）
    @JvmField var frameRate: String? = null // 视频帧率
    @JvmField var codecid: Int = 0 // 码流编码标识代码

    constructor()

    companion object {
        /**
         * 从JSON对象解析视频流
         */
        @JvmStatic
        fun fromJson(json: JSONObject): DashVideoStream {
            val stream = DashVideoStream()
            stream.id = json.optInt("id", 0)
            stream.baseUrl = json.optString("baseUrl", json.optString("base_url", ""))
            stream.bandwidth = json.optLong("bandwidth", 0)
            stream.mimeType = json.optString("mimeType", json.optString("mime_type", ""))
            stream.codecs = json.optString("codecs", "")
            stream.width = json.optInt("width", 0)
            stream.height = json.optInt("height", 0)
            stream.frameRate = json.optString("frameRate", json.optString("frame_rate", ""))
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
         * 从JSON数组解析视频流列表
         */
        @JvmStatic
        fun fromJsonArray(jsonArray: JSONArray?): List<DashVideoStream> {
            val streams = ArrayList<DashVideoStream>()
            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    streams.add(fromJson(jsonArray.getJSONObject(i)))
                }
            }
            return streams
        }
    }
}
