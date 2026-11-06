package com.RobinNotBad.BiliClient.activity.settings.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.google.android.material.card.MaterialCardView

import org.json.JSONObject

import java.util.Timer
import java.util.TimerTask

/**
 * APP 端口（Android手机 / HD / TV）的扫码登录页。
 *
 * 与 web 端扫码登录的区别：
 * - 接口为 passport-tv-login（auth_code / poll），返回 access_token 而非 web Cookie；
 * - 登录信息写进 [AccountStore] 的该端口多账号槽位，完全不动软件的 web 登录；
 * - 同一端口可以同时登录多个账号（每次扫码成功都新增/更新一个账号实例）。
 *
 * v5.1.5「账号管理」入口：extra 为 [EXTRA_PORT]（取值 [AccountStore.PORT_ANDROID] /
 * [AccountStore.PORT_HD] / [AccountStore.PORT_TV]，正好等于 AccountStore 的端口常量）。
 * web 端口不走本页（web 扫码走既有的 [LoginActivity]）；不带 extra 时用「页签 2」选的端口
 * （若那是 web，则本页退回 HD —— 本页不做 web 扫码）。
 */
class AppRcmdLoginActivity : BaseActivity() {

    companion object {
        /**
         * 指定登录端口的 extra（取值 web / android / hd / tv，与 [AccountStore] 的端口常量一致）；
         * 不带 = 用「页签 2（其它页面 API）」选的端口。
         */
        const val EXTRA_PORT = "port"
    }

    private var qrImageView: ImageView? = null
    private var scanStat: TextView? = null
    private var qrImage: Bitmap? = null
    private var timer: Timer? = null
    private var needRefresh = false

    /** 本次扫码要登录的端口（[AccountStore] 端口常量） */
    private var loginPort: String = AccountStore.PORT_HD

