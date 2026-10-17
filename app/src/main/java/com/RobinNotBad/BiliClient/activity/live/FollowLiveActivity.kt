package com.RobinNotBad.BiliClient.activity.live

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.LiveCardAdapter
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.api.LiveApi
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import java.util.ArrayList

open class FollowLiveActivity : RefreshListActivity() {
    private var roomList: MutableList<LiveRoom>? = null
    private var adapter: LiveCardAdapter? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("我关注的直播")

        // #4：双列模式适用于所有视频列表（关注直播）
        video_card_layout = true

        recyclerView!!.setHasFixedSize(true)

        roomList = ArrayList()

        CenterThreadPool.run(Runnable {
            try {
                roomList = LiveApi.getFollowed(page)!! as MutableList<LiveRoom>
                val newAdapter = LiveCardAdapter(this, roomList!!)
                newAdapter.feedLayout = ListLayoutUtil.isDualColumnVideo() // #4
                adapter = newAdapter
                setOnLoadMoreListener { pageNum -> continueLoading(pageNum) }
                setRefreshing(false)
                setAdapter(newAdapter)
                if (roomList!!.size < 1) showEmptyView()
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            try {
                var list: List<LiveRoom>?
                list = LiveApi.getFollowed(page)
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
