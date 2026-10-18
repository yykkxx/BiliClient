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
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.card.MaterialCardView

import org.json.JSONObject

import java.util.Timer
import java.util.TimerTask

/**
 * 移动端推荐逻辑的扫码登录页（电视端扫码协议）。
 *
 * 与 web 端扫码登录的区别：
 * - 接口为 passport-tv-login（auth_code / poll），返回 access_token 而非 web Cookie；
 * - 登录信息只写入 app_rcmd_ 前缀的独立键，完全不动软件的 web 登录；
 * - 登录成功后弹窗询问「是否打开推荐页面的移动端逻辑」。
 */
class AppRcmdLoginActivity : BaseActivity() {
    private var qrImageView: ImageView? = null
    private var scanStat: TextView? = null
    private var qrImage: Bitmap? = null
    private var timer: Timer? = null
    private var needRefresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_rcmd_login)
        setPageName("移动端登录")
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
                qrImage = AppRcmdApi.getLoginQR()
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
                    val result = AppRcmdApi.getLoginState()
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
                                    val mid = AppRcmdApi.saveLogin(data)
                                    scanStat!!.text = "登录成功！"
                                    showEnableDialog(mid)
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
                            if (!isDestroyed) scanStat!!.text = "请使用官方手机端哔哩哔哩扫码登录"
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

    /** 登录成功后：询问是否打开推荐页面的移动端逻辑 */
    private fun showEnableDialog(mid: Long) {
        AlertDialog.Builder(this)
            .setTitle("登录成功")
            .setMessage("已使用移动端方式登录（uid：$mid）。\n\n是否打开推荐页面的移动端逻辑？")
            .setPositiveButton("打开") { _, _ ->
                SharedPreferencesUtil.putBoolean(SharedPreferencesUtil.APP_RCMD_ENABLED, true)
                MsgUtil.showMsg("已开启移动端推荐逻辑，刷新推荐页生效")
                finish()
            }
            .setNegativeButton("暂不") { _, _ ->
                MsgUtil.showMsg("可稍后在实验室-移动端推荐逻辑中开启")
                finish()
            }
            .setCancelable(false)
            .show()
    }
}
