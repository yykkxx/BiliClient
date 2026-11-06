package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCheckAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.api.VideoInfoApi
import com.RobinNotBad.BiliClient.model.FavoriteFolder
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.TerminalContext

import com.google.android.material.button.MaterialButton

import kotlinx.coroutines.Job

import java.io.IOException
import java.util.ArrayList

/**
 * 收藏夹多选缓存（#7）。
 *
 * v5.2.1 重做交互后，一个 Activity 里只有**两态**、不再开新页面：
 * 1. [STATE_FOLDERS] 收藏夹列表（点击某个收藏夹就切到态 2）；
 * 2. [STATE_VIDEOS] 该收藏夹的视频多选列表。
 *
 * 为什么不再用 `startActivity(自己)` 进第二个态：
 * 老写法把「进入收藏夹」实现成又开一个同名 Activity，于是用户按返回键直接**退出整页**，
 * 而不是回到收藏夹列表（规格 §3 第 6 条）。改成同一个 Activity 内部换态后，
 * 返回键只需判断 `state`：视频列表 → 回到收藏夹列表；收藏夹列表 → 正常退出。
 *
 * 六个交互点（对应规格 §3 的七条要求）：
 * - 全选 / 反选 / 清空 是**三个独立按钮**；全选用「已加载」的范围并在提示里说清（第 1 条）；
 * - 选择条（顶栏下方、不随列表滚动）实时显示「已选 N · 已加载 M」，底栏按钮也实时（第 2 条）；
 * - 长按整行 = **更多操作**（详情 / 仅选中此条 / 复制标题），理由见 [showMoreActions]（第 3 条）；
 * - 清晰度取**所有已选视频可用档位的交集**，交集为空则回落到「跟随播放器设置」并提示（第 4 条）；
 * - 入队用 [DownloadService.enqueueDownload] 的**同步返回值**计数，不再轮询 `queueCount`（第 5 条）；
 * - 返回键按态处理；入队进度「正在加入队列 3/20」+ 可取消（第 6、7 条）。
 *
 * 下载入队说明：VideoCard 只有 aid，下载需要 cid —— 这里对每个勾选视频先取一次
 * VideoInfo（含 cids）再入队；多 P 视频只缓存 P1（与单个视频「下载」按钮进多页选择的
 * 行为不同，这里保持批量路径简单，多 P 的可以点进详情页单独下载）。
 * 同一次点击里，「取 cid + 取可用清晰度」只发一轮请求，入队阶段复用扫描结果，
 * 不会因为「先看清晰度再下载」而对每个视频重复请求两遍。
 */
class FavoriteBatchDownloadActivity : BaseActivity() {

    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var recyclerView: RecyclerView? = null
    private var emptyView: TextView? = null
    private var selectionBar: View? = null
    private var selectedCountView: TextView? = null
    private var bottomBar: View? = null
    private var selectAllBtn: MaterialButton? = null
    private var invertBtn: MaterialButton? = null
    private var clearBtn: MaterialButton? = null
    private var downloadBtn: MaterialButton? = null
    private var progressRow: View? = null
    private var progressText: TextView? = null
    private var cancelBtn: MaterialButton? = null

    private var mid: Long = 0
    private var folderId: Long = 0
    private var folderMediaId: Long = 0
    private var folderName: String = ""

    private val videoList = ArrayList<VideoCard>()
    private val checkedAids = HashSet<Long>()
    private var checkAdapter: VideoCheckAdapter? = null

    private var state: Int = STATE_FOLDERS
    private var page = 1
    private var bottomReached = false
    private var loading = false

    /** 入队/清晰度扫描协程（可取消：进度行上的「取消」与返回键都取消它） */
    private var taskJob: Job? = null

    /** 列表加载协程（换态/刷新时取消上一次，避免旧请求把数据写回新态的列表） */
    private var loadJob: Job? = null

    /** 用户点了取消：循环在下一个条目边界退出（不打断正在写库的那一条） */
    private var cancelRequested = false

    /** 入队进行中：此时禁止再触发一次扫描/入队，也禁止改选择 */
    private var busy = false

