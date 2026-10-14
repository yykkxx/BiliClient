package com.RobinNotBad.BiliClient.activity.settings.setup

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.UIPreviewActivity
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.switchmaterial.SwitchMaterial

class SetupUIActivity : BaseActivity() {

    private var uiScaleInput: EditText? = null
    private var uiPaddingH: EditText? = null
    private var uiPaddingV: EditText? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_ui)

        uiScaleInput = findViewById(R.id.ui_scale_input)
        uiScaleInput!!.setText(SharedPreferencesUtil.getFloat("dpi", 1.0F).toString())

        uiPaddingH = findViewById(R.id.ui_padding_horizontal)
        uiPaddingH!!.setText(SharedPreferencesUtil.getInt("paddingH_percent", 0).toString())
        uiPaddingV = findViewById(R.id.ui_padding_vertical)
        uiPaddingV!!.setText(SharedPreferencesUtil.getInt("paddingV_percent", 0).toString())

        val round = findViewById<SwitchMaterial>(R.id.switch_round)
        round.isChecked = SharedPreferencesUtil.getBoolean("player_ui_round", false)
        round.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                uiPaddingH!!.setText("5")
                uiPaddingV!!.setText("3")
                SharedPreferencesUtil.putBoolean("player_ui_round", true)
                MsgUtil.showMsg("界面边距已更改\n可以手动微调喵")
            } else {
                uiPaddingH!!.setText("0")
                uiPaddingV!!.setText("0")
                SharedPreferencesUtil.putBoolean("player_ui_round", false)
            }
        }

        findViewById<View>(R.id.preview).setOnClickListener { view ->
            save()
            val intent = Intent()
            intent.setClass(this@SetupUIActivity, UIPreviewActivity::class.java)
            startActivity(intent)
        }

        findViewById<View>(R.id.confirm).setOnClickListener { view ->
            save()
            val intent = Intent()
            intent.setClass(this@SetupUIActivity, IntroductionActivity::class.java)
            startActivity(intent)
            finish()
        }

        findViewById<View>(R.id.reset).setOnClickListener { view ->
            SharedPreferencesUtil.putInt("paddingH_percent", 0)
            SharedPreferencesUtil.putInt("paddingV_percent", 0)
            SharedPreferencesUtil.putFloat("dpi", 1.0f)
            SharedPreferencesUtil.putBoolean("player_ui_round", false)
            uiScaleInput!!.setText("1.0")
            uiPaddingH!!.setText("0")
            uiPaddingV!!.setText("0")
            round.isChecked = false
            MsgUtil.showMsg("恢复完成")
        }
    }

    private fun save() {
        if (!uiScaleInput!!.getText().toString().isEmpty()) {
            val dpiTimes = uiScaleInput!!.getText().toString().toFloat()
            if (dpiTimes >= 0.1F && dpiTimes <= 10.0F)
                SharedPreferencesUtil.putFloat("dpi", dpiTimes)
            Logu.i("dpi", uiScaleInput!!.getText().toString())
        }

        if (!uiPaddingH!!.getText().toString().isEmpty()) {
            val paddingH = uiPaddingH!!.getText().toString().toInt()
            if (paddingH <= 30) SharedPreferencesUtil.putInt("paddingH_percent", paddingH)
            Logu.i("paddingH", uiPaddingH!!.getText().toString())
        }

        if (!uiPaddingV!!.getText().toString().isEmpty()) {
            val paddingV = uiPaddingV!!.getText().toString().toInt()
            if (paddingV <= 30) SharedPreferencesUtil.putInt("paddingV_percent", paddingV)
            Logu.i("paddingV", uiPaddingV!!.getText().toString())
        }
    }
}
