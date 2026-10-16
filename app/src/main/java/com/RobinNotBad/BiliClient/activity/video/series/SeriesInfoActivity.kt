package com.RobinNotBad.BiliClient.activity.video.series

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.SeriesApi
import com.RobinNotBad.BiliClient.model.PageInfo
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil

import java.util.ArrayList

class SeriesInfoActivity : RefreshListActivity() {

    private var type: String? = null
    private var mid: Long = 0
    private var sid = 0
    private var videoList: ArrayList<VideoCard>? = null
    private var videoCardAdapter: VideoCardAdapter? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setVideoCardLayout()

        val intent = getIntent()
        type = intent.getStringExtra("type")

        mid = intent.getLongExtra("mid", 0)
        sid = intent.getIntExtra("sid", 0)
        val name = intent.getStringExtra("name")

        setPageName(name)

        videoList = ArrayList()

        val videoListLocal = videoList!!
        val typeLocal = type
        val thisLocal = this
        CenterThreadPool.run(Runnable {
            try {
                val pageInfo = SeriesApi.getSeriesInfo(typeLocal ?: "", mid, sid, page, videoListLocal)
                if (pageInfo.return_ps != 0) {
                    val newAdapter = VideoCardAdapter(thisLocal, videoListLocal)
                    newAdapter.feedLayout = ListLayoutUtil.isDualColumnVideo()
                    videoCardAdapter = newAdapter

                    setOnLoadMoreListener { page -> continueLoading(page) }
                    setAdapter(newAdapter)

                    Log.e("debug", "return=" + pageInfo.return_ps + "require=" + pageInfo.require_ps)

                    if (pageInfo.return_ps < pageInfo.require_ps) {
                        Log.e("debug", "到底了")
                        setBottom(true)
                    }
                } else showEmptyView()
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading(page: Int) {
        val videoListLocal = videoList!!
        val typeLocal = type
        val adapterLocal = videoCardAdapter
        CenterThreadPool.run(Runnable {
            try {
                Log.e("debug", "下一页")
                val lastSize = videoListLocal.size
                val pageInfo = SeriesApi.getSeriesInfo(typeLocal ?: "", mid, sid, page, videoListLocal)
                runOnUiThread { adapterLocal!!.notifyItemRangeInserted(lastSize, pageInfo.return_ps) }

                if (pageInfo.return_ps < pageInfo.require_ps || pageInfo.return_ps == 0) {
                    Log.e("debug", "到底了")
                    setBottom(true)
                }
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }
}
