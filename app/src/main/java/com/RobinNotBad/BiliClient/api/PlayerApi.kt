package com.RobinNotBad.BiliClient.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log

import androidx.core.content.FileProvider

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.player.PlayerActivity
import com.RobinNotBad.BiliClient.model.DashAudioStream
import com.RobinNotBad.BiliClient.model.DashData
import com.RobinNotBad.BiliClient.model.DashVideoStream
import com.RobinNotBad.BiliClient.model.HighEnergyData
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.Subtitle
import com.RobinNotBad.BiliClient.model.SubtitleLink
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.model.ViewPoint
import com.RobinNotBad.BiliClient.service.DownloadService
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.File
import java.io.IOException
import java.io.Serializable
import java.util.Arrays
import java.util.HashMap
import java.util.Map

object PlayerApi {
    /**
     * 直接使用内置播放器播放
     *
     * 原 JumpToPlayerActivity 已删除（去除播放器跳转逻辑），获取播放地址这一步
     * 改为在后台线程完成后再启动内置播放器。
     * 进度上报已改为 PlayerActivity 退出时后台异步执行（见 PlayerActivity#finish）。
     *
     * @param playerData 传入aid、cid、qn等必要数据
     */
    // #6：播放器防重复打开。地址解析是异步的，解析完成后才 startActivity，
    // 连续点击"播放"会触发两次解析 + 两个 PlayerActivity 实例。
    // 用时间戳去抖 + 解析进行中标志双保险。
    private const val PLAYER_LAUNCH_DEBOUNCE_MS = 800L

    @Volatile
    private var lastPlayerLaunchAt = 0L

    @Volatile
    private var playerLaunchInFlight = false

