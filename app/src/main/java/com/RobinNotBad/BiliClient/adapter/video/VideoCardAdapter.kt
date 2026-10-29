package com.RobinNotBad.BiliClient.adapter.video

import android.content.Context
import android.view.LayoutInflater
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

    /**
     * v5.0.2-alpha10：点击/长按监听器改为**创建 ViewHolder 时挂一次**。
     *
     * 原来这两个监听器写在 onBindViewHolder 里，每 bind 一行就新建两个 lambda 并分别捕获
     * videoCard/position/上下文 —— fling 时列表每秒会 bind 几十次，等于每秒几十次短命对象分配
     * 外加一次 view 的重设监听。监听器本身与数据无关（只依赖 ViewHolder 与 adapter 的
     * longClickListener 字段），所以完全可以只挂一次；位置改成读 holder.boundPosition，
     * 该字段在每次 bind 时被写成刚绑定的 position，语义与原来「闭包捕获 bind 时的 position」完全一致。
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoCardHolder {
        val layoutRes = if (feedLayout) R.layout.cell_video_feed else R.layout.cell_video_list
        val view = LayoutInflater.from(this.context).inflate(layoutRes, parent, false)
        val holder = VideoCardHolder(view)
        installItemListeners(holder)
        return holder
    }

    /** 只在 onCreateViewHolder 里调用一次（见上面的说明）。 */
    private fun installItemListeners(holder: VideoCardHolder) {
        holder.itemView.setOnClickListener {
            val position = holder.boundPosition
            if (position < 0 || position >= videoCardList.size) return@setOnClickListener
            val videoCard = videoCardList[position]
            when (videoCard.type) {
                "video" -> TerminalContext.getInstance()
                    .enterVideoDetailPage(context, videoCard.aid, videoCard.bvid, "video")
                "media_bangumi" -> TerminalContext.getInstance()
                    .enterVideoDetailPage(context, videoCard.aid, null, "media")
            }
        }

        holder.itemView.setOnLongClickListener {
            // 默认行为（#3/#6）：长按加入/移出稍后再看（由 VideoCardHolder 统一实现，
            // 推荐/详情第三页/搜索等所有视频列表行为一致）
            val listener = longClickListener
            if (listener != null) {
                listener.onItemLongClick(holder.boundPosition)
            } else {
                holder.toggleWatchLater()
            }
            true
        }
    }

    override fun onBindViewHolder(holder: VideoCardHolder, position: Int) {
        if (position < 0 || position >= videoCardList.size) return
        val videoCard = videoCardList[position]
        if (videoCard == null) return

        // 只为「点击/长按要用的位置」写一个 int（不分配对象），监听器本体仍在 onCreateViewHolder 里
        holder.boundPosition = position

        holder.showVideoCard(videoCard, context)
    }

    override fun getItemCount(): Int {
        return if (videoCardList != null) videoCardList.size else 0
    }

}
