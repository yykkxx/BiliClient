package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions

class VideoCardHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    @JvmField
    var title: TextView? = null
    @JvmField
    var upName: TextView? = null
    @JvmField
    var viewCount: TextView? = null
    @JvmField
    var cover: ImageView? = null
    private var lastCoverUrl: String? = null

    init {
        title = itemView.findViewById(R.id.text_title)
        upName = itemView.findViewById(R.id.text_upname)
        viewCount = itemView.findViewById(R.id.text_viewcount)
        cover = itemView.findViewById(R.id.img_cover)
    }

    @SuppressLint("SetTextI18n")
    fun showVideoCard(videoCard: VideoCard, context: Context) {
        val str_upName = videoCard.upName
        if (str_upName == null || str_upName.isEmpty()) {
            upName!!.visibility = View.GONE
        } else upName!!.text = str_upName

        val str_viewCount = videoCard.view
        if (str_viewCount == null || str_viewCount.isEmpty()) {
            viewCount!!.visibility = View.GONE
        } else {
            viewCount!!.text = str_viewCount
        }

        try {
            val coverUrl = GlideUtil.url(videoCard.cover!!)
            if (coverUrl != lastCoverUrl) {
                lastCoverUrl = coverUrl
                Glide.with(BiliTerminal.context).asDrawable().load(coverUrl)
                        .transition(GlideUtil.getTransitionOptions())
                        .placeholder(R.mipmap.placeholder)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f)))
                                .sizeMultiplier(0.85f))
                        .into(cover!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        when (videoCard.type) {
            "live" -> {
                val sstr_live = SpannableString("[直播]" + StringUtil.htmlToString(videoCard.title ?: ""))
                sstr_live.setSpan(ForegroundColorSpan(ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt())), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                title!!.text = sstr_live
            }
            "series" -> {
                val sstr_series = SpannableString("[系列]" + StringUtil.htmlToString(videoCard.title ?: ""))
                sstr_series.setSpan(ForegroundColorSpan(ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt())), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                title!!.text = sstr_series
            }
            else -> title!!.text = StringUtil.htmlToString(videoCard.title ?: "")
        }

    }
}
