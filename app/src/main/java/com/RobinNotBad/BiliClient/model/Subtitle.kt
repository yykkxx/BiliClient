package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import androidx.annotation.NonNull

class Subtitle : Parcelable {
    @JvmField var content: String? = null
    @JvmField var from: Double = 0.0
    @JvmField var to: Double = 0.0

    constructor(content: String?, from: Double, to: Double) {
        this.content = content
        this.from = from
        this.to = to
    }

    protected constructor(`in`: Parcel) {
        content = `in`.readString()
        from = `in`.readDouble()
        to = `in`.readDouble()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(content)
        dest.writeDouble(from)
        dest.writeDouble(to)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Subtitle> = object : Parcelable.Creator<Subtitle> {
            override fun createFromParcel(`in`: Parcel): Subtitle = Subtitle(`in`)

            override fun newArray(size: Int): Array<Subtitle?> = arrayOfNulls(size)
        }
    }
}
