package com.RobinNotBad.BiliClient.activity.user

import android.os.Bundle
import android.view.View

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.CoinLogAdapter
import com.RobinNotBad.BiliClient.api.CoinLogApi
import com.RobinNotBad.BiliClient.model.CoinLog
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager


class CoinLogActivity : BaseActivity() {

    private var recyclerView: RecyclerView? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var logList: List<CoinLog>? = null
    private var adapter: CoinLogAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_refresh)

        setPageName("硬币变化记录")

        recyclerView = findViewById(R.id.recyclerView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        swipeRefreshLayout!!.isEnabled = false
        swipeRefreshLayout!!.isRefreshing = true

        recyclerView!!.layoutManager = CustomLinearManager(this)

        logList = ArrayList()

        CenterThreadPool.run(Runnable {
            try {
                logList = CoinLogApi.getCoinLog()

                val list = logList!!
                runOnUiThread {
                    if (list.isEmpty()) {
                        MsgUtil.showMsg("暂无硬币变化记录")
                        findViewById<View>(R.id.emptyTip).visibility = View.VISIBLE
                    } else {
                        val adapter = CoinLogAdapter(this, list)
                        this.adapter = adapter
                        recyclerView!!.adapter = adapter
                    }
                    swipeRefreshLayout!!.isRefreshing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    MsgUtil.showMsg("加载失败：" + e.message)
                    swipeRefreshLayout!!.isRefreshing = false
                    findViewById<View>(R.id.emptyTip).visibility = View.VISIBLE
                }
                e.printStackTrace()
            }
        })
    }
}
