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

object RecommendApi {
    private val UNIQ_ID: Long = (Random().nextDouble() * (1500000000000L - 1300000000000L).toDouble()).toLong()

    @JvmStatic
    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    fun getRecommend(videoCardList: MutableList<VideoCard>) {
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
