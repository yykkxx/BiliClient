package com.RobinNotBad.BiliClient.api

import android.annotation.SuppressLint
import android.text.TextUtils

import com.RobinNotBad.BiliClient.model.LivePlayInfo
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.LinkedHashMap

object LiveApi {
    @JvmField
    val QualityMap: LinkedHashMap<String, Int> = LinkedHashMap()

    init {
        //清晰度代码
        QualityMap.put("流畅", 80)
        QualityMap.put("高清", 150)
        QualityMap.put("超清", 250)
        QualityMap.put("蓝光", 400)
        QualityMap.put("原画", 10000)
    }

    /**
     * 获取推荐直播间列表
     *
     * @param page 页数
     * @return 直播间列表
     */
    @JvmSuppressWildcards
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getRecommend(page: Int): List<LiveRoom>? {
        val url = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getUserRecommend" + NetWorkUtil.FormData().setUrlParam(true)
                .put("page", page)
                .put("page_size", 10)
                .put("platform", "web")
        val result = NetWorkUtil.getJson(ConfInfoApi.signWBI(url))
        if (result.getInt("code") != 0) throw JSONException(result.getString("message"))

        val data = result.optJSONObject("data")
        if (data != null) {
            val list = data.optJSONArray("list")
            if (list != null) {
                return analyzeLiveRooms(list)
            }
        }
        return null
    }

    /**
     * 获取关注的直播间列表
     *
     * @param page 页数
     * @return 关注的直播间列表
     */
    @JvmSuppressWildcards
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getFollowed(page: Int): List<LiveRoom>? {
        val url = "https://api.live.bilibili.com/xlive/web-ucenter/v1/xfetter/GetWebList" + NetWorkUtil.FormData().setUrlParam(true)
                .put("page", page)
                .put("page_size", 10)
        val result = NetWorkUtil.getJson(url)
        if (result.getInt("code") != 0) throw JSONException(result.getString("message"))

        val data = result.optJSONObject("data")
        if (data != null) {
            val list = data.optJSONArray("rooms")
            if (list != null) {
                return analyzeLiveRooms(list)
            }
        }
        return null
    }

