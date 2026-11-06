package com.RobinNotBad.BiliClient.api

import android.util.Base64

import android.os.SystemClock

import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONObject
import org.jsoup.Jsoup

import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Objects

import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.json.JSONException
import java.io.IOException

/*
这里是Cookie刷新逻辑
https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E8%8E%B7%E5%8F%96refresh-csrf
 */
object CookieRefreshApi {

    private const val TAG = "cookie-refresh"

    // ==========================================================================================
    // v5.2.1 Web 主动续期（用户需求：「Web 增加主动续期」）
    // ==========================================================================================
    //
    // ## 现状与问题
    // 旧实现只在 `RecommendActivity.onCreate` 里被动调一次 [checkAndRefresh]：
    // 用户如果长时间**不进推荐页**（停在详情/播放/搜索里连着看几小时），
    // Cookie 到期了也不会被续，等到某次写操作失败才暴露；
    // 反过来如果频繁进推荐页，又会反复请求 `cookie/info`（每次一个 RTT）。
    //
    // ## v5.2.1 的做法
    // 把「该不该续」的判断收进本文件，由两个入口驱动：
    // 1. **启动时主动一次**（`BiliTerminal` 前台化时，见 [renewIfNeededOnForeground]）；
    // 2. **长时间运行中按间隔兜底**（同一入口，内部用时间戳节流，默认 30 分钟一次）。
    //
    // 为什么用「时间戳节流」而不是「每次前台都问」：
    // `cookie/info` 是个带鉴权的网络请求，每次前台都问会让「切进切出 App」变成
    // 网络抖动放大器（用户体感是耗电+慢）。30 分钟一次对 Cookie 有效期（通常数天）绰绰有余。
    //
    // ## 为什么这个函数**只对 web 端口生效**
    // 三个 APP 端口用的是 access_key，没有 Cookie 可续（它们的续期语义是 refresh_token，
    // 由各自的登录链路处理）。在 APP 端口下调用只会白跑一趟，所以先判端口。
    // 这也符合「四端分开」：**不做跨端偷偷续期**。

    /** 节流记录：上次真正检查 `cookie/info` 的时刻（SystemClock.elapsedRealtime） */
    @Volatile
    private var lastCheckElapsed = 0L

    /** 主动续期的默认间隔：30 分钟。Cookie 有效期是数天级别，这个粒度足够且不打扰网络。 */
    private const val RENEW_INTERVAL_MS = 30L * 60L * 1000L

