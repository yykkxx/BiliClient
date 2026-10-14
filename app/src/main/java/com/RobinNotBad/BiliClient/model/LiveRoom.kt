package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import java.io.Serializable

class LiveRoom : Parcelable, Serializable {
    @JvmField var roomid: Long = 0
    @JvmField var short_id: Long = 0
    @JvmField var uid: Long = 0
    @JvmField var title: String? = null
    @JvmField var uname: String? = null
    @JvmField var tags: String? = null
    @JvmField var description: String? = null
    @JvmField var online: Int = 0
    @JvmField var attention: Int = 0
    @JvmField var user_cover: String? = null
    @JvmField var user_cover_flag: Int = 0
    @JvmField var system_cover: String? = null
    @JvmField var cover: String? = null
    @JvmField var keyframe: String? = null
    @JvmField var show_cover: String? = null
    @JvmField var face: String? = null
    @JvmField var area_parent_id: Int = 0
    @JvmField var area_parent_name: String? = null
    @JvmField var area_id: Int = 0
    @JvmField var area_name: String? = null
    @JvmField var session_id: String? = null
    @JvmField var group_id: Long = 0
    @JvmField var show_callback: String? = null
    @JvmField var click_callback: String? = null
    @JvmField var liveTime: String? = null
    @JvmField var live_status: Int = 0
    @JvmField var old_area_id: Int = 0
    @JvmField var background: String? = null
    @JvmField var is_portrait: Boolean = false
    @JvmField var room_silent_type: String? = null
    @JvmField var room_silent_level: Int = 0
    @JvmField var room_silent_second: Int = 0
    @JvmField var pk_status: Int = 0
    @JvmField var pk_id: Long = 0
    @JvmField var battle_id: Long = 0
    @JvmField var allow_change_area_time: Int = 0
    @JvmField var allow_upload_cover_time: Int = 0
    @JvmField var verify: Verify? = null
    @JvmField var watched_show: Watched? = null
    @JvmField var new_pendants: NewPendants? = null
    @JvmField var studio_info: StudioInfo? = null

    class Verify : Parcelable, Serializable {
        @JvmField var role: Int = 0
        @JvmField var desc: String? = null
        @JvmField var type: Int = 0

        constructor()

        protected constructor(`in`: Parcel) {
            role = `in`.readInt()
            desc = `in`.readString()
            type = `in`.readInt()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(role)
            dest.writeString(desc)
            dest.writeInt(type)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<Verify> = object : Parcelable.Creator<Verify> {
                override fun createFromParcel(`in`: Parcel): Verify = Verify(`in`)

                override fun newArray(size: Int): Array<Verify?> = arrayOfNulls(size)
            }
        }
    }

    class Watched : Parcelable, Serializable {
        @JvmField var isSwitch: Boolean = false
        @JvmField var num: Int = 0
        @JvmField var text_small: String? = null
        @JvmField var text_large: String? = null
        @JvmField var icon: String? = null
        @JvmField var icon_location: Int = 0
        @JvmField var icon_web: String? = null

        constructor()

        protected constructor(`in`: Parcel) {
            isSwitch = `in`.readByte() != 0.toByte()
            num = `in`.readInt()
            text_small = `in`.readString()
            text_large = `in`.readString()
            icon = `in`.readString()
            icon_location = `in`.readInt()
            icon_web = `in`.readString()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeByte((if (isSwitch) 1 else 0).toByte())
            dest.writeInt(num)
            dest.writeString(text_small)
            dest.writeString(text_large)
            dest.writeString(icon)
            dest.writeInt(icon_location)
            dest.writeString(icon_web)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<Watched> = object : Parcelable.Creator<Watched> {
                override fun createFromParcel(`in`: Parcel): Watched = Watched(`in`)

                override fun newArray(size: Int): Array<Watched?> = arrayOfNulls(size)
            }
        }
    }

    class NewPendants : Parcelable, Serializable {
        @JvmField var frame: FrameInfo? = null
        @JvmField var mobile_frame: FrameInfo? = null
        @JvmField var badge: BadgeInfo? = null
        @JvmField var mobile_badge: BadgeInfo? = null

        constructor()

        protected constructor(`in`: Parcel) {
            frame = `in`.readParcelable(FrameInfo::class.java.classLoader)
            mobile_frame = `in`.readParcelable(FrameInfo::class.java.classLoader)
            badge = `in`.readParcelable(BadgeInfo::class.java.classLoader)
            mobile_badge = `in`.readParcelable(BadgeInfo::class.java.classLoader)
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeParcelable(frame, flags)
            dest.writeParcelable(mobile_frame, flags)
            dest.writeParcelable(badge, flags)
            dest.writeParcelable(mobile_badge, flags)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<NewPendants> = object : Parcelable.Creator<NewPendants> {
                override fun createFromParcel(`in`: Parcel): NewPendants = NewPendants(`in`)

                override fun newArray(size: Int): Array<NewPendants?> = arrayOfNulls(size)
            }
        }
    }

    class FrameInfo : Parcelable, Serializable {
        @JvmField var name: String? = null
        @JvmField var value: String? = null
        @JvmField var position: Int = 0
        @JvmField var desc: String? = null
        @JvmField var area: Int = 0
        @JvmField var area_old: Int = 0
        @JvmField var bg_color: String? = null
        @JvmField var bg_pic: String? = null
        @JvmField var use_old_area: Boolean = false

        constructor()

