package com.RobinNotBad.BiliClient.activity.video

import android.os.Bundle
import android.view.View

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.TimelineAdapter
import com.RobinNotBad.BiliClient.api.TimelineApi
import com.RobinNotBad.BiliClient.model.Timeline
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil

import java.util.ArrayList
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager

class TimelineActivity : InstanceActivity() {
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View

    private lateinit var dayInfoList: MutableList<Timeline.DayInfo>
    private var adapter: TimelineAdapter? = null
    private var types = "1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_refresh)

        setPageName("时间线")

        emptyView = findViewById(R.id.emptyTip)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout.isEnabled = true
        swipeRefreshLayout.isRefreshing = true
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = CustomLinearManager(this)

        dayInfoList = ArrayList()

        swipeRefreshLayout.setOnRefreshListener {
            dayInfoList.clear()
            loadTimeline()
        }

        loadTimeline()
    }

    private fun loadTimeline() {
        swipeRefreshLayout.isRefreshing = true
        CenterThreadPool.run(Runnable {
            try {
                val result = TimelineApi.getTimeline(types, 7, 7)
                runOnUiThread {
                    dayInfoList.addAll(result)
                    if (adapter == null) {
                        adapter = TimelineAdapter(this, dayInfoList)
                        recyclerView.adapter = adapter
                    } else {
                        adapter!!.notifyDataSetChanged()
                    }
                    swipeRefreshLayout.isRefreshing = false
                    if (dayInfoList.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        emptyView.visibility = View.VISIBLE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        emptyView.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    swipeRefreshLayout.isRefreshing = false
                    report(e)
                    MsgUtil.showMsgLong("加载失败")
                }
            }
        })
    }
}
