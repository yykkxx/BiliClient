package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import java.io.Serializable
import java.util.ArrayList

class VideoInfo : Parcelable, Serializable { // 自定义类需要加这个才能传输

    @JvmField var bvid: String? = null
    @JvmField var aid: Long = 0
    @JvmField var title: String? = null
    @JvmField var staff: ArrayList<UserInfo>? = ArrayList() // UP主列表
    @JvmField var cover: String? = null
    @JvmField var description: String? = null
    @JvmField var duration: String? = null
    @JvmField var stats: Stats? = null
    @JvmField var timeDesc: String? = null
    @JvmField var pagenames: ArrayList<String>? = ArrayList()
    @JvmField var cids: ArrayList<Long>? = ArrayList()
    @JvmField var descAts: MutableList<At>? = ArrayList()

    @JvmField var upowerExclusive: Boolean = false // 充电专属
    @JvmField var argueMsg: String? = null // 争议信息
    @JvmField var isCooperation: Boolean = false // 联合投稿
    @JvmField var isSteinGate: Boolean = false // 互动视频
    @JvmField var is360: Boolean = false // 全景视频

    @JvmField var epid: Long = 0 // 如果是番剧则不为空，应自动跳转
    @JvmField var copyright: Int = 0 // 是否转载
    @JvmField var collection: Collection? = null

    constructor()

    protected constructor(`in`: Parcel) {
        bvid = `in`.readString()
        aid = `in`.readLong()
        title = `in`.readString()
        staff = `in`.createTypedArrayList(UserInfo.CREATOR)
        cover = `in`.readString()
        description = `in`.readString()
        duration = `in`.readString()
        stats = `in`.readParcelable(Stats::class.java.classLoader)
        timeDesc = `in`.readString()
        pagenames = `in`.createStringArrayList()
        cids = ArrayList()
        `in`.readList(cids!!, Long::class.java.classLoader)
        descAts = `in`.createTypedArrayList(At.CREATOR)
        upowerExclusive = `in`.readByte() != 0.toByte()
        argueMsg = `in`.readString()
        isCooperation = `in`.readByte() != 0.toByte()
        isSteinGate = `in`.readByte() != 0.toByte()
        is360 = `in`.readByte() != 0.toByte()
        epid = `in`.readLong()
        copyright = `in`.readInt()
        collection = `in`.readParcelable(Collection::class.java.classLoader)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(bvid)
        dest.writeLong(aid)
        dest.writeString(title)
        dest.writeTypedList(staff)
        dest.writeString(cover)
        dest.writeString(description)
        dest.writeString(duration)
        dest.writeParcelable(stats, flags)
        dest.writeString(timeDesc)
        dest.writeStringList(pagenames)
        dest.writeList(cids)
        dest.writeTypedList(descAts)
        dest.writeByte((if (upowerExclusive) 1 else 0).toByte())
        dest.writeString(argueMsg)
        dest.writeByte((if (isCooperation) 1 else 0).toByte())
        dest.writeByte((if (isSteinGate) 1 else 0).toByte())
        dest.writeByte((if (is360) 1 else 0).toByte())
        dest.writeLong(epid)
        dest.writeInt(copyright)
        dest.writeParcelable(collection, flags)
    }

    override fun describeContents(): Int = 0

    fun toCard(): VideoCard {
        return VideoCard(title, staff!!.get(0).name, StringUtil.toWan(stats!!.view.toLong()), cover, aid, bvid)
    }

    fun toPlayerData(index: Int): PlayerData {
        val data = PlayerData()
        data.aid = aid
        data.cid = cids!!.get(index)
        data.title = if (pagenames!!.size == 1) title else pagenames!!.get(index)
        data.mid = SharedPreferencesUtil.getLong("mid", 0)
        data.qn = SharedPreferencesUtil.getInt("play_qn", 16)
        data.pagenames = pagenames
        data.cids = cids
        data.currentPageIndex = index
        return data
    }

    companion object {
        @JvmField
        val COPYRIGHT_SELF: Int = 1

        @JvmField
        val COPYRIGHT_REPRINT: Int = 2

        @JvmField
        val CREATOR: Parcelable.Creator<VideoInfo> = object : Parcelable.Creator<VideoInfo> {
            override fun createFromParcel(`in`: Parcel): VideoInfo = VideoInfo(`in`)

            override fun newArray(size: Int): Array<VideoInfo?> = arrayOfNulls(size)
        }
    }
}
