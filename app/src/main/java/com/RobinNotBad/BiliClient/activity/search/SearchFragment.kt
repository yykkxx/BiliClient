package com.RobinNotBad.BiliClient.activity.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener

open class SearchFragment : Fragment() {
    @JvmField
    var swipeRefreshLayout: SwipeRefreshLayout? = null
    @JvmField
    var recyclerView: RecyclerView? = null
    @JvmField
    var emptyView: TextView? = null
    @JvmField
    var listener: OnLoadMoreListener? = null
    @JvmField
    var refreshListener: SwipeRefreshLayout.OnRefreshListener? = null
    @JvmField
    var keyword: String? = null
    @JvmField
    var bottom = false
    @JvmField
    var page = 1
    @JvmField
    var lastLoadTimestamp: Long = 0
    @JvmField
    var refreshable = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_simple_refresh, container, false)
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.emptyTip)

        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout!!.isEnabled = false

        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView!!.layoutManager = getLayoutManager()
        recyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState != RecyclerView.SCROLL_STATE_DRAGGING) return

                if (!recyclerView.canScrollVertically(-1)) {
                    if (requireActivity() is SearchActivity) {
                        val activity = requireActivity() as SearchActivity  //不能向上滚动了就显示搜索栏
                        activity.onScrolled(-114)
                    }
                } else if (listener != null && !recyclerView.canScrollVertically(1) && !swipeRefreshLayout!!.isRefreshing && !bottom) {
                    goOnLoad()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (listener != null) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager?
                    assert(manager != null)
                    val lastItemPosition = manager!!.findLastVisibleItemPosition()  //获取最后一个显示的itemPosition
                    val itemCount = manager.itemCount
                    if (lastItemPosition >= itemCount - 3 && dy > 0 && !swipeRefreshLayout!!.isRefreshing && !bottom) {// 滑动到倒数第三个就可以刷新了
                        goOnLoad()
                    }

                    if (requireActivity() is SearchActivity) {
                        val activity = requireActivity() as SearchActivity
                        activity.onScrolled(dy)
                    }
                }
            }
        })
        ImageAutoLoadScrollListener.install(recyclerView!!)
    }

    fun setAdapter(adapter: RecyclerView.Adapter<*>) {
        runOnUiThread(Runnable { recyclerView!!.adapter = adapter })
    }

    fun setOnRefreshListener(listener: SwipeRefreshLayout.OnRefreshListener) {
        this.refreshListener = listener
    }

    fun setRefreshing(bool: Boolean) {
        runOnUiThread(Runnable { swipeRefreshLayout!!.isRefreshing = bool })
    }

    fun setOnLoadMoreListener(loadMore: OnLoadMoreListener) {
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

    fun setBottom(bool: Boolean) {
        bottom = bool
        if (page == 1) showEmptyView(bool)
        else if (bool && isAdded) {
            MsgUtil.showMsg("已经到底啦OwO")
        }
    }

    fun runOnUiThread(runnable: Runnable) {
        if (isAdded) requireActivity().runOnUiThread(runnable)
    }

    fun showEmptyView(empty: Boolean) {
        if (emptyView != null) {
            runOnUiThread(Runnable { emptyView!!.visibility = if (empty) View.VISIBLE else View.GONE })
        }
    }

    fun isRefreshing(): Boolean {
        if (swipeRefreshLayout != null) return swipeRefreshLayout!!.isRefreshing
        return false
    }

    fun report(e: Throwable) {
        MsgUtil.err(e)
    }

    fun loadFail() {
        page--
        MsgUtil.showMsgLong("加载失败")
        setRefreshing(false)
    }

    fun loadFail(e: Throwable) {
        page--
        report(e)
        setRefreshing(false)
    }

    fun getLayoutManager(): RecyclerView.LayoutManager {
        return ListLayoutUtil.create(requireContext(), false)
    }

    fun update(keyword: String) {
        this.page = 1
        this.keyword = keyword
        this.refreshable = true
        setBottom(false)
    }

    fun refresh() {
        if (!refreshable) return
        refreshable = false
        setRefreshing(true)
        refreshListener!!.onRefresh()
    }

    protected open fun refreshInternal() {
    }
}