    /**
     * 主动续期入口（幂等 + 节流）。**调用方在后台线程调**（它会发网络请求）。
     *
     * @param force true = 忽略节流立刻检查（用户手动点「立即续期」时用）
     * @return 本次是否**真的刷新成功**（false 也表示「不需要刷新」或「没到时间」，
     *         所以调用方不要把它当成失败来提示用户）
     *
     * 触发条件（全部满足才真的发请求）：
     * 1. 当前主端口是 web（APP 端口没有 Cookie 可续）；
    2. web 有 `refresh_token`（没有它协议层就续不了，只能重新扫码）；
    3. 距上次检查超过 [RENEW_INTERVAL_MS]（或 [force]）。
     */
    @JvmStatic
    fun renewIfNeededOnForeground(force: Boolean): Boolean {
        if (AccountStore.mainPort() != AccountStore.PORT_WEB) return false
        if (!AccountManager.isWebLoggedIn()) return false
        if (SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "").isNullOrEmpty()) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastCheckElapsed
        if (!force && last > 0L && now - last < RENEW_INTERVAL_MS) return false
        lastCheckElapsed = now
        return checkAndRefresh()
    }

    /**
     * 真正做一次「问 → 取 path → 取 csrf → 刷新 → 确认」的完整链路。
     *
     * 抽成独立函数（原来的逻辑内联在 `RecommendActivity.checkCookieRefresh` 里）的理由：
     * 续期现在是**至少两个入口**（启动 + 间隔兜底）共用，逻辑留在 Activity 里就必然复制。
     * 本函数**不弹 UI**（不 MsgUtil），只返回布尔 —— 由调用方决定怎么提示，
     * 因为「启动时静默续期成功」不该弹一个 Toast 打扰用户。
     *
     * @return 是否真的刷新成功（`false` 的含义是「不需要 / 失败了」，见调用方注释）
     */
    @JvmStatic
    fun checkAndRefresh(): Boolean {
        return try {
            val cookieInfo = cookieInfo()
            if (!cookieInfo.optBoolean("refresh")) return false
            Logu.d(TAG, "cookie 需要刷新")
            val correspondPath = getCorrespondPath(cookieInfo.getLong("timestamp"))
            val refreshCsrf = getRefreshCsrf(correspondPath)
            if (refreshCsrf.isEmpty()) {
                Logu.e(TAG, "拿不到 refresh_csrf，无法续期")
                return false
            }
            val ok = refreshCookie(refreshCsrf)
            if (ok) Logu.d(TAG, "Cookie 主动续期成功") else Logu.e(TAG, "Cookie 主动续期失败")
            ok
        } catch (e: JSONException) {
            // 结构异常通常意味着 Cookie 已彻底失效（服务端返回的不是预期结构）
            Logu.e(TAG, "cookie/info 结构异常，Cookie 可能已失效")
            false
        } catch (e: Exception) {
            // 网络异常不改变登录态：下次前台再试即可，绝不能在这里清账号
            Logu.w(TAG, "cookie 续期异常：" + e.javaClass.simpleName)
            false
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun cookieInfo(): JSONObject {
        val url = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        val result = NetWorkUtil.getJson(url)
        return result.getJSONObject("data")
    }

    @JvmStatic
    fun getCorrespondPath(timestamp: Long): String { //时间戳要精准到毫秒！
        /*
        https://socialsisteryi.github.io/bilibili-API-collect/docs/login/cookie_refresh.html#%E7%94%9F%E6%88%90correspondpath%E7%AE%97%E6%B3%95
         */
        try {
            val publicKeyPEM = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB"
            val x509EncodedKeySpec = X509EncodedKeySpec(Base64.decode(publicKeyPEM, Base64.DEFAULT))
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(x509EncodedKeySpec)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            val oaepParameterSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParameterSpec)
            val data = "refresh_$timestamp"
            return base16Encode(cipher.doFinal(data.toByteArray()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    @Throws(IOException::class)
    @JvmStatic
    fun getRefreshCsrf(CorrespondPath: String): String {
        if (Objects.equals(CorrespondPath, "")) return ""
        val url = "https://www.bilibili.com/correspond/1/" + CorrespondPath
        val response = NetWorkUtil.get(url)
        if (response.body != null) {
            val document = Jsoup.parse(response.body!!.string())
            if (document.select("#1-name").size > 0)
                return document.select("#1-name").get(0).text()
            else return ""
        } else return ""
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun refreshCookie(RefreshCsrf: String): Boolean {
        //必须的参数不能为空
        if (Objects.equals(RefreshCsrf, "")) return false
        if (Objects.equals(SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""), ""))
            return false

        val cookies_old = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")

        //请求一个新的cookie
        val url = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        val args = "csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!) + "&refresh_csrf=" + RefreshCsrf + "&source=main_web&refresh_token=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "")
        val response = NetWorkUtil.post(url, args, NetWorkUtil.webHeaders)
        val result = JSONObject(response.body!!.string())
        if (result.getInt("code") == 0) {
            val refreshToken_new = result.getJSONObject("data").getString("refresh_token")
            Logu.v("新的RefreshToken", refreshToken_new)

            val cookies_new = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!
            Logu.v("新的cookies", cookies_new)


            //使老的Cookie失效
            val confirmCode = JSONObject(NetWorkUtil.post("https://passport.bilibili.com/x/passport-login/web/confirm/refresh", "csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", cookies_new) + "&refresh_token=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, ""), NetWorkUtil.webHeaders).body!!.string()).getInt("code")
            if (confirmCode != 0) { //必须要等确认更新Cookie成功，不然就无法完成Cookie的刷新
                Logu.e("Cookie刷新失败", "确认刷新时返回:$confirmCode")
                SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies_old)
                return false
            }
            SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, refreshToken_new)
            SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, NetWorkUtil.getInfoFromCookie("DedeUserID", cookies_new).toLong())
            SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, NetWorkUtil.getInfoFromCookie("bili_jct", cookies_new))
            Logu.v("Cookie刷新成功")
            NetWorkUtil.refreshHeaders()
            return true
        } else {
            Logu.e("Cookie刷新失败", "刷新时返回:" + result.getInt("code"))
            return false
        }
    }

    @JvmStatic
    fun base16Encode(src: ByteArray): String {
        val strbuf = StringBuilder(src.size * 2)

        for (i in src.indices) {
            if ((src[i].toInt() and 0xff) < 0x10)
                strbuf.append("0")

            strbuf.append(java.lang.Long.toString((src[i].toInt() and 0xff).toLong(), 16))
        }

        return strbuf.toString()
    }
}
