package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.TreeMap

/**
 * 短视频（Story）专用 API —— shortvideo 分支。
 *
 * v5.0.1-alpha5 重构要点（对应「短视频黑屏、连信息都加载不出来」）：
 * 1. **完整 APP 端参数/请求头**：官方手机客户端请求 /x/v2/feed/index/story 时带的
 *    fnval/fourk/network/device/statistics 等参数一并带上，并补 fp_local/fp_remote/
 *    session_id/bili-http-engine 头，尽量与官方客户端一致，避免被风控静默返回空列表。
 * 2. **手机端 access_key 失效自动匿名重试**：旧版迁移过来的 token 可能早已失效，
 *    带上去会直接 -101，导致整条流为空（用户侧就是「黑屏 + 没有任何信息」）；
 *    现在遇到 -101/-400/-352 会自动去掉 access_key 重试一次。
 * 3. **推荐流兜底**：短视频接口彻底不可用时，退回推荐页同款 web 推荐流
 *    （api.bilibili.com/.../feed/rcmd），保证永远有内容可刷，并在页面上说明已降级。
 * 4. **取流三级回退**：PC durl（带声音）→ HTML5 durl（带声音）→ DASH 视频流（最后手段）。
 *    旧实现只用 PC durl，一旦该视频没有 durl 就直接「加载失败」。
 */
object StoryApi {

    private const val STORY_FEED_URL = "https://app.bilibili.com/x/v2/feed/index/story"
    private const val WEB_RCMD_URL = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd"
    private const val PLAYURL_URL = "https://api.bilibili.com/x/player/wbi/playurl"

    // android 客户端密钥（与官方手机客户端一致，与 AppRcmdApi 手机身份相同）
    private const val APP_KEY = "1d8b6e7d45225436"
    private const val APP_SEC = "560c52ccd288fed045859ed18bffd973"
    private const val BUILD = 8430300
    private const val APP_UA =
        "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2"
    private const val APP_STATISTICS = "{\"appId\":1,\"platform\":3,\"version\":\"8.43.0\",\"abtest\":\"\"}"

    // 官方客户端指纹占位（与 RecommendApi 中 APP 端推荐请求保持一致）
    private const val FP =
        "1111111111111111111111111111111111111111111111111111111111111111"

    private val uniqRandom = Random()

    /** 一刷至少凑多少条视频 */
    private const val MIN_ITEMS = 4
    /** 最多连拉几刷（接口偶尔返回空批次时补拉） */
    private const val MAX_STORY_ROUNDS = 2

    /** 短视频卡片：VideoCard 的字段平铺 + cid 与竖屏时长（VideoCard 不是 open，用组合） */
    class StoryCard {
        @JvmField var aid: Long = 0L
        @JvmField var bvid: String? = null
        @JvmField var title: String? = null
        @JvmField var cover: String? = null
        @JvmField var upName: String? = null
        @JvmField var view: String? = null
        @JvmField var cid: Long = 0L
        @JvmField var durationSec: Int = 0
        /** 视频宽高（用于只保留竖屏视频；0 表示未知） */
        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
    }

    /** 一次拉流的结果：是否降级 + 降级原因（给 UI 显示，便于用户反馈） */
    class StoryFeedResult {
        @JvmField var fromFallback: Boolean = false
        @JvmField var warning: String = ""
    }

    /** 取流结果：url + 来源（pc/html5/dash）+ 清晰度 + 提示 */
    class StoryPlayInfo {
        @JvmField var url: String = ""
        @JvmField var source: String = "pc"
        @JvmField var quality: Int = 0
        @JvmField var note: String = ""

        /** 取流时间：B 站直链带时效（约 2 小时），缓存超过 20 分钟就重新解析 */
        @JvmField var fetchedAt: Long = System.currentTimeMillis()
    }

    // ============================ 拉流 ============================

