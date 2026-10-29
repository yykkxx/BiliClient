package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.DownloadSection
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.util.ArrayList
import java.util.Locale

//下载列表Adapter
//2024-11-24

class DownloadAdapter(val context: Context, @JvmField var downloadList: ArrayList<DownloadSection>) :
    RecyclerView.Adapter<DownloadAdapter.DownloadHolder>() {

    var longClickListener: OnItemLongClickListener? = null
    var clickListener: OnItemClickListener? = null

    fun setOnLongClickListener(listener: OnItemLongClickListener?) {
        this.longClickListener = listener
    }

    fun setOnClickListener(listener: OnItemClickListener?) {
        this.clickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.cell_video_local, parent, false)
        return DownloadHolder(view)
    }

    override fun onBindViewHolder(holder: DownloadHolder, position: Int) {
        if (downloadList.isEmpty()) {
            holder.show(null, context)
            holder.showProgress(null, -1f)
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            return
        }

        val section = downloadList[position]
        holder.show(section, context)

        if (section.state == "downloading" && DownloadService.section != null
            && DownloadService.section!!.id == section.id) {
            holder.showProgress(DownloadService.state, DownloadService.percent)
        } else {
            holder.showProgress(null, -1f)
        }

        holder.itemView.setOnClickListener {
            clickListener?.onItemClick(position)
        }

        holder.itemView.setOnLongClickListener {
            if (longClickListener != null) {
                longClickListener!!.onItemLongClick(position)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int {
        return if (downloadList.isNotEmpty()) downloadList.size else 1
    }

    class DownloadHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.text_title)
        val extra: TextView = itemView.findViewById(R.id.text_extra)
        val cover: ImageView = itemView.findViewById(R.id.img_cover)
        val progress: View = itemView.findViewById(R.id.progress)

        fun show(section: DownloadSection?, context: Context) {
            if (section == null) {
                title.text = "没有下载中的项"
                extra.text = "点击下面继续下载喵？"
                cover.setImageResource(R.mipmap.placeholder)
                return
            }
            title.text = section.name_short ?: ""
            when (section.state) {
                "error" -> extra.text = "下载出错"
                "none" -> extra.text = "等待下载"
                "downloading" -> if (DownloadService.section == null) extra.text = "下载中断"
                else -> extra.text = "未知状态？"
            }

            if (!section.url_cover.isNullOrEmpty()) {
                Glide.with(BiliTerminal.context).asDrawable().load(section.url_cover)
                    .transition(GlideUtil.getTransitionOptions())
                    .apply(GlideUtil.roundOptions(5))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(cover)
            }
        }

        @SuppressLint("SetTextI18n")
        fun showProgress(state: String?, percent: Float) {
            if (state == null || percent == -1f) {
                progress.visibility = View.GONE
                return
            }
            progress.visibility = View.VISIBLE
            extra.visibility = View.VISIBLE
            extra.text = state + "：" + String.format(Locale.CHINA, "%.2f", percent * 100) + "%"
            val width = itemView.measuredWidth
            if (width > 0) {
                val layoutParams = progress.layoutParams
                layoutParams.width = (width * percent).toInt()
                progress.layoutParams = layoutParams
            } else {
                progress.post {
                    val measuredWidth = itemView.measuredWidth
                    if (measuredWidth > 0) {
                        val layoutParams = progress.layoutParams
                        layoutParams.width = (measuredWidth * percent).toInt()
                        progress.layoutParams = layoutParams
                    }
                }
            }
        }
    }
}
