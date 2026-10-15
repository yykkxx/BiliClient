package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity
import com.RobinNotBad.BiliClient.activity.settings.login.SpecialLoginActivity
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.api.UserInfoApi
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

            //登录
            val login = findViewById<MaterialCardView>(R.id.login)
            if (SharedPreferencesUtil.getLong("mid", 0L) == 0L) {
                login_cookie.visibility = View.GONE
                login.visibility = View.VISIBLE
                login.setOnClickListener { view ->
                    val intent = Intent()
                    intent.setClass(this, LoginActivity::class.java)   //去扫码登录页面
                    startActivity(intent)
                }
            }

            //播放器（原"选择播放器"界面已删除，入口改为清晰度设置）
            val playerSetting = findViewById<MaterialCardView>(R.id.playerSetting)
            playerSetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingQualityActivity::class.java)
                startActivity(intent)
            }


            //内置播放器设置
            val clientPlayerSetting = findViewById<MaterialCardView>(R.id.terminalPlayerSetting)
            clientPlayerSetting.setOnClickListener { view ->
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

            //实验性设置
            val laboratorySetting = findViewById<MaterialCardView>(R.id.laboratory)
            //laboratorySetting.setVisibility(View.GONE);
            laboratorySetting.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(this, SettingLaboratoryActivity::class.java)
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

            //清空登录数据（cookie / mid / csrf / refresh_token 等）
            val clearLogin = findViewById<MaterialCardView>(R.id.clear_login)
            clearLogin.setOnClickListener { view ->
                if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L &&
                    SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "").orEmpty().isEmpty()) {
                    MsgUtil.showMsg("当前没有登录数据")
                } else if (clearLoginClick++ > 0) {
                    clearLoginClick = 0
                    CenterThreadPool.run(Runnable {
                        try {
                            UserInfoApi.exitLogin()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    })
                    SharedPreferencesUtil.removeValue(SharedPreferencesUtil.cookies)
                    SharedPreferencesUtil.removeValue(SharedPreferencesUtil.mid)
                    SharedPreferencesUtil.removeValue(SharedPreferencesUtil.csrf)
                    SharedPreferencesUtil.removeValue(SharedPreferencesUtil.refresh_token)
                    SharedPreferencesUtil.removeValue(SharedPreferencesUtil.cookie_refresh)
                    MsgUtil.showMsg("登录数据已清空")
                } else MsgUtil.showMsg("清空后需要重新登录，再点一次确认")
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
