package com.RobinNotBad.BiliClient.activity.base

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.KeyEvent

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.MenuActivity
import com.RobinNotBad.BiliClient.ui.motion.MotionPage

open class InstanceActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        BiliTerminal.setInstance(this)
        super.onCreate(savedInstanceState)
    }

    @JvmField
    var menuClick: Runnable = Runnable {
        val intent = Intent()
        intent.setClass(this, MenuActivity::class.java)
        if (getIntent().hasExtra("from"))
            intent.putExtra("from", getIntent().getStringExtra("from"))
        startActivity(intent)
        // 菜单页有专属的下滑出场动画（既有观感，v5.3.6 之前的唯一一处页面动画）。
        // 必须先告诉动效模块「本页自己管」：overridePendingTransition 是后写生效，
        // 不跳过的话 BaseActivity.onPause 里的 MotionPage.apply 会把这一行顶掉。
        MotionPage.skip(this)
        overridePendingTransition(R.anim.anim_activity_in_down, 0)
    }

    fun setMenuClick() {
        findViewById<View>(R.id.top).setOnClickListener { menuClick.run() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) menuClick.run()
        return super.onKeyDown(keyCode, event)
    }
}
