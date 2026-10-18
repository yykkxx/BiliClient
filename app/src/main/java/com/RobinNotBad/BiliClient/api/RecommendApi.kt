package com.RobinNotBad.BiliClient.api

import android.text.TextUtils
import android.util.Log

import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.Random


//推荐API 自己写的
//#如果想要增/删/改内容，可以直接把url复制到浏览器里，把得到的一大串东西交给json解析软件就能缕清结构了，然后就是拆拆拆
//2023-07-12
//2023-12-09
//2026-10-18 新增「移动端推荐逻辑」（实验性）：开启后推荐页改走 APP 端接口
//app.bilibili.com/x/v2/feed/index（请求参数/请求头/过滤逻辑参照 PiliPlus 的 rcmdVideoListApp），
//默认关闭，关闭时与旧版完全一致（web 端 wbi 接口）。
//APP 端返回里 bvid 恒为 null、播放量是现成的格式化文本（如"103.2万"），封面是 http 链接。

object RecommendApi {
    private val UNIQ_ID: Long = (Random().nextDouble() * (1500000000000L - 1300000000000L).toDouble()).toLong()

    //APP端推荐接口
    private const val APP_RCMD_URL = "https://app.bilibili.com/x/v2/feed/index"
    private const val APP_STATISTICS = "{\"appId\":1,\"platform\":3,\"version\":\"8.43.0\",\"abtest\":\"\"}"
    private const val FP = "1111111111111111111111111111111111111111111111111111111111111111"

    //APP端推荐的 idx 游标：刷新归零（pull=true），加载更多用当前值（pull=false），
    //成功后前移；对应 PiliPlus 的 fresh_idx / pull 语义。
    private var rcmdIdx = 0

    @JvmStatic
    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    fun getRecommend(videoCardList: MutableList<VideoCard>, isRefresh: Boolean) {
        if (AppRcmdApi.isEnabled()) {
            getRecommendApp(videoCardList, isRefresh)
        } else {
            getRecommendWeb(videoCardList)
        }
    }

    /** 原有 web 端推荐逻辑（与旧版一致） */
    @JvmStatic
    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    private fun getRecommendWeb(videoCardList: MutableList<VideoCard>) {
        var url = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd"
        url += NetWorkUtil.FormData().setUrlParam(true)
            .put("web_location", 1430650)
            .put("feed_version", "V8")
            .put("homepage_ver", 1)
            .put("uniq_id", UNIQ_ID)
            .put("screen", "1100-2056")

        val result = NetWorkUtil.getJson(ConfInfoApi.signWBI(url))  //得到一整个json

        val data = result.getJSONObject("data")  //推荐列表中的data项又是一个json，把它提出来

        val item = data.getJSONArray("item")  //data里面的items是视频卡片列表，把它提出来

        for (i in 0 until item.length()) {    //遍历所有的视频卡片
            val card = item.getJSONObject(i)
            val bvid = card.getString("bvid")    //bv号
            if (TextUtils.isEmpty(bvid)) {
                Log.d("BiliClient", "RecommendApi getRecommend: isAd")
                continue
            }
            val cover = card.getString("pic")    //封面图片
            val title = card.getString("title")    //标题
            val upName = card.getJSONObject("owner").getString("name")  //up主名字
            val view = StringUtil.toWan(card.getJSONObject("stat").getInt("view").toLong()) + "观看"    //播放量
            videoCardList.add(VideoCard(title, upName, view, cover, 0, bvid))
        }
    }

    /**
     * APP 端推荐逻辑（实验性，参照 PiliPlus rcmdVideoListApp）。
     * 已完成移动端登录时附带 access_key（登录态推荐），未登录时匿名获取；
     * access_key 失效（-101）自动降级为匿名重试一次，保证推荐页可用。
     */
    @JvmStatic
    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    private fun getRecommendApp(videoCardList: MutableList<VideoCard>, isRefresh: Boolean) {
        if (isRefresh) rcmdIdx = 0

        var accessToken = AppRcmdApi.getAccessToken()
        var result = requestAppRcmd(accessToken)

        //access_key 失效：清掉本地移动端登录信息，匿名重试一次
        if (result.optInt("code", -1) == -101 && accessToken.isNotEmpty()) {
            Log.d("BiliClient", "RecommendApi getRecommendApp: access_key 失效，降级为匿名")
            AppRcmdApi.logout()
            accessToken = ""
            result = requestAppRcmd(accessToken)
        }

        if (result.optInt("code", -1) != 0) {
            throw IOException("推荐接口返回错误：" + result.optString("message", "未知错误"))
        }

        val data = result.optJSONObject("data") ?: throw JSONException("推荐接口返回数据为空")
        val item = data.optJSONArray("items") ?: JSONArray()

        for (i in 0 until item.length()) {
            val card = item.optJSONObject(i) ?: continue

            //过滤广告与无法播放的卡片（参照 PiliPlus _filterAppRcmd）
            val cardGoto = card.optString("card_goto")
            if (cardGoto != "av") {    //只保留普通视频；bangumi 等卡片的 param 不是 aid，本客户端打不开
                Log.d("BiliClient", "RecommendApi getRecommendApp: skip card_goto=$cardGoto")
                continue
            }
            if (card.has("ad_info") && !card.isNull("ad_info")) {
                Log.d("BiliClient", "RecommendApi getRecommendApp: isAd")
                continue
            }
            if (card.optInt("can_play", 0) != 1) continue

            val playerArgs = card.optJSONObject("player_args")
            val argsAid = playerArgs?.optLong("aid", 0L) ?: 0L
            val aid = if (argsAid != 0L) argsAid else card.optString("param").toLongOrNull() ?: 0L
            if (aid == 0L) continue

            var cover = card.optString("cover")
            if (cover.startsWith("http://")) cover = "https://" + cover.substring(7)    //统一走https

            val viewText = card.optString("cover_left_text_1")    //APP端给的是现成的格式化文本（如"103.2万"）
            val view = if (viewText.isEmpty()) "" else viewText + "观看"

            val videoCard = VideoCard(
                card.optString("title"),
                card.optJSONObject("args")?.optString("up_name") ?: "",
                view, cover, aid, card.optString("bvid")
            )
            videoCard.cid = playerArgs?.optLong("cid", 0L) ?: 0L
            videoCardList.add(videoCard)
        }

        rcmdIdx++    //成功后游标前移；失败不前移，重试仍是同一页
    }

