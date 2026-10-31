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
        // staff / stats 缺失时不能崩：这几个字段在未登录、合作稿 staff 缺失、
        // 老版本缓存反序列化（Parcelable）等场景下都可能是 null 或空列表。
        val upName = staff?.firstOrNull()?.name ?: ""
        val viewText = StringUtil.toWan((stats?.view ?: 0).toLong())
        return VideoCard(title, upName, viewText, cover, aid, bvid)
    }

    fun toPlayerData(index: Int): PlayerData {
        val data = PlayerData()
        data.aid = aid
        // cids / pagenames 可能为空或长度对不上（老缓存反序列化、接口没给 pages、
        // 分P 列表被截断），老写法 cids!!.get(index) 会 NPE/越界 → 进播放器即闪退。
        // 这里夹到合法范围，最差退化成 0 号分P，至少还能播。
        val cidList = cids ?: ArrayList()
        val nameList = pagenames ?: ArrayList()
        val safeIndex = if (index in cidList.indices) index else 0
        data.cid = cidList.getOrElse(safeIndex) { 0L }
        data.title = if (nameList.size <= 1) title else nameList.getOrElse(safeIndex) { title }
        data.mid = SharedPreferencesUtil.getLong("mid", 0)
        data.qn = SharedPreferencesUtil.getInt("play_qn", 16)
        data.pagenames = pagenames
        data.cids = cids
        data.currentPageIndex = safeIndex
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
