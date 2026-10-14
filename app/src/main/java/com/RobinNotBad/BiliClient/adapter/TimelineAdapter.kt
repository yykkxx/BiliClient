package com.RobinNotBad.BiliClient.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.Timeline
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimelineAdapter(private val context: Context, private val dayInfoList: List<Timeline.DayInfo>?) : RecyclerView.Adapter<TimelineAdapter.DayViewHolder>() {

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat: SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): DayViewHolder {
        val view: View = LayoutInflater.from(context).inflate(R.layout.cell_timeline_day, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: DayViewHolder, position: Int) {
        val dayInfo: Timeline.DayInfo = dayInfoList!![position]

        var dateText: String? = dayInfo.date
        if (dayInfo.is_today == 1) {
            dateText = dateText + " (今天)"
        }
        holder.dateText.setText(dateText)

        holder.episodesLayout.removeAllViews()

        val episodes: List<Timeline.Episode>? = dayInfo.episodes
        if (episodes != null && !episodes.isEmpty()) {
            for (episode in episodes) {
                val episodeView: View = LayoutInflater.from(context).inflate(R.layout.cell_timeline_episode, holder.episodesLayout, false)

                val cover: ImageView = episodeView.findViewById(R.id.img_cover)
                val title: TextView = episodeView.findViewById(R.id.text_title)
                val episodeText: TextView = episodeView.findViewById(R.id.text_episode)
                val timeText: TextView = episodeView.findViewById(R.id.text_time)

                title.setText(episode.title)
                episodeText.setText(episode.pub_index)

                if (episode.pub_ts > 0) {
                    val pubDate: Date = Date(episode.pub_ts * 1000)
                    timeText.setText(timeFormat.format(pubDate))
                } else {
                    timeText.setText(episode.pub_time ?: "")
                }

                val coverUrl: String = GlideUtil.url(episode.cover ?: "")
                Glide.with(BiliTerminal.context).asDrawable().load(coverUrl)
                        .placeholder(R.mipmap.placeholder)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .apply(RequestOptions.bitmapTransform(RoundedCorners(8))
                                .sizeMultiplier(0.85f))
                        .into(cover)

                holder.episodesLayout.addView(episodeView)
            }
        }
    }

    override fun getItemCount(): Int {
        return if (dayInfoList != null) dayInfoList.size else 0
    }

    class DayViewHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val dateText: TextView = itemView.findViewById(R.id.text_date)

        @JvmField
        val episodesLayout: LinearLayout = itemView.findViewById(R.id.episodes_layout)
    }
}
