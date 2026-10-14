package com.RobinNotBad.BiliClient.activity.user.info

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View

import androidx.annotation.Nullable
import androidx.fragment.app.Fragment
import androidx.viewpager.widget.ViewPager

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter
import com.RobinNotBad.BiliClient.helper.TutorialHelper

import java.util.ArrayList

//用户信息页面
//2023-08-07

class UserInfoActivity : BaseActivity() {

    var udFragment: UserDynamicFragment? = null

    @SuppressLint("MissingInflatedId", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_viewpager)

        val intent = getIntent()
        val mid = intent.getLongExtra("mid", 114514)

        setPageName("用户信息")

        TutorialHelper.showTutorialList(this, R.array.tutorial_space, 2)

        val viewPager = findViewById<ViewPager>(R.id.viewPager)

        val fragmentList = ArrayList<Fragment>()
        udFragment = UserDynamicFragment.newInstance(mid)
        fragmentList.add(udFragment!!)
        val uvFragment = UserVideoFragment.newInstance(mid)
        fragmentList.add(uvFragment)
        val acFragment = UserArticleFragment.newInstance(mid)
        fragmentList.add(acFragment)
        //UserSeasonFragment ssFragment = UserSeasonFragment.newInstance(mid);
        //fragmentList.add(ssFragment);
        viewPager.offscreenPageLimit = fragmentList.size

        val vpfAdapter = ViewPagerFragmentAdapter(supportFragmentManager, fragmentList)

        viewPager.adapter = vpfAdapter  //没啥好说的，教科书式的ViewPager使用方法

        findViewById<View>(R.id.loading).visibility = View.GONE

        TutorialHelper.showPagerTutorial(this, 3)

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, @Nullable data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DynamicHolder.GO_TO_INFO_REQUEST && resultCode == RESULT_OK) {
            if (data != null) {
                udFragment!!.onDynamicRemove(data.getIntExtra("position", 0) - 1)
            }
        }
    }
}
