package com.RobinNotBad.BiliClient.api

import android.util.Log
import android.util.Pair

import com.RobinNotBad.BiliClient.model.Collection
import com.RobinNotBad.BiliClient.model.FavoriteFolder
import com.RobinNotBad.BiliClient.model.Opus
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONObject

import java.util.ArrayList
import org.json.JSONException
import java.io.IOException

//收藏API

object FavoriteApi {
    // TODO 合集收藏

    /** 新版 v3 收藏夹列表接口的每页数量 */
    private const val FOLDER_PAGE_SIZE = 20

    /** 新版 v3 收藏夹列表接口最多翻页数（20 * 5 = 100 个收藏夹，防止接口异常时死循环） */
    private const val FOLDER_MAX_PAGE = 5

    /** 新版 v3 收藏夹内容接口的每页数量 */
    private const val FOLDER_VIDEO_PAGE_SIZE = 20

    /** 默认收藏夹的名称（v3 接口里默认收藏夹的 fid 为 0，个别账号会直接返回这个标题） */
    private const val DEFAULT_FOLDER_NAME = "默认收藏夹"

    /**
     * 由 fid 推算视频收藏夹的 media_id。
     *
     * B 站的 media_id 生成规则是 `fid * 100 + mid 后两位数字`：
     * 例如 mid=10330740（后两位 40）、fid=22202432 → media_id=2220243240，
     * 与 v3 接口返回的 `id` 字段完全一致（已实测比对）。
     *
     * 旧代码用 `fid.toString() + mid后两位` 的字符串拼接，本质是同一件事，
     * 但 mid 不足两位时会 `StringIndexOutOfBoundsException`，这里改成数值运算并兜底。
     * fid 为 0（默认收藏夹）时 media_id 就是 mid 的后两位。
     */
    @JvmStatic
    fun folderMediaId(mid: Long, fid: Long): Long {
        if (mid <= 0) return 0
        val suffix = mid % 100
        return if (fid <= 0) suffix else fid * 100 + suffix
    }

    /**
     * 获取用户的视频收藏夹列表。
     *
     * 【为什么要改】旧实现请求 `space.bilibili.com/ajax/fav/getBoxList?mid=`。
     * 实测该接口已经废弃：HTTP 200、`status:true`、但 `data.list` 恒为空数组
     * （用有公开收藏夹的账号 mid=10330740 实测同样是空），既不报错也不抛异常。
     * 于是 [getFavoriteFolders] 返回空列表 → 收藏夹页上只剩那个人造的
     * 「图文收藏夹」入口 → 用户点进来看到的就是图文收藏，这就是
     * 「长按收藏 / 个人中心 → 收藏，进去是图文收藏夹」的根因。
     *
     * 【现在】优先走新版 v3 接口 `x/v3/fav/folder/created/list`（带封面、可分页），
     * 失败退到 `x/v3/fav/folder/created/list-all`（一次性拿全、无封面），
     * 最后才用已失效的旧接口兜底（万一以后又恢复）。三者都拿不到时抛 IOException，
     * 由页面显示「加载失败 + 下拉重试」，绝不再静默返回空列表让用户以为没有视频收藏夹。
     *
     * @return 收藏夹列表（按默认收藏夹在前的顺序，接口原序）
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFavoriteFolders(mid: Long): ArrayList<FavoriteFolder> {
        if (mid <= 0) return ArrayList()

        var lastError: Exception? = null

        // 1) 新版 v3 分页接口（含封面）
        try {
            val folderList = getFavoriteFoldersV3(mid)
            if (folderList.isNotEmpty()) return folderList
        } catch (e: Exception) {
            lastError = e
            Log.e("debug-收藏夹v3", "created/list 失败：" + e.message)
        }

        // 2) 新版 v3 全量接口（无封面，但一次请求拿全）
        try {
            val folderList = getFavoriteFoldersV3All(mid)
            if (folderList.isNotEmpty()) return folderList
        } catch (e: Exception) {
            lastError = e
            Log.e("debug-收藏夹v3", "created/list-all 失败：" + e.message)
        }

        // 3) 旧的 space 接口（已失效、恒返回空列表，仅作最后兜底）
        try {
            val folderList = getFavoriteFoldersLegacy(mid)
            if (folderList.isNotEmpty()) return folderList
        } catch (e: Exception) {
            lastError = e
            Log.e("debug-收藏夹旧接口", "getBoxList 失败：" + e.message)
        }

        if (lastError != null) throw IOException("收藏夹列表获取失败：" + lastError.message, lastError)
        return ArrayList()
    }

    /** 新版 v3 分页接口：api.bilibili.com/x/v3/fav/folder/created/list */
    private fun getFavoriteFoldersV3(mid: Long): ArrayList<FavoriteFolder> {
        val folderList = ArrayList<FavoriteFolder>()
        var page = 1
        var hasMore = true
        while (hasMore && page <= FOLDER_MAX_PAGE) {
            val url = "https://api.bilibili.com/x/v3/fav/folder/created/list?up_mid=" + mid +
                    "&pn=" + page + "&ps=" + FOLDER_PAGE_SIZE
            val result = NetWorkUtil.getJson(url)
            val code = result.optInt("code", -1)
            if (code != 0) throw IOException("收藏夹接口返回 code=" + code)
            // 未登录 / 登录态失效 / 对方收藏夹全为私密时 data 为 null（code 仍是 0）
            val data = result.optJSONObject("data")
            val list = data?.optJSONArray("list")
            if (list == null) break
            for (i in 0 until list.length()) {
                folderList.add(parseV3Folder(mid, list.getJSONObject(i)))
            }
            hasMore = data.optBoolean("has_more", false)
            page++
        }
        if (folderList.isNotEmpty()) Log.e("debug-收藏夹", "v3 分页接口取到 " + folderList.size + " 个收藏夹")
        return folderList
    }

