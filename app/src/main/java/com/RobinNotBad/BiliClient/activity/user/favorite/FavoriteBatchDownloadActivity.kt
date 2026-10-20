package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.LinearLayoutManager

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCheckAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.api.VideoInfoApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import com.google.android.material.button.MaterialButton

import java.util.ArrayList

/**
 * 收藏夹多选缓存（#7）。
 *
 * 两态页面：
 * 1. 未选收藏夹：列出全部收藏夹（点击进入）；
 * 2. 已选收藏夹：列出收藏夹内视频，逐个勾选（或全选），
 *    点「缓存所选」把勾选的视频全部塞进下载队列（DownloadService 串行下载）。
 *
 * 下载入队说明：VideoCard 只有 aid，下载需要 cid —— 这里对每个勾选视频
 * 先取一次 VideoInfo（含 cids）再入队；多 P 视频只缓存 P1（与单个视频
 * 「下载」按钮进多页选择的行为不同，这里保持批量路径简单，多 P 的可以
 * 点进详情页单独下载）。
 */
class FavoriteBatchDownloadActivity : BaseActivity() {

    private var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var recyclerView: androidx.recyclerview.widget.RecyclerView? = null
    private var emptyView: TextView? = null
    private var selectAllBtn: MaterialButton? = null
    private var downloadBtn: MaterialButton? = null

    private var mid: Long = 0
    private var folderId: Long = 0
    private var folderName: String = ""

    private val videoList = ArrayList<VideoCard>()
    private val checkedAids = HashSet<Long>()
    private var checkAdapter: VideoCheckAdapter? = null

