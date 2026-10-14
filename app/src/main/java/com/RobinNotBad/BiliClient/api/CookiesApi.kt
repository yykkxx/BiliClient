package com.RobinNotBad.BiliClient.api

import android.annotation.SuppressLint
import android.content.Context
import android.util.DisplayMetrics
import android.util.Pair
import android.view.WindowManager

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.util.Cookies
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.ArrayList
import java.util.HashMap
import java.util.Random

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cookies相关API
 */
object CookiesApi {

    @JvmStatic
    fun genWebHeaders(): ArrayList<String> {
        val headers = ArrayList<String>(NetWorkUtil.webHeaders)

        headers.add("Sec-Fetch-Site")
        headers.add("same-site")

        headers.add("Sec-Fetch-Mode")
        headers.add("cors")

        headers.add("Sec-Fetch-Dest")
        headers.add("empty")

        return headers
    }

    /**
     * 调用ExClimbWuzhi API激活Cookies，并检查如果有一些可本地生成的Cookie没有就顺带生成一下
     * 注：payload是我瞎jb弄得
     *
     * @return 返回码
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun activeCookieInfo(): Int {
        val url = "https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi"
        //NetWorkUtil.postJson(url, genCookiePayload().toString(), genWebHeaders());    //b站自己请求两次，所以我也请求两次（？）
        return JSONObject(NetWorkUtil.postJson(url, genCookiePayload(), genWebHeaders()).body()!!.string()).getInt("code")
    }

    /*
     * from https://s1.hdslb.com/bfs/seed/log/report/log-reporter.js
     */
    @Throws(JSONException::class)
    @JvmStatic
    fun genCookiePayload(): String {
        val resolution = gen_browser_resolution()
        val resolutionArray = JSONArray()
        resolutionArray.put(resolution.second)
        resolutionArray.put(resolution.first)

        val payload = """{"payload": "{\"5062\":TIME_HERE,\"39c8\":\"333.1007.fp.risk\",\"920b\":\"0\",\"df35\":\"UUID_HERE\",\"03bf\":\"https://www.bilibili.com/\",\"6e7c\":\"RESOLUTION_HERE\",\"3c43\":{\"2673\":0,\"5766\":24,\"6527\":0,\"7003\":1,\"807e\":1,\"b8ce\":\"UA_HERE\",\"641c\":0,\"07a4\":\"zh-CN\",\"1c57\":8,\"0bd0\":4,\"748e\":PAIR_HERE,\"d61f\":PAIR_HERE,\"fc9d\":-480,\"6aa9\":\"Asia/Shanghai\",\"75b8\":1,\"3b21\":1,\"8a1c\":0,\"d52f\":\"not available\",\"adca\":\"Win32\",\"80c9\":[[\"360SoftMgrPlugin\",\"360SoftMgrPlugin\",[[\"application/360softmgrplugin\",\"dll\"]]],[\"Alipay Security Control 3\",\"Alipay Security Control\",[[\"application/x-alisecctrl-plugin\",\"*\"]]],[\"Alipay security control\",\"npaliedit\",[[\"application/aliedit\",\"\"]]],[\"BaiduYunGuanjia Application\",\"YunWebDetect\",[[\"application/bd-npyunwebdetect-plugin\",\"\"]]],[\"Chromium PDF Plugin\",\"Portable Document Format\",[[\"application/x-google-chrome-pdf\",\"pdf\"]]],[\"Chromium PDF Viewer\",\"\",[[\"application/pdf\",\"pdf\"]]],[\"Java Deployment Toolkit 8.0.2910.10\",\"NPRuntime Script Plug-in Library for Java(TM) Deploy\",[[\"application/java-deployment-toolkit\",\"\"]]],[\"Java(TM) Platform SE 8 U291\",\"Next Generation Java Plug-in 11.291.2 for Mozilla browsers\",[[\"application/x-java-applet\",\"\"],[\"application/x-java-bean\",\"\"],[\"application/x-java-vm\",\"\"],[\"application/x-java-applet;version=1.1.1\",\"\"],[\"application/x-java-bean;version=1.1.1\",\"\"],[\"application/x-java-applet;version=1.1\",\"\"],[\"application/x-java-bean;version=1.1\",\"\"],[\"application/x-java-applet;version=1.2\",\"\"],[\"application/x-java-bean;version=1.2\",\"\"],[\"application/x-java-applet;version=1.1.3\",\"\"],[\"application/x-java-bean;version=1.1.3\",\"\"],[\"application/x-java-applet;version=1.1.2\",\"\"],[\"application/x-java-bean;version=1.1.2\",\"\"],[\"application/x-java-applet;version=1.3\",\"\"],[\"application/x-java-bean;version=1.3\",\"\"],[\"application/x-java-applet;version=1.2.2\",\"\"],[\"application/x-java-bean;version=1.2.2\",\"\"],[\"application/x-java-applet;version=1.2.1\",\"\"],[\"application/x-java-bean;version=1.2.1\",\"\"],[\"application/x-java-applet;version=1.3.1\",\"\"],[\"application/x-java-bean;version=1.3.1\",\"\"],[\"application/x-java-applet;version=1.4\",\"\"],[\"application/x-java-bean;version=1.4\",\"\"],[\"application/x-java-applet;version=1.4.1\",\"\"],[\"application/x-java-bean;version=1.4.1\",\"\"],[\"application/x-java-applet;version=1.4.2\",\"\"],[\"application/x-java-bean;version=1.4.2\",\"\"],[\"application/x-java-applet;version=1.5\",\"\"],[\"application/x-java-bean;version=1.5\",\"\"],[\"application/x-java-applet;version=1.6\",\"\"],[\"application/x-java-bean;version=1.6\",\"\"],[\"application/x-java-applet;version=1.7\",\"\"],[\"application/x-java-bean;version=1.7\",\"\"],[\"application/x-java-applet;version=1.8\",\"\"],[\"application/x-java-bean;version=1.8\",\"\"],[\"application/x-java-applet;jpi-version=1.8.0_291\",\"\"],[\"application/x-java-bean;jpi-version=1.8.0_291\",\"\"],[\"application/x-java-vm-npruntime\",\"\"],[\"application/x-java-applet;deploy=11.291.2\",\"\"],[\"application/x-java-applet;javafx=8.0.291\",\"\"]]],[\"MicrosoftÂ® Windows Media Player Firefox Plugin\",\"np-mswmp\",[[\"application/x-ms-wmp\",\"*\"],[\"application/asx\",\"*\"],[\"video/x-ms-asf-plugin\",\"*\"],[\"application/x-mplayer2\",\"*\"],[\"video/x-ms-asf\",\"asf,asx,*\"],[\"video/x-ms-wm\",\"wm,*\"],[\"audio/x-ms-wma\",\"wma,*\"],[\"audio/x-ms-wax\",\"wax,*\"],[\"video/x-ms-wmv\",\"wmv,*\"],[\"video/x-ms-wvx\",\"wvx,*\"]]],[\"QQÃÃ´ÃÃÂ²Â¥Â·ÃÂ¿ÃÂ¼Ã¾\",\"QQÃÃ´ÃÃÂ²Â¥Â·ÃÂ¿ÃÂ¼Ã¾\",[[\"application/tecent-qzonemusic-plugin\",\"rts\"]]],[\"Shockwave Flash\",\"Shockwave Flash 34.0 r0\",[[\"application/x-shockwave-flash\",\"swf\"],[\"application/futuresplash\",\"spl\"]]],[\"XunLei User Plugin\",\"Xunlei User scriptability Plugin,version= 2.0.2.3\",[[\"application/npxluser_plugin\",\"\"]]],[\"iTrusChina iTrusPTA,XEnroll,iEnroll,hwPTA,UKeyInstalls Firefox Plugin\",\"iTrusPTA&XEnroll hwPTA,IEnroll,UKeyInstalls for FireFox,version=1.0.0.2\",[[\"application/pta.itruspta.version.1\",\"*\"],[\"application/cenroll.cenroll.version.1\",\"\"],[\"application/itrusenroll.certenroll.version.1\",\"\"],[\"application/hwpta.itrushwpta\",\"\"],[\"application/hwwdkey.installwdkey\",\"\"],[\"application/hwepass2001.installepass2001\",\"\"]]],[\"npQQPhotoDrawEx\",\"npQQPhotoDrawEx Module\",[[\"application/tencent-qqphotodrawex2-plugin\",\"rts\"]]]],\"13ab\":\"hwAAAABJRU5ErkJggg==\",\"bfe9\":\"SAAskoALCSsZpEUcC+Av8DxpQVtSPLlMwAAAAASUVORK5CYII=\",\"a3c1\":[\"extensions:ANGLE_instanced_arrays;EXT_blend_minmax;EXT_clip_control;EXT_color_buffer_half_float;EXT_depth_clamp;EXT_disjoint_timer_query;EXT_float_blend;EXT_frag_depth;EXT_polygon_offset_clamp;EXT_shader_texture_lod;EXT_texture_compression_bptc;EXT_texture_compression_rgtc;EXT_texture_filter_anisotropic;EXT_sRGB;KHR_parallel_shader_compile;OES_element_index_uint;OES_fbo_render_mipmap;OES_standard_derivatives;OES_texture_float;OES_texture_float_linear;OES_texture_half_float;OES_texture_half_float_linear;OES_vertex_array_object;WEBGL_blend_func_extended;WEBGL_color_buffer_float;WEBGL_compressed_texture_s3tc;WEBGL_compressed_texture_s3tc_srgb;WEBGL_debug_renderer_info;WEBGL_debug_shaders;WEBGL_depth_texture;WEBGL_draw_buffers;WEBGL_lose_context;WEBGL_multi_draw;WEBGL_polygon_mode\",\"webgl aliased line width range:[1, 1]\",\"webgl aliased point size range:[1, 1024]\",\"webgl alpha bits:8\",\"webgl antialiasing:yes\",\"webgl blue bits:8\",\"webgl depth bits:24\",\"webgl green bits:8\",\"webgl max anisotropy:16\",\"webgl max combined texture image units:32\",\"webgl max cube map texture size:16384\",\"webgl max fragment uniform vectors:1024\",\"webgl max render buffer size:16384\",\"webgl max texture image units:16\",\"webgl max texture size:16384\",\"webgl max varying vectors:30\",\"webgl max vertex attribs:16\",\"webgl max vertex texture image units:16\",\"webgl max vertex uniform vectors:4096\",\"webgl max viewport dims:[32767, 32767]\",\"webgl red bits:8\",\"webgl renderer:WebKit WebGL\",\"webgl shading language version:WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)\",\"webgl stencil bits:0\",\"webgl vendor:WebKit\",\"webgl version:WebGL 1.0 (OpenGL ES 2.0 Chromium)\",\"webgl unmasked vendor:Google Inc. (Intel)\",\"webgl unmasked renderer:ANGLE (Intel, Intel(R) HD Graphics 4000 (0x00000166) Direct3D11 vs_5_0 ps_5_0, D3D11)\",\"webgl vertex shader high float precision:23\",\"webgl vertex shader high float precision rangeMin:127\",\"webgl vertex shader high float precision rangeMax:127\",\"webgl vertex shader medium float precision:23\",\"webgl vertex shader medium float precision rangeMin:127\",\"webgl vertex shader medium float precision rangeMax:127\",\"webgl vertex shader low float precision:23\",\"webgl vertex shader low float precision rangeMin:127\",\"webgl vertex shader low float precision rangeMax:127\",\"webgl fragment shader high float precision:23\",\"webgl fragment shader high float precision rangeMin:127\",\"webgl fragment shader high float precision rangeMax:127\",\"webgl fragment shader medium float precision:23\",\"webgl fragment shader medium float precision rangeMin:127\",\"webgl fragment shader medium float precision rangeMax:127\",\"webgl fragment shader low float precision:23\",\"webgl fragment shader low float precision rangeMin:127\",\"webgl fragment shader low float precision rangeMax:127\",\"webgl vertex shader high int precision:0\",\"webgl vertex shader high int precision rangeMin:31\",\"webgl vertex shader high int precision rangeMax:30\",\"webgl vertex shader medium int precision:0\",\"webgl vertex shader medium int precision rangeMin:31\",\"webgl vertex shader medium int precision rangeMax:30\",\"webgl vertex shader low int precision:0\",\"webgl vertex shader low int precision rangeMin:31\",\"webgl vertex shader low int precision rangeMax:30\",\"webgl fragment shader high int precision:0\",\"webgl fragment shader high int precision rangeMin:31\",\"webgl fragment shader high int precision rangeMax:30\",\"webgl fragment shader medium int precision:0\",\"webgl fragment shader medium int precision rangeMin:31\",\"webgl fragment shader medium int precision rangeMax:30\",\"webgl fragment shader low int precision:0\",\"webgl fragment shader low int precision rangeMin:31\",\"webgl fragment shader low int precision rangeMax:30\"],\"6bc5\":\"Google Inc. (Intel)~ANGLE (Intel, Intel(R) HD Graphics 4000 (0x00000166) Direct3D11 vs_5_0 ps_5_0, D3D11)\",\"ed31\":0,\"72bd\":0,\"097b\":0,\"52cd\":[0,0,0],\"a658\":[\"Arial\",\"Arial Black\",\"Arial Narrow\",\"Arial Unicode MS\",\"Book Antiqua\",\"Bookman Old Style\",\"Calibri\",\"Cambria\",\"Cambria Math\",\"Century\",\"Century Gothic\",\"Century Schoolbook\",\"Comic Sans MS\",\"Consolas\",\"Courier\",\"Courier New\",\"Georgia\",\"Helvetica\",\"Impact\",\"Lucida Bright\",\"Lucida Calligraphy\",\"Lucida Console\",\"Lucida Fax\",\"Lucida Handwriting\",\"Lucida Sans\",\"Lucida Sans Typewriter\",\"Lucida Sans Unicode\",\"Microsoft Sans Serif\",\"Monotype Corsiva\",\"MS Gothic\",\"MS PGothic\",\"MS Reference Sans Serif\",\"MS Sans Serif\",\"MS Serif\",\"MYRIAD PRO\",\"Palatino Linotype\",\"Segoe Print\",\"Segoe Script\",\"Segoe UI\",\"Segoe UI Light\",\"Segoe UI Semibold\",\"Segoe UI Symbol\",\"Tahoma\",\"Times\",\"Times New Roman\",\"Trebuchet MS\",\"Verdana\",\"Wingdings\",\"Wingdings 2\",\"Wingdings 3\"],\"d02f\":\"124.04347527516074\"}}"}"""

        return payload.replace("TIME_HERE", System.currentTimeMillis().toString())
                .replace("RESOLUTION_HERE", resolution.first.toString() + "x" + resolution.second)
                .replace("UUID_HERE", NetWorkUtil.getCookies().getOrDefault("_uuid", ""))
                .replace("UA_HERE", NetWorkUtil.USER_AGENT_WEB)
                .replace("PAIR_HERE", resolutionArray.toString())
    }

