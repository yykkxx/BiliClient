package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.util.Pair
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.dynamic.DynamicActivity
import com.RobinNotBad.BiliClient.activity.live.RecommendLiveActivity
import com.RobinNotBad.BiliClient.activity.message.MessageActivity
import com.RobinNotBad.BiliClient.activity.search.SearchActivity
import com.RobinNotBad.BiliClient.activity.settings.SettingAppRcmdActivity
import com.RobinNotBad.BiliClient.activity.settings.SettingMainActivity
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity
import com.RobinNotBad.BiliClient.activity.user.MySpaceActivity
import com.RobinNotBad.BiliClient.activity.video.PopularActivity
import com.RobinNotBad.BiliClient.activity.video.PreciousActivity
import com.RobinNotBad.BiliClient.activity.video.RankingActivity
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity
import com.RobinNotBad.BiliClient.activity.video.TimelineActivity
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton

//菜单页面
//2023-07-14

class MenuActivity : BaseActivity() {

    private var from: String? = null
    private var dynamicButton: MaterialButton? = null
    private var messageButton: MaterialButton? = null

    private var time: Long = 0

    @SuppressLint("MissingInflatedId", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        time = System.currentTimeMillis()
        Log.e("debug", "MenuActivity onCreate: $time")

        from = intent.getStringExtra("from")
        val currentFrom = from
        if (currentFrom != null) {
            Log.d("debug-menu", currentFrom)
            if (btnNames.containsKey(currentFrom))
                setPageName(btnNames[currentFrom]!!.first)
        }

        findViewById<View>(R.id.top).setOnClickListener { finish() }

        var btnList: MutableList<String>

        val sortConf = SharedPreferencesUtil.getString(SharedPreferencesUtil.MENU_SORT, "")
        Log.e("debug_sort", sortConf ?: "")

        if (!sortConf.isNullOrEmpty()) {
            val splitName = sortConf.split(";")
            if (splitName.size != btnNames.size) {
                btnList = defaultSortList
            } else {
                btnList = ArrayList()
                for (name in splitName) {
                    if (!btnNames.containsKey(name)) {
                        btnList = defaultSortList
                        break
                    } else {
                        btnList.add(name)
                    }
                }
            }
        } else {
            btnList = defaultSortList
        }

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
            btnList.add(0, "login")
            btnList.remove("dynamic")
            btnList.remove("message")
            btnList.remove("myspace")
        }

        if (!SharedPreferencesUtil.getBoolean("menu_story", true)) btnList.remove("story")
        if (!SharedPreferencesUtil.getBoolean("menu_popular", true)) btnList.remove("popular")
        if (!SharedPreferencesUtil.getBoolean("menu_precious", false)) btnList.remove("precious")
        if (!SharedPreferencesUtil.getBoolean("menu_ranking", false)) btnList.remove("ranking")
        if (!SharedPreferencesUtil.getBoolean("menu_live", false)) btnList.remove("live")
        if (!SharedPreferencesUtil.getBoolean("menu_timeline", false)) btnList.remove("timeline")

        btnList.add("exit") //如果你希望用户手动把退出按钮排到第一个（

