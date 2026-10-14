package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.card.MaterialCardView

import java.util.ArrayList

class AboutActivity : BaseActivity() {
    var eggClick_authorWords = 0
    var eggClick_toUncle = 0
    var eggClick_Dev = 0

    @SuppressLint("MissingInflatedId", "SetTextI18n", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_setting_about) { layoutView, resId ->
            Log.e("debug", "进入关于页面")

            try {
                val version_str = SpannableString("版本名\n" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName)
                version_str.setSpan(StyleSpan(Typeface.BOLD), 0, 3, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                (findViewById<View>(R.id.app_version) as TextView).text = version_str

                val code_str = SpannableString("版本号\n" + getPackageManager().getPackageInfo(getPackageName(), 0).versionCode)
                code_str.setSpan(StyleSpan(Typeface.BOLD), 0, 3, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                (findViewById<View>(R.id.app_version_code) as TextView).text = code_str

                val updateLog = ToolsUtil.getUpdateLog(this)
                (findViewById<View>(R.id.updatelog_view) as TextView).text = "\n更新细节：$updateLog"
                StringUtil.setCopy(findViewById(R.id.updatelog_view), updateLog)
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }

            val developerAvaterViews = ArrayList<ImageView>()
            developerAvaterViews.add(findViewById(R.id.robinAvatar))
            developerAvaterViews.add(findViewById(R.id.duduAvatar))
            developerAvaterViews.add(findViewById(R.id.dadaAvatar))
            developerAvaterViews.add(findViewById(R.id.moyeAvatar))
            developerAvaterViews.add(findViewById(R.id.silentAvatar))
            developerAvaterViews.add(findViewById(R.id.huanliAvatar))
            developerAvaterViews.add(findViewById(R.id.jankAvatar))

            val developerAvaters = ArrayList<Int>()
            developerAvaters.add(R.mipmap.avatar_robin)
            developerAvaters.add(R.mipmap.avatar_dudu)
            developerAvaters.add(-1)
            developerAvaters.add(R.mipmap.avatar_moye)
            developerAvaters.add(R.mipmap.avatar_silent)
            developerAvaters.add(R.mipmap.avatar_huanli)
            developerAvaters.add(R.mipmap.avatar_jank)

            val developerCardList = ArrayList<MaterialCardView>()
            developerCardList.add(findViewById(R.id.robin_card))
            developerCardList.add(findViewById(R.id.dudu_card))
            developerCardList.add(findViewById(R.id.dada_card))
            developerCardList.add(findViewById(R.id.moye_card))
            developerCardList.add(findViewById(R.id.silent_card))
            developerCardList.add(findViewById(R.id.huanli_card))
            developerCardList.add(findViewById(R.id.jank_card))

            val developerUidList = ArrayList<Long>()
            developerUidList.add(646521226L)
            developerUidList.add(517053179L)
            developerUidList.add(432128342L)
            developerUidList.add(394675616L)
            developerUidList.add(40140732L)
            developerUidList.add(673815151L)
            developerUidList.add(661403494L)

            for (i in developerAvaterViews.indices) {
                val finalI = i
                if (developerAvaters[i] != -1) try {
                    Glide.with(this).load(developerAvaters[i])
                            .transition(GlideUtil.getTransitionOptions())
                            .placeholder(R.mipmap.akari)
                            .apply(RequestOptions.circleCropTransform())
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(developerAvaterViews[i])
                } catch (ignored: Exception) {
                }


                developerCardList[i].setOnClickListener { view ->
                    val uid = developerUidList[finalI]
                    if (uid == -1L) return@setOnClickListener
                    val intent = Intent()
                            .setClass(this, UserInfoActivity::class.java)
                            .putExtra("mid", uid)
                    startActivity(intent)
                }
            }

            findViewById<View>(R.id.author_words).setOnClickListener { view ->
                eggClick_authorWords++
                if (eggClick_authorWords == 7) {
                    eggClick_authorWords = 0
                    MsgUtil.showText("作者的话", getString(R.string.egg_about_author_words))
                }
            }

            findViewById<View>(R.id.toUncle).setOnClickListener { view ->
                eggClick_toUncle++
                if (eggClick_toUncle == 7) {
                    eggClick_toUncle = 0
                    MsgUtil.showText("给叔叔", getString(R.string.egg_about_to_uncle))
                }
            }

            findViewById<View>(R.id.icon_license_list).setOnClickListener { v ->
                val str = StringBuilder(getString(R.string.desc_icon_license))

                val logItems = getResources().getStringArray(R.array.icon_license)
                for (i in logItems.indices)
                    str.append('\n').append(i + 1).append('.').append(logItems[i])
                MsgUtil.showText("开源图标的信息", str.toString())
            }

            findViewById<View>(R.id.sponsor_list).setOnClickListener { view ->
                val intent = Intent(this, SponsorActivity::class.java)
                startActivity(intent)
            }

            if (!ToolsUtil.isDebugBuild()) findViewById<View>(R.id.debug_tip).visibility = View.GONE
            findViewById<View>(R.id.version_code_card).setOnClickListener { view ->
                if (SharedPreferencesUtil.getBoolean("developer", false)) {
                    MsgUtil.showMsg("已关闭开发者模式！")
                    SharedPreferencesUtil.putBoolean("developer", false)
                } else {
                    eggClick_Dev++
                    if (eggClick_Dev == 7) {
                        SharedPreferencesUtil.putBoolean("developer", true)
                        MsgUtil.showMsg("已启用开发者模式！")
                        eggClick_Dev = 0
                    }
                }
            }

            val scrollView = findViewById<View>(R.id.scrollView)
            scrollView.isFocusable = true
            scrollView.isFocusableInTouchMode = true
            scrollView.requestFocus()
        }

    }
}