    /** 新版 v3 全量接口：api.bilibili.com/x/v3/fav/folder/created/list-all（无封面字段） */
    private fun getFavoriteFoldersV3All(mid: Long): ArrayList<FavoriteFolder> {
        val folderList = ArrayList<FavoriteFolder>()
        val url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=" + mid
        val result = NetWorkUtil.getJson(url)
        val code = result.optInt("code", -1)
        if (code != 0) throw IOException("收藏夹全量接口返回 code=" + code)
        val data = result.optJSONObject("data") ?: return folderList
        val list = data.optJSONArray("list") ?: return folderList
        for (i in 0 until list.length()) {
            folderList.add(parseV3Folder(mid, list.getJSONObject(i)))
        }
        if (folderList.isNotEmpty()) Log.e("debug-收藏夹", "v3 全量接口取到 " + folderList.size + " 个收藏夹")
        return folderList
    }

    /**
     * 旧接口兜底：space.bilibili.com/ajax/fav/getBoxList。
     * 已实测失效（恒返回空 list），保留只是为了接口万一恢复时还能用。
     */
    private fun getFavoriteFoldersLegacy(mid: Long): ArrayList<FavoriteFolder> {
        val folderList = ArrayList<FavoriteFolder>()
        val url = "https://space.bilibili.com/ajax/fav/getBoxList?mid=" + mid
        val result = NetWorkUtil.getJson(url)
        val data = result.optJSONObject("data") ?: return folderList
        val list = data.optJSONArray("list") ?: return folderList
        for (i in 0 until list.length()) {
            val folder = list.getJSONObject(i)
            val favoriteFolder = FavoriteFolder()
            val fid = folder.optLong("fav_box", 0)

            favoriteFolder.id = fid
            favoriteFolder.mediaId = folderMediaId(mid, fid)
            favoriteFolder.name = folder.optString("name", "")

            val videos = folder.optJSONArray("videos")
            favoriteFolder.cover = if (videos != null && videos.length() > 0)
                videos.getJSONObject(0).optString("pic", "") else ""

            favoriteFolder.videoCount = folder.optInt("count", 0)
            favoriteFolder.maxCount = folder.optInt("max_count", 0)
            favoriteFolder.isDefault = i == 0 || fid == 0L

            folderList.add(favoriteFolder)
        }
        if (folderList.isNotEmpty()) Log.e("debug-收藏夹", "旧接口取到 " + folderList.size + " 个收藏夹")
        return folderList
    }

