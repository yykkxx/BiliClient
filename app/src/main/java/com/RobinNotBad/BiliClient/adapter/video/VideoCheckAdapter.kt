package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.VideoCard

/**
 * 带复选框的视频列表（#7：收藏夹多选缓存用）。
 *
 * 点击整行切换勾选状态；勾选集合由 Activity 持有（按 aid 记），
 * 滚动复用时由 isChecked 重建状态，避免 RecyclerView 复用错乱。
 */
class VideoCheckAdapter(
        private val context: Context,
        private val videoList: List<VideoCard>,
        private val checkedAids: MutableSet<Long>
) : RecyclerView.Adapter<VideoCheckAdapter.CheckHolder>() {

    /** 勾选状态变化时回调（Activity 里刷新按钮文字） */
    var onCheckedChange: Runnable? = null

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): CheckHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.cell_video_check, parent, false)
        return CheckHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: CheckHolder, position: Int) {
        if (position < 0 || position >= videoList.size) return
        val card = videoList[position] ?: return

        holder.title.text = com.RobinNotBad.BiliClient.util.StringUtil.htmlToString(card.title ?: "")
        holder.upname.text = card.upName ?: ""
        holder.checkBox.isChecked = checkedAids.contains(card.aid)

        holder.itemView.setOnClickListener {
            if (checkedAids.contains(card.aid)) checkedAids.remove(card.aid)
            else checkedAids.add(card.aid)
            notifyItemChanged(holder.adapterPosition)
            onCheckedChange?.run()
        }
    }

    override fun getItemCount(): Int = videoList.size

    class CheckHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkBox: CheckBox = itemView.findViewById(R.id.check_box)
        val title: TextView = itemView.findViewById(R.id.text_title)
        val upname: TextView = itemView.findViewById(R.id.text_upname)
    }
}
