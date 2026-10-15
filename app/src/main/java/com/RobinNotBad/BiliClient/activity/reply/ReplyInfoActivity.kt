package com.RobinNotBad.BiliClient.activity.reply

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.ReplyAdapter
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.model.ContentType
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext

import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

import java.util.ArrayList
import java.util.Objects
import java.util.concurrent.Future

//评论详细信息
//2023-07-22

class ReplyInfoActivity : BaseActivity() {

    private var oid: Long = 0
    private var rpid: Long = 0
    private var up_mid: Long = 0
    private var sort = 0
    private var isManager = false
    private var type: ContentType? = null
    private var recyclerView: RecyclerView? = null
    private var refreshLayout: SwipeRefreshLayout? = null
    private var replyList: ArrayList<Reply>? = null
    private var replyAdapter: ReplyAdapter? = null
    private var bottom = false
    private var page = 1
    private var refreshing = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_refresh)

        val intent = getIntent()
        rpid = intent.getLongExtra("rpid", 0)
        oid = intent.getLongExtra("oid", 0)
        try {
            type = ContentType.getContentType(intent.getIntExtra("type", 1))
        } catch (e: ContentType.TerminalIllegalTypeCodeException) {
            throw RuntimeException(e)
        }
        up_mid = intent.getLongExtra("up_mid", -1)
        isManager = intent.getBooleanExtra("is_manager", false)

        refreshLayout = findViewById(R.id.swipeRefreshLayout)
        recyclerView = findViewById(R.id.recyclerView)
        refreshLayout!!.setOnRefreshListener { refresh() }

        setPageName("评论详情")

        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val paddings = metrics.widthPixels / 6
            recyclerView!!.setPadding(paddings, 0, paddings, 0)
        }


        refreshLayout!!.isRefreshing = true
        TerminalContext.getInstance().getReply(type!!, oid, rpid).observe(this) { rootReplyResult ->
            replyList = ArrayList()
            rootReplyResult.onSuccess { rootReply ->
                val listLocal = replyList!!
                val future: Future<Int> = CenterThreadPool.supplyAsyncWithFuture { ReplyApi.getReplies(oid, rpid, page, type!!, sort, listLocal) }
                CenterThreadPool.observe(future, { result ->
                    if (result != -1) {
                        replyList!!.add(0, rootReply)
                        replyAdapter = ReplyAdapter(this, replyList, oid, rpid, type!!.getTypeCode(), sort, up_mid)
                        replyAdapter!!.isManager = isManager
                        replyAdapter!!.isDetail = true
                        setOnSortSwitch()
                        recyclerView!!.layoutManager = CustomLinearManager(this)
                        recyclerView!!.adapter = replyAdapter
                        recyclerView!!.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                super.onScrollStateChanged(recyclerView, newState)
                            }

                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                val manager = recyclerView.layoutManager as LinearLayoutManager
                                assert(manager != null)
                                val lastItemPosition = manager.findLastVisibleItemPosition()  //获取最后一个显示的itemPosition
                                val itemCount = manager.itemCount
                                if (lastItemPosition >= itemCount - 3 && dy > 0 && !refreshing && !bottom) {// 滑动到倒数第三个就可以刷新了
                                    refreshing = true
                                    CenterThreadPool.run(Runnable { continueLoading() }) //加载第二页
                                }
                            }
                        })
                        refreshLayout!!.isRefreshing = false
                        if (result == 1) {
                            Log.e("debug", "到底了")
                            bottom = true
                        }
                    }
                }, { error -> onPullDataFailed(Exception(error)) })
            }.onFailure { error -> onPullDataFailed(Exception(error)) }
        }
    }

    private fun onPullDataFailed(e: Exception) {
        MsgUtil.err(e)
        refreshLayout!!.isRefreshing = false
    }

    private fun continueLoading() {
        runOnUiThread { refreshLayout!!.isRefreshing = true }
        page++
        try {
            val list = ArrayList<Reply>()
            val result = ReplyApi.getReplies(oid, rpid, page, type!!, sort, list)
            if (result != -1) {
                Log.e("debug", "下一页")
                runOnUiThread {
                    replyList!!.addAll(list)
                    replyAdapter!!.notifyItemRangeInserted(replyList!!.size - list.size + 2, list.size)  //顶上有两个固定项
                    refreshLayout!!.isRefreshing = false
                }
                if (result == 1) {
                    //runOnUiThread(()-> MsgUtil.showMsg("到底啦QwQ",this));
                    Log.e("debug", "到底了")
                    bottom = true
                }
            }
            refreshing = false
        } catch (e: Exception) {
            runOnUiThread {
                MsgUtil.err(e)
                refreshLayout!!.isRefreshing = false
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        page = 1
        refreshLayout!!.isRefreshing = true

        TerminalContext.getInstance().getReply(type!!, oid, rpid).observe(this) { rootReplyResult ->
            rootReplyResult.onSuccess { rootReply ->
                val list = ArrayList<Reply>()
                val future: Future<Int> = CenterThreadPool.supplyAsyncWithFuture { ReplyApi.getReplies(oid, rpid, page, type!!, sort, list) }
                CenterThreadPool.observe(future, { result ->
                    if (result != -1) {
                        runOnUiThread {
                            replyList!!.clear()
                            replyList!!.add(0, rootReply)
                            replyList!!.addAll(list)
                            if (replyAdapter == null) {
                                replyAdapter = ReplyAdapter(this, replyList, oid, rpid, type!!.getTypeCode(), sort, up_mid)
                                replyAdapter!!.isDetail = true
                                setOnSortSwitch()
                                recyclerView!!.adapter = replyAdapter
                            } else {
                                replyAdapter!!.notifyDataSetChanged()
                            }
                            refreshLayout!!.isRefreshing = false
                        }
                        if (result == 1) {
                            Log.e("debug", "到底了")
                            bottom = true
                        } else bottom = false
                    }
                }, { error ->
                    onPullDataFailed(Exception(error))
                })
            }.onFailure { error ->
                onPullDataFailed(Exception(error))
            }
        }
    }

    private fun setOnSortSwitch() {
        replyAdapter!!.setOnSortSwitchListener { position ->
            sort = if (sort == 0) 1 else 0
            refresh()
        }
    }

    override fun eventBusEnabled(): Boolean {
        return true
    }

    @Subscribe(threadMode = ThreadMode.ASYNC, sticky = true, priority = 1)
    fun onEvent(event: ReplyEvent) {
        if (event.oid != oid) return
        val layoutManager = Objects.requireNonNull(recyclerView!!.layoutManager) as LinearLayoutManager
        var pos = layoutManager.findFirstCompletelyVisibleItemPosition()
        pos--
        if (pos <= 0) {
            pos = layoutManager.findFirstVisibleItemPosition()
            pos--
        }
        pos = if (pos <= 0) 1 else pos
        replyList!!.add(pos, event.message!!)
        val finalPos = pos
        runOnUiThread {
            if (replyAdapter != null) {
                replyAdapter!!.notifyItemInserted(finalPos)
                replyAdapter!!.notifyItemRangeChanged(finalPos, replyList!!.size - finalPos + 1)
                layoutManager.scrollToPositionWithOffset(finalPos + 1, 0)
            }
        }
    }
}
