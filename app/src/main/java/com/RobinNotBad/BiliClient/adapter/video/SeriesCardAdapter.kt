package com.RobinNotBad.BiliClient.adapter.video

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.video.series.SeriesInfoActivity
import com.RobinNotBad.BiliClient.model.Series
import com.RobinNotBad.BiliClient.model.VideoCard

import java.util.ArrayList

class SeriesCardAdapter(val context: Context, val seasonList: ArrayList<Series>) : RecyclerView.Adapter<VideoCardHolder>() {

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): VideoCardHolder {
        val view: View = LayoutInflater.from(this.context).inflate(R.layout.cell_video_list, parent, false)
        return VideoCardHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: VideoCardHolder, position: Int) {
        if (position < 0 || position >= seasonList.size)
            return
        val series: Series? = seasonList[position]
        if (series == null)
            return

        val videoCard = VideoCard(series.title, series.intro, series.total, series.cover, 0, "", "series")
        holder.showVideoCard(videoCard, context)

        holder.itemView.setOnClickListener { view ->
            val intent = Intent(context, SeriesInfoActivity::class.java)
            intent.putExtra("type", series.type)
            intent.putExtra("mid", series.mid)
            intent.putExtra("sid", series.id)
            intent.putExtra("name", series.title)
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int {
        return if (seasonList != null) seasonList.size else 0
    }
}
