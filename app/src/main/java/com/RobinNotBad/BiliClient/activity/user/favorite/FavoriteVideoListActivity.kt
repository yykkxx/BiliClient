package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList

//收藏夹内
//2023-08-08
//2024-05-01
//2025 收藏夹内容接口迁移到 v3（media_id），旧 x/space/fav/arc 已失效

class FavoriteVideoListActivity : RefreshListActivity() {

    private var mid: Long = 0
    private var fid: Long = 0
    private var mediaId: Long = 0
    private var videoList: ArrayList<VideoCard>? = null
    private var videoCardAdapter: VideoCardAdapter? = null

    private var longClickPosition = -1
    private var longClickTimestamp: Long = 0

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setVideoCardLayout()

        val intent = getIntent()
        mid = intent.getLongExtra("mid", 0)
        fid = intent.getLongExtra("fid", 0)
        //新版收藏夹内容接口要 media_id；老入口只传 fid 时留 0，由 API 层按 fid 推算
        mediaId = intent.getLongExtra("media_id", 0)
        val name = intent.getStringExtra("name")

        if (mid <= 0) mid = SharedPreferencesUtil.getLong("mid", 0)

        setPageName(name)

        videoList = ArrayList()

        setOnRefreshListener {
            page = 1
            setBottom(false)
            loadFirstPage()
        }

        loadFirstPage()
    }

    private fun loadFirstPage() {
        CenterThreadPool.run(Runnable {
            try {
                //用新列表拉取，避免刷新期间清空正在显示的 adapter 数据导致 RecyclerView 越界
                val list = ArrayList<VideoCard>()
                val result = FavoriteApi.getFolderVideos(mid, fid, 1, list, mediaId)
                if (isDestroyed) return@Runnable
                runOnUiThread(Runnable { // quality-allow：刷新收尾只碰主线程 UI，沿用本页历史写法
                    setRefreshing(false)
                    when {
                        result == -1 -> showEmptyTip("收藏夹加载失败\n下拉可以重试")
                        list.isEmpty() -> showEmptyTip("这个收藏夹里还没有视频")
                        else -> {
                            emptyView?.visibility = View.GONE
                            videoList = list
                            val adapter = buildAdapter(list)
                            setAdapter(adapter)
                            if (result == 1) setBottom(true)
                        }
                    }
                })
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    /** 空列表提示（保留 recyclerView 可见，这样空列表也能下拉刷新） */
    private fun showEmptyTip(text: String) {
        emptyView?.text = text
        emptyView?.visibility = View.VISIBLE
    }

    private fun buildAdapter(videoList: ArrayList<VideoCard>): VideoCardAdapter {
        val adapter = VideoCardAdapter(this, videoList)
        adapter.feedLayout = ListLayoutUtil.isDualColumnVideo()
        this.videoCardAdapter = adapter

        adapter.setOnLongClickListener { position ->
            val timestamp = System.currentTimeMillis()
            if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                CenterThreadPool.run(Runnable {
                    try {
                        if (position !in videoList.indices) return@Runnable
                        val aid = videoList[position].aid
                        //有 media_id 就用 media_id（v3 语义），没有再从 fid 推算
                        val delResult = if (mediaId > 0)
                            FavoriteApi.deleteFavoriteByMediaId(aid, mediaId)
                        else
                            FavoriteApi.deleteFavorite(aid, fid)
                        longClickPosition = -1
                        if (delResult == 0) runOnUiThread(Runnable { // quality-allow：沿用本页历史写法，回调只在主线程更新 UI，改协程收益低风险高
                            MsgUtil.showMsg("删除成功")
                            if (position in videoList.indices) {
                                videoList.removeAt(position)
                                adapter.notifyItemRemoved(position)
                                adapter.notifyItemRangeChanged(position, videoList.size - position)
                            }
                        })
                        else
                            runOnUiThread(Runnable { MsgUtil.showMsg("删除失败，错误码：" + delResult) }) // quality-allow：同上，历史写法
                    } catch (e: Exception) {
                        report(e)
                    }
                })
            } else {
                longClickPosition = position
                longClickTimestamp = timestamp
                MsgUtil.showMsg("再次长按删除")
            }
        }

        setOnLoadMoreListener { pageNum -> continueLoading(pageNum) }
        return adapter
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val videoList = this.videoList ?: return@Runnable
                val adapter = this.videoCardAdapter ?: return@Runnable
                val lastSize = videoList.size
                val result = FavoriteApi.getFolderVideos(mid, fid, page, videoList, mediaId)
                if (isDestroyed) return@Runnable
                if (result != -1) {
                    runOnUiThread(Runnable { adapter.notifyItemRangeInserted(lastSize, videoList.size - lastSize) }) // quality-allow：同上，历史写法
                    if (result == 1) setBottom(true)
                }
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }
}
