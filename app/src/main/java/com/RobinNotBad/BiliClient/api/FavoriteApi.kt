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

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList
import org.json.JSONException
import java.io.IOException

//收藏API

object FavoriteApi {
    // TODO 合集收藏

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFavoriteFolders(mid: Long): ArrayList<FavoriteFolder> {
        val url = "https://space.bilibili.com/ajax/fav/getBoxList?mid=$mid"
        val result = NetWorkUtil.getJson(url)
        val data = result.getJSONObject("data")
        val folderList = ArrayList<FavoriteFolder>()
        val fidToMediaIdMap = HashMap<Long, Long>()

        try {
            val v3Url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all?type=2&up_mid=$mid"
            val v3Result = NetWorkUtil.getJson(v3Url)
            val v3Data = v3Result.optJSONObject("data")
            if (v3Data != null && v3Data.has("list") && !v3Data.isNull("list")) {
                val v3List = v3Data.getJSONArray("list")
                for (j in 0 until v3List.length()) {
                    val v3Folder = v3List.getJSONObject(j)
                    val v3Fid = v3Folder.optLong("fid", 0)
                    val v3MediaId = v3Folder.optLong("id", 0)
                    if (v3Fid > 0 && v3MediaId > 0) {
                        fidToMediaIdMap[v3Fid] = v3MediaId
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("debug-获取mediaId失败", e.message.toString())
        }

        if (data.has("list") && !data.isNull("list")) {
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val folder = list.getJSONObject(i)

                val favoriteFolder = FavoriteFolder()

                favoriteFolder.id = folder.getLong("fav_box")
                favoriteFolder.name = folder.getString("name")

                if (!folder.isNull("videos"))
                    favoriteFolder.cover = folder.getJSONArray("videos").getJSONObject(0).getString("pic")
                else favoriteFolder.cover = ""

                favoriteFolder.videoCount = folder.getInt("count")
                favoriteFolder.maxCount = folder.getInt("max_count")
                favoriteFolder.isDefault = (i == 0 || favoriteFolder.id == 0L)

                val mediaId = fidToMediaIdMap[favoriteFolder.id]
                if (mediaId != null) {
                    favoriteFolder.mediaId = mediaId
                }

                Log.e("debug-收藏夹ID", favoriteFolder.id.toString())
                Log.e("debug-收藏夹mediaId", favoriteFolder.mediaId.toString())
                Log.e("debug-收藏夹名称", favoriteFolder.name ?: "")
                Log.e("debug-收藏夹封面", favoriteFolder.cover ?: "")
                Log.e("debug-收藏夹视频数量", favoriteFolder.videoCount.toString())
                Log.e("debug-收藏夹视频上限", favoriteFolder.maxCount.toString())
                Log.e("debug-收藏夹", "----------------")
                folderList.add(favoriteFolder)
            }
        }
        return folderList
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

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFolderVideos(mid: Long, fid: Long, page: Int, videoList: ArrayList<VideoCard>): Int {
        val url = "https://api.bilibili.com/x/space/fav/arc?vmid=" + mid +
                "&ps=30&fid=" + fid + "&tid=0&keyword=&pn=" + page + "&order=fav_time"
        val result = NetWorkUtil.getJson(url)
        val data = result.getJSONObject("data")
        if (data.has("archives") && !data.isNull("archives")) {
            val archives = data.getJSONArray("archives")
            if (archives.length() != 0) {
                for (i in 0 until archives.length()) {
                    val video = archives.getJSONObject(i)
                    val title = video.getString("title")
                    val cover = video.getString("pic")
                    val aid = video.getLong("aid")

                    val owner = video.getJSONObject("owner")
                    val upName = owner.getString("name")

                    val stat = video.getJSONObject("stat")
                    val view = StringUtil.toWan(stat.getLong("view")) + "观看"
                    videoList.add(VideoCard(title, upName, view, cover, aid, ""))
                }
                return 0
            } else return 1
        } else return -1
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

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFavoriteState(aid: Long, folderList: ArrayList<String>, fidList: ArrayList<Long>, stateList: ArrayList<Boolean>) {
        val url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all?type=2&jsonp=jsonp&rid=" + aid + "&up_mid=" + SharedPreferencesUtil.getLong("mid", 0)
        val result = NetWorkUtil.getJson(url)
        val data = result.getJSONObject("data")

        if (data.has("list") && !data.isNull("list")) {
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val folder = list.getJSONObject(i)
                folderList.add(folder.getString("title"))
                fidList.add(folder.getLong("fid"))
                stateList.add(folder.getInt("fav_state") == 1)
            }
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun addFavorite(aid: Long, fid: Long): Int {
        val strMid = SharedPreferencesUtil.getLong("mid", 0).toString()
        val addFid = fid.toString() + strMid.substring(strMid.length - 2)
        val url = "https://api.bilibili.com/medialist/gateway/coll/resource/deal"
        val per = "rid=" + aid + "&type=2&add_media_ids=" + addFid + "&del_media_ids=&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-添加收藏", result.toString())
        return result.getInt("code")
    }


    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun deleteFavorite(aid: Long, fid: Long): Int {
        val strMid = SharedPreferencesUtil.getLong("mid", 0).toString()
        val delFid = fid.toString() + strMid.substring(strMid.length - 2)    //腕上哔哩那边是错的，fid后面要加上mid的后两位而不是定值，虽然这不影响什么
        val url = "https://api.bilibili.com/medialist/gateway/coll/resource/batch/del"
        val per = "resources=" + aid + ":2&media_id=" + delFid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

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
