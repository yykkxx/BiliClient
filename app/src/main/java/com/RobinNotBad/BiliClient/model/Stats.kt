package com.RobinNotBad.BiliClient.model

import android.os.Parcel
import android.os.Parcelable
import org.json.JSONObject
import java.io.Serializable

class Stats : Parcelable, Serializable {
    @JvmField var view: Int = 0
    @JvmField var like: Int = 0
    @JvmField var reply: Int = 0
    @JvmField var coin: Int = 0
    @JvmField var share: Int = 0
    @JvmField var danmaku: Int = 0
    @JvmField var favorite: Int = 0

    @JvmField var followed: Boolean = false
    @JvmField var liked: Boolean = false
    @JvmField var disliked: Boolean = false
    @JvmField var favoured: Boolean = false
    @JvmField var coined: Int = 0

    @JvmField var like_disabled: Boolean = false
    @JvmField var coin_disabled: Boolean = false
    @JvmField var fav_disabled: Boolean = false
    @JvmField var reply_disabled: Boolean = false
    @JvmField var share_disabled: Boolean = false
    @JvmField var coin_limit: Int = 0

    constructor()

    protected constructor(`in`: Parcel) {
        view = `in`.readInt()
        like = `in`.readInt()
        reply = `in`.readInt()
        coin = `in`.readInt()
        share = `in`.readInt()
        danmaku = `in`.readInt()
        favorite = `in`.readInt()
        followed = `in`.readByte() != 0.toByte()
        liked = `in`.readByte() != 0.toByte()
        disliked = `in`.readByte() != 0.toByte()
        favoured = `in`.readByte() != 0.toByte()
        coined = `in`.readInt()
        like_disabled = `in`.readByte() != 0.toByte()
        coin_disabled = `in`.readByte() != 0.toByte()
        fav_disabled = `in`.readByte() != 0.toByte()
        reply_disabled = `in`.readByte() != 0.toByte()
        share_disabled = `in`.readByte() != 0.toByte()
        coin_limit = `in`.readInt()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(view)
        dest.writeInt(like)
        dest.writeInt(reply)
        dest.writeInt(coin)
        dest.writeInt(share)
        dest.writeInt(danmaku)
        dest.writeInt(favorite)
        dest.writeByte((if (followed) 1 else 0).toByte())
        dest.writeByte((if (liked) 1 else 0).toByte())
        dest.writeByte((if (disliked) 1 else 0).toByte())
        dest.writeByte((if (favoured) 1 else 0).toByte())
        dest.writeInt(coined)
        dest.writeByte((if (like_disabled) 1 else 0).toByte())
        dest.writeByte((if (coin_disabled) 1 else 0).toByte())
        dest.writeByte((if (fav_disabled) 1 else 0).toByte())
        dest.writeByte((if (reply_disabled) 1 else 0).toByte())
        dest.writeByte((if (share_disabled) 1 else 0).toByte())
        dest.writeInt(coin_limit)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Stats> = object : Parcelable.Creator<Stats> {
            override fun createFromParcel(`in`: Parcel): Stats = Stats(`in`)

            override fun newArray(size: Int): Array<Stats?> = arrayOfNulls(size)
        }

        @JvmStatic
        fun fromOpus(module_stat: JSONObject?): Stats {
            val stats = Stats()
            if (module_stat == null) return stats
            val coin = module_stat.optJSONObject("coin")
            if (coin != null) {
                stats.coin = coin.optInt("count")
                stats.coin_disabled = coin.optBoolean("forbidden", true) || coin.optBoolean("hidden", true)
            }
            val comment = module_stat.optJSONObject("comment")
            if (comment != null) {
                stats.reply = comment.optInt("count")
                stats.reply_disabled = comment.optBoolean("forbidden", true)
            }
            val favorite = module_stat.optJSONObject("favorite")
            if (favorite != null) {
                stats.favorite = favorite.optInt("count")
                stats.fav_disabled = favorite.optBoolean("forbidden", true)
                stats.favoured = favorite.optBoolean("status", true)
            }
            val forward = module_stat.optJSONObject("forward")
            if (forward != null) {
                stats.share = forward.optInt("count")
                stats.share_disabled = forward.optBoolean("forbidden", true)
            }
            val like = module_stat.optJSONObject("like")
            if (like != null) {
                stats.like = like.optInt("count")
                stats.like_disabled = like.optBoolean("forbidden", true)
                stats.liked = like.optBoolean("status", true)
            }
            return stats
        }
    }
}
