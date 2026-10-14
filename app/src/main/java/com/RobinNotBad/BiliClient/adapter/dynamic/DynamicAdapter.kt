package com.RobinNotBad.BiliClient.adapter.dynamic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.activity.result.ActivityResultLauncher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ListChooseActivity
import com.RobinNotBad.BiliClient.activity.dynamic.DynamicActivity
import com.RobinNotBad.BiliClient.activity.dynamic.send.SendDynamicActivity
import com.RobinNotBad.BiliClient.activity.live.FollowLiveActivity
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.button.MaterialButton

import java.util.ArrayList
import java.util.Arrays

//动态Adapter 显示部分在单独的DynamicHolder里
//2023-09-28

class DynamicAdapter(val context: Context, val dynamicList: List<Dynamic>?, val recyclerView: RecyclerView, recentUpList: List<DynamicApi.UpInfo>?) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val dynamicActivity: DynamicActivity = context as DynamicActivity
    val writeDynamicLauncher: ActivityResultLauncher<Intent> = dynamicActivity.writeDynamicLauncher

    @JvmField
    var recentUpList: List<DynamicApi.UpInfo>? = recentUpList

    private fun showRecentUp(): Boolean {
        val recentUpList = this.recentUpList ?: return false
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.RECENT_UP_DISPLAY_ENABLE, true)
                && !recentUpList.isEmpty()
    }

    override fun getItemViewType(position: Int): Int {
        if (position == 0) {
            return 0
        } else if (position == 1 && showRecentUp()) {
            return 2
        } else {
            return 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            val view = LayoutInflater.from(this.context).inflate(R.layout.cell_dynamic_action, parent, false)
            return WriteDynamic(view)
        } else if (viewType == 2) {
            val view = LayoutInflater.from(this.context).inflate(R.layout.cell_recent_up_list, parent, false)
            return RecentUpListHolder(view)
        } else {
            return DynamicHolder(LayoutInflater.from(this.context).inflate(R.layout.cell_dynamic, parent, false),
                    dynamicActivity, false)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is WriteDynamic) {
            val writeDynamic = holder
            writeDynamic.write_dynamic.setOnClickListener {
                val intent = Intent()
                intent.setClass(context, SendDynamicActivity::class.java)
                writeDynamicLauncher.launch(intent)
            }
            writeDynamic.type.setOnClickListener {
                dynamicActivity.selectTypeLauncher
                        .launch(Intent().setClass(context, ListChooseActivity::class.java).putExtra("title", "选择类型")
                                .putExtra("items", ArrayList(Arrays.asList("全部", "视频投稿", "追番", "专栏"))))
            }
            writeDynamic.live.setOnClickListener {
                val intent = Intent(context, FollowLiveActivity::class.java)
                context.startActivity(intent)
            }
        } else if (holder is RecentUpListHolder) {
            val recentUpListHolder = holder
            if (recentUpListHolder.recentUpAdapter == null) {
                recentUpListHolder.recentUpRecyclerView.setLayoutManager(LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false))
                recentUpListHolder.recentUpAdapter = RecentUpAdapter(context, recentUpList)
                recentUpListHolder.recentUpRecyclerView.adapter = recentUpListHolder.recentUpAdapter
            }
        } else if (holder is DynamicHolder) {
            val dynamicList = this.dynamicList ?: return
            val realPosition = position - (if (showRecentUp()) 2 else 1)
            if (realPosition < 0 || realPosition >= dynamicList.size)
                return

            val dynamic = dynamicList[realPosition] ?: return

            val dynamicHolder = holder
            dynamicHolder.showDynamic(context, dynamic, true)

            if (dynamic.dynamic_forward != null) {
                val childCard = dynamicHolder.cell_dynamic_child
                if (childCard != null) {
                    if (dynamicHolder.childDynamicHolder == null) {
                        dynamicHolder.childDynamicHolder = DynamicHolder(childCard, dynamicActivity, true)
                    }
                    val forward = dynamic.dynamic_forward
                    if (forward != null) dynamicHolder.childDynamicHolder!!.showDynamic(context, forward, true)
                    childCard.visibility = View.VISIBLE
                }
            } else {
                dynamicHolder.cell_dynamic_child?.visibility = View.GONE
            }

            val onDeleteLongClick = DynamicHolder.getDeleteListener(dynamicActivity, dynamicList,
                    realPosition, this, showRecentUp())
            val itemDynamicDelete = dynamicHolder.item_dynamic_delete
            if (itemDynamicDelete != null) {
                itemDynamicDelete.setOnLongClickListener(onDeleteLongClick)
                if (dynamic.canDelete)
                    itemDynamicDelete.visibility = View.VISIBLE
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        val baseCount = if (dynamicList != null) dynamicList.size + 1 else 1
        return if (showRecentUp()) baseCount + 1 else baseCount
    }

    class WriteDynamic(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val write_dynamic: MaterialButton = itemView.findViewById(R.id.write_dynamic)
        val type: MaterialButton = itemView.findViewById(R.id.type)
        val live: MaterialButton = itemView.findViewById(R.id.live)
    }

    class RecentUpListHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val recentUpRecyclerView: RecyclerView = itemView.findViewById(R.id.recentUpRecyclerView)
        var recentUpAdapter: RecentUpAdapter? = null
    }
}
