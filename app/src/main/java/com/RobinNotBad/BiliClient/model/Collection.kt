package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable
import java.util.ArrayList

class Collection : Parcelable, Serializable {
    @JvmField var id: Int = 0
    @JvmField var title: String? = null
    @JvmField var cover: String? = null
    @JvmField var intro: String? = null
    @JvmField var mid: Long = 0
    @JvmField var sections: MutableList<Section> = ArrayList()
    @JvmField var cards: MutableList<VideoCard> = ArrayList()
    @JvmField var view: String? = null

    class Section : Parcelable, Serializable {
        @JvmField var season_id: Int = 0
        @JvmField var id: Int = 0
        @JvmField var title: String? = null
        @JvmField var type: Int = 0
        @JvmField var episodes: MutableList<Episode> = ArrayList()

        constructor()

        protected constructor(`in`: Parcel) {
            season_id = `in`.readInt()
            id = `in`.readInt()
            title = `in`.readString()
            type = `in`.readInt()
            episodes = `in`.createTypedArrayList(Episode.CREATOR) ?: ArrayList()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(season_id)
            dest.writeInt(id)
            dest.writeString(title)
            dest.writeInt(type)
            dest.writeTypedList(episodes)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<Section> = object : Parcelable.Creator<Section> {
                override fun createFromParcel(`in`: Parcel): Section = Section(`in`)

                override fun newArray(size: Int): Array<Section?> = arrayOfNulls(size)
            }
        }
    }

    class Episode : Parcelable, Serializable {
        @JvmField var season_id: Int = 0
        @JvmField var section_id: Int = 0
        @JvmField var id: Long = 0
        @JvmField var aid: Long = 0
        @JvmField var cid: Long = 0
        @JvmField var title: String? = null
        @JvmField var bvid: String? = null
        @JvmField var arc: VideoInfo? = null

        constructor()

        protected constructor(`in`: Parcel) {
            season_id = `in`.readInt()
            section_id = `in`.readInt()
            id = `in`.readLong()
            aid = `in`.readLong()
            cid = `in`.readLong()
            title = `in`.readString()
            bvid = `in`.readString()
            arc = `in`.readParcelable(VideoInfo::class.java.classLoader)
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(season_id)
            dest.writeInt(section_id)
            dest.writeLong(id)
            dest.writeLong(aid)
            dest.writeLong(cid)
            dest.writeString(title)
            dest.writeString(bvid)
            dest.writeParcelable(arc, flags)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<Episode> = object : Parcelable.Creator<Episode> {
                override fun createFromParcel(`in`: Parcel): Episode = Episode(`in`)

                override fun newArray(size: Int): Array<Episode?> = arrayOfNulls(size)
            }
        }
    }

    constructor()

    protected constructor(`in`: Parcel) {
        id = `in`.readInt()
        title = `in`.readString()
        cover = `in`.readString()
        intro = `in`.readString()
        mid = `in`.readLong()
        sections = `in`.createTypedArrayList(Section.CREATOR) ?: ArrayList()
        cards = `in`.createTypedArrayList(VideoCard.CREATOR) ?: ArrayList()
        view = `in`.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(id)
        dest.writeString(title)
        dest.writeString(cover)
        dest.writeString(intro)
        dest.writeLong(mid)
        dest.writeTypedList(sections)
        dest.writeTypedList(cards)
        dest.writeString(view)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Collection> = object : Parcelable.Creator<Collection> {
            override fun createFromParcel(`in`: Parcel): Collection = Collection(`in`)

            override fun newArray(size: Int): Array<Collection?> = arrayOfNulls(size)
        }
    }
}
