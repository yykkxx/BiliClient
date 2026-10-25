package com.RobinNotBad.BiliClient.activity.dynamic

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import androidx.fragment.app.Fragment
import androidx.viewpager.widget.ViewPager

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.reply.ReplyFragment
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.event.ReplyEvent
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.util.AnimationUtils
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.TerminalContext

import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

import java.util.ArrayList

//动态信息页面
//2023-10-03

class DynamicInfoActivity : BaseActivity() {

    var rFragment: ReplyFragment? = null
    private var seek_reply: Long = 0

    //本页要展示的动态 id，onDestroy 时按它精确释放 TerminalContext 里的动态缓存
    private var dynamicId: Long = 0

    @SuppressLint("MissingInflatedId", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_viewpager)
        seek_reply = getIntent().getLongExtra("seekReply", -1)

        val intent = getIntent()
        val id = intent.getLongExtra("id", 0)
        dynamicId = id

        val pageName = findViewById<TextView>(R.id.pageName)
        pageName.text = "动态详情"

        TutorialHelper.showTutorialList(this, R.array.tutorial_dynamic_info, 6)
        TerminalContext.getInstance().getDynamicById(id)
                .observe(this, { dynamicResult -> dynamicResult.onSuccess { dynamic ->
                    val fragmentList = ArrayList<Fragment>()
                    val diFragment = DynamicInfoFragment.newInstance(id)
                    fragmentList.add(diFragment)
                    rFragment = ReplyFragment.newInstance(dynamic.comment_id, dynamic.comment_type, dynamic.stats!!.reply, seek_reply, dynamic.userInfo!!.mid)
                    rFragment!!.setManager(dynamic.userInfo)
                    rFragment!!.replyType = ReplyApi.REPLY_TYPE_DYNAMIC
                    fragmentList.add(rFragment!!)
                    val vpfAdapter = ViewPagerFragmentAdapter(supportFragmentManager, fragmentList)
                    val viewPager = findViewById<ViewPager>(R.id.viewPager)
                    viewPager.adapter = vpfAdapter  //没啥好说的，教科书式的ViewPager使用方法
                    val view: View? = diFragment.view
                    view?.visibility = View.GONE
                    if (seek_reply != -1L) viewPager.currentItem = 1

                    AnimationUtils.crossFade(findViewById(R.id.loading), diFragment.view)
                    diFragment.view!!.post {
                        val scrollView = diFragment.view!!.findViewById<View>(R.id.scrollView)
                        scrollView.isFocusable = true
                        scrollView.isFocusableInTouchMode = true
                        scrollView.requestFocus()
                    }
                    TutorialHelper.showPagerTutorial(this, 2)
                }.onFailure { e ->
                    MsgUtil.err(e)
                    (findViewById<View>(R.id.loading) as ImageView).setImageResource(R.mipmap.loading_2233_error)
                } })

    }

    override fun eventBusEnabled(): Boolean {
        return true
    }

    @Subscribe(threadMode = ThreadMode.ASYNC, sticky = true, priority = 1)
    fun onEvent(event: ReplyEvent) {
        rFragment!!.notifyReplyInserted(event)
    }

    override fun onDestroy() {
        //精确释放本页缓存（Dynamic_<id>）：老式动态页由 OpusInfoActivity 重定向进入，
        //没有对应的 enterXxx 压栈登记，重复移除也是幂等的
        TerminalContext.getInstance().leaveDynamicDetailPage(dynamicId)
        super.onDestroy()
    }
}
