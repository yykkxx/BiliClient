package com.RobinNotBad.BiliClient.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R

class DragAdapter(private val mContext: Context, private val mList: List<String>) : RecyclerView.Adapter<DragAdapter.ViewHolder>() {

    companion object {
        private const val fixedPosition = -1 // 固定菜单
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        val view: View = LayoutInflater.from(mContext).inflate(R.layout.item_drag_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        if (position < 0 || position >= mList.size)
            return
        holder.mItemTextView.setText(mList[position])

        holder.mItemTextView.setOnClickListener {
            val listener = mListener
            if (listener != null) {
                val adapterPosition = holder.adapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    listener.onItemClick(adapterPosition)
                }
            }
        }

        holder.mItemTextView.setOnLongClickListener {
            val listener = mListener
            if (listener != null) {
                listener.onItemLongClick(holder)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int {
        return if (mList != null) mList.size else 0
    }

    fun getFixedPosition(): Int {
        return fixedPosition
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val mItemTextView: TextView = itemView.findViewById(R.id.item)
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int)

        fun onItemLongClick(holder: ViewHolder)
    }

    private var mListener: OnItemClickListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener) {
        mListener = listener
    }
}
