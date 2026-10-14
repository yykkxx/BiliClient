package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import androidx.annotation.NonNull

import java.io.Serializable

class Series : Parcelable, Serializable {
    @JvmField var type: String? = "series"
    @JvmField var id: Int = 0
    @JvmField var title: String? = null
    @JvmField var cover: String? = null
    @JvmField var intro: String? = null
    @JvmField var mid: Long = 0
    @JvmField var total: String? = null

    constructor()

    protected constructor(`in`: Parcel) {
        type = `in`.readString()
        id = `in`.readInt()
        title = `in`.readString()
        cover = `in`.readString()
        intro = `in`.readString()
        mid = `in`.readLong()
        total = `in`.readString()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(type)
        dest.writeInt(id)
        dest.writeString(title)
        dest.writeString(cover)
        dest.writeString(intro)
        dest.writeLong(mid)
        dest.writeString(total)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Series> = object : Parcelable.Creator<Series> {
            override fun createFromParcel(`in`: Parcel): Series = Series(`in`)

            override fun newArray(size: Int): Array<Series?> = arrayOfNulls(size)
        }
    }
}
