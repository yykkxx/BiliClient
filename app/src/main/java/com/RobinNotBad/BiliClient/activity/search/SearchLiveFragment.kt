package com.RobinNotBad.BiliClient.activity.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.adapter.LiveCardAdapter
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener
import com.RobinNotBad.BiliClient.api.LiveApi
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList

class SearchLiveFragment : SearchFragment() {

    private var roomList: ArrayList<LiveRoom> = ArrayList()
    private var liveCardAdapter: LiveCardAdapter? = null

    companion object {
        @JvmStatic
        fun newInstance(): SearchLiveFragment {
            return SearchLiveFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    // #4：搜索"直播"标签也走双列信息流
    override fun getLayoutManager(): RecyclerView.LayoutManager {
        return ListLayoutUtil.createVideoCard(requireContext(), false)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        roomList = ArrayList()
        liveCardAdapter = LiveCardAdapter(requireContext(), roomList)
        liveCardAdapter!!.feedLayout = ListLayoutUtil.isDualColumnVideo() // #4
        setAdapter(liveCardAdapter!!)

        setOnRefreshListener { refreshInternal() }
        setOnLoadMoreListener(OnLoadMoreListener { page: Int -> continueLoading(page) })
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            Log.e("debug", "加载下一页")
            try {
                val result = SearchApi.searchType(keyword, page, "live")
                if (result != null) {
                    if (page == 1) showEmptyView(false)
                    var jsonArray: JSONArray? = null
                    if (result is JSONObject)
                        jsonArray = result.optJSONArray("live_room")
                    else if (result is JSONArray) jsonArray = result

                    val list: MutableList<LiveRoom> = ArrayList()
                    if (jsonArray != null) list.addAll(LiveApi.analyzeLiveRooms(jsonArray))
                    if (list.size == 0) setBottom(true)
                    else CenterThreadPool.runOnUiThread(Runnable {
                        val lastSize = roomList.size
                        roomList.addAll(list)
                        liveCardAdapter!!.notifyItemRangeInserted(lastSize + 1, roomList.size - lastSize)
                    })
                } else setBottom(true)
            } catch (e: Exception) {
                report(e)
            }
            setRefreshing(false)
            if (bottom && roomList.isEmpty()) {
                showEmptyView(true)
            }
        })
    }

    override fun refreshInternal() {
        CenterThreadPool.runOnUiThread(Runnable {
            page = 1
            if (this.liveCardAdapter == null)
                this.liveCardAdapter = LiveCardAdapter(this.requireContext(), this.roomList)
            val size_old = this.roomList.size
            this.roomList.clear()
            if (size_old != 0) this.liveCardAdapter!!.notifyItemRangeRemoved(0, size_old)
            CenterThreadPool.run(Runnable { continueLoading(page) })
        })
    }

}
