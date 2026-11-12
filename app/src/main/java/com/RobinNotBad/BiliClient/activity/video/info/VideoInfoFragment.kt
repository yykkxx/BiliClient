package com.RobinNotBad.BiliClient.activity.video.info

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.DisplayMetrics
import android.view.Display
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView

import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.base.BaseFragment
import com.RobinNotBad.BiliClient.activity.dynamic.send.SendDynamicActivity
import com.RobinNotBad.BiliClient.activity.search.SearchActivity
import com.RobinNotBad.BiliClient.activity.settings.SettingTerminalPlayerActivity
import com.RobinNotBad.BiliClient.activity.user.HistoryActivity
import com.RobinNotBad.BiliClient.activity.user.WatchLaterActivity
import com.RobinNotBad.BiliClient.activity.user.favorite.FavoriteFolderListActivity
import com.RobinNotBad.BiliClient.activity.video.MultiPageActivity
import com.RobinNotBad.BiliClient.activity.video.QualityChooserActivity
import com.RobinNotBad.BiliClient.activity.video.collection.CollectionInfoActivity
import com.RobinNotBad.BiliClient.adapter.user.UpListAdapter
import com.RobinNotBad.BiliClient.api.BangumiApi
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.HistoryApi
import com.RobinNotBad.BiliClient.api.LikeCoinFavApi
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.api.VideoInfoApi
import com.RobinNotBad.BiliClient.api.WatchLaterApi
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.Stats
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.RobinNotBad.BiliClient.util.WifiPreloadManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.regex.Pattern

//真正的视频详情页
//2023-07-17

class VideoInfoFragment : BaseFragment() {

