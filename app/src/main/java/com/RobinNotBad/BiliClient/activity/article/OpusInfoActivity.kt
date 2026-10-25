package com.RobinNotBad.BiliClient.activity.article

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.viewpager.widget.ViewPager
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.dynamic.DynamicInfoActivity
import com.RobinNotBad.BiliClient.activity.reply.ReplyFragment
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.model.Opus
import com.RobinNotBad.BiliClient.util.AnimationUtils
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.ArrayList

class OpusInfoActivity : BaseActivity() {
    private var oid: Long = 0

    private var replyFragment: ReplyFragment? = null
    private var seek_reply: Long = 0

    private var loadingView: ImageView? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_viewpager)
        val intent = getIntent()
        oid = intent.getLongExtra("id", 114514)
        seek_reply = getIntent().getLongExtra("seekReply", -1)

        setPageName("文章详情")
        loadingView = findViewById(R.id.loading)

        //TutorialHelper.showTutorialList(this, R.array.tutorial_article, 7);

        val viewPager = findViewById<ViewPager>(R.id.viewPager)


        TerminalContext.getInstance().getOpusById(oid)
                .observe(this) { result ->
                    result.onSuccess { opus ->
                        if (opus.type == Opus.TYPE_DYNAMIC_OLD_STYLE) {
                            val intent1 = Intent(this, DynamicInfoActivity::class.java)
                            intent1.putExtra("id", oid)
                            intent1.putExtra("seekReply", seek_reply)
                            startActivity(intent1)
                            finish()
                            return@onSuccess
                        }

                        val fragmentList = ArrayList<Fragment>()

                        val oiFragment = OpusInfoFragment.newInstance(oid)
                        fragmentList.add(oiFragment)

                        replyFragment = ReplyFragment.newInstance(opus.commentId, opus.commentType, opus.stats!!.reply, seek_reply, opus.upInfo!!.mid)
                        replyFragment!!.setManager(opus.upInfo)
                        fragmentList.add(replyFragment!!)

                        val vpfAdapter = ViewPagerFragmentAdapter(supportFragmentManager, fragmentList)
                        viewPager.adapter = vpfAdapter
                        if (seek_reply != -1L) viewPager.currentItem = 1

                        AnimationUtils.crossFade(loadingView, oiFragment.view)
                        TutorialHelper.showPagerTutorial(this, 2)
                    }.onFailure { error ->
                        loadingView!!.setImageResource(R.mipmap.loading_2233_error)
                        MsgUtil.err(error)
                    }
                }


    }

    override fun eventBusEnabled(): Boolean {
        return true
    }

    @Subscribe(threadMode = ThreadMode.ASYNC, sticky = true, priority = 1)
    fun onEvent(event: ReplyEvent) {
        //replyFragment.notifyReplyInserted(event);
    }

    override fun onDestroy() {
        //本页由专栏/图文/动态三个入口共用，缓存 key 统一是 Opus 码，
        //进入时由 enterArticleDetailPage/enterOpusDetailPage/enterDynamicDetailPage 压栈，
        //这里按栈式语义弹出并释放；空栈只会记日志，不会崩
        TerminalContext.getInstance().leaveDetailPage()
        super.onDestroy()
    }
}