    /**
     * 仅获取buvid3
     *
     * @return buvid3
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getBuvid3Only(): String {
        val url = "https://api.bilibili.com/x/web-frontend/getbuvid"
        val data = NetWorkUtil.getJson(url, genWebHeaders())
        return data.optString("buvid", "")
    }

    /**
     * 获取buvid3、buvid4，可能需要在上面先获取单个的buvid3
     *
     * @return buvid3、buvid4
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getWebBuvids(): Pair<String, String> {
        val url = "https://api.bilibili.com/x/frontend/finger/spi"
        val data = NetWorkUtil.getJson(url, genWebHeaders()).getJSONObject("data")
        return Pair(data.optString("b_3"), data.optString("b_4"))
    }

    /**
     * 生成bili_ticket
     *
     * @return bili_ticket and create time
     */
    @Throws(IOException::class, NoSuchAlgorithmException::class, InvalidKeyException::class, JSONException::class)
    @JvmStatic
    fun genBiliTicket(): Pair<String?, Int> {
        val ts = System.currentTimeMillis() / 1000
        val o = hmacSha256("XgwSnGZ1p", "ts$ts")
        val url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket"
        val result = JSONObject(NetWorkUtil.postJson(url + NetWorkUtil.FormData()
                        .setUrlParam(true)
                        .put("key_id", "ec02")
                        .put("hexsign", o)
                        .put("context[ts]", ts.toString())
                        .put("csrf", SharedPreferencesUtil.getString("csrf", "")),
                "", genWebHeaders()).body()!!.string())
        if (result.has("data") && !result.isNull("data")) {
            val data = result.getJSONObject("data")
            return Pair(data.optString("ticket"), data.optInt("created_at"))
        } else {
            return Pair(null, -1)
        }
    }

    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    @JvmStatic
    fun hmacSha256(key: String, message: String): String {
        val sha256Hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        sha256Hmac.init(secretKey)
        val hashBytes = sha256Hmac.doFinal(message.toByteArray())
        val hexHash = StringBuilder()
        for (b in hashBytes) {
            val hex = Integer.toHexString(0xff and b.toInt())
            if (hex.length == 1) hexHash.append('0')
            hexHash.append(hex)
        }
        return hexHash.toString()
    }

