package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable

import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.Serializable

class UserInfo : Parcelable, Serializable {
    @JvmField var mid: Long = 0
    @JvmField var name: String? = null
    @JvmField var avatar: String? = null
    @JvmField var sign: String? = null
    @JvmField var fans: Int = 0
    @JvmField var level: Int = 0
    @JvmField var following: Int = 0
    @JvmField var followed: Boolean = false
    @JvmField var notice: String? = null

    @JvmField var official: Int = 0
    @JvmField var officialDesc: String? = null
    @JvmField var mtime: Long = 0

    @JvmField var vip_role: Int = 0
    @JvmField var vip_nickname_color: String? = ""

    @JvmField var current_exp: Long = 0
    @JvmField var next_exp: Long = 0

    @JvmField var medal_name: String? = ""
    @JvmField var medal_level: Int = 0

    @JvmField var sys_notice: String? = ""

    @JvmField var live_room: LiveRoom? = null

    @JvmField var is_senior_member: Int = 0

    @JvmField var is_follow_display: Boolean = false

    constructor(mid: Long, name: String?, avatar: String?, sign: String?, fans: Int, following: Int, level: Int, followed: Boolean, notice: String?, official: Int, officialDesc: String?, is_senior_member: Int) {
        this.mid = mid
        this.name = name
        this.avatar = avatar
        this.sign = sign
        this.fans = fans
        this.level = level
        this.following = following
        this.followed = followed
        this.notice = notice
        this.official = official
        this.officialDesc = officialDesc
        this.is_senior_member = is_senior_member
    }

    constructor(mid: Long, name: String?, avatar: String?, sign: String?, fans: Int, following: Int, level: Int, followed: Boolean, notice: String?, official: Int, officialDesc: String?, sys_notice: String?, live_room: LiveRoom?, is_senior_member: Int) {
        this.mid = mid
        this.name = name
        this.avatar = avatar
        this.sign = sign
        this.fans = fans
        this.level = level
        this.following = following
        this.followed = followed
        this.notice = notice
        this.official = official
        this.officialDesc = officialDesc
        this.sys_notice = sys_notice
        this.live_room = live_room
        this.is_senior_member = is_senior_member
    }

    constructor(mid: Long, name: String?, avatar: String?, sign: String?, fans: Int, following: Int, level: Int, followed: Boolean, notice: String?, official: Int, officialDesc: String?, current_exp: Long, next_exp: Long, is_senior_member: Int) {
        this.mid = mid
        this.name = name
        this.avatar = avatar
        this.sign = sign
        this.fans = fans
        this.level = level
        this.following = following
        this.followed = followed
        this.notice = notice
        this.official = official
        this.officialDesc = officialDesc
        this.current_exp = current_exp
        this.next_exp = next_exp
        this.is_senior_member = is_senior_member
    }

    constructor(mid: Long, name: String?, avatar: String?, sign: String?, fans: Int, following: Int, level: Int, followed: Boolean, notice: String?, official: Int, officialDesc: String?, vip_role: Int, sys_notice: String?, live_room: LiveRoom?, is_senior_member: Int) {
        this.mid = mid
        this.name = name
        this.avatar = avatar
        this.sign = sign
        this.fans = fans
        this.level = level
        this.following = following
        this.followed = followed
        this.notice = notice
        this.official = official
        this.officialDesc = officialDesc
        this.vip_role = vip_role
        this.sys_notice = sys_notice
        this.live_room = live_room
        this.is_senior_member = is_senior_member
    }

    constructor(mid: Long, name: String?, avatar: String?, sign: String?, fans: Int, following: Int, level: Int, followed: Boolean, notice: String?, official: Int, officialDesc: String?, mtime: Long, is_senior_member: Int) {
        this.mid = mid
        this.name = name
        this.avatar = avatar
        this.sign = sign
        this.fans = fans
        this.level = level
        this.following = following
        this.followed = followed
        this.notice = notice
        this.official = official
        this.officialDesc = officialDesc
        this.mtime = mtime
        this.is_senior_member = is_senior_member
    }

    constructor()

    constructor(userInfoJson: JSONObject) {
        this.level = userInfoJson.getJSONObject("level_info").getInt("current_level")
        this.mid = userInfoJson.getLong("mid")
        this.name = userInfoJson.getString("uname")
        this.avatar = userInfoJson.getString("avatar")
        this.is_senior_member = userInfoJson.getInt("is_senior_member")
        val vip = userInfoJson.getJSONObject("vip")
        this.vip_role = vip.getInt("vipStatus")
        this.vip_nickname_color = vip.getString("nickname_color")
        if ((!userInfoJson.isNull("fans_detail")) && (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NO_MEDAL, false))) {
            val fans_detail = userInfoJson.getJSONObject("fans_detail")
            this.medal_name = fans_detail.getString("medal_name")
            this.medal_level = fans_detail.getInt("level")
        }
    }

    protected constructor(`in`: Parcel) {
        mid = `in`.readLong()
        name = `in`.readString()
        avatar = `in`.readString()
        sign = `in`.readString()
        fans = `in`.readInt()
        level = `in`.readInt()
        following = `in`.readInt()
        followed = `in`.readByte() != 0.toByte()
        notice = `in`.readString()
        official = `in`.readInt()
        officialDesc = `in`.readString()
        mtime = `in`.readLong()
        vip_role = `in`.readInt()
        vip_nickname_color = `in`.readString()
        current_exp = `in`.readLong()
        next_exp = `in`.readLong()
        medal_name = `in`.readString()
        medal_level = `in`.readInt()
        sys_notice = `in`.readString()
        live_room = `in`.readParcelable(LiveRoom::class.java.classLoader)
        is_senior_member = `in`.readInt()
        is_follow_display = `in`.readByte() != 0.toByte()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(mid)
        dest.writeString(name)
        dest.writeString(avatar)
        dest.writeString(sign)
        dest.writeInt(fans)
        dest.writeInt(level)
        dest.writeInt(following)
        dest.writeByte((if (followed) 1 else 0).toByte())
        dest.writeString(notice)
        dest.writeInt(official)
        dest.writeString(officialDesc)
        dest.writeLong(mtime)
        dest.writeInt(vip_role)
        dest.writeString(vip_nickname_color)
        dest.writeLong(current_exp)
        dest.writeLong(next_exp)
        dest.writeString(medal_name)
        dest.writeInt(medal_level)
        dest.writeString(sys_notice)
        dest.writeParcelable(live_room, flags)
        dest.writeInt(is_senior_member)
        dest.writeByte((if (is_follow_display) 1 else 0).toByte())
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<UserInfo> = object : Parcelable.Creator<UserInfo> {
            override fun createFromParcel(`in`: Parcel): UserInfo = UserInfo(`in`)

            override fun newArray(size: Int): Array<UserInfo?> = arrayOfNulls(size)
        }
    }
}
