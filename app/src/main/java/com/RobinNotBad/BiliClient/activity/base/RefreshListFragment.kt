package com.RobinNotBad.BiliClient.activity.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener

/*
跟RefreshListActivity基本相同
2024-05-02
 */

open class RefreshListFragment : BaseFragment() {
    @JvmField
    var swipeRefreshLayout: SwipeRefreshLayout? = null

    @JvmField
    var recyclerView: RecyclerView? = null

    @JvmField
    var emptyView: TextView? = null

    @JvmField
    var listener: OnLoadMoreListener? = null

    @JvmField
    var bottom = false

    @JvmField
    var page = 1

    @JvmField
    var lastLoadTimestamp: Long = 0

    @JvmField
    var force_single_column = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_simple_refresh, container, false)
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.emptyTip)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout!!.isEnabled = false
        swipeRefreshLayout!!.isRefreshing = true
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView!!.layoutManager = getLayoutManager()
        recyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(@NonNull recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (listener != null && !recyclerView.canScrollVertically(1) && isRefreshing && newState == RecyclerView.SCROLL_STATE_DRAGGING && !bottom) {
                    goOnLoad()
                }
            }

            override fun onScrolled(@NonNull recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (listener != null) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager?
                    assert(manager != null)
                    val lastItemPosition = manager!!.findLastVisibleItemPosition()  //获取最后一个显示的itemPosition
                    val itemCount = manager.itemCount
                    if (lastItemPosition >= itemCount - 3 && dy > 0 && !isRefreshing && !bottom) { // 滑动到倒数第三个就可以刷新了
                        goOnLoad()
                    }
                }
            }
        })
        ImageAutoLoadScrollListener.install(recyclerView!!)
    }

    open fun setAdapter(adapter: RecyclerView.Adapter<*>) {
        runOnUiThread { recyclerView!!.adapter = adapter }
    }

    fun setOnRefreshListener(listener: SwipeRefreshLayout.OnRefreshListener) {
        swipeRefreshLayout!!.setOnRefreshListener(listener)
        swipeRefreshLayout!!.isEnabled = true
    }

    open fun setRefreshing(bool: Boolean) {
        runOnUiThread { swipeRefreshLayout!!.isRefreshing = bool }
    }

    fun setOnLoadMoreListener(loadMore: OnLoadMoreListener?) {
        listener = loadMore
    }

    private fun goOnLoad() {
        val timeCurrent = System.currentTimeMillis()
        if (timeCurrent - lastLoadTimestamp > 100) {
            swipeRefreshLayout!!.isRefreshing = true
            page++
            listener!!.onLoad(page)
            lastLoadTimestamp = timeCurrent
        }
    }

    open fun setBottom(bool: Boolean) {
        bottom = bool
    }

    open fun showEmptyView() {
        if (emptyView != null) {
            runOnUiThread {
                recyclerView!!.visibility = View.GONE
                emptyView!!.visibility = View.VISIBLE
            }
        }
    }

    val isRefreshing: Boolean
        get() = swipeRefreshLayout?.isRefreshing ?: false

    fun report(e: Throwable) {
        MsgUtil.err(e)
    }

    open fun loadFail() {
        page--
        MsgUtil.showMsgLong("加载失败")
        setRefreshing(false)
    }

    open fun loadFail(e: Throwable) {
        page--
        report(e)
        setRefreshing(false)
    }

    /** 视频卡片列表页标记（#2）：打开后可用实验室「双列模式」信息流布局 */
    @JvmField
    var video_card_layout: Boolean = false

    open fun getLayoutManager(): RecyclerView.LayoutManager {
        return if (video_card_layout)
            ListLayoutUtil.createVideoCard(requireContext(), force_single_column)
        else
            ListLayoutUtil.create(requireContext(), force_single_column)
    }

    /** 标记为视频卡片列表页（#2），并让 adapter 使用信息流卡片 */
    open fun setVideoCardLayout() {
        video_card_layout = true
    }

    open fun setForceSingleColumn() {
        force_single_column = true
    }
}
