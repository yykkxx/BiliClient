package com.RobinNotBad.BiliClient.activity.video.info

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView

import androidx.fragment.app.Fragment
import androidx.viewpager.widget.ViewPager

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.reply.ReplyFragment
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.util.Result
import com.RobinNotBad.BiliClient.util.AnimationUtils
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.bumptech.glide.Glide

import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

import java.util.ArrayList
import java.util.concurrent.TimeUnit

//视频详情页，但这只是个壳，瓤是VideoInfoFragment、VideoReplyFragment、VideoRcmdFragment

class VideoInfoActivity : BaseActivity() {

    private var aid: Long = 0
    private var bvid: String? = null

    private var fragmentList: ArrayList<Fragment>? = null
    @JvmField
    var replyFragment: ReplyFragment? = null
    @JvmField
    var contentFragment: Fragment? = null
    private var seek_reply: Long = 0
    private var loading: ImageView? = null
    private var viewPager: ViewPager? = null

    //private MediaViewPager2Adapter mediaViewPager2Adapter;

    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_viewpager)    //这里async是否反而会减慢速度，仅有一个viewpager的页面加载已经足够快了吧

        Glide.get(BiliTerminal.context).clearMemory()
        val intent = getIntent()
        var type = intent.getStringExtra("type")
        if (type == null) type = "video"
        this.aid = intent.getLongExtra("aid", 114514)
        this.bvid = intent.getStringExtra("bvid")
        this.seek_reply = intent.getLongExtra("seekReply", -1)

        viewPager = findViewById<View>(R.id.viewPager) as ViewPager
        loading = findViewById<View>(R.id.loading) as ImageView
        if (type == "media") initMediaInfoView()
        else initVideoInfoView()
    }


    fun initMediaInfoView() {
        setPageName("番剧详情")

        fragmentList = ArrayList(2)
        contentFragment = BangumiInfoFragment.newInstance(aid)
        fragmentList!!.add(contentFragment!!)
        replyFragment = ReplyFragment.newInstance(aid, 1, seek_reply == -1L, seek_reply)
        fragmentList!!.add(replyFragment!!)

        viewPager!!.offscreenPageLimit = fragmentList!!.size
        val vpfAdapter = ViewPagerFragmentAdapter(supportFragmentManager, fragmentList!!)
        viewPager!!.adapter = vpfAdapter
        if (seek_reply != -1L) viewPager!!.currentItem = 1
        if (SharedPreferencesUtil.getBoolean("first_videoinfo", true)) {
            MsgUtil.showMsgLong("提示：本页面可以左右滑动")
            SharedPreferencesUtil.putBoolean("first_videoinfo", false)
        }
    }

    protected fun initVideoInfoView() {
        TutorialHelper.showTutorialList(this, R.array.tutorial_video, 1)
        TutorialHelper.showPagerTutorial(this, 3)

        setPageName("视频详情")
        val seekReplyLocal = seek_reply
        TerminalContext.getInstance().getVideoInfoByAidOrBvId(aid, bvid).observe(this) { result ->
            // 统一收口：详情页的构建逻辑跑在主线程的 LiveData 回调里，
            // 任何意外抛出（空数据、列表越界、fragment 初始化失败…）都是直接闪退。
            // 这里兜住，给出可读提示后退出页面；堆栈由全局 ErrorCatch 落盘，便于事后定位。
            try {
                applyVideoInfoResult(result, seekReplyLocal)
            } catch (e: Exception) {
                MsgUtil.err("打开视频详情失败", e)
                finish()
            }
        }
    }

    private fun applyVideoInfoResult(result: Result<VideoInfo>, seekReplyLocal: Long) {
        result.onSuccess { videoInfo -> applyVideoInfo(videoInfo, seekReplyLocal) }
                .onFailure { error ->
                    loading?.setImageResource(R.mipmap.loading_2233_error)
                    MsgUtil.showMsg("获取信息失败！\n可能是视频不存在？")
                    CenterThreadPool.runOnUIThreadAfter(5L, TimeUnit.SECONDS, Runnable {
                        MsgUtil.err(error)
                    })
                }
    }

    private fun applyVideoInfo(videoInfo: VideoInfo, seekReplyLocal: Long) {
        aid = videoInfo.aid
        bvid = videoInfo.bvid
        val newFragmentList = ArrayList<Fragment>(3)
        val content = VideoInfoFragment.newInstance(videoInfo.aid, bvid)
        contentFragment = content
        newFragmentList.add(content)
        // stats / staff 都可能为空（未登录、字段缺失、合作稿 staff 缺失等）。
        // 老写法 stats!!.reply、staff!!.get(0).mid 会直接 NPE / IndexOutOfBounds。
        val replyCount = videoInfo.stats?.reply ?: 0
        val upMid = videoInfo.staff?.firstOrNull()?.mid ?: 0L
        val reply = ReplyFragment.newInstance(videoInfo.aid, 1, replyCount, seekReplyLocal, upMid)
        reply.setManager(videoInfo.staff)
        replyFragment = reply
        newFragmentList.add(reply)
        if (SharedPreferencesUtil.getBoolean("related_enable", true)) {
            newFragmentList.add(VideoRcmdFragment.newInstance(videoInfo.aid))
        }
        fragmentList = newFragmentList
        val pager = viewPager ?: return
        pager.offscreenPageLimit = newFragmentList.size
        pager.adapter = ViewPagerFragmentAdapter(supportFragmentManager, newFragmentList)
        if (seekReplyLocal != -1L) pager.currentItem = 1
    }

    fun setCurrentAid(aid: Long) {
        val replyFragment = replyFragment
        if (replyFragment != null) runOnUiThread(Runnable { replyFragment.refresh(aid) })
    }

    fun crossFade(fragmentView: View) {
        AnimationUtils.crossFade(loading, fragmentView)
        fragmentView.post {
            val scrollView = fragmentView.findViewById<View>(R.id.scrollView)
            if (scrollView != null) {
                scrollView.isFocusable = true
                scrollView.isFocusableInTouchMode = true
                scrollView.requestFocus()
            }
        }
    }

    override fun eventBusEnabled(): Boolean {
        return true
    }

    @Subscribe(threadMode = ThreadMode.ASYNC, sticky = true, priority = 1)
    fun onEvent(event: ReplyEvent) {
        replyFragment!!.notifyReplyInserted(event)
    }

    override fun onDestroy() {
        Logu.d("onDestroy")
        Glide.get(BiliTerminal.context).clearMemory()
        //显式释放本页缓存：aid/bvid 在 fetch 成功后已被更新为真实值，
        //leaveVideoDetailPage 会把 aid/bvid 两个候选 key 都幂等清一遍，并同步摘掉栈登记
        TerminalContext.getInstance().leaveVideoDetailPage(aid, bvid)
        super.onDestroy()
    }
}