    /**
     * 拉一刷短视频（每次返回新的一批，自带降级）。
     * 先走短视频专用接口；失败时退回 web 推荐流；两者都失败才抛异常。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun loadFeed(out: MutableList<StoryCard>): StoryFeedResult {
        val result = StoryFeedResult()
        var storyError = ""
        //短视频接口（/x/v2/feed/index/story）本身就是官方短视频流；
        //它混有竖屏与少量横屏视频，都保留（横屏在大屏设备上同样能看）。
        try {
            var guard = 0
            while (out.size < MIN_ITEMS && guard < MAX_STORY_ROUNDS) {
                guard++
                val batch = ArrayList<StoryCard>()
                val n = getStoryFeed(batch)
                if (n <= 0) break
                out.addAll(batch)
            }
            if (out.isNotEmpty()) return result
            storyError = "短视频接口没有返回可播放内容"
        } catch (e: Exception) {
            storyError = e.message ?: e.javaClass.simpleName
            Logu.e("story", "短视频接口失败：" + storyError)
            out.clear()
        }

        // 降级：推荐流（与推荐页同源，稳定性最高）
        try {
            val n = getFallbackFeed(out)
            if (n > 0 && out.isNotEmpty()) {
                result.fromFallback = true
                result.warning = "短视频接口不可用（" + storyError + "），已临时使用推荐流"
                Logu.e("story", result.warning)
                return result
            }
            storyError += "；推荐流也没有返回内容"
        } catch (e: Exception) {
            storyError += "；推荐流回退失败：" + (e.message ?: e.javaClass.simpleName)
            Logu.e("story", "推荐流回退失败：" + e.message)
            out.clear()
        }
        throw IOException(storyError)
    }

    /**
     * 短视频专用接口：/x/v2/feed/index/story
     * 先带手机端 access_key（有个性化）；失效（-101/-400/-352）自动匿名额。
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getStoryFeed(out: MutableList<StoryCard>): Int {
        val token = try {
            AppRcmdApi.getAccessToken(AppRcmdApi.IDENTITY_PHONE)
        } catch (e: Exception) {
            ""
        }

        var json = requestStoryFeed(token)
        var code = json.optInt("code", -1)

        // access_key 失效：去掉后匿名重试一次（旧版迁移过来的 token 很容易失效）
        if (code != 0 && token.isNotEmpty()) {
            Logu.e("story", "短视频接口返回 " + code + "，尝试匿名重试")
            json = requestStoryFeed("")
            code = json.optInt("code", -1)
        }

        if (code != 0) {
            throw IOException("短视频接口错误 " + code + "：" + json.optString("message", "未知错误"))
        }
        val data = json.optJSONObject("data") ?: throw IOException("短视频接口返回数据为空")
        val items = data.optJSONArray("items") ?: JSONArray()
        return parseStoryItems(items, out)
    }

    private fun requestStoryFeed(accessToken: String): JSONObject {
        val form = NetWorkUtil.FormData().setUrlParam(true)
            .put("appkey", APP_KEY)
            .put("build", BUILD)
            .put("mobi_app", "android")
            .put("platform", "android")
            .put("ts", (System.currentTimeMillis() / 1000L).toString())
            .put("fnval", 976)
            .put("fnver", 0)
            .put("fourk", 1)
            .put("qn", 80)
            .put("network", "wifi")
            .put("c_locale", "zh_CN")
            .put("s_locale", "zh_CN")
            .put("channel", "master")
            .put("device", "phone")
            .put("device_name", "android")
            .put("device_type", 0)
            .put("disable_rcmd", 0)
            .put("flush", 8)
            .put("force_host", 2)
            .put("guidance", 1)
            .put("https_url_req", 1)
            .put("player_net", 1)
            .put("recsys_mode", 0)
            .put("voice_balance", 0)
            .put("login_event", 0)
            .put("statistics", APP_STATISTICS)
        if (accessToken.isNotEmpty()) form.put("access_key", accessToken)
        form.put("sign", sign(form))

        val headers = arrayListOf(
            "buvid", AppRcmdApi.getBuvid(),
            "env", "prod",
            "app-key", "android",
            "User-Agent", APP_UA,
            "fp_local", FP,
            "fp_remote", FP,
            "session_id", "11111111",
            "bili-http-engine", "cronet"
        )
        return NetWorkUtil.getJson(STORY_FEED_URL + form, headers)
    }

    private fun parseStoryItems(items: JSONArray, out: MutableList<StoryCard>): Int {
        var added = 0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val goto = it.optString("goto")
            val cardGoto = it.optString("card_goto")

            // 只收视频卡片：抖音式竖屏（vertical_av）以及普通 av（降级/新版接口可能混合返回）
            val isVideo = goto == "vertical_av" || cardGoto == "vertical_av" ||
                    goto == "av" || cardGoto == "av" || goto == "story_av"
            if (!isVideo) continue

            val playerArgs = it.optJSONObject("player_args")
            var aid = playerArgs?.optLong("aid", 0L) ?: 0L
            if (aid <= 0) aid = it.optLong("param", 0L)
            if (aid <= 0) continue

            val card = StoryCard()
            card.aid = aid
            card.bvid = it.optString("bvid")
            card.title = it.optString("title")
            var cover = it.optString("cover")
            if (cover.isEmpty()) cover = it.optString("pic")
            if (cover.startsWith("http://")) cover = "https://" + cover.substring(7)
            card.cover = cover
            card.upName = it.optJSONObject("owner")?.optString("name") ?: ""
            val view = it.optJSONObject("stat")?.optLong("view", 0L) ?: 0L
            card.view = if (view > 0) StringUtil.toWan(view) + "观看" else ""
            card.cid = playerArgs?.optLong("cid", 0L) ?: it.optLong("cid", 0L)
            card.durationSec = it.optInt("duration", 0)
            val dim = it.optJSONObject("dimension")
            if (dim != null) {
                var w = dim.optInt("width", 0)
                var h = dim.optInt("height", 0)
                val rotate = dim.optInt("rotate", 0)
                if (rotate == 90 || rotate == 270) {
                    val t = w
                    w = h
                    h = t
                }
                card.width = w
                card.height = h
            }
            out.add(card)
            added++
        }
        return added
    }

    /**
     * 兜底流：推荐页同款 web 推荐接口（wbi 签名，走 Cookie）。
     * 返回结构与「推荐页」一致：data.item[]，字段 id(aid)/cid/bvid/pic/title/owner/stat/duration。
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getFallbackFeed(out: MutableList<StoryCard>): Int {
        val uniqId = 1300000000000L + (uniqRandom.nextDouble() * 200000000000L).toLong()
        val url = WEB_RCMD_URL + NetWorkUtil.FormData().setUrlParam(true)
            .put("web_location", 1430650)
            .put("feed_version", "V8")
            .put("homepage_ver", 1)
            .put("uniq_id", uniqId)
            .put("screen", "1100-2056")

        val json = NetWorkUtil.getJson(ConfInfoApi.signWBI(url), webHeadersSnapshot())
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw IOException("推荐流接口错误 " + code + "：" + json.optString("message", "未知错误"))
        }
        val data = json.optJSONObject("data") ?: throw IOException("推荐流返回数据为空")
        val items = data.optJSONArray("item") ?: JSONArray()

        var added = 0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.has("ad_info") && !it.isNull("ad_info")) continue
            val aid = it.optLong("id", 0L)
            if (aid <= 0) continue
            val bvid = it.optString("bvid")
            if (bvid.isEmpty() && aid <= 0) continue

            val card = StoryCard()
            card.aid = aid
            card.bvid = bvid
            card.title = it.optString("title")
            var cover = it.optString("pic")
            if (cover.startsWith("http://")) cover = "https://" + cover.substring(7)
            card.cover = cover
            card.upName = it.optJSONObject("owner")?.optString("name") ?: ""
            val view = it.optJSONObject("stat")?.optLong("view", 0L) ?: 0L
            card.view = if (view > 0) StringUtil.toWan(view) + "观看" else ""
            card.cid = it.optLong("cid", 0L)
            card.durationSec = it.optInt("duration", 0)
            val dim = it.optJSONObject("dimension")
            if (dim != null) {
                var w = dim.optInt("width", 0)
                var h = dim.optInt("height", 0)
                val rotate = dim.optInt("rotate", 0)
                if (rotate == 90 || rotate == 270) {
                    val t = w
                    w = h
                    h = t
                }
                card.width = w
                card.height = h
            }
            out.add(card)
            added++
        }
        return added
    }

    // ============================ 取流 ============================

    /**
     * 取播放直链（三级回退）：
     * pc durl（最佳，带声音）→ html5 durl（带声音，兼容性最好）→ dash（无声音，最后手段）。
     *
     * @param forceHtml5 跳过 PC 步骤，直接要 HTML5 直链（播放失败后的重新取流用）
     */
    @JvmStatic
    @Throws(IOException::class)
    fun resolvePlay(aid: Long, cid: Long, qn: Int, forceHtml5: Boolean = false): StoryPlayInfo {
        if (cid <= 0) throw IOException("缺少 cid，无法取流")

        val errors = StringBuilder()

        if (!forceHtml5) {
            try {
                val info = requestPlayUrl(aid, cid, qn, html5 = false, dash = false)
                if (info.url.isNotEmpty()) return info
            } catch (e: Exception) {
                appendError(errors, "PC线路", e)
            }
        }

        try {
            val info = requestPlayUrl(aid, cid, qn, html5 = true, dash = false)
            if (info.url.isNotEmpty()) return info
        } catch (e: Exception) {
            appendError(errors, "HTML5线路", e)
        }

        try {
            val info = requestPlayUrl(aid, cid, qn, html5 = false, dash = true)
            if (info.url.isNotEmpty()) return info
        } catch (e: Exception) {
            appendError(errors, "DASH线路", e)
        }

        throw IOException("取流失败（" + errors.toString() + "）")
    }