    /** 本次扫码要登录的身份（由 [loginPort] 换算，web 端口不会出现在本页） */
    private var loginIdentity: AppRcmdApi.RcmdIdentity =
        AccountStore.identityOf(AccountStore.PORT_HD) ?: AppRcmdApi.getIdentityById(AppRcmdApi.IDENTITY_HD)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_rcmd_login)

        // 不带 extra 时用「页签 2」选的端口；若那个端口是 web（本页不处理 web 扫码），退回 HD。
        val resolvedPort = intent?.getStringExtra(EXTRA_PORT)
            ?.let { AccountStore.normalizePort(it) }
            ?: AccountStore.mainPort().ifEmpty { AccountStore.PORT_HD }
        loginPort = if (resolvedPort == AccountStore.PORT_WEB) AccountStore.PORT_HD else resolvedPort
        loginIdentity = AccountStore.identityOf(loginPort)
            ?: AppRcmdApi.getIdentityById(AppRcmdApi.IDENTITY_HD)
        setPageName("登录" + AccountStore.portDisplayName(loginPort) + "端账号")
        setTopbarExit()

        qrImageView = findViewById(R.id.appRcmdQrImage)
        scanStat = findViewById(R.id.appRcmdScanStat)

        findViewById<MaterialCardView>(R.id.appRcmdCancel).setOnClickListener { finish() }

        //点击二维码：失效后重新获取；未失效时放大/缩小
        qrImageView!!.setOnClickListener {
            if (needRefresh) {
                qrImageView!!.setImageResource(R.mipmap.loading_qr)
                needRefresh = false
                refreshQrCode()
            }
        }

        refreshQrCode()
    }

    override fun onDestroy() {
        if (timer != null) timer!!.cancel()
        super.onDestroy()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshQrCode() {
        CenterThreadPool.run(Runnable {
            try {
                CenterThreadPool.runOnUiThread(Runnable { scanStat!!.text = "正在获取二维码" })
                qrImage = AppRcmdApi.getLoginQR(loginIdentity)
                CenterThreadPool.runOnUiThread(Runnable {
                    if (isDestroyed) return@Runnable
                    qrImageView!!.setImageBitmap(qrImage)
                    startLoginDetect()
                })
            } catch (e: Exception) {
                CenterThreadPool.runOnUiThread(Runnable {
                    if (isDestroyed) return@Runnable
                    qrImageView!!.isEnabled = true
                    needRefresh = true
                    scanStat!!.text = "获取二维码失败，点击二维码重试"
                })
                e.printStackTrace()
            }
        })
    }

    @SuppressLint("SetTextI18n")
    private fun startLoginDetect() {
        //先取消旧的轮询，避免刷新二维码后出现两个 Timer 同时轮询
        if (timer != null) {
            timer!!.cancel()
            timer!!.purge()
        }
        timer = Timer()
        timer!!.schedule(object : TimerTask() {
            override fun run() {
                try {
                    val result = AppRcmdApi.getLoginState(loginIdentity)
                    if (isDestroyed) {
                        this.cancel()
                        return
                    }

                    val data = result.optJSONObject("data")
                    val code = result.optInt("code", -1)
                    when {
                        //登录成功
                        code == 0 && data != null -> {
                            this.cancel()
                            CenterThreadPool.runOnUiThread(Runnable {
                                if (isDestroyed) return@Runnable
                                try {
                                    // 凭据写入 AccountStore 的该端口多账号槽位（同端口可多号并存）
                                    val outcome = loginWithAccountStore(data)
                                    scanStat!!.text = "登录成功！"
                                    showSuccessDialog(outcome)
                                } catch (e: Exception) {
                                    //token_info 缺失（风控）等
                                    scanStat!!.text = "登录失败：" + (e.message ?: "未知错误")
                                }
                            })
                        }
                        //已扫码待确认（86061=电视端协议，86090=兼容web端语义）
                        code == 86061 || code == 86090 -> CenterThreadPool.runOnUiThread {
                            if (!isDestroyed) scanStat!!.text = "已扫描，请在手机上点击登录"
                        }
                        //二维码失效
                        code == 86038 -> {
                            CenterThreadPool.runOnUiThread(Runnable {
                                if (isDestroyed) return@Runnable
                                scanStat!!.text = "二维码已失效，点击上方重新获取"
                                needRefresh = true
                                qrImageView!!.isEnabled = true
                            })
                            this.cancel()
                        }
                        //待扫码
                        code == 86039 -> CenterThreadPool.runOnUiThread {
                            if (!isDestroyed) scanStat!!.text =
                                "请使用官方手机端哔哩哔哩，扫描${loginIdentity.displayName}二维码登录"
                        }
                        else -> CenterThreadPool.runOnUiThread {
                            if (!isDestroyed) {
                                val msg = result.optString("message", "")
                                scanStat!!.text = if (msg.isEmpty()) "登录接口可能变动，建议反馈给开发者" else msg
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isDestroyed) {
                        CenterThreadPool.runOnUiThread(Runnable {
                            if (isDestroyed) return@Runnable
                            needRefresh = true
                            qrImageView!!.isEnabled = true
                            scanStat!!.text = "无法获取二维码信息，点击二维码重试"
                            MsgUtil.err(e)
                        })
                    }
                    this.cancel()
                }
            }
        }, 500, 1000)
    }

    /** 一次扫码登录的结果：mid + 本轮是否把「其它页面 API」端口从 Web 切到了该 APP 端口 */
    private data class LoginOutcome(val mid: Long, val mainPortSwitched: Boolean)

    /**
     * 把扫码成功的凭据写进 [AccountStore] 的该端口多账号槽位，返回登录结果。
     *
     * 只调 AccountStore：它内部负责「先把当前激活账号快照回自己的槽位、再写新号」，
     * 若这里再手动写一遍激活槽位就会把快照对象弄错（上一个号会被新号覆盖）。
     *
     * 回包里没有 token_info（多为风控）时抛出 IllegalStateException（用 checkNotNull()），由调用方展示失败原因。
     *
     * ## 顺带把「其它页面 API」从 Web 切到本端口（P0-1）
     *
     * 背景：`ApiRouter.isEnabled()` 的唯一来源是「账号管理 → 其它页面 API」端口
     * （`AccountStore.mainPort()`，默认 Web）。历史上该端口只由用户手动点选，
     * 于是「扫码登了 APP 端口、却没登 web」的用户迁移层始终不启用，
     * 一切请求继续带空 web Cookie 走 web —— 写操作全部失败。
     *
     * 口径：**只在该端口原本是 Web 时才切**。用户手动选过 Android手机/HD/TV 的，
     * 说明那是他的明确选择，这里不动。web 登录不走本页，`loginPort` 天然非 web，
     * 但仍显式判一次，避免将来调用方传 web 时误切。
     */
    private fun loginWithAccountStore(data: JSONObject): LoginOutcome {
        val tokenInfo = data.optJSONObject("token_info")
        checkNotNull(tokenInfo) { "接口未返回身份信息，可能被风控，请尝试重新登录" }
        val mid = tokenInfo.optLong("mid", 0L)
        val accessToken = tokenInfo.optString("access_token", "")
        val refreshToken = tokenInfo.optString("refresh_token", "")
        AccountStore.loginApp(
            loginPort, mid, accessToken, refreshToken, cookiesTextOf(data), null, null
        )
        // v5.2.1（审计 P1-6）：自动切换要覆盖**两个**端口作用域，原来只切 mainPort。
        //
        // 漏切 rcmdPort 的后果：用户只扫了 HD 码 → mainPort 切到 HD（其它页面 API 走 HD 了），
        // 但推荐页仍走 web → web 未登录时推荐页是**匿名流**（WBI + 空 Cookie）。
        // 用户体感就是「我明明登录了，推荐页还是那些老东西，一点个性化都没有」。
        //
        // 口径不变：**只在该作用域原本是 Web 时才切**。用户手动选过端口说明那是他的明确选择。
        var switched = false
        if (loginPort != AccountStore.PORT_WEB) {
            if (AccountStore.mainPort() == AccountStore.PORT_WEB) {
                AccountStore.setMainPort(loginPort)
                switched = true
            }
            if (AccountStore.rcmdPort() == AccountStore.PORT_WEB) {
                AccountStore.setRcmdPort(loginPort)
                // 推荐页也切了，同样要告诉用户「已切换」，否则他只看到推荐页变了却不知道为什么
                switched = true
            }
        }
        return LoginOutcome(mid, switched)
    }

    /** 从登录回包提取 "name=value; name2=value2" 形式的 Cookie 串（token 响应的 cookie_info） */
    private fun cookiesTextOf(data: JSONObject): String {
        val cookies = data.optJSONObject("cookie_info")?.optJSONArray("cookies") ?: return ""
        val pairs = ArrayList<String>()
        for (i in 0 until cookies.length()) {
            val pair = cookiePairOf(cookies.optJSONObject(i))
            if (pair != null) pairs.add(pair)
        }
        return pairs.joinToString("; ")
    }

    /** 单条 Cookie 拼成 "name=value"；缺名或缺值返回 null（调用方跳过这条） */
    private fun cookiePairOf(cookie: JSONObject?): String? {
        if (cookie == null) return null
        val name = cookie.optString("name")
        val value = cookie.optString("value")
        if (name.isEmpty() || value.isEmpty()) return null
        return name + "=" + value
    }

    /**
     * 登录成功后的单一提示（v5.1.5 起不再询问「是否打开推荐页面的移动端逻辑」，
     * 也不再写 app_rcmd_enabled —— 该键已删除，推荐页走哪个端口由「设置 → 账号管理」页签 1 决定）。
     *
     * 本轮（P0-1）在「其它页面 API」端口本来还是 Web 时，登录成功会顺带把它切到该 APP 端口；
     * 切换是隐式发生的，必须在这里告诉用户，否则用户会以为「其它页面」还是 web。
     */
    private fun showSuccessDialog(outcome: LoginOutcome) {
        val baseMsg = "已登录 " + AccountStore.portDisplayName(loginPort) + " 端账号（uid：${outcome.mid}），" +
                "可在「设置 → 账号管理」里切换账号。"
        val switchMsg = if (outcome.mainPortSwitched) {
            "\n\n其它页面接口已切换到" + AccountStore.portDisplayName(loginPort) + "端" +
                    "（原本是 Web）；如需改回，可在「设置 → 账号管理 → 其它页面 API」手动切换。"
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("登录成功")
            .setMessage(baseMsg + switchMsg)
            .setPositiveButton("好的") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

}
