package com.RobinNotBad.BiliClient.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.util.StringUtil

import java.util.ArrayList

class SearchHistoryAdapter(
    @JvmField val context: Context,
    @JvmField val historyList: ArrayList<String>
) : RecyclerView.Adapter<SearchHistoryAdapter.BtnListHolder>() {

    @JvmField
    var longClickListener: OnItemLongClickListener? = null

    @JvmField
    var clickListener: OnItemClickListener? = null

    fun setOnLongClickListener(listener: OnItemLongClickListener) {
        this.longClickListener = listener
    }

    fun setOnClickListener(listener: OnItemClickListener) {
        this.clickListener = listener
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): BtnListHolder {
        val view: View = LayoutInflater.from(this.context).inflate(R.layout.cell_choose, parent, false)
        return BtnListHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: BtnListHolder, position: Int) {
        if (position < 0 || position >= historyList.size)
            return
        holder.show(historyList[position])

        holder.itemView.setOnClickListener { view ->
            val listener = clickListener
            if (listener != null) {
                listener.onItemClick(position)
            }
        }

        holder.itemView.setOnLongClickListener { view ->
            val listener = longClickListener
            if (listener != null) {
                listener.onItemLongClick(position)
                true
            } else
                false
        }
    }

    override fun getItemCount(): Int {
        return if (historyList != null) historyList.size else 0
    }

    class BtnListHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val text_view: TextView = itemView.findViewById(R.id.text)

        fun show(text: String) {
            text_view.setText(StringUtil.htmlToString(text))
        }
    }
}