    private fun appendError(sb: StringBuilder, step: String, e: Exception) {
        if (sb.isNotEmpty()) sb.append("；")
        sb.append(step).append("：").append(e.message ?: e.javaClass.simpleName)
        Logu.e("story", "取流失败(" + step + ")：" + e.message)
    }

    @Throws(IOException::class, JSONException::class)
    private fun requestPlayUrl(aid: Long, cid: Long, qn: Int, html5: Boolean, dash: Boolean): StoryPlayInfo {
        val form = NetWorkUtil.FormData().setUrlParam(true)
            .put("avid", aid)
            .put("cid", cid)
            .put("qn", qn)
            .put("fnval", if (dash) 16 else 1)
            .put("fnver", 0)
            .put("voice_balance", 1)
            .put("gaia_source", "pre-load")
            .put("isGaiaAvoided", true)
        if (html5) {
            form.put("platform", "html5")
            form.put("high_quality", 1)
        } else {
            form.put("platform", "pc")
        }

        val url = ConfInfoApi.signWBI(PLAYURL_URL + form)
        val json = NetWorkUtil.getJson(url, webHeadersSnapshot())
        val code = json.optInt("code", -1)
        if (code != 0) {
            throw IOException("接口错误 " + code + "：" + json.optString("message", "未知错误"))
        }
        val data = json.optJSONObject("data") ?: throw IOException("返回数据为空")
        val info = StoryPlayInfo()
        info.quality = data.optInt("quality", 0)

        // 1) 普通 durl（最理想：单文件带声音）
        val durl = data.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            val first = durl.optJSONObject(0)
            val u = first?.optString("url") ?: ""
            if (u.isNotEmpty()) {
                info.url = u
                info.source = if (html5) "html5" else "pc"
                return info
            }
        }

