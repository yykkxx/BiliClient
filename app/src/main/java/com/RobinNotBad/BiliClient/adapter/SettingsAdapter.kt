package com.RobinNotBad.BiliClient.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.SettingSection
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial

//设置项
//2024-06-06

class SettingsAdapter(
    @JvmField val context: Context,
    @JvmField val list: List<SettingSection>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val typeMap: MutableMap<String, Int> = hashMapOf(
        "divider" to -1,
        "title" to -2,
        "switch" to 0,
        "choose" to 1,
        "input_int" to 2,
        "input_float" to 3,
        "input_string" to 4,
        "multi_choose" to 5
    )

    override fun getItemViewType(position: Int): Int {
        return typeMap[list[position].type!!]!!
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> ChooseHolder(
                LayoutInflater.from(this.context).inflate(R.layout.cell_setting_choose, parent, false))
            2, 3, 4 -> InputHolder(
                LayoutInflater.from(this.context).inflate(R.layout.cell_setting_input, parent, false))
            5 -> MultiChooseHolder(
                LayoutInflater.from(this.context).inflate(R.layout.cell_setting_multi_choose, parent, false))
            -1 -> DividerHolder(
                LayoutInflater.from(this.context).inflate(R.layout.cell_divider, parent, false))
            -2 -> TitleHolder(
                LayoutInflater.from(this.context).inflate(R.layout.cell_setting_title, parent, false))
            else -> SwitchHolder(
                LayoutInflater.from(this.context).inflate(R.layout.cell_setting_switch, parent, false))
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
        if (position < 0 || position >= list.size)
            return
        val settingSection: SettingSection? = list[position]
        if (settingSection == null)
            return

        when (holder.itemViewType) {
            -1 -> {
            }
            -2 -> {
                val titleHolder = holder as TitleHolder
                titleHolder.bind(settingSection)
            }
            1 -> {
                val chooseHolder = holder as ChooseHolder
                chooseHolder.bind(settingSection)
            }
            2, 3, 4 -> {
                val inputHolder = holder as InputHolder
                inputHolder.bind(settingSection)
            }
            5 -> {
                val multiChooseHolder = holder as MultiChooseHolder
                multiChooseHolder.bind(settingSection)
            }
            else -> {
                val switchHolder = holder as SwitchHolder
                switchHolder.bind(settingSection)
            }
        }
    }

    override fun getItemCount(): Int {
        return list.size
    }

    class SwitchHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val desc: TextView = itemView.findViewById(R.id.setting_switch_desc)

        @JvmField
        val switchMaterial: SwitchMaterial = itemView.findViewById(R.id.setting_switch)

        fun bind(settingSection: SettingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE)
            else {
                desc.setText(settingSection.desc)
                desc.setVisibility(View.VISIBLE)
            }
            switchMaterial.setText(settingSection.name)
            switchMaterial.setOnCheckedChangeListener(
                { buttonView, isChecked -> SharedPreferencesUtil.putBoolean(settingSection.id, isChecked) })
            switchMaterial.setChecked(SharedPreferencesUtil.getBoolean(settingSection.id,
                java.lang.Boolean.parseBoolean(settingSection.defaultValue)))
        }
    }

    class ChooseHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val chocola: RadioButton = itemView.findViewById(R.id.setting_choose_chocola)

        @JvmField
        val vanilla: RadioButton = itemView.findViewById(R.id.setting_choose_vanilla) // 我在思考这样的命名方式是否合理（玩艹猫玩的

        @JvmField
        val name: TextView = itemView.findViewById(R.id.setting_choose_name)

        @JvmField
        val desc: TextView = itemView.findViewById(R.id.setting_choose_desc)

        fun bind(settingSection: SettingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE)
            else {
                desc.setText(settingSection.desc)
                desc.setVisibility(View.VISIBLE)
            }
            name.setText(settingSection.name)
            val strings = settingSection.extra as Array<String>
            chocola.setText(strings[0])
            vanilla.setText(strings[1])

            val value = SharedPreferencesUtil.getBoolean(settingSection.id,
                java.lang.Boolean.parseBoolean(settingSection.defaultValue))
            chocola.setChecked(value)
            vanilla.setChecked(!value)

            chocola.setOnCheckedChangeListener(
                { buttonView, isChecked -> SharedPreferencesUtil.putBoolean(settingSection.id, isChecked) }) // 有些选项的true和false不能改了，所以交换
        }
    }

    class InputHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val input: EditText = itemView.findViewById(R.id.setting_input_edittext)

        @JvmField
        val desc: TextView = itemView.findViewById(R.id.setting_input_desc)

        @JvmField
        val name: TextView = itemView.findViewById(R.id.setting_input_name)

        fun bind(settingSection: SettingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE)
            else {
                desc.setText(settingSection.desc)
                desc.setVisibility(View.VISIBLE)
            }
            name.setText(settingSection.name)
            when (settingSection.type) {
                "input_int" -> {
                    val intValue = SharedPreferencesUtil.getInt(settingSection.id,
                        Integer.parseInt(settingSection.defaultValue))
                    input.setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
                    input.setText(java.lang.String.valueOf(intValue))
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {
                        }

                        override fun onTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {
                        }

                        override fun afterTextChanged(editable: Editable) {
                            try {
                                SharedPreferencesUtil.putInt(settingSection.id, Integer.parseInt(editable.toString()))
                            } catch (ignored: Exception) {
                            }
                        }
                    })
                }
                "input_float" -> {
                    val floatValue = SharedPreferencesUtil.getFloat(settingSection.id,
                        java.lang.Float.parseFloat(settingSection.defaultValue))
                    input.setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                    input.setText(java.lang.String.valueOf(floatValue))
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {
                        }

                        override fun onTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {
                        }

                        override fun afterTextChanged(editable: Editable) {
                            try {
                                SharedPreferencesUtil.putFloat(settingSection.id,
                                    java.lang.Float.parseFloat(editable.toString()))
                            } catch (ignored: Exception) {
                            }
                        }
                    })
                }
                else -> {
                    val strValue = SharedPreferencesUtil.getString(settingSection.id, settingSection.defaultValue) ?: ""
                    input.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                    input.setText(strValue)
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {
                        }

                        override fun onTextChanged(charSequence: CharSequence?, i: Int, i1: Int, i2: Int) {
                        }

                        override fun afterTextChanged(editable: Editable) {
                            SharedPreferencesUtil.putString(settingSection.id, editable.toString())
                        }
                    })
                }
            }
        }
    }

    /**
     * 多选一设置项（#4）：以 Chip 流式排布全部候选值，高亮当前选中项。
     * 选中值以字符串形式存进 SharedPreferences（与 play_qn 等 int 值通过
     * valueIds 一一对应）。
     */
    class MultiChooseHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val name: TextView = itemView.findViewById(R.id.setting_multi_name)

        @JvmField
        val desc: TextView = itemView.findViewById(R.id.setting_multi_desc)

        @JvmField
        val chipGroup: ChipGroup = itemView.findViewById(R.id.setting_multi_chips)

        fun bind(settingSection: SettingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE)
            else {
                desc.setText(settingSection.desc)
                desc.setVisibility(View.VISIBLE)
            }
            name.setText(settingSection.name)

            @Suppress("UNCHECKED_CAST")
            val options = settingSection.extra as? Pair<Array<String>, IntArray> ?: return
            val labels = options.first
            val valueIds = options.second

            val current = SharedPreferencesUtil.getInt(settingSection.id,
                settingSection.defaultValue?.toIntOrNull() ?: 0)

            chipGroup.removeAllViews()
            for (i in labels.indices) {
                val chip = Chip(chipGroup.context)
                chip.text = labels[i]
                chip.isCheckable = true
                chip.isCheckedIconVisible = false
                chip.id = View.generateViewId()
                // 高亮当前选中项：选中色走主题色，未选中保持灰
                if (valueIds[i] == current) {
                    chip.isChecked = true
                    chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(ThemeUtil.withAlpha(ThemeUtil.getColor(), 0x66))
                    chip.chipStrokeWidth = 0f
                } else {
                    chip.isChecked = false
                    chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt())
                    chip.chipStrokeColor = android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt())
                    chip.chipStrokeWidth = 1f * chip.resources.displayMetrics.density
                }
                chip.setOnClickListener {
                    SharedPreferencesUtil.putInt(settingSection.id, valueIds[i])
                    // 重新绑定以刷新高亮
                    (chipGroup.tag as? Runnable)?.run() ?: bind(settingSection)
                }
                chipGroup.addView(chip)
            }
        }
    }

    class DividerHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {

    }

    class TitleHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val titleText: TextView = itemView.findViewById(R.id.setting_title_text)

        fun bind(settingSection: SettingSection) {
            titleText.setText(settingSection.name)
        }
    }
}
