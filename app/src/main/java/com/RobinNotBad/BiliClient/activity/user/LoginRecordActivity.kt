package com.RobinNotBad.BiliClient.activity.user

import android.os.Bundle
import android.view.View

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.LoginRecordAdapter
import com.RobinNotBad.BiliClient.api.LoginRecordApi
import com.RobinNotBad.BiliClient.model.LoginRecord
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil


class LoginRecordActivity : BaseActivity() {

    private var recyclerView: RecyclerView? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var recordList: List<LoginRecord>? = null
    private var adapter: LoginRecordAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_refresh)

        setPageName("登录记录")

        recyclerView = findViewById(R.id.recyclerView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        swipeRefreshLayout!!.isEnabled = false
        swipeRefreshLayout!!.isRefreshing = true

        recyclerView!!.layoutManager = LinearLayoutManager(this)

        recordList = ArrayList()

        CenterThreadPool.run(Runnable {
            try {
                val mid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
                val buvid = ""

                recordList = LoginRecordApi.getLoginRecord(mid, buvid)

                val list = recordList!!
                runOnUiThread {
                    if (list.isEmpty()) {
                        MsgUtil.showMsg("暂无登录记录")
                        findViewById<View>(R.id.emptyTip).visibility = View.VISIBLE
                    } else {
                        val adapter = LoginRecordAdapter(this, list)
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
