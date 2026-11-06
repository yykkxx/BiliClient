package com.RobinNotBad.BiliClient.api

import android.util.Log

import com.RobinNotBad.BiliClient.model.ApiResult
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException

object LikeCoinFavApi {

    // ==========================================================================================
    // 凭据通道适配（v5.2.1「任意端口全功能」的核心，审计报告 P0-1 / P0-2 / P0-3）
    //
    // ## 为什么必须有这一段
    // 文档（bilibili-API-collect）对这三条 web 端点的认证方式全部标注为
    // 「**仅可 Cookie（SESSDATA）**」：
    // - 投币 `api.bilibili.com/x/web-interface/coin/add`
    // - 点赞 `api.bilibili.com/x/web-interface/archive/like`
    // - 三连 `api.bilibili.com/x/web-interface/archive/like/triple`
    //
    // 「仅可 Cookie」的含义是：**给同一个路径补 access_key 也无效**，服务端只认 SESSDATA。
    // 于是 v5.2.0 及更早版本的「同路径 + 补 APP 参数」策略在这三条上必然返回 -101
    // （账号未登录），再被 ApiRouter 当成失败去回退 web —— 而 web 退出后回退带的是空
    // Cookie，于是又一次 -101。用户看到的就是「投币失败：-101」。
    // 这就是用户说的「去掉 Web 登录之后半残」里最硬的那一块：**不是概率问题，是 100% 必然失败**。
    //
    // ## 改法：换端点，不是补参数
    // APP 端有**独立**的同功能端点，认证方式为「仅可 APP」（只要 access_key，不要 csrf）：
    // - 投币 `app.bilibili.com/x/v2/view/coin/add`（参数 access_key / aid / multiply / select_like）
    // - 点赞 `app.bilibili.com/x/v2/view/like`（参数 access_key / aid / like）
    // - 收藏 `medialist/gateway/coll/resource/deal`（本来就支持 APP，见 [favorite]）
    // 三连**没有**任何 APP 等价端点（PiliPlus 也是 web 端点 + csrf），所以拆成
    // 「点赞 + 投币 + 收藏」三次独立调用、本地拼结果 —— 功能等价，代价是没有服务端原子性
    // （中途失败可能只成功一部分）。这一点在 [triple] 的注释里对用户如实说明。
    //
    // ## 关键的语义陷阱（写错就会「点一次赞变取消」）
    // 点赞的 `like` 参数在两端**语义相反**：
    // - web 端：`like=1` 点赞、`like=2` 取消
    // - APP 端：`like=0` 点赞、`like=1` 取消
    // 所以适配层统一以「布尔 liked」为输入，由各实现自己翻译，调用方不再拼数字。
    //
    // ## 为什么保留旧函数（coin/like/triple 原签名一字不改）
    // `ApiMigrationRules` 里这些路径仍有规则、`VideoInfoFragment` / `StoryPlayerActivity`
    // 仍在调用；直接把签名改掉会让 detekt baseline（签名级匹配）失效。
    // 做法是：旧函数**内部改成先看端口**，web 端口走原来的 web 路径（行为逐字节一致），
    // APP 端口才换端点。对新代码同时提供语义清晰的 [likeOn]、[coinOn]、[tripleOn]。
    // ==========================================================================================

    // APP 端专用的两条写端点（**仅可 APP**：只要 access_key，不要 csrf；点赞语义与 web 相反）
    private const val APP_COIN_URL = "https://app.bilibili.com/x/v2/view/coin/add"
    private const val APP_LIKE_URL = "https://app.bilibili.com/x/v2/view/like"

