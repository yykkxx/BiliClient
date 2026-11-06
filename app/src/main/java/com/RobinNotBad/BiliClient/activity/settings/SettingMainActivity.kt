package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.settings.login.SpecialLoginActivity
import com.RobinNotBad.BiliClient.time.activity.TimeMainActivity
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

class SettingMainActivity : InstanceActivity() {

    private var eggClick = 0

    private var refreshTutorialClick = 0

    private var clearLoginClick = 0

    @SuppressLint("MissingInflatedId", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_setting_main) { layoutView, id ->
            Log.e("debug", "进入设置页")

            //查看登录信息
            val login_cookie = findViewById<MaterialCardView>(R.id.login_cookie)
            login_cookie.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SpecialLoginActivity::class.java)
                intent.putExtra("login", false)
                startActivity(intent)
            }

            //登录（v5.1.5：入口统一到「账号管理」，这里只在**一个账号都没有**时露出快捷入口）
            //
            // 历史行为：web mid==0 就露出「登录」按钮并直跳 web 扫码页。现在登录方式有四种端口、
            // 且同一端口可多账号，直接跳 web 扫码头会让人误以为只能登 web。所以：
            // - 判断口径改成「任意端口有没有账号」（AccountManager.isLoggedIn），
            //   只登录了 HD/TV/手机端时不再显示这个快捷入口；
            // - 点它进「账号管理」，让用户先选端口再登录，和设置主页的其它入口一致。
            val login = findViewById<MaterialCardView>(R.id.login)
            if (!AccountManager.isLoggedIn()) {
                login.visibility = View.VISIBLE
                login.setOnClickListener { view ->
                    val intent = Intent()
                    intent.setClass(this, SettingAccountActivity::class.java)
                    startActivity(intent)
                }
            }

            //播放器设置（#4 清晰度设置已并入播放器设置页）
            val playerSetting = findViewById<MaterialCardView>(R.id.playerSetting)
            playerSetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingTerminalPlayerActivity::class.java)
                startActivity(intent)
            }

            //界面设置
            val uiSetting = findViewById<MaterialCardView>(R.id.uiSetting)
            uiSetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingUIActivity::class.java)
                startActivity(intent)
            }

            // 菜单设置
            val menuSetting = findViewById<MaterialCardView>(R.id.menuSetting)
            menuSetting.setOnClickListener { view -> startActivity(Intent(this, SettingMenuActivity::class.java)) }

            //偏好设置
            val prefSetting = findViewById<MaterialCardView>(R.id.prefSetting)
            prefSetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingPrefActivity::class.java)
                startActivity(intent)
            }

            //评论区设置
            val repliesSetting = findViewById<MaterialCardView>(R.id.repliesSetting)
            repliesSetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingRepliesActivity::class.java)
                startActivity(intent)
            }

            //评论区设置
            val infoSetting = findViewById<MaterialCardView>(R.id.infoSetting)
            infoSetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingInfoActivity::class.java)
                startActivity(intent)
            }

            //时间管理（v5.2.1 重写）：入口页 [TimeMainActivity]，下分三个小栏
            //（使用控制 / 时间银行 / 使用记录）。用户要求「在设置下面新增一个时间管理的栏目，
            //这个栏目再分成多个小栏」，所以主页只留一个入口卡片，具体分栏在下一层。
            //注意这里不再直连任何具体功能页：入口的职责是「让用户看到三个小栏」，
            //直接把用户丢进某一个分栏会让他找不到另外两个。
            val timeManager = findViewById<MaterialCardView>(R.id.timeManager)
            timeManager.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, TimeMainActivity::class.java)
                startActivity(intent)
            }

            //实验性设置
            val laboratorySetting = findViewById<MaterialCardView>(R.id.laboratory)
            //laboratorySetting.setVisibility(View.GONE);
            laboratorySetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingLaboratoryActivity::class.java)
                startActivity(intent)
            }

            //账号管理（v5.1.5）：推荐页 / 其它页面的 API 端口 + 同端口多账号的登录、切换与退出
            val accountManager = findViewById<MaterialCardView>(R.id.accountManager)
            accountManager.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingAccountActivity::class.java)
                startActivity(intent)
            }

            //关于
            val about = findViewById<MaterialCardView>(R.id.about)
            about.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, AboutActivity::class.java)
                startActivity(intent)
            }

            //彩蛋
            val eggList = getResources().getStringArray(R.array.eggs)
            about.setOnLongClickListener { view ->
                MsgUtil.showText("回声洞", eggList[eggClick])
                if (eggClick < eggList.size - 1) eggClick++
                true
            }

            //检查更新
            val checkUpdate = findViewById<MaterialCardView>(R.id.checkupdate)
            checkUpdate.setOnClickListener { view ->
                MsgUtil.showMsg("正在获取...")
                CenterThreadPool.run(Runnable {
                    try {
                        AppInfoApi.checkUpdate(this, true)
                    } catch (e: Exception) {
                        runOnUiThread(Runnable { MsgUtil.showMsg("连接到哔哩终端接口时发生错误") })
                    }
                })
            }

            //公告列表
            val announcement = findViewById<MaterialCardView>(R.id.announcement)
            announcement.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, AnnouncementsActivity::class.java)
                startActivity(intent)
            }

            //清除教程进度
            val refreshTutorial = findViewById<MaterialCardView>(R.id.refresh_tutorial)
            refreshTutorial.setOnClickListener { view ->
                if (refreshTutorialClick++ > 0) {
                    refreshTutorialClick = 0

                    val tutorialList = getResources().getStringArray(R.array.tutorial_list)
                    for (name in tutorialList) {
                        SharedPreferencesUtil.removeValue("tutorial_ver_$name")
                    }

                    MsgUtil.showMsg("教程进度已清除")
                } else MsgUtil.showMsg("再点一次清除")
            }

            //清空登录数据（v5.2.1：四端统一清空）
            //
            // 【为什么要改】审计报告 P1-8：旧实现只判 web 的两个键（`mid` + `cookies`），
            // 于是「web 已退出、HD 端口还登录着」时用户点这一项会看到「当前没有登录数据」——
            // 明明还有个号登着，却清不掉也看不见。这正是「半残」在设置页的体现。
            // 现在：判定用 `AccountManager.isLoggedIn()`（任一端口有账号即算），
            // 清空用 `AccountStore.logoutAll()`（四端账号池 + 激活槽位一起清），
            // 清完后 web 的 Cookie 头也要立刻刷新（否则内存里还留着旧 Cookie 继续发请求）。
            val clearLogin = findViewById<MaterialCardView>(R.id.clear_login)
            clearLogin.setOnClickListener { view ->
                if (!AccountManager.isLoggedIn()) {
                    MsgUtil.showMsg("当前没有登录数据")
                } else if (clearLoginClick++ > 0) {
                    clearLoginClick = 0
                    CenterThreadPool.run(Runnable {
                        try {
                            UserInfoApi.exitLogin()
                        } catch (e: Exception) {
                            // 退出登录接口失败不影响本地清理：本地清干净才是用户要的结果
                            Log.w("setting", "exitLogin failed: " + e.javaClass.simpleName)
                        }
                    })
                    AccountStore.logoutAll()
                    MsgUtil.showMsg("四端登录数据已清空")
                } else MsgUtil.showMsg("清空后需要重新登录（四端一起清），再点一次确认")
            }

            val test = findViewById<MaterialCardView>(R.id.test)    //用于测试
            test.visibility = if (SharedPreferencesUtil.getBoolean("developer", false)) View.VISIBLE else View.GONE
            test.setOnClickListener { view ->
                val intent = Intent(this, TestActivity::class.java)
                startActivity(intent)
            }

            findViewById<View>(R.id.scrollView).requestFocus()
        }
    }
}
