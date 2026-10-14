package com.RobinNotBad.BiliClient.adapter.message

import android.view.View
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.Reply

class ReplyCardHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
    val content: TextView
    val tiptext: TextView

    init {
        content = itemView.findViewById(R.id.content)
        tiptext = itemView.findViewById(R.id.tip)
    }

    fun showReplyCard(replyInfo: Reply?) {
        content.setText(replyInfo!!.message)
    }
}
