package com.RobinNotBad.BiliClient.activity.video.series

import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.SeriesCardAdapter
import com.RobinNotBad.BiliClient.api.SeriesApi
import com.RobinNotBad.BiliClient.model.Series
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import java.util.ArrayList

//用户的视频系列列表
//2024-06-13

//我宣布：
//在此之后：series统一叫系列，collection统一叫合集
//我看b站他们自己都没搞明白，全都叫合集，请允许我在此问候下他们的开发者……
//2024-10-20

class UserSeriesActivity : RefreshListActivity() {

    private var mid: Long = 0
    private var seriesList: ArrayList<Series>? = null
    private var adapter: SeriesCardAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("投稿的系列")

        seriesList = ArrayList()
        mid = getIntent().getLongExtra("mid", 0)

        setOnLoadMoreListener { page -> continueLoading(page) }

        val seriesListLocal = seriesList!!
        val thisLocal = this
        CenterThreadPool.run(Runnable {
            try {
                bottom = SeriesApi.getUserSeries(mid, page, seriesListLocal) == 1
                setRefreshing(false)
                val newAdapter = SeriesCardAdapter(thisLocal, seriesListLocal)
                adapter = newAdapter
                setAdapter(newAdapter)
                if (bottom && seriesListLocal.isEmpty()) showEmptyView()
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    private fun continueLoading(page: Int) {
        val seriesListLocal = seriesList!!
        val adapterLocal = adapter
        CenterThreadPool.run(Runnable {
            try {
                val last = seriesListLocal.size
                val result = SeriesApi.getUserSeries(mid, page, seriesListLocal)
                if (result != -1) {
                    Log.e("debug", "下一页")
                    runOnUiThread { adapterLocal!!.notifyItemRangeInserted(last, seriesListLocal.size - last) }
                    if (result == 1) {
                        Log.e("debug", "到底了")
                        bottom = true
                    }
                }
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }
}
