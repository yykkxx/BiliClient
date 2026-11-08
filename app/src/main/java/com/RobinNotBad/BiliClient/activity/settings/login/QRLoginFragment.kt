package com.RobinNotBad.BiliClient.activity.settings.login

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.constraintlayout.widget.Guideline
import androidx.fragment.app.Fragment

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.api.CookiesApi
import com.RobinNotBad.BiliClient.api.LoginApi
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.Timer
import java.util.TimerTask

import okhttp3.Response

class QRLoginFragment : Fragment() {
    private var qrImageView: ImageView? = null
    private var scanStat: TextView? = null
    var QRImage: Bitmap? = null
    var timer: Timer? = null
    var need_refresh = false
    var from_setup = false
    var qrScale = 0

    companion object {
        @JvmStatic
        fun newInstance(from_setup: Boolean): QRLoginFragment {
            val args = Bundle()
            args.putBoolean("from_setup", from_setup)
            val fragment = QRLoginFragment()
            fragment.setArguments(args)
            return fragment
        }
    }

    override fun onCreate(@Nullable savedInstance: Bundle?) {
        super.onCreate(savedInstance)

        val bundle = getArguments()
        if (bundle != null) from_setup = bundle.getBoolean("from_setup", false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_qr_login, container, false)

        qrImageView = view.findViewById(R.id.qrImage)
        scanStat = view.findViewById(R.id.scanStat)

        return view
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        val jump = view.findViewById<MaterialCardView>(R.id.jump)
        jump.setOnClickListener { v ->
            //「但是我拒绝」：无论从哪个入口进来，都直接进主界面（#2）。
            //旧逻辑只有 from_setup=true 才跳推荐页，否则直接 finish，
            //启动器路径（已完成设置但未登录）点了这个按钮就变成“点了没反应/退出应用”。
            startActivity(Intent(requireContext(), RecommendActivity::class.java))
            if (timer != null) timer!!.cancel()
            if (isAdded()) requireActivity().finish()
        }

        val special = view.findViewById<MaterialCardView>(R.id.special)
        special.setOnClickListener { v ->
            val intent = Intent(requireContext(), SpecialLoginActivity::class.java)
            intent.putExtra("from_setup", from_setup)
            startActivity(intent)
            if (timer != null) timer!!.cancel()
            if (isAdded()) requireActivity().finish()
        }

        qrImageView!!.setOnClickListener { v ->
            if (need_refresh) {
                qrImageView!!.setImageResource(R.mipmap.loading_qr)
                qrImageView!!.isEnabled = false
                refreshQrCode()
            } else {
                val guideline_left = view.findViewById<Guideline>(R.id.guideline33)
                val guideline_right = view.findViewById<Guideline>(R.id.guideline34)
                when (qrScale) {
                    0 -> {
                        guideline_left.setGuidelinePercent(0.00f)
                        guideline_right.setGuidelinePercent(1.00f)
                        MsgUtil.showMsg("切换为大二维码")
                        qrScale = 1
                    }
                    1 -> {
                        guideline_left.setGuidelinePercent(0.30f)
                        guideline_right.setGuidelinePercent(0.70f)
                        MsgUtil.showMsg("切换为小二维码")
                        qrScale = 2
                    }
                    2 -> {
                        guideline_left.setGuidelinePercent(0.15f)
                        guideline_right.setGuidelinePercent(0.85f)
                        MsgUtil.showMsg("切换为默认大小")
                        qrScale = 0
                    }
                }
            }
        }

        // v5.1.5 多账号：进入扫码页先把「当前激活的 web 会话」快照进它自己的账号槽位，
        // 否则再登一个号时，激活槽位会被新号覆盖，上一个 web 账号就直接丢了。
        // 幂等（无激活账号时内部直接返回），不发起网络请求。
        AccountStore.persistActiveWeb()

        if (isAdded()) refreshQrCode()
    }