    /**
     * 解析 v3 收藏夹对象（`created/list` 与 `created/list-all` 通用）。
     *
     * 字段对应关系：
     * - `fid`          → [FavoriteFolder.id]（旧接口语义，供旧调用方沿用）
     * - `id`           → [FavoriteFolder.mediaId]（新接口真正要用的 media_id）
     * - `title`        → [FavoriteFolder.name]
     * - `cover`        → [FavoriteFolder.cover]（list-all 没有这个字段，为空串）
     * - `media_count`  → [FavoriteFolder.videoCount]
     * - `max_count`    → v3 已不返回，置 0 表示未知，UI 只显示视频数不显示「/上限」
     */
    private fun parseV3Folder(mid: Long, folder: JSONObject): FavoriteFolder {
        val favoriteFolder = FavoriteFolder()
        val fid = folder.optLong("fid", 0)
        val mediaId = folder.optLong("id", 0)
        val title = folder.optString("title", "")

        favoriteFolder.id = fid
        favoriteFolder.mediaId = if (mediaId > 0) mediaId else folderMediaId(mid, fid)
        favoriteFolder.name = if (title.isEmpty()) folder.optString("name", "未命名收藏夹") else title
        favoriteFolder.cover = folder.optString("cover", "")
        favoriteFolder.videoCount = folder.optInt("media_count", folder.optInt("count", 0))
        favoriteFolder.maxCount = 0
        // 默认收藏夹的 fid 是 0；部分账号的默认收藏夹标题就写作「默认收藏夹」，一并当作默认处理
        favoriteFolder.isDefault = fid <= 0L || favoriteFolder.name == DEFAULT_FOLDER_NAME

        return favoriteFolder
    }

    /**
     * 获取收藏的合集
     *
     * @param mid            目标用户
     * @param page           页数
     * @param collectionList collection对象List
     * @return 返回码与has_more
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFavoritedCollections(mid: Long, page: Int, collectionList: MutableList<Collection>): Pair<Int, Boolean> {
        val url = "https://api.bilibili.com/x/v3/fav/folder/collected/list" + NetWorkUtil.FormData()
                .setUrlParam(true)
                .put("platform", "web")
                .put("up_mid", mid)
                .put("pn", page)
                .put("ps", 10)
        val result = NetWorkUtil.getJson(url)
        val data = result.optJSONObject("data")
        var has_more = false
        if (data != null) {
            has_more = data.optBoolean("has_more", false)
            val list = data.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val collection = Collection()
                    collection.id = item.optInt("id", -1)
                    collection.mid = item.optLong("mid", -1)
                    collection.title = item.optString("title")
                    collection.cover = item.optString("cover")
                    collection.intro = item.optString("intro")
                    collection.view = StringUtil.toWan(item.optInt("view_count", -1).toLong())
                    collectionList.add(collection)
                }
            }
        }
        return Pair(result.optInt("code", -1), has_more)
    }

    /**
     * 获取某个视频收藏夹里的视频。
     *
     * 【为什么要改】旧实现请求 `api.bilibili.com/x/space/fav/arc?vmid=&fid=`。
     * 实测该接口同样已废弃：code 0、`data.archives` 恒为空数组，
     * 于是收藏夹点进去永远是空的。新版接口是 `x/v3/fav/resource/list`，
     * 但它要 **media_id** 而不是 fid，所以这里先用 [mediaId]（未知时按
     * [folderMediaId] 从 fid 推算）请求 v3，失败再退旧接口。
     *
     * @param fid     收藏夹 fid（[FavoriteFolder.id]）
     * @param mediaId 收藏夹 media_id（[FavoriteFolder.mediaId]），传 0 表示按 fid 推算
     * @return 0=还有下一页，1=到底或空收藏夹，-1=失败
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFolderVideos(mid: Long, fid: Long, page: Int, videoList: ArrayList<VideoCard>): Int {
        return getFolderVideos(mid, fid, page, videoList, 0)
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFolderVideos(mid: Long, fid: Long, page: Int, videoList: ArrayList<VideoCard>, mediaId: Long): Int {
        val realMediaId = if (mediaId > 0) mediaId else folderMediaId(mid, fid)

        if (realMediaId > 0) {
            try {
                val result = getFolderVideosV3(realMediaId, page, videoList)
                if (result != -1) return result
            } catch (e: Exception) {
                Log.e("debug-收藏夹视频v3", "resource/list 失败：" + e.message)
            }
        }

        // 兜底：旧接口（已废弃，很可能返回空 archives）
        return getFolderVideosLegacy(mid, fid, page, videoList)
    }

    /** 新版 v3 收藏夹内容接口：api.bilibili.com/x/v3/fav/resource/list（用 media_id） */
    private fun getFolderVideosV3(mediaId: Long, page: Int, videoList: ArrayList<VideoCard>): Int {
        val url = "https://api.bilibili.com/x/v3/fav/resource/list?media_id=" + mediaId +
                "&pn=" + page + "&ps=" + FOLDER_VIDEO_PAGE_SIZE +
                "&keyword=&order=mtime&type=0&tid=0&platform=web"
        val result = NetWorkUtil.getJson(url)
        val code = result.optInt("code", -1)
        if (code != 0) {
            Log.e("debug-收藏夹视频v3", "code=" + code + " msg=" + result.optString("message"))
            return -1
        }
        // code 为 0 但 data 为 null：收藏夹不存在 / 已删除 / 没有权限（未登录看私密收藏夹）。
        // 实测空 media_id 与无权限时都是这个返回，这里按「到底了（空收藏夹）」处理，避免显示成加载失败。
        val data = result.optJSONObject("data")
        if (data == null) {
            Log.e("debug-收藏夹视频v3", "data 为 null，按空收藏夹处理")
            return 1
        }
        val medias = data.optJSONArray("medias")
        if (medias == null || medias.length() == 0) return 1

        for (i in 0 until medias.length()) {
            val media = medias.getJSONObject(i)
            // 收藏夹里可能混有合集/音频等条目，这里只要稿件视频（type=2 且 id 有效）
            val aid = media.optLong("id", 0)
            if (media.optInt("type", 2) != 2 || aid <= 0) continue

            val title = media.optString("title", "")
            val cover = media.optString("cover", "")
            val bvid = media.optString("bvid", "")
            val upName = media.optJSONObject("upper")?.optString("name", "") ?: ""
            val play = media.optJSONObject("cnt_info")?.optLong("play", 0) ?: 0

            videoList.add(VideoCard(title, upName, StringUtil.toWan(play) + "观看", cover, aid, bvid))
        }

        return if (data.optBoolean("has_more", false)) 0 else 1
    }