    private fun requestAppRcmd(accessToken: String): JSONObject {
        val formData = NetWorkUtil.FormData().setUrlParam(true)
            .put("build", 8430300)
            .put("c_locale", "zh_CN")
            .put("channel", "master")
            .put("column", 2)
            .put("device", "phone")
            .put("device_name", "android")
            .put("device_type", 0)
            .put("disable_rcmd", 0)
            .put("flush", 8)
            .put("fnval", 976)
            .put("fnver", 0)
            .put("force_host", 2)   //使用https
            .put("fourk", 1)
            .put("guidance", 1)
            .put("https_url_req", 1)
            .put("idx", rcmdIdx)
            .put("mobi_app", "android_i")
            .put("network", "wifi")
            .put("platform", "android")
            .put("player_net", 1)
            .put("pull", if (rcmdIdx == 0) "true" else "false")
            .put("qn", 32)
            .put("recsys_mode", 0)
            .put("s_locale", "zh_CN")
            .put("splash_id", "")
            .put("statistics", APP_STATISTICS)
            .put("voice_balance", 0)
        if (accessToken.isNotEmpty()) formData.put("access_key", accessToken)

        //PiliPlus 对 app 域名请求一律签名（appkey+ts+sign），与官方客户端行为一致
        AppRcmdApi.sign(formData)

        //APP端请求头（buvid 识别设备）；app 请求不走 Cookie，登录态由 access_key 表达
        val headers = AppRcmdApi.appHeaders()
        headers.add("fp_local")
        headers.add(FP)
        headers.add("fp_remote")
        headers.add(FP)
        headers.add("session_id")
        headers.add("11111111")
        headers.add("bili-http-engine")
        headers.add("cronet")

        return NetWorkUtil.getJson(APP_RCMD_URL + formData, headers)
    }

    @JvmStatic
    @Throws(JSONException::class, IOException::class)
    fun getRelated(aid: Long): ArrayList<VideoCard> {
        val url = "https://api.bilibili.com/x/web-interface/archive/related?aid=" + aid

        val result = NetWorkUtil.getJson(url)  //得到一整个json

        val videoList = ArrayList<VideoCard>()
        if (result.has("data") && !result.isNull("data")) {
            val data = result.getJSONArray("data")
            for (i in 0 until data.length()) {
                val card = data.getJSONObject(i)
                val videoCard = VideoCard()
                videoCard.aid = card.getLong("aid")
                videoCard.view = StringUtil.toWan(card.getJSONObject("stat").getLong("view")) + "观看"
                videoCard.cover = card.getString("pic")
                videoCard.title = card.getString("title")
                videoCard.upName = card.getJSONObject("owner").getString("name")
                videoList.add(videoCard)
            }

        }

        return videoList
    }

    @JvmStatic
    @JvmSuppressWildcards
    @Throws(JSONException::class, IOException::class)
    fun getPopular(videoCardList: MutableList<VideoCard>, page: Int) {
        //热门接口在携带Cookie时返回的数据的排行是个性化的

        val url = "https://api.bilibili.com/x/web-interface/popular?pn=" + page + "&ps=10"

        val result = NetWorkUtil.getJson(url)  //得到一整个json

        if (result.has("data") && !result.isNull("data")) {
            if (result.getJSONObject("data").has("list")) {
                val list = result.getJSONObject("data").getJSONArray("list")
                for (i in 0 until list.length()) {
                    val card = list.getJSONObject(i)
                    val videoCard = VideoCard()
                    videoCard.aid = card.getLong("aid")
                    videoCard.cover = card.getString("pic")
                    videoCard.title = card.getString("title")
                    videoCard.upName = card.getJSONObject("owner").getString("name")
                    videoCard.view = StringUtil.toWan(card.getJSONObject("stat").getLong("view")) + "观看"
                    videoCardList.add(videoCard)
                }
            }
        }
    }

    @JvmStatic
    @JvmSuppressWildcards
    @Throws(JSONException::class, IOException::class)
    fun getPrecious(videoCardList: MutableList<VideoCard>, page: Int) {
        //热门接口在携带Cookie时返回的数据的排行是个性化的

        val url = "https://api.bilibili.com/x/web-interface/popular/precious?page=" + page + "&page_size=10"

        val result = NetWorkUtil.getJson(url)  //得到一整个json

        if (result.has("data") && !result.isNull("data")) {
            if (result.getJSONObject("data").has("list")) {
                val list = result.getJSONObject("data").getJSONArray("list")
                for (i in 0 until list.length()) {
                    val card = list.getJSONObject(i)
                    val videoCard = VideoCard()
                    videoCard.aid = card.getLong("aid")
                    videoCard.cover = card.getString("pic")
                    videoCard.title = card.getString("title")
                    videoCard.upName = card.getJSONObject("owner").getString("name")
                    videoCard.view = StringUtil.toWan(card.getJSONObject("stat").getLong("view")) + "观看"
                    videoCardList.add(videoCard)
                }
            }
        }
    }
}
