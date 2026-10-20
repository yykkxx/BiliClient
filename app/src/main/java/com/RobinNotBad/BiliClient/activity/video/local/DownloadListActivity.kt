package com.RobinNotBad.BiliClient.activity.video.local

import android.annotation.SuppressLint
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

import com.RobinNotBad.BiliClient.R

import androidx.annotation.Nullable

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.DownloadAdapter
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.DownloadSection
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.activity.user.favorite.FavoriteBatchDownloadActivity
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
    private var lastTopClickTime: Long = 0

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setPageName("下载列表")
        setRefreshing(false)
        weakRef = WeakReference(this)

        // #7：长按顶栏进入「收藏夹多选缓存」；空列表时点提示也能进
        findViewById<android.view.View>(R.id.top)?.setOnLongClickListener {
            startActivity(Intent(this, FavoriteBatchDownloadActivity::class.java))
            true
        }
        emptyView?.setOnClickListener {
            startActivity(Intent(this, FavoriteBatchDownloadActivity::class.java))
        }
        // #5：双击顶栏呼出「清空」菜单（清空待缓存+下载中 / 清空已缓存，均带确认弹窗）
        findViewById<android.view.View>(R.id.top)?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTopClickTime < 500) {
                lastTopClickTime = 0
                showClearMenu()
            } else {
                lastTopClickTime = now
            }
        }

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

    /** #5：清空菜单（双击顶栏呼出） */
    private fun showClearMenu() {
        val options = arrayOf(
                "清空待缓存与下载中（保留已缓存视频）",
                "清空已缓存视频（保留下载队列）",
                "全部清空")
        AlertDialog.Builder(this)
                .setTitle("清空")
                .setItems(options) { dialog, which ->
                    when (which) {
                        0 -> confirmClearQueue()
                        1 -> confirmClearLocal()
                        2 -> confirmClearAll()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
    }

    /** 清空待缓存 + 下载中（下载队列数据库），带确认弹窗 */
    private fun confirmClearQueue() {
        val sections = this.sections
        val count = sections?.size ?: 0
        if (count == 0) {
            MsgUtil.showMsg("下载队列为空")
            return
        }
        AlertDialog.Builder(this)
                .setTitle("清空下载队列")
                .setMessage("将清空 $count 个待缓存/下载中任务（已缓存完成的视频不受影响）。\n确定继续吗？")
                .setPositiveButton("清空") { _, _ ->
                    // 正在下载的先停服务
                    try { stopService(Intent(this, DownloadService::class.java)) } catch (e: Exception) { e.printStackTrace() }
                    CenterThreadPool.run(Runnable {
                        try {
                            DownloadService.clear()
                            runOnUiThread {
                                MsgUtil.showMsg("下载队列已清空")
                                refreshList(false)
                            }
                        } catch (e: Exception) {
                            MsgUtil.err(e)
                        }
                    })
                }
                .setNegativeButton("取消", null)
                .show()
    }

    /** 清空已缓存视频（删除下载目录内容），带确认弹窗 */
    private fun confirmClearLocal() {
        AlertDialog.Builder(this)
                .setTitle("清空已缓存视频")
                .setMessage("将删除所有已缓存完成的视频文件（下载队列不受影响）。\n确定继续吗？")
                .setPositiveButton("清空") { _, _ ->
                    CenterThreadPool.run(Runnable {
                        try {
                            FileUtil.deleteFolder(FileUtil.getVideoDownloadPath())
                            runOnUiThread { MsgUtil.showMsg("已缓存视频已清空") }
                        } catch (e: Exception) {
                            MsgUtil.err(e)
                        }
                    })
                }
                .setNegativeButton("取消", null)
                .show()
    }

    /** 全部清空（队列 + 已缓存），带确认弹窗 */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
                .setTitle("全部清空")
                .setMessage("将清空下载队列并删除所有已缓存视频文件。\n确定继续吗？")
                .setPositiveButton("全部清空") { _, _ ->
                    try { stopService(Intent(this, DownloadService::class.java)) } catch (e: Exception) { e.printStackTrace() }
                    CenterThreadPool.run(Runnable {
                        try {
                            DownloadService.clear()
                            FileUtil.deleteFolder(FileUtil.getVideoDownloadPath())
                            runOnUiThread {
                                MsgUtil.showMsg("已全部清空")
                                refreshList(false)
                            }
                        } catch (e: Exception) {
                            MsgUtil.err(e)
                        }
                    })
                }
                .setNegativeButton("取消", null)
                .show()
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
                    MsgUtil.showMsg("下载列表为空，可长按顶栏从收藏夹批量缓存")
                    emptyView?.text = "下载列表为空\n点此从收藏夹批量缓存"
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
                    // #5：长按打开视频详情页（原"长按删除"改由双击顶栏的清空菜单承担）
                    CenterThreadPool.run(Runnable {
                        try {
                            val sections = this@DownloadListActivity.sections
                            if (sections == null || position < 0 || position >= sections.size) return@Runnable
                            val section = sections.get(position)
                            if (section.aid <= 0) {
                                runOnUiThread { MsgUtil.showMsg("该任务没有视频信息，无法打开详情") }
                                return@Runnable
                            }
                            runOnUiThread {
                                com.RobinNotBad.BiliClient.util.TerminalContext.getInstance()
                                        .enterVideoDetailPage(this@DownloadListActivity, section.aid)
                            }
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