    private var page = 1
    private var bottomReached = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_batch)

        mid = SharedPreferencesUtil.getLong("mid", 0)
        folderId = intent.getLongExtra("fid", 0)
        folderName = intent.getStringExtra("name") ?: ""

        emptyView = findViewById(R.id.emptyTip)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout!!.isEnabled = false
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView!!.layoutManager = LinearLayoutManager(this)

        selectAllBtn = findViewById(R.id.btn_select_all)
        downloadBtn = findViewById(R.id.btn_download)

        if (folderId == 0L) {
            setPageName("收藏夹多选缓存")
            selectAllBtn!!.visibility = View.GONE
            downloadBtn!!.visibility = View.GONE
            loadFolders()
        } else {
            setPageName(folderName)
            downloadBtn!!.visibility = View.VISIBLE
            selectAllBtn!!.visibility = View.VISIBLE

            swipeRefreshLayout!!.setOnRefreshListener {
                page = 1
                bottomReached = false
                videoList.clear()
                checkedAids.clear()
                loadVideos()
            }
            swipeRefreshLayout!!.isEnabled = true

            recyclerView!!.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                    if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING
                            && !rv.canScrollVertically(1) && !bottomReached && !loading) {
                        page++
                        loadVideos()
                    }
                }
            })

            selectAllBtn!!.setOnClickListener { toggleSelectAll() }
            downloadBtn!!.setOnClickListener { downloadChecked() }
            updateDownloadBtnText()

            loadVideos()
        }
    }

    // ------------------------ 状态1：收藏夹列表 ------------------------

    private fun loadFolders() {
        swipeRefreshLayout!!.isRefreshing = true
        CenterThreadPool.run(Runnable {
            try {
                val folders = FavoriteApi.getFavoriteFolders(mid)
                CenterThreadPool.runOnUiThread(Runnable {
                    if (isDestroyed) return@Runnable
                    swipeRefreshLayout!!.isRefreshing = false
                    if (folders.isEmpty()) {
                        emptyView!!.text = "没有收藏夹"
                        emptyView!!.visibility = View.VISIBLE
                        return@Runnable
                    }
                    // 复用现有收藏夹 Adapter，但拦截点击改成进入本页的多选模式
                    // （直接换 intent 目标不行，Adapter 内部写死了跳转，这里包一层）
                    swipeRefreshLayout!!.isEnabled = false
                    recyclerView!!.adapter = FolderTapAdapter(this, folders)
                })
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread(Runnable {
                    if (isDestroyed) return@Runnable
                    swipeRefreshLayout!!.isRefreshing = false
                    emptyView!!.text = "加载失败，请重试"
                    emptyView!!.visibility = View.VISIBLE
                    report(e)
                })
            }
        })
    }

    /** 收藏夹列表（点击进入该收藏夹的多选缓存），标题行风格与设置页一致 */
    private inner class FolderTapAdapter(
            private val activity: FavoriteBatchDownloadActivity,
            private val folders: ArrayList<com.RobinNotBad.BiliClient.model.FavoriteFolder>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<FolderTapAdapter.Holder>() {

        @NonNull
        override fun onCreateViewHolder(@NonNull parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = android.view.LayoutInflater.from(activity)
                    .inflate(R.layout.cell_setting_click, parent, false)
            return Holder(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(@NonNull holder: Holder, position: Int) {
            if (position < 0 || position >= folders.size) return
            val folder = folders[position] ?: return
            holder.text.text = com.RobinNotBad.BiliClient.util.StringUtil.htmlToString(folder.name ?: "") +
                    "（" + folder.videoCount + "）"
            holder.itemView.setOnClickListener {
                val intent = android.content.Intent(activity, FavoriteBatchDownloadActivity::class.java)
                intent.putExtra("fid", folder.id)
                intent.putExtra("name", folder.name)
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = folders.size

        inner class Holder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            val text: TextView = itemView.findViewById(R.id.setting_click_name)
        }
    }

    // ------------------------ 状态2：视频多选列表 ------------------------

    private fun loadVideos() {
        if (loading) return
        loading = true
        swipeRefreshLayout!!.isRefreshing = true
        CenterThreadPool.run(Runnable {
            var result = -1
            try {
                result = FavoriteApi.getFolderVideos(mid, folderId, page, videoList)
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread(Runnable {
                    if (isDestroyed) return@Runnable
                    loading = false
                    swipeRefreshLayout!!.isRefreshing = false
                    page = if (page > 1) page - 1 else 1
                    report(e)
                })
                return@Runnable
            }
            CenterThreadPool.runOnUiThread(Runnable {
                if (isDestroyed) return@Runnable
                loading = false
                swipeRefreshLayout!!.isRefreshing = false
                emptyView!!.visibility = View.GONE

                if (videoList.isEmpty()) {
                    emptyView!!.text = "这个收藏夹是空的"
                    emptyView!!.visibility = View.VISIBLE
                    return@Runnable
                }

                if (checkAdapter == null) {
                    checkAdapter = VideoCheckAdapter(this, videoList, checkedAids)
                    checkAdapter!!.onCheckedChange = Runnable { updateDownloadBtnText() }
                    recyclerView!!.adapter = checkAdapter
                } else {
                    checkAdapter!!.notifyDataSetChanged()
                }

                if (result == 1) bottomReached = true
            })
        })
    }

    private fun toggleSelectAll() {
        if (checkedAids.size == videoList.size) {
            checkedAids.clear()
            MsgUtil.showMsg("已取消全选")
        } else {
            for (card in videoList) checkedAids.add(card.aid)
            MsgUtil.showMsg("已全选 " + checkedAids.size + " 个视频")
        }
        checkAdapter!!.notifyDataSetChanged()
        updateDownloadBtnText()
    }

    @SuppressLint("SetTextI18n")
    private fun updateDownloadBtnText() {
        downloadBtn!!.text = "缓存所选(" + checkedAids.size + ")"
    }

    private fun downloadChecked() {
        if (checkedAids.isEmpty()) {
            MsgUtil.showMsg("还没有勾选任何视频")
            return
        }
        val aids = ArrayList(checkedAids)
        downloadBtn!!.isEnabled = false
        selectAllBtn!!.isEnabled = false
        MsgUtil.showMsg("正在加入下载队列…")

        CenterThreadPool.run(Runnable {
            var queued = 0
            var skipped = 0
            var failed = 0
            for (aid in aids) {
                try {
                    // VideoCard 只有 aid；下载需要 cid，先取详情（多 P 只取 P1）
                    val info = VideoInfoApi.getVideoInfo(aid)
                    if (info == null) { failed++; continue }
                    if (info.cids == null || info.cids!!.isEmpty()) { failed++; continue }
                    val cid = info.cids!![0]
                    val before = DownloadService.queueCount()
                    DownloadService.startDownload(info.title ?: aid.toString(), aid, cid,
                            info.cover, SharedPreferencesUtil.getInt("play_qn", 16), "video", "")
                    // startDownload 是异步插库，等它真正进表再数（简单起见轮询一次短暂等待）
                    var wait = 0
                    while (DownloadService.queueCount() == before && wait < 20) {
                        Thread.sleep(50)
                        wait++
                    }
                    if (DownloadService.queueCount() > before) queued++ else skipped++
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Runnable
                } catch (e: Exception) {
                    failed++
                }
            }
            CenterThreadPool.runOnUiThread(Runnable {
                if (isDestroyed) return@Runnable
                downloadBtn!!.isEnabled = true
                selectAllBtn!!.isEnabled = true
                val msg = StringBuilder("已加入队列 " + queued + " 个")
                if (skipped > 0) msg.append("，已在队列 " + skipped + " 个")
                if (failed > 0) msg.append("，失败 " + failed + " 个")
                MsgUtil.showMsgLong(msg.toString())
                if (queued > 0) {
                    checkedAids.clear()
                    checkAdapter!!.notifyDataSetChanged()
                    updateDownloadBtnText()
                }
            })
        })
    }
}
