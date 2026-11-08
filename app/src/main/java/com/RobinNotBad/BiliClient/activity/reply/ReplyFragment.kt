package com.RobinNotBad.BiliClient.activity.reply

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment
import com.RobinNotBad.BiliClient.adapter.ReplyAdapter
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.MsgUtil

import java.io.IOException
import java.util.ArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var isManager = false
    private val loadState = ReplyLoadState()
    private val sortSwitch = ReplySortSwitch()

    /** 排序切换前那一份列表的快照；只在切换在途时非空，成功后丢弃。 */
    private var sortSwitchBackup: ArrayList<Reply>? = null

    /** 切换前的 seek（来自「从评论链接定位」的一次性参数）；失败回滚时要原样还回去。 */
    private var sortSwitchSeekBackup: Long = ReplySortMode.SEEK_NONE
    private var viewScope: CoroutineScope? = null
    private var requestJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            aid = args.getLong("aid", 0)
            count = args.getInt("count", 0)
            type = args.getInt("type", 0)
            replyType = type
            dontload = args.getBoolean("dontload", false)
            seek = args.getLong("seek", -1)
            mid = args.getLong("mid", -1)
        }
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        setForceSingleColumn()
        super.onViewCreated(view, savedInstanceState)

        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        loadState.invalidate()
        bottom = false
        page = 1
        lastLoadTimestamp = 0
        setOnRefreshListener { refresh(aid) }
        setOnLoadMoreListener { page -> continueLoading(page) }

        Log.e("debug-av号", aid.toString())
        replyList = ArrayList()
        bindReplyAdapter()
        if (!dontload) refresh(aid) else setRefreshing(false)
    }

    fun setManager(source: Any?) {
        // 门槛型：一个账号都没有时 setManager 没有意义
        if (!AccountManager.isLoggedIn()) return

        try {
            if (source != null) {
                if (source is List<*>) {
                    val staffs = source as List<UserInfo>
                    for (userInfo in staffs) {
                        // 我是谁：多通道下任一通道命中即算「我」
                        if (AccountManager.isSelf(userInfo.mid)) {
                            isManager = true
                            break
                        }
                    }
                } else if (source is UserInfo) {
                    isManager = AccountManager.isSelf((source as UserInfo).mid)
                }
            }
        } catch (e: Exception) {
            MsgUtil.err(e)
        }
    }

    private fun createReplyAdapter(): ReplyAdapter {
        return ReplyAdapter(requireContext(), replyList, aid, 0L, type, sort, mid)
    }

    private fun bindReplyAdapter() {
        val adapter = createReplyAdapter()
        adapter.count = count.toLong()
        adapter.isManager = isManager
        replyAdapter = adapter
        setOnSortSwitch()
        recyclerView?.adapter = adapter
    }

    /** EventBus 可从后台调用；作用域和 View 身份同时绑定，旧 View 的排队动作不能碰新 View。 */
    private fun withReplyView(action: (View) -> Unit) {
        val scope = viewScope ?: return
        val expectedView = view ?: return
        scope.launch {
            if (isAdded && view === expectedView) action(expectedView)
        }
    }

    override fun setRefreshing(bool: Boolean) {
        withReplyView { swipeRefreshLayout?.isRefreshing = bool }
    }

    private fun continueLoading(page: Int) {
        withReplyView { currentView ->
            // 父类先加 page；实际页数只能由成功提交的请求推进。
            this.page = loadState.page
            if (!loadState.isLoading && loadState.needsRefresh) {
                startRefresh(aid, currentView)
            } else {
                val key = ReplyLoadState.Key(aid, type, sort, seek)
                val request = if (page > loadState.page) loadState.next(key) else null
                if (request != null) {
                    this.page = request.page
                    loadReplies(request, currentView)
                } else {
                    swipeRefreshLayout?.isRefreshing = loadState.isLoading
                }
            }
        }
    }

    fun notifyReplyInserted(replyEvent: ReplyEvent) {
        withReplyView {
            if (replyEvent.oid == aid) insertReply(replyEvent)
        }
    }

    private fun insertReply(replyEvent: ReplyEvent) {
        val reply = replyEvent.message ?: return
        val list = replyList ?: return
        val adapter = replyAdapter ?: return
        if (reply.root == 0L) {
            val manager = recyclerView?.layoutManager as? LinearLayoutManager ?: return
            val visiblePosition = manager.findFirstCompletelyVisibleItemPosition()
            val index = (visiblePosition - 1).coerceIn(0, list.size)
            list.add(index, reply)
            adapter.notifyItemInserted(index + 1)
            adapter.notifyItemRangeChanged(index + 1, list.size - index)
            manager.scrollToPositionWithOffset(index + 1, 0)
        } else {
            // 排序改变后 pos 已过期；按根 rpid 定位，不能给另一条评论加子回复。
            val index = list.indexOfFirst { it.rpid == reply.root }
            if (index < 0) return
            val parent = list[index]
            val children = parent.childMsgList ?: ArrayList<Reply>().also { parent.childMsgList = it }
            children.add(reply)
            parent.childCount++
            adapter.notifyItemChanged(index + 1)
        }
    }

    fun refresh(aid: Long) {
        withReplyView { currentView -> startRefresh(aid, currentView) }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun startRefresh(oid: Long, currentView: View) {
        val previousKey = loadState.key
        if (aid != oid) seek = 0L
        aid = oid
        val key = ReplyLoadState.Key(oid, type, sort, seek)
        if (previousKey != null && (previousKey.oid != key.oid || previousKey.type != key.type)) {
            // 换评论/换类型后旧列表本来就该丢，切换备份也不能跨目标复用。
            sortSwitch.cancel()
            sortSwitchBackup = null
            sortSwitchSeekBackup = ReplySortMode.SEEK_NONE
        }
        val request = loadState.refresh(key)
        requestJob?.cancel()
        bottom = false
        page = 1
        lastLoadTimestamp = 0
        // 相同目标刷新失败时保留旧列表；换排序/oid 时不能展示另一目标的数据或使用旧写评论 oid。
        if (previousKey != key) {
            replyList?.clear()
            bindReplyAdapter()
        } else {
            replyAdapter?.sort = sort
            replyAdapter?.notifyItemChanged(0)
        }
        swipeRefreshLayout?.isRefreshing = true
        loadReplies(request, currentView)
    }

    private fun loadReplies(request: ReplyLoadState.Request, currentView: View) {
        requestJob = viewScope?.launch {
            try {
                val list = ArrayList<Reply>()
                val key = request.key
                val response = withContext(Dispatchers.IO) {
                    ReplyApi.getRepliesLazy(key.oid, if (request.refresh) key.seek else 0L,
                        request.pagination, key.type, key.sort, list)
                }
                if (view === currentView && isAdded && loadState.isCurrent(request)) {
                    if (response.first == -1) {
                        failRequest(request, IOException("评论加载失败，请重试"))
                    } else {
                        applyReplies(request, response.first, response.second, list)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (view === currentView && isAdded) failRequest(request, error)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyReplies(request: ReplyLoadState.Request, result: Int, next: String, replies: List<Reply>) {
        if (!loadState.finish(request, result, next)) return
        val list = replyList ?: return
        val adapter = replyAdapter ?: return
        if (request.refresh) {
            list.clear()
            list.addAll(replies)
            adapter.notifyDataSetChanged()
            if (sortSwitch.active && sortSwitch.target == request.key.sort) {
                // 新排序的数据已经真正展示出来，切换事务成功：旧列表备份不再需要。
                sortSwitch.commit()
                sortSwitchBackup = null
            }
        } else {
            val start = list.size + 1 // 第 0 项是 header
            list.addAll(replies)
            if (replies.isNotEmpty()) adapter.notifyItemRangeInserted(start, replies.size)
        }
        bottom = loadState.bottom
        page = loadState.page
        swipeRefreshLayout?.isRefreshing = false
    }

    private fun failRequest(request: ReplyLoadState.Request, error: Throwable) {
        if (!loadState.finish(request, -1, "")) return
        val restoreSort = sortSwitch.failureSort(request.key.sort)
        if (restoreSort != null) {
            // 排序切换失败：回到切换前的排序与列表，绝不停在「空列表 + 新排序标签」的半截状态。
            restoreSortSwitch(request.key, restoreSort)
        }
        page = loadState.page
        bottom = loadState.bottom
        swipeRefreshLayout?.isRefreshing = false
        MsgUtil.err(error)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun restoreSortSwitch(failedKey: ReplyLoadState.Key, restoreSort: Int) {
        val backup = sortSwitchBackup ?: return
        sort = restoreSort
        seek = sortSwitchSeekBackup
        replyList?.let { list ->
            list.clear()
            list.addAll(backup)
        }
        bindReplyAdapter()
        recyclerView?.scrollToPosition(0)
        loadState.restore(failedKey.copy(sort = restoreSort, seek = seek))
        sortSwitchBackup = null
        sortSwitchSeekBackup = ReplySortMode.SEEK_NONE
        MsgUtil.showMsg("排序切换失败，已保留" + ReplySortMode.label(restoreSort))
    }

    private fun setOnSortSwitch() {
        replyAdapter?.setOnSortSwitchListener {
            switchSort()
        }
    }

    /**
     * 切换评论排序。
     *
     * 三件事必须一起做，缺一件用户就会觉得「按钮点了没反应」（这是本页报障 5 的成因）：
     * 1. **丢弃 seek**：seek_rpid 是「定位到某条评论」，排序一变就没有意义；
     *    cursor 端点带着 seek_rpid 时两种 mode 都返回同一条评论的上下文，列表看起来几乎没变。
     * 2. **清空并重建列表**：否则下拉刷新后新排序的数据会被追加在旧数据后面。
     * 3. **立刻回滚到顶部 + 给出可见反馈**：用户需要马上看到「排序真的换了」。
     */
    private fun switchSort() {
        val fromSort = sort
        sort = ReplySortMode.switched(sort)
        // 第一下切换才备份旧列表与旧 seek；连续快速切换沿用最早那份，失败时回到点按钮之前的样子。
        if (sortSwitch.begin(fromSort, sort)) {
            sortSwitchBackup = replyList?.let { ArrayList(it) }
            sortSwitchSeekBackup = seek
        }
        seek = ReplySortMode.seekAfterSwitch()
        replyAdapter?.sort = sort
        replyList?.clear()
        bindReplyAdapter()
        recyclerView?.scrollToPosition(0)
        MsgUtil.showMsg("已切换到" + ReplySortMode.label(sort))
        refresh(aid)
    }

    override fun onDestroyView() {
        // IO 可能仍在阻塞；取消作用域 + 换世代确保其返回后无法访问已销毁的 View。
        viewScope?.cancel()
        viewScope = null
        requestJob = null
        loadState.invalidate()
        sortSwitch.cancel()
        sortSwitchBackup = null
        sortSwitchSeekBackup = ReplySortMode.SEEK_NONE
        recyclerView?.adapter = null
        replyAdapter = null
        replyList = null
        recyclerView = null
        swipeRefreshLayout = null
        emptyView = null
        listener = null
        super.onDestroyView()
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
