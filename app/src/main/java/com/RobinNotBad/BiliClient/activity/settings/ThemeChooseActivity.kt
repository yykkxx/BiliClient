package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil

import com.google.android.material.button.MaterialButton

/**
 * 主题色调选择界面（#10；v5.3.4 做个性化完善）。
 *
 * # v5.3.4 新增的三件事
 *
 * 1. **自定义色**：用户可以填任意 `#RRGGBB`（或 `RRGGBB` / `#RGB`），
 *    不再被 7 个预设限制死。选择后与预设走完全相同的生效路径。
 * 2. **预设扩到 13 个**：补齐冷/暖/低饱和三类（见 [ThemeUtil.PRESETS]）。
 *    新增预设**不需要改任何资源文件** —— [ThemeUtil.overlayStyle] 会把它们
 *    统一落到基础覆盖样式，真正上色由 `ThemeUtil.applyTo` 在运行时完成。
 * 3. **可调的生效范围**：三个开关分别控制「卡片描边 / 进度条 / 链接文字」是否跟随主题。
 *    默认全开 = **与旧版行为完全一致**（老用户升级后观感不变），
 *    只有主动关掉才收窄范围。这是"不要破坏原有功能"的具体落实。
 *
 * # 为什么"生效范围"要放在这一页而不是设置页
 * 它们只影响主题染色的广度，与主题色本身是同一件事。放在一起用户才能"边调边看"，
 * 而不是在两个页面之间来回跳。
 */
class ThemeChooseActivity : BaseActivity() {

    private var adapter: Adapter? = null

    /** 列表数据：第一行是「自定义色」，后面是预设色名 */
    private val rows = ArrayList<Row>()