    private val otherCookieMap: MutableMap<String, String> = HashMap()

    init {
        otherCookieMap["enable_web_push"] = "DISABLE"
        otherCookieMap["header_theme_version"] = "undefined"
        otherCookieMap["home_feed_column"] = "4"
        otherCookieMap["PVID"] = "1"
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun checkCookies() {
        NetWorkUtil.get("https://www.bilibili.com/")

        val cookies = NetWorkUtil.getCookies()

        // _uuid
        if (!cookies.containsKey("_uuid")) {
            NetWorkUtil.putCookie("_uuid", gen_uuid_infoc())
        }

        // b_nut
        if (!cookies.containsKey("b_nut")) {
            NetWorkUtil.putCookie("b_nut", gen_b_nut())
        }

        // b_lsid
        if (!cookies.containsKey("b_lsid")) {
            NetWorkUtil.putCookie("b_lsid", gen_b_lsid())
        }

        // buvid3 根据浏览器端的网络请求顺序，B站自己是先获取单个buvid3的，虽然我并不知道为什么要这样做…？
        if (!cookies.containsKey("buvid3")) {
            val buvid3 = getBuvid3Only()
            NetWorkUtil.putCookie("buvid3", buvid3)
        }

        // buvid3 & buvid4. Get from http API.
        if (!cookies.containsKey("buvid4")) {
            val buvids = getWebBuvids()
            NetWorkUtil.putCookie("buvid3", buvids.first)
            NetWorkUtil.putCookie("buvid4", buvids.second)
        }

        // LIVE_BUVID
        if (!cookies.containsKey("LIVE_BUVID")) {
            val min = 1000000000000000L
            val max = 9999999999999999L
            NetWorkUtil.putCookie("LIVE_BUVID", "AUTO" + (min + (Random().nextDouble() * (max - min)).toLong()))
        }

        // browser_resolution
        if (!cookies.containsKey("browser_resolution")) {
            val resolution = gen_browser_resolution()
            NetWorkUtil.putCookie("browser_resolution", resolution.first.toString() + "-" + resolution.second)
        }

        // bili_ticket
        val ticketExpires = parseInt(cookies.get("bili_ticket_expires"))
        if (!cookies.containsKey("bili_ticket") || cookies.get("bili_ticket")!!.equals("null") || !cookies.containsKey("bili_ticket_expires") || ticketExpires == null || ticketExpires < System.currentTimeMillis() / 1000) {
            try {
                val bili_ticket = genBiliTicket()
                NetWorkUtil.putCookie("bili_ticket", bili_ticket.first!!)
                NetWorkUtil.putCookie("bili_ticket_expires", (bili_ticket.second + (3 * 24 * 60 * 60)).toString())
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            } catch (e: InvalidKeyException) {
                throw RuntimeException(e)
            }
        }

        // buvid_fp
        if (!cookies.containsKey("buvid_fp")) {
            NetWorkUtil.putCookie("buvid_fp", gen_buvid_fp(NetWorkUtil.USER_AGENT_WEB + System.currentTimeMillis(), 31))
        }

        // Others
        for (entry in otherCookieMap.entries) {
            if (!cookies.containsKey(entry.key)) {
                NetWorkUtil.putCookie(entry.key, entry.value)
            }
        }

        //activeCookieInfo();
    }

    private fun parseInt(string: String?): Int? {
        return try {
            string!!.toInt()
        } catch (ignored: Throwable) {
            null
        }
    }

    private val MP = arrayOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "A", "B", "C", "D", "E", "F", "10"
    )
    private val PCK = intArrayOf(8, 4, 4, 4, 12)
    private val CHARSET = "0123456789ABCDEF"

