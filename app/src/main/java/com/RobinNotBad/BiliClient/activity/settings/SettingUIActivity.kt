package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.EditText

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingUIActivity : BaseActivity() {

    private var uiScaleInput: EditText? = null
    private var uiPaddingH: EditText? = null
    private var uiPaddingV: EditText? = null
    private var density_input: EditText? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_setting_ui) { layoutView, resId ->

            uiScaleInput = findViewById(R.id.ui_scale_input)
            uiScaleInput!!.setText(SharedPreferencesUtil.getFloat("dpi", 1.0F).toString())

            uiPaddingH = findViewById(R.id.ui_padding_horizontal)
            uiPaddingH!!.setText(SharedPreferencesUtil.getInt("paddingH_percent", 0).toString())
            uiPaddingV = findViewById(R.id.ui_padding_vertical)
            uiPaddingV!!.setText(SharedPreferencesUtil.getInt("paddingV_percent", 0).toString())

            density_input = findViewById(R.id.density_input)
            val density = SharedPreferencesUtil.getInt("density", -1)
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            density_input!!.setText(if (density == -1) displayMetrics.densityDpi.toString() + "(默认)" else density.toString())

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
                intent.setClass(this@SettingUIActivity, UIPreviewActivity::class.java)
                startActivity(intent)
            }
            findViewById<View>(R.id.reset).setOnClickListener { view ->
                SharedPreferencesUtil.putInt("paddingH_percent", 0)
                SharedPreferencesUtil.putInt("paddingV_percent", 0)
                SharedPreferencesUtil.putFloat("dpi", 1.0f)
                SharedPreferencesUtil.putInt("density", -1)
                SharedPreferencesUtil.putBoolean("player_ui_round", false)
                uiScaleInput!!.setText("1.0")
                uiPaddingH!!.setText("0")
                uiPaddingV!!.setText("0")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                density_input!!.setText(displayMetrics.densityDpi.toString() + "(默认)")
                round.isChecked = false
                MsgUtil.showMsg("恢复完成")
            }

            val scrollView = findViewById<View>(R.id.scrollView)
            scrollView.isFocusable = true
            scrollView.isFocusableInTouchMode = true
            scrollView.requestFocus()
        }
    }

    private fun save() {
        if (!uiScaleInput!!.getText().toString().isEmpty()) {
            val dpiScale = uiScaleInput!!.getText().toString().toFloat()
            if (dpiScale >= 0.25F && dpiScale <= 5.0F) {
                SharedPreferencesUtil.putFloat("dpi", dpiScale)
                BiliTerminal.DPI_FORCE_CHANGE = true
            }
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

        if (!density_input!!.getText().toString().isEmpty()) {
            try {
                val density = density_input!!.getText().toString().toInt()
                if (density >= 72) SharedPreferencesUtil.putInt("density", density)
            } catch (ignored: Throwable) {
            }
        }

    }

    override fun onDestroy() {
        if (uiScaleInput != null) save()
        super.onDestroy()
    }
}
