package com.RobinNotBad.BiliClient.activity.video

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.RankingApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener

import java.util.ArrayList

class RankingActivity : InstanceActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var videoCardList: ArrayList<VideoCard>
    private lateinit var videoCardAdapter: VideoCardAdapter
    private var firstRefresh = true
    private var refreshing = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_main_refresh)
        setMenuClick()
        Log.e("debug", "进入排行榜页")

        recyclerView = findViewById(R.id.recyclerView)
        ImageAutoLoadScrollListener.install(recyclerView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout.setOnRefreshListener { loadRanking() }

        val title = findViewById<TextView>(R.id.pageName)
        title.text = "全站排行榜"

        loadRanking()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadRanking() {
        Log.e("debug", "刷新")
        if (firstRefresh) {
            recyclerView.layoutManager = CustomLinearManager(this)
            videoCardList = ArrayList()
        } else {
            val last = videoCardList.size
            videoCardList.clear()
            videoCardAdapter.notifyItemRangeRemoved(0, last)
        }
        swipeRefreshLayout.isRefreshing = true

        refreshing = true
        CenterThreadPool.run(Runnable { addRanking() })
    }

    private fun addRanking() {
        Log.e("debug", "加载排行榜")
        runOnUiThread { swipeRefreshLayout.isRefreshing = true }
        try {
            val list: MutableList<VideoCard> = ArrayList()
            RankingApi.getRanking(list, 0, "all")
            runOnUiThread {
                videoCardList.addAll(list)
                swipeRefreshLayout.isRefreshing = false
                refreshing = false
                if (firstRefresh) {
                    firstRefresh = false
                    videoCardAdapter = VideoCardAdapter(this, videoCardList)
                    recyclerView.adapter = videoCardAdapter
                } else {
                    videoCardAdapter.notifyItemRangeInserted(videoCardList.size - list.size, list.size)
                }
            }
        } catch (e: Exception) {
            runOnUiThread { MsgUtil.err(e) }
        }
    }
}
