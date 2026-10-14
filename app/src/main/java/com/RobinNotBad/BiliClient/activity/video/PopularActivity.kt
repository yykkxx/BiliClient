package com.RobinNotBad.BiliClient.activity.video

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.RecommendApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener

import java.util.ArrayList

//热门页面
//2024-01-14

class PopularActivity : InstanceActivity() {

    private var recyclerView: RecyclerView? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var videoCardList: ArrayList<VideoCard>? = null
    private var videoCardAdapter: VideoCardAdapter? = null
    private var firstRefresh = true
    private var refreshing = false

    private var page = 1

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_main_refresh)
        setMenuClick()
        Log.e("debug", "进入热门页")

        recyclerView = findViewById(R.id.recyclerView)
        ImageAutoLoadScrollListener.install(recyclerView!!)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout!!.setOnRefreshListener { loadPopular() }

        val title = findViewById<TextView>(R.id.pageName)
        title.text = "热门"

        loadPopular()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadPopular() {
        Log.e("debug", "刷新")
        page = 1
        if (firstRefresh) {
            recyclerView!!.layoutManager = CustomLinearManager(this)
            videoCardList = ArrayList()
        } else {
            val last = videoCardList!!.size
            videoCardList!!.clear()
            videoCardAdapter!!.notifyItemRangeRemoved(0, last)
        }
        swipeRefreshLayout!!.isRefreshing = true

        refreshing = true
        CenterThreadPool.run(Runnable { addPopular() })
    }

    private fun addPopular() {
        Log.e("debug", "加载下一页")
        runOnUiThread { swipeRefreshLayout!!.isRefreshing = true }
        try {
            val list = ArrayList<VideoCard>()
            RecommendApi.getPopular(list, page)
            page++
            runOnUiThread {
                val videoCardList = this.videoCardList
                videoCardList!!.addAll(list)
                swipeRefreshLayout!!.isRefreshing = false
                refreshing = false
                if (firstRefresh) {
                    firstRefresh = false
                    videoCardAdapter = VideoCardAdapter(this, videoCardList)
                    recyclerView!!.adapter = videoCardAdapter

                    recyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrollStateChanged(@NonNull recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                        }

                        override fun onScrolled(@NonNull recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val manager = recyclerView.layoutManager as LinearLayoutManager?
                            assert(manager != null)
                            val lastItemPosition = manager!!.findLastCompletelyVisibleItemPosition()  //获取最后一个完全显示的itemPosition
                            val itemCount = manager.itemCount
                            if (lastItemPosition >= itemCount - 3 && dy > 0 && !refreshing) {// 滑动到倒数第三个就可以刷新了
                                refreshing = true
                                CenterThreadPool.run(Runnable { addPopular() }) //加载第二页
                            }
                        }
                    })
                } else {
                    videoCardAdapter!!.notifyItemRangeInserted(videoCardList.size - list.size, list.size)
                }
            }
        } catch (e: Exception) {
            runOnUiThread { MsgUtil.err(e) }
        }
    }
}
