package com.RobinNotBad.BiliClient.adapter.video

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.TerminalContext

//视频卡片Adapter 适用于各种场景（迫真
//日期不记得了

//2023-10-01 把一些公用代码移动到VideoCardHolder里了

class VideoCardAdapter(val context: Context, val videoCardList: List<VideoCard>) : RecyclerView.Adapter<VideoCardHolder>() {

    var longClickListener: OnItemLongClickListener? = null

    fun setOnLongClickListener(listener: OnItemLongClickListener?) {
        this.longClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoCardHolder {
        val view = LayoutInflater.from(this.context).inflate(R.layout.cell_video_list, parent, false)
        return VideoCardHolder(view)
    }

    override fun onBindViewHolder(holder: VideoCardHolder, position: Int) {
        if (position < 0 || position >= videoCardList.size) return
        val videoCard = videoCardList[position]
        if (videoCard == null) return

        holder.showVideoCard(videoCard, context)

        holder.itemView.setOnClickListener { view ->
            when (videoCard.type) {
                "video" -> TerminalContext.getInstance().enterVideoDetailPage(context, videoCard.aid, videoCard.bvid, "video")
                "media_bangumi" -> TerminalContext.getInstance().enterVideoDetailPage(context, videoCard.aid, null, "media")
            }
        }

        holder.itemView.setOnLongClickListener { view ->
            if (longClickListener != null) {
                longClickListener!!.onItemLongClick(position)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int {
        return if (videoCardList != null) videoCardList.size else 0
    }

}
