package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.AccountStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import java.io.IOException

object HistoryApi {

    /**
     * 上传历史记录
     *
     * @param aid      视频aid
     * @param cid      分集cid
     * @param progress 观看进度，单位为s
     */
    @Throws(IOException::class)
    @JvmStatic
    fun reportHistory(aid: Long, cid: Long, progress: Long) {
        // v5.2.1 通道适配（审计报告 P0-4）：以前这条只会走 web（Cookie + csrf），
        // web 退出后即使 APP 端口登录着也报不上去 —— 用户体感是「看了两小时，历史里一条没有」。
        //
        // 为什么可以换通道：`x/v2/history/report` 是**早期 APP 原生端点**（`platform=pc` 只是
        // 历史遗留参数），APP 侧的 `x/v2/history/report` + access_key 是被接受的写法；
        // 而 web 侧的鉴权（csrf）在 APP 端口下根本拿不到（csrf 是 web 专属键）。
        //
        // 做法与 LikeCoinFavApi 一致：web 端口走原路（逐字节不变），APP 端口自签 access_key。
        // 失败**静默**：上报进度是后台副作用，不能因为它给用户弹错误框打断播放。
        val channel = appReportChannel()
        if (channel == null) {
            val url = "https://api.bilibili.com/x/v2/history/report"
            val per = "aid=" + aid + "&cid=" + cid +
                    "&progress=" + (if (progress >= 0) progress else "") +
                    "&platform=pc" +
                    "&csrf=" + SharedPreferencesUtil.getString(SharedPreferencesUtil.csrf, "")
            NetWorkUtil.post(url, per, NetWorkUtil.webHeaders)
            return
        }
        try {
            val (identity, token) = channel
            val form = NetWorkUtil.FormData().setUrlParam(true)
                .put("access_key", token)
                .put("aid", aid)
                .put("cid", cid)
                // progress 会随秒增长，-1 表示「不指定进度（只记锚点）」，此时不该发空串参数
                .put("progress", if (progress >= 0) progress else 0)
                .put("platform", "android")
                .put("mobi_app", identity.feedMobiApp)
                .put("build", identity.feedBuild)
                .put("device", identity.feedDevice)
                .put("device_name", identity.feedDeviceName)
                .put("c_locale", "zh_CN")
                .put("s_locale", "zh_CN")
            AppRcmdApi.sign(form, identity)
            // 为什么不用 NetWorkUtil.post：那个入口会先过 ApiRouter.planPost，
            // 而路由层会按规则**再补一次** access_key/sign（它并不知道我们已经签过了），
            // 结果是同一组参数出现两遍、sign 被重算 —— 服务端按字典序拼参数验签，
            // 重复键会让签名对不上（-663）。这里直接用底层 OkHttp 发一次，
            // 参数与签名**只由本函数生成一遍**，可控。
            val body = form.toString().removePrefix("?")
            val mediaType = "application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()
            val builder = Request.Builder().url(APP_REPORT_URL).post(body.toRequestBody(mediaType))
            val appHeaders = AppRcmdApi.appHeaders(identity)
            var i = 0
            while (i + 1 < appHeaders.size) {
                builder.addHeader(appHeaders[i], appHeaders[i + 1])
                i += 2
            }
            NetWorkUtil.getOkHttpInstance().newCall(builder.build()).execute().close()
        } catch (ignored: Exception) {
            // 上报失败不影响播放：下一次心跳/退出还会再报一次
        }
    }

    /** APP 历史上报端点（`platform=android` 口径） */
    private const val APP_REPORT_URL = "https://api.bilibili.com/x/v2/history/report"

    /**
     * 取「当前是否该走 APP 通道上报」：APP 端口 + 有身份 + 有 token 才算。
     *
     * 与 `LikeCoinFavApi.appChannel` 是同一条判断，但**故意各自实现不共享**：
     * 这两个 API 类在 v5.2.1 的并行开发里写域不同，硬抽公共函数会让两边都改一次；
     * 等 gateway（`api/gateway/CredentialStore`）落地后应统一收敛到那里（见 notes/gateway_contract）。
     */
    private fun appReportChannel(): Pair<AppRcmdApi.RcmdIdentity, String>? {
        val port = AccountStore.mainPort()
        if (port == AccountStore.PORT_WEB) return null
        val identity = AccountStore.mainIdentity() ?: return null
        val token = AccountStore.activeAccessToken(port)
        if (token.isEmpty()) return null
        return identity to token
    }

    /**
     * 获取视频历史记录
     *
     * @param lastResult 上一次获取返回的ApiResult，如果是第一次就传入新对象
     * @param videoList  已有的视频列表
     * @return 新的ApiResult，包含了返回码、文本信息以及翻页所需的offset
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getHistory(lastResult: ApiResult, videoList: MutableList<VideoCard>): ApiResult {
        val url = "https://api.bilibili.com/x/web-interface/history/cursor?type=archive&view_at=" + lastResult.timestamp + "&business=" + lastResult.business + "&max=" + lastResult.offset
        val result = NetWorkUtil.getJson(url)
        val apiResult = ApiResult(result)
        if (!result.isNull("data")) {
            val data = result.getJSONObject("data")
            val list = data.getJSONArray("list")
            for (i in 0 until list.length()) {
                val videoCard = list.getJSONObject(i)
                val title = videoCard.getString("title")
                val cover = videoCard.getString("cover")
                val upName = videoCard.getString("author_name")
                val progress = videoCard.getInt("progress")

                val history = videoCard.getJSONObject("history")
                val aid = history.getLong("oid")
                val bvid = history.getString("bvid")

                val viewStr: String
                if (progress == 0) viewStr = "还没看过"
                else viewStr = "看到" + StringUtil.toTime(videoCard.getInt("progress"))

                videoList.add(VideoCard(title, upName, viewStr, cover, aid, bvid))
            }
            if (list.length() == 0) apiResult.isBottom = true

            val cursor = data.getJSONObject("cursor")
            apiResult.business = cursor.optString("business")
            apiResult.offset = cursor.optLong("max")
            apiResult.timestamp = cursor.optLong("view_at")
        }
        return apiResult
    }

}