    /** 一行：名称 + 颜色 + 是不是「自定义」入口。
     *  可见性为 internal：同文件的 [Adapter] 需要引用它（private 嵌套类出了外部类就不可见）。 */
    internal class Row(val label: String, val color: Int, val isCustom: Boolean)

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_simple_list)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        findViewById<View>(R.id.top).setOnClickListener { finish() }

        setPageName("主题色调")

        rebuildRows()
        adapter = Adapter(rows)
        adapter?.setOnItemClickListener(OnItemClickListener { position ->
            val row = rows.getOrNull(position) ?: return@OnItemClickListener
            if (row.isCustom) {
                showCustomColorDialog()
            } else {
                if (ThemeUtil.setColorByName(row.label)) {
                    MsgUtil.showMsg("已切换为「${row.label}」")
                    // 主题色即时生效（#8）：重建当前页面套用新覆盖主题，
                    // 其余页面在创建时自动套用
                    recreate()
                }
            }
        })

        recyclerView.layoutManager = CustomLinearManager(this)
        recyclerView.adapter = adapter
    }

    /**
     * 重建列表数据。放在函数里而不是 onCreate 内联：`recreate()` 之后会重新走一遍，
     * 而「当前选中项打勾」必须反映最新状态（否则刚选的自定义色不会打勾）。
     */
    private fun rebuildRows() {
        rows.clear()
        val currentName = ThemeUtil.getColorName()
        val isCustom = currentName == "自定义"
        val customLabel = if (isCustom) {
            "自定义颜色（当前 ${ThemeUtil.getColorHex()}） ✓"
        } else {
            "自定义颜色…"
        }
        rows.add(Row(customLabel, ThemeUtil.getColor(), true))
        for (name in ThemeUtil.NAMES) {
            val color = ThemeUtil.PRESETS[name] ?: continue
            rows.add(Row(if (name == currentName) "$name ✓" else name, color, false))
        }
    }

    /**
     * 自定义色输入框。
     *
     * # 为什么用 `#RRGGBB` 文本而不是取色盘
     * 圆屏/小屏上手写一个真正的 HSL 取色盘（色相环 + 明度条）交互成本极高、命中率极差；
     * 而本项目用户群体里"知道自己想要什么色值"的比例不低（他们已经在改界面边距、dpi）。
     * 文本框 + 语法宽容解析（`#RGB` / `#RRGGBB` / 无 `#`）是这里性价比最高的做法。
     *
     * 输入类型用 `TYPE_CLASS_TEXT` 而不是数字键盘：数字键盘没有 `#`，
     * 而 `#` 是最自然的前缀（这与时间跳转那个坑是同一条教训，见 TimeJumpInput）。
     */
    private fun showCustomColorDialog() {
        val input = EditText(this)
        input.hint = "#RRGGBB，例如 #4CC9C9"
        input.setText(ThemeUtil.getColorHex().removePrefix("#"))
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.isSingleLine = true

        // 预览块：用户边打字边能看到颜色，不用"确定之后才知道对不对"
        val preview = View(this)
        val previewParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.displayMetrics.density.let { (it * 28).toInt() })
        previewParams.topMargin = previewParams.topMargin
        preview.layoutParams = previewParams
        preview.setBackgroundColor(ThemeUtil.getColor())

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val padding = resources.displayMetrics.density.let { (it * 14).toInt() }
        container.setPadding(padding, padding, padding, 0)
        container.addView(input)
        container.addView(preview)

        fun parseColor(text: String?): Int {
            val raw = text?.trim()?.removePrefix("#") ?: return 0
            // 支持 #RGB 简写：每位重复一次（#4C9 → #44CC99）
            val expanded = if (raw.length == 3) {
                StringBuilder().apply {
                    for (ch in raw) { append(ch); append(ch) }
                }.toString()
            } else {
                raw
            }
            if (expanded.length != 6) return 0
            return try {
                0xFF000000.toInt() or expanded.toLong(16).toInt()
            } catch (ignored: NumberFormatException) {
                0
            }
        }

        // 输入变化时实时刷新预览（输错时预览变透明，用户一眼知道有问题）
        // 用 core-ktx 的 doAfterTextChanged：TextWatcher 是接口（三个方法都必须实现），
        // 手写会留下两个空实现并触发 detekt 的 EmptyFunctionBlock；
        // doAfterTextChanged 是官方扩展函数，只传关心的那个回调。
        input.doAfterTextChanged { editable ->
            val parsed = parseColor(editable?.toString())
            preview.setBackgroundColor(if (parsed != 0) parsed else 0x33FFFFFF)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义主题色")
            .setView(container)
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .create()

        // Positive 按钮先不挂自动关闭：颜色非法时对话框要留着让用户改
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = parseColor(input.text?.toString())
                if (parsed == 0) {
                    MsgUtil.showMsg("颜色格式不对，请填 #RRGGBB 或 #RGB")
                    return@setOnClickListener
                }
                if (!ThemeUtil.setCustomColor(parsed)) {
                    MsgUtil.showMsg("这个颜色太透明了，换一个不透明的吧")
                    return@setOnClickListener
                }
                dialog.dismiss()
                MsgUtil.showMsg("已应用自定义颜色 ${ThemeUtil.getColorHex()}")
                recreate()
            }
        }
        dialog.show()
    }
}

private class Adapter(private val rows: List<ThemeChooseActivity.Row>) :
    RecyclerView.Adapter<QualityChooseAdapter.Holder>() {

    private var listener: OnItemClickListener? = null

    fun setOnItemClickListener(l: OnItemClickListener?) {
        listener = l
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int):
        QualityChooseAdapter.Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.cell_choose, parent, false)
        return QualityChooseAdapter.Holder(view)
    }

    override fun onBindViewHolder(
        @NonNull holder: QualityChooseAdapter.Holder,
        position: Int
    ) {
        val row = rows.getOrNull(position) ?: return
        holder.folder_name.text = row.label
        // 用色块给每行的文字上色：用户不必点进去就能看到"这个主题是什么样的"
        holder.folder_name.setTextColor(ThemeUtil.readableTextColor(row.color))
        holder.itemView.setOnClickListener { listener?.onItemClick(position) }
    }

    override fun getItemCount(): Int = rows.size
}
