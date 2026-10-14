package com.RobinNotBad.BiliClient.model

import android.database.Cursor

import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.io.File

class DownloadSection {
    @JvmField var id: Long = 0L
    @JvmField var type: String? = null
    @JvmField var aid: Long = 0L
    @JvmField var cid: Long = 0L
    @JvmField var qn: Int = 0
    @JvmField var url_cover: String? = null
    @JvmField var title: String? = null
    @JvmField var child: String? = null
    @JvmField var name_short: String? = null
    @JvmField var state: String? = null
    @JvmField var downloadType: String? = null // 下载类型：video, audio_only
    @JvmField var audioUrl: String? = null // 音频流URL

    constructor()

    constructor(cursor: Cursor) {
        id = cursor.getInt(0).toLong()
        type = cursor.getString(1)
        state = cursor.getString(2)
        aid = cursor.getLong(3)
        cid = cursor.getLong(4)
        qn = cursor.getInt(5)
        title = cursor.getString(6)
        child = cursor.getString(7)
        url_cover = cursor.getString(8)

        val downloadTypeIndex = cursor.getColumnIndex("download_type")
        if (downloadTypeIndex != -1) {
            downloadType = cursor.getString(downloadTypeIndex)
        }
        if (downloadType.isNullOrEmpty()) {
            downloadType = "video"
        }

        val audioUrlIndex = cursor.getColumnIndex("audio_url")
        if (audioUrlIndex != -1) {
            audioUrl = cursor.getString(audioUrlIndex)
        }
        if (audioUrl == null) {
            audioUrl = ""
        }

        val sBuilder = StringBuilder()
        val titleStr = title!!
        sBuilder.append(titleStr.substring(0, Math.min(8, titleStr.length)))
        sBuilder.append(if (titleStr.length > 7) "..." else "")

        if (type == "video_multi") {
            sBuilder.append("-")
            val childStr = child!!
            sBuilder.append(childStr.substring(0, Math.min(8, childStr.length)))
            sBuilder.append(if (childStr.length > 7) "..." else "")
        }

        // 如果是仅音频下载，在名称后添加标识
        if ("audio_only" == downloadType) {
            sBuilder.append("[音频]")
        }

        name_short = sBuilder.toString()
    }

    fun getPath(): File {
        if (type!!.contains("video")) {
            val path = FileUtil.getVideoDownloadPath(title, child)
            if (!path.exists())
                path.mkdirs()
            return path
        } else
            return FileUtil.getPicturePath()
    }

    /**
     * 是否是仅音频下载
     */
    fun isAudioOnly(): Boolean {
        return "audio_only" == downloadType
    }

    fun toPlayerData(): PlayerData {
        val data = PlayerData()
        data.aid = aid
        data.cid = cid
        data.title = title
        data.qn = qn
        data.mid = SharedPreferencesUtil.getLong("mid", 0L)
        return data
    }
}
