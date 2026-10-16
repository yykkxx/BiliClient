package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.DmImgParamUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.net.URLEncoder

//用户信息API

object UserInfoApi {

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getUserInfo(mid: Long): UserInfo? {
        val url = "https://api.bilibili.com/x/web-interface/card?mid=" + mid
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            val notice_all = NetWorkUtil.getJson("https://api.bilibili.com/x/space/notice?mid=" + mid)
            val notice: String
            if (notice_all.has("data") && !notice_all.isNull("data"))
                notice = notice_all.getString("data")
            else notice = ""
            val data = all.getJSONObject("data")
            val followed = data.getBoolean("following")
            val fans = data.getInt("follower")

            val card = data.getJSONObject("card")
            val name = card.getString("name")
            val avatar = card.getString("face")
            val sign = card.getString("sign")
            val levelInfo = card.getJSONObject("level_info")
            val level = levelInfo.getInt("current_level")
            val attention = card.getInt("attention")

            val official_data = card.getJSONObject("Official")
            val official = official_data.getInt("role")
            val officialDesc = official_data.getString("title")

            var sys_notice: String? = ""
            var liveroom: LiveRoom? = null
            var is_follow_display = false
            try {
                val spaceInfo = getUserSpaceInfo(mid)
                if (spaceInfo != null) {
                    if (!spaceInfo.isNull("sys_notice")) {
                        sys_notice = spaceInfo.getJSONObject("sys_notice").optString("content")
                        if (sys_notice == null) sys_notice = ""
                        else sys_notice = sys_notice!!.replace("请点此查看纪念账号相关说明", "")
                    }
                    if (!spaceInfo.isNull("live_room")) {
                        val live_room = spaceInfo.getJSONObject("live_room")
                        if (live_room.getInt("roomStatus") == 1 && live_room.getInt("liveStatus") == 1) {
                            val room = LiveRoom()
                            room.title = "直播中：" + live_room.getString("title")
                            room.user_cover = live_room.getString("cover")
                            room.roomid = live_room.getLong("roomid")
                            liveroom = room
                        }
                    }
                    if (!spaceInfo.isNull("contract")) {
                        val contract = spaceInfo.getJSONObject("contract")
                        is_follow_display = contract.optBoolean("is_follow_display", false)
                    }
                }
            } catch (ignore: Exception) {
            }

            val vip = card.getJSONObject("vip")
            if (vip.getInt("status") == 1) {
                val result = UserInfo(mid, name, avatar, sign, fans, attention, level, followed, notice, official, officialDesc, vip.getInt("role"), sys_notice, liveroom, card.getInt("is_senior_member"))
                result.vip_nickname_color = vip.optString("nickname_color", "")
                result.is_follow_display = is_follow_display
                return result
            } else {
                val result = UserInfo(mid, name, avatar, sign, fans, attention, level, followed, notice, official, officialDesc, sys_notice, liveroom, card.getInt("is_senior_member"))
                result.is_follow_display = is_follow_display
                return result
            }
        } else return null
    }

    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getUserSpaceInfo(mid: Long): JSONObject? {
        var url = "https://api.bilibili.com/x/space/wbi/acc/info?"
        url += "mid=" + mid
        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url)))
        if (all.has("data") && !all.isNull("data")) {
            return all.getJSONObject("data")
        }
        return null
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getCurrentUserInfo(): UserInfo {
        val url = "https://api.bilibili.com/x/space/myinfo"
        val all = NetWorkUtil.getJson(url)
        if (all.has("data") && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            val mid = data.getLong("mid")
            val name = data.getString("name")
            val avatar = data.getString("face")
            val sign = data.getString("sign")
            val fans = data.getInt("follower")
            val level = data.getInt("level")

            val official_data = data.getJSONObject("official")
            val official = official_data.getInt("role")
            val officialDesc = official_data.getString("desc")

            val level_exp = data.getJSONObject("level_exp")
            val current_exp = level_exp.getLong("current_exp")
            val next_exp = level_exp.getLong("next_exp")

            return UserInfo(mid, name, avatar, sign, fans, 0, level, false, "", official, officialDesc, current_exp, next_exp, data.getInt("is_senior_member"))
        } else return UserInfo(0, "加载失败", "", "", 0, 0, 0, false, "", 0, "", 0)
    }

    @JvmStatic
    fun getCurrentUserCoin(): Int {
        try {
            val url = "https://account.bilibili.com/site/getCoin"
            val all = NetWorkUtil.getJson(url)
            if (all.has("data") && !all.isNull("data")) {
                val data = all.getJSONObject("data")
                return if (data.has("money")) data.getInt("money") else 0
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        return 0
    }


    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getUserVideos(mid: Long, page: Int, searchKeyword: String?, videoList: MutableList<VideoCard>): Int {
        var url = "https://api.bilibili.com/x/space/wbi/arc/search?"
        url += "keyword=" + searchKeyword + "&mid=" + mid + "&order_avoided=true&order=pubdate&pn=" + page +
                "&ps=40&tid=0&web_location=333.999"
        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(DmImgParamUtil.getDmImgParamsUrl(url)))
        if (all.has("data") && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            val list = data.getJSONObject("list")
            if (list.has("vlist") && !list.isNull("vlist")) {
                val vlist = list.getJSONArray("vlist")
                if (vlist.length() == 0) return 1
                for (i in 0 until vlist.length()) {
                    val card = vlist.getJSONObject(i)
                    val cover = card.getString("pic")
                    val play = card.getLong("play")
                    val playStr = StringUtil.toWan(play) + "观看"
                    val aid = card.getLong("aid")
                    val bvid = card.getString("bvid")
                    val upName = card.getString("author")
                    val title = card.getString("title")

                    videoList.add(VideoCard(title, upName, playStr, cover, aid, bvid))
                }
                return 0
            } else return -1
        } else return -1
    }


    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getUserArticles(mid: Long, page: Int, articleList: MutableList<ArticleCard>): Int {
        var url = "https://api.bilibili.com/x/space/wbi/article?"
        url += "mid=" + mid + "&order_avoided=true&order=pubdate&pn=" + page +
                "&ps=30&tid=0"
        val all = NetWorkUtil.getJson(ConfInfoApi.signWBI(url), NetWorkUtil.webHeaders)
        if (all.has("data") && !all.isNull("data")) {
            val data = all.getJSONObject("data")
            if (data.has("articles")) {
                val list = data.getJSONArray("articles")
                if (list.length() == 0) return 1
                for (i in 0 until list.length()) {
                    val card = list.getJSONObject(i)

                    val articleCard = ArticleCard()
                    articleCard.id = card.getLong("id")
                    articleCard.title = card.getString("title")
                    val stats = card.getJSONObject("stats")
                    articleCard.view = StringUtil.toWan(stats.getInt("view").toLong()) + "阅读"
                    articleCard.cover = card.getString("banner_url")
                    val author = card.getJSONObject("author")
                    articleCard.upName = author.getString("name")
                    articleList.add(articleCard)
                }
                return 0
            } else return 1
        } else return -1
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun followUser(mid: Long, isFollow: Boolean): Int {
        val url = "https://api.bilibili.com/x/relation/modify?"
        var arg = "fid=" + mid + "&csrf=" + NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!)
        if (isFollow) arg += "&act=1" //关注
        else arg += "&act=2" //取消关注
        val all = JSONObject(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body!!.string())
        return all.getInt("code")
    }

    @JvmStatic
    fun exitLogin() {
        try {
            val url = "https://passport.bilibili.com/login/exit/v2"
            NetWorkUtil.get(url, NetWorkUtil.webHeaders)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun addContract(upMid: Long): Int {
        val url = "https://api.bilibili.com/x/v1/contract/add_contract"
        val csrf = NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!)
        val arg = "aid=&up_mid=" + upMid + "&source=4&scene=105&platform=web&mobi_app=pc&csrf=" + csrf
        val all = JSONObject(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body!!.string())
        return all.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getMedalWall(targetId: Long): JSONObject? {
        val url = "https://api.live.bilibili.com/xlive/web-ucenter/user/MedalWall?target_id=" + targetId
        val all = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)
        if (all.has("data") && !all.isNull("data")) {
            return all.getJSONObject("data")
        }
        return null
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun updateUserSign(userSign: String?): JSONObject {
        val url = "https://api.bilibili.com/x/member/web/sign/update"
        val csrf = NetWorkUtil.getInfoFromCookie("bili_jct", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")!!)
        var arg = "csrf=" + csrf
        if (userSign != null) {
            arg += "&user_sign=" + URLEncoder.encode(userSign, "UTF-8")
        }
        val all = JSONObject(NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders).body!!.string())
        return all
    }
}
