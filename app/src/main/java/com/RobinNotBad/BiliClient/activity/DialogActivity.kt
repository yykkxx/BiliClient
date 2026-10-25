package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class DialogActivity : BaseActivity() {

    var wait_time = 0

    /**
     * 「知道了」倒计时任务。
     *
     * 协程化示范：原来是 `java.util.Timer` + `runOnUiThread`（每秒回主线程改一次文案，
     * 页面销毁后 Timer 还可能继续跑、继续持有 Activity）。现在改成一个主线程协程：
     * `delay` 不占线程、可取消，页面销毁时在 [onDestroy] 里取消，天然不会泄漏。
     */
    private var countdownJob: Job? = null

    @SuppressLint("MissingInflatedId", "SetTextI18n")
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
            // 每秒倒计时：delay 挂起不占线程，UI 操作天然在主线程（CoroutineScopes.mainScope）
            countdownJob = CoroutineScopes.launchUi {
                while (wait_time > 0) {
                    delay(1000)
                    wait_time--
                    if (wait_time > 0) {
                        close_btn.text = "知道了(${wait_time}s)"
                    } else {
                        close_btn.text = "知道了"
                        close_btn.isEnabled = true
                    }
                }
            }
        } else close_btn.isEnabled = true
        close_btn.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        // 页面销毁即停止倒计时，避免协程继续持有已销毁的 Activity
        countdownJob?.cancel()
        countdownJob = null
        super.onDestroy()
    }

    override fun onBackPressed() {
    }
}