    @JvmStatic
    fun startGettingUrl(playerData: PlayerData) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (playerLaunchInFlight) {
                Logu.d("播放器", "解析进行中，忽略重复打开请求")
                return
            }
            if (now - lastPlayerLaunchAt < PLAYER_LAUNCH_DEBOUNCE_MS) {
                Logu.d("播放器", "启动过于频繁，忽略重复打开请求")
                return
            }
            lastPlayerLaunchAt = now
            playerLaunchInFlight = true
        }

        //原 JumpToPlayerActivity 会在这里补默认清晰度，此处保持一致
        if (playerData.qn == -1) {
            playerData.qn = SharedPreferencesUtil.getInt("play_qn", 16)
        }

        if (playerData.isLocal()) {
            //本地文件：无需请求，直接播放
            playerLaunchInFlight = false
            val context = BiliTerminal.context
            context.startActivity(buildPlayerIntent(playerData).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        CenterThreadPool.run(Runnable {
            try {
                if (playerData.isBangumi()) getBangumi(playerData)
                else getVideo(playerData, false)
                val context = BiliTerminal.context
                CenterThreadPool.runOnUiThread(Runnable {
                    context.startActivity(buildPlayerIntent(playerData).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            } catch (e: Exception) {
                MsgUtil.showMsg("视频获取失败！\n可能的原因：\n1.本视频仅大会员可播放\n2.视频获取接口失效")
                e.printStackTrace()
            } finally {
                playerLaunchInFlight = false
            }
        })
    }

    @JvmStatic
    fun startDownloading(videoInfo: VideoInfo, page: Int, qn: Int) {
        if (videoInfo.cids!!.size == 1)
            DownloadService.startDownload(videoInfo.title!!,
                    videoInfo.aid, videoInfo.cids!!.get(0),
                    videoInfo.cover,
                    qn, "video", "")
        else
            DownloadService.startDownload(videoInfo.title!!, videoInfo.pagenames!!.get(page),
                    videoInfo.aid, videoInfo.cids!!.get(page),
                    videoInfo.cover,
                    qn, "video", "")
    }

    /**
     * 开始仅音频下载
     *
     * @param videoInfo 视频信息
     * @param page      页码
     * @param qn        清晰度
     * @param audioUrl  音频流URL
     */
    @JvmStatic
    fun startDownloadingAudioOnly(videoInfo: VideoInfo, page: Int, qn: Int, audioUrl: String?) {
        if (videoInfo.cids!!.size == 1)
            DownloadService.startDownload(videoInfo.title!!,
                    videoInfo.aid, videoInfo.cids!!.get(0),
                    videoInfo.cover,
                    qn, "audio_only", audioUrl)
        else
            DownloadService.startDownload(videoInfo.title!!, videoInfo.pagenames!!.get(page),
                    videoInfo.aid, videoInfo.cids!!.get(page),
                    videoInfo.cover,
                    qn, "audio_only", audioUrl)
    }

    /**
     * 解析视频（DASH格式）
     *
     * @param playerData 传入aid、cid、qn等必要数据
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getVideoDash(playerData: PlayerData) {
        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml"

        var url = "https://api.bilibili.com/x/player/wbi/playurl?" +
                "avid=" + playerData.aid +
                "&cid=" + playerData.cid +
                "&qn=" + playerData.qn +
                "&fnval=16&fnver=0" + // 16:DASH格式
                "&platform=pc" +
                "&voice_balance=1" +
                "&gaia_source=pre-load" +
                "&isGaiaAvoided=true"

        url = ConfInfoApi.signWBI(url)

        val body = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)
        val data = body.getJSONObject("data")

        // 解析DASH数据
        if (data.has("dash")) {
            val dashJson = data.getJSONObject("dash")
            playerData.dashData = DashData.fromJson(dashJson)

            // 设置视频URL（选择指定清晰度的视频流）
            val videoStream = playerData.dashData!!.getVideoStream(playerData.qn)
            if (videoStream != null) {
                playerData.videoUrl = videoStream.baseUrl
            }

            // 设置音频URL（选择最高质量的音频流）
            val audioStream = playerData.dashData!!.getBestAudioStream()
            if (audioStream != null) {
                playerData.audioUrl = audioStream.baseUrl
            }
        } else {
            getVideo(playerData, true)
            return
        }

        applyHistoryAnchor(playerData, data)

        val accept_description = data.getJSONArray("accept_description")
        val accept_quality = data.getJSONArray("accept_quality")
        val qnStrList = arrayOfNulls<String>(accept_description.length())
        val qnValueList = IntArray(accept_description.length())
        for (i in qnStrList.indices) {
            qnStrList[i] = accept_description.optString(i)
            qnValueList[i] = accept_quality.optInt(i)
        }
        Logu.d("qn_str", Arrays.toString(qnStrList))
        Logu.d("qn_val", Arrays.toString(qnValueList))
        playerData.qnStrList = qnStrList
        playerData.qnValueList = qnValueList
    }

    /**
     * 把响应里的 `last_play_cid` / `last_play_time` 落到 [PlayerData]，**两者必须成对使用**。
     *
     * 背景（完整证据链见 `notes/api_full_migration_alpha10.md` D.6）：
     * 开启全 API 迁移后，这两个字段来自 APP 通道那个账号的播放历史
     * （HD / TV / 手机身份与 web Cookie 是**两套独立账号体系**，见 AppRcmdApi 注释），
     * 可能指向**另一个分 P**。而 [buildPlayerIntent] 会把 `cid` 与 `progress`
     * 分别塞进 Intent，`PlayerActivity` 直接 `seekTo(progress_history)` ——
     * 于是画面被 seek 到「别的 P 的时间轴位置」，而字幕是按当前 cid 取的（时间轴正确），
     * 表现出来就是「视频与字幕不对应」。
     *
     * 规则：只有 `last_play_cid == 本次请求的 cid` 时才采信 `last_play_time`；
     * 指向别的 P 时 `progress` 归零（`cidHistory` 仍保留，用于「上次看到 Px」的展示与归位）。
     * 自检日志脱敏：只打 cid 与是否采信，不打 URL / token。
     */
    private fun applyHistoryAnchor(playerData: PlayerData, data: JSONObject) {
        val lastPlayCid = data.optLong("last_play_cid", 0)
        val lastPlayTime = data.optInt("last_play_time", 0)
        val samePage = lastPlayCid != 0L && lastPlayCid == playerData.cid
        playerData.cidHistory = if (lastPlayCid != 0L) lastPlayCid else playerData.cid
        playerData.progress = if (samePage) lastPlayTime else 0
        Logu.d("player-cid-check", "reqCid=" + playerData.cid +
                " lastPlayCid=" + lastPlayCid +
                " keepProgress=" + samePage +
                " progress=" + playerData.progress)
    }

    /**
     * 解析视频
     *
     * @param playerData 传入aid、cid、qn等必要数据，可以使用VideoInfo.toPlayerData
     * @param download   是否下载
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getVideo(playerData: PlayerData, download: Boolean) {
        // 如果上一次获取在十分钟内就无需再次获取了
        if (System.currentTimeMillis() - playerData.timeStamp < 600000)
            return

        playerData.timeStamp = System.currentTimeMillis()

        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml"

        val html5 = !download && SharedPreferencesUtil.getString("player", "") == "mtvPlayer"
        // html5方式现在已经仅对小电视播放器保留了

        var url = "https://api.bilibili.com/x/player/wbi/playurl?" +
                "avid=" + playerData.aid +
                "&cid=" + playerData.cid +
                (if (html5) "&high_quality=1" else "") +
                "&qn=" + playerData.qn +
                "&fnval=1&fnver=0" +
                "&platform=" + (if (html5) "html5" else "pc") +
                "&voice_balance=1" +
                "&gaia_source=pre-load" +
                "&isGaiaAvoided=true"

        url = ConfInfoApi.signWBI(url)

        val body = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)
        val data = body.getJSONObject("data")
        val durl = data.getJSONArray("durl")
        val video_url = durl.getJSONObject(0)
        playerData.videoUrl = video_url.getString("url")
        applyHistoryAnchor(playerData, data)

        val accept_description = data.getJSONArray("accept_description")
        val accept_quality = data.getJSONArray("accept_quality")
        val qnStrList = arrayOfNulls<String>(accept_description.length())
        val qnValueList = IntArray(accept_description.length())
        for (i in qnStrList.indices) {
            qnStrList[i] = accept_description.optString(i)
            qnValueList[i] = accept_quality.optInt(i)
        }
        Logu.d("qn_str", Arrays.toString(qnStrList))
        Logu.d("qn_val", Arrays.toString(qnValueList))
        playerData.qnStrList = qnStrList
        playerData.qnValueList = qnValueList

    }


    /**
     * 解析番剧，和普通视频的api不一样
     *
     * @param playerData 传入aid、cid、qn等必要数据
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getBangumi(playerData: PlayerData) {
        val reqData = NetWorkUtil.FormData()
                .setUrlParam(true)
                .put("aid", playerData.aid)
                .put("cid", playerData.cid)
                .put("fnval", 1)
                .put("fnvar", 0)
                .put("qn", playerData.qn)
                .put("season_type", 1)
                .put("session",
                        ToolsUtil.md5(
                                (System.currentTimeMillis() - SystemClock.currentThreadTimeMillis()).toString()))
                .put("platform", "pc")

        val url = "https://api.bilibili.com/pgc/player/web/playurl" + reqData.toString()

        val body = NetWorkUtil.getJson(url)
        Logu.v(body.toString())

        val data = body.getJSONObject("result")
        val durl = data.getJSONArray("durl")
        val video_url = durl.getJSONObject(0)
        playerData.videoUrl = video_url.getString("url")

        playerData.danmakuUrl = "https://comment.bilibili.com/" + playerData.cid + ".xml"

        val accept_description = data.getJSONArray("accept_description")
        val accept_quality = data.getJSONArray("accept_quality")
        val qnStrList = arrayOfNulls<String>(accept_description.length())
        val qnValueList = IntArray(accept_description.length())
        for (i in qnStrList.indices) {
            qnStrList[i] = accept_description.optString(i)
            qnValueList[i] = accept_quality.optInt(i)
        }
        playerData.qnStrList = qnStrList
        playerData.qnValueList = qnValueList
    }

    /**
     * 构造内置播放器的启动 Intent（已去掉第三方播放器跳转逻辑）
     *
     * @param playerData 传入aid、cid、qn等必要数据
     */
    @JvmStatic
    fun buildPlayerIntent(playerData: PlayerData): Intent {
        val context = BiliTerminal.context
        Logu.v("准备播放", "--------")
        Logu.v("视频标题", playerData.title)
        Logu.v("视频地址", playerData.videoUrl)
        Logu.v("弹幕地址", playerData.danmakuUrl)
        Logu.v("准备播放", "--------")

        val intent = Intent()
        intent.setClass(context, PlayerActivity::class.java)
        intent.putExtra("url", playerData.videoUrl)
        intent.putExtra("danmaku", playerData.danmakuUrl)
        intent.putExtra("title", playerData.title)
        intent.putExtra("aid", playerData.aid)
        intent.putExtra("cid", playerData.cid)
        intent.putExtra("mid", playerData.mid)
        intent.putExtra("progress", playerData.progress)
        intent.putExtra("live_mode", playerData.isLive())
        if (playerData.qnStrList != null && playerData.qnValueList != null) {
            intent.putExtra("qnStrList", playerData.qnStrList)
            intent.putExtra("qnValueList", playerData.qnValueList)
            intent.putExtra("currentQuality", playerData.qn)
        }
        val pagenames = playerData.pagenames
        val cids = playerData.cids
        if (pagenames != null && cids != null && pagenames.size > 1) {
            intent.putStringArrayListExtra("pagenames", pagenames)
            val cidArray = LongArray(cids.size)
            for (i in cids.indices) {
                cidArray[i] = cids[i]
            }
            intent.putExtra("cids", cidArray)
            intent.putExtra("currentPageIndex", playerData.currentPageIndex)
        }
        return intent
    }

    @JvmStatic
    fun getVideoUri(context: Context, path: String): Uri {
        val file = File(path)
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file)

        // 因为在文件夹里放了.nomedia标识，现在不能用这个了
        /*
         * Cursor cursor = context.getContentResolver().query(MediaStore.Video.Media.
         * EXTERNAL_CONTENT_URI,
         * new String[]{MediaStore.Video.Media._ID},
         * MediaStore.Video.Media.DATA + "=? ",
         * new String[]{path}, null);
         * if (cursor != null && cursor.moveToFirst()) {
         *
         * @SuppressLint("Range") int id =
         * cursor.getInt(cursor.getColumnIndex(MediaStore.Video.VideoColumns._ID));
         * Uri baseUri = Uri.parse("content://media/external/video/media");
         * cursor.close();
         * return Uri.withAppendedPath(baseUri, String.valueOf(id));
         * } else {
         * if (cursor != null) cursor.close();
         * ContentValues values = new ContentValues();
         * values.put(MediaStore.Video.Media.DATA, path);
         * return context.getContentResolver().insert(MediaStore.Video.Media.
         * EXTERNAL_CONTENT_URI, values);
         * }
         */
    }

    /**
     * 通过本地文件获取字幕
     *
     * @param folder 字幕文件夹
     * @return 字幕列表
     */
    @JvmStatic
    fun getSubtitleLinks(folder: File): Array<SubtitleLink?> {
        val files = folder.listFiles { _, name -> name.endsWith(".json") }
        val links = arrayOfNulls<SubtitleLink>(if (files != null) files.size + 1 else 1)
        if (files != null) {
            for (i in files.indices) {
                links[i] = SubtitleLink(i.toLong(), files[i].name, files[i].toString(), false)
            }
        }
        links[links.size - 1] = SubtitleLink(-1, "不显示字幕", "null", false)
        return links
    }

    /**
     * 「空字幕列表」：只有一项「不显示字幕」（id=-1）。
     *
     * 调用方（`PlayerActivity.downSubtitle` / `DownloadService.downSubtitles`）用
     * `size == 1` / `size <= 1` 判定「本视频无字幕」，所以这里必须是 1 项而不是 0 项。
     */
    private fun emptySubtitleLinks(): Array<SubtitleLink?> {
        val links = arrayOfNulls<SubtitleLink>(1)
        links[0] = SubtitleLink(-1, "不显示字幕", "null", false)
        return links
    }

    /**
     * 获取视频的字幕链接列表
     *
     * @param aid aid
     * @param cid cid
     * @return 链接列表
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getSubtitleLinks(aid: Long, cid: Long): Array<SubtitleLink?> {
        // 播放地址与字幕必须来自同一组 (aid, cid)：这里的 cid 由调用方传入
        // （PlayerActivity 的 cid 来自 Intent / 分 P 切换，与 getVideoDash 请求的 cid 一致）。
        var url = "https://api.bilibili.com/x/player/wbi/v2?aid=" + aid +
                "&cid=" + cid
        url = ConfInfoApi.signWBI(url)
        val response = NetWorkUtil.getJson(url)
        if (!response.has("data") || response.isNull("data")) {
            Logu.d("subtitle-cid-check", "reqCid=" + cid + " respCid=- node=data 缺失，按无字幕处理")
            return emptySubtitleLinks()
        }
        val data = response.getJSONObject("data")

        // ---- 字幕必须与正在播放的流是同一个 cid（本函数的存在理由）----
        // 实测（notes/api_full_migration_alpha10.md D.5）：服务端**不校验** aid/bvid 与 cid
        // 的一致性，参数一旦错位就会把两个视频的东西混着返回；而 APP 通道的
        // /x/player/v2 与 web 的 /x/player/wbi/v2 行为完全相同，改正路径救不了。
        // 所以这里自己校验回包 cid：不一致就当作「无字幕」——
        // 宁可字幕暂时为空，也绝不显示别的视频的字幕。
        val respCid = data.optLong("cid", -1L)
        // respCid <= 0 表示回包没有 cid 字段（无法判断，按信任处理）；> 0 且不等于请求 cid 才拒绝。
        val accepted = respCid <= 0L || respCid == cid
        Logu.d("subtitle-cid-check", "reqCid=" + cid + " respCid=" + respCid + " accepted=" + accepted)
        if (!accepted) return emptySubtitleLinks()

        // 有些账号 / 番剧不回 subtitle 节点，按「无字幕」处理，不要抛 JSONException 打断播放
        val subtitleNode = data.optJSONObject("subtitle") ?: return emptySubtitleLinks()
        val subtitles = subtitleNode.optJSONArray("subtitles") ?: return emptySubtitleLinks()
        Log.d("subtitle", subtitles.toString())

        val links = arrayOfNulls<SubtitleLink>(subtitles.length() + 1)
        for (i in 0 until subtitles.length()) {
            val subtitle = subtitles.getJSONObject(i)

            val id = subtitle.getLong("id")
            val isAI = subtitle.getInt("type") == 1
            val lang = subtitle.getString("lan_doc")
            val subtitle_url = "https:" + subtitle.getString("subtitle_url")

            val link = SubtitleLink(id, lang, subtitle_url, isAI)
            links[i] = link
        }
        links[subtitles.length()] = SubtitleLink(-1, "不显示字幕", "null", false)
        return links
    }

    @JvmSuppressWildcards
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getViewPoints(aid: Long, cid: Long): MutableList<ViewPoint> {
        var url = "https://api.bilibili.com/x/player/wbi/v2?aid=" + aid +
                "&cid=" + cid
        url = ConfInfoApi.signWBI(url)
        val data = NetWorkUtil.getJson(url).getJSONObject("data")

        val viewPoints: MutableList<ViewPoint> = ArrayList()

        if (data.has("view_points")) {
            val viewPointsArray = data.getJSONArray("view_points")
            for (i in 0 until viewPointsArray.length()) {
                val vp = viewPointsArray.getJSONObject(i)
                val content = vp.optString("content", "")
                val from = vp.optInt("from", 0)
                val to = vp.optInt("to", 0)
                val type = vp.optInt("type", 0)
                val imgUrl = vp.optString("imgUrl", "")
                val logoUrl = vp.optString("logoUrl", "")

                viewPoints.add(ViewPoint(content, from, to, type, imgUrl, logoUrl))
            }
        }

        return viewPoints
    }

    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getInteractionGraphVersion(aid: Long, cid: Long): Long {
        var url = "https://api.bilibili.com/x/player/wbi/v2?aid=" + aid +
                "&cid=" + cid
        url = ConfInfoApi.signWBI(url)
        val data = NetWorkUtil.getJson(url).getJSONObject("data")

        if (data.has("interaction") && !data.isNull("interaction")) {
            val interaction = data.getJSONObject("interaction")
            if (interaction.has("graph_version")) {
                return interaction.getLong("graph_version")
            }
        }

        return 0
    }

    /**
     * 通过链接获取字幕
     *
     * @param url 传入链接，可通过getSubtitleLinks()获取
     * @return 逐条字幕的列表，每条包含文本和始末时间，时间以秒为单位
     */
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getSubtitle(url: String): Array<Subtitle?> {
        val body = NetWorkUtil.getJson(url).getJSONArray("body")
        val subtitles = arrayOfNulls<Subtitle>(body.length())
        for (i in 0 until body.length()) {
            val single = body.getJSONObject(i)
            subtitles[i] = Subtitle(
                    single.getString("content"),
                    single.getDouble("from"),
                    single.getDouble("to"))
        }
        return subtitles
    }

    /**
     * 通过本地文件获取字幕
     *
     * @param file 传入json文件
     * @return 逐条字幕的列表，每条包含文本和始末时间，时间以秒为单位
     */
    @Throws(JSONException::class)
    @JvmStatic
    fun getSubtitle(file: File): Array<Subtitle?>? {
        val str = FileUtil.readString(file)
        if (str == null)
            return null

        val body = JSONObject(str).getJSONArray("body")
        val subtitles = arrayOfNulls<Subtitle>(body.length())
        for (i in 0 until body.length()) {
            val single = body.getJSONObject(i)
            subtitles[i] = Subtitle(
                    single.getString("content"),
                    single.getDouble("from"),
                    single.getDouble("to"))
        }
        return subtitles
    }

    /**
     * 获取高能进度条数据
     */
    @JvmStatic
    fun getHighEnergyData(cid: Long, aid: Long): HighEnergyData? {
        try {
            var url = "https://bvc.bilivideo.com/pbp/data?cid=" + cid
            if (aid > 0) {
                url += "&aid=" + aid
            }

            val response = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)

            if (response == null) {
                Logu.w("高能进度条", "响应为空")
                return null
            }

            val code = response.optInt("code", -1)
            if (code != 0 && code != -1) {
                Logu.w("高能进度条", "API返回错误码: " + code)
                return null
            }

            val data = HighEnergyData()
            data.stepSec = response.optInt("step_sec", 10)
            data.tagStr = response.optString("tagstr", "")
            data.debug = response.optString("debug", "")

            val events = response.optJSONObject("events")
            if (events != null) {
                val defaultArray = events.optJSONArray("default")
                if (defaultArray != null && defaultArray.length() > 0) {
                    val eventData = FloatArray(defaultArray.length())
                    for (i in 0 until defaultArray.length()) {
                        eventData[i] = defaultArray.optDouble(i, 0.0).toFloat()
                    }
                    data.events = eventData
                    Logu.d("高能进度条", "成功获取 " + eventData.size + " 个数据点，采样间隔: " + data.stepSec + "秒")
                } else {
                    Logu.w("高能进度条", "default数组为空或不存在")
                    data.events = FloatArray(0)
                }
            } else {
                Logu.w("高能进度条", "events对象不存在")
                data.events = FloatArray(0)
            }

            return data
        } catch (e: Exception) {
            Logu.e("高能进度条", "获取失败: " + e.message)
            e.printStackTrace()
            return null
        }
    }
}
