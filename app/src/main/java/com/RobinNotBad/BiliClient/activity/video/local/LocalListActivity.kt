package com.RobinNotBad.BiliClient.activity.video.local

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.video.LocalVideoAdapter
import com.RobinNotBad.BiliClient.model.LocalVideo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.MsgUtil

import java.io.File
import java.util.ArrayList

//本地列表
//2023-08-07

class LocalListActivity : InstanceActivity() {

    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var recyclerView: RecyclerView? = null
    private val videoList = ArrayList<LocalVideo>(10)
    private var adapter: LocalVideoAdapter? = null
    private var emptyTip: TextView? = null

    private var longClickPosition = -1
    private var longClickTimestamp: Long = 0

    private var started = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_main_refresh)
        setMenuClick()

        recyclerView = findViewById(R.id.recyclerView)

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout!!.setOnRefreshListener { refresh() }

        emptyTip = findViewById(R.id.emptyTip)

        val pageName = findViewById<TextView>(R.id.pageName)
        pageName.text = "缓存"

        if (!FileUtil.checkStoragePermission()) {
            FileUtil.requestStoragePermission(this)
        }

        CenterThreadPool.run(Runnable {
            runOnUiThread { swipeRefreshLayout?.isRefreshing = true }
            scan(FileUtil.getVideoDownloadPath())
            val newAdapter = LocalVideoAdapter(this, videoList)
            adapter = newAdapter

            newAdapter.setOnLongClickListener { position ->
                val timestamp = System.currentTimeMillis()
                if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                    val file = File(FileUtil.getVideoDownloadPath(), videoList[position].title ?: "")
                    CenterThreadPool.run(Runnable { FileUtil.deleteFolder(file) })
                    MsgUtil.showMsg("删除成功")
                    videoList.removeAt(position)
                    newAdapter.notifyItemRemoved(position + 1)
                    newAdapter.notifyItemRangeChanged(position + 1, videoList.size - position)
                    longClickPosition = -1
                    checkEmpty()
                } else {
                    longClickPosition = position
                    longClickTimestamp = timestamp
                    MsgUtil.showMsg("再次长按删除")
                }
            }
            val recyclerViewLocal = recyclerView
            val swipeRefreshLayoutLocal = swipeRefreshLayout
            runOnUiThread {
                recyclerViewLocal!!.layoutManager = getLayoutManager()
                recyclerViewLocal.adapter = newAdapter
                swipeRefreshLayoutLocal!!.isRefreshing = false
                started = true
            }
        })
    }

    private fun scan(folder: File) {
        val files = folder.listFiles() ?: return

        for (video in files) {
            if (video.isDirectory) {
                val localVideo = LocalVideo()
                localVideo.title = video.name

                localVideo.cover = File(video, "cover.png").toString()

                localVideo.pageList = ArrayList()
                localVideo.danmakuFileList = ArrayList()
                localVideo.videoFileList = ArrayList()
                localVideo.sizeList = ArrayList()

                val videoFile = File(video, "video.mp4")
                val audioFile = File(video, "audio.m4a") // 仅音频文件
                val danmakuFile = File(video, "danmaku.xml")

                // 优先检查视频文件，其次检查音频文件
                val mediaFile = if (videoFile.exists()) videoFile else (if (audioFile.exists()) audioFile else null)

                if (mediaFile != null) {
                    val mark = File(video, ".DOWNLOADING")
                    if (mark.exists())
                        continue

                    localVideo.sizeList!!.add(mediaFile.length())
                    localVideo.videoFileList!!.add(mediaFile.toString())
                    localVideo.danmakuFileList!!.add(danmakuFile.toString()) // 单集视频

                    localVideo.calcTotalSize()
                    videoList.add(localVideo)
                } else {
                    val pages = video.listFiles() // 分页视频
                    if (pages != null) {
                        for (page in pages) {
                            if (page.isDirectory) {
                                val mark = File(page, ".DOWNLOADING")
                                if (mark.exists())
                                    continue

                                val pageVideoFile = File(page, "video.mp4")
                                val pageAudioFile = File(page, "audio.m4a") // 仅音频文件
                                val pageDanmakuFile = File(page, "danmaku.xml")

                                // 优先检查视频文件，其次检查音频文件
                                val pageMediaFile = if (pageVideoFile.exists()) pageVideoFile
                                else (if (pageAudioFile.exists()) pageAudioFile else null)

                                if (pageMediaFile != null) {
                                    localVideo.pageList!!.add(page.name)
                                    localVideo.sizeList!!.add(pageMediaFile.length())
                                    localVideo.videoFileList!!.add(pageMediaFile.toString())
                                    localVideo.danmakuFileList!!.add(pageDanmakuFile.toString())
                                }
                            }
                        }
                        localVideo.calcTotalSize()
                        if (localVideo.videoFileList!!.size > 0)
                            videoList.add(localVideo)
                    }
                }
            }
        }
        checkEmpty()
    }

    private fun checkEmpty() {
        runOnUiThread {
            val tip = emptyTip
            if (videoList.isEmpty() && tip != null) {
                tip.visibility = View.VISIBLE
            } else {
                if (tip != null) {
                    tip.visibility = View.GONE
                }
            }
        }
    }

    fun refresh() {
        if (started)
            CenterThreadPool.run(Runnable {
                runOnUiThread { swipeRefreshLayout?.isRefreshing = true }
                val oldSize = videoList.size
                videoList.clear()
                scan(FileUtil.getVideoDownloadPath())
                runOnUiThread {
                    adapter!!.notifyItemRangeChanged(1, oldSize)
                    swipeRefreshLayout?.isRefreshing = false
                }
            })
    }
}
