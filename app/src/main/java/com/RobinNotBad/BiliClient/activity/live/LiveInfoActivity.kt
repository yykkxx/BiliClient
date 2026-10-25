package com.RobinNotBad.BiliClient.activity.live

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.Nullable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.user.UpListAdapter
import com.RobinNotBad.BiliClient.adapter.video.MediaEpisodeAdapter
import com.RobinNotBad.BiliClient.api.LiveApi
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.model.Bangumi
import com.RobinNotBad.BiliClient.model.LivePlayInfo
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.AnimationUtils
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
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

import java.util.ArrayList
import java.util.concurrent.TimeUnit

class LiveInfoActivity : BaseActivity() {
    private var room_id: Long = 0
    private var room: LiveRoom? = null
    private var desc_expand = false
    private var tags_expand = false

    private var host_list: RecyclerView? = null
    private var selectedHost = 0
    private var hostAdapter: MediaEpisodeAdapter? = null
    private var playInfo: LivePlayInfo? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        room_id = getIntent().getLongExtra("room_id", 0)
        if (room_id == 0L) {
            finish()
            return
        }

        asyncInflate(R.layout.activity_live_info) { layoutView, id ->
            val loading = findViewById<ImageView>(R.id.loading)
            val scrollView = findViewById<View>(R.id.scrollView)
            val cover = findViewById<ImageView>(R.id.img_cover)
            val title = findViewById<TextView>(R.id.text_title)
            val up_recyclerView = findViewById<RecyclerView>(R.id.up_recyclerView)
            val viewsCount = findViewById<TextView>(R.id.viewCount)
            val play = findViewById<MaterialButton>(R.id.play)
            val durationText = findViewById<TextView>(R.id.durationText)
            val idText = findViewById<TextView>(R.id.idText)
            val tags = findViewById<TextView>(R.id.tags)
            val description = findViewById<TextView>(R.id.description)
            val areaText = findViewById<TextView>(R.id.areaText)
            val attentionText = findViewById<TextView>(R.id.attentionText)
            val liveStatusText = findViewById<TextView>(R.id.liveStatusText)
            host_list = findViewById(R.id.host_list)
            val quality_list = findViewById<RecyclerView>(R.id.quality_list)

            AnimationUtils.crossFade(loading, scrollView)
            TerminalContext.getInstance().getLiveInfoByRoomId(room_id).observe(this, { liveInfoResult ->
                liveInfoResult.onSuccess { liveInfo ->
                val room = liveInfo.liveRoom!!
                val userInfo = liveInfo.userInfo
                val playInfo = liveInfo.livePlayInfo!!
                this.room = room
                this.playInfo = playInfo
                val initialPlayInfo = playInfo

                Glide.with(this).asDrawable().load(GlideUtil.url(room.user_cover ?: "")).placeholder(R.mipmap.placeholder)
                        .transition(GlideUtil.getTransitionOptions())
                        .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(4f))).sizeMultiplier(0.85f).skipMemoryCache(true).dontAnimate())
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(cover)

                cover.setOnClickListener { view -> startActivity(Intent(view.context, ImageViewerActivity::class.java).putExtra("imageList", ArrayList(listOf(room.user_cover)))) }

                title.text = StringUtil.removeHtml(room.title ?: "")

                val upList = ArrayList<UserInfo>()
                if (userInfo != null) {
                    val displayUserInfo = UserInfo(userInfo.mid, userInfo.name, userInfo.avatar, "主播", 0, 0, 6, false, "", 0, "", 0)
//                            displayUserInfo.vip_nickname_color = userInfo.vip_nickname_color;
                    upList.add(displayUserInfo)
                }
                val upListAdapter = UpListAdapter(this, upList)
                up_recyclerView.setHasFixedSize(true)
                up_recyclerView.layoutManager = CustomLinearManager(this)
                up_recyclerView.adapter = upListAdapter

                viewsCount.text = StringUtil.toWan(room.online.toLong()) + "人观看"
                durationText.text = "直播开始于" + room.liveTime

                idText.text = "房间号: $room_id" + (if (room.short_id > 0) " (短号: " + room.short_id + ")" else "")
                tags.text = "标签：" + room.tags

                tags.setOnClickListener { view1 ->
                    if (tags_expand) tags.maxLines = 1
                    else tags.maxLines = 233
                    tags_expand = !tags_expand
                }

                areaText.text = "分区: " + (if (room.area_parent_name != null) room.area_parent_name + " > " else "") + (room.area_name ?: "")
                attentionText.text = "关注数: " + StringUtil.toWan(room.attention.toLong())

                val statusStr: String
                if (room.live_status == 0) {
                    statusStr = "未开播"
                } else if (room.live_status == 1) {
                    statusStr = "直播中"
                } else if (room.live_status == 2) {
                    statusStr = "轮播中"
                } else {
                    statusStr = "未知"
                }
                liveStatusText.text = "直播状态: $statusStr"

                StringUtil.setCopy(idText, tags, title)

                description.text = StringUtil.removeHtml(StringUtil.htmlToString(room.description ?: ""))
                description.setOnClickListener { view1 ->
                    if (desc_expand) description.maxLines = 3
                    else description.maxLines = 512
                    desc_expand = !desc_expand
                }
                play.setOnClickListener { view -> CenterThreadPool.run(Runnable {
                    try {
                        var codec: LivePlayInfo.Codec
                        val curPlayInfo = this.playInfo
                        if (curPlayInfo != null) {
                            codec = curPlayInfo.playUrl!!.stream!![0].format!![0].codec!![0]
                            val urlInfo = codec.url_info!![selectedHost]
                            val play_url = urlInfo.host + codec.base_url + urlInfo.extra

                            val playerData = PlayerData(PlayerData.TYPE_LIVE)
                            playerData.videoUrl = play_url
                            playerData.title = "直播·" + room.title
                            playerData.aid = room_id
                            playerData.mid = SharedPreferencesUtil.getLong("mid", 0L)

                            runOnUiThread(Runnable {
                                try {
                                    startActivity(PlayerApi.buildPlayerIntent(playerData))
                                } catch (e: ActivityNotFoundException) {
                                    MsgUtil.showMsg("没有找到播放器，请检查是否安装")
                                } catch (e: Exception) {
                                    MsgUtil.err(e)
                                }
                            })
                        }
                    } catch (e: Exception) {
                        runOnUiThread(Runnable { MsgUtil.err(e) })
                    }
                })
                if (initialPlayInfo.playUrl == null) {
                    MsgUtil.showMsg("直播已结束")
                    play.visibility = View.GONE
                } else {
                    //清晰度选择
                    val qualityAdapter = MediaEpisodeAdapter()
                    val qualityList = ArrayList<Bangumi.Episode>()
                    qualityAdapter.setOnItemClickListener { index ->
                        hostAdapter!!.setData(ArrayList()) //先留空
                        play.isEnabled = false
                        CenterThreadPool.run(Runnable {
                            try {
                                this.playInfo = LiveApi.getRoomPlayInfo(room_id, qualityList[index].id.toInt())
                                runOnUiThread(Runnable {
                                    refresh_host_list()
                                    play.isEnabled = true
                                })
                            } catch (e: Exception) {
                                runOnUiThread(Runnable { MsgUtil.err(e) })
                            }
                        })
                    }
                    for ((key, value) in LiveApi.QualityMap) {
                        val episode = Bangumi.Episode()
                        episode.id = value.toLong()
                        episode.title = key
                        qualityList.add(episode)
                    }
                    qualityAdapter.setData(qualityList)
                    quality_list.layoutManager = CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false)
                    quality_list.adapter = qualityAdapter
                    qualityAdapter.setSelectedItemIndex(0)

                    //路线选择
                    hostAdapter = MediaEpisodeAdapter()
                    hostAdapter!!.setOnItemClickListener { i -> selectedHost = i }
                    hostAdapter!!.setData(ArrayList())
                    host_list!!.layoutManager = CustomLinearManager(this, LinearLayoutManager.HORIZONTAL, false)
                    runOnUiThread(Runnable { host_list!!.adapter = hostAdapter })
                    refresh_host_list()

                    runOnUiThread(Runnable {
                        scrollView.isFocusable = true
                        scrollView.isFocusableInTouchMode = true
                        scrollView.requestFocus()
                    })
                }
                if (SharedPreferencesUtil.getString("player", "null") != "terminalPlayer")
                    MsgUtil.showMsgLong("直播可能只有内置播放器可以正常播放")
                }
            }.onFailure { e ->
                runOnUiThread(Runnable { MsgUtil.showMsg("直播不存在") })
                CenterThreadPool.runOnUIThreadAfter(1, TimeUnit.MINUTES, Runnable { MsgUtil.err(e) })
                finish()
            }
            })
        }
    }

    private fun refresh_host_list() {
        val hostList = ArrayList<Bangumi.Episode>()
        val urlInfoList = playInfo!!.playUrl!!.stream!![0].format!![0].codec!![0].url_info!!
        for (i in urlInfoList.indices) {
            val episode = Bangumi.Episode()
            episode.id = i.toLong()
            episode.title = "路线" + (i + 1)
            hostList.add(episode)
        }
        hostAdapter!!.setData(hostList)
        selectedHost = 0
        hostAdapter!!.setSelectedItemIndex(0)
    }

    override fun onDestroy() {
        //精确释放本页缓存（Live_<roomId>），并同步摘掉 enterLiveDetailPage 压入的栈登记
        TerminalContext.getInstance().leaveLiveDetailPage(room_id)
        super.onDestroy()
    }
}
