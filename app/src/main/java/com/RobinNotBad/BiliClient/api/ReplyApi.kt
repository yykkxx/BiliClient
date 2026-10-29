package com.RobinNotBad.BiliClient.api

import android.text.TextUtils
import android.util.Log
import android.util.Pair

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.api.migration.ApiMigrationRules
import com.RobinNotBad.BiliClient.model.ContentType
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.Result
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException

//腕上哔哩那边注释里写了一连串的麻烦麻烦麻烦，顿时预感不妙
//其实还好
//用Log直接替代注释了，应该都能看懂吧
//2023-07-22

object ReplyApi {

    // Java 端 NoticeHolder 把这些常量用作 switch 的 case 标签，必须是 const val（有 ConstantValue 属性）
    const val REPLY_TYPE_VIDEO_CHILD = 0
    const val REPLY_TYPE_VIDEO = 1
    const val REPLY_TYPE_ARTICLE = 12
    const val REPLY_TYPE_DYNAMIC_CHILD = 11
    const val REPLY_TYPE_DYNAMIC = 17
    const val TOP_TIP = "[置顶]"

    /**
     * @param originId       评论区id，为评论所属内容的id，例如视频aid
     * @param rpid           父评论的id，无父评论则为0
     * @param pageNumber     分页，需要拉取的评论的页号
     * @param type           评论所属内容类型
     * @param sort           评论区排序方式，0=时间；1=点赞数量；2=回复数量
     * @param replyArrayList 填充数组
     * @return -1错误,0正常，1到底了
     * @throws JSONException json解析异常
     * @throws IOException   网络异常
     */
    @JvmSuppressWildcards
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getReplies(originId: Long, rpid: Long, pageNumber: Int, type: ContentType, sort: Int, replyArrayList: MutableList<Reply>): Int {

        val url = "https://api.bilibili.com/x/v2/reply" + (if (rpid == 0L) "" else "/reply") + "?pn=" + pageNumber +
                "&type=" + type.getTypeCode() + "&oid=" + originId + "&sort=" + sort + (if (rpid == 0L) "" else ("&root=" + rpid))
        val all = NetWorkUtil.getJson(url)

        //Log.e("debug-评论区",all.toString());

        val size = replyArrayList.size
        if (all.getInt("code") == 0 && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            val page = data.getJSONObject("page")
            if (!data.isNull("replies") && page.getInt("size") > 0) {
                if (rpid == 0L && data.has("top_replies") && page.getInt("num") == 1)
                    analyzeReplyArray(true, data.getJSONArray("top_replies"), replyArrayList)
                val replies = data.getJSONArray("replies")
                analyzeReplyArray(rpid == 0L, replies, replyArrayList)
                if (replyArrayList.size == size) return 1
                else return 0
            } else return 1
        } else return -1
    }

    @JvmStatic
    fun getRootReply(contentType: ContentType, originId: Long, rpid: Long): Result<Reply> {
        val url = "https://api.bilibili.com/x/v2/reply/reply" + "?type=" + contentType.getTypeCode() + "&oid=" + originId + "&root=" + rpid
        try {
            val json = NetWorkUtil.getJson(url)
            if (json.getInt("code") != 0 || json.isNull("data")) {
                return Result.failure<Reply>(Exception(json.getString("message")))
            }
            val data = json.getJSONObject("data")
            if (data.isNull("root")) {
                return Result.failure<Reply>(Exception("未找到根评论"))
            }
            return Result.success(Reply(true, data.getJSONObject("root")))
        } catch (e: Exception) {
            return Result.failure<Reply>(e)
        }
    }


