package com.RobinNotBad.BiliClient.adapter

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.annotation.NonNull
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.google.android.material.button.MaterialButton

class QualitySelectorAdapter : RecyclerView.Adapter<QualitySelectorAdapter.QualityHolder>() {
    private var qualityNames: Array<String>? = null
    private var qualityValues: IntArray? = null

    @JvmField
    var listener: OnItemClickListener? = null

    @JvmField
    var selectedItemIndex: Int = 0

    @SuppressLint("NotifyDataSetChanged")
    fun setData(qualityNames: Array<String>, qualityValues: IntArray, currentQuality: Int) {
        this.qualityNames = qualityNames
        this.qualityValues = qualityValues
        this.selectedItemIndex = indexOfQuality(currentQuality)
        // 当前清晰度不在可选列表里时，退回列表第一项（与实际生效的清晰度一致）
        this.selectedItemIndex = if (selectedItemIndex >= 0) selectedItemIndex
            else if (qualityValues.size > 0) 0 else -1
        this.selectedQuality = if (selectedItemIndex >= 0) qualityValues[selectedItemIndex] else currentQuality

        notifyDataSetChanged()
    }

    /** 返回清晰度值在列表中的下标，找不到返回 -1。 */
    private fun indexOfQuality(quality: Int): Int {
        val values = qualityValues ?: return -1
        for (i in values.indices) {
            if (values[i] == quality) return i
        }
        return -1
    }

    /** 记录当前选中项对应的清晰度值，便于外部判断。 */
    @JvmField
    var selectedQuality: Int = 0

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    fun setSelectedItemIndex(selectedItemIndex: Int) {
        val previousSelectedIndex = this.selectedItemIndex
        val values = qualityValues
        if (values != null && selectedItemIndex in values.indices) {
            this.selectedQuality = values[selectedItemIndex]
        }
        this.selectedItemIndex = selectedItemIndex
        if (previousSelectedIndex in 0 until itemCountSafe()) notifyItemChanged(previousSelectedIndex)
        if (selectedItemIndex in 0 until itemCountSafe()) notifyItemChanged(selectedItemIndex)
    }

    private fun itemCountSafe(): Int = qualityNames?.size ?: 0

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): QualityHolder {
        val contextWrapper = ContextThemeWrapper(parent.context, R.style.Theme_BiliClient)
        val view: View = LayoutInflater.from(contextWrapper)
            .inflate(R.layout.cell_episode, parent, false)
        return QualityHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: QualityHolder, position: Int) {
        if (position < 0 || qualityNames == null || position >= qualityNames!!.size)
            return
        if (listener != null) {
            holder.listener = listener
        }
        holder.bind(position, selectedItemIndex == position)
    }

    override fun getItemCount(): Int {
        return if (qualityNames != null) qualityNames!!.size else 0
    }

    // Java 版为内部类（可直接访问外部适配器字段），Kotlin 需显式 inner
    inner class QualityHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        var listener: OnItemClickListener? = null

        private val button: MaterialButton = itemView.findViewById(R.id.btn)

        fun bind(currentIndex: Int, isSelected: Boolean) {
            if (currentIndex < 0 || qualityNames == null || currentIndex >= qualityNames!!.size)
                return
            button.text = qualityNames!![currentIndex]
            if (isSelected) {
                button.setTextColor(0xcc262626.toInt())
                ViewCompat.setBackgroundTintList(button, AppCompatResources.getColorStateList(itemView.context,
                    R.color.background_button_selected))
            } else {
                button.setTextColor(0xffebe0e2.toInt())
                ViewCompat.setBackgroundTintList(button,
                    AppCompatResources.getColorStateList(itemView.context, R.color.background_button))
            }
            button.setOnClickListener { _ ->
                this@QualitySelectorAdapter.setSelectedItemIndex(currentIndex)
                if (listener != null) {
                    listener!!.onItemClick(currentIndex)
                }
            }
        }
    }
}
