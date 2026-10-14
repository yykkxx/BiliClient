package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable
import java.util.ArrayList

class EmotePackage : Parcelable, Serializable {
    @JvmField var id: Int = 0
    @JvmField var text: String? = null
    @JvmField var url: String? = null
    @JvmField var type: Int = 0
    @JvmField var attr: Int = 0
    @JvmField var size: Int = 0
    @JvmField var item_id: Int = 0
    @JvmField var emotes: MutableList<Emote>? = ArrayList()
    @JvmField var permanent: Boolean = false

    constructor()

    protected constructor(`in`: Parcel) {
        id = `in`.readInt()
        text = `in`.readString()
        url = `in`.readString()
        type = `in`.readInt()
        attr = `in`.readInt()
        size = `in`.readInt()
        item_id = `in`.readInt()
        emotes = `in`.createTypedArrayList(Emote.CREATOR)
        permanent = `in`.readByte() != 0.toByte()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(id)
        dest.writeString(text)
        dest.writeString(url)
        dest.writeInt(type)
        dest.writeInt(attr)
        dest.writeInt(size)
        dest.writeInt(item_id)
        dest.writeTypedList(emotes)
        dest.writeByte((if (permanent) 1 else 0).toByte())
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<EmotePackage> = object : Parcelable.Creator<EmotePackage> {
            override fun createFromParcel(`in`: Parcel): EmotePackage {
                return EmotePackage(`in`)
            }

            override fun newArray(size: Int): Array<EmotePackage?> {
                return arrayOfNulls(size)
            }
        }
    }
}
