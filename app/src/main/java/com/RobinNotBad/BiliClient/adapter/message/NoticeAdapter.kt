package com.RobinNotBad.BiliClient.adapter.message

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.MessageCard

class NoticeAdapter(context: Context?, messageList: List<MessageCard>?) : RecyclerView.Adapter<NoticeHolder>() {
    var context: Context? = context
    var messageList: List<MessageCard>? = messageList

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): NoticeHolder {
        val view = LayoutInflater.from(this.context).inflate(R.layout.cell_message, parent, false)
        return NoticeHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: NoticeHolder, position: Int) {
        if (position < 0 || position >= messageList!!.size) return
        val message = messageList!![position] ?: return
        holder.showMessage(message, context!!)
    }

    override fun onViewRecycled(@NonNull holder: NoticeHolder) {
        if (holder.extraCard != null) {
            holder.extraCard.removeAllViews()
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        return messageList?.size ?: 0
    }
}
