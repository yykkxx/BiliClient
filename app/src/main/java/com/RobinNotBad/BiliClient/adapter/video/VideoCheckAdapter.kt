package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.google.android.material.card.MaterialCardView

/**
 * 带复选框的视频列表（#7：收藏夹多选缓存用）。
 *
 * v5.2.1 交互口径（三处都能改勾选状态，用户不用去猜）：
 * 1. **点整行** → 切换勾选（最快、面积最大）；
 * 2. **直接点复选框** → 也是切换勾选（以前 CheckBox 是 clickable=false 的，点了没反应，
 *    和「有复选框」这个视觉预期相矛盾）；
 * 3. **长按整行** → 唤出「更多操作」（查看详情 / 仅选中此视频 / 复制标题）。
 *
 * 为什么长按不是「进入多选模式」：
 * 本页**本身就是**多选页（每行恒有复选框，单击就是勾选），再让长按去「进入多选模式」
 * 等于同一个动作做两遍，且会让「单击=勾选」变得可疑（用户会怀疑单击是不是别的意思）。
 * 反过来把长按留给「更多操作」收益明确：收藏夹里刷到一条想先看详情再决定要不要缓存的视频时，
 * 不必退出本页；也可能只需要「就缓存这一条」而不用先清空再勾。App 内其它视频列表
 * （VideoCardAdapter）的长按也是「对这条视频做点别的事」的语义，这条与全局习惯一致。
 *
 * 勾选集合由 Activity 持有（按 aid 记），滚动复用时由 isChecked 重建状态，避免 RecyclerView 复用错乱。
 */
class VideoCheckAdapter(
        private val context: Context,
        private val videoList: List<VideoCard>,
        private val checkedAids: MutableSet<Long>
) : RecyclerView.Adapter<VideoCheckAdapter.CheckHolder>() {

    /** 勾选状态变化时回调（Activity 里刷新选择条与底部按钮） */
    var onCheckedChange: Runnable? = null

    /** 长按某一行的回调；为 null 时按「无更多操作」处理 */
    var onItemMoreAction: ((VideoCard?) -> Unit)? = null

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): CheckHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.cell_video_check, parent, false)
        val holder = CheckHolder(view)
        // 监听器只挂一次（与 VideoCardAdapter 的做法一致）：bind 时每行都会发生几十次，
        // 每 bind 一次新建 lambda 是纯浪费；位置从 holder.boundPosition 读（bind 时写入）。
        holder.itemView.setOnClickListener { toggleByPosition(holder.boundPosition) }
        holder.itemView.setOnLongClickListener {
            val position = holder.boundPosition
            val card = if (position in videoList.indices) videoList[position] else null
            val callback = onItemMoreAction
            if (callback != null && card != null) {
                callback.invoke(card)
                true
            } else {
                false
            }
        }
        holder.checkBox.setOnClickListener { toggleByPosition(holder.boundPosition) }
        return holder
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: CheckHolder, position: Int) {
        holder.boundPosition = position
        if (position < 0 || position >= videoList.size) return
        val card = videoList[position]
        if (card == null) return

        val checked = checkedAids.contains(card.aid)
        holder.checkBox.isChecked = checked
        holder.title.text = com.RobinNotBad.BiliClient.util.StringUtil.htmlToString(card.title ?: "")
        holder.upname.text = card.upName ?: ""
        // 勾选态除了复选框本身，再给整行描一圈主题色：小方块在长列表里不容易一眼扫到，
        // 描边能让「哪些行已经选了」整列可见（不依赖复选框的渲染差异）。
        holder.card.strokeWidth = if (checked) ToolsUtil.dp2px(1.5f) else 0
        holder.card.strokeColor = ThemeUtil.getColor()
    }

    override fun getItemCount(): Int = videoList.size

    /** 按位置切换勾选并刷新该行；重复点击同一行不会因为 RecyclerView 复用而串行 */
    private fun toggleByPosition(position: Int) {
        if (position < 0 || position >= videoList.size) return
        val card = videoList[position] ?: return
        if (!checkedAids.remove(card.aid)) checkedAids.add(card.aid)
        notifyItemChanged(position)
        onCheckedChange?.run()
    }

    class CheckHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        /** 最近一次 bind 的位置（监听器只挂一次，位置必须从这里取） */
        var boundPosition: Int = -1

        val card: MaterialCardView = itemView.findViewById(R.id.cardView)
        val checkBox: CheckBox = itemView.findViewById(R.id.check_box)
        val title: TextView = itemView.findViewById(R.id.text_title)
        val upname: TextView = itemView.findViewById(R.id.text_upname)
    }
}
