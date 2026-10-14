package com.RobinNotBad.BiliClient.activity.live

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity
import com.RobinNotBad.BiliClient.adapter.LiveCardAdapter
import com.RobinNotBad.BiliClient.api.LiveApi
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import java.util.ArrayList

open class RecommendLiveActivity : RefreshMainActivity() {
    private var roomList: MutableList<LiveRoom>? = null
    private var adapter: LiveCardAdapter? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("推荐直播")

        recyclerView!!.setHasFixedSize(true)

        roomList = ArrayList()

        setMenuClick()

        CenterThreadPool.run(Runnable {
            try {
                roomList = LiveApi.getRecommend(page)!! as MutableList<LiveRoom>
                val newAdapter = LiveCardAdapter(this, roomList!!)
                adapter = newAdapter
                setOnLoadMoreListener { pageNum -> continueLoading(pageNum) }
                setRefreshing(false)
                setAdapter(newAdapter)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            try {
                var list: List<LiveRoom>?
                list = LiveApi.getRecommend(page)
                Log.e("debug", "下一页")
                runOnUiThread(Runnable {
                    if (list != null) {
                        val adapter = this.adapter
                        val roomList = this.roomList
                        if (adapter != null && roomList != null) {
                            roomList.addAll(list!!)
                            adapter.notifyItemRangeInserted(roomList.size - list!!.size, list!!.size)
                        }
                    }
                })
                if (list != null && list!!.size < 1) {
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
