package com.RobinNotBad.BiliClient.activity.video.local

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

import androidx.annotation.Nullable

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.DownloadAdapter
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.DownloadSection
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.MsgUtil

import java.io.File
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.Timer
import java.util.TimerTask

class DownloadListActivity : RefreshListActivity() {

    companion object {
        @JvmField
        var weakRef: WeakReference<DownloadListActivity>? = null
    }

    @JvmField
    var adapter: DownloadAdapter? = null
    var timer: Timer? = null
    var emptyTipShown = false
    var firstRefresh = true
    var created = false
    var sections: ArrayList<DownloadSection>? = null
    private var lastPercent = -1f
    private var lastState: String? = null
    private var lastDownloadingId: Long = -1

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setPageName("下载列表")
        setRefreshing(false)
        weakRef = WeakReference(this)

        CenterThreadPool.run(Runnable {
            created = true
            refreshList(false)

            timer = Timer()
            timer!!.schedule(object : TimerTask() {
                override fun run() {
                    val adapter = this@DownloadListActivity.adapter
                    if (adapter == null || !created || isDestroyed()) return
                    if (DownloadService.section != null) {
                        var needUpdate = false
                        if (lastDownloadingId != DownloadService.section!!.id) {
                            lastDownloadingId = DownloadService.section!!.id
                            needUpdate = true
                        }
                        if (lastPercent != DownloadService.percent) {
                            lastPercent = DownloadService.percent
                            needUpdate = true
                        }
                        if (lastState == null || lastState != DownloadService.state) {
                            lastState = DownloadService.state
                            needUpdate = true
                        }
                        if (needUpdate) {
                            val pos = findDownloadingPosition()
                            if (pos >= 0) {
                                runOnUiThread(Runnable {
                                    adapter.notifyItemChanged(pos)
                                })
                            }
                        }
                    }
                }
            }, 300L, 500L)
        })

    }

    private fun findDownloadingPosition(): Int {
        val sections = this.sections ?: return -1
        if (DownloadService.section == null) return -1
        for (i in sections.indices) {
            if (sections.get(i).id == DownloadService.section!!.id) {
                return i
            }
        }
        return -1
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshList(fromOutside: Boolean) {
        if (this.isDestroyed() || !created) return
        Log.d("debug", "刷新下载列表")

        sections = DownloadService.getAll()
        val sections = this.sections

        if (sections == null || sections.isEmpty()) {
            if (!emptyTipShown) {
                runOnUiThread(Runnable {
                    MsgUtil.showMsg("下载列表为空")
                    showEmptyView()
                })
                emptyTipShown = true
            }
        } else {
            for (s in sections) {
                Log.d("debug-download", s.name_short ?: "")
            }

            if (emptyTipShown) {
                emptyTipShown = false
                runOnUiThread(Runnable {
                    hideEmptyView()
                })
            }

            if (firstRefresh) {
                adapter = DownloadAdapter(this@DownloadListActivity, sections)
                adapter!!.setOnClickListener(OnItemClickListener { position ->
                    CenterThreadPool.run(Runnable {
                        Log.d("debug-download", "click:$position")
                        val sections = this@DownloadListActivity.sections
                        if (sections == null || position < 0 || position >= sections.size) return@Runnable

                        val section = sections.get(position)
                        if (section.state == "downloading") {
                            MsgUtil.showMsg("下载中，无法操作")
                            return@Runnable
                        }
                        if (section.state == "error") {
                            DownloadService.setState(section.id, "none")
                        }

                        DownloadService.start(section.id)
                    })
                })

                adapter!!.setOnLongClickListener(OnItemLongClickListener { position ->
                    CenterThreadPool.run(Runnable {
                        try {
                            val sections = this@DownloadListActivity.sections
                            if (sections == null || position < 0 || position >= sections.size) return@Runnable

                            val delete = sections.get(position)

                            if (delete.state == "downloading" && DownloadService.started) {
                                stopService(Intent(this@DownloadListActivity, DownloadService::class.java))
                                try {
                                    Thread.sleep(500)
                                } catch (ignored: InterruptedException) {
                                }
                            }

                            val folder = delete.getPath()
                            if (folder != null && folder.exists()) {
                                FileUtil.deleteFolder(folder)
                            }

                            DownloadService.deleteSection(delete.id)

                            refreshList(false)
                            MsgUtil.showMsg("删除成功")
                        } catch (e: Exception) {
                            MsgUtil.err(e)
                        }
                    })
                })

                runOnUiThread(Runnable {
                    setAdapter(adapter!!)
                })
                firstRefresh = false
            } else {
                adapter!!.downloadList = sections
                runOnUiThread(Runnable {
                    adapter!!.notifyDataSetChanged()
                })
                Log.d("debug-adapter", adapter!!.itemCount.toString())
            }
        }

    }

    override fun onDestroy() {
        if (timer != null) timer!!.cancel()
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        weakRef = null
        super.onDestroy()
    }
}
