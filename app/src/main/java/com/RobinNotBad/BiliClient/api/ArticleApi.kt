package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ArticleInfo
import com.RobinNotBad.BiliClient.model.Opus
import com.RobinNotBad.BiliClient.model.Stats
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONException
import org.json.JSONObject

import java.util.TimeZone
import java.io.IOException

/*
专栏API
API是自己扒的
 */
object ArticleApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getArticle(id: Long): ArticleInfo? {
        var url = "https://api.bilibili.com/x/article/view?"
        url += "id=" + id + "&gaia_source=main_web&web_location=333.976"
        val result = NetWorkUtil.getJson(ConfInfoApi.signWBI(url))
        if (!result.has("data")) return null
        val data = result.getJSONObject("data")

        val articleInfo = ArticleInfo()
        articleInfo.id = id
        articleInfo.title = data.getString("title")
        articleInfo.summary = data.getString("summary")
        articleInfo.banner = data.getString("banner_url")
        articleInfo.ctime = data.getLong("ctime")

        val author = data.getJSONObject("author")
        val upInfo = UserInfo()
        upInfo.mid = author.getLong("mid")
        upInfo.name = author.getString("name")
        upInfo.avatar = author.getString("face")
        upInfo.fans = author.getInt("fans")
        upInfo.level = author.getInt("level")
        articleInfo.upInfo = upInfo

        val jsonStats = data.getJSONObject("stats")
        val stats = Stats()
        stats.view = jsonStats.getInt("view")
        stats.favorite = jsonStats.getInt("favorite")
        stats.like = jsonStats.getInt("like")
        stats.reply = jsonStats.getInt("reply")
        stats.share = jsonStats.getInt("share")
        stats.coin = jsonStats.getInt("coin")
        stats.liked = data.getBoolean("is_like")

        articleInfo.stats = stats

        articleInfo.wordCount = data.getInt("words")
        articleInfo.content = data.getString("content")
        articleInfo.keywords = data.getString("keywords")
        return articleInfo
    }

    /**
     * 另一个获取专栏相关信息api
     *
     * @param id cvid
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getArticleViewInfo(id: Long): ArticleInfo? {
        var url = "https://api.bilibili.com/x/article/viewinfo?"
        url += "id=" + id + "&gaia_source=main_web&web_location=333.976&mobi_app=pc&from=web"
        val result = NetWorkUtil.getJson(ConfInfoApi.signWBI(url))
        if (!result.has("data")) return null
        val data = result.getJSONObject("data")

        val articleInfo = ArticleInfo()
        articleInfo.id = id
        articleInfo.title = data.getString("title")
        articleInfo.banner = data.getString("banner_url")

        val upInfo = UserInfo()
        upInfo.mid = data.getLong("mid")
        upInfo.name = data.getString("author_name")
        articleInfo.upInfo = upInfo

        val jsonStats = data.getJSONObject("stats")
        val stats = Stats()
        stats.view = jsonStats.getInt("view")
        stats.favorite = jsonStats.getInt("favorite")
        stats.like = jsonStats.getInt("like")
        stats.reply = jsonStats.getInt("reply")
        stats.share = jsonStats.getInt("share")
        stats.coin = jsonStats.getInt("coin")
        stats.liked = data.getInt("like") == 1
        stats.favoured = data.getBoolean("favorite")
        stats.coined = data.getInt("coin")
        articleInfo.stats = stats
        return articleInfo
    }

    /**
     * 专栏点赞
     *
     * @param cvid cvid
     * @param type true=点赞，false=取消赞
     * @return resultCode
     */
    @Throws(IOException::class)
    @JvmStatic
    fun like(cvid: Long, type: Boolean): Int {
        val url = "https://api.bilibili.com/x/article/like"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("id", cvid)
                .put("type", if (type) 1 else 2)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        try {
            return JSONObject(resp.body!!.string()).getInt("code")
        } catch (ignored: JSONException) {
            return -1
        }
    }

    /**
     * 专栏投币
     *
     * @param cvid     CVID
     * @param upid     UP主ID
     * @param multiply 投币数量
     * @return 返回码
     */
    @Throws(IOException::class)
    @JvmStatic
    fun addCoin(cvid: Long, upid: Long, multiply: Int): Int {
        val url = "https://api.bilibili.com/x/web-interface/coin/add"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("aid", cvid)
                .put("upid", upid)
                .put("avtype", 2)
                .put("multiply", multiply)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        try {
            return JSONObject(resp.body!!.string()).getInt("code")
        } catch (ignored: JSONException) {
            return -1
        }
    }

    /**
     * 收藏专栏
     *
     * @param cvid CVID
     * @return 返回码
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun favorite(cvid: Long): Int {
        val url = "https://api.bilibili.com/x/article/favorites/add"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("id", cvid)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        return JSONObject(resp.body!!.string()).getInt("code")
    }

    /**
     * 取消收藏专栏
     *
     * @param cvid CVID
     * @return 返回码
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun delFavorite(cvid: Long): Int {
        val url = "https://api.bilibili.com/x/article/favorites/del"
        val resp = NetWorkUtil.post(url, NetWorkUtil.FormData()
                .put("id", cvid)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
                .toString(), NetWorkUtil.webHeaders)!!
        return JSONObject(resp.body!!.string()).getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun opusId2cvid(opusId: Long): Opus {
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id=" + opusId + "&time_zone_offset=" + TimeZone.getDefault().getRawOffset() / 100000
        val result = NetWorkUtil.getJson(url)
        if (result.getJSONObject("data").has("item"))
            return Opus(Opus.TYPE_DYNAMIC, result.getJSONObject("data").getJSONObject("item").getJSONObject("basic").getString("rid_str").toLong())
        else
            return Opus(Opus.TYPE_ARTICLE, result.getJSONObject("data").getJSONObject("fallback").getString("id").toLong())
    }
}
