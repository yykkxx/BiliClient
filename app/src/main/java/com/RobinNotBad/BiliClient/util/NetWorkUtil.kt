package com.RobinNotBad.BiliClient.util

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Inflater
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 被 luern0313 创建于 2019/10/13.
 * #以下代码来源于腕上哔哩的开源项目，感谢开源者做出的贡献！
 *
 * Kotlin 重构版（原 NetWorkUtil.java）。
 * 所有方法 @JvmStatic、常量 @JvmField，保持 Java 调用端签名与行为不变。
 * uncompress 在原逻辑基础上增加了 needsInput 防护，修复数据异常时 inflate(0) 空转导致的卡死。
 */
object NetWorkUtil {

    private val INSTANCE = AtomicReference<OkHttpClient?>()

    /** 与原 synchronized(NetWorkUtil.class) 等价的锁对象 */
    private val COOKIE_LOCK = Any()

    /**
     * 连接池：弱网/4G 场景下复用 TCP 连接可以显著减少握手开销。
     * 默认 5 个空闲连接、5 分钟保活对本应用足够了。
     */
    private val CONNECTION_POOL = okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)

    class Inet4Selector : Dns {
        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> {
            val hosts = Dns.SYSTEM.lookup(hostname)
            val inet4Hosts = ArrayList<InetAddress>()
            for (host in hosts) {
                if (host.address.size == 4) inet4Hosts.add(host)
            }
            return inet4Hosts //筛选IPV4地址，IPV6请求有异常
        }
    }

    private fun getOrCreateClient(): OkHttpClient? {
        run {
            while (INSTANCE.get() == null) {
                INSTANCE.compareAndSet(
                    null,
                    setOkHttpSsl(OkHttpClient.Builder())
                        .followRedirects(false)
                        .addInterceptor { chain: Interceptor.Chain ->
                            val request = chain.request()
                            val response = chain.proceed(request)
                            val location = response.header("Location")
                            var isSslRedirect = false
                            try {
                                isSslRedirect = location != null && !request.isHttps &&
                                        URI(location).scheme.equals("https", ignoreCase = true) &&
                                        request.url.host.equals(URI(location).host, ignoreCase = true)
                            } catch (ignored: URISyntaxException) {
                            }

                            if (response.isRedirect && location != null) {
                                val handler = request.tag(RedirectHandler::class.java)
                                if (request.url.host == "b23.tv" && !isSslRedirect && handler != null) {
                                    handler.handleRedirect(location)
                                } else {
                                    val newRequest = request.newBuilder()
                                        .url(location)
                                        .build()
                                    return@addInterceptor chain.proceed(newRequest)
                                }
                            }
                            response
                        }
                        .addInterceptor(CookieSaveInterceptor())
                        // 弱网优化（#11）：复用连接、跳过无意义的压缩，
                        // 减少 4G 下的连接建立次数与 CPU 开销（连接握手是最费电的部分）
                        .addInterceptor(WeakNetworkInterceptor())
                        .dns(Inet4Selector())
                        .pingInterval(8, TimeUnit.SECONDS)
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(16, TimeUnit.SECONDS)
                        .connectionPool(CONNECTION_POOL)
                        .retryOnConnectionFailure(true)
                        .build()
                )
            }
            return INSTANCE.get()
        }
    }

    @JvmStatic
    fun getOkHttpInstance(): OkHttpClient {
        return getOrCreateClient()!!
    }

    @JvmStatic
    @Synchronized
    fun setOkHttpSsl(okhttpBuilder: OkHttpClient.Builder): OkHttpClient.Builder {
        //minSdk 29（Android 10）起系统 TLS 栈已足够完整，
        //旧版（API<=22）才需要的自建 SSL socket 工厂适配层已删除。
        return okhttpBuilder
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getJson(url: String?): JSONObject {
        val body = get(url)?.body ?: throw JSONException("在访问${url}时返回数据为空")
        body.use { return JSONObject(it.string()) }
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getJson(url: String?, headers: ArrayList<String>?): JSONObject {
        val body = get(url, headers)?.body ?: throw JSONException("在访问${url}时返回数据为空")
        body.use { return JSONObject(it.string()) }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?): Response {
        return get(url, webHeaders)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?, headers: ArrayList<String>?): Response {
        return get(url, headers, null)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?, headers: ArrayList<String>?, redirectHandler: RedirectHandler?): Response {
        Logu.d("get-url", url ?: "")
        val client = getOkHttpInstance()
        val requestBuilder = Request.Builder().url(url!!).get()
        var i = 0
        while (i < headers!!.size) {
            requestBuilder.addHeader(headers[i], headers[i + 1])
            i += 2
        }
        if (redirectHandler != null) requestBuilder.tag(RedirectHandler::class.java, redirectHandler)
        val request = requestBuilder.build()
        return client.newCall(request).execute()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun post(url: String?, data: String?, headers: List<String>?, contentType: String?): Response {
        Logu.d("post-url", url ?: "")
        Logu.d("post-data", data ?: "")
        val client = getOkHttpInstance()
        val mediaType = ((contentType ?: "application/json") + "; charset=utf-8").toMediaTypeOrNull()
        val body = (data ?: "").toRequestBody(mediaType)
        val requestBuilder = Request.Builder().url(url!!).post(body)
        var i = 0
        while (i < headers!!.size) {
            val key = headers[i]
            val `val` = headers[i + 1]
            if (key.equals("Content-Type", ignoreCase = true)) requestBuilder.addHeader(key, contentType!!)
            else requestBuilder.addHeader(key, `val`)
            i += 2
        }
        val request = requestBuilder.build()
        return client.newCall(request).execute()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun post(url: String?, data: String?, headers: List<String>?): Response {
        return post(url, data, headers, "application/x-www-form-urlencoded")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun postJson(url: String?, data: String?, headers: List<String>?): Response {
        return post(url, data, headers, "application/json")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun postJson(url: String?, data: String?): Response {
        return post(url, data, webHeaders, "application/json")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun post(url: String?, data: String?): Response {
        return post(url, data, webHeaders)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readStream(inStream: InputStream): ByteArray {
        val outStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var len: Int
        while (inStream.read(buffer).also { len = it } != -1) {
            outStream.write(buffer, 0, len)
        }
        outStream.close()
        inStream.close()
        return outStream.toByteArray()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun uncompress(inputByte: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream(inputByte.size)
        try {
            val inflater = Inflater(true)
            inflater.setInput(inputByte)
            val buffer = ByteArray(4 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                outputStream.write(buffer, 0, count)
                // 防护：数据异常时 inflate 返回0且需要输入，原实现会在此空转卡死
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            }
            inflater.end()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return outputStream.toByteArray()
    }

    @JvmStatic
    fun getInfoFromCookie(name: String, cookie: String): String {
        val cookies = cookie.split("; ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (i in cookies) {
            if (i.contains("$name=")) return i.substring(name.length + 1)
        }
        return ""
    }

    private fun saveCookiesFromResponse(response: Response) {
        val newCookies = response.headers("Set-Cookie")

        //如果没有新cookies，直接返回
        if (newCookies.isEmpty()) return
        val cookiesStr = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
        val oldCookies: ArrayList<String> =
            if (cookiesStr.isEmpty()) ArrayList() else ArrayList(listOf(*cookiesStr.split("; ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())) //转list

        for (newCookieIn in newCookies) { //对每一条新cookie遍历
            var newCookie = newCookieIn

            val cookies = Cookies(newCookie)
            if (cookies.containsKey("Domain") && !cookies["Domain"]!!.endsWith("bilibili.com")) continue

            var index = newCookie.indexOf("; ")
            if (index != -1) newCookie = newCookie.substring(0, index) //如果没有分号不做处理

            index = newCookie.indexOf("=") + 1
            if (index == 0) continue //如果没有等号，跳过

            val key = newCookie.substring(0, index) //key=
            Logu.d("newCookie", newCookie)

            var added = false
            for (i in oldCookies.indices) { //查找旧cookie表有没有
                val oldCookie = oldCookies[i]
                if (oldCookie.contains(key)) {
                    oldCookies[i] = newCookie //有的话直接换掉
                    added = true
                    break
                }
            }
            if (!added) {
                oldCookies.add(newCookie) //没有就加项
            }
        }

        val setCookies = StringBuilder()
        for (setCookie in oldCookies) {
            setCookies.append(setCookie).append("; ")
        }
        //如果一次setCookies都没有，就不要存了， 因为是个空字符串
        if (setCookies.length >= 2) {
            Logu.d("save-result", setCookies.substring(0, setCookies.length - 2))
            SharedPreferencesUtil.putString(
                SharedPreferencesUtil.cookies,
                setCookies.substring(0, setCookies.length - 2)
            )
            refreshHeaders()
        }
    }

    /**
     * 存储单个Cookie
     *
     * @param key 键
     * @param val 值
     */
    @JvmStatic
    fun putCookie(key: String, `val`: String) {
        synchronized(COOKIE_LOCK) {
            val cookies = Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
            cookies[key] = `val`
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies.toString())
            refreshHeaders()
        }
    }

    /**
     * 存储Cookies（覆盖写入）
     *
     * @param cookies cookies
     */
    @JvmStatic
    fun setCookies(cookies: Cookies?) {
        synchronized(COOKIE_LOCK) {
            SharedPreferencesUtil.putString(SharedPreferencesUtil.cookies, cookies!!.toString())
            refreshHeaders()
        }
    }

    /**
     * 获取存储的Cookies
     *
     * @return 存储的Cookies
     */
    @JvmStatic
    fun getCookies(): Cookies {
        synchronized(COOKIE_LOCK) {
            return Cookies(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
        }
    }

    @JvmField
    val USER_AGENT_WEB: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.95 Safari/537.36"

    @JvmField
    val webHeaders: ArrayList<String> = object : ArrayList<String>() {
        init {
            add("Cookie")
            add(SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")

            add("Origin")
            add("https://www.bilibili.com")

            add("Referer")
            add("https://www.bilibili.com/")

            add("User-Agent")
            add(USER_AGENT_WEB)

            add("Sec-Ch-Ua")
            add("\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")

            add("Sec-Ch-Ua-Platform")
            add("\"Windows\"")

            add("Sec-Ch-Ua-Mobile")
            add("?0")
        }
    }

    @JvmStatic
    fun refreshHeaders() {
        webHeaders[1] = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: ""
    }

    class FormData {
        private val data: MutableMap<String, String> = HashMap()
        private var isUrlParam = false

        fun remove(key: String?): FormData {
            data.remove(key)
            return this
        }

        fun put(key: String?, value: Any?): FormData {
            data[key!!] = value.toString()
            return this
        }

        fun setUrlParam(isUrlParam: Boolean): FormData {
            this.isUrlParam = isUrlParam
            return this
        }

        override fun toString(): String {
            val sb = StringBuilder()
            if (isUrlParam) sb.append("?")
            try {
                for (key in data.keys) {
                    if (sb.length > if (isUrlParam) 1 else 0) {
                        sb.append("&")
                    }
                    sb.append(URLEncoder.encode(key, "UTF-8"))
                    sb.append("=")
                    sb.append(URLEncoder.encode(data[key], "UTF-8"))
                }
            } catch (e: UnsupportedEncodingException) {
                throw RuntimeException(e)
            }
            return sb.toString()
        }
    }

    interface RedirectHandler {
        fun handleRedirect(location: String?)
    }

    private class CookieSaveInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            saveCookiesFromResponse(response)
            return response
        }
    }
}
