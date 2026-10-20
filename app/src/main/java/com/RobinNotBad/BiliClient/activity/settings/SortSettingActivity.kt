package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.util.Pair

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.MenuActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.DragAdapter
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.Objects
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager

class SortSettingActivity : BaseActivity() {

    val data: MutableList<String> = ArrayList()
    val displayKeyMap: MutableMap<String, String> = HashMap()

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)

        setPageName("菜单排序")

        val sortConf = SharedPreferencesUtil.getString(SharedPreferencesUtil.MENU_SORT, "")
        var splitName: Array<String>? = null
        var matchConf = false
        if (!TextUtils.isEmpty(sortConf)) {
            splitName = sortConf!!.split(";").toTypedArray()
            matchConf = splitName.size == MenuActivity.btnNames.size
        }
        if (matchConf) {
            for (name in splitName!!) {
                if (!MenuActivity.btnNames.containsKey(name)) {
                    data.clear()
                    displayKeyMap.clear()
                    for (entry in MenuActivity.btnNames.entries) {
                        val displayText = entry.value.first
                        data.add(displayText)
                        displayKeyMap[displayText] = entry.key
                    }
                    break
                } else {
                    val displayText = java.util.Objects.requireNonNull(MenuActivity.btnNames[name])!!.first
                    data.add(displayText)
                    displayKeyMap[displayText] = name
                }
            }
        } else {
            for (entry in MenuActivity.btnNames.entries) {
                val key = entry.key
                val displayText = entry.value.first
                data.add(displayText)
                displayKeyMap[displayText] = key
            }
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = CustomLinearManager(this)
        val dragAdapter = DragAdapter(this, data)
        recyclerView.adapter = dragAdapter

        val dragCallBack = DragCallBack(dragAdapter, data)
        val itemTouchHelper = ItemTouchHelper(dragCallBack)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        dragAdapter.setOnItemClickListener(object : DragAdapter.OnItemClickListener {
            override fun onItemClick(position: Int) {
            }

            override fun onItemLongClick(holder: DragAdapter.ViewHolder) {
                if (holder.getAdapterPosition() != dragAdapter.getFixedPosition()) {
                    itemTouchHelper.startDrag(holder)
                }
            }
        })

        dragAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                save()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        MsgUtil.showMsg("拖动以排序~")
    }

    override fun onPause() {
        super.onPause()
        save()
        MsgUtil.showMsg("已保存")
    }

    private fun save() {
        val sb = StringBuilder()
        var flag = false
        for (s in data) {
            if (displayKeyMap.containsKey(s)) {
                if (flag) {
                    sb.append(";")
                } else {
                    flag = true
                }
                sb.append(displayKeyMap[s])
            }
        }
        SharedPreferencesUtil.putString(SharedPreferencesUtil.MENU_SORT, sb.toString())
    }

    class DragCallBack(private val mAdapter: DragAdapter, private val mData: MutableList<String>) : ItemTouchHelper.Callback() {

        override fun getMovementFlags(recyclerView: RecyclerView, @NonNull viewHolder: RecyclerView.ViewHolder): Int {
            val dragFlags: Int
            var swipeFlags = 0
            val layoutManager = recyclerView.layoutManager
            if (layoutManager is GridLayoutManager) {
                dragFlags = ItemTouchHelper.LEFT or ItemTouchHelper.UP or ItemTouchHelper.RIGHT or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, swipeFlags)
            } else if (layoutManager is LinearLayoutManager) {
                dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                swipeFlags = ItemTouchHelper.START or ItemTouchHelper.END
                return makeMovementFlags(dragFlags, swipeFlags)
            } else {
                return 0
            }
        }

        override fun onMove(@NonNull recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            // 起始位置
            val fromPosition = viewHolder.getAdapterPosition()
            // 结束位置
            val toPosition = target.getAdapterPosition()
            // 固定位置
            if (fromPosition == mAdapter.getFixedPosition() || toPosition == mAdapter.getFixedPosition()) {
                return false
            }
            // 根据滑动方向交换数据
            if (fromPosition < toPosition) {
                // 含头不含尾
                for (index in fromPosition until toPosition) {
                    Collections.swap(mData, index, index + 1)
                }
            } else {
                for (index in fromPosition downTo toPosition + 1) {
                    Collections.swap(mData, index, index - 1)
                }
            }
            // 刷新布局
            mAdapter.notifyItemMoved(fromPosition, toPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.getAdapterPosition()
            mData.removeAt(position)
            mAdapter.notifyItemRemoved(position)
        }

        override fun onSelectedChanged(@Nullable viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                if (viewHolder != null) {
                    ViewCompat.animate(viewHolder.itemView).setDuration(200).scaleX(1.3F).scaleY(1.3F).start()
                }
            }
            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, @NonNull viewHolder: RecyclerView.ViewHolder) {
            // 恢复显示
            // 这里不能用if判断，因为GridLayoutManager是LinearLayoutManager的子类，改用switch，类型推导有区别
            when (Objects.requireNonNull<RecyclerView.LayoutManager?>(recyclerView.layoutManager)!!.javaClass.getSimpleName()) {
                "GridLayoutManager", "LinearLayoutManager" -> ViewCompat.animate(viewHolder.itemView).setDuration(200).scaleX(1F).scaleY(1F).start()
            }
            super.clearView(recyclerView, viewHolder)
        }

        override fun isLongPressDragEnabled(): Boolean {
            return super.isLongPressDragEnabled()
        }

        override fun isItemViewSwipeEnabled(): Boolean {
            return super.isItemViewSwipeEnabled()
        }
    }

}
