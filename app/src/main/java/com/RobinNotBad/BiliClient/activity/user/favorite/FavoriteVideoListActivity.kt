package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.MsgUtil

import java.util.ArrayList

//收藏夹内
//2023-08-08
//2024-05-01

class FavoriteVideoListActivity : RefreshListActivity() {

    private var mid: Long = 0
    private var fid: Long = 0
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
        val name = intent.getStringExtra("name")

        setPageName(name)

        videoList = ArrayList()

        CenterThreadPool.run(Runnable {
            try {
                val videoList = this.videoList!!
                val result = FavoriteApi.getFolderVideos(mid, fid, page, videoList)
                if (result != -1) {
                    val videoCardAdapter = VideoCardAdapter(this, videoList)
                    videoCardAdapter.feedLayout = ListLayoutUtil.isDualColumnVideo()
                    this.videoCardAdapter = videoCardAdapter

                    videoCardAdapter.setOnLongClickListener { position ->
                        val timestamp = System.currentTimeMillis()
                        if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                            CenterThreadPool.run(Runnable {
                                try {
                                    val delResult = FavoriteApi.deleteFavorite(videoList[position].aid, fid)
                                    longClickPosition = -1
                                    if (delResult == 0) runOnUiThread(Runnable {
                                        MsgUtil.showMsg("删除成功")
                                        videoList.removeAt(position)
                                        videoCardAdapter.notifyItemRemoved(position)
                                        videoCardAdapter.notifyItemRangeChanged(position, videoList.size - position)
                                    })
                                    else
                                        runOnUiThread(Runnable { MsgUtil.showMsg("删除失败，错误码：" + delResult) })
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
                    setAdapter(videoCardAdapter)
                    setRefreshing(false)

                    if (result == 1) {
                        Log.e("debug", "到底了")
                        setBottom(true)
                    }
                }

            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val videoList = this.videoList!!
                val videoCardAdapter = this.videoCardAdapter!!
                val lastSize = videoList.size
                val result = FavoriteApi.getFolderVideos(mid, fid, page, videoList)
                if (result != -1) {
                    Log.e("debug", "下一页")
                    runOnUiThread(Runnable { videoCardAdapter.notifyItemRangeInserted(lastSize, videoList.size - lastSize) })
                    if (result == 1) {
                        Log.e("debug", "到底了")
                        setBottom(true)
                    }
                }
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }
}
