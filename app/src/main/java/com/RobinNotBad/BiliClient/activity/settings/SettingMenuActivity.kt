package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingMenuActivity : BaseActivity() {

    private var menu_popular: SwitchMaterial? = null
    private var menu_live: SwitchMaterial? = null
    private var menu_precious: SwitchMaterial? = null
    private var menu_ranking: SwitchMaterial? = null
    private var menu_timeline: SwitchMaterial? = null

    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_setting_menu) { layoutView, resId ->

            menu_popular = findViewById(R.id.menu_popular)
            menu_popular!!.isChecked = SharedPreferencesUtil.getBoolean("menu_popular", true)

            menu_live = findViewById(R.id.menu_live)
            menu_live!!.isChecked = SharedPreferencesUtil.getBoolean("menu_live", false)

            menu_precious = findViewById(R.id.menu_precious)
            menu_precious!!.isChecked = SharedPreferencesUtil.getBoolean("menu_precious", false)

            menu_ranking = findViewById(R.id.menu_ranking)
            menu_ranking!!.isChecked = SharedPreferencesUtil.getBoolean("menu_ranking", false)

            menu_timeline = findViewById(R.id.menu_timeline)
            menu_timeline!!.isChecked = SharedPreferencesUtil.getBoolean("menu_timeline", false)

            val sort_btn = findViewById<MaterialButton>(R.id.sort)
            sort_btn.setOnClickListener { view ->
                val intent = Intent(this@SettingMenuActivity, SortSettingActivity::class.java)
                startActivity(intent)
            }
        }
    }

    private fun save() {
        SharedPreferencesUtil.putBoolean("menu_popular", menu_popular!!.isChecked)
        SharedPreferencesUtil.putBoolean("menu_precious", menu_precious!!.isChecked)
        SharedPreferencesUtil.putBoolean("menu_ranking", menu_ranking!!.isChecked)
        SharedPreferencesUtil.putBoolean("menu_live", menu_live!!.isChecked)
        SharedPreferencesUtil.putBoolean("menu_timeline", menu_timeline!!.isChecked)
    }

    override fun onDestroy() {
        save()
        super.onDestroy()
    }
}