    /**
     * 获取直播间详情
     *
     * @param room_id 直播间ID
     * @return 直播间信息
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getRoomInfo(room_id: Long): LiveRoom? {
        val url = "https://api.live.bilibili.com/room/v1/Room/get_info" + NetWorkUtil.FormData().setUrlParam(true)
                .put("room_id", room_id)
        val result = NetWorkUtil.getJson(url)
        if (result.getInt("code") != 0) throw JSONException(result.getString("message"))

        val data = result.optJSONObject("data")
        if (data != null) {
            return analyzeLiveRooms(JSONArray().put(data)).get(0) //我认为没问题（
        }
        return null
    }

    /**
     * 获取直播间PlayInfo
     *
     * @param roomId 直播间id
     * @param qn     清晰度
     * @return 直播间PlayInfo
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getRoomPlayInfo(roomId: Long, qn: Int): LivePlayInfo? {
        val url = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo" + NetWorkUtil.FormData().setUrlParam(true)
                .put("room_id", roomId)
                .put("qn", qn)
                .put("protocol", "0,1")
                .put("format", "0,1,2")
                .put("codec", "0,1,2")
                .put("platform", "web")
                .put("ptype", 8)
                .put("dolby", 5)
                .put("panorama", 1)
        val result = NetWorkUtil.getJson(url)
        if (result.getInt("code") != 0) throw JSONException(result.getString("message"))

        val data = result.optJSONObject("data")
        if (data != null) {
            val livePlayInfo = LivePlayInfo()
            livePlayInfo.roomid = data.getLong("room_id")
            livePlayInfo.short_id = data.optLong("short_id", -1)
            livePlayInfo.uid = data.getLong("uid")
            livePlayInfo.isHidden = data.optBoolean("is_hidden", false)
            livePlayInfo.isLocked = data.optBoolean("is_locked", false)
            livePlayInfo.isPortrait = data.optBoolean("is_portrait", false)
            livePlayInfo.live_status = data.optInt("live_status", -1)
            livePlayInfo.encrypted = data.optBoolean("encrypted", false)
            livePlayInfo.pwd_verified = data.optBoolean("pwd_verified", false)
            livePlayInfo.live_time = data.optLong("live_time", -1)
            val playurl_info = data.optJSONObject("playurl_info")
            if (playurl_info != null) {
                livePlayInfo.conf_json = playurl_info.optString("conf_json")
                val play_url = playurl_info.optJSONObject("playurl")
                if (play_url != null) {
                    val playUrl = LivePlayInfo.PlayUrl()
                    playUrl.cid = play_url.optLong("cid", -1)
                    val g_qn_descs = play_url.optJSONArray("g_qn_desc")
                    if (g_qn_descs != null) {
                        val qnDescs: MutableList<LivePlayInfo.QnDesc> = ArrayList()
                        for (i in 0 until g_qn_descs.length()) {
                            val jsonObject = g_qn_descs.getJSONObject(i)
                            val qnDesc = LivePlayInfo.QnDesc()
                            qnDesc.qn = jsonObject.optInt("qn", -1)
                            qnDesc.desc = jsonObject.optString("desc")
                            qnDesc.hdr_desc = jsonObject.optString("hdr_desc")
                            qnDesc.attr_desc = jsonObject.optString("attr_desc")
                            qnDescs.add(qnDesc)
                        }
                        playUrl.g_qn_desc = qnDescs
                    }
                    val streams = play_url.optJSONArray("stream")
                    if (streams != null) {
                        val protocolInfos: MutableList<LivePlayInfo.ProtocolInfo> = ArrayList()
                        for (i in 0 until streams.length()) {
                            val protocol = streams.getJSONObject(i)
                            val protocolInfo = LivePlayInfo.ProtocolInfo()
                            protocolInfo.protocol_name = protocol.getString("protocol_name")
                            val formats = protocol.optJSONArray("format")
                            if (formats != null) {
                                val formatList: MutableList<LivePlayInfo.Format> = ArrayList()
                                for (j in 0 until formats.length()) {
                                    val format = formats.getJSONObject(j)
                                    val formatInfo = LivePlayInfo.Format()
                                    formatInfo.format_name = format.getString("format_name")
                                    val codecs = format.optJSONArray("codec")
                                    if (codecs != null) {
                                        val codecList: MutableList<LivePlayInfo.Codec> = ArrayList()
                                        for (k in 0 until codecs.length()) {
                                            val codec = codecs.getJSONObject(k)
                                            val codecInfo = LivePlayInfo.Codec()
                                            codecInfo.codec_name = codec.getString("codec_name")
                                            codecInfo.current_qn = codec.optInt("current_qn", -1)
                                            val accept_qns = codec.optJSONArray("accept_qn")
                                            if (accept_qns != null) {
                                                val acceptQnList: MutableList<Int> = ArrayList()
                                                for (l in 0 until accept_qns.length()) {
                                                    acceptQnList.add(accept_qns.getInt(l))
                                                }
                                                codecInfo.accept_qn = acceptQnList
                                            }
                                            codecInfo.base_url = codec.getString("base_url")
                                            val url_infos = codec.optJSONArray("url_info")
                                            if (url_infos != null) {
                                                val urlInfos: MutableList<LivePlayInfo.UrlInfo> = ArrayList()
                                                for (l in 0 until url_infos.length()) {
                                                    val url_info = url_infos.getJSONObject(l)
                                                    val urlInfo = LivePlayInfo.UrlInfo()
                                                    urlInfo.host = url_info.getString("host")
                                                    urlInfo.extra = url_info.optString("extra")
                                                    urlInfo.stream_ttl = url_info.optInt("stream_ttl")
                                                    urlInfos.add(urlInfo)
                                                }
                                                codecInfo.url_info = urlInfos
                                            }
                                            codecInfo.hdr_qn = codec.optInt("hdr_qn", -1)
                                            codecInfo.dolby_type = codec.optInt("dolby_type", -1)
                                            codecInfo.attr_name = codec.optString("attr_name")
                                            codecList.add(codecInfo)
                                        }
                                        formatInfo.codec = codecList
                                        formatInfo.master_url = format.optString("master_url")
                                    }
                                    formatList.add(formatInfo)
                                }
                                protocolInfo.format = formatList
                            }
                            protocolInfos.add(protocolInfo)
                        }
                        playUrl.stream = protocolInfos
                    }
                    val p2p_data = play_url.optJSONObject("p2p_data")
                    if (p2p_data != null) {
                        val p2PData = LivePlayInfo.P2PData()
                        p2PData.p2p = p2p_data.optBoolean("p2p")
                        p2PData.p2p_type = p2p_data.optInt("p2p_type", -1)
                        p2PData.m_p2p = p2p_data.optBoolean("m_p2p")
                        val m_servers = p2p_data.optJSONArray("m_servers")
                        if (m_servers != null) {
                            val mServers: MutableList<String> = ArrayList()
                            for (i in 0 until m_servers.length()) {
                                mServers.add(m_servers.optString(i))
                            }
                            p2PData.m_servers = mServers
                        }
                    }
                    playUrl.dolby_qn = play_url.optInt("dolby_qn", -1)
                    livePlayInfo.playUrl = playUrl
                }
            }
            livePlayInfo.official_type = data.optInt("official_type", -1)
            livePlayInfo.official_room_id = data.optInt("official_room_id", -1)
            livePlayInfo.risk_with_delay = data.optInt("risk_with_delay", -1)
            return livePlayInfo
        }
        return null
    }

    @JvmSuppressWildcards
    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeLiveRooms(list: JSONArray): List<LiveRoom> {
        val liveRooms: MutableList<LiveRoom> = ArrayList()
        for (i in 0 until list.length()) {
            val jsonObject = list.getJSONObject(i)
            val liveRoom = LiveRoom()
            liveRoom.roomid = jsonObject.optLong("roomid", -1)
            if (liveRoom.roomid == -1L) {
                liveRoom.roomid = jsonObject.getLong("room_id")
            }
            liveRoom.short_id = jsonObject.optLong("short_id", 0)
            liveRoom.uid = jsonObject.getLong("uid")
            liveRoom.title = jsonObject.getString("title")
            liveRoom.uname = jsonObject.optString("uname")
            liveRoom.tags = jsonObject.optString("tags")
            liveRoom.description = jsonObject.optString("description")
            liveRoom.online = jsonObject.optInt("online", -1)
            liveRoom.attention = jsonObject.optInt("attention", -1)
            liveRoom.user_cover = jsonObject.optString("user_cover")
            liveRoom.user_cover_flag = jsonObject.optInt("user_cover_flag", -1)
            liveRoom.system_cover = jsonObject.optString("system_cover")
            liveRoom.cover = jsonObject.optString("cover")
            if (TextUtils.isEmpty(liveRoom.cover)) {
                liveRoom.cover = jsonObject.optString("cover_from_user")
            }
            liveRoom.keyframe = jsonObject.optString("keyframe")
            liveRoom.show_cover = jsonObject.optString("show_cover")
            liveRoom.face = jsonObject.optString("face")
            liveRoom.area_parent_id = jsonObject.optInt("area_v2_parent_id", -1)
            if (liveRoom.area_parent_id == -1) {
                liveRoom.area_parent_id = jsonObject.optInt("parent_area_id", -1)
            }
            liveRoom.area_parent_name = jsonObject.optString("area_v2_parent_name")
            if (TextUtils.isEmpty(liveRoom.area_parent_name)) {
                liveRoom.area_parent_name = jsonObject.optString("parent_area_name")
            }
            liveRoom.area_id = jsonObject.optInt("area_v2_id", -1)
            if (liveRoom.area_id == -1) {
                liveRoom.area_id = jsonObject.optInt("area_id", -1)
            }
            liveRoom.area_name = jsonObject.optString("area_v2_name")
            if (TextUtils.isEmpty(liveRoom.area_name)) {
                liveRoom.area_name = jsonObject.optString("area_name")
            }
            liveRoom.session_id = jsonObject.optString("session_id")
            liveRoom.group_id = jsonObject.optInt("group_id").toLong()
            liveRoom.show_callback = jsonObject.optString("show_callback")
            liveRoom.click_callback = jsonObject.optString("click_callback")
            liveRoom.live_status = jsonObject.optInt("live_status", -1)
            liveRoom.old_area_id = jsonObject.optInt("old_area_id", -1)
            liveRoom.background = jsonObject.optString("background")
            liveRoom.is_portrait = jsonObject.optBoolean("is_portrait", false)
            liveRoom.room_silent_type = jsonObject.optString("room_silent_type")
            liveRoom.room_silent_level = jsonObject.optInt("room_silent_level", -1)
            liveRoom.room_silent_second = jsonObject.optInt("room_silent_second", -1)
            liveRoom.pk_status = jsonObject.optInt("pk_status", -1)
            liveRoom.pk_id = jsonObject.optLong("pk_id", -1)
            liveRoom.battle_id = jsonObject.optLong("battle_id", -1)
            liveRoom.allow_change_area_time = jsonObject.optInt("allow_change_area_time", -1)
            liveRoom.allow_upload_cover_time = jsonObject.optInt("allow_upload_cover_time", -1)
            val verify = jsonObject.optJSONObject("verify")
            if (verify != null) {
                val verifyObj = LiveRoom.Verify()
                verifyObj.desc = verify.optString("desc")
                verifyObj.type = verify.optInt("type", -1)
                verifyObj.role = verify.optInt("role", -1)
                liveRoom.verify = verifyObj
            }
            val watched_show = jsonObject.optJSONObject("watched_show")
            if (watched_show != null) {
                val watched = LiveRoom.Watched()
                watched.isSwitch = watched_show.optBoolean("switch", false)
                watched.num = watched_show.optInt("num", -1)
                watched.text_small = watched_show.optString("text_small")
                watched.text_large = watched_show.optString("text_large")
                watched.icon = watched_show.optString("icon")
                watched.icon_location = watched_show.optInt("icon_location", -1)
                watched.icon_web = watched_show.optString("icon_web")
                liveRoom.watched_show = watched
            }
            val new_pendants = jsonObject.optJSONObject("new_pendants")
            if (new_pendants != null) {
                val pendants = LiveRoom.NewPendants()
                val frame = new_pendants.optJSONObject("frame")
                if (frame != null) {
                    pendants.frame = parseFrameInfo(frame)
                }
                val mobile_frame = new_pendants.optJSONObject("mobile_frame")
                if (mobile_frame != null) {
                    pendants.mobile_frame = parseFrameInfo(mobile_frame)
                }
                val badge = new_pendants.optJSONObject("badge")
                if (badge != null) {
                    pendants.badge = parseBadgeInfo(badge)
                }
                val mobile_badge = new_pendants.optJSONObject("mobile_badge")
                if (mobile_badge != null) {
                    pendants.mobile_badge = parseBadgeInfo(mobile_badge)
                }
                liveRoom.new_pendants = pendants
            }
            val studio_info = jsonObject.optJSONObject("studio_info")
            if (studio_info != null) {
                val studioInfoObj = LiveRoom.StudioInfo()
                studioInfoObj.status = studio_info.optInt("status", -1)
                val masterList: MutableList<Any?> = ArrayList()
                studioInfoObj.master_list = masterList
                val master_list = studio_info.optJSONArray("master_list")
                if (master_list != null) {
                    for (j in 0 until master_list.length()) {
                        masterList.add(master_list.opt(j))
                    }
                }
                liveRoom.studio_info = studioInfoObj
            }
            val live_time = jsonObject.optLong("live_time", -1)
            @Suppress("SimpleDateFormat") val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            if (live_time != -1L) liveRoom.liveTime = sdf.format(live_time * 1000)
            else liveRoom.liveTime = jsonObject.optString("live_time")
            liveRooms.add(liveRoom)
        }
        return liveRooms
    }

    @Throws(JSONException::class)
    private fun parseFrameInfo(frameObj: JSONObject): LiveRoom.FrameInfo {
        val frameInfo = LiveRoom.FrameInfo()
        frameInfo.name = frameObj.optString("name")
        frameInfo.value = frameObj.optString("value")
        frameInfo.position = frameObj.optInt("position", -1)
        frameInfo.desc = frameObj.optString("desc")
        frameInfo.area = frameObj.optInt("area", -1)
        frameInfo.area_old = frameObj.optInt("area_old", -1)
        frameInfo.bg_color = frameObj.optString("bg_color")
        frameInfo.bg_pic = frameObj.optString("bg_pic")
        frameInfo.use_old_area = frameObj.optBoolean("use_old_area", false)
        return frameInfo
    }

    @Throws(JSONException::class)
    private fun parseBadgeInfo(badgeObj: JSONObject): LiveRoom.BadgeInfo {
        val badgeInfo = LiveRoom.BadgeInfo()
        badgeInfo.name = badgeObj.optString("name")
        badgeInfo.position = badgeObj.optInt("position", -1)
        badgeInfo.value = badgeObj.optString("value")
        badgeInfo.desc = badgeObj.optString("desc")
        return badgeInfo
    }
}
