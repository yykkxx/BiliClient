package com.RobinNotBad.BiliClient.activity.user

import android.os.Bundle

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.HistoryApi
import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil


//历史记录
//2023-08-18
//2024-04-30

class HistoryActivity : RefreshListActivity() {

    private var lastResult = ApiResult()
    private var videoList: ArrayList<VideoCard>? = null
    private var videoCardAdapter: VideoCardAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("历史记录")

        recyclerView!!.setHasFixedSize(true)

        videoList = ArrayList()

        val videoListLocal = videoList
        CenterThreadPool.run(Runnable {
            try {
                lastResult = HistoryApi.getHistory(lastResult, videoListLocal!!)
                if (lastResult.code == 0) {
                    val adapter = VideoCardAdapter(this, videoListLocal)
                    videoCardAdapter = adapter
                    setOnLoadMoreListener { p -> continueLoading(p) }
                    setRefreshing(false)
                    setAdapter(adapter)

                    if (lastResult.isBottom) {
                        setBottom(true)
                    }
                } else MsgUtil.showMsg(lastResult.message)

            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    private fun continueLoading(p: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val list = ArrayList<VideoCard>()
                lastResult = HistoryApi.getHistory(lastResult, list)
                if (lastResult.code == 0) {
                    runOnUiThread {
                        videoList!!.addAll(list)
                        videoCardAdapter!!.notifyItemRangeInserted(videoList!!.size - list.size, list.size)
                    }
                    if (lastResult.isBottom) {
                        setBottom(true)
                    }
                }
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }
}