    private fun gen_b_lsid(): String {
        val random = SecureRandom()
        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            sb.append(CHARSET[random.nextInt(CHARSET.length)])
        }
        val randomString = sb.toString()
        val currentTimeMillis = System.currentTimeMillis()
        return randomString + "_" + java.lang.Long.toHexString(currentTimeMillis).toUpperCase()
    }

    @Suppress("DefaultLocale")
    private fun gen_uuid_infoc(): String {
        val t = System.currentTimeMillis() % 100000
        val sb = StringBuilder()
        val random = Random()
        for (len in PCK) {
            for (i in 0 until len) {
                sb.append(MP[random.nextInt(16)])
            }
            sb.append("-")
        }
        sb.deleteCharAt(sb.length - 1)
        sb.append(String.format("%05d", t)).append("infoc")
        return sb.toString()
    }

    private fun gen_b_nut(): String {
        val timestampInSeconds = System.currentTimeMillis() / 1000
        return timestampInSeconds.toString()
    }

    private fun gen_browser_resolution(): Pair<Int, Int> {
        val windowManager = BiliTerminal.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    private val MOD = BigInteger.ONE.shiftLeft(64)
    private val C1 = BigInteger("87C37B91114253D5", 16)
    private val C2 = BigInteger("4CF5AD432745937F", 16)
    private val C3 = BigInteger.valueOf(0x52DCE729L)
    private val C4 = BigInteger.valueOf(0x38495AB5L)
    private val R1 = 27
    private val R2 = 31
    private val R3 = 33
    private val M = 5

    @Throws(IOException::class)
    @JvmStatic
    fun gen_buvid_fp(key: String, seed: Long): String {
        val source = ByteArrayInputStream(key.toByteArray(charset("US-ASCII")))
        val m = murmur3_x64_128(source, BigInteger.valueOf(seed))
        return String.format("%016x%016x", m.mod(MOD), m.shiftRight(64).mod(MOD))
    }

    private fun rotateLeft(x: BigInteger, k: Int): BigInteger {
        return x.shiftLeft(k).or(x.shiftRight(64 - k)).mod(MOD)
    }

    private fun murmur3_x64_128(source: InputStream, seed: BigInteger): BigInteger {
        var h1 = seed
        var h2 = seed
        var processed = 0L
        val buffer = ByteArray(16)
        while (true) {
            val bytesRead = source.read(buffer)
            processed += bytesRead.toLong()
            if (bytesRead == 16) {
                val k1 = ByteBuffer.wrap(buffer, 0, 8).getLong()
                val k2 = ByteBuffer.wrap(buffer, 8, 8).getLong()
                h1 = h1.xor(rotateLeft(BigInteger.valueOf(k1).multiply(C1).mod(MOD), R2).multiply(C2).mod(MOD))
                h1 = (rotateLeft(h1, R1).add(h2).multiply(BigInteger.valueOf(M.toLong())).add(C3)).mod(MOD)
                h2 = h2.xor(rotateLeft(BigInteger.valueOf(k2).multiply(C2).mod(MOD), R3).multiply(C1).mod(MOD))
                h2 = (rotateLeft(h2, R2).add(h1).multiply(BigInteger.valueOf(M.toLong())).add(C4)).mod(MOD)
            } else if (bytesRead == -1) {
                h1 = h1.xor(BigInteger.valueOf(processed))
                h2 = h2.xor(BigInteger.valueOf(processed))
                h1 = h1.add(h2).mod(MOD)
                h2 = h2.add(h1).mod(MOD)
                h1 = fmix64(h1)
                h2 = fmix64(h2)
                h1 = h1.add(h2).mod(MOD)
                h2 = h2.add(h1).mod(MOD)
                return h2.shiftLeft(64).or(h1)
            } else {
                var k1 = 0L
                var k2 = 0L
                val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                if (bytesRead >= 15) {
                    k2 = k2 xor (byteBuffer.get(14).toLong() shl 48)
                }
                if (bytesRead >= 14) {
                    k2 = k2 xor (byteBuffer.get(13).toLong() shl 40)
                }
                if (bytesRead >= 13) {
                    k2 = k2 xor (byteBuffer.get(12).toLong() shl 32)
                }
                if (bytesRead >= 12) {
                    k2 = k2 xor (byteBuffer.get(11).toLong() shl 24)
                }
                if (bytesRead >= 11) {
                    k2 = k2 xor (byteBuffer.get(10).toLong() shl 16)
                }
                if (bytesRead >= 10) {
                    k2 = k2 xor (byteBuffer.get(9).toLong() shl 8)
                }
                if (bytesRead >= 9) {
                    k2 = k2 xor byteBuffer.get(8).toLong()
                    h2 = h2.xor(rotateLeft(BigInteger.valueOf(k2).multiply(C2).mod(MOD), R3).multiply(C1).mod(MOD))
                }
                if (bytesRead >= 8) {
                    k1 = k1 xor (byteBuffer.get(7).toLong() shl 56)
                }
                if (bytesRead >= 7) {
                    k1 = k1 xor (byteBuffer.get(6).toLong() shl 48)
                }
                if (bytesRead >= 6) {
                    k1 = k1 xor (byteBuffer.get(5).toLong() shl 40)
                }
                if (bytesRead >= 5) {
                    k1 = k1 xor (byteBuffer.get(4).toLong() shl 32)
                }
                if (bytesRead >= 4) {
                    k1 = k1 xor (byteBuffer.get(3).toLong() shl 24)
                }
                if (bytesRead >= 3) {
                    k1 = k1 xor (byteBuffer.get(2).toLong() shl 16)
                }
                if (bytesRead >= 2) {
                    k1 = k1 xor (byteBuffer.get(1).toLong() shl 8)
                }
                if (bytesRead >= 1) {
                    k1 = k1 xor byteBuffer.get(0).toLong()
                    h1 = h1.xor(rotateLeft(BigInteger.valueOf(k1).multiply(C1).mod(MOD), R2))
                }
            }
        }
    }

    private fun fmix64(k_: BigInteger): BigInteger {
        var k = k_
        val C1 = BigInteger("FF51AFD7ED558CCD", 16)
        val C2 = BigInteger("C4CEB9FE1A85EC53", 16)
        val R = 33
        k = k.xor(k.shiftRight(R)).multiply(C1).mod(MOD)
        k = k.xor(k.shiftRight(R)).multiply(C2).mod(MOD)
        k = k.xor(k.shiftRight(R)).mod(MOD)
        return k
    }
}
