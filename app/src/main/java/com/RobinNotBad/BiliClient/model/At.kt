package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable

class At : Parcelable, Serializable {
    @JvmField val id: Long
    @JvmField var start: Int = 0
    @JvmField var end: Int = 0
    @JvmField var name: String? = null

    constructor(id: Long, startIndex: Int, endIndex: Int) {
        this.id = id
        this.start = startIndex
        this.end = endIndex
    }

    constructor(id: Long, name: String?) {
        this.id = id
        this.name = name
    }

    protected constructor(`in`: Parcel) {
        id = `in`.readLong()
        start = `in`.readInt()
        end = `in`.readInt()
        name = `in`.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeInt(start)
        dest.writeInt(end)
        dest.writeString(name)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<At> = object : Parcelable.Creator<At> {
            override fun createFromParcel(`in`: Parcel): At = At(`in`)

            override fun newArray(size: Int): Array<At?> = arrayOfNulls(size)
        }
    }
}
