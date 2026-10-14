package com.RobinNotBad.BiliClient.activity.base

import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener
import com.RobinNotBad.BiliClient.util.MsgUtil


/*
尝试造轮子以减少代码量
2024-05-01
 */

open class RefreshListActivity : BaseActivity() {
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

    //帮你findView和设置滚动监测器，自动显示转圈动画
    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_refresh)
        emptyView = findViewById(R.id.emptyTip)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout!!.isEnabled = false
        swipeRefreshLayout!!.isRefreshing = true
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView!!.layoutManager = getLayoutManager()
        recyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(@NonNull recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (listener != null && !recyclerView.canScrollVertically(1) && !swipeRefreshLayout!!.isRefreshing && newState == RecyclerView.SCROLL_STATE_DRAGGING && !bottom) {
                    goOnLoad()
                }
            }

            override fun onScrolled(@NonNull recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (listener != null) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager?
                    assert(manager != null)
                    val lastItemPosition = manager!!.findLastCompletelyVisibleItemPosition()  //获取最后一个完全显示的itemPosition
                    val itemCount = manager.itemCount
                    if (lastItemPosition >= itemCount - 3 && dy > 0 && !swipeRefreshLayout!!.isRefreshing && !bottom) { // 滑动到倒数第三个就可以刷新了
                        goOnLoad()
                    }
                }
            }
        })
    }

    open fun setAdapter(adapter: RecyclerView.Adapter<*>) {
        runOnUiThread { recyclerView!!.adapter = adapter }
    }

    fun setOnRefreshListener(listener: SwipeRefreshLayout.OnRefreshListener) {
        swipeRefreshLayout!!.setOnRefreshListener(listener)
        swipeRefreshLayout!!.isEnabled = true
    }

    open fun showEmptyView() {
        if (emptyView != null) {
            runOnUiThread {
                recyclerView!!.visibility = View.GONE
                emptyView!!.visibility = View.VISIBLE
            }
        }
    }

    open fun hideEmptyView() {
        if (emptyView != null) {
            runOnUiThread {
                recyclerView!!.visibility = View.VISIBLE
                emptyView!!.visibility = View.GONE
            }
        }
    }

    open fun setRefreshing(bool: Boolean) {
        runOnUiThread { swipeRefreshLayout!!.isRefreshing = bool }
    }

    fun setOnLoadMoreListener(loadMore: OnLoadMoreListener?) {
        listener = loadMore
    }

    //自动
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

    open fun loadFail() {
        page--
        MsgUtil.showMsgLong("加载失败")
        setRefreshing(false)
    }

    open fun loadFail(e: Exception) {
        page--
        report(e)
        setRefreshing(false)
    }

}
