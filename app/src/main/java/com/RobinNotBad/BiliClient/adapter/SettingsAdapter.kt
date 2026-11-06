package com.RobinNotBad.BiliClient.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
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
        "multi_choose" to 5,
        "click" to 6
    )

    override fun getItemViewType(position: Int): Int {
        // RecyclerView 在测量阶段就会调这里，抛任何异常都是直接闪退。
        // 旧实现是 typeMap[list[position].type!!]!! —— 只要某个设置项的 type 拼错、
        // 为 null、或者越界，就是 NPE/IndexOutOfBounds，用户看到的就是「进设置页闪退」。
        // 现在越界/未知类型一律降级成 switch 行（0），宁可显示得朴素一点也不能崩。
        if (position < 0 || position >= list.size) return 0
        val type = list[position].type ?: return 0
        return typeMap[type] ?: 0
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
            6 -> ClickHolder(
                LayoutInflater.from(this.context).inflate(R.layout.cell_setting_click, parent, false))
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
            6 -> {
                val clickHolder = holder as ClickHolder
                clickHolder.bind(settingSection)
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

        /** 倒计时心跳所在线程（主线程；RecyclerView 的 bind 也在主线程） */
        private val guardHandler = Handler(Looper.getMainLooper())

        /** 当前挂着的倒计时任务；bind 开头 / 回收时移除，避免跑到别的行上 */
        private var guardTask: Runnable? = null

        /** 程序性 setChecked（回弹开关、绑定初值）期间屏蔽 OnCheckedChangeListener */
        private var suppressChange = false

        /** 当前倒计时对应的原始 desc 文案，结束后恢复 */
        private var originalDesc: CharSequence = ""

        fun bind(settingSection: SettingSection) {
            // 复用坑：ViewHolder 会被回收给别的行用，先把上一个倒计时彻底取消，
            // 否则滑动列表会让倒计时跑到别的行上，或对着已经回收的 View 操作。
            cancelGuard()
            bindDescText(settingSection)
            switchMaterial.setText(settingSection.name)

            val guard = settingSection.extra as? SwitchDisableGuard
            val sideEffect = settingSection.extra as? Runnable

            switchMaterial.setOnCheckedChangeListener(
                { buttonView, isChecked ->
                    if (!suppressChange) {
                        onSwitchChanged(settingSection, guard, sideEffect, isChecked)
                    }
                })
            val stored = SharedPreferencesUtil.getBoolean(settingSection.id,
                java.lang.Boolean.parseBoolean(settingSection.defaultValue))
            // 不带守卫的行：与旧版逐行为一致，照旧直接 setChecked（监听器会在值变化时触发一次）。
            // 带守卫的行：必须挡住监听器——复用到的 View 可能还残留上一行的选中态，
            // 不挡的话 setChecked(false) 会被当成「用户想关闭」而立刻启动倒计时。
            if (guard == null) switchMaterial.setChecked(stored) else setCheckedProgrammatically(stored)
        }

        /** desc 文案：空则隐藏，否则显示；同时记下原始文案供倒计时结束后恢复 */
        private fun bindDescText(settingSection: SettingSection) {
            val text = settingSection.desc
            if (text == null || text.isEmpty()) {
                originalDesc = ""
                desc.setVisibility(View.GONE)
            } else {
                originalDesc = text
                desc.setText(text)
                desc.setVisibility(View.VISIBLE)
            }
        }

        /** 程序性改开关状态：必须挡住监听器，否则回弹会再次进入拦截逻辑死循环 */
        private fun setCheckedProgrammatically(checked: Boolean) {
            suppressChange = true
            switchMaterial.setChecked(checked)
            suppressChange = false
        }

        private fun onSwitchChanged(
            settingSection: SettingSection,
            guard: SwitchDisableGuard?,
            sideEffect: Runnable?,
            isChecked: Boolean
        ) {
            // 倒计时进行中又点了开关：忽略（开关已被顶回「开」），不允许叠加、也不允许取消
            if (guardTask != null) {
                setCheckedProgrammatically(true)
                return
            }
            // 关闭方向 + 带守卫：不写偏好、把开关顶回「开」，原地倒计时 [SwitchDisableGuard.seconds] 秒
            if (!isChecked && guard != null && guard.seconds > 0) {
                setCheckedProgrammatically(true)
                startDisableGuard(settingSection, guard)
                return
            }
            SharedPreferencesUtil.putBoolean(settingSection.id, isChecked)
            sideEffect?.run()
        }

        /** 原地倒计时：每秒刷新 desc，归零后真正写入 false、恢复文案、执行确认回调 */
        private fun startDisableGuard(settingSection: SettingSection, guard: SwitchDisableGuard) {
            cancelGuard()
            tickGuard(settingSection, guard, guard.seconds)
        }

        private fun tickGuard(settingSection: SettingSection, guard: SwitchDisableGuard, remaining: Int) {
            if (remaining <= 0) {
                finishGuard(settingSection, guard)
                return
            }
            desc.setText(itemView.context.getString(R.string.tm2_guard_tip, guard.seconds, remaining))
            desc.setVisibility(View.VISIBLE)
            val task = Runnable { tickGuard(settingSection, guard, remaining - 1) }
            guardTask = task
            guardHandler.postDelayed(task, GUARD_TICK_MS)
        }

        private fun finishGuard(settingSection: SettingSection, guard: SwitchDisableGuard) {
            guardTask = null
            SharedPreferencesUtil.putBoolean(settingSection.id, false)
            setCheckedProgrammatically(false)
            restoreDesc()
            guard.onDisableConfirmed?.run()
        }

        private fun restoreDesc() {
            if (originalDesc.isEmpty()) {
                desc.setVisibility(View.GONE)
            } else {
                desc.setText(originalDesc)
                desc.setVisibility(View.VISIBLE)
            }
        }

        /**
         * 取消挂着的倒计时并把 suppress 标志复位（bind 开头、行被回收、重新开始倒计时三处共用）。
         * 顺手把 desc 恢复成原始文案，避免「取消后还停在『剩余 N 秒』」的假象。
         */
        private fun cancelGuard() {
            val task = guardTask
            guardTask = null
            suppressChange = false
            if (task == null) return
            guardHandler.removeCallbacks(task)
            restoreDesc()
        }

        init {
            // 行被回收时也清掉，避免 Handler 持有已分离的 View
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) = cancelGuard()
            })
        }

        private companion object {
            /** 倒计时心跳间隔：1 秒 */
            const val GUARD_TICK_MS = 1000L
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
            // extra 必须是 Array<String> 且至少两项，旧实现直接 `as Array<String>` + [0]/[1]，
            // 漏传 extra、传错类型、或数组只有一项都会当场 NPE/越界 —— 也是「进设置页闪退」的一种。
            // 这里改成安全取用：缺项就显示空，第二项不存在时干脆隐藏，功能降级但不崩。
            val strings = settingSection.extra as? Array<*>
            val first = strings?.getOrNull(0)?.toString() ?: ""
            val second = strings?.getOrNull(1)?.toString() ?: ""
            chocola.setText(first)
            vanilla.setText(second)
            vanilla.visibility = if (second.isEmpty()) View.GONE else View.VISIBLE

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
                    // defaultValue 若不是合法整数（漏填/写成空串/带单位），
                    // 旧实现 Integer.parseInt 会抛 NumberFormatException → 进设置页直接闪退。
                    val intValue = SharedPreferencesUtil.getInt(settingSection.id,
                        settingSection.defaultValue?.trim()?.toIntOrNull() ?: 0)
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
                        settingSection.defaultValue?.trim()?.toFloatOrNull() ?: 0f)
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
                // labels 与 valueIds 长度不一致时（配置写错）不能直接 valueIds[i]，
                // 那样是 ArrayIndexOutOfBounds → 进设置页闪退。
                val valueId = valueIds.getOrNull(i)
                // 高亮当前选中项：选中色走主题色，未选中保持灰
                if (valueId != null && valueId == current) {
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
                    val id = valueIds.getOrNull(i) ?: return@setOnClickListener
                    SharedPreferencesUtil.putInt(settingSection.id, id)
                    // 重新绑定以刷新高亮
                    (chipGroup.tag as? Runnable)?.run() ?: bind(settingSection)
                }
                chipGroup.addView(chip)
            }
        }
    }

    /**
     * 点击类设置项：extra 放 Runnable 作为点击回调（纯增量类型，不影响既有设置页）。
     */
    class ClickHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val name: TextView = itemView.findViewById(R.id.setting_click_name)

        @JvmField
        val desc: TextView = itemView.findViewById(R.id.setting_click_desc)

        fun bind(settingSection: SettingSection) {
            if (settingSection.desc == null || settingSection.desc.isEmpty())
                desc.setVisibility(View.GONE)
            else {
                desc.setText(settingSection.desc)
                desc.setVisibility(View.VISIBLE)
            }
            name.setText(settingSection.name)
            itemView.setOnClickListener {
                (settingSection.extra as? Runnable)?.run()
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
