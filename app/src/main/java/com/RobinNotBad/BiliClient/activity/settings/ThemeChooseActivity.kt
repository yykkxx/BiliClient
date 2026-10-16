package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil

/**
 * 主题色调选择界面（#10）。
 *
 * 简化实现：用现成的 cell_choose 列表展示预设主题色名称，
 * 选择后写入 ui_theme_color 并关闭页面；新开的页面会套用新主题色。
 */
class ThemeChooseActivity : BaseActivity() {

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_simple_list)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        findViewById<View>(R.id.top).setOnClickListener { finish() }

        setPageName("主题色调")

        val adapter = Adapter(ThemeUtil.NAMES.toList())
        adapter.setOnItemClickListener(OnItemClickListener { position ->
            val name = ThemeUtil.NAMES[position]
            if (ThemeUtil.setColorByName(name)) {
                MsgUtil.showMsg("已切换为「$name」")
                // 主题色即时生效（#8）：重建当前页面套用新覆盖主题，
                // 其余页面在创建时自动套用
                recreate()
            }
        })

        recyclerView.layoutManager = CustomLinearManager(this)
        recyclerView.adapter = adapter
    }

    private class Adapter(private val nameList: List<String>) :
        RecyclerView.Adapter<com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder>() {

        private var listener: OnItemClickListener? = null

        fun setOnItemClickListener(l: OnItemClickListener?) {
            listener = l
        }

        @NonNull
        override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int):
            com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.cell_choose, parent, false)
            return com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder(view)
        }

        override fun onBindViewHolder(
            @NonNull holder: com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder,
            position: Int
        ) {
            val name = nameList[position]
            holder.folder_name.text = if (name == ThemeUtil.getColorName()) "$name ✓" else name
            holder.itemView.setOnClickListener { listener?.onItemClick(position) }
        }

        override fun getItemCount(): Int = nameList.size
    }
}
