package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.net.URLEncoder
import java.util.ArrayList

//搜索API 自己写的
//逐渐感觉拆json是个很爽的事（
//2023-07-14

object SearchApi {

    @JvmField
    var seid: String = ""

    @JvmField
    var search_keyword: String = ""

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun search(keyword: String?, page: Int): JSONArray? {
        if (!search_keyword.equals(keyword)) {
            search_keyword = keyword ?: ""
            seid = ""
        }

        var url = "https://api.bilibili.com/x/web-interface/wbi/search/all/v2?"
        url += "page=" + page +
                "&keyword=" + URLEncoder.encode(search_keyword, "UTF-8") + "&seid=" + seid

        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)) // 得到一整个json

        if (all.isNull("data"))
            return null
        val data = all.getJSONObject("data") // 搜索列表中的data项又是一个json，把它提出来

        seid = data.getString("seid")

        if (data.has("result") && !data.isNull("result"))
            return data.getJSONArray("result") // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            return null
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun searchType(keyword: String?, page: Int, type: String?): Any? {
        if (!search_keyword.equals(keyword)) {
            search_keyword = keyword ?: ""
            seid = ""
        }

        var url = "https://api.bilibili.com/x/web-interface/wbi/search/type?"
        url += "page=" + page +
                "&keyword=" + URLEncoder.encode(search_keyword, "UTF-8") + "&search_type=" + type + "&seid=" + seid

        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)) // 得到一整个json

        if (all.isNull("data"))
            return null
        val data = all.getJSONObject("data") // 搜索列表中的data项又是一个json，把它提出来

        seid = data.getString("seid")

        if (data.has("result") && !data.isNull("result"))
            return data.get("result") // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            return null
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun getVideosFromSearchResult(input: JSONArray, videoCardList: ArrayList<VideoCard>, first: Boolean) {
        for (i in 0 until input.length()) { // 遍历所有的分类，找到视频那一项
            val typecard = input.getJSONObject(i)
            val type = typecard.getString("result_type")
            if (type == "video") {
                val data = typecard.getJSONArray("data") // 把这个列表提出来，接着拆
                for (j in 0 until data.length()) {
                    val card = data.getJSONObject(j) // 获得视频卡片
                    if (card.getString("type") != "video")
                        continue // 警惕虚假视频卡片（阿B为什么要把直播间放进视频结果里）

                    var title = card.getString("title")
                    title = title.replace("<em class=\"keyword\">", "").replace("</em>", "")
                    // 标题里的红字，知道怎么显示但因为懒所以直接删（//显示方式可以用imagespan，参照表情包那部分程序（EmoteUtil），期待后人补齐（
                    title = StringUtil.htmlToString(title)

                    val bvid = card.getString("bvid")
                    val aid = card.getLong("aid")
                    val cover = "http:" + card.getString("pic") // 离谱了嗷，前面甚至不肯加个http:
                    val upName = card.getString("author")

                    val play = card.getLong("play")
                    val playTimesStr = StringUtil.toWan(play) + "观看"

                    videoCardList.add(VideoCard(title, upName, playTimesStr, cover, aid, bvid, type))
                }
            } else if (type == "media_bangumi" && first) {
                val data = typecard.getJSONArray("data")
                for (j in 0 until data.length()) {
                    val card = data.getJSONObject(j) // 获得番剧卡片

                    var title = card.optString("title", "")
                    title = title.replace("<em class=\"keyword\">", "").replace("</em>", "")
                    // 标题里的红字,直接上面复制粘贴
                    title = StringUtil.htmlToString(title)
                    val cover = card.optString("cover", "")
                    // #5：getString 对 JSON null 返回字面量 "null"（areas/index_show 常缺省）
                    val upName = card.optString("areas", "")
                    val aid = card.optLong("media_id", 0L)
                    val bvid = card.optString("season_id", "")
                    val playTimesStr = card.optString("index_show", "")
                    videoCardList.add(VideoCard(title, upName, playTimesStr, cover, aid, bvid, type))
                }
            }
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun getUsersFromSearchResult(input: JSONArray, userInfoList: MutableList<UserInfo>) {
        for (i in 0 until input.length()) {
            val card = input.getJSONObject(i) // 获得用户卡片

            val mid = card.getLong("mid")
            val name = card.getString("uname")
            val avatar = "http:" + card.getString("upic")
            val sign = card.getString("usign")
            val fans = card.getInt("fans")
            val level = card.getInt("level")

            userInfoList.add(UserInfo(mid, name, avatar, sign, fans, 0, level, false, "", 0, "", 0))
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun getArticlesFromSearchResult(input: JSONArray, articleCardList: ArrayList<ArticleCard>) {
        for (i in 0 until input.length()) {
            val articleCard = ArticleCard()
            val card = input.getJSONObject(i) // 获得专栏卡片

            articleCard.id = card.getLong("id")
            if (card.getJSONArray("image_urls").length() > 0)
                articleCard.cover = "http:" + card.getJSONArray("image_urls").getString(0)
            else
                articleCard.cover = ""
            articleCard.upName = card.getString("category_name")
            articleCard.title = StringUtil.htmlReString(card.getString("title"))
            articleCard.view = StringUtil.toWan(card.getInt("view").toLong()) + "阅读"

            articleCardList.add(articleCard)
        }
    }

    /**
     * 获取搜索建议
     *
     * @param keyword 搜索关键词
     * @return 返回搜索建议列表，最多10条
     * @throws IOException   网络请求异常
     * @throws JSONException JSON解析异常
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getSearchSuggestions(keyword: String?): ArrayList<String> {
        val suggestions = ArrayList<String>()

        if (keyword == null || keyword.trim().isEmpty()) {
            return suggestions
        }

        val url = "https://s.search.bilibili.com/main/suggest?term=" + URLEncoder.encode(keyword, "UTF-8")

        val response = NetWorkUtil.getJson(url)

        if (response.getInt("code") == 0 && response.has("result")) {
            val result = response.getJSONObject("result")
            if (result.has("tag") && !result.isNull("tag")) {
                val tagArray = result.getJSONArray("tag")
                for (i in 0 until tagArray.length()) {
                    val tagObj = tagArray.getJSONObject(i)
                    val value = tagObj.getString("value")
                    suggestions.add(value)
                }
            }
        }

        return suggestions
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getDefaultSearchContent(): String? {
        val url = "https://api.bilibili.com/x/web-interface/wbi/search/default"
        val response = NetWorkUtil.getJson(ConfInfoApi.signWBI(url))
        if (response.getInt("code") == 0 && response.has("data")) {
            val data = response.getJSONObject("data")
            if (data.has("show_name") && !data.isNull("show_name")) {
                return data.getString("show_name")
            }
        }
        return null
    }
}