        protected constructor(`in`: Parcel) {
            name = `in`.readString()
            value = `in`.readString()
            position = `in`.readInt()
            desc = `in`.readString()
            area = `in`.readInt()
            area_old = `in`.readInt()
            bg_color = `in`.readString()
            bg_pic = `in`.readString()
            use_old_area = `in`.readByte() != 0.toByte()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeString(name)
            dest.writeString(value)
            dest.writeInt(position)
            dest.writeString(desc)
            dest.writeInt(area)
            dest.writeInt(area_old)
            dest.writeString(bg_color)
            dest.writeString(bg_pic)
            dest.writeByte((if (use_old_area) 1 else 0).toByte())
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<FrameInfo> = object : Parcelable.Creator<FrameInfo> {
                override fun createFromParcel(`in`: Parcel): FrameInfo = FrameInfo(`in`)

                override fun newArray(size: Int): Array<FrameInfo?> = arrayOfNulls(size)
            }
        }
    }

    class BadgeInfo : Parcelable, Serializable {
        @JvmField var name: String? = null
        @JvmField var position: Int = 0
        @JvmField var value: String? = null
        @JvmField var desc: String? = null

        constructor()

        protected constructor(`in`: Parcel) {
            name = `in`.readString()
            position = `in`.readInt()
            value = `in`.readString()
            desc = `in`.readString()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeString(name)
            dest.writeInt(position)
            dest.writeString(value)
            dest.writeString(desc)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<BadgeInfo> = object : Parcelable.Creator<BadgeInfo> {
                override fun createFromParcel(`in`: Parcel): BadgeInfo = BadgeInfo(`in`)

                override fun newArray(size: Int): Array<BadgeInfo?> = arrayOfNulls(size)
            }
        }
    }

    class StudioInfo : Parcelable, Serializable {
        @JvmField var status: Int = 0
        @JvmField var master_list: MutableList<Any?>? = null

        constructor()

        protected constructor(`in`: Parcel) {
            status = `in`.readInt()
            master_list = `in`.readArrayList(Any::class.java.classLoader)
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(status)
            dest.writeList(master_list)
        }

        override fun describeContents(): Int = 0

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<StudioInfo> = object : Parcelable.Creator<StudioInfo> {
                override fun createFromParcel(`in`: Parcel): StudioInfo = StudioInfo(`in`)

                override fun newArray(size: Int): Array<StudioInfo?> = arrayOfNulls(size)
            }
        }
    }

    constructor()

    protected constructor(`in`: Parcel) {
        roomid = `in`.readLong()
        short_id = `in`.readLong()
        uid = `in`.readLong()
        title = `in`.readString()
        uname = `in`.readString()
        tags = `in`.readString()
        description = `in`.readString()
        online = `in`.readInt()
        attention = `in`.readInt()
        user_cover = `in`.readString()
        user_cover_flag = `in`.readInt()
        system_cover = `in`.readString()
        cover = `in`.readString()
        keyframe = `in`.readString()
        show_cover = `in`.readString()
        face = `in`.readString()
        area_parent_id = `in`.readInt()
        area_parent_name = `in`.readString()
        area_id = `in`.readInt()
        area_name = `in`.readString()
        session_id = `in`.readString()
        group_id = `in`.readLong()
        show_callback = `in`.readString()
        click_callback = `in`.readString()
        liveTime = `in`.readString()
        live_status = `in`.readInt()
        old_area_id = `in`.readInt()
        background = `in`.readString()
        is_portrait = `in`.readByte() != 0.toByte()
        room_silent_type = `in`.readString()
        room_silent_level = `in`.readInt()
        room_silent_second = `in`.readInt()
        pk_status = `in`.readInt()
        pk_id = `in`.readLong()
        battle_id = `in`.readLong()
        allow_change_area_time = `in`.readInt()
        allow_upload_cover_time = `in`.readInt()
        verify = `in`.readParcelable(Verify::class.java.classLoader)
        watched_show = `in`.readParcelable(Watched::class.java.classLoader)
        new_pendants = `in`.readParcelable(NewPendants::class.java.classLoader)
        studio_info = `in`.readParcelable(StudioInfo::class.java.classLoader)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(roomid)
        dest.writeLong(short_id)
        dest.writeLong(uid)
        dest.writeString(title)
        dest.writeString(uname)
        dest.writeString(tags)
        dest.writeString(description)
        dest.writeInt(online)
        dest.writeInt(attention)
        dest.writeString(user_cover)
        dest.writeInt(user_cover_flag)
        dest.writeString(system_cover)
        dest.writeString(cover)
        dest.writeString(keyframe)
        dest.writeString(show_cover)
        dest.writeString(face)
        dest.writeInt(area_parent_id)
        dest.writeString(area_parent_name)
        dest.writeInt(area_id)
        dest.writeString(area_name)
        dest.writeString(session_id)
        dest.writeLong(group_id)
        dest.writeString(show_callback)
        dest.writeString(click_callback)
        dest.writeString(liveTime)
        dest.writeInt(live_status)
        dest.writeInt(old_area_id)
        dest.writeString(background)
        dest.writeByte((if (is_portrait) 1 else 0).toByte())
        dest.writeString(room_silent_type)
        dest.writeInt(room_silent_level)
        dest.writeInt(room_silent_second)
        dest.writeInt(pk_status)
        dest.writeLong(pk_id)
        dest.writeLong(battle_id)
        dest.writeInt(allow_change_area_time)
        dest.writeInt(allow_upload_cover_time)
        dest.writeParcelable(verify, flags)
        dest.writeParcelable(watched_show, flags)
        dest.writeParcelable(new_pendants, flags)
        dest.writeParcelable(studio_info, flags)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LiveRoom> = object : Parcelable.Creator<LiveRoom> {
            override fun createFromParcel(`in`: Parcel): LiveRoom = LiveRoom(`in`)

            override fun newArray(size: Int): Array<LiveRoom?> = arrayOfNulls(size)
        }
    }
}
