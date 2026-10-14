package com.RobinNotBad.BiliClient.activity.video.local

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import java.io.File
import android.view.View
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.video.PageChooseAdapter
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.MsgUtil

//分页视频选集
//2023-07-17

class LocalPageChooseActivity : BaseActivity() {

    private var longClickPosition = -1
    private var longClickTimestamp: Long = 0
    private var deleted = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_list)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        findViewById<View>(R.id.top).setOnClickListener { finish() }

        val textView = findViewById<TextView>(R.id.pageName)
        textView.text = "请选择分页"

        val intent = getIntent()
        val title = intent.getStringExtra("title") ?: ""
        val pageList = intent.getStringArrayListExtra("pageList")
        val videoFileList = intent.getStringArrayListExtra("videoFileList")
        val danmakuFileList = intent.getStringArrayListExtra("danmakuFileList")

        val adapter = PageChooseAdapter(this, pageList)
        val pageListLocal = pageList
        val videoFileListLocal = videoFileList
        val danmakuFileListLocal = danmakuFileList
        adapter.setOnItemClickListener { position ->
            val playerData = PlayerData(PlayerData.TYPE_LOCAL)
            val mediaPath = videoFileListLocal!![position] ?: ""
            playerData.videoUrl = mediaPath
            playerData.danmakuUrl = danmakuFileListLocal!![position]
            playerData.title = pageListLocal!![position]
            try {
                val player = PlayerApi.jumpToPlayer(playerData)
                // 如果是仅音频文件，标记为音频模式
                if (mediaPath.endsWith("audio.m4a")) {
                    player.putExtra("audio_only", true)
                }
                startActivity(player)
            } catch (e: ActivityNotFoundException) {
                MsgUtil.showMsg("没有找到播放器，请检查是否安装")
            } catch (e: Exception) {
                MsgUtil.err(e)
            }
        }
        adapter.setOnItemLongClickListener { position ->
            val timestamp = System.currentTimeMillis()
            if (longClickPosition == position && timestamp - longClickTimestamp < 4000) {
                CenterThreadPool.run(Runnable {
                    val workPath = FileUtil.getVideoDownloadPath()
                    val videoPath = File(workPath, title)
                    val pagePath = File(videoPath, pageListLocal!![position] ?: "")

                    FileUtil.deleteFolder(pagePath)
                    pageListLocal.removeAt(position)
                    videoFileListLocal!!.removeAt(position)
                    danmakuFileListLocal!!.removeAt(position)
                    if (pageListLocal.isEmpty())
                        FileUtil.deleteFolder(videoPath)
                })

                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(0, pageListLocal!!.size - position)

                MsgUtil.showMsg("删除成功")
                longClickPosition = -1

                deleted = true
            } else {
                longClickPosition = position
                longClickTimestamp = timestamp
                MsgUtil.showMsg("再次长按删除")
            }
        }

        recyclerView.layoutManager = CustomLinearManager(this)
        recyclerView.adapter = adapter
    }

    override fun onDestroy() {
        val instance = BiliTerminal.getInstanceActivityOnTop()
        if (deleted && instance is LocalListActivity && !instance.isDestroyed) (instance as LocalListActivity).refresh()
        super.onDestroy()
    }
}
