package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.Logu
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

    /** 综合搜索（分类聚合）端点 */
    private const val SEARCH_ALL_URL = "https://api.bilibili.com/x/web-interface/wbi/search/all/v2?"

    /** 分类搜索端点（search_type=video/bili_user/article/live...） */
    private const val SEARCH_TYPE_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type?"

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

        val url = SEARCH_ALL_URL + buildSearchQuery(page, null)

        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)) // 得到一整个json

        val data = extractSearchData(all) ?: return null

        seid = data.optString("seid", seid)

        return if (data.has("result") && !data.isNull("result"))
            data.getJSONArray("result") // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            null
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun searchType(keyword: String?, page: Int, type: String?): Any? {
        if (!search_keyword.equals(keyword)) {
            search_keyword = keyword ?: ""
            seid = ""
        }

        val url = SEARCH_TYPE_URL + buildSearchQuery(page, type)

        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url)) // 得到一整个json

        val data = extractSearchData(all) ?: return null

        seid = data.optString("seid", seid)

        return if (data.has("result") && !data.isNull("result"))
            data.get("result") // 其实这还不是我们要的结果，下面的函数对它进行再次拆解 这里做了判空
        else
            null
    }

    /**
     * 拼搜索 query（all/v2 与 type 共用一个，差别只有 search_type）。
     *
     * v5.1.6：**不再带 `seid` 空参数**。原实现无条件拼 `&seid=`（首屏时 seid 为空串），
     * 服务端对这种「有键无值」的 seid 更容易判定为异常请求并回风控壳；首屏本来也不需要 seid。
     */
    private fun buildSearchQuery(page: Int, type: String?): String {
        val sb = StringBuilder()
        sb.append("page=").append(page)
        sb.append("&keyword=").append(URLEncoder.encode(search_keyword, "UTF-8"))
        if (type != null) sb.append("&search_type=").append(type)
        if (seid.isNotEmpty()) sb.append("&seid=").append(seid)
        return sb.toString()
    }

    /**
     * 从整包 JSON 里取出可用的 `data`，并把「风控空壳」与「正常空结果」都变成 null。
     *
     * 修复的线上问题（v5.1.6）：**账号管理里把「其它页面 API」选成 Android 端口后，
     * 搜索直接报 `No value for seid at DebugStrings.kt at DispatchedTask.kt`**。
     *
     * 根因是两个原因叠加：
     * 1. 走 APP 通道时服务端会返回**风控壳** —— `code=0`，但 `data` 只有一个 `v_voucher`，
     *    既没有 `seid` 也没有 `result`（本机实测：连续请求全部命中；同关键词走 web 的
     *    WBI 通道则正常带 seid/result。PiliPlus 的 `lib/http/search.dart` 同样专门判
     *    `vVoucher != null` → 「触发风控」，可作第三方佐证）。
     * 2. 原代码 `data.getString("seid")` 对缺失字段抛 `JSONException: No value for seid`；
     *    异常从后台线程冒出去，用户看到的就是「搜索打不开、直接报错」。
     *
     * 现在的处理：缺 `seid` 不再抛异常（用 `optString` 保留旧值），
     * 风控壳直接当「本次没有数据」返回 null，由列表页走正常的空态/失败分支。
     * 上层 [ApiRouter] 也会识别风控壳并回退 web 重试（见 `riskControlShellReason`），
     * 两层兜底：路由层能回退就回退，退不回来也不会崩。
     */
    private fun extractSearchData(all: JSONObject): JSONObject? {
        if (all.isNull("data")) return null
        val data = all.optJSONObject("data") ?: return null
        // 风控壳：只有校验票据、没有任何业务数据
        if (data.has("v_voucher") && !data.has("result") && !data.has("seid")) {
            return null
        }
        return data
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

        // v5.2.0（P2-3）：先试 WBI 签名的 `/x/web-interface/suggest`
        // （PiliPlus lib/http/search.dart:23-44 实证：term/highlight/spmid/web_location + WBI，
        // 读 data.result），结构不符/失败再退回历史 `s.search.bilibili.com/main/suggest`。
        try {
            readSuggestTags(NetWorkUtil.getJson(signedWebSuggestUrl(keyword)), suggestions)
        } catch (e: Exception) {
            // 网络/签名/结构异常都回退历史端点：联想是纯装饰功能，失败绝不能影响搜索输入
            Logu.d("search", "联想 WBI 通道失败，回退 s.search：" + e.javaClass.simpleName)
        }
        if (suggestions.isNotEmpty()) return suggestions

        // 回退：历史端点（web 匿名也可用，故这条兜底在 S4 纯匿名下同样有效）
        val url = "https://s.search.bilibili.com/main/suggest?term=" + URLEncoder.encode(keyword, "UTF-8")
        val response = NetWorkUtil.getJson(url)
        readSuggestTags(response, suggestions)

        return suggestions
    }

    /**
     * 构造 WBI 签名的联想 URL。
     *
     * 参数照 PiliPlus（`term` / `highlight=0` / `spmid=333.1365` / `web_location=333.1365`）。
     * `web_location` 属 `DROPPED_PARAMS`，但那是**路由层**的丢弃口径（APP 通道要剔 web 参数）；
     * 这里走的是 web 端点，必须带上，否则与文档参数不一致。
     */
    private fun signedWebSuggestUrl(keyword: String): String {
        val url = "https://api.bilibili.com/x/web-interface/suggest" +
                "?term=" + URLEncoder.encode(keyword, "UTF-8") +
                "&highlight=0&spmid=333.1365&web_location=333.1365"
        return ConfInfoApi.signWBI(url)
    }

    /**
     * 从回包取 `result.tag[].value` 追加到 [out]。
     *
     * 历史 `s.search` 与 `/x/web-interface/suggest` 的**键路径按调研报告推断一致**
     * （`data.result.tag[]`；注意新端点的 result 在 `data` 下，历史端点 result 在顶层，
     * 所以这里两个位置都找一遍，避免结构差异导致联想为空）。
     */
    private fun readSuggestTags(response: JSONObject, out: ArrayList<String>) {
        if (response.optInt("code", -1) != 0) return
        val result = response.optJSONObject("result")
                ?: response.optJSONObject("data")?.optJSONObject("result")
                ?: return
        val tagArray = result.optJSONArray("tag") ?: return
        for (i in 0 until tagArray.length()) {
            val value = tagArray.optJSONObject(i)?.optString("value", "") ?: ""
            if (value.isNotEmpty()) out.add(value)
        }
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