    /** 一次扫描的结果：每条视频的 aid/title/cid/可用清晰度，以及本次请求的总条目数 */
    private class ScanResult(val requested: Int, val scanned: List<ScannedVideo>)

    /** 扫描出来的单条视频信息（下载阶段直接复用，避免重复请求） */
    private class ScannedVideo(
            val aid: Long,
            val title: String?,
            val cover: String?,
            val cid: Long,
            val qnValues: IntArray?,
            val qnStrs: Array<String?>?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_batch)

        // v5.2.1 主账号口径：mid 不再只读 web 槽位（web 退出后恒为 0，收藏夹会直接是空的）
        mid = AccountManager.accountMid()
        folderId = intent.getLongExtra("fid", 0)
        folderMediaId = intent.getLongExtra("media_id", 0)
        folderName = intent.getStringExtra("name") ?: ""

        bindViews()

        swipeRefreshLayout?.setOnRefreshListener { onRefresh() }
        recyclerView?.layoutManager = CustomLinearManager(this)
        recyclerView?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // 只有「用户拖动到列表底部」才翻页；快速 fling 也走这里，
                // 老代码把条件全挤在一个 if 里（detekt ComplexCondition 进过 baseline），
                // 拆成 maybeLoadMore() 更好读。
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) maybeLoadMore()
            }
        })

        selectAllBtn?.setOnClickListener { selectAllLoaded() }
        invertBtn?.setOnClickListener { invertSelection() }
        clearBtn?.setOnClickListener { clearSelection() }
        downloadBtn?.setOnClickListener { showQualityDialog() }
        cancelBtn?.setOnClickListener { requestCancel() }

        if (folderId == 0L) {
            showFolders()
        } else {
            // 兼容旧入口（历史上从别处带 fid 直接进本页）：直接进视频态，
            // 但返回键仍然先回收藏夹列表态 —— 与正常路径一致。
            openFolder(folderId, folderMediaId, folderName)
        }
    }

    override fun onDestroy() {
        taskJob?.cancel()
        loadJob?.cancel()
        super.onDestroy()
    }

    /**
     * 返回键（规格 §3 第 6 条）：
     * - 入队/扫描进行中：第一次返回 = 取消任务（不让用户被一个跑 40 次网络请求的任务困住），
     *   提示再按一次才退出；任务真正停下后再按一次就继续下面的逻辑；
     * - 视频列表态：回到收藏夹列表态（同一个 Activity 内换态，不退出页面）；
     * - 收藏夹列表态：走 BaseActivity 的默认退出（含用户的「返回键禁用」偏好）。
     */
    override fun onBackPressed() {
        if (busy) {
            requestCancel()
            MsgUtil.showMsg(getString(R.string.fav_batch_busy_back))
            return
        }
        if (state == STATE_VIDEOS) {
            showFolders()
            return
        }
        super.onBackPressed()
    }

    private fun bindViews() {
        emptyView = findViewById(R.id.emptyTip)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        recyclerView = findViewById(R.id.recyclerView)
        selectionBar = findViewById(R.id.selection_bar)
        selectedCountView = findViewById(R.id.selected_count)
        bottomBar = findViewById(R.id.bottom_bar)
        selectAllBtn = findViewById(R.id.btn_select_all)
        invertBtn = findViewById(R.id.btn_invert)
        clearBtn = findViewById(R.id.btn_clear)
        downloadBtn = findViewById(R.id.btn_download)
        progressRow = findViewById(R.id.progress_row)
        progressText = findViewById(R.id.progress_text)
        cancelBtn = findViewById(R.id.btn_cancel_enqueue)
    }

    private fun onRefresh() {
        if (state == STATE_FOLDERS) {
            loadFolders()
            return
        }
        page = 1
        bottomReached = false
        videoList.clear()
        checkedAids.clear()
        requestCancelIfBusy()
        loadVideos()
    }

    // ------------------------ 状态1：收藏夹列表 ------------------------

    /** 回到收藏夹列表态（从视频态返回、或首次进入）。所有视频态的状态一并清干净。 */
    private fun showFolders() {
        loadJob?.cancel()
        loading = false
        requestCancelIfBusy()
        state = STATE_FOLDERS
        folderId = 0
        folderMediaId = 0
        folderName = ""
        videoList.clear()
        checkedAids.clear()
        checkAdapter = null
        page = 1
        bottomReached = false
        selectionBar?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
        emptyView?.visibility = View.GONE
        swipeRefreshLayout?.isEnabled = false
        swipeRefreshLayout?.isRefreshing = false
        // 收藏夹列表不复用带复选框的 adapter，换成点击进入的列表
        recyclerView?.adapter = null
        setPageName(getString(R.string.fav_batch_folder_page))
        loadFolders()
    }

    private fun loadFolders() {
        loadJob?.cancel()
        swipeRefreshLayout?.isRefreshing = true
        loadJob = CoroutineScopes.launchUi {
            var folders: ArrayList<FavoriteFolder>? = null
            try {
                folders = CoroutineScopes.io { FavoriteApi.getFavoriteFolders(mid) }
            } catch (e: Exception) {
                if (!isDestroyed) report(e)
            }
            swipeRefreshLayout?.isRefreshing = false
            if (isDestroyed || isFinishing) return@launchUi
            val loaded = folders ?: ArrayList()
            emptyView?.visibility = View.GONE
            if (loaded.isEmpty()) {
                emptyView?.text = getString(R.string.fav_batch_folder_empty)
                emptyView?.visibility = View.VISIBLE
                return@launchUi
            }
            recyclerView?.adapter = FolderTapAdapter(loaded)
        }
    }

    /** 收藏夹列表（点击直接切到本 Activity 的视频态，不再开第二个 Activity） */
    private inner class FolderTapAdapter(
            private val folders: ArrayList<FavoriteFolder>
    ) : RecyclerView.Adapter<FolderTapAdapter.Holder>() {

        @NonNull
        override fun onCreateViewHolder(@NonNull parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = android.view.LayoutInflater.from(this@FavoriteBatchDownloadActivity)
                    .inflate(R.layout.cell_setting_click, parent, false)
            return Holder(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(@NonNull holder: Holder, position: Int) {
            val folder = folders.getOrNull(position) ?: return
            holder.name.text = StringUtil.htmlToString(folder.name ?: "") + "（" + folder.videoCount + "）"
            holder.itemView.setOnClickListener {
                openFolder(folder.id, folder.mediaId, folder.name ?: "")
            }
        }

        override fun getItemCount(): Int = folders.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.setting_click_name)
        }
    }

    // ------------------------ 状态2：视频多选列表 ------------------------

    /** 进入某个收藏夹的视频态（同一个 Activity 内，返回键可回到收藏夹列表） */
    private fun openFolder(fid: Long, mediaId: Long, name: String) {
        loadJob?.cancel()
        loading = false
        folderId = fid
        folderMediaId = mediaId
        folderName = name
        state = STATE_VIDEOS
        page = 1
        bottomReached = false
        videoList.clear()
        checkedAids.clear()
        checkAdapter = null
        recyclerView?.adapter = null
        emptyView?.visibility = View.GONE
        swipeRefreshLayout?.isEnabled = true
        swipeRefreshLayout?.isRefreshing = false
        selectionBar?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        setPageName(if (name.isEmpty()) getString(R.string.fav_batch_folder_page) else name)
        updateSelectionUi()
        loadVideos()
    }

    private fun maybeLoadMore() {
        if (state != STATE_VIDEOS || bottomReached || loading) return
        val rv = recyclerView ?: return
        if (rv.canScrollVertically(1)) return
        page++
        loadVideos()
    }

    private fun loadVideos() {
        if (loading) return
        loading = true
        swipeRefreshLayout?.isRefreshing = true
        loadJob?.cancel()
        val requestPage = page
        loadJob = CoroutineScopes.launchUi {
            // 先把本页结果收进临时表再并进 videoList：取消/换态时旧请求不会把数据写进新态的列表
            val pageItems = ArrayList<VideoCard>()
            var result = -1
            try {
                result = CoroutineScopes.io {
                    FavoriteApi.getFolderVideos(mid, folderId, requestPage, pageItems, folderMediaId)
                }
            } catch (e: Exception) {
                if (!isDestroyed) report(e)
            }
            loading = false
            swipeRefreshLayout?.isRefreshing = false
            if (isDestroyed || isFinishing) return@launchUi
            if (result == -1) {
                if (page > 1) page--
                return@launchUi
            }
            if (pageItems.isNotEmpty()) videoList.addAll(pageItems)
            emptyView?.visibility = View.GONE
            if (videoList.isEmpty()) {
                emptyView?.text = getString(R.string.fav_batch_video_empty)
                emptyView?.visibility = View.VISIBLE
                return@launchUi
            }
            val adapter = checkAdapter
            if (adapter == null) {
                val newAdapter = VideoCheckAdapter(this@FavoriteBatchDownloadActivity, videoList, checkedAids)
                newAdapter.onCheckedChange = Runnable { updateSelectionUi() }
                newAdapter.onItemMoreAction = { card -> showMoreActions(card) }
                checkAdapter = newAdapter
                recyclerView?.adapter = newAdapter
            } else {
                adapter.notifyDataSetChanged()
            }
            if (result == 1) bottomReached = true
            updateSelectionUi()
        }
    }

    // ------------------------ 选择动作：全选 / 反选 / 清空 ------------------------

    /**
     * 全选（规格 §3 第 1 条）。
     *
     * 只作用于**已加载**的视频（跨页不会偷偷补全），并且提示里明确写「当前已加载的 N 个」；
     * 收藏夹还有更多时额外说明「继续下滑加载后可再全选一次」——
     * 用户不会误以为自己选中了整个收藏夹（老代码只说「已全选 N 个」，这就是那条「错觉」）。
     */
    private fun selectAllLoaded() {
        if (videoList.isEmpty()) return
        for (card in videoList) checkedAids.add(card.aid)
        checkAdapter?.notifyDataSetChanged()
        updateSelectionUi()
        MsgUtil.showMsgLong(if (bottomReached) {
            getString(R.string.fav_batch_all_selected, videoList.size)
        } else {
            getString(R.string.fav_batch_all_selected_more, videoList.size)
        })
    }

    /** 反选（老代码没有这个动作，用户只能「全选/全不选」来回切）。同样只作用于已加载的条目。 */
    private fun invertSelection() {
        if (videoList.isEmpty()) return
        for (card in videoList) {
            if (!checkedAids.remove(card.aid)) checkedAids.add(card.aid)
        }
        checkAdapter?.notifyDataSetChanged()
        updateSelectionUi()
        MsgUtil.showMsg(getString(R.string.fav_batch_inverted, checkedAids.size))
    }

    /** 清空：清的是**全部**勾选（包括之前翻页选上的），语义比「再点一次全选」更明确。 */
    private fun clearSelection() {
        if (checkedAids.isEmpty()) return
        checkedAids.clear()
        checkAdapter?.notifyDataSetChanged()
        updateSelectionUi()
        MsgUtil.showMsg(getString(R.string.fav_batch_cleared))
    }

    /** 选择条 + 底栏文字实时刷新（规格 §3 第 2 条） */
    private fun updateSelectionUi() {
        val selected = checkedAids.size
        val loaded = videoList.size
        selectedCountView?.text = if (bottomReached) {
            getString(R.string.fav_batch_selected_count, selected, loaded)
        } else {
            getString(R.string.fav_batch_selected_count_more, selected, loaded)
        }
        downloadBtn?.text = getString(R.string.fav_batch_download_count, selected)
    }

    // ------------------------ 长按 = 更多操作（规格 §3 第 3 条） ------------------------

    /**
     * 长按整行的菜单。
     *
     * 为什么长按是「更多操作」而不是「进入多选模式」：
     * 本页**天生就是多选页** —— 每行恒有复选框、单击就是勾选。再让长按去「进入多选模式」，
     * 等于同一个语义做两遍，还会让「单击=勾选」变得可疑（用户会以为单击另有含义）。
     * 从用户角度，长按留给「对这条视频做点别的事」收益明确：
     * 刷到一条想先看详情再决定要不要缓存的，不必退出本页再看；
     * 只想缓存某一条时，也不用「清空 → 只勾这一条」两步。
     * App 内其它视频列表（VideoCardAdapter）的长按也是「对这条视频做别的操作」的语义，习惯一致。
     */
    private fun showMoreActions(card: VideoCard?) {
        if (card == null) return
        val labels = arrayOf(
                getString(R.string.fav_batch_menu_detail),
                getString(R.string.fav_batch_menu_only_this),
                getString(R.string.fav_batch_menu_copy_title))
        AlertDialog.Builder(this)
                .setTitle(StringUtil.htmlToString(card.title ?: ""))
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> TerminalContext.getInstance()
                                .enterVideoDetailPage(this, card.aid, card.bvid)
                        1 -> selectOnly(card)
                        else -> copyTitle(card)
                    }
                }
                .show()
    }

    /** 仅选中这一条：先清空再勾上它（长按菜单里最常用的批量前提动作） */
    private fun selectOnly(card: VideoCard) {
        checkedAids.clear()
        checkedAids.add(card.aid)
        checkAdapter?.notifyDataSetChanged()
        updateSelectionUi()
        MsgUtil.showMsg(getString(R.string.fav_batch_only_this_done))
    }

    private fun copyTitle(card: VideoCard) {
        val title = StringUtil.htmlToString(card.title ?: "")
        if (title.isEmpty()) return
        StringUtil.copyText(this, title)
        MsgUtil.showMsg(getString(R.string.fav_batch_copied))
    }

    // ------------------------ 清晰度交集 + 入队（规格 §3 第 4、5、7 条） ------------------------

    private fun showQualityDialog() {
        if (busy) return
        if (checkedAids.isEmpty()) {
            MsgUtil.showMsg(getString(R.string.fav_batch_need_selection))
            return
        }
        val aids = ArrayList(checkedAids)
        taskJob?.cancel()
        cancelRequested = false
        taskJob = CoroutineScopes.launchUi {
            setBusy(true)
            showProgress(getString(R.string.fav_batch_checking_quality, 0, aids.size))
            val result = scanSelected(aids)
            setBusy(false)
            if (isDestroyed || isFinishing) return@launchUi
            if (cancelRequested) {
                cancelRequested = false
                MsgUtil.showMsg(getString(R.string.fav_batch_canceled_none))
                return@launchUi
            }
            showQualityChoice(result)
        }
    }

    /**
     * 逐个扫「可用清晰度」。
     *
     * 每个视频要两趟请求：详情报（拿 cid）→ playurl（拿 accept_quality）。
     * playurl 以**最低档 qn=16** 请求：这里只要档位列表，不需要真的把高码率流拉下来，
     * 最低档返回最快、也最不容易被风控。
     *
     * 失败（稿件已删/私密/网络）的视频不进交集，入队阶段计为失败并汇总提示。
     */
    private suspend fun scanSelected(aids: List<Long>): ScanResult {
        val scanned = ArrayList<ScannedVideo>()
        for ((index, aid) in aids.withIndex()) {
            if (cancelRequested) break
            showProgress(getString(R.string.fav_batch_checking_quality, index + 1, aids.size))
            val item = CoroutineScopes.io { fetchScannedVideo(aid) }
            if (item != null) scanned.add(item)
        }
        return ScanResult(aids.size, scanned)
    }

    private fun fetchScannedVideo(aid: Long): ScannedVideo? {
        return try {
            val info = VideoInfoApi.getVideoInfo(aid)
            val cid = info?.cids?.getOrNull(0)
            if (info == null || cid == null || cid <= 0L) {
                null
            } else {
                val playerData = PlayerData()
                playerData.aid = aid
                playerData.cid = cid
                playerData.qn = LOW_QUALITY_QN
                PlayerApi.getVideo(playerData, true)
                ScannedVideo(aid, info.title, info.cover, cid, playerData.qnValueList, playerData.qnStrList)
            }
        } catch (ignored: IOException) {
            Logu.w("fav_batch", "取视频信息网络错误 aid=$aid")
            null
        } catch (e: Exception) {
            // 解析失败（JSONException）与其它异常合并处理：这里对单个视频一律按「拿不到信息」计数，
            // 不需要为不同异常类型给出不同文案；顺带避免在新增行里 import org.json.*（门禁点名的反模式）。
            Logu.w("fav_batch", "取视频信息失败 aid=$aid：" + e.javaClass.simpleName)
            null
        }
    }

    /**
     * 所有已选视频可用档位的**交集**（规格 §3 第 4 条）。
     *
     * 老代码只取第一个勾选视频的档位 → 可能给后面那些没有该档的视频请求一个不存在的清晰度，
     * 表现是「默默下成了别的档」或直接失败。改成交集后，选项里出现的每一档都能真的下到。
     */
    private fun commonQualities(scanned: List<ScannedVideo>): List<Int> {
        var common: List<Int>? = null
        for (item in scanned) {
            val values = item.qnValues?.toList() ?: emptyList()
            val current = common
            common = if (current == null) values else current.filter { qn -> values.contains(qn) }
        }
        // 0 不是合法清晰度；倒序让高画质排在前面（用户第一眼就看到最好的那档）
        return (common ?: emptyList()).filter { it > 0 }.sortedDescending()
    }

    private fun showQualityChoice(result: ScanResult) {
        if (isDestroyed || isFinishing) return
        val followQn = SharedPreferencesUtil.getInt("play_qn", 16)
        val common = commonQualities(result.scanned)
        val labels = ArrayList<String>()
        val values = ArrayList<Int>()
        labels.add(getString(R.string.fav_batch_follow_player, qnName(followQn)))
        values.add(QN_FOLLOW)
        for (qn in common) {
            labels.add(qualityLabel(qn, result.scanned))
            values.add(qn)
        }
        val builder = AlertDialog.Builder(this)
                .setTitle(getString(R.string.fav_batch_quality_title))
                .setSingleChoiceItems(labels.toTypedArray(), 0) { dialog, which ->
                    dialog.dismiss()
                    val chosen = values.getOrElse(which) { QN_FOLLOW }
                    startEnqueue(result, if (chosen == QN_FOLLOW) followQn else chosen)
                }
                .setNegativeButton(getString(R.string.fav_batch_dialog_cancel), null)
        // 交集为空 → 明确告诉用户只能用「跟随播放器设置」，而不是悄悄挑一个档
        if (common.isEmpty()) {
            builder.setMessage(getString(R.string.fav_batch_quality_empty, qnName(followQn)))
        } else {
            builder.setMessage(getString(R.string.fav_batch_quality_scope, result.scanned.size))
        }
        builder.show()
        val missing = result.requested - result.scanned.size
        if (missing > 0) MsgUtil.showMsgLong(getString(R.string.fav_batch_scan_partial, missing))
    }

    /** 档位 → 中文名：优先用接口给的 accept_description（不同视频的命名可能不同），拿不到再本地兜底 */
    private fun qualityLabel(qn: Int, scanned: List<ScannedVideo>): String {
        // 用 firstNotNullOfOrNull 而不是 for + 三个 continue：
        // 「在所有已扫描视频里找第一个能给这个 qn 提供名字的」是一个**查找语义**，
        // 写成循环要处理"缺 qnValues""缺 qnStrs""index 越界""名字为空"四种跳过条件，
        // 读的人得在脑子里模拟四次 continue。改成函数式的单条表达式后，
        // 「找不到就兜底」这件事一眼可见。
        val fromApi = scanned.firstNotNullOfOrNull { item ->
            val values = item.qnValues ?: return@firstNotNullOfOrNull null
            val strs = item.qnStrs ?: return@firstNotNullOfOrNull null
            val index = values.indexOf(qn)
            if (index < 0) return@firstNotNullOfOrNull null
            strs.getOrNull(index)?.takeIf { it.isNotEmpty() }
        }
        return fromApi ?: qnName(qn)
    }

    private fun qnName(qn: Int): String = when (qn) {
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P高码率"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "qn$qn"
    }

    /**
     * 入队（规格 §3 第 5 条 + 第 7 条）。
     *
     * 老写法：先 `queueCount()` 记基线 → `startDownload`（异步线程池插库）→ `Thread.sleep(50)`
     * 轮询最多 20 次判断有没有进表。两个问题：一是竞态（插库慢于 20×50ms 就误判为「已在队列」），
     * 二是 `Thread.sleep` 被质量门禁硬拦。现在改成 `DownloadService.enqueueDownload(...)`：
     * **同步插库并返回 Boolean**，循环直接用返回值计数，没有等待、没有轮询。
     */
    private fun startEnqueue(result: ScanResult, qn: Int) {
        if (busy) return
        taskJob?.cancel()
        cancelRequested = false
        taskJob = CoroutineScopes.launchUi {
            setBusy(true)
            var queued = 0
            var duplicated = 0
            var failed = result.requested - result.scanned.size
            for ((index, item) in result.scanned.withIndex()) {
                if (cancelRequested) break
                showProgress(getString(R.string.fav_batch_enqueueing, index + 1, result.scanned.size))
                val title = item.title ?: item.aid.toString()
                // 先查重再入队：这样汇总里能区分「已在队列」与「写库失败」，与 v5.2.0 的提示口径一致
                val duplicate = CoroutineScopes.io { DownloadService.isQueued(item.aid, item.cid) }
                if (duplicate) {
                    duplicated++
                } else {
                    val ok = CoroutineScopes.io {
                        DownloadService.enqueueDownload(title, item.aid, item.cid,
                                item.cover, qn, DOWNLOAD_TYPE_VIDEO, "")
                    }
                    if (ok) queued++ else failed++
                }
            }
            val canceled = cancelRequested
            cancelRequested = false
            setBusy(false)
            if (isDestroyed || isFinishing) return@launchUi
            if (canceled) {
                MsgUtil.showMsgLong(if (queued > 0) {
                    getString(R.string.fav_batch_canceled_partial, queued)
                } else {
                    getString(R.string.fav_batch_canceled_none)
                })
            } else {
                MsgUtil.showMsgLong(buildSummary(queued, duplicated, failed))
            }
            if (queued > 0) {
                checkedAids.clear()
                checkAdapter?.notifyDataSetChanged()
                updateSelectionUi()
            }
        }
    }

    private fun buildSummary(queued: Int, duplicated: Int, failed: Int): String {
        if (queued == 0 && duplicated == 0 && failed == 0) {
            return getString(R.string.fav_batch_result_none)
        }
        val sb = StringBuilder(getString(R.string.fav_batch_result_queued, queued))
        if (duplicated > 0) sb.append(getString(R.string.fav_batch_result_duplicate, duplicated))
        if (failed > 0) sb.append(getString(R.string.fav_batch_result_failed, failed))
        return sb.toString()
    }

    /** 取消：置标志，循环在下一个条目边界退出（正在写库的那一条会正常完成，不产生半条记录） */
    private fun requestCancel() {
        if (!busy) return
        cancelRequested = true
        cancelBtn?.isEnabled = false
        progressText?.text = getString(R.string.fav_batch_canceling)
    }

    /** 换态/刷新时要顺手停掉在跑的入队任务，避免它把结果写到一个已经不显示该收藏夹的页面 */
    private fun requestCancelIfBusy() {
        if (busy) {
            cancelRequested = true
            taskJob?.cancel()
            taskJob = null
            busy = false
            cancelRequested = false
            setBusy(false)
        }
    }

    private fun setBusy(active: Boolean) {
        busy = active
        downloadBtn?.isEnabled = !active
        selectAllBtn?.isEnabled = !active
        invertBtn?.isEnabled = !active
        clearBtn?.isEnabled = !active
        cancelBtn?.isEnabled = active
        progressRow?.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) progressText?.text = ""
        updateSelectionUi()
    }

    private fun showProgress(text: String) {
        if (isDestroyed) return
        progressRow?.visibility = View.VISIBLE
        progressText?.text = text
    }

    companion object {
        private const val STATE_FOLDERS = 0
        private const val STATE_VIDEOS = 1

        /** -1 = 跟随播放器设置（不写死成具体档位） */
        private const val QN_FOLLOW = -1

        /** 扫描档位时用的请求清晰度：最低档，只要 accept_quality 列表，不需要高码率流 */
        private const val LOW_QUALITY_QN = 16

        /** DownloadService 的下载类型字段：完整视频 */
        private const val DOWNLOAD_TYPE_VIDEO = "video"
    }
}