    /** 旧收藏夹内容接口兜底（已废弃）：api.bilibili.com/x/space/fav/arc（用 fid） */
    private fun getFolderVideosLegacy(mid: Long, fid: Long, page: Int, videoList: ArrayList<VideoCard>): Int {
        val url = "https://api.bilibili.com/x/space/fav/arc?vmid=" + mid +
                "&ps=30&fid=" + fid + "&tid=0&keyword=&pn=" + page + "&order=fav_time"
        val result = NetWorkUtil.getJson(url)
        val data = result.optJSONObject("data") ?: return -1
        val archives = data.optJSONArray("archives") ?: return -1
        if (archives.length() == 0) return 1

        for (i in 0 until archives.length()) {
            val video = archives.getJSONObject(i)
            val title = video.optString("title", "")
            val cover = video.optString("pic", "")
            val aid = video.optLong("aid", 0)
            val upName = video.optJSONObject("owner")?.optString("name", "") ?: ""
            val stat = video.optJSONObject("stat")
            val view = if (stat != null) StringUtil.toWan(stat.optLong("view", 0)) + "观看" else ""

            videoList.add(VideoCard(title, upName, view, cover, aid, ""))
        }
        return 0
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFavouriteOpus(list: ArrayList<Opus>, page: Int): Boolean {
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/favlist?page_size=10&page=$page"
        val result = NetWorkUtil.getJson(url)
        Log.e("OpusFav", result.toString())
        val hasMore = result.getJSONObject("data").getBoolean("has_more")
        if (!hasMore) Log.e("图文", "没有更多啦")
        val items = result.getJSONObject("data").getJSONArray("items")

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val opus = Opus()
            opus.content = item.optString("content")
            opus.cover = item.optString("cover")
            opus.title = item.optString("title")
            if (opus.title.isEmpty()) opus.title = opus.content
            opus.id = item.getString("opus_id").toLong()
            opus.pubTime = item.optString("time_text")
            list.add(opus)
        }

        return hasMore
    }

