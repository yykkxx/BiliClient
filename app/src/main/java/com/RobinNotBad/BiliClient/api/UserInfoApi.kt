package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.DmImgParamUtil
import com.RobinNotBad.BiliClient.util.Logu
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
        if (!all.has("data") || all.isNull("data")) return null
        val data = all.getJSONObject("data")
        // 未开启全 API 迁移时拿到的是 web 形状，原样返回（零回归）。
        // 开启后这条请求会被改写到 app.bilibili.com/x/v2/space，回包是 data.card 结构，
        // 在这里归一化成 web 形状，让上层（UserDynamicAdapter / PrivateMsgApi /
        // TerminalContext）一行都不用改。
        if (!data.has("card") || data.isNull("card")) return data
        return normalizeAppSpace(data, mid)
    }

    /**
     * 把 `app.bilibili.com/x/v2/space` 的 `data.card` 归一化成 web
     * `/x/space/wbi/acc/info` 的 `data` 形状。
     *
     * 实测字段对照（2026-10-29，`vmid=2`，见 notes/api_full_migration_alpha10.md 的 H 节）：
     * | web `data` | APP `data.card` |
     * |---|---|
     * | `mid`(数字) | `mid`(**字符串**，需转数字) |
     * | `name` / `sign` / `face` | 同名；另有 `avatar` 同值 |
     * | `level`(数字) | `level_info.current_level` |
     * | `official{role,title,desc}` | `official_verify{type,desc,role,title}` |
     * | `fans` / `attention` / `friend` / `vip` / `pendant` / `nameplate` | 同名同构 |
     * | `live_room{roomStatus,liveStatus,title,cover,roomid}` | **无**（改用直播域公开接口补，见 [fetchLiveRoom]） |
     * | `sys_notice` / `contract` | **无**（上层用 isNull 判断跳过，天然兼容） |
     *
     * @param data APP 回包的 data（含 card）
     * @param mid 请求用的 mid，card.mid 缺失时兜底
     */
    private fun normalizeAppSpace(data: JSONObject, mid: Long): JSONObject {
        val card = data.optJSONObject("card") ?: return data
        val out = JSONObject()
        out.put("mid", card.optLong("mid", mid))
        out.put("name", card.optString("name", ""))
        out.put("sex", card.optString("sex", "保密"))
        out.put("face", card.optString("face", card.optString("avatar", "")))
        out.put("sign", card.optString("sign", ""))
        val levelInfo = card.optJSONObject("level_info")
        out.put("level", levelInfo?.optInt("current_level", 0) ?: 0)
        if (levelInfo != null) out.put("level_info", levelInfo)
        out.put("rank", card.optString("rank", ""))
        out.put("fans", card.optInt("fans", 0))
        out.put("attention", card.optInt("attention", 0))
        out.put("friend", card.optInt("friend", 0))
        out.put("is_senior_member", card.optInt("is_senior_member", 0))
        copyIfPresent(card, out, "pendant")
        copyIfPresent(card, out, "nameplate")
        copyIfPresent(card, out, "vip")
        // web 叫 official，APP 叫 official_verify：两个键都放，消费方读哪个都在
        val official = card.optJSONObject("official_verify")
        if (official != null) {
            out.put("official", official)
            out.put("official_verify", official)
        }
        val liveRoom = fetchLiveRoom(mid)
        if (liveRoom != null) out.put("live_room", liveRoom)
        return out
    }

    /** 从 source 里复制一个对象字段到 target（不存在就跳过，绝不写 null） */
    private fun copyIfPresent(source: JSONObject, target: JSONObject, key: String) {
        val value = source.optJSONObject(key) ?: return
        target.put(key, value)
    }

    /**
     * 补出 web `acc/info` 里的 `live_room` 字段（APP 空间接口不带）。
     *
     * 用直播域公开接口 `api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=`：
     * 实测 APP 参数下 code=0，`data` 里 `roomStatus/liveStatus/title/cover/roomid`
     * 与 web `live_room` 完全同名同义，上层 `if (roomStatus==1 && liveStatus==1)` 直接可用。
     *
     * 这是「有就显示」的装饰数据，失败只能影响直播角标，绝不影响空间信息主流程。
     */
    private fun fetchLiveRoom(mid: Long): JSONObject? {
        return try {
            val url = "https://api.live.bilibili.com/room/v1/Room/getRoomInfoOld?mid=" + mid
            val all = NetWorkUtil.getJson(url)
            if (all.optInt("code", -1) != 0 || all.isNull("data")) null else all.getJSONObject("data")
        } catch (e: Exception) {
            Logu.d("live-room", "补直播状态失败：" + e.javaClass.simpleName)
            null
        }
    }

    /**
     * 从 UP 主投稿响应里取出视频数组，兼容两种结构：
     * - web：`data.list.vlist[]`
     * - APP：`data.item[]`（开启全 API 迁移后走 app.bilibili.com/x/v2/space/archive）
     *
     * @return null 表示响应结构不认识（调用方按「加载失败」处理）
     */
    private fun resolveArchiveArray(data: JSONObject): JSONArray? {
        if (data.has("list") && !data.isNull("list")) {
            val list = data.getJSONObject("list")
            if (!list.has("vlist") || list.isNull("vlist")) return null
            return list.getJSONArray("vlist")
        }
        if (data.has("item") && !data.isNull("item")) return data.optJSONArray("item")
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
            // 两种结构都要认（全 API 迁移会把这条请求改写到 app.bilibili.com/x/v2/space/archive）：
            // - web：data.list.vlist[]（pic/play/aid/bvid/author/title）
            // - APP：data.item[]（cover/play/bvid/author/title，aid 在 param 里且是字符串）
            val vlist = resolveArchiveArray(data) ?: return -1
            if (vlist.length() == 0) return 1
            for (i in 0 until vlist.length()) {
                val card = vlist.getJSONObject(i)
                val cover = card.optString("pic", card.optString("cover", ""))
                val play = card.optLong("play", 0L)
                val playStr = StringUtil.toWan(play) + "观看"
                val aid = if (card.has("aid")) card.optLong("aid", 0L)
                else card.optString("param", "0").toLongOrNull() ?: 0L
                val bvid = card.optString("bvid", "")
                val upName = card.optString("author", "")
                val title = card.optString("title", "")

                videoList.add(VideoCard(title, upName, playStr, cover, aid, bvid))
            }
            return 0
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
