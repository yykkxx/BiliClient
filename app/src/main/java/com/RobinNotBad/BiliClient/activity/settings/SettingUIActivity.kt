package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.EditText
import android.widget.TextView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil
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

            // 主题色调入口（#10）
            val themeValue = findViewById<TextView>(R.id.theme_setting_value)
            themeValue.text = getString(R.string.desc_theme_color) + "\n当前：" + ThemeUtil.getColorName()
            // 用 readableTextColor 而不是直接 getColor()：自定义色可能是深色，
            // 直接当文字色会在黑底上几乎看不见（这是 v5.3.4 新增自定义色之后才出现的风险）
            themeValue.setTextColor(ThemeUtil.readableTextColor(ThemeUtil.getColor()))
            findViewById<View>(R.id.theme_setting_btn).setOnClickListener { view ->
                startActivity(Intent(this@SettingUIActivity, ThemeChooseActivity::class.java))
            }

            // v5.3.4：主题色生效范围三项开关。默认 true = 与旧版行为完全一致。
            bindThemeScopeSwitch(R.id.theme_apply_card, ThemeUtil.PREF_THEME_APPLY_CARD)
            bindThemeScopeSwitch(R.id.theme_apply_progress, ThemeUtil.PREF_THEME_APPLY_PROGRESS)
            bindThemeScopeSwitch(R.id.theme_apply_text, ThemeUtil.PREF_THEME_APPLY_TEXT)

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

    /**
     * 绑定一个「主题色生效范围」开关。
     *
     * 默认值 `true`（与旧版行为一致），所以老用户升级后不会看到任何观感变化 ——
     * 这是「不要破坏原有的功能」这条要求在主题功能上的具体做法。
     */
    private fun bindThemeScopeSwitch(id: Int, prefKey: String) {
        val switch = findViewById<SwitchMaterial>(id) ?: return
        switch.isChecked = SharedPreferencesUtil.getBoolean(prefKey, true)
        switch.setOnCheckedChangeListener { _, isChecked ->
            SharedPreferencesUtil.putBoolean(prefKey, isChecked)
            // 立即重建本页让新范围生效，用户能马上看到区别
            recreate()
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
