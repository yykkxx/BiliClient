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
import com.RobinNotBad.BiliClient.api.CookieRefreshApi
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
     * 登录成功后处理「这次到底有没有拿到 refresh_token」（v5.3.4 改写）。
     *
     * # 为什么不能只报「没有」就完事
     * 用户原话是「我重新登录了 web 账号，但是仍然无法自己续期自己」。
     * v5.3.3 的做法是当场弹一句「B 站这次没有下发 refresh_token —— Web 无法自续」，
     * 然后让用户自己去开跨端修复或导入网页版凭据。问题是：
     *
     * 1. 那句话把**服务端的未知**说成了**确定的结论**（我们并没有真扫过码验证过回包）；
     * 2. 它**没有尝试任何补救**就宣布失败 —— 而 `correspond` 页的 `#1-value` 是官方
     *    把 web refresh_token 交给主站的 DOM 通道（notes/web_renew_v533.md §6.2），
     *    这条通道不需要用户做任何事，值得自动试一次；
     * 3. 「请重新登录」这个建议对「新登录页根本不下发」的场景是死循环。
     *
     * # 现在的行为
     * - 取到 token（任意来源）→ 直接说拿到多少位；
     * - 没取到 → 自动跑一次 [CookieRefreshApi.bootstrapMissingToken]（后台线程），
     *   按**实际结果**说话：捡到了就说已恢复自续，没捡到就说清楚「已尝试，仍没有」，
     *   并把下一步收窄成两条可执行的（跨端修复 / 导入网页版凭据）。
     *
     * 只读**长度**，绝不读取/打印 token 内容。
     */
    private fun reportRefreshTokenPresence(payload: LoginApi.WebLoginPayload) {
        if (payload.refreshToken.isNotBlank()) {
            CoroutineScopes.launchUi {
                MsgUtil.showMsgLong(getString(R.string.web_renew_login_has_token, payload.refreshToken.length))
            }
            return
        }
        // 没有 token：先在后台试一次自举，再按结果提示（失败不抛、不影响已完成的登录）。
        CoroutineScopes.launchIo {
            val result = try {
                CookieRefreshApi.bootstrapMissingToken()
            } catch (e: RuntimeException) {
                Logu.w("web-renewal", "登录后自举异常：" + e.javaClass.simpleName)
                CookieRefreshApi.RenewResult.NetworkError(e.javaClass.simpleName)
            }
            val text = when (result) {
                is CookieRefreshApi.RenewResult.Success, is CookieRefreshApi.RenewResult.CrossPortSuccess ->
                    getString(R.string.web_renew_login_bootstrap_ok, WebRenewalState.refreshTokenLength())
                is CookieRefreshApi.RenewResult.NoRefreshToken ->
                    getString(R.string.web_renew_login_bootstrap_none)
                is CookieRefreshApi.RenewResult.TokenExpired ->
                    getString(R.string.web_renew_login_bootstrap_expired)
                is CookieRefreshApi.RenewResult.ServerError ->
                    getString(R.string.web_renew_login_bootstrap_error, result.code)
                else -> getString(R.string.web_renew_login_bootstrap_failed)
            }
            CoroutineScopes.launchUi { MsgUtil.showMsgLong(text) }
        }
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
                            val data = loginJson.getJSONObject("data")
                            // v5.3.4：**先把 data.url 走完，再读 Cookie**。
                            // 新式 crossDomain 形态下 Cookie 只在 follow 它的 Set-Cookie 里，
                            // 旧顺序（先读 Cookie 再 follow）会把本次登录的 Cookie 整个漏掉，
                            // 账号槽写进去的还是上一份。见 LoginApi.followLoginUrl 注释。
                            val cookies = LoginApi.followLoginUrl(data.optString("url", ""))
                            val payload = LoginApi.extractWebLoginPayload(data, cookies)
                            val effectiveCookies = payload.cookies
                            // web 扫码回包只带 Cookie，mid/csrf 从 Cookie 里解析（v5.1.5 起交给 AccountStore
                            // 统一写激活槽位 + 该账号自己的 account_mgmt_web_<mid>_* 槽位，支持同端口多账号）。
                            val mid = NetWorkUtil.getInfoFromCookie("DedeUserID", effectiveCookies).trim().toLongOrNull() ?: 0L
                            val csrf = NetWorkUtil.getInfoFromCookie("bili_jct", effectiveCookies)

                            if (mid > 0L) {
                                AccountStore.loginWeb(mid, effectiveCookies, csrf, payload.refreshToken, null, null)
                            } else {
                                // 拿不到 DedeUserID 说明这次回包既没 Set-Cookie 也没 URL 凭据，
                                // 不写半个账号（宁可不登录，也不制造 mid<=0 的半死状态）。
                                Logu.w("debug-login", "登录回包未取到 DedeUserID，未写入账号槽")
                            }
                            reportRefreshTokenPresence(payload)

                            SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.cookie_refresh, true)

                            Logu.d("debug-login", "Web 登录凭据已保存（不输出内容），token 来源=" + payload.tokenSource)

                            val instance = BiliTerminal.getInstanceActivityOnTop()
                            if (instance != null && !instance.isDestroyed()) instance.finish()

                            NetWorkUtil.refreshHeaders()

                            LoginApi.requestSSOs()

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
