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
        this.selectedItemIndex = -1

        for (i in qualityValues.indices) {
            if (qualityValues[i] == currentQuality) {
                this.selectedItemIndex = i
                break
            }
        }

        if (this.selectedItemIndex == -1 && qualityValues.size > 0) {
            this.selectedItemIndex = 0
        }

        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    fun setSelectedItemIndex(selectedItemIndex: Int) {
        val previousSelectedIndex = this.selectedItemIndex
        this.selectedItemIndex = selectedItemIndex
        notifyItemChanged(previousSelectedIndex)
        notifyItemChanged(selectedItemIndex)
    }

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
