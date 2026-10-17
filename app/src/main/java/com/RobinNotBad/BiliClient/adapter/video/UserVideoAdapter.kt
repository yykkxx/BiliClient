package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.video.series.UserSeriesActivity
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.TerminalContext

import java.util.ArrayList

//用户视频列表专用Adapter

class UserVideoAdapter(val context: Context, val mid: Long, val videoCardList: ArrayList<VideoCard>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 信息流布局（#4）：双列模式下图片撑满卡片、标题在下方不省略 */
    @JvmField
    var feedLayout: Boolean = false

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_goto, parent, false)
            return object : RecyclerView.ViewHolder(view) {
            }
        } else {
            val layoutRes = if (feedLayout) R.layout.cell_video_feed else R.layout.cell_video_list
            val view: View = LayoutInflater.from(this.context).inflate(layoutRes, parent, false)
            return VideoCardHolder(view)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) {
            val textView = holder.itemView.findViewById<TextView>(R.id.text)
            textView.text = "视频系列"
            holder.itemView.setOnClickListener { view ->
                val intent = Intent(context, UserSeriesActivity::class.java)
                intent.putExtra("mid", mid)
                context.startActivity(intent)
            }
        } else {
            val realPosition = position - 1
            if (realPosition < 0 || realPosition >= videoCardList.size)
                return
            val videoCardHolder = holder as VideoCardHolder
            val videoCard: VideoCard? = videoCardList[realPosition]
            if (videoCard == null)
                return

            videoCardHolder.showVideoCard(videoCard, context)

            holder.itemView.setOnClickListener { view ->
                TerminalContext.getInstance().enterVideoDetailPage(context, videoCard.aid, videoCard.bvid)
            }
        }
    }

    override fun onViewRecycled(@NonNull holder: RecyclerView.ViewHolder) {
        if (holder is DynamicHolder)
            (holder as DynamicHolder).extraCard.removeAllViews()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        return if (videoCardList != null) videoCardList.size + 1 else 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) 0 else 1
    }
}
