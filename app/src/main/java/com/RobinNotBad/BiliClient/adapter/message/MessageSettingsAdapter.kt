package com.RobinNotBad.BiliClient.adapter.message

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.message.MessageSettingItem
import com.google.android.material.switchmaterial.SwitchMaterial

class MessageSettingsAdapter(context: Context?, items: List<MessageSettingItem>?, listener: OnSettingChangedListener?) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val context: Context? = context
    private val items: List<MessageSettingItem>? = items
    private val listener: OnSettingChangedListener? = listener

    interface OnSettingChangedListener {
        fun onSettingChanged(key: String?, value: Boolean)
    }

    override fun getItemViewType(position: Int): Int {
        return items!![position].type
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == MessageSettingItem.TYPE_CHOOSE) {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_setting_choose, parent, false)
            return ChooseHolder(view)
        } else {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_setting_switch, parent, false)
            return SwitchHolder(view)
        }
    }

    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
        val item = items!![position]
        if (holder is SwitchHolder) {
            (holder as SwitchHolder).bind(item, listener)
        } else if (holder is ChooseHolder) {
            (holder as ChooseHolder).bind(item, listener)
        }
    }

    override fun getItemCount(): Int {
        return items?.size ?: 0
    }

    class SwitchHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val desc: TextView
        val switchMaterial: SwitchMaterial

        init {
            desc = itemView.findViewById(R.id.setting_switch_desc)
            switchMaterial = itemView.findViewById(R.id.setting_switch)
        }

        fun bind(item: MessageSettingItem, listener: OnSettingChangedListener?) {
            if (item.desc.isEmpty()) {
                desc.setVisibility(View.GONE)
            } else {
                desc.setText(item.desc)
                desc.setVisibility(View.VISIBLE)
            }

            switchMaterial.setText(item.title)
            switchMaterial.setOnCheckedChangeListener(null)
            switchMaterial.setChecked(item.value)
            switchMaterial.setOnCheckedChangeListener { buttonView, isChecked ->
                item.value = isChecked
                if (listener != null) {
                    listener.onSettingChanged(item.key, isChecked)
                }
            }
        }
    }

    class ChooseHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val chocola: RadioButton
        val vanilla: RadioButton
        val name: TextView
        val desc: TextView

        init {
            chocola = itemView.findViewById(R.id.setting_choose_chocola)
            vanilla = itemView.findViewById(R.id.setting_choose_vanilla)
            desc = itemView.findViewById(R.id.setting_choose_desc)
            name = itemView.findViewById(R.id.setting_choose_name)
        }

        fun bind(item: MessageSettingItem, listener: OnSettingChangedListener?) {
            if (item.desc.isEmpty()) {
                desc.setVisibility(View.GONE)
            } else {
                desc.setText(item.desc)
                desc.setVisibility(View.VISIBLE)
            }

            name.setText(item.title)

            if (item.options != null && item.options!!.size >= 2) {
                chocola.setText(item.options!![0])
                vanilla.setText(item.options!![1])
            }

            chocola.setOnCheckedChangeListener(null)
            vanilla.setOnCheckedChangeListener(null)

            chocola.setChecked(item.value)
            vanilla.setChecked(!item.value)

            chocola.setOnCheckedChangeListener { buttonView, isChecked ->
                item.value = isChecked
                if (listener != null) {
                    listener.onSettingChanged(item.key, isChecked)
                }
            }
        }
    }
}
