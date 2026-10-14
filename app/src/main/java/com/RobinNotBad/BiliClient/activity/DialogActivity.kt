package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.google.android.material.button.MaterialButton
import java.util.Timer
import java.util.TimerTask

class DialogActivity : BaseActivity() {

    var wait_time = 0

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialog)

        val close_btn = findViewById<MaterialButton>(R.id.close_btn)

        val intent = getIntent()

        (findViewById<View>(R.id.tip_title) as TextView).text = intent.getStringExtra("title")
        (findViewById<View>(R.id.content) as TextView).text = intent.getStringExtra("content")

        if (intent.getIntExtra("wait_time", -1) > 0) {
            close_btn.isEnabled = false
            wait_time = intent.getIntExtra("wait_time", 0)
            val timer = Timer()
            timer.scheduleAtFixedRate(object : TimerTask() {
                @SuppressLint("SetTextI18n")
                override fun run() {
                    runOnUiThread {
                        if (wait_time-- > 0) {
                            close_btn.text = "知道了(" + wait_time + "s)"
                            close_btn.isEnabled = false
                        } else {
                            close_btn.text = "知道了"
                            close_btn.isEnabled = true
                            timer.cancel()
                        }
                    }
                }
            }, 0, 1000)
        } else close_btn.isEnabled = true
        close_btn.setOnClickListener { finish() }
    }

    override fun onBackPressed() {
    }
}
