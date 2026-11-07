package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList

//稍后再看API
//2023-08-17

object WatchLaterApi {

    /**
     * 把「可能只有 bvid 的卡片」解析出 aid（v5.3.0）。
     *
     * # 为什么需要这一步
     * web 推荐端口给的卡片历史上只有 bvid 没有 aid（`RecommendApi.getRecommendWeb` 一律传 0），
     * 而稍后再看的两个写端点（`/x/v2/history/toview/add` / `del`）只认 **aid**。
     * 于是长按加入稍后再看必然走不通 —— 表现为「长按没反应」。
     *
     * v5.3.0 已经让 `getRecommendWeb` 带上 aid（web 返回里的 `id` 字段就是 aid，已实测核对），
     * 但**不能只靠那一处**：卡片还可能来自收藏夹/历史/搜索等其它构造点，其中仍有只填 bvid 的。
     * 所以这里补一条通用兜底，任何调用点都能安全降级。
     *
     * @param aid  卡片已知的 aid；> 0 时**直接返回，不发网络请求**
     * @param bvid 卡片的 bvid；aid 无效时用它反查
     * @return 解析出的 aid；无法解析返回 0（调用方据此提示用户，不要静默）
     */
    @JvmStatic
    fun resolveAid(aid: Long, bvid: String?): Long {
        if (aid > 0L) return aid
        if (bvid.isNullOrBlank()) return 0L
        return try {
            // 走 NetWorkUtil 而不是自己拼请求：这样它会经 ApiRouter 按「其它页面 API」端口
            // 路由到 APP 通道（APP 通道同样支持按 bvid 查），与全 App 的凭据口径保持一致。
            val result = NetWorkUtil.getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid)
            if (result.optInt("code", -1) != 0) return 0L
            result.optJSONObject("data")?.optLong("aid", 0L) ?: 0L
        } catch (ignored: IOException) {
            0L
        } catch (ignored: JSONException) {
            0L
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getWatchLaterList(): ArrayList<VideoCard> {
        val url = "https://api.bilibili.com/x/v2/history/toview/web"

        val result = NetWorkUtil.getJson(url)
        val data = result.getJSONObject("data")


        val videoCardList = ArrayList<VideoCard>()
        if (!data.isNull("list")) {
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val videoCard = list.getJSONObject(i)
                val aid = videoCard.getLong("aid")
                val bvid = videoCard.getString("bvid")
                val title = videoCard.getString("title")
                val cover = videoCard.getString("pic")
                val upName = videoCard.getJSONObject("owner").getString("name")
                val view = videoCard.getJSONObject("stat").getLong("view")
                val viewStr = StringUtil.toWan(view) + "观看"
                videoCardList.add(VideoCard(title, upName, viewStr, cover, aid, bvid))
            }
        }
        return videoCardList
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun delete(aid: Long): Int {
        val url = "https://api.bilibili.com/x/v2/history/toview/del"
        val per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)

        val result = JSONObject(response.body!!.string())

        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun add(aid: Long): Int {
        val url = "https://api.bilibili.com/x/v2/history/toview/add"
        val per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val response = NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)

        val result = JSONObject(response.body!!.string())

        return result.getInt("code")
    }
}
