package com.RobinNotBad.BiliClient.adapter.dynamic

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.util.GlideUtil

class RecentUpAdapter(private val context: Context, private val upList: List<DynamicApi.UpInfo>?) :
    RecyclerView.Adapter<RecentUpAdapter.ViewHolder>() {

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        val view: View = LayoutInflater.from(context).inflate(R.layout.cell_up_avatar, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        val upInfo = upList!![position]

        GlideUtil.requestRound(holder.avatar, upInfo.face ?: "", R.mipmap.akari)
        holder.name.text = upInfo.uname

        if (upInfo.has_update) {
            holder.updateIndicator.visibility = View.VISIBLE
        } else {
            holder.updateIndicator.visibility = View.GONE
        }

        holder.itemView.isClickable = true
        holder.itemView.isFocusable = true
        holder.itemView.setOnClickListener { v -> BiliTerminal.jumpToUser(context, upInfo.mid) }
        holder.avatar.setOnClickListener { v -> BiliTerminal.jumpToUser(context, upInfo.mid) }
    }

    override fun getItemCount(): Int {
        return upList?.size ?: 0
    }

    class ViewHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: ImageView = itemView.findViewById(R.id.avatar)
        val name: TextView = itemView.findViewById(R.id.name)
        val updateIndicator: View = itemView.findViewById(R.id.updateIndicator)
    }
}
