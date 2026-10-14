package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import androidx.annotation.NonNull

class SubtitleLink : Parcelable {
    @JvmField var id: Long = 0
    @JvmField var isAI: Boolean = false
    @JvmField var lang: String? = null
    @JvmField var url: String? = null

    constructor(id: Long, lang: String?, url: String?, isAI: Boolean) {
        this.id = id
        this.lang = lang
        this.url = url
        this.isAI = isAI
    }

    protected constructor(`in`: Parcel) {
        id = `in`.readLong()
        isAI = `in`.readByte() != 0.toByte()
        lang = `in`.readString()
        url = `in`.readString()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeByte((if (isAI) 1 else 0).toByte())
        dest.writeString(lang)
        dest.writeString(url)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SubtitleLink> = object : Parcelable.Creator<SubtitleLink> {
            override fun createFromParcel(`in`: Parcel): SubtitleLink = SubtitleLink(`in`)

            override fun newArray(size: Int): Array<SubtitleLink?> = arrayOfNulls(size)
        }
    }
}