    /**
     * 获取评论列表（/x/v2/reply/wbi/main）
     *
     * @param oid        评论区oid
     * @param rpid       指定要寻找的评论的rpid
     * @param pagination 页
     * @param type       评论区类型
     * @param sort       排序方式
     * @return 返回码（0=继续加载，1=到底了，-1=错误）与下一页的pagination
     */
    @JvmSuppressWildcards
    @NonNull
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getRepliesLazy(oid: Long, rpid: Long, pagination: String, type: Int, sort: Int, replyArrayList: MutableList<Reply>): Pair<Int, String> {

        // 全 API 迁移开启 + 当前 APP 身份已登录时，走 APP 的 pn/ps 分页端点。
        //
        // 为什么必须换端点：cursor 版 /x/v2/reply/wbi/main 在 APP 身份参数下实测 -352，
        // 而同族的 /x/v2/reply（pn/ps 版）实测 code=0，data 里有
        // page/config/replies/upper/top/vote，结构和 web 完全一致（详见
        // notes/api_full_migration_alpha10.md 的 H.2）。Reply 模型的解析器本来就是
        // 为这个端点写的，所以 analyzeReplyArray 一行都不用动。
        //
        // 为什么要求已登录：匿名下 pn>=2 实测返回空（page.num=0/count=0），web 与 APP
        // 两条通道都一样。这时如果硬走 pn 分页，第 2 页会被判成「到底了」，
        // 反而比 web cursor 更差。
        //
        // 为什么不处理 seek_rpid（定位某条评论）：pn 版没有 seek_rpid 语义
        // （定位需要先算出页码，服务端不支持），拿不准就保持 web cursor 分支。
        if (rpid <= 0L && ApiMigrationRules.isReplyAppPagingEnabled()) {
            return getRepliesByPage(oid, pagination, type, sort, replyArrayList)
        }

        val reqData = NetWorkUtil.FormData()
                .setUrlParam(true)
                .put("type", type)
                .put("oid", oid)
                .put("plat", 1)
                .put("web_location", "1315875")
                .put("mode", sort)
        reqData.put("pagination_str", JSONObject().put("offset", if (TextUtils.isEmpty(pagination)) "" else pagination))
        if (rpid > 0L) reqData.put("seek_rpid", rpid)
        val url = "https://api.bilibili.com/x/v2/reply/wbi/main" + reqData

        //Log.e("debug-评论区链接", url);

        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url))

        //Log.e("debug-评论区",all.toString());

        if (all.getInt("code") == 0 && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            val cursor = data.getJSONObject("cursor")
            if (!data.isNull("replies") && data.getJSONArray("replies").length() > 0) {
                if (rpid <= 0L && data.has("top_replies") && !data.isNull("top_replies") && cursor.getBoolean("is_begin"))
                    analyzeReplyArray(true, data.getJSONArray("top_replies"), replyArrayList)
                val replies = data.getJSONArray("replies")
                analyzeReplyArray(true, replies, replyArrayList)
                val paginationReply = cursor.optJSONObject("pagination_reply")
                val nextOffset: String? = if (paginationReply == null) null else paginationReply.optString("next_offset")
                if (cursor.optBoolean("is_end", false) || TextUtils.isEmpty(nextOffset)) {
                    return Pair(1, "")
                } else {
                    // 能走到这里 nextOffset 一定非空（TextUtils.isEmpty(null) 为 true）
                    return Pair(0, nextOffset!!)
                }
            } else if (rpid <= 0L && data.has("top_replies") && !data.isNull("top_replies") && cursor.getBoolean("is_begin")) {
                analyzeReplyArray(true, data.getJSONArray("top_replies"), replyArrayList)
                return Pair(1, "")
            } else return Pair(1, "")
        } else return Pair(-1, "")
    }  //-1错误,0正常，1到底了

    /** APP pn/ps 分页时的每页条数（实测 ps=30 → -400，20 是安全上限） */
    private const val REPLY_PAGE_SIZE = 20

    /**
     * APP 通道的 pn/ps 分页实现（`/x/v2/reply`）。
     *
     * `pagination` 在这个模式下**当页码载体**用：空串 = 第 1 页，之后是 "2"、"3"……
     * 返回值语义与 cursor 版保持一致：`0`=还能加载（第二个值是下一页页码）、`1`=到底了、`-1`=错误。
     *
     * 实测要点（notes 的 H.2）：
     * - `sort` 必须 > 0，传 0 会返回空列表（page.num=0）→ 这里 `<=0` 一律兜底成 2；
     * - `ps` 上限 20，30 → -400；
     * - `data.page = {num,size,count,acount}`，`num` 就是当前页码；
     * - 置顶评论在 `data.top.replies`（第一页才有）。
     */
    @JvmSuppressWildcards
    @NonNull
    @Throws(JSONException::class, IOException::class)
    private fun getRepliesByPage(oid: Long, pagination: String, type: Int, sort: Int, replyArrayList: MutableList<Reply>): Pair<Int, String> {
        val page = if (TextUtils.isEmpty(pagination)) 1 else (pagination.toIntOrNull() ?: 1)
        val reqData = NetWorkUtil.FormData()
                .setUrlParam(true)
                .put("type", type)
                .put("oid", oid)
                .put("pn", page)
                .put("ps", REPLY_PAGE_SIZE)
                .put("sort", if (sort <= 0) 2 else sort)
        val url = "https://api.bilibili.com/x/v2/reply" + reqData

        val all = NetWorkUtil.getJson(url)
        if (all.optInt("code", -1) != 0 || all.isNull("data")) return Pair(-1, "")
        val data = all.getJSONObject("data")

        val replies = data.optJSONArray("replies")
        if (page == 1) appendTopReplies(data, replyArrayList)
        if (replies == null || replies.length() == 0) return Pair(1, "")
        analyzeReplyArray(true, replies, replyArrayList)

        val pageObj = data.optJSONObject("page") ?: return Pair(1, "")
        val num = pageObj.optInt("num", 0)
        val size = pageObj.optInt("size", REPLY_PAGE_SIZE)
        val count = pageObj.optInt("count", 0)
        // num<=0 / count<=0 说明服务端没给有效分页信息，按「到底了」收尾，避免无限拉取
        val noPagingInfo = num <= 0 || count <= 0 || size <= 0
        if (noPagingInfo || num * size >= count) return Pair(1, "")
        return Pair(0, (num + 1).toString())
    }

    /** 追加 pn 版的置顶评论（`data.top.replies`，与 web cursor 版的 `top_replies` 同义） */
    @JvmSuppressWildcards
    @Throws(JSONException::class)
    private fun appendTopReplies(data: JSONObject, replyArrayList: MutableList<Reply>) {
        val top = data.optJSONObject("top") ?: return
        val topReplies = top.optJSONArray("replies") ?: return
        if (topReplies.length() == 0) return
        analyzeReplyArray(true, topReplies, replyArrayList)
    }

    @JvmSuppressWildcards
    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeReplyArray(isRoot: Boolean, replies: JSONArray, replyArrayList: MutableList<Reply>) {
        for (i in 0 until replies.length()) {
            val reply = replies.getJSONObject(i)
            val replyReturn = Reply(isRoot, reply)
            replyArrayList.add(replyReturn)
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun sendReply(oid: Long, root: Long, parent: Long, text: String, type: Int): Pair<Int, Reply?> {
        val url = "https://api.bilibili.com/x/v2/reply/add"
        val arg = "oid=" + oid + "&type=" + type + (if (root == 0L) "" else ("&root=" + root + "&parent=" + parent)) +
                "&message=" + text + "&jsonp=jsonp&csrf=" + (SharedPreferencesUtil.getString("csrf", "") ?: "")
        val result = JSONObject(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-发送评论", result.toString())
        var reply: JSONObject? = null
        if (result.has("data") && !result.isNull("data") && result.getJSONObject("data").has("reply") && !result.getJSONObject("data").isNull("reply")) {
            reply = result.getJSONObject("data").getJSONObject("reply")
        }
        return Pair(result.getInt("code"), if (reply == null) null else Reply(root != 0L, reply))
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun sendReply(oid: Long, root: Long, parent: Long, text: String): Pair<Int, Reply?> {
        return sendReply(oid, root, parent, text, REPLY_TYPE_VIDEO)
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun sendDynamicReply(oid: Long, root: Long, parent: Long, text: String): Pair<Int, Reply?> {
        return sendReply(oid, root, parent, text, REPLY_TYPE_DYNAMIC)
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun likeReply(oid: Long, root: Long, action: Boolean): Int {
        val url = "https://api.bilibili.com/x/v2/reply/action"
        val arg = "oid=" + oid + "&type=1&rpid=" + root + "&action=" + (if (action) "1" else "0") + "&jsonp=jsonp&csrf=" + (SharedPreferencesUtil.getString("csrf", "") ?: "")
        val result = JSONObject(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-点赞评论", result.toString())
        return result.getInt("code")
    }

    /**
     * 删除评论
     *
     * @param oid  oid
     * @param rpid rpid
     * @param type 评论区类型
     * @return 返回码
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun deleteReply(oid: Long, rpid: Long, type: Int): Int {
        val url = "https://api.bilibili.com/x/v2/reply/del"
        val reqBody = NetWorkUtil.FormData()
                .put("type", type)
                .put("oid", oid)
                .put("rpid", rpid)
                .put("csrf", SharedPreferencesUtil.getString("csrf", "") ?: "")
                .toString()
        val result = JSONObject(NetWorkUtil.post(url, reqBody, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-点赞评论", result.toString())
        return result.getInt("code")
    }

    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getReplyCount(oid: Long, type: Int): Long {
        val url = "https://api.bilibili.com/x/v2/reply/count?oid=" + oid + "&type=" + type
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && (!all.isNull("data"))) {
            return all.getJSONObject("data").getLong("count")
        }
        return 0
    }
}
