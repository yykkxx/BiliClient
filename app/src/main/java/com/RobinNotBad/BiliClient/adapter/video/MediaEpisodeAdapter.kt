package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.model.Bangumi
import com.google.android.material.button.MaterialButton

class MediaEpisodeAdapter() : RecyclerView.Adapter<MediaEpisodeAdapter.EpisodeHolder>() {

    private var listener: OnItemClickListener? = null
    private var selectedItemIndex: Int = 0
    private var useVerticalLayout: Boolean = false

    constructor(useVerticalLayout: Boolean) : this() {
        this.useVerticalLayout = useVerticalLayout
    }

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
    fun setData(episodeList: List<Bangumi.Episode>?) {
        this.episodeList = episodeList
        selectedItemIndex = 0
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
        val contextWrapper = ContextThemeWrapper(parent.context, R.style.Theme_BiliClient)
        val view = LayoutInflater.from(contextWrapper)
            .inflate(if (useVerticalLayout) R.layout.cell_item_vertical else R.layout.cell_episode, parent, false)
        return EpisodeHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
        if (position < 0 || episodeList == null || position >= episodeList!!.size) return
        if (listener != null) {
            holder.listener = listener
        }
        holder.bind(position, selectedItemIndex == position)
    }

    override fun getItemCount(): Int {
        return episodeList?.size ?: 0
    }

    inner class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view) {

        var listener: OnItemClickListener? = null
        private val button: MaterialButton = itemView.findViewById(R.id.btn)

        fun bind(currentIndex: Int, isSelected: Boolean) {
            if (currentIndex < 0 || episodeList == null || currentIndex >= episodeList!!.size) return
            button.text = episodeList!![currentIndex].title ?: ""
            if (isSelected) {
                button.setTextColor(-0x33d9d9da)
                ViewCompat.setBackgroundTintList(button, AppCompatResources.getColorStateList(itemView.context,
                    R.color.background_button_selected))
            } else {
                button.setTextColor(-0x141f1e)
                ViewCompat.setBackgroundTintList(button,
                    AppCompatResources.getColorStateList(itemView.context, R.color.background_button))
            }
            button.setOnClickListener {
                setSelectedItemIndex(currentIndex)
                if (listener != null) {
                    listener!!.onItemClick(currentIndex)
                }
            }
        }
    }

    private var episodeList: List<Bangumi.Episode>? = null
}
