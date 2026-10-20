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
import com.RobinNotBad.BiliClient.api.WatchLaterApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
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

    /** 当前绑定的视频卡片（长按稍后再看用） */
    private var boundCard: VideoCard? = null

    /** 该卡片是否已知在稍后再看列表里（避免每次长按都全量拉列表） */
    private var watchLaterKnown = false

    init {
        title = itemView.findViewById(R.id.text_title)
        upName = itemView.findViewById(R.id.text_upname)
        viewCount = itemView.findViewById(R.id.text_viewcount)
        cover = itemView.findViewById(R.id.img_cover)
    }

    @SuppressLint("SetTextI18n")
    fun showVideoCard(videoCard: VideoCard, context: Context) {
        boundCard = videoCard
        val str_upName = videoCard.upName
        if (str_upName == null || str_upName.isEmpty() || str_upName == "null") {
            upName!!.visibility = View.GONE
        } else upName!!.text = str_upName

        val str_viewCount = videoCard.view
        // "null" 字面量兜底（#5）：部分老数据源 getString(JSON null) 产生
        if (str_viewCount == null || str_viewCount.isEmpty() || str_viewCount == "null") {
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
    /**
     * 长按切换「稍后再看」（#3/#6）。
     *
     * 所有视频列表（推荐/详情第三页/搜索/热门等）行为统一：
     * 长按一次加入稍后再看（Toast 提示），再次长按移出。
     * 已知状态缓存到 watchLaterKnown，只有第一次长按才全量拉列表判断，
     * 之后直接按记录的状态切换，避免每次长按都请求。
     */
    fun toggleWatchLater() {
        val card = boundCard ?: return
        if (card.type != null && card.type != "video") {
            MsgUtil.showMsg("此类卡片不支持稍后再看")
            return
        }
        if (card.aid <= 0) return

        val willAdd = !watchLaterKnown
        MsgUtil.showMsg(if (willAdd) "正在加入稍后再看…" else "正在移出稍后再看…")
        CenterThreadPool.run(Runnable {
            try {
                if (!watchLaterKnown) {
                    // 第一次长按：先确认是否已在列表里（用户可能在别处加过）
                    val inList = try {
                        WatchLaterApi.getWatchLaterList().any { it.aid == card.aid }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        MsgUtil.showMsg("操作失败，请稍后再试")
                        return@Runnable
                    }
                    if (inList) {
                        // 已在列表里 → 这次长按是移出
                        val result = WatchLaterApi.delete(card.aid)
                        if (result == 0) {
                            watchLaterKnown = false
                            MsgUtil.showMsg("已移出稍后再看")
                        } else MsgUtil.showMsg("操作失败，错误码：" + result)
                        return@Runnable
                    }
                }
                val result = if (willAdd) WatchLaterApi.add(card.aid)
                else WatchLaterApi.delete(card.aid)
                if (result == 0) {
                    watchLaterKnown = willAdd
                    MsgUtil.showMsg(if (willAdd) "已加入稍后再看" else "已移出稍后再看")
                } else {
                    MsgUtil.showMsg("操作失败，错误码：" + result)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                MsgUtil.showMsg("操作失败，请稍后再试")
            }
        })
    }
}
