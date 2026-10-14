package com.RobinNotBad.BiliClient.activity.user

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity
import com.RobinNotBad.BiliClient.activity.user.favorite.FavoriteFolderListActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.card.MaterialCardView

class MySpaceActivity : InstanceActivity() {

    private var userAvatar: ImageView? = null
    private var userName: TextView? = null
    private var userFans: TextView? = null
    private var userExp: TextView? = null
    private var myInfo: MaterialCardView? = null
    private var follow: MaterialCardView? = null
    private var watchLater: MaterialCardView? = null
    private var favorite: MaterialCardView? = null
    private var bangumi: MaterialCardView? = null
    private var history: MaterialCardView? = null
    private var creative: MaterialCardView? = null
    private var vip: MaterialCardView? = null
    private var loginRecord: MaterialCardView? = null
    private var coinLog: MaterialCardView? = null
    private var expLog: MaterialCardView? = null
    private var editSign: MaterialCardView? = null
    private var logout: MaterialCardView? = null

    private var confirmLogout = false
    private var currentUserInfo: UserInfo? = null

    @SuppressLint("SetTextI18n", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_myspace) { layoutView, resId ->
            Log.e("debug", "进入个人页")

            userAvatar = findViewById(R.id.userAvatar)
            userName = findViewById(R.id.userName)
            userFans = findViewById(R.id.userFans)
            userExp = findViewById(R.id.userExp)

            myInfo = findViewById(R.id.myinfo)
            follow = findViewById(R.id.follow)
            watchLater = findViewById(R.id.watchlater)
            favorite = findViewById(R.id.favorite)
            bangumi = findViewById(R.id.bangumi)
            history = findViewById(R.id.history)
            creative = findViewById(R.id.creative)
            vip = findViewById(R.id.vip)
            loginRecord = findViewById(R.id.login_record)
            coinLog = findViewById(R.id.coin_log)
            expLog = findViewById(R.id.exp_log)
            editSign = findViewById(R.id.edit_sign)
            logout = findViewById(R.id.logout)


            CenterThreadPool.run(Runnable {
                try {
                    val userInfo = UserInfoApi.getCurrentUserInfo()
                    currentUserInfo = userInfo
                    val userCoin = UserInfoApi.getCurrentUserCoin()
                    if (!isDestroyed) runOnUiThread {
                        Glide.with(this@MySpaceActivity).load(GlideUtil.url(userInfo.avatar ?: ""))
                                .transition(GlideUtil.getTransitionOptions())
                                .placeholder(R.mipmap.akari).apply(RequestOptions.circleCropTransform())
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .into(userAvatar!!)
                        userName!!.text = userInfo.name
                        userFans!!.text = StringUtil.toWan(userInfo.fans.toLong()) + "粉丝 " + userCoin + "硬币"
                        userExp!!.text = "EXP:" + userInfo.current_exp + (if (userInfo.level >= 6) "" else "/" + userInfo.next_exp)

                        myInfo!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, UserInfoActivity::class.java)
                            intent.putExtra("mid", userInfo.mid)
                            startActivity(intent)
                        }

                        follow!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, FollowUsersActivity::class.java)
                            intent.putExtra("mid", userInfo.mid)
                            intent.putExtra("mode", 0)
                            startActivity(intent)
                        }

                        watchLater!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, WatchLaterActivity::class.java)
                            startActivity(intent)
                        }

                        favorite!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, FavoriteFolderListActivity::class.java)
                            startActivity(intent)
                        }

                        bangumi!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, FollowingBangumisActivity::class.java)
                            startActivity(intent)
                        }

                        history!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, HistoryActivity::class.java)
                            startActivity(intent)
                        }

                        creative!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, CreativeCenterActivity::class.java)
                            startActivity(intent)
                        }
                        if (!SharedPreferencesUtil.getBoolean("creative_enable", true))
                            creative!!.visibility = View.GONE

                        vip!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, VipActivity::class.java)
                            startActivity(intent)
                        }

                        loginRecord!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, LoginRecordActivity::class.java)
                            startActivity(intent)
                        }

                        coinLog!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, CoinLogActivity::class.java)
                            startActivity(intent)
                        }

                        expLog!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, ExpLogActivity::class.java)
                            startActivity(intent)
                        }

                        editSign!!.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(this@MySpaceActivity, EditSignActivity::class.java)
                            intent.putExtra("currentSign", userInfo.sign)
                            startActivity(intent)
                        }

                        logout!!.setOnClickListener { view ->
                            if (confirmLogout) {
                                CenterThreadPool.run(Runnable { UserInfoApi.exitLogin() })
                                SharedPreferencesUtil.removeValue(SharedPreferencesUtil.cookies)
                                SharedPreferencesUtil.removeValue(SharedPreferencesUtil.mid)
                                SharedPreferencesUtil.removeValue(SharedPreferencesUtil.csrf)
                                SharedPreferencesUtil.removeValue(SharedPreferencesUtil.refresh_token)
                                SharedPreferencesUtil.removeValue(SharedPreferencesUtil.cookie_refresh)
                                MsgUtil.showMsg("账号已退出")
                                val intent = Intent(this, LoginActivity::class.java)
                                startActivity(intent)
                                finish()
                            } else MsgUtil.showMsg("再点一次退出登录！")
                            confirmLogout = !confirmLogout
                        }

                        val scrollView = findViewById<View>(R.id.scrollView)
                        scrollView.isFocusable = true
                        scrollView.isFocusableInTouchMode = true
                        scrollView.requestFocus()
                    }
                } catch (e: Exception) {
                    report(e)
                }
            })
        }
    }
}
