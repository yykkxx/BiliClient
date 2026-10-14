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
import com.RobinNotBad.BiliClient.activity.user.WatchLaterActivity
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
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.ToolsUtil
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

    companion object {
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

                initView(rootview)
            }.onFailure { error ->
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initView(rootview: View) {
        val videoInfo = this.videoInfo ?: return
        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            val windowManager = rootview.getContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display: Display = windowManager.getDefaultDisplay()
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics)
            else display.getMetrics(metrics)
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

        //点赞
        rootview.findViewById<View>(R.id.layout_like).setOnClickListener { view1 ->
            CenterThreadPool.run(Runnable {
                if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) {
                    MsgUtil.showMsg("还没有登录喵~")
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
                if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) {
                    MsgUtil.showMsg("还没有登录喵~")
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
            val intent = Intent()
            intent.setClass(requireContext(), WatchLaterActivity::class.java)
            startActivity(intent)
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

        //未登录隐藏按钮
        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) {
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
                SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) != 0L
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
        val badgeBG = RadiusBackgroundSpan(0, getResources().getDimension(R.dimen.card_round).toInt(), Color.WHITE, Color.rgb(207, 75, 95))
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
        cancelTripleAction()
        super.onDestroy()
    }
}