        // 2) DASH 视频流（无声音，最后手段）
        if (dash) {
            val videos = data.optJSONObject("dash")?.optJSONArray("video")
            if (videos != null && videos.length() > 0) {
                var best: JSONObject? = null
                var bestQ = -1
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val id = v.optInt("id", 0)
                    if (id <= qn && id > bestQ) {
                        bestQ = id
                        best = v
                    }
                }
                if (best == null) {
                    //qn 比所有可选档位都低（如请求 6）：取**最小**的那一档，
                    //而不是默认的第 0 个（第 0 个往往是最高清）
                    var smallestQ = Int.MAX_VALUE
                    for (i in 0 until videos.length()) {
                        val v = videos.optJSONObject(i) ?: continue
                        val id = v.optInt("id", 0)
                        if (id in 1 until smallestQ) {
                            smallestQ = id
                            best = v
                        }
                    }
                }
                var u = best?.optString("baseUrl") ?: ""
                if (u.isEmpty()) u = best?.optString("base_url") ?: ""
                if (u.isNotEmpty()) {
                    info.url = u
                    info.source = "dash"
                    info.note = "仅取到 DASH 视频流，可能没有声音"
                    return info
                }
            }
        }

        throw IOException("未返回可播放地址")
    }

    /**
     * 把下一支视频预下载到本地（真正的"预加载"，切过去时直接播本地文件，不卡）。
     * 只预取前 [maxBytes] 字节；超过大小上限或下载失败都返回 false，调用方回退到直链。
     */
    @JvmStatic
    fun prefetchToFile(url: String, dest: java.io.File, maxBytes: Long = 12L * 1024 * 1024): Boolean {
        if (url.isEmpty()) return false
        if (dest.exists() && dest.length() > 0) return true
        val tmp = java.io.File(dest.absolutePath + ".part")
        var ok = false
        try {
            dest.parentFile?.mkdirs()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", NetWorkUtil.USER_AGENT_WEB)
                .header("Cookie", com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
                    .getString(com.RobinNotBad.BiliClient.util.SharedPreferencesUtil.cookies, "") ?: "")
                .build()
            val response = NetWorkUtil.getOkHttpInstance().newCall(request).execute()
            val body = response.body
            if (response.code != 200 || body == null) {
                Logu.e("story", "预下载失败 HTTP " + response.code)
                response.close()
                return false
            }
            val total = body.contentLength()
            if (total > maxBytes) {
                Logu.d("story", "预下载跳过：文件过大 " + total)
                response.close()
                return false
            }
            tmp.delete()
            var written = 0L
            try {
                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            if (written > maxBytes) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                //大小对得上才算完整（分块传输没有 Content-Length 时按已写完处理）
                ok = tmp.length() > 0 && (total <= 0 || tmp.length() >= total)
                if (ok) {
                    dest.delete()
                    ok = tmp.renameTo(dest)
                }
            } finally {
                response.close()
                if (!ok) tmp.delete()
            }
        } catch (e: Exception) {
            Logu.d("story", "预下载异常：" + e.message)
            tmp.delete()
        }
        if (ok) Logu.d("story", "已预下载：" + dest.name + " " + dest.length() / 1024 + "KB")
        return ok
    }

    private fun webHeadersSnapshot(): ArrayList<String> {
        // 复制一份，避免与 refreshHeaders 并发改同一个 ArrayList
        return try {
            ArrayList(NetWorkUtil.webHeaders)
        } catch (e: Exception) {
            arrayListOf("User-Agent", NetWorkUtil.USER_AGENT_WEB)
        }
    }

    /** APP 端签名：参数字典序 + appsec 取 MD5（与官方客户端一致） */
    @Throws(IOException::class)
    private fun sign(form: NetWorkUtil.FormData): String {
        val sorted = TreeMap<String, String>()
        val query = form.toString().removePrefix("?")
        if (query.isNotEmpty()) {
            for (pair in query.split("&")) {
                val idx = pair.indexOf('=')
                if (idx > 0) sorted[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }
        val sb = StringBuilder()
        for ((k, v) in sorted) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(k).append('=').append(v)
        }
        sb.append(APP_SEC)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        val out = StringBuilder()
        for (b in digest) out.append(String.format("%02x", b))
        return out.toString()
    }
}
