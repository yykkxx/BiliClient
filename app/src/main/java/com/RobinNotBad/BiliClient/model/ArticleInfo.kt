package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable


class ArticleInfo : Parcelable, Serializable {
    @JvmField var id: Long = 0
    @JvmField var title: String? = null
    @JvmField var summary: String? = null //摘要
    @JvmField var banner: String? = null //头图
    @JvmField var upInfo: UserInfo? = null
    @JvmField var ctime: Long = 0
    @JvmField var stats: Stats? = null
    @JvmField var wordCount: Int = 0 //字数
    @JvmField var keywords: String? = null
    @JvmField var content: String? = null //文章内容

    constructor()


    protected constructor(`in`: Parcel) {
        id = `in`.readLong()
        title = `in`.readString()
        summary = `in`.readString()
        banner = `in`.readString()
        upInfo = `in`.readParcelable(UserInfo::class.java.classLoader)
        ctime = `in`.readLong()
        stats = `in`.readParcelable(Stats::class.java.classLoader)
        wordCount = `in`.readInt()
        keywords = `in`.readString()
        content = `in`.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeString(title)
        dest.writeString(summary)
        dest.writeString(banner)
        dest.writeParcelable(upInfo, flags)
        dest.writeLong(ctime)
        dest.writeParcelable(stats, flags)
        dest.writeInt(wordCount)
        dest.writeString(keywords)
        dest.writeString(content)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ArticleInfo> = object : Parcelable.Creator<ArticleInfo> {
            override fun createFromParcel(`in`: Parcel): ArticleInfo = ArticleInfo(`in`)

            override fun newArray(size: Int): Array<ArticleInfo?> = arrayOfNulls(size)
        }
    }
}
