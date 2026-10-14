package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable

class Emote : Parcelable, Serializable {
    @JvmField var id: Int = 0
    @JvmField var packageId: Int = 0
    @JvmField var name: String? = null
    @JvmField var alias: String? = null
    @JvmField var url: String? = null
    @JvmField var size: Int = 0

    constructor()

    constructor(name: String?, url: String?, size: Int) {
        this.name = name
        this.url = url
        this.size = size
    }

    protected constructor(`in`: Parcel) {
        id = `in`.readInt()
        packageId = `in`.readInt()
        name = `in`.readString()
        alias = `in`.readString()
        url = `in`.readString()
        size = `in`.readInt()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(id)
        dest.writeInt(packageId)
        dest.writeString(name)
        dest.writeString(alias)
        dest.writeString(url)
        dest.writeInt(size)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Emote> = object : Parcelable.Creator<Emote> {
            override fun createFromParcel(`in`: Parcel): Emote {
                return Emote(`in`)
            }

            override fun newArray(size: Int): Array<Emote?> {
                return arrayOfNulls(size)
            }
        }
    }
}
