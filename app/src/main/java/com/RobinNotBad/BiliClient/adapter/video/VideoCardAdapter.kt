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

    /** 信息流布局（#2）：双列模式下图片撑满卡片、标题在下方不省略 */
    @JvmField
    var feedLayout: Boolean = false

    fun setOnLongClickListener(listener: OnItemLongClickListener?) {
        this.longClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoCardHolder {
        val layoutRes = if (feedLayout) R.layout.cell_video_feed else R.layout.cell_video_list
        val view = LayoutInflater.from(this.context).inflate(layoutRes, parent, false)
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
                // 默认行为（#3/#6）：长按加入/移出稍后再看（由 VideoCardHolder 统一实现，
                // 推荐/详情第三页/搜索等所有视频列表行为一致）
                holder.toggleWatchLater()
                true
            }
        }
    }

    override fun getItemCount(): Int {
        return if (videoCardList != null) videoCardList.size else 0
    }

}
