package com.RobinNotBad.BiliClient.activity.reply

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Pair
import android.view.Display
import android.view.View
import android.view.WindowManager

import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment
import com.RobinNotBad.BiliClient.adapter.ReplyAdapter
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList
import java.util.Objects

//视频下评论页面，评论详情见ReplyInfoActivity
//部分通用代码在VideoReplyAdapter内
//2023-07-22

class ReplyFragment : RefreshListFragment() {

    private var dontload = false

    @JvmField
    protected var aid: Long = 0

    @JvmField
    protected var mid: Long = 0

    @JvmField
    protected var sort = 3

    @JvmField
    protected var type = 0

    @JvmField
    protected var count = 0

    @JvmField
    protected var replyList: ArrayList<Reply>? = null

    @JvmField
    protected var replyAdapter: ReplyAdapter? = null

    @JvmField
    var replyType = ReplyApi.REPLY_TYPE_VIDEO

    private var seek: Long = 0
    private var pagination = ""
    private var isManager = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getArguments() != null) {
            aid = getArguments()!!.getLong("aid", 0)
            count = getArguments()!!.getInt("count", 0)
            type = getArguments()!!.getInt("type", 0)
            replyType = type
            dontload = getArguments()!!.getBoolean("dontload", false)
            seek = getArguments()!!.getLong("seek", -1)
            mid = getArguments()!!.getLong("mid", -1)
        }
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        setForceSingleColumn()
        super.onViewCreated(view, savedInstanceState)

        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics) else display.getMetrics(metrics)
            val paddings = metrics.widthPixels / 6
            recyclerView!!.setPadding(paddings, 0, paddings, 0)
        }

        setOnRefreshListener { refresh(aid) }
        setOnLoadMoreListener { page -> continueLoading(page) }

        Log.e("debug-av号", aid.toString())

        replyList = ArrayList()

        if (!dontload) refresh(aid)
    }

    fun setManager(source: Any?) {
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) return

        try {
            if (source != null) {
                if (source is List<*>) {
                    val staffs = source as List<UserInfo>
                    for (userInfo in staffs) {
                        if (userInfo.mid == SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)) {
                            isManager = true
                            break
                        }
                    }
                } else if (source is UserInfo) {
                    isManager = (source as UserInfo).mid == SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
                }
            }
        } catch (e: Exception) {
            MsgUtil.err(e)
        }
    }

    private fun createReplyAdapter(): ReplyAdapter {
        return ReplyAdapter(requireContext(), replyList, aid, 0L, type, sort, mid)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val list = ArrayList<Reply>()
                val pageState = ReplyApi.getRepliesLazy(aid, 0L, pagination, type, sort, list)
                val result = pageState.first
                pagination = pageState.second
                setRefreshing(false)
                if (result != -1) {
                    Log.e("debug", "下一页")
                    runOnUiThread {
                        replyList!!.addAll(list)
                        if (replyAdapter != null)
                            replyAdapter!!.notifyItemRangeInserted(replyList!!.size - list.size + 1, list.size)
                    }
                    if (result == 1) {
                        Log.e("debug", "到底了")
                        bottom = true
                    }
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    fun notifyReplyInserted(replyEvent: ReplyEvent) {
        if (replyEvent.oid != aid) return
        val reply = replyEvent.message
        if (reply!!.root == 0L) {
            val layoutManager = Objects.requireNonNull(recyclerView!!.layoutManager) as LinearLayoutManager
            var pos = layoutManager.findFirstCompletelyVisibleItemPosition()
            pos = Math.max(pos, 0)
            replyList!!.add(pos, reply)
            val finalPos = pos
            runOnUiThread {
                replyAdapter!!.notifyItemInserted(finalPos)
                replyAdapter!!.notifyItemRangeChanged(finalPos, replyList!!.size - finalPos + 1)
                layoutManager.scrollToPositionWithOffset(finalPos + 1, 0)
            }
        } else if (replyEvent.pos >= 0) {
            replyList!![replyEvent.pos].childMsgList!!.add(reply)
            replyList!![replyEvent.pos].childCount++
            runOnUiThread { replyAdapter!!.notifyItemChanged(replyEvent.pos + 1) }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refresh(aid: Long) {
        pagination = ""
        this.aid = aid
        setRefreshing(true)
        CenterThreadPool.run(Runnable {
            try {
                val list = ArrayList<Reply>()
                val pageState = ReplyApi.getRepliesLazy(aid, seek, pagination, type, sort, list)
                val result = pageState.first
                pagination = pageState.second
                setRefreshing(false)
                if (result != -1 && isAdded()) {
                    runOnUiThread {
                        if (!isAdded()) return@runOnUiThread
                        if (replyList != null) replyList!!.clear() else replyList = ArrayList()
                        replyList!!.addAll(list)
                        if (replyAdapter == null) {
                            replyAdapter = createReplyAdapter()
                            replyAdapter!!.count = count.toLong()
                            replyAdapter!!.isManager = isManager
                            setOnSortSwitch()
                            setAdapter(replyAdapter!!)
                        } else {
                            replyAdapter!!.notifyDataSetChanged()
                        }
                    }
                    //replyAdapter.notifyItemRangeInserted(0,replyList.size());
                    if (result == 1) {
                        Log.e("debug", "到底了")
                        bottom = true
                    } else bottom = false
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    private fun setOnSortSwitch() {
        replyAdapter!!.setOnSortSwitchListener { position ->
            sort = if (sort == 2) 3 else 2
            replyAdapter!!.sort = sort
            refresh(aid)
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(aid: Long, type: Int): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            fragment.setArguments(args)
            return fragment
        }

        @JvmStatic
        fun newInstance(aid: Long, type: Int, dontload: Boolean): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            args.putBoolean("dontload", dontload)
            fragment.setArguments(args)
            return fragment
        }

        @JvmStatic
        fun newInstance(aid: Long, type: Int, seek_rpid: Long): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            args.putLong("seek", seek_rpid)
            fragment.setArguments(args)
            return fragment
        }

        @JvmStatic
        fun newInstance(aid: Long, type: Int, dontload: Boolean, seek_rpid: Long): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("type", type)
            args.putBoolean("dontload", dontload)
            args.putLong("seek", seek_rpid)
            fragment.setArguments(args)
            return fragment
        }

        @JvmStatic
        fun newInstance(aid: Long, type: Int, count: Int, seek_rpid: Long, up_mid: Long): ReplyFragment {
            val fragment = ReplyFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            args.putInt("count", count)
            args.putInt("type", type)
            args.putLong("seek", seek_rpid)
            args.putLong("mid", up_mid)
            fragment.setArguments(args)
            return fragment
        }
    }
}
