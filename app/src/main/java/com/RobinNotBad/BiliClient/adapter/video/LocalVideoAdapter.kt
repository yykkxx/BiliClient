package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.video.local.DownloadListActivity
import com.RobinNotBad.BiliClient.activity.video.local.LocalPageChooseActivity
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.LocalVideo
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import java.util.ArrayList
import java.util.Locale

//本地视频Adapter
//2023-10-04

class LocalVideoAdapter(val context: Context, val localVideoList: ArrayList<LocalVideo>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var longClickListener: OnItemLongClickListener? = null

    fun setOnLongClickListener(listener: OnItemLongClickListener?) {
        this.longClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_video_local, parent, false)
            LocalVideoHolder(view)
        } else {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_goto, parent, false)
            GotoHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position != 0) {
            val realPosition = position - 1
            if (realPosition < 0 || realPosition >= localVideoList.size) return
            val localVideo = localVideoList[realPosition] ?: return

            (holder as LocalVideoHolder).showLocalVideo(localVideo, context)

            holder.itemView.setOnClickListener {
                if (localVideo.videoFileList != null && localVideo.videoFileList!!.size == 1) {
                    val playerData = PlayerData(PlayerData.TYPE_LOCAL)
                    val mediaPath = localVideo.videoFileList!![0]
                    playerData.videoUrl = mediaPath
                    if (localVideo.danmakuFileList != null && localVideo.danmakuFileList!!.isNotEmpty()) {
                        playerData.danmakuUrl = localVideo.danmakuFileList!![0]
                    }
                    playerData.title = localVideo.title

                    try {
                        val player = PlayerApi.jumpToPlayer(playerData)
                        // 如果是仅音频文件，标记为音频模式
                        if (mediaPath.endsWith("audio.m4a")) {
                            player.putExtra("audio_only", true)
                        }
                        context.startActivity(player)
                    } catch (e: ActivityNotFoundException) {
                        MsgUtil.showMsg("跳转失败")
                        e.printStackTrace()
                    }
                } else if (localVideo.videoFileList != null && localVideo.videoFileList!!.size > 1) {
                    val intent = Intent()
                    intent.setClass(context, LocalPageChooseActivity::class.java)
                    intent.putExtra("pageList", localVideo.pageList)
                    intent.putExtra("videoFileList", localVideo.videoFileList)
                    intent.putExtra("danmakuFileList", localVideo.danmakuFileList)
                    intent.putExtra("title", localVideo.title)
                    context.startActivity(intent)
                }
            }

            holder.itemView.setOnLongClickListener {
                if (longClickListener != null) {
                    longClickListener!!.onItemLongClick(realPosition)
                    true
                } else {
                    false
                }
            }
        } else {
            (holder as GotoHolder).show(context)
        }
    }

    override fun getItemCount(): Int {
        return localVideoList.size + 1
    }

    class GotoHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val textView: TextView = itemView.findViewById(R.id.text)

        fun show(context: Context) {
            textView.text = "下载列表"
            itemView.setOnClickListener { v -> context.startActivity(Intent(context, DownloadListActivity::class.java)) }
        }
    }

    class LocalVideoHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.text_title)
        val extra: TextView = itemView.findViewById(R.id.text_extra)
        val cover: ImageView = itemView.findViewById(R.id.img_cover)

        @SuppressLint("SetTextI18n")
        fun showLocalVideo(videoCard: LocalVideo, context: Context) {
            title.text = videoCard.title ?: ""
            val size = String.format(Locale.CHINA, "%.2f", videoCard.size / 1000000f) + "MB"
            extra.text = size

            try {
                Glide.with(BiliTerminal.context).asDrawable().load(videoCard.cover)
                    .transition(GlideUtil.getTransitionOptions())
                    .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f))))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(cover)
            } catch (ignored: Exception) {
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) 1 else 0
    }
}