    /**
     * 视频详情页「收藏」弹窗的收藏夹列表（带当前视频在不在该收藏夹的状态）。
     *
     * 接口仍是 v3 `created/list-all` + `rid`（会顺带返回每个收藏夹的 fav_state），
     * 但旧代码直接 `getJSONObject("data")`：登录态失效时 data 为 null 会抛 JSONException，
     * 整个弹窗直接「加载失败」。这里改成 data 为 null 时退化为
     * 「只列收藏夹、状态按未收藏处理」，至少让用户还能选收藏夹。
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFavoriteState(aid: Long, folderList: ArrayList<String>, fidList: ArrayList<Long>, stateList: ArrayList<Boolean>) {
        val mid = SharedPreferencesUtil.getLong("mid", 0)
        val url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all?type=2&jsonp=jsonp&rid=" + aid + "&up_mid=" + mid
        val result = NetWorkUtil.getJson(url)

        val list = result.optJSONObject("data")?.optJSONArray("list")
        if (list != null) {
            for (i in 0 until list.length()) {
                val folder = list.getJSONObject(i)
                folderList.add(folder.optString("title", ""))
                fidList.add(folder.optLong("fid", 0))
                stateList.add(folder.optInt("fav_state", 0) == 1)
            }
            return
        }

        // data 为 null（多为登录态失效）：退化成只列收藏夹
        Log.e("debug-收藏状态", "接口未返回收藏状态，退化为只列收藏夹")
        val folders = getFavoriteFolders(mid)
        for (folder in folders) {
            folderList.add(folder.name ?: "")
            fidList.add(folder.id)
            stateList.add(false)
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun addFavorite(aid: Long, fid: Long): Int {
        return addFavoriteByMediaId(aid, folderMediaId(SharedPreferencesUtil.getLong("mid", 0), fid))
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun addFavoriteByMediaId(aid: Long, mediaId: Long): Int {
        if (mediaId <= 0) return -1
        val url = "https://api.bilibili.com/medialist/gateway/coll/resource/deal"
        val per = "rid=" + aid + "&type=2&add_media_ids=" + mediaId + "&del_media_ids=&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-添加收藏", result.toString())
        return result.getInt("code")
    }


    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun deleteFavorite(aid: Long, fid: Long): Int {
        //旧代码是 fid 后面拼上 mid 的后两位，本质就是 media_id = fid * 100 + mid % 100，这里统一走下面的函数
        return deleteFavoriteByMediaId(aid, folderMediaId(SharedPreferencesUtil.getLong("mid", 0), fid))
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun deleteFavoriteByMediaId(aid: Long, mediaId: Long): Int {
        if (mediaId <= 0) return -1
        val url = "https://api.bilibili.com/medialist/gateway/coll/resource/batch/del"
        val per = "resources=" + aid + ":2&media_id=" + mediaId + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-删除收藏", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun addFolder(title: String?, intro: String?, privacy: Int): Int {
        val url = "https://api.bilibili.com/x/v3/fav/folder/add"
        val formData = NetWorkUtil.FormData()
                .put("title", title)
                .put("intro", if (intro != null) intro else "")
                .put("privacy", privacy)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
        val data = formData.toString()
        val result = JSONObject(NetWorkUtil.post(url, data, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-新建收藏夹", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun editFolder(mediaId: Long, title: String?, intro: String?, privacy: Int): Int {
        val url = "https://api.bilibili.com/x/v3/fav/folder/edit"
        val formData = NetWorkUtil.FormData()
                .put("media_id", mediaId)
                .put("title", title)
                .put("intro", if (intro != null) intro else "")
                .put("privacy", privacy)
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
        val data = formData.toString()
        val result = JSONObject(NetWorkUtil.post(url, data, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-修改收藏夹", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun deleteFolder(mediaId: Long): Int {
        val url = "https://api.bilibili.com/x/v3/fav/folder/del"
        val formData = NetWorkUtil.FormData()
                .put("media_ids", mediaId.toString())
                .put("csrf", SharedPreferencesUtil.getString("csrf", ""))
        val data = formData.toString()
        val result = JSONObject(NetWorkUtil.post(url, data, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-删除收藏夹", result.toString())
        return result.getInt("code")
    }

}
