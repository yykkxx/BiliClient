package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.security.MessageDigest
import java.util.TreeMap

/**
 * 短视频（Story）专用 API —— shortvideo 分支。
 *
 * 数据源是 B 站官方手机客户端（哔哩哔哩 9.13.0，见 app_api/哔哩哔哩_9.13.0.apk）
 * 短视频首页用的同一个接口：
 *
 *   GET https://app.bilibili.com/x/v2/feed/index/story
 *
 * 实测特性（curl 验证）：
 * - 每次请求返回 4 条左右的竖屏视频（goto=vertical_av）；
 * - **不需要游标**：不带 idx 直接重复请求，每次都返回不重复的新一批，
 *   天然适合“无限上滑刷”；
 * - 匿名可用（code=0）；带 access_key（手机端登录）可获得个性化推荐；
 * - item 里自带 aid(param)/bvid/cid(player_args)/封面/标题/UP主/播放量/时长。
 *
 * 请求按手机客户端方式签名（appkey+ts+sign，android 密钥，与官方手机端一致），
 * 并附带 AppRcmd 手机端登录的 access_key（若已登录手机端槽位）。
 */
object StoryApi {

    private const val STORY_FEED_URL = "https://app.bilibili.com/x/v2/feed/index/story"

    // android 客户端密钥（与官方手机客户端一致，与 AppRcmdApi 手机身份相同）
    private const val APP_KEY = "1d8b6e7d45225436"
    private const val APP_SEC = "560c52ccd288fed045859ed18bffd973"
    private const val BUILD = 8430300

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
    }

    /**
     * 拉一刷短视频（每次调用返回新的一批，无需传游标）。
     * @param out 收集列表（追加）
     * @return 成功条数；接口异常抛出
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getStoryFeed(out: MutableList<StoryCard>): Int {
        val form = NetWorkUtil.FormData()
                .put("appkey", APP_KEY)
                .put("build", BUILD)
                .put("mobi_app", "android")
                .put("platform", "android")
                .put("ts", (System.currentTimeMillis() / 1000L).toString())

        // 手机端登录槽位有 token 就带上（个性化推荐）；没有就匿名刷
        val accessToken = com.RobinNotBad.BiliClient.api.AppRcmdApi
                .getAccessToken(com.RobinNotBad.BiliClient.api.AppRcmdApi.IDENTITY_PHONE)
        if (accessToken.isNotEmpty()) form.put("access_key", accessToken)

        form.put("sign", sign(form))

        val headers = arrayListOf(
                "buvid", com.RobinNotBad.BiliClient.api.AppRcmdApi.getBuvid(),
                "env", "prod",
                "app-key", "android",
                "User-Agent",
                "Mozilla/5.0 BiliDroid/8.43.0 (bbcallen@gmail.com) os/android model/android mobi_app/android build/8430300 channel/master innerVer/8430300 osVer/15 network/2"
        )

        val result = NetWorkUtil.getJson(STORY_FEED_URL + form, headers)
        if (result.optInt("code", -1) != 0) {
            throw IOException("短视频接口返回错误：" + result.optString("message", "未知错误"))
        }

        val data = result.optJSONObject("data") ?: return 0
        val items = data.optJSONArray("items") ?: return 0

        var added = 0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            // 只收竖屏视频卡；广告/其他卡片直接跳过
            val goto = it.optString("goto")
            if (goto != "vertical_av") continue

            val card = StoryCard()
            card.aid = it.optLong("param", 0L)
            card.bvid = it.optString("bvid")
            card.title = it.optString("title")
            var cover = it.optString("cover")
            if (cover.startsWith("http://")) cover = "https://" + cover.substring(7)
            card.cover = cover
            card.upName = it.optJSONObject("owner")?.optString("name") ?: ""
            val view = it.optJSONObject("stat")?.optLong("view", 0L) ?: 0L
            card.view = com.RobinNotBad.BiliClient.util.StringUtil.toWan(view) + "观看"
            card.cid = it.optJSONObject("player_args")?.optLong("cid", 0L) ?: 0L
            card.durationSec = it.optInt("duration", 0)
            if (card.aid > 0) {
                out.add(card)
                added++
            }
        }
        return added
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