    /**
     * 当前生效的凭据通道是不是 APP 端口（且该端口真的有 access_key）。
     *
     * 三个条件同时成立才走 APP 端点：
     * 1. `AccountStore.mainPort()` 不是 web（主端口是用户选的「其它页面 API」端口）；
     * 2. 存在该端口的身份（[AccountStore.mainIdentity] 非空）；
     * 3. 该端口真的有 access_token（[AccountStore.activeAccessToken] 非空）。
     *
     * 少任何一条就退回 web 路径 —— 这不是「回退 web」语义（那是要删除的东西），
     * 而是「用户本来就选了 web 端口，那就走 web 拼 csrf」。两者区别很关键：
     * 前者是**未登录却偷偷换通道**，后者是**用户明确选了 web**。
     */
    @JvmStatic
    private fun appChannel(): Pair<AppRcmdApi.RcmdIdentity, String>? {
        val port = AccountStore.mainPort()
        if (port == AccountStore.PORT_WEB) return null
        val identity = AccountStore.mainIdentity() ?: return null
        val token = AccountStore.activeAccessToken(port)
        if (token.isEmpty()) return null
        return identity to token
    }

    /**
     * 组装 APP 通道的公共参数并签名（照 `UserInfoApi.getAppUserCoin` 的既有自签范式）。
     *
     * 为什么这些参数一个都不能少：B 站 APP 端点会校验 `mobi_app`/`platform`/`build`
     * 与 appkey 是否同源，缺了会返回 -663「鉴权失败」而不是 -101。
     * `c_locale`/`s_locale` 是地区参数，缺省也会被部分端点拒。
     */
    private fun appForm(identity: AppRcmdApi.RcmdIdentity, token: String): NetWorkUtil.FormData =
        NetWorkUtil.FormData().setUrlParam(true)
            .put("access_key", token)
            .put("build", identity.feedBuild)
            .put("c_locale", "zh_CN")
            .put("channel", "master")
            .put("device", identity.feedDevice)
            .put("device_name", identity.feedDeviceName)
            .put("mobi_app", identity.feedMobiApp)
            .put("platform", "android")
            .put("s_locale", "zh_CN")

