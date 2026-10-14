package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable

class ArticleCard : Parcelable, Serializable {
    @JvmField var title: String? = null
    @JvmField var id: Long = 0
    @JvmField var cover: String? = null
    @JvmField var upName: String? = null
    @JvmField var view: String? = null

    constructor()

    constructor(title: String?, id: Long, cover: String?, upName: String?, view: String?) {
        this.title = title
        this.id = id
        this.cover = cover
        this.upName = upName
        this.view = view
    }

    protected constructor(`in`: Parcel) {
        title = `in`.readString()
        id = `in`.readLong()
        cover = `in`.readString()
        upName = `in`.readString()
        view = `in`.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(title)
        dest.writeLong(id)
        dest.writeString(cover)
        dest.writeString(upName)
        dest.writeString(view)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ArticleCard> = object : Parcelable.Creator<ArticleCard> {
            override fun createFromParcel(`in`: Parcel): ArticleCard = ArticleCard(`in`)

            override fun newArray(size: Int): Array<ArticleCard?> = arrayOfNulls(size)
        }
    }
}
