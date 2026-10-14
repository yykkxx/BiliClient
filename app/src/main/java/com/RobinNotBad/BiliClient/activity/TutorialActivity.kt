package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.utils.widget.ImageFilterView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.model.Tutorial
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.util.Objects
import java.util.Timer
import java.util.TimerTask

class TutorialActivity : BaseActivity() {
    private var wait_time = 3

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_tutorial) { layoutView, resId ->

            val intent = getIntent()

            val tutorial: com.RobinNotBad.BiliClient.model.Tutorial = java.util.Objects.requireNonNull<com.RobinNotBad.BiliClient.model.Tutorial>(TutorialHelper.loadTutorial(getResources().getXml(intent.getIntExtra("xml_id", R.xml.tutorial_recommend))))

            (findViewById<View>(R.id.text_title) as TextView).text = tutorial.name
            (findViewById<View>(R.id.content) as TextView).text = TutorialHelper.loadText(tutorial.content!!)

            try {
                if (tutorial.imgid != null) {
                    val indentify = getResources().getIdentifier(getPackageName() + ":" + tutorial.imgid, null, null)
                    if (indentify > 0)
                        (findViewById<View>(R.id.image_view) as ImageFilterView).setImageDrawable(getResources().getDrawable(indentify))
                } else findViewById<View>(R.id.image_view).visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val close_btn = findViewById<MaterialButton>(R.id.close_btn)
            close_btn.isEnabled = false
            val timer = Timer()
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        if (wait_time > 0) {
                            close_btn.text = String.format(Locale.getDefault(), "已阅(%ds)", wait_time)
                            close_btn.isEnabled = false
                            wait_time--
                        } else {
                            close_btn.text = "已阅"
                            close_btn.isEnabled = true
                            timer.cancel()
                        }
                    }
                }
            }, 0, 1000)
            close_btn.setOnClickListener {
                SharedPreferencesUtil.putInt("tutorial_ver_" + intent.getStringExtra("tag"), intent.getIntExtra("version", -1))
                finish()
            }

            val scrollView = findViewById<View>(R.id.scrollView)
            scrollView.isFocusable = true
            scrollView.isFocusableInTouchMode = true
            scrollView.requestFocus()
        }
    }

    override fun onBackPressed() {
    }
}