    @SuppressLint("SetTextI18n")
    fun refreshQrCode() {
        CenterThreadPool.run(Runnable {
            try {
                CenterThreadPool.runOnUiThread(Runnable { scanStat!!.text = "正在获取二维码" })

                CookiesApi.checkCookies()
                QRImage = LoginApi.getLoginQR()
                //CookiesApi.activeCookieInfo();

                CenterThreadPool.runOnUiThread(Runnable {
                    val image = QRImage
                    Log.e("debug-image", image!!.width.toString() + "," + image.height.toString())
                    qrImageView!!.setImageBitmap(image)
                    startLoginDetect()
                })
            } catch (e: IOException) {
                CenterThreadPool.runOnUiThread(Runnable {
                    qrImageView!!.isEnabled = true
                    scanStat!!.text = "获取二维码失败，网络错误"
                })
                e.printStackTrace()
            } catch (e: JSONException) {
                CenterThreadPool.runOnUiThread(Runnable {
                    qrImageView!!.isEnabled = true
                    scanStat!!.text = "登录接口可能失效，请找开发者"
                })
                e.printStackTrace()
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread(Runnable {
                    qrImageView!!.isEnabled = true
                    scanStat!!.text = "遇到其他错误：\n" + (e.message ?: "")
                })
                e.printStackTrace()
            }
        })
    }

    override fun onDestroy() {
        if (timer != null) timer!!.cancel()
        super.onDestroy()
    }

    fun startLoginDetect() {
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            @SuppressLint("SetTextI18n")
            override fun run() {
                try {
                    val response = LoginApi.getLoginState()
                    if (!isAdded()) {
                        this.cancel()
                        return
                    }

                    val str = response.body!!.string()
                    val loginJson = JSONObject(str)
                    // 回包可能包含完整 Web 凭据，禁止写入日志。

                    val code = loginJson.getJSONObject("data").getInt("code")
                    when (code) {
                        86090 -> CenterThreadPool.runOnUiThread { scanStat!!.text = "已扫描，请在手机上点击登录" }
                        86101 -> CenterThreadPool.runOnUiThread { scanStat!!.text = "请使用官方手机端哔哩哔哩扫码登录\n点击二维码可以进行放大和缩小" }
                        86038 -> {
                            CenterThreadPool.runOnUiThread {
                                scanStat!!.text = "二维码已失效，点击上方重新获取"
                                qrImageView!!.isEnabled = true
                            }
                            this.cancel()
                        }
                        0 -> {
                            this.cancel()
                            CenterThreadPool.runOnUiThread { scanStat!!.text = "正在处理登录……" } // quality-allow
                            val cookies = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
                            // web 扫码回包只带 Cookie，mid/csrf 从 Cookie 里解析（v5.1.5 起交给 AccountStore
                            // 统一写激活槽位 + 该账号自己的 account_mgmt_web_<mid>_* 槽位，支持同端口多账号）。
                            val mid = NetWorkUtil.getInfoFromCookie("DedeUserID", cookies).trim().toLongOrNull() ?: 0L
                            val csrf = NetWorkUtil.getInfoFromCookie("bili_jct", cookies)
                            val refreshToken = loginJson.getJSONObject("data").optString("refresh_token", "")

                            AccountStore.loginWeb(mid, cookies, csrf, refreshToken, null, null)

                            SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.cookie_refresh, true)

                            Logu.d("debug-login", "Web 登录凭据已保存（不输出内容）")

                            val instance = BiliTerminal.getInstanceActivityOnTop()
                            if (instance != null && !instance.isDestroyed()) instance.finish()

                            NetWorkUtil.refreshHeaders()

                            LoginApi.requestSSOs()
                            if (loginJson.getJSONObject("data").has("url")) {
                                try {
                                    NetWorkUtil.get(loginJson.getJSONObject("data").optString("url"))
                                } catch (ignored: Throwable) {
                                }
                            }

                            startActivity(Intent(requireContext(), RecommendActivity::class.java))

                            if (isAdded()) requireActivity().finish()
                        }
                        else -> CenterThreadPool.runOnUiThread { scanStat!!.text = "二维码登录API可能变动，\n但你仍然可以尝试扫码登录。\n建议反馈给开发者" }
                    }

                } catch (e: Exception) {
                    if (isAdded()) CenterThreadPool.runOnUiThread {
                        qrImageView!!.isEnabled = true
                        scanStat!!.text = "无法获取二维码信息，点击上方重试\n" + (e.message ?: "")
                        MsgUtil.err(e)
                    }
                    this.cancel()
                }
            }
        }, 500, 1000)
    }
}
