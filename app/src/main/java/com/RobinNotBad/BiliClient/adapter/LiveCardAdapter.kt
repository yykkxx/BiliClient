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

    fun setOnLongClickListener(listener: OnItemLongClickListener) {
        this.longClickListener = listener
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): VideoCardHolder {
        val view: View = LayoutInflater.from(this.context).inflate(R.layout.cell_video_list, parent, false)
        return VideoCardHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: VideoCardHolder, position: Int) {
        if (position < 0 || position >= roomList.size)
            return
        val room: LiveRoom? = roomList[position]
        if (room == null)
            return

        val videoCard = VideoCard()
        videoCard.title = StringUtil.removeHtml(room.title!!)
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

}
