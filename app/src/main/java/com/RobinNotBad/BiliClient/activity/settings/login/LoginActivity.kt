package com.RobinNotBad.BiliClient.activity.settings.login

import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.fragment.app.Fragment

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerFragmentAdapter
import com.RobinNotBad.BiliClient.receiver.SecretCodeReceiver
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList

//登录页面，参考了腕上哔哩和WearBili的代码

class LoginActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_viewpager)
        Log.e("debug", "进入登录页面")
        setPageName("登录")

        val from_setup = getIntent().getBooleanExtra("from_setup", false)

        /*
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            startActivity(new Intent(this, SpecialLoginActivity.class).putExtra("from_setup",from_setup));
            finish();
        }
         */

        val viewPager = findViewById<androidx.viewpager.widget.ViewPager>(R.id.viewPager)
        val fragmentList = ArrayList<Fragment>()
        fragmentList.add(QRLoginFragment.newInstance(from_setup))

        viewPager.offscreenPageLimit = fragmentList.size
        val vpfAdapter = ViewPagerFragmentAdapter(supportFragmentManager, fragmentList)
        viewPager.adapter = vpfAdapter

        findViewById<View>(R.id.loading).visibility = View.GONE

        // 由拨号盘暗码 *#*#159951#*#* 进入时给出提示，说明这是账号/注册入口
        if (getIntent().getBooleanExtra(SecretCodeReceiver.EXTRA_FROM_SECRET_CODE, false)) {
            setPageName(getString(R.string.pagename_secret_code))
            MsgUtil.showMsgLong(getString(R.string.tip_secret_code_entry))
        }

        if (fragmentList.size > 1 && SharedPreferencesUtil.getBoolean("first_" + LoginActivity::class.java.getSimpleName(), true)) {
            MsgUtil.showMsgLong("提示：本页面可以左右滑动")
            SharedPreferencesUtil.putBoolean("first_" + LoginActivity::class.java.getSimpleName(), false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
