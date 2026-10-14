package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable

class VideoCard : Parcelable, Serializable {
    @JvmField var title: String? = null
    @JvmField var upName: String? = null
    @JvmField var view: String? = null
    @JvmField var cover: String? = null
    @JvmField var type: String? = "video"
    @JvmField var aid: Long = 0
    @JvmField var bvid: String? = null
    @JvmField var cid: Long = 0

    constructor(title: String?, upName: String?, view: String?, cover: String?, aid: Long, bvid: String?, type: String?) {
        this.title = title
        this.upName = upName
        this.view = view
        this.cover = cover
        this.aid = aid
        this.bvid = bvid
        this.type = type
    }

    constructor(title: String?, upName: String?, view: String?, cover: String?, aid: Long, bvid: String?, cid: Long?) {
        this.title = title
        this.upName = upName
        this.view = view
        this.cover = cover
        this.aid = aid
        this.bvid = bvid
        this.cid = cid!!
    }

    constructor(title: String?, upName: String?, view: String?, cover: String?, aid: Long, bvid: String?) {
        this.title = title
        this.upName = upName
        this.view = view
        this.cover = cover
        this.aid = aid
        this.bvid = bvid
    }

    constructor()

    protected constructor(`in`: Parcel) {
        title = `in`.readString()
        upName = `in`.readString()
        view = `in`.readString()
        cover = `in`.readString()
        type = `in`.readString()
        aid = `in`.readLong()
        bvid = `in`.readString()
        cid = `in`.readLong()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, i: Int) {
        parcel.writeString(title)
        parcel.writeString(upName)
        parcel.writeString(view)
        parcel.writeString(cover)
        parcel.writeString(type)
        parcel.writeLong(aid)
        parcel.writeString(bvid)
        parcel.writeLong(cid)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<VideoCard> = object : Parcelable.Creator<VideoCard> {
            override fun createFromParcel(`in`: Parcel): VideoCard = VideoCard(`in`)

            override fun newArray(size: Int): Array<VideoCard?> = arrayOfNulls(size)
        }
    }
}
