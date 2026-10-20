package com.RobinNotBad.BiliClient.adapter.video

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.api.WatchLaterApi
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
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
                //默认行为（#6）：任意视频长按加入稍后再看并弹 Toast，再次长按移出。
                //显式设置了长按监听的页面（稍后再看页/收藏夹页等）仍走自己的逻辑。
                toggleWatchLater(videoCard)
                true
            }
        }
    }

    /**
     * 长按切换「稍后再看」（#6）。
     *
     * 先拉一次稍后再看列表判断该视频是否已在里面：
     * 在 → delete + Toast「已移出稍后再看」；不在 → add + Toast「已加入稍后再看」。
     * 弱网下失败就 Toast 错误码，绝不阻塞主线程。
     */
    private fun toggleWatchLater(videoCard: VideoCard) {
        if (videoCard.type != null && videoCard.type != "video") return  //番剧等特殊卡片不支持
        if (videoCard.aid <= 0) return
        CenterThreadPool.run(Runnable {
            try {
                val inList = try {
                    WatchLaterApi.getWatchLaterList().any { it.aid == videoCard.aid }
                } catch (e: Exception) {
                    e.printStackTrace()
                    MsgUtil.showMsg("操作失败，请稍后再试")
                    return@Runnable
                }
                val result = if (inList) WatchLaterApi.delete(videoCard.aid)
                else WatchLaterApi.add(videoCard.aid)
                if (result == 0) {
                    MsgUtil.showMsg(if (inList) "已移出稍后再看" else "已加入稍后再看")
                } else {
                    MsgUtil.showMsg("操作失败，错误码：" + result)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                MsgUtil.showMsg("操作失败，请稍后再试")
            }
        })
    }

    override fun getItemCount(): Int {
        return if (videoCardList != null) videoCardList.size else 0
    }

}