        val layout = findViewById<LinearLayout>(R.id.menu_layout)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        for (btn in btnList) {
            val materialButton = MaterialButton(this)
            when (btn) {
                "exit" -> materialButton.text = "退出"
                "login" -> materialButton.text = getString(R.string.btn_register_login)
                "dynamic" -> {
                    var btnText = btnNames[btn]!!.first
                    dynamicButton = materialButton
                    val updateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
                    if (updateNum > 0) {
                        btnText = "$btnText ($updateNum)"
                    }
                    materialButton.text = btnText
                }
                "message" -> {
                    var messageBtnText = btnNames[btn]!!.first
                    messageButton = materialButton
                    val messageUpdateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0)
                    if (messageUpdateNum > 0) {
                        messageBtnText = "$messageBtnText ($messageUpdateNum)"
                    }
                    materialButton.text = messageBtnText
                }
                else -> materialButton.text = btnNames[btn]!!.first
            }
            materialButton.setOnClickListener { killAndJump(btn) }
            // 长按「推荐」= 打开推荐 API 设置（移动端推荐逻辑：登录身份 / 开关 / 状态）
            if (btn == "recommend") {
                materialButton.setOnLongClickListener {
                    val intent = Intent()
                    intent.setClass(this@MenuActivity, SettingAppRcmdActivity::class.java)
                    startActivity(intent)
                    true
                }
            }
            layout.addView(materialButton, params)
        }

        Log.e("debug", "MenuActivity onCreate in: " + (System.currentTimeMillis() - time))
    }

    override fun onStart() {
        super.onStart()
        Log.e("debug", "MenuActivity onStart in: " + (System.currentTimeMillis() - time))
    }

    override fun onResume() {
        super.onResume()
        Log.e("debug", "MenuActivity onResume in: " + (System.currentTimeMillis() - time))
        dynamicButton?.let { button ->
            var btnText = btnNames["dynamic"]!!.first
            val updateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
            if (updateNum > 0) {
                btnText = "$btnText ($updateNum)"
            }
            button.text = btnText
        }
        messageButton?.let { button ->
            var messageBtnText = btnNames["message"]!!.first
            val messageUpdateNum = SharedPreferencesUtil.getInt(SharedPreferencesUtil.MESSAGE_UPDATE_NUM, 0)
            if (messageUpdateNum > 0) {
                messageBtnText = "$messageBtnText ($messageUpdateNum)"
            }
            button.text = messageBtnText
        }
    }

    private fun killAndJump(name: String) {
        if (btnNames.containsKey(name) && name != from) {
            val instance = BiliTerminal.getInstanceActivityOnTop()
            if (instance != null && instance.lifecycle.currentState != Lifecycle.State.DESTROYED)
                instance.finish()

            val intent = Intent()
            intent.setClass(this@MenuActivity, btnNames[name]!!.second)
            intent.putExtra("from", name)
            startActivity(intent)
            Glide.get(BiliTerminal.context).clearMemory()
        } else {
            when (name) {
                "exit" -> { //退出按钮
                    val instance = BiliTerminal.getInstanceActivityOnTop()
                    if (instance != null && !instance.isDestroyed) instance.finish()
                    Process.killProcess(Process.myPid())
                }
                "login" -> { //登录按钮
                    val intent = Intent()
                    intent.setClass(this@MenuActivity, LoginActivity::class.java)
                    startActivity(intent)
                }
            }
        }
        finish()
    }

    private val defaultSortList: MutableList<String>
        get() = arrayListOf(
            "recommend",
            "story",
            "popular",
            "precious",
            "ranking",
            "live",
            "timeline",
            "search",
            "dynamic",
            "myspace",
            "message",
            "local",
            "settings"
        )

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) finish()
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /**
         * 在排序设置和Splash中使用到的，
         * 需要使用排序，故用了LinkedHashMap
         * 请不要让它的顺序被打乱（
         */
        @JvmField
        val btnNames: MutableMap<String, Pair<String, Class<out android.app.Activity>>> =
            linkedMapOf<String, Pair<String, Class<out android.app.Activity>>>().apply {
                put("recommend", Pair("推荐", RecommendActivity::class.java))
                put("story", Pair("短视频", com.RobinNotBad.BiliClient.activity.video.story.StoryPlayerActivity::class.java))
                put("popular", Pair("热门", PopularActivity::class.java))
                put("precious", Pair("入站必刷", PreciousActivity::class.java))
                put("ranking", Pair("全站排行榜", RankingActivity::class.java))
                put("live", Pair("直播", RecommendLiveActivity::class.java))
                put("timeline", Pair("时间线", TimelineActivity::class.java))
                put("search", Pair("搜索", SearchActivity::class.java))
                put("dynamic", Pair("动态", DynamicActivity::class.java))
                put("myspace", Pair("我的", MySpaceActivity::class.java))
                put("message", Pair("消息", MessageActivity::class.java))
                put("local", Pair("缓存", LocalListActivity::class.java))
                put("settings", Pair("设置", SettingMainActivity::class.java))
            }
    }
}
