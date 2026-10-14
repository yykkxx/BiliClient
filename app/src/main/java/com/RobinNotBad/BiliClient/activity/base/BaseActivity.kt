package com.RobinNotBad.BiliClient.activity.base

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.dynamic.DynamicActivity
import com.RobinNotBad.BiliClient.event.SnackEvent
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomGridManager
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.AsyncLayoutInflaterX
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

open class BaseActivity : AppCompatActivity {
    @JvmField var window_width: Int = 0
    @JvmField var window_height: Int = 0
    @JvmField var old_context: Context? = null
    @JvmField val relayDynamicLauncher: ActivityResultLauncher<Intent> = DynamicActivity.getRelayDynamicLauncher(this)
    @JvmField var force_single_column: Boolean = false

    constructor() : super()

    //调整应用内dpi的代码，其他Activity要继承于BaseActivity才能调大小
    override fun attachBaseContext(newBase: Context) {
        old_context = newBase
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase))
    }

    //调整页面边距，参考了hankmi的方式
    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        requestedOrientation = if (SharedPreferencesUtil.getBoolean("ui_landscape", false))
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        super.onCreate(savedInstanceState)

        val paddingH_percent = SharedPreferencesUtil.getInt("paddingH_percent", 0)
        val paddingV_percent = SharedPreferencesUtil.getInt("paddingV_percent", 0)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display: Display = windowManager.getDefaultDisplay()
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 17) display.getRealMetrics(metrics)
        else display.getMetrics(metrics)

        val scrW = metrics.widthPixels
        val scrH = metrics.heightPixels
        if (paddingH_percent != 0 || paddingV_percent != 0) {
            Logu.d("debug", "调整边距")
            val paddingH = scrW * paddingH_percent / 100
            val paddingT = scrH * paddingV_percent / 100
            var paddingB = paddingT
            if (SharedPreferencesUtil.getBoolean("player_ui_round", false)) paddingB += (scrH * 0.03).toInt()
            window_width = scrW - paddingH * 2
            window_height = scrH - paddingT - paddingB
            val rootView = this.window!!.decorView.rootView
            rootView.setPadding(paddingH, paddingT, paddingH, paddingB)
        } else {
            window_width = scrW
            window_height = scrH
        }

        // 随便加的
        val density = SharedPreferencesUtil.getInt("density", -1)
        if (density >= 72) {
            setDensity(density)
        }
    }

    override fun onBackPressed() {
        if (!SharedPreferencesUtil.getBoolean("back_disable", false)) super.onBackPressed()
    }

    open fun setPageName(name: String?) {
        val textView = findViewById<TextView>(R.id.pageName)
        if (textView != null) textView.text = name
    }

    open fun setTopbarExit() {
        val view = findViewById<View>(R.id.top) ?: return
        if (Build.VERSION.SDK_INT > 17 && view.hasOnClickListeners()) return
        view.setOnClickListener { view1 ->
            if (Build.VERSION.SDK_INT < 17 || !isDestroyed()) {
                finish()
            }
        }
        Logu.d("debug", "set_exit")
    }

    open fun setRound() {
        //圆屏适配

        val pagename = findViewById<TextView>(R.id.pageName)
        val clock = findViewById<TextView>(R.id.timeText)
        if (pagename != null) {
            pagename.maxLines = 1
            pagename.ellipsize = TextUtils.TruncateAt.END
            if (SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
                try {
                    val params = pagename.layoutParams
                    val paddingH = (window_width * 0.18).toInt()
                    val paddingV = (window_width * 0.03).toInt()
                    pagename.setPadding(paddingH, paddingV, paddingH, 0)
                    if (params is RelativeLayout.LayoutParams) {
                        val clockParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        clockParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
                        clock!!.layoutParams = clockParams
                        clock.alpha = 0.85f
                        clock.setTextSize(12f)

                        val pnParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        pnParams.addRule(RelativeLayout.CENTER_HORIZONTAL)
                        pnParams.topMargin = (window_height * 0.01).toInt() + ToolsUtil.sp2px(12f)
                        pnParams.bottomMargin = (window_height * 0.01).toInt()
                        pagename.layoutParams = pnParams
                        pagename.setPadding(0, 0, ToolsUtil.dp2px(5f), 0)
                        Logu.d("round", "ok")
                    }
                } catch (e: Throwable) {
                    MsgUtil.err("圆屏适配执行错误：", e)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (Build.VERSION.SDK_INT < 17 || !isDestroyed()) {
                finish()
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    open fun report(e: Exception) {
        runOnUiThread { MsgUtil.err(className, e) }
    }

    private var eventBusInit = false

    override fun onStart() {
        super.onStart()
        if (this !is InstanceActivity) setTopbarExit()
        setRound()
        if (eventBusEnabled() && !eventBusInit) {
            EventBus.getDefault().register(this)
            eventBusInit = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (eventBusEnabled()) {
            val snackEvent = EventBus.getDefault().getStickyEvent(SnackEvent::class.java)
            if (snackEvent != null) onEvent(snackEvent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (eventBusInit) {
            EventBus.getDefault().unregister(this)
            eventBusInit = false
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onEvent(event: SnackEvent) {
        if (isDestroyed()) return
        MsgUtil.processSnackEvent(event, window!!.decorView.rootView)
    }

    protected open fun eventBusEnabled(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SNACKBAR_ENABLE, true)
    }

    open fun setDensity(targetDensityDpi: Int) {
        if (Build.VERSION.SDK_INT < 17) return
        val resources = resources

        if (resources.configuration.densityDpi == targetDensityDpi) return

        val configuration = resources.configuration
        configuration.densityDpi = targetDensityDpi
        configuration.fontScale = 1f
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    protected open fun asyncInflate(id: Int, callBack: InflateCallBack) {
        setContentView(R.layout.activity_loading)
        AsyncLayoutInflaterX(this).inflate(id, null) { view, layoutId, parent ->
            setContentView(view)

            if (this is InstanceActivity) (this as InstanceActivity).setMenuClick()
            else setTopbarExit()

            setRound()
            callBack.finishInflate(view, layoutId)
        }
    }

    protected fun interface InflateCallBack {
        fun finishInflate(view: View, id: Int)
    }

    open fun getLayoutManager(): RecyclerView.LayoutManager {
        return if (SharedPreferencesUtil.getBoolean("ui_landscape", false) && !force_single_column)
            CustomGridManager(this, 3)
        else
            CustomLinearManager(this)
    }

    open fun setForceSingleColumn() {
        force_single_column = true
    }

    override fun isDestroyed(): Boolean {
        return lifecycle.currentState == Lifecycle.State.DESTROYED || isFinishing
    }

    open val className: String
        get() = this.javaClass.simpleName
}
