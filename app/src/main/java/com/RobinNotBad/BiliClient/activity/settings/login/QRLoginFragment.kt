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
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.WebRenewalState
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

        showSelfRenewalStatus()

        if (isAdded()) refreshQrCode()
    }

    /**
     * 在扫码页顶部显示「当前 Web 登录能不能自续」（v5.3.3）。
     *
     * # 为什么放在这一页
     * 用户来这一页的唯一目的就是「重新登录」。他之所以来，通常是因为
     * 「续期又失败了」。把能力状态直接摆在他说要重扫之前，能直接回答
     * 「我到底需不需要重扫」——
     *
     * 而本轮实测的结论恰恰是：**B 站新登录页可能根本不下发 `refresh_token`**
     * （`account.bilibili.com` 前端代码里它出现 0 次，notes/web_renew_v533.md §6）。
     * 如果属于这种情况，用户在这里就会看到「不能自续」+
     * 「重扫也拿不到，请用跨端修复或网页版导出导入」——
     * 而不是像 v5.3.2 那样一遍遍重扫、一遍遍失败。
     *
     * 只在已登录时覆盖提示文案；未登录时保持原来的「正在获取二维码」流程不变。
     */
    private fun showSelfRenewalStatus() {
        val text = when (WebRenewalState.selfRenewalCapability()) {
            WebRenewalState.SelfRenewalCapability.NotLoggedIn -> return
            WebRenewalState.SelfRenewalCapability.Ready ->
                getString(R.string.web_renew_status_ready, WebRenewalState.refreshTokenLength())
            WebRenewalState.SelfRenewalCapability.MissingToken ->
                getString(R.string.web_renew_status_missing)
        }
        CoroutineScopes.launchUi { scanStat?.text = text }
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

    /**
     * 登录成功后立刻告诉用户「这次到底有没有拿到 refresh_token」。
     *
     * # 为什么值得单独弹一条提示
     * 用户本轮的原话是「我重新登录了 web 账号，但是仍然无法自己续期自己」——
     * 在 v5.3.2 里，用户重扫完二维码看到的是「登录成功」，然后过几天才发现续期不能用，
     * 而他**没有任何办法**回忆「上次扫码到底有没有拿到 token」。于是只能一遍遍重扫。
     *
     * 把这一刻的事实当场说出来，用户立刻就知道该不该指望自续：
     * - 有 token → 什么都不用做，之后会自动续；
     * - 没有 token → 马上知道要开「跨端修复」，不用等下一次续期失败才发现。
     *
     * # 实测背景（notes/web_renew_v533.md §6）
     * B 站已把扫码登录页从 `passport.bilibili.com` 换到 `account.bilibili.com` 的新 H5 页，
     * 新登录页前端代码里 `refresh_token` 出现 **0 次**。因此「没有 token」很可能是服务端行为，
     * 而不是本 App 的解析问题 —— 这种情况下再让用户「重新扫码」只会无限循环，必须改口。
     *
     * 只判断**长度**，绝不读取/打印内容。
     */
    private fun reportRefreshTokenPresence(refreshToken: String) {
        val message = if (refreshToken.isBlank()) {
            getString(R.string.web_renew_login_no_token)
        } else {
            getString(R.string.web_renew_login_has_token, refreshToken.length)
        }
        CoroutineScopes.launchUi { MsgUtil.showMsgLong(message) }
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
                            reportRefreshTokenPresence(refreshToken)

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
