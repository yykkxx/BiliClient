package com.RobinNotBad.BiliClient.activity.video

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.CatchActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity
import com.RobinNotBad.BiliClient.util.CrashStore
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 启动页（#9）。
 *
 * 打开软件先进入这个名为「哔哩终端」的空 Activity，由它再跳到
 * 推荐 / 登录 / 初次设置；渠道包构建脚本只改它的 label（QQ音乐），
 * 桌面上显示的应用名与这里保持一致。
 *
 * 逻辑尽量薄：未完成初次设置 → 设置页；未登录 → 登录页；否则 → 推荐页。
 */
class LauncherActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        setPageName(getString(R.string.app_name))

        val hint = findViewById<TextView>(R.id.launcher_hint)
        val logo = findViewById<View>(R.id.launcher_logo)

        //轻微的浮现动画，避免“卡了一下什么都没有”的观感
        logo.alpha = 0f
        hint.alpha = 0f
        val logoAnim = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f).setDuration(220)
        val hintAnim = ObjectAnimator.ofFloat(hint, "alpha", 0f, 1f).setDuration(220)
        val animator = AnimatorSet()
        animator.playTogether(logoAnim, hintAnim)
        animator.start()

        // 补展示上次崩溃：ErrorCatch 会在 startActivity(CatchActivity) 之后立刻 killProcess，
        // 页面经常来不及绘制，用户只看到「闪退一下」。这里在下一次启动时再展示一次，
        // 用户随手截图就能把完整堆栈反馈出来。（展示过就标记，不会每次都弹）
        showLastCrashIfAny()

        // 用 postDelayed 保证动画至少可见一帧，路由到真正的页面
        logo.postDelayed({ route() }, 260)
    }

    private fun showLastCrashIfAny() {
        val stack = CrashStore.takeUnacknowledged(this) ?: return
        try {
            val intent = Intent(this, CatchActivity::class.java)
            intent.putExtra("stack", stack)
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("launcher", "show last crash failed", e)
        }
    }

    private fun route() {
        if (!isDestroyed && !isFinishing) {
            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {
                //没完成初次设置，先去设置页
                startActivity(Intent(this, SetupUIActivity::class.java))
            } else if (SharedPreferencesUtil.getLong("mid", 0L) == 0L) {
                //未登录进登录页
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                startActivity(Intent(this, RecommendActivity::class.java))
            }
            finish()
        }
    }
}
