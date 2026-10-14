package com.RobinNotBad.BiliClient.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.Announcement
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.google.android.material.card.MaterialCardView

import java.util.ArrayList

//公告
//2024-02-23

class AnnouncementAdapter(
    @JvmField val context: Context,
    @JvmField val list: ArrayList<Announcement>
) : RecyclerView.Adapter<AnnouncementAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view: View = LayoutInflater.from(this.context).inflate(R.layout.cell_announcement_list, parent, false)
        return Holder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position < 0 || position >= list.size)
            return
        val announcement: Announcement? = list[position]
        if (announcement == null)
            return

        val content: String? = announcement.content
        holder.name.setText(announcement.title)
        val extra_start: Int = if (content != null) content.indexOf("<extra_insert>") else -1
        holder.content.setText(
            if (extra_start == -1) content else content!!.substring(0, extra_start) + "[附加内容]"
        )
        holder.info.setText("ID:" + announcement.id + " | " + announcement.ctime)

        holder.cardView.setOnClickListener { view -> MsgUtil.showText(announcement.title, announcement.content) }
    }

    override fun getItemCount(): Int {
        return list.size
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val name: TextView = itemView.findViewById(R.id.name)

        @JvmField
        val content: TextView = itemView.findViewById(R.id.content)

        @JvmField
        val info: TextView = itemView.findViewById(R.id.info)

        @JvmField
        val cardView: MaterialCardView = itemView.findViewById(R.id.cardView)
    }
}
