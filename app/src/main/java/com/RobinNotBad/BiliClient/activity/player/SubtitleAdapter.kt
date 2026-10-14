package com.RobinNotBad.BiliClient.activity.player

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button

import androidx.annotation.NonNull
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.model.SubtitleLink

class SubtitleAdapter : RecyclerView.Adapter<SubtitleAdapter.Holder>() {
    private var list: Array<SubtitleLink?>? = null

    @JvmField
    var listener: OnItemClickListener? = null

    @JvmField
    var selectedItemIndex = 0

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    fun setSelectedItemIndex(selectedItemIndex: Int) {
        // Cancel previous selected item and set current item
        val previousSelectedIndex = this.selectedItemIndex
        this.selectedItemIndex = selectedItemIndex
        notifyItemChanged(previousSelectedIndex)
        notifyItemChanged(selectedItemIndex)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setData(episodeList: Array<SubtitleLink?>?) {
        this.list = episodeList
        selectedItemIndex = 0
        notifyDataSetChanged()
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.cell_subtitle, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(@NonNull holder: Holder, position: Int) {
        if (position < 0 || list == null || position >= list!!.size) return
        if (listener != null) {
            holder.listener = listener
        }
        holder.bind(position, selectedItemIndex == position)
    }

    override fun getItemCount(): Int {
        return list?.size ?: 0
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        var listener: OnItemClickListener? = null
        private val button: Button

        init {
            button = itemView.findViewById(R.id.btn)
        }

        fun bind(currentIndex: Int, isSelected: Boolean) {
            if (currentIndex < 0 || list == null || currentIndex >= list!!.size) return
            button.text = list!![currentIndex]!!.lang
            if (isSelected) {
                button.setTextColor(0xcc262626.toInt())
                ViewCompat.setBackgroundTintList(button, AppCompatResources.getColorStateList(itemView.context,
                        R.color.background_button_selected))
            } else {
                button.setTextColor(0xffebe0e2.toInt())
                ViewCompat.setBackgroundTintList(button,
                        AppCompatResources.getColorStateList(itemView.context, R.color.background_button))
            }
            button.setOnClickListener { v ->
                setSelectedItemIndex(currentIndex)
                if (listener != null) {
                    listener!!.onItemClick(currentIndex)
                }
            }
        }
    }
}
