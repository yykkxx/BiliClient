package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.util.ArrayList

class PlayerData : Parcelable {
    @JvmField var title: String? = ""
    @JvmField var videoUrl: String? = ""
    @JvmField var danmakuUrl: String? = ""
    @JvmField var qn: Int = -1
    @JvmField var qnStrList: Array<String?>? = null
    @JvmField var qnValueList: IntArray? = null
    @JvmField var aid: Long = 0
    @JvmField var cid: Long = 0
    @JvmField var mid: Long = 0
    @JvmField var progress: Int = 0
    @JvmField var cidHistory: Long = 0
    @JvmField var type: Int = 0
    @JvmField var timeStamp: Long = 0
    @JvmField var pagenames: ArrayList<String>? = null
    @JvmField var cids: ArrayList<Long>? = null
    @JvmField var currentPageIndex: Int = 0
    @JvmField var dashData: DashData? = null // DASH格式数据
    @JvmField var audioUrl: String? = "" // 单独的音频URL（用于仅音频下载）

    constructor()

    constructor(type: Int) {
        this.type = type
    }

    protected constructor(`in`: Parcel) {
        title = `in`.readString()
        videoUrl = `in`.readString()
        danmakuUrl = `in`.readString()
        qn = `in`.readInt()
        qnStrList = `in`.createStringArray()
        qnValueList = `in`.createIntArray()
        aid = `in`.readLong()
        cid = `in`.readLong()
        mid = `in`.readLong()
        progress = `in`.readInt()
        type = `in`.readInt()
        timeStamp = `in`.readLong()
        pagenames = `in`.createStringArrayList()
        val cidsList = ArrayList<Long>()
        `in`.readList(cidsList, Long::class.java.classLoader)
        cids = cidsList
        currentPageIndex = `in`.readInt()
        audioUrl = `in`.readString()
        // dashData不序列化，下载时会重新获取
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(title)
        dest.writeString(videoUrl)
        dest.writeString(danmakuUrl)
        dest.writeInt(qn)
        dest.writeStringArray(qnStrList)
        dest.writeIntArray(qnValueList)
        dest.writeLong(aid)
        dest.writeLong(cid)
        dest.writeLong(mid)
        dest.writeInt(progress)
        dest.writeInt(type)
        dest.writeLong(timeStamp)
        dest.writeStringList(pagenames)
        dest.writeList(cids)
        dest.writeInt(currentPageIndex)
        dest.writeString(audioUrl)
        // dashData不序列化，下载时会重新获取
    }

    fun isVideo(): Boolean = type == TYPE_VIDEO

    fun isBangumi(): Boolean = type == TYPE_BANGUMI

    fun isLive(): Boolean = type == TYPE_LIVE

    fun isLocal(): Boolean = type == TYPE_LOCAL

    companion object {
        @JvmField
        var TYPE_VIDEO: Int = 0

        @JvmField
        var TYPE_BANGUMI: Int = 1

        @JvmField
        var TYPE_LIVE: Int = 2

        @JvmField
        var TYPE_LOCAL: Int = 4

        @JvmField
        val CREATOR: Parcelable.Creator<PlayerData> = object : Parcelable.Creator<PlayerData> {
            override fun createFromParcel(`in`: Parcel): PlayerData = PlayerData(`in`)

            override fun newArray(size: Int): Array<PlayerData?> = arrayOfNulls(size)
        }
    }
}
