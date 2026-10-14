package com.RobinNotBad.BiliClient.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener

import java.util.ArrayList

class QualityChooseAdapter(@JvmField val context: Context) : RecyclerView.Adapter<QualityChooseAdapter.Holder>() {

    @JvmField
    var nameList: List<String>? = ArrayList()

    @JvmField
    var onItemClickListener: OnItemClickListener? = null

    @JvmField
    var onItemLongClickListener: OnItemLongClickListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.onItemClickListener = listener
    }

    // 勾使。
    @SuppressLint("NotifyDataSetChanged")
    fun setNameList(newList: List<String>?) {
        this.nameList = newList
        notifyDataSetChanged()
    }

    fun getName(index: Int): String? {
        return if (nameList == null) null else nameList!![index]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view: View = LayoutInflater.from(this.context).inflate(R.layout.cell_choose, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {

        holder.folder_name.text = nameList!![position]

        holder.itemView.setOnClickListener { view ->
            if (onItemClickListener != null) onItemClickListener!!.onItemClick(position)
        }

        holder.itemView.setOnLongClickListener { view ->
            if (onItemLongClickListener != null) {
                onItemLongClickListener!!.onItemLongClick(position)
                true
            } else false
        }

    }

    override fun getItemCount(): Int {
        return nameList!!.size
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val folder_name: TextView = itemView.findViewById(R.id.text)
    }
}
