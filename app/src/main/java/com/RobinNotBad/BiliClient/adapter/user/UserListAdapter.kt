package com.RobinNotBad.BiliClient.adapter.user

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

//关注列表
//2023-08-29

open class UserListAdapter(val context: Context, private val userList: List<UserInfo>?) :
    RecyclerView.Adapter<UserListAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(context).inflate(R.layout.cell_user_list, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position < 0 || userList == null || position >= userList.size) return
        val user = userList[position] ?: return

        holder.name.text = user.name ?: ""
        if (user.vip_nickname_color != null && user.vip_nickname_color!!.isNotEmpty()) {
            try {
                holder.name.setTextColor(Color.parseColor(user.vip_nickname_color))
            } catch (e: IllegalArgumentException) {
                holder.name.setTextColor(Color.WHITE)
            }
        }
        holder.desc.text = user.sign ?: ""

        if (user.avatar == null || user.avatar!!.isEmpty()) {
            holder.avatar.visibility = View.GONE
            holder.desc.setSingleLine(false)
        } else {
            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(user.avatar!!))
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(R.mipmap.akari)
                .apply(RequestOptions.circleCropTransform())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(holder.avatar)
            holder.avatar.visibility = View.VISIBLE
            holder.desc.setSingleLine(true)
        }

        if (user.mid != -1L) {
            holder.itemView.setOnClickListener {
                val intent = Intent()
                    .setClass(context, UserInfoActivity::class.java)
                    .putExtra("mid", user.mid)
                context.startActivity(intent)
            }
        }
    }

    override fun getItemCount(): Int {
        return userList?.size ?: 0
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.userName)
        val desc: TextView = itemView.findViewById(R.id.userDesc)
        val avatar: ImageView = itemView.findViewById(R.id.userAvatar)
    }
}