    val RESULT_ADDED = 1
    val RESULT_DELETED = -1
    val favLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { o ->
        val code = o.resultCode
        if (code == RESULT_ADDED) {
            fav!!.setImageResource(R.drawable.icon_fav_1)
        } else if (code == RESULT_DELETED) {
            fav!!.setImageResource(R.drawable.icon_fav_0)
        }
    }
    private var videoInfo: VideoInfo? = null
    // 其实我不会用，也是抄的上面的😡
    val writeDynamicLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == Activity.RESULT_OK && data != null) {
            val text = data.getStringExtra("text") ?: ""
            CenterThreadPool.run(Runnable {
                try {
                    val atUids = HashMap<String, Long>()
                    val pattern = Pattern.compile("@(\\S+)\\s")
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        val matchedString = matcher.group(1) ?: continue
                        val uid = DynamicApi.mentionAtFindUser(matchedString)
                        if (uid != -1L) {
                            atUids[matchedString] = uid
                        }
                    }
                    val dynId = DynamicApi.relayVideo(text, if (atUids.isEmpty()) null else atUids, videoInfo!!.aid)

                    if (dynId != -1L) MsgUtil.showMsg("转发成功~")
                    else MsgUtil.showMsg("转发失败")
                } catch (e: Exception) {
                    MsgUtil.err(e)
                }
            })
        }
    }
    private var playerData: PlayerData? = null
    private var aid: Long = 0
    private var bvid: String? = null
    private var description: TextView? = null
    private var tagsText: TextView? = null
    private var like: ImageView? = null
    private var coin: ImageView? = null
    private var fav: ImageView? = null
    private var coinAdd = 0
    private var desc_expand = false
    private var tags_expand = false
    private var shakeAnimation: Animation? = null
    private var tripleActionRunnable: Runnable? = null
    private var isTripleInProgress = false

    // ---------------- 「同时长按 收藏 + 稍后再看」打开历史记录（v5.3.4 需求 4） ----------------

    /**
     * 两个按钮各自「单独长按」的延迟任务。
     *
     * 为什么不在长按回调里**立刻**执行原行为：用户要的「同时长按」本质是两个独立的长按事件，
     * 人手不可能严格同时。如果第一次长按立刻打开收藏夹列表，页面已经被推走了，
     * 第二个长按根本落不到这个 Fragment 上 —— 组合手势永远不可能成立。
     * 所以第一次长按先**挂起** [COMBO_WINDOW_MS] 再执行原行为；窗口内另一按钮也被长按时，
     * 取消这个挂起任务、改为打开历史记录。
     *
     * 代价是单独长按会晚 [COMBO_WINDOW_MS] 才响应。取 450ms 是在
     * 「组合手势够宽容」与「单独长按不觉得卡」之间的折中（系统长按本身就要 500ms，
     * 用户已经按了那么久，再多 450ms 体感可接受；再长就会被觉得「点了没反应」）。
     */
    private var favLongPressPending: Runnable? = null
    private var watchLaterLongPressPending: Runnable? = null

    /** 最近一次长按是哪个按钮、发生在什么时候（毫秒时间戳，0 = 没有） */
    private var lastLongPressSource = 0
    private var lastLongPressAt = 0L

    /**
     * 处理一次「收藏 / 稍后再看」的长按。
     *
     * 三条路径：
     * 1. 另一个按钮刚在 [COMBO_WINDOW_MS] 内被长按过 → **组合手势**：撤销所有挂起任务，打开历史记录；
     * 2. 否则 → 挂起自己的原行为 [single]，并在窗口到期后执行；
     * 3. 同一按钮连按两次（窗口内）→ 不组成组合，第二次直接执行原行为（避免把单按拖成两次延迟）。
     *
     * @param source [SOURCE_FAV] 或 [SOURCE_WATCH_LATER]
     * @param single 这个按钮「单独长按」原本该做的事
     */
    private fun handleComboLongPress(source: Int, single: () -> Unit) {
        val now = System.currentTimeMillis()
        val other = if (source == SOURCE_FAV) SOURCE_WATCH_LATER else SOURCE_FAV
        val combo = lastLongPressSource == other && now - lastLongPressAt <= COMBO_WINDOW_MS

        // 无论走哪条路径，先把这个按钮自己的挂起任务清掉，避免重复执行
        clearPendingLongPress(source)

        if (combo) {
            // 组合成立：把另一个按钮的挂起任务也撤销，两个原行为都不执行
            clearPendingLongPress(other)
            lastLongPressSource = 0
            lastLongPressAt = 0L
            openHistory()
            return
        }

        lastLongPressSource = source
        lastLongPressAt = now

        // 用 rootView 承载 postDelayed：Fragment 销毁时统一 removeCallbacks（见 onDestroy）
        val host = view ?: run {
            // 没有视图时无法延迟，直接执行原行为（退化路径，不会丢功能）
            single()
            return
        }
        val pending = Runnable {
            clearPendingLongPress(source)
            // 原行为执行后清掉手势状态：否则「先单按收藏，再单按稍后再看」会被误判成组合
            if (lastLongPressSource == source) {
                lastLongPressSource = 0
                lastLongPressAt = 0L
            }
            if (isAdded && !isDetached) single()
        }
        when (source) {
            SOURCE_FAV -> favLongPressPending = pending
            else -> watchLaterLongPressPending = pending
        }
        host.postDelayed(pending, COMBO_WINDOW_MS)
    }

    /** 撤销某个按钮的挂起长按任务 */
    private fun clearPendingLongPress(source: Int) {
        val pending = if (source == SOURCE_FAV) favLongPressPending else watchLaterLongPressPending
        if (pending != null) {
            view?.removeCallbacks(pending)
            if (source == SOURCE_FAV) favLongPressPending = null else watchLaterLongPressPending = null
        }
    }

    /** 撤销全部挂起的长按任务（页面销毁时必须调用，否则回调会打到已销毁的 Fragment） */
    private fun clearAllPendingLongPress() {
        clearPendingLongPress(SOURCE_FAV)
        clearPendingLongPress(SOURCE_WATCH_LATER)
        lastLongPressSource = 0
        lastLongPressAt = 0L
    }

    private fun openHistory() {
        try {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
            MsgUtil.showMsg("已打开历史记录")
        } catch (e: Exception) {
            MsgUtil.err(e)
        }
    }

    companion object {
        /** 「同时长按」的判定窗口：两个按钮的长按间隔不超过这个时长就算组合手势 */
        private const val COMBO_WINDOW_MS = 450L

        /** 长按来源：收藏（`layout_fav`） */
        private const val SOURCE_FAV = 1

        /** 长按来源：稍后再看（`addWatchlater`） */
        private const val SOURCE_WATCH_LATER = 2

        @JvmStatic
        fun newInstance(aid: Long, bvid: String?): VideoInfoFragment {
            val args = Bundle()
            args.putLong("aid", aid)
            args.putString("bvid", bvid)
            val fragment = VideoInfoFragment()
            fragment.setArguments(args)
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bundle = getArguments()
        if (bundle == null) {
            MsgUtil.showMsg("视频详情页：数据为空")
            return
        }
        aid = bundle.getLong("aid")
        bvid = bundle.getString("bvid")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_video_info, container, false)
    }

    override fun onViewCreated(@NonNull rootview: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootview, savedInstanceState)

        TerminalContext.getInstance().getVideoInfoByAidOrBvId(aid, bvid).observe(getViewLifecycleOwner()) { videoInfoResult ->
            videoInfoResult.onSuccess { videoInfo ->
                if (getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) return@onSuccess
                this.videoInfo = videoInfo

                // 详情页渲染跑在 LiveData 的主线程回调里，这里没有 try 就是一颗闪退地雷：
                // 一旦下面的渲染（含 Glide、列表、Spannable）抛出任何异常，进程直接被杀，
                // 用户看到的正是「点进视频详情就闪退」。现在兜住并把堆栈交给全局落盘。
                try {
                    normalizeVideoInfo(videoInfo)
                    initView(rootview)
                } catch (e: Exception) {
                    MsgUtil.err("打开视频详情失败", e)
                }
            }.onFailure { error ->
            }
        }
    }

    /**
     * 把可能为 null 的集合/统计字段补齐成「空但合法」的值。
     *
     * 为什么要这一步：VideoInfo 的 stats / staff / cids / pagenames 都是可空字段，
     * 新解析路径（VideoInfoApi.getInfoByJson）已经保证补齐，但**缓存里来自老版本的
     * Parcelable 对象**、以及合作稿缺 staff 等情况仍可能是 null 或空列表；
     * 而本页面后续渲染与点赞/投币回调里有大量 `stats!!` / `staff!!.get(0)` 用法
     * （其中一部分跑在 runOnUiThread 的独立消息里，外层 try/catch 抓不到 → 必崩）。
     * 在这里一次性补成合法空值，比逐处改写更小、更不容易漏。
     */
    private fun normalizeVideoInfo(videoInfo: VideoInfo) {
        if (videoInfo.stats == null) videoInfo.stats = Stats()
        if (videoInfo.staff == null) videoInfo.staff = ArrayList()
        if (videoInfo.cids == null) videoInfo.cids = ArrayList()
        if (videoInfo.pagenames == null) videoInfo.pagenames = ArrayList()
        if (videoInfo.cover == null) videoInfo.cover = ""
    }

    @SuppressLint("SetTextI18n")
    private fun initView(rootview: View) {
        val videoInfo = this.videoInfo ?: return
        if (false) { // 横屏模式已删除（#2）
            val windowManager = rootview.getContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display: Display = windowManager.getDefaultDisplay()
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val paddings = metrics.widthPixels / 6
            rootview.setPadding(paddings, 0, paddings, 0)
        }

        val cover = rootview.findViewById(R.id.img_cover) as ImageView
        val title = rootview.findViewById(R.id.text_title) as TextView
        description = rootview.findViewById(R.id.description) as TextView
        tagsText = rootview.findViewById(R.id.tags) as TextView
        val exclusiveTip = rootview.findViewById(R.id.exclusiveTip) as MaterialCardView
        val up_recyclerView = rootview.findViewById(R.id.up_recyclerView) as RecyclerView
        val exclusiveTipLabel = rootview.findViewById(R.id.exclusiveTipLabel) as TextView
        val viewCount = rootview.findViewById(R.id.viewCount) as TextView
        val timeText = rootview.findViewById(R.id.timeText) as TextView
        val durationText = rootview.findViewById(R.id.durationText) as TextView
        val play = rootview.findViewById(R.id.play) as MaterialButton
        val addWatchlater = rootview.findViewById(R.id.addWatchlater) as MaterialButton
        val download = rootview.findViewById(R.id.download) as MaterialButton
        val relay = rootview.findViewById(R.id.relay) as MaterialButton
        val bvidText = rootview.findViewById(R.id.bvidText) as TextView
        val danmakuCount = rootview.findViewById(R.id.danmakuCount) as TextView
        like = rootview.findViewById(R.id.btn_like) as ImageView
        coin = rootview.findViewById(R.id.btn_coin) as ImageView
        fav = rootview.findViewById(R.id.btn_fav) as ImageView
        val likeLabel = rootview.findViewById(R.id.like_label) as TextView
        val coinLabel = rootview.findViewById(R.id.coin_label) as TextView
        val favLabel = rootview.findViewById(R.id.fav_label) as TextView
        val collectionCard = rootview.findViewById(R.id.collection) as MaterialCardView

        rootview.setVisibility(View.GONE)


        if (videoInfo.epid != -1L) { //不是空的的话就应该跳转到番剧页面了
            val context: Context = rootview.getContext()
            CenterThreadPool.run(Runnable {
                TerminalContext.getInstance()
                    .enterVideoDetailPage(context, BangumiApi.getMdidFromEpid(videoInfo.epid), null, "media")
                val activity = getActivity()
                if (activity == null) return@Runnable
                activity.finish()
            })
            return
        }

        //显示封面
        Glide.with(getAppContext()).asDrawable().load(GlideUtil.url(videoInfo.cover!!)).placeholder(R.mipmap.placeholder)
            .transition(GlideUtil.getTransitionOptions())
            .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(4f))).sizeMultiplier(0.85f).skipMemoryCache(true).dontAnimate())
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .into(cover)

        if (SharedPreferencesUtil.getBoolean("tags_enable", true)) {
            CenterThreadPool.run(Runnable {
                try {
                    val tags_spannable = getDescSpan(VideoInfoApi.getTags(videoInfo.aid))

                    runOnUiThread(Runnable {
                        tagsText!!.setMovementMethod(LinkMovementMethod.getInstance())
                        tagsText!!.setText(tags_spannable.toString())
                        tagsText!!.setOnClickListener { view1 ->
                            tags_expand = !tags_expand
                            if (tags_expand) {
                                tagsText!!.setMaxLines(233)
                                tagsText!!.setText(tags_spannable)
                            } else {
                                tagsText!!.setMaxLines(1)
                                tagsText!!.setText(tags_spannable.toString())
                            }
                        }
                    })
                } catch (e: Exception) {
                    MsgUtil.err(e)
                }
            })
        } else tagsText!!.setVisibility(View.GONE)

        //点赞投币收藏
        if (videoInfo.stats!!.coined != 0) coin!!.setImageResource(R.drawable.icon_coin_1)
        if (videoInfo.stats!!.liked) like!!.setImageResource(R.drawable.icon_like_1)
        if (videoInfo.stats!!.favoured) fav!!.setImageResource(R.drawable.icon_fav_1)

        //历史上报
        CenterThreadPool.run(Runnable {
            try {
                playerData = videoInfo.toPlayerData(0)
                PlayerApi.getVideo(playerData!!, false)
                val pd = playerData
                if (pd == null) return@Runnable
                HistoryApi.reportHistory(videoInfo.aid, pd.cidHistory, (pd.progress / 1000).toLong())
                // 实验室功能（#5）：WiFi 下进入详情页即预加载视频流前几 MB
                WifiPreloadManager.preload(videoInfo.aid, pd.cid, pd.videoUrl)
            } catch (e: Exception) {
                MsgUtil.err(e)
            }
            onFinishLoad()
        })

        //封面
        cover.requestFocus()
        cover.setOnClickListener { view1 ->
            if (SharedPreferencesUtil.getBoolean("cover_play_enable", true)) playClick()
            else showCover()
        }
        cover.setOnLongClickListener {
            showCover()
            true
        }

        //标题
        title.setText(getTitleSpan())
        StringUtil.setCopy(title, videoInfo.title)

        //争议信息
        if (videoInfo.argueMsg != null && !videoInfo.argueMsg!!.isEmpty()) {
            exclusiveTipLabel.setText(videoInfo.argueMsg)
            exclusiveTip.setVisibility(View.VISIBLE)
        }

        //UP主列表
        val adapter = UpListAdapter(requireContext(), videoInfo.staff)
        up_recyclerView.setHasFixedSize(true)
        up_recyclerView.setLayoutManager(CustomLinearManager(requireContext()))
        up_recyclerView.setAdapter(adapter)

        viewCount.setText(StringUtil.toWan(videoInfo.stats!!.view.toLong()))
        likeLabel.setText(StringUtil.toWan(videoInfo.stats!!.like.toLong()))
        coinLabel.setText(StringUtil.toWan(videoInfo.stats!!.coin.toLong()))
        favLabel.setText(StringUtil.toWan(videoInfo.stats!!.favorite.toLong()))

        danmakuCount.setText(videoInfo.stats!!.danmaku.toString())
        bvidText.setText(videoInfo.bvid)
        timeText.setText(videoInfo.timeDesc)
        durationText.setText(videoInfo.duration)

        description!!.setText(videoInfo.description)
        description!!.setOnClickListener { view1 ->
            if (desc_expand) description!!.setMaxLines(3)
            else description!!.setMaxLines(512)
            desc_expand = !desc_expand
        }
        StringUtil.setLink(description!!)
        StringUtil.setAtLink(videoInfo.descAts, description!!)
        StringUtil.setCopy(description!!)

        bvidText.setOnLongClickListener {
            val context = getContext()
            if (context == null) {
                return@setOnLongClickListener true
            }
            StringUtil.copyText(context, videoInfo.bvid ?: "")
            MsgUtil.showMsg("BV号已复制")
            true
        }

        //播放
        play.setOnClickListener { view1 -> playClick() }
        //长按播放按钮进入内置播放器设置（原“选择播放器”界面已删除）
        play.setOnLongClickListener {
            startActivity(Intent(requireContext(), SettingTerminalPlayerActivity::class.java))
            true
        }

        //点赞
        rootview.findViewById<View>(R.id.layout_like).setOnClickListener { view1 ->
            CenterThreadPool.run(Runnable {
                // 门槛型（写操作）：任一通道有账号即可点赞
                if (!AccountManager.isLoggedIn()) {
                    MsgUtil.showMsg(AccountManager.loginHint())
                    return@Runnable
                }
                try {
                    val result = LikeCoinFavApi.like(videoInfo.aid, if (videoInfo.stats!!.liked) 2 else 1)
                    if (result == 0) {
                        videoInfo.stats!!.liked = !videoInfo.stats!!.liked
                        runOnUiThread(Runnable {
                            MsgUtil.showMsg(if (videoInfo.stats!!.liked) "点赞成功" else "取消成功")

                            if (videoInfo.stats!!.liked) likeLabel.setText(StringUtil.toWan((videoInfo.stats!!.like + 1).toLong()))
                            else likeLabel.setText(StringUtil.toWan((videoInfo.stats!!.like - 1).toLong()))
                            like!!.setImageResource(if (videoInfo.stats!!.liked) R.drawable.icon_like_1 else R.drawable.icon_like_0)
                        })
                    } else if (isAdded()) {
                        var msg = "操作失败：$result"
                        when (result) {
                            -403 -> msg = "当前请求触发B站风控"
                            65006 -> {
                                msg = "已经点赞过了喵~"
                                videoInfo.stats!!.liked = true
                                runOnUiThread(Runnable { like!!.setImageResource(R.drawable.icon_like_1) })
                            }
                        }
                        val finalMsg = msg
                        MsgUtil.showMsg(finalMsg)
                    }
                } catch (e: Exception) {
                    MsgUtil.err(e)
                }
            })
        }

        //投币
        rootview.findViewById<View>(R.id.layout_coin).setOnClickListener { view1 ->
            CenterThreadPool.run(Runnable {
                // 门槛型（写操作）：任一通道有账号即可投币
                if (!AccountManager.isLoggedIn()) {
                    MsgUtil.showMsg(AccountManager.loginHint())
                    return@Runnable
                }
                if (videoInfo.stats!!.coined < videoInfo.stats!!.coin_limit) {
                    try {
                        val result = LikeCoinFavApi.coin(videoInfo.aid, 1)
                        if (result == 0) {
                            ++coinAdd
                            if (coinAdd <= 2) videoInfo.stats!!.coined++
                            runOnUiThread(Runnable {
                                MsgUtil.showMsg("投币成功")
                                coinLabel.setText(StringUtil.toWan((videoInfo.stats!!.coin + 1).toLong()))
                                coin!!.setImageResource(R.drawable.icon_coin_1)
                            })
                        } else if (isAdded()) {
                            var msg = "投币失败：$result"
                            if (result == -403) {
                                msg = "当前请求触发B站风控"
                            } else if (result == 34002) {
                                msg = "不能给自己投币哦"
                            }
                            val finalMsg = msg
                            MsgUtil.showMsg(finalMsg)
                        }
                    } catch (e: Exception) {
                        MsgUtil.err(e)
                    }
                } else {
                    MsgUtil.showMsg("投币数量到达上限")
                }
            })
        }

        //收藏
        rootview.findViewById<View>(R.id.layout_fav).setOnClickListener { view1 ->
            val intent = Intent()
            intent.setClass(requireContext(), AddFavoriteActivity::class.java)
            intent.putExtra("aid", videoInfo.aid)
            intent.putExtra("bvid", videoInfo.bvid)
            favLauncher.launch(intent)
        }
        //长按收藏打开收藏页面（#13，与长按稍后再看打开稍后再看页一致）
        // v5.3.4：改为走 handleComboLongPress —— 「同时长按收藏 + 稍后再看」打开历史记录。
        // 单独长按仍然打开收藏夹列表，只是延后 COMBO_WINDOW_MS 执行（理由见 handleComboLongPress）。
        rootview.findViewById<View>(R.id.layout_fav).setOnLongClickListener { view1 ->
            handleComboLongPress(SOURCE_FAV) {
                startActivity(Intent(requireContext(), FavoriteFolderListActivity::class.java))
            }
            true
        }

        //稍后再看
        addWatchlater.setOnClickListener { view1 ->
            CenterThreadPool.run(Runnable {
                try {
                    val result = WatchLaterApi.add(videoInfo.aid)
                    if (result == 0) MsgUtil.showMsg("添加成功")
                    else MsgUtil.showMsg("添加失败，错误码：$result")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            })
        }
        addWatchlater.setOnLongClickListener { view1 ->
            handleComboLongPress(SOURCE_WATCH_LATER) {
                startActivity(Intent(requireContext(), WatchLaterActivity::class.java))
            }
            true
        }

        //下载
        download.setOnClickListener { view1 -> downloadClick() }
        download.setOnLongClickListener {
            CenterThreadPool.run(Runnable {
                val downPath = FileUtil.getVideoDownloadPath(videoInfo.title, null)
                FileUtil.deleteFolder(downPath)
                MsgUtil.showMsg("已清除此视频的缓存文件夹")
            })
            true
        }

        //转发
        relay.setOnClickListener { view1 ->
            val intent = Intent()
            intent.setClass(requireContext(), SendDynamicActivity::class.java)
            writeDynamicLauncher.launch(intent)
        }
        relay.setOnLongClickListener {
            StringUtil.copyText(requireContext(), "https://www.bilibili.com/" + (videoInfo.bvid ?: ""))
            MsgUtil.showMsg("视频完整链接已复制")
            true
        }

        //显隐型：只有「真的一个账号都没有」时才藏按钮；退出 web 登录但端口还登录着时保持可见
        if (!AccountManager.isLoggedIn()) {
            addWatchlater.setVisibility(View.GONE)
            relay.setVisibility(View.GONE)
        }

        //合集按钮
        if (videoInfo.collection != null) {
            val collectionTitle = rootview.findViewById(R.id.collectionText) as TextView
            collectionTitle.setText(String.format("合集 · %s", videoInfo.collection!!.title))
            collectionCard.setOnClickListener { view1 ->
                startActivity(
                    Intent(requireContext(), CollectionInfoActivity::class.java)
                        .putExtra("fromVideo", videoInfo.aid)
                )
            }
        } else {
            collectionCard.setVisibility(View.GONE)
        }

        // 一键三连
        rootview.findViewById<View>(R.id.layout_like).setOnLongClickListener {
            if (SharedPreferencesUtil.getBoolean("like_one_triple", true) &&
                AccountManager.isLoggedIn()
            ) {

                if (shakeAnimation == null) {
                    shakeAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.shake)
                    shakeAnimation!!.setRepeatCount(Animation.INFINITE)
                }

                like!!.startAnimation(shakeAnimation)
                coin!!.startAnimation(shakeAnimation)
                fav!!.startAnimation(shakeAnimation)

                isTripleInProgress = true
                val likeView = like
                val coinView = coin
                val favView = fav
                tripleActionRunnable = Runnable {
                    likeView!!.clearAnimation()
                    coinView!!.clearAnimation()
                    favView!!.clearAnimation()

                    CenterThreadPool.run(Runnable {
                        try {
                            val code = LikeCoinFavApi.triple(aid)
                            if (code == 0) {
                                coinView.setImageResource(R.drawable.icon_coin_1)
                                likeView.setImageResource(R.drawable.icon_like_1)
                                favView.setImageResource(R.drawable.icon_fav_1)
                                MsgUtil.showMsg("三连成功")
                            } else MsgUtil.showMsg("三连失败，错误码：$code")
                        } catch (e: Exception) {
                            MsgUtil.err("三连失败", e)
                        }
                    })
                }
                like!!.postDelayed(tripleActionRunnable, 2000L)

                true
            } else {
                false
            }
        }

        rootview.findViewById<View>(R.id.layout_like).setOnTouchListener { v, event ->
            if (event.getAction() == MotionEvent.ACTION_UP ||
                event.getAction() == MotionEvent.ACTION_CANCEL
            ) {
                cancelTripleAction()
            }
            false
        }
    }

    private fun cancelTripleAction() {
        if (isTripleInProgress) {
            isTripleInProgress = false
            val tripleActionRunnable = this.tripleActionRunnable
            if (tripleActionRunnable != null) {
                like!!.removeCallbacks(tripleActionRunnable)
            }
            like!!.clearAnimation()
            coin!!.clearAnimation()
            fav!!.clearAnimation()
        }
    }


    private fun getTitleSpan(): SpannableString {
        var string: String? = null

        //优先级要对
        val videoInfo = this.videoInfo ?: return SpannableString("")
        if (videoInfo.upowerExclusive) string = "充电专属"
        else if (videoInfo.isSteinGate) string = "互动视频"
        else if (videoInfo.is360) string = "全景视频"
        else if (videoInfo.isCooperation) string = "联合投稿"

        if (string == null) return SpannableString(videoInfo.title ?: "")

        val titleStr = SpannableString(" " + string + " " + (videoInfo.title ?: ""))
        val badgeBG = RadiusBackgroundSpan(0, getResources().getDimension(R.dimen.card_round).toInt(), Color.WHITE, ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt()))
        titleStr.setSpan(badgeBG, 0, string.length + 2, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        return titleStr
    }

    private fun getDescSpan(tags: String): SpannableStringBuilder {
        val tag_str = SpannableStringBuilder("标签：")
        for (str in tags.split("/")) {
            val old_len = tag_str.length
            tag_str.append(str).append("/")
            tag_str.setSpan(object : ClickableSpan() {
                override fun onClick(arg0: View) {
                    val intent = Intent(requireContext(), SearchActivity::class.java)
                    intent.putExtra("keyword", str)
                    requireContext().startActivity(intent)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.setUnderlineText(false)
                    ds.setColor(Color.parseColor("#03a9f4"))
                }
            }, old_len, tag_str.length - 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        return tag_str
    }

    private fun playClick() {
        val videoInfo = this.videoInfo ?: return
        val playerData = this.playerData ?: return
        if (SharedPreferencesUtil.getBoolean("first_play", true)) {
            SharedPreferencesUtil.putBoolean("first_play", false)

            if (SharedPreferencesUtil.getBoolean("cover_play_enable", true)) MsgUtil.showDialog("播放视频", getString(R.string.tutorial_cover_play_enabled))
            else MsgUtil.showDialog("播放视频", getString(R.string.tutorial_cover_play_disabled))

            return
        }

        Glide.get(getAppContext()).clearMemory()
        //在播放前清除内存缓存，因为手表内存太小了，播放完回来经常把Activity全释放掉
        //...经过测试，还是会释放，但会好很多
        if (videoInfo.pagenames!!.size == 1) PlayerApi.startGettingUrl(playerData)
        else startActivity(Intent(requireContext(), MultiPageActivity::class.java).putExtra("data", playerData))

        playerData.timeStamp = 0
    }

    private fun downloadClick() {
        val videoInfo = this.videoInfo ?: return
        if (!FileUtil.checkStoragePermission()) {
            FileUtil.requestStoragePermission(requireActivity())
        } else {
            val downPath = FileUtil.getVideoDownloadPath(videoInfo.title, null)

            if (downPath.exists() && videoInfo.pagenames!!.size == 1) {
                val file_sign = File(downPath, ".DOWNLOADING")
                MsgUtil.showMsg(if (file_sign.exists()) "已在下载队列\n如有异常，长按可清空文件" else "已下载完成")
            } else {
                if (videoInfo.pagenames!!.size > 1) {
                    val intent = Intent()
                    intent.setClass(requireContext(), MultiPageActivity::class.java)
                        .putExtra("download", 1)
                        .putExtra("data", playerData)
                    startActivity(intent)
                } else {
                    startActivity(
                        Intent(requireContext(), QualityChooserActivity::class.java)
                            .putExtra("page", 0)
                            .putExtra("aid", videoInfo.aid)
                            .putExtra("bvid", videoInfo.bvid)
                    )
                }
            }
        }
    }

    private fun showCover() {
        try {
            val intent = Intent()
            intent.setClass(requireContext(), ImageViewerActivity::class.java)
            val imageList = ArrayList<String>()
            imageList.add(videoInfo!!.cover!!)
            intent.putExtra("imageList", imageList)
            requireContext().startActivity(intent)
        } catch (ignored: Exception) {
        }
    }


    fun onFinishLoad() {
        try {
            val activity = getActivity()
            if (activity is VideoInfoActivity) {
                activity.crossFade(requireView())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        Logu.d("onDestroy")
        // 撤销挂起的长按任务：否则 postDelayed 的回调会在 Fragment 已销毁后触发
        // （组合手势的两个挂起任务都挂在 rootView 上，页面走了必须清掉）
        clearAllPendingLongPress()
        cancelTripleAction()
        super.onDestroy()
    }
}