    /**
     * 点赞（v5.2.1 新增的语义化入口，**新代码请用它**）。
     *
     * @param liked true=点赞 / false=取消
     * @return B 站业务 code（0 = 成功）
     *
     * 语义翻译是这个函数的全部意义：调用方说「我要点赞」，而不是「传 1 还是 2」。
     * web 分支传 `1/2`，APP 分支传 `0/1` —— 这个差异绝不能由调用方记。
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun likeOn(aid: Long, liked: Boolean): Int {
        val channel = appChannel() ?: return like(aid, if (liked) 1 else 2)
        val (identity, token) = channel
        val form = appForm(identity, token)
            .put("aid", aid)
            // APP 端口语义反了：0=点赞 1=取消（见类注释「关键的语义陷阱」）
            .put("like", if (liked) 0 else 1)
        AppRcmdApi.sign(form, identity)
        val all = NetWorkUtil.getJson(APP_LIKE_URL + form, AppRcmdApi.appHeaders(identity))
        return all.optInt("code", -1)
    }

    /**
     * 投币（v5.2.1 新增的语义化入口，**新代码请用它**）。
     *
     * @param multiply 投几个币（1 或 2）
     * @param selectLike 投币时是否顺手点赞（APP 端点特有的参数，web 端点没有）。
     *   默认 0（不勾）。之所以把它暴露出来：B 站的投币弹窗里常有「同时点赞」勾选框，
     *   APP 端点能在**同一次请求**里完成，省一次往返也少一次失败机会。
     * @return B 站业务 code（0 = 成功）
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun coinOn(aid: Long, multiply: Int, selectLike: Int = 0): Int {
        val channel = appChannel() ?: return coin(aid, multiply)
        val (identity, token) = channel
        val form = appForm(identity, token)
            .put("aid", aid)
            .put("multiply", multiply)
            .put("select_like", selectLike)
        AppRcmdApi.sign(form, identity)
        val all = NetWorkUtil.getJson(APP_COIN_URL + form, AppRcmdApi.appHeaders(identity))
        return all.optInt("code", -1)
    }

    /**
     * 一键三连（旧入口，签名与返回语义保持不变）。
     *
     * ## v5.2.1 的行为变化（唯一一处，且是修补不是改语义）
     * web 端口：仍走 `x/web-interface/archive/like/triple`（原样）。
     * APP 端口：**没有 APP 等价端点**，改为「点赞 + 投币 + 收藏」三次独立调用。
     *
     * 为什么必须拆：三连的 web 端点同样标注「仅可 Cookie」，APP 端口调它必然 -101。
     * 而 APP 侧三个动作各有可用端点（见类注释），拆开就能做到**功能等价**。
     *
     * ## 拆开的代价（对用户如实说明，不要掩盖）
     * 没有服务端原子性：可能「赞点上了、币没投进」（例如币不足 34002）。
     * 所以返回值语义定为：
     * - `0`：三步全部成功；
     * - 第一步失败的原始 code（点赞失败）；
     * - 后续步骤失败时返回 `-1`，并由调用方提示「部分成功」。
     * 调用点（`VideoInfoFragment`）拿到非 0 时会提示失败，用户可以再点一次补齐 ——
     * 三个动作都是幂等的（重复点赞/重复投币服务端有 65006/34005 之类的保护），重试安全。
     */
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun triple(aid: Long): Int {
        // web 端口：保持原样（逐字节一致，避免任何回归）
        if (appChannel() == null) {
            val url = "https://api.bilibili.com/x/web-interface/archive/like/triple"
            val per = "aid=" + aid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

            val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body?.string() ?: "{}")
            Log.e("debug-三联", result.toString())
            return result.optInt("code", -1)
        }
        // APP 端口：拆成三步。顺序按用户感知排（点赞最快，先给正反馈）
        val likeCode = likeOn(aid, true)
        if (likeCode != 0) return likeCode
        val coinCode = coinOn(aid, 1)
        // 投币成功或「已经投过/币不够」都不算致命，收藏照样试；但要把首个真实失败记下来
        val coinOk = coinCode == 0 || coinCode == 34005
        val favCode = favorite(aid, favoriteTargetFid())
        if (!coinOk) return -1
        if (favCode != 0 && favCode != 112) return -1
        return 0
    }

    /**
     * 三连里「收藏」要投到哪个收藏夹。
     *
     * 复用 [favorite] 里那套「mid 末两位拼到 fid 后面」的历史约定：它是 B 站
     * 「默认收藏夹 id」的老写法（默认收藏夹 fid == mid，服务端要 fid+suffix）。
     * 这里抽成独立函数只是为了让 [triple] 可读，**算法一字未改**。
     */
    private fun favoriteTargetFid(): Long {
        val strMid = AccountManager.accountMid().toString()
        return strMid.toLongOrNull() ?: 0L
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun like(aid: Long, likeState: Int): Int {  //likeState 1点赞0取消
        val url = "https://api.bilibili.com/x/web-interface/archive/like"
        val per = "aid=" + aid + "&like=" + likeState + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-点赞", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun coin(aid: Long, multiply: Int): Int {
        val url = "https://api.bilibili.com/x/web-interface/coin/add"
        val per = "aid=" + aid + "&multiply=" + multiply + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-投币", result.toString())
        return result.getInt("code")
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun favorite(aid: Long, fid: Long): Int {
        // v5.1.4：改用 AccountManager.accountMid()（三级模式下端口账号也算），
        // 并去掉原来的 `strMid.substring(strMid.length - 2)` —— 未登录时 mid="0"，
        // length-2 = -1 会直接抛 StringIndexOutOfBoundsException（老版本潜在崩溃点），
        // 而且「取 mid 末两位拼到收藏夹 id 后面」这个约定对短 mid 也会算出错的 addFid。
        val strMid = AccountManager.accountMid().toString()
        val suffix = if (strMid.length >= 2) strMid.substring(strMid.length - 2) else strMid
        val addFid = fid.toString() + suffix
        val url = "https://api.bilibili.com/medialist/gateway/coll/resource/deal"
        val per = "rid=" + aid + "&type=2&add_media_ids=" + addFid + "&del_media_ids=&csrf=" + SharedPreferencesUtil.getString("csrf", "")

        val result = JSONObject(NetWorkUtil.post(url, per, NetWorkUtil.webHeaders).body!!.string())
        Log.e("debug-添加收藏", result.toString())
        return result.getInt("code")
    }

    /**
     * 读取当前用户对该视频的三连状态（是否已点赞 / 已投币 / 已收藏 / 是否关注 UP）。
     *
     * ## v5.2.1 修的「点赞按钮永远是灰的」
     * 这个端点是 **GET**，而 GET 的鉴权在 B 站是「Cookie（SESSDATA）**或** APP」两个都接受
     * （与投币/点赞那两条「仅可 Cookie」的写端点不同）。所以 APP 端口直接给同一个路径补
     * access_key 就能读（`ApiMigrationRules` 里这条也是这么备注定为可迁移的：
     * 「实测 -101（通道被接受）」）。
     *
     * 但 v5.2.0 的实际表现是「web 退出后状态永远不亮」，原因是 `NetWorkUtil.getJson(url)`
     * 走的是默认 web 头（空 Cookie），ApiRouter 即便生成了 APP 版计划也拿不到数据。
     * v5.2.1 的做法：**APP 端口下由本函数自己显式带 APP 身份去请求**，不再依赖路由层兜。
     *
     * ## 为什么失败要静默按「未互动」返回
     * 这是详情页的**附加信息**，不是主内容。读不到状态时页面必须照常显示视频，
     * 只是三个图标不亮；抛错误框会把「看视频」这件事本身挡住（v5.1.1 踩过。
     * 那版 `getJSONObject("data")` 在 -101 时抛 No value for data，详情页弹框）。
     */
    @JvmStatic
    fun getVideoStats(videoInfo: VideoInfo): ApiResult {
        val apiResult = ApiResult()
        // 门槛：任一通道有账号（web 或 APP 端口都算），一个都没登就没必要发这一趟
        if (!AccountManager.isLoggedIn()) return apiResult
        try {
            val result = requestRelation(videoInfo.aid)
            apiResult.fromJson(result)
            // 非登录/鉴权类业务错误（-101 / -663 等）：静默按「未互动」返回，不往下解析 data
            if (apiResult.code != 0) return apiResult
            val data = result.optJSONObject("data") ?: return apiResult
            val stats = videoInfo.stats ?: return apiResult
            stats.followed = data.optBoolean("attention")
            stats.liked = data.optBoolean("like")
            stats.disliked = data.optBoolean("dislik")
            stats.favoured = data.optBoolean("favorite")
            stats.coined = data.optInt("coin")
        } catch (e: Exception) {
            MsgUtil.err(apiResult.message, e)
        }
        return apiResult
    }

    /**
     * 发这条 relation 请求：APP 端口显式带 APP 身份，web 端口走原来的默认 web 头。
     *
     * 为什么不总是走 `NetWorkUtil.getJson(url)`：那个重载只会带 web 头。
     * APP 端口下必须自己拼 access_key + 签名，否则读到的永远是「未登录」。
     */
    @Throws(IOException::class, JSONException::class)
    private fun requestRelation(aid: Long): JSONObject {
        val base = "https://api.bilibili.com/x/web-interface/archive/relation"
        val channel = appChannel() ?: return NetWorkUtil.getJson(base + "?aid=" + aid)
        val (identity, token) = channel
        // relation 是 GET：参数必须整条拼在 URL 上再签名（签名是对**全部参数**算的，
        // 不能「先拼 aid、再签另外几个」——少一个 aid 服务端就会因 sign 不匹配返回 -663）
        val form = appForm(identity, token).put("aid", aid)
        AppRcmdApi.sign(form, identity)
        // appForm 已经设了 setUrlParam(true)，toString() 自带前缀 '?'
        return NetWorkUtil.getJson(base + form, AppRcmdApi.appHeaders(identity))
    }

}
