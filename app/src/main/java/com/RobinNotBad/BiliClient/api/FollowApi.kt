package com.RobinNotBad.BiliClient.api

//关注api
//2023-08-27

import com.RobinNotBad.BiliClient.model.FollowTag
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList
import java.io.IOException


object FollowApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFollowingList(mid: Long, page: Int, userList: MutableList<UserInfo>): Int {
        val url = "https://api.bilibili.com/x/relation/followings?vmid=" + mid + "&pn=" + page + "&ps=20&order=desc&order_type=attention"
        val callback = NetWorkUtil.getJson(url)
        if (callback.optInt("code", -1) != 0)
            throw JSONException(callback.optInt("code", -1).toString() + "：" + callback.optString("message", "未知API错误"))
        val data = callback.getJSONObject("data")
        val list = data.getJSONArray("list")
        if (list.length() == 0) return 1
        else {
            for (i in 0 until list.length()) {
                val userInfo = list.getJSONObject(i)
                val name = userInfo.getString("uname")
                val uid = userInfo.getLong("mid")
                val avatar = userInfo.getString("face")
                val sign = userInfo.getString("sign")
                userList.add(UserInfo(uid, name, avatar, sign, 0, 0, 0, true, "", 0, "", userInfo.optLong("mtime", 0), 0))
            }
            return 0
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFollowerList(mid: Long, page: Int, userList: MutableList<UserInfo>): Int {
        val url = "https://api.bilibili.com/x/relation/followers?vmid=" + mid + "&pn=" + page + "&ps=20&order=desc&order_type=attention"
        val callback = NetWorkUtil.getJson(url)
        if (callback.optInt("code", -1) != 0)
            throw JSONException(callback.optInt("code", -1).toString() + "：" + callback.optString("message", "未知API错误"))
        val data = callback.getJSONObject("data")
        val list = data.getJSONArray("list")
        if (list.length() == 0) return 1
        else {
            for (i in 0 until list.length()) {
                val userInfo = list.getJSONObject(i)
                val name = userInfo.getString("uname")
                val uid = userInfo.getLong("mid")
                val avatar = userInfo.getString("face")
                val sign = userInfo.getString("sign")
                userList.add(UserInfo(uid, name, avatar, sign, 0, 0, 0, true, "", 0, "", userInfo.optLong("mtime", 0), 0))
            }
            return 0
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFollowTags(): List<FollowTag> {
        val url = "https://api.bilibili.com/x/relation/tags"
        val callback = NetWorkUtil.getJson(url)
        if (callback.optInt("code", -1) != 0)
            throw JSONException(callback.optInt("code", -1).toString() + "：" + callback.optString("message", "未知API错误"))
        val data = callback.getJSONArray("data")
        val tagList = ArrayList<FollowTag>()
        for (i in 0 until data.length()) {
            val tag = data.getJSONObject(i)
            val tagid = tag.getInt("tagid")
            val name = tag.getString("name")
            val count = tag.getInt("count")
            tagList.add(FollowTag(tagid, name, count))
        }
        return tagList
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getFollowTagUsers(tagid: Int, page: Int, userList: MutableList<UserInfo>): Int {
        val url = "https://api.bilibili.com/x/relation/tag?tagid=" + tagid + "&pn=" + page + "&ps=20"
        val callback = NetWorkUtil.getJson(url)
        if (callback.optInt("code", -1) != 0)
            throw JSONException(callback.optInt("code", -1).toString() + "：" + callback.optString("message", "未知API错误"))
        val data = callback.getJSONArray("data")
        if (data.length() == 0) return 1
        else {
            for (i in 0 until data.length()) {
                val userInfo = data.getJSONObject(i)
                val name = userInfo.getString("uname")
                val uid = userInfo.getLong("mid")
                val avatar = userInfo.getString("face")
                val sign = userInfo.getString("sign")
                userList.add(UserInfo(uid, name, avatar, sign, 0, 0, 0, true, "", 0, "", 0))
            }
            return 0
        }
    }
}
