package com.RobinNotBad.BiliClient.activity.user

import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.BangumiApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil


//追番列表
//2024-06-07

class FollowingBangumisActivity : RefreshListActivity() {

    private var videoList: ArrayList<VideoCard>? = null
    private var videoCardAdapter: VideoCardAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setVideoCardLayout()

        setPageName("追番列表")

        recyclerView!!.setHasFixedSize(true)

        videoList = ArrayList()

        val videoListLocal = videoList
        CenterThreadPool.run(Runnable {
            try {
                val result = BangumiApi.getFollowingList(page, videoListLocal!!)
                if (result != -1) {
                    val adapter = VideoCardAdapter(this, videoListLocal)
                    adapter.feedLayout = ListLayoutUtil.isDualColumnVideo()
                    videoCardAdapter = adapter
                    setOnLoadMoreListener { p -> continueLoading(p) }
                    setRefreshing(false)
                    setAdapter(adapter)

                    if (result == 1) {
                        Log.e("debug", "到底了")
                        setBottom(true)
                    }
                }

            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    private fun continueLoading(p: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val list = ArrayList<VideoCard>()
                val result = BangumiApi.getFollowingList(p, list)
                if (result != -1) {
                    Log.e("debug", "下一页")
                    runOnUiThread {
                        videoList!!.addAll(list)
                        videoCardAdapter!!.notifyItemRangeInserted(videoList!!.size - list.size, list.size)
                    }
                    if (result == 1) {
                        Log.e("debug", "到底了")
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
