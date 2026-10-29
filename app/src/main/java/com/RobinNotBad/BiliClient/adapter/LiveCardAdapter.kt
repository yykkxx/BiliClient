package com.RobinNotBad.BiliClient.adapter

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.TerminalContext

class LiveCardAdapter(@JvmField val context: Context, @JvmField val roomList: List<LiveRoom>) : RecyclerView.Adapter<VideoCardHolder>() {

    @JvmField
    var longClickListener: OnItemLongClickListener? = null

    /** 信息流布局（#4）：双列模式下图片撑满卡片、标题在下方不省略 */
    @JvmField
    var feedLayout: Boolean = false

    fun setOnLongClickListener(listener: OnItemLongClickListener) {
        this.longClickListener = listener
    }

    /**
     * 标题纯文本缓存（v5.0.2-alpha10）。
     *
     * 原来每条 bind 都要 `StringUtil.removeHtml(room.title)`，而它是 `Jsoup.parse(html).text()`：
     * 一个完整的 HTML 解析器实例 + Document/Element 树，单次在手表上要几十到上百微秒并产生大量短命对象。
     * 直播间列表（推荐/关注/搜索结果）滚动时每条都会走过这里，而**同一个房间的标题几乎不变**，
     * 所以按原始标题串缓存解析结果即可。缓存条目数受列表里的不同标题数限制，另加硬上限兜底防止异常增长。
     */
    private val titleTextCache = HashMap<String, String>()

    private fun plainTitle(rawTitle: String): String {
        val cached = titleTextCache[rawTitle]
        if (cached != null) return cached
        val text = StringUtil.removeHtml(rawTitle)
        if (titleTextCache.size >= TITLE_CACHE_MAX) titleTextCache.clear()
        titleTextCache[rawTitle] = text
        return text
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): VideoCardHolder {
        val layoutRes = if (feedLayout) R.layout.cell_video_feed else R.layout.cell_video_list
        val view: View = LayoutInflater.from(this.context).inflate(layoutRes, parent, false)
        return VideoCardHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: VideoCardHolder, position: Int) {
        if (position < 0 || position >= roomList.size)
            return
        val room: LiveRoom? = roomList[position]
        if (room == null)
            return

        val videoCard = VideoCard()
        // 标题走缓存（见 plainTitle 注释）；原先 room.title 用 !! 强解，这里按空串兜底，
        // 有效数据（接口必有 title）输出完全一致
        videoCard.title = plainTitle(room.title ?: "")
        if (room.user_cover != null && !room.user_cover!!.startsWith("http"))
            videoCard.cover = "http:" + room.user_cover
        else
            videoCard.cover = room.user_cover
        if (TextUtils.isEmpty(videoCard.cover) || videoCard.cover == "http:")
            videoCard.cover = room.cover
        videoCard.upName = room.uname
        videoCard.view = StringUtil.toWan(room.online.toLong()) + "人观看"
        videoCard.type = "live"

        holder.showVideoCard(videoCard, context)

        holder.itemView
            .setOnClickListener { view -> TerminalContext.getInstance().enterLiveDetailPage(context, room.roomid) }

        holder.itemView.setOnLongClickListener { view ->
            if (longClickListener != null) {
                longClickListener!!.onItemLongClick(position)
                true
            } else
                false
        }
    }

    override fun getItemCount(): Int {
        return if (roomList != null) roomList.size else 0
    }

    private companion object {
        /** 标题缓存硬上限（直播间列表规模远小于此，仅作异常兜底） */
        const val TITLE_CACHE_MAX = 200
    }
}
