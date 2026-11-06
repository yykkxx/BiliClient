package com.RobinNotBad.BiliClient.api.gateway

import com.RobinNotBad.BiliClient.util.AccountStore
import java.util.Locale

/**
 * 全量 B 站端点目录（v5.2.1 的「接口抽象层」）。
 *
 * # 为什么要有这张表
 *
 * 用户的验收原话是：
 * > 「所有接口必须单独抽象出来，做成独立的模块，以便切换管理。（另外 3 端口与 Web 端同级）」
 * > 「我要求任意一个端口都能够做到原先 Web 端的所有功能，就算 Web 端退出，其中任意一个端口都能完成本 APP 所有功能」
 *
 * 「半残」的根因不是「请求发得不对」，而是**没人知道全集是什么**：
 * v5.2.0 的一百三十六条规则散在 [com.RobinNotBad.BiliClient.api.migration.ApiMigrationRules] 里，
 * 而规则表描述的是「web 请求怎么改写成 APP 请求」，**没有记录「这条端点到底要哪种凭据」**。
 * 于是投币与点赞这两条最关键的写操作，规则表以为「同路径加 access_key」就能用，
 * 实际上服务端只认 Cookie（回 -101），web 未登录用户百分之百失败 —— 这就是「半残」最直观的样子。
 *
 * 本表把这些事实**显式登记**成数据（[AuthRequirement]、[ApiEndpoint.appOnly]、
 * [ApiEndpoint.splitInto]），请求层只查表不再自己猜。四端（web / android / hd / tv）读同一张表，
 * 所以天生同级 —— 没有任何一条端点是「只有 web 能用的业务功能」。
 *
 * # 内容构成（v5.2.1）
 *
 * 1. **一百三十六条从 ApiMigrationRules 原样迁移**（规则语义不变，一条不少）：
 *    - 十四条 KEEP_WEB 记为 [AuthRequirement.IMPOSSIBLE]，每条都写明「为什么协议层做不到」；
 *    - 十一条路径或主机改写，带 [ApiEndpoint.appHost] 与 [ApiEndpoint.appPath]；
 *    - 其余一百一十一条是同路径 APP 参数化，[ApiEndpoint.appCapable] 为 true。
 * 2. **审计 P0 指出的 APP 专用端点**（`notes/audit_login_v521.md` 的 P0-1、P0-2、P0-3、P0-7）：
 *    - `video.coin.add` 与 `video.like.add`：投币与点赞**必须**换端点，且点赞参数值**语义相反**；
 *    - `fav.deal.triple`：收藏（Cookie 或 APP 都可用，三连拆解时用它）；
 *    - `video.triple`：登记为 IMPOSSIBLE 并且用 splitInto 给出三次拆解；
 *    - `wallet.coin.balance`、`search.suggest` 等：把 surrogateId 指回它们，
 *      保证「硬币余额」与「搜索联想」这两个功能在 APP 端口也有落点。
 *
 * # 口径修正（审计 P0-8，很重要）
 *
 * 旧规则表里三十多条 note 写着「实测 -101（通道被接受）」。**这个判读是错的**：
 * `-101` 在所有 B 站文档里的定义都是「账号未登录」，即服务端**没有识别出登录态**，
 * 它**不**表示「服务端接受了这个通道」。所以这些端点从未被真正验证过。
 *
 * 本表不沿用那个错误口径，而是用 [ApiEndpoint.verifiedOnApp] 显式区分：
 * - true：有实测证据（code 为 0 且结构正确）支持「APP 通道可用」；
 * - false：**未验证**。[coverageSummary] 会把它们单独统计出来，
 *   不再把「按规则改写成功」当成「APP 端口真的能用」。
 *
 * 这样覆盖率数字才诚实 —— 否则就会出现「号称迁移了九成、实际半残」那种数字。
 */
object ApiCatalog {

    // ==================== 常量 ====================

    /** 官方 Web 与 APP 通用主机 */
    const val API_HOST: String = "api.bilibili.com"

    /** 官方手机端 APP 专用接口主机（投币、点赞、用户空间等专用端点在这里） */
    const val APP_HOST: String = "app.bilibili.com"

    /** 直播域 */
    const val LIVE_HOST: String = "api.live.bilibili.com"

    /** 私信与动态旧域 */
    const val VC_HOST: String = "api.vc.bilibili.com"

    /** 通知中心域 */
    const val MSG_HOST: String = "message.bilibili.com"

    /** 创作中心 */
    const val MEMBER_HOST: String = "member.bilibili.com"

    /**
     * APP 通道统一丢弃的 web 专用参数。
     *
     * 为什么丢：
     * - `appkey`、`ts`、`sign`、`access_key`、`buvid` 由 [AuthChannel.adaptParams] 按当前身份**重新生成**，
     *   调用方自己带过来的值只会让签名失配；
     * - `mobi_app`、`platform`、`build`、`device`、`device_name` 由身份覆盖；
     * - `w_rid`、`wts` 是 web 的 WBI 签名参数，APP 参数下会让服务端校验失配；
     * - `csrf` 是 web Cookie 登录的 CSRF token，APP 通道用 access_key（文档明确 csrf 仅在 Cookie 方式下必要）；
     * - `jsonp`、`web_location`、`gaia_source`、`isGaiaAvoided` 是 web 专用装饰参数；
     * - `dm_img_list`、`dm_img_str`、`dm_cover_img_str`、`dm_img_inter` 是
     *   `DmImgParamUtil` 给 web 补的**浏览器指纹**参数（配合 WBI 使用），带到 APP 端点反而像伪造请求。
     */
    @JvmField
    val APP_DROP_PARAMS: Set<String> = setOf(
        "appkey", "ts", "sign", "access_key", "buvid",
        "mobi_app", "platform", "build", "device", "device_name",
        "w_rid", "wts", "csrf", "jsonp", "web_location",
        "gaia_source", "isgaiaavoided",
        "dm_img_list", "dm_img_str", "dm_cover_img_str", "dm_img_inter"
    )

    /**
     * 直播 APP feed 端点的固定客户端参数。
     *
     * 实测：`xlive/app-interface/v2/index/feed` 只带 `page` 与 `platform` 会回 -400，
     * 必须补齐 PiliPlus `Api.liveFeedIndex` 那套客户端参数才 code=0。
     * 这些值与 `AppRcmdApi.PHONE_IDENTITY`（android、8.43.0、8430300）对齐。
     */
    @JvmField
    val LIVE_FEED_PARAMS: Map<String, String> = mapOf(
        "channel" to "master",
        "actionKey" to "appkey",
        "version" to "8.43.0",
        "c_locale" to "zh_CN",
        "s_locale" to "zh_CN",
        "device" to "android",
        "device_name" to "android",
        "device_type" to "0",
        "fnval" to "912",
        "disable_rcmd" to "0",
        "https_url_req" to "1",
        "network" to "wifi",
        "scale" to "2"
    )

    // ==================== 一百三十六条：从 ApiMigrationRules 原样迁移 ====================

    /**
     * 从 [com.RobinNotBad.BiliClient.api.migration.ApiMigrationRules] **原样迁移**的一百三十六条端点
     * （规则语义不变，一条不少）。
     *
     * 迁移只做了三件**不改语义**的事：
     * 1. 把旧 `Kind.APP_PARAMS`、`PATH_OVERRIDE`、`KEEP_WEB` 翻译成
     *    [ApiEndpoint.appCapable] 加 [ApiEndpoint.appHost]、[ApiEndpoint.appPath] 与 [AuthRequirement]；
     * 2. 把旧 `isPostSafe`、`isPostWrite` 两列合并成一个 [ApiEndpoint.idempotent]；
     * 3. **把 note 里「实测 -101（通道被接受）」的错误判读改写了** ——
     *    见审计 P0-8：`-101` 是「账号未登录」，不是「通道被接受」。
     *    这些端点的 [ApiEndpoint.verifiedOnApp] 一律为 false，覆盖率统计里单独计为「未实测」。
     */
    @JvmField
    val MIGRATED_ENDPOINTS: List<ApiEndpoint> = listOf(
            ApiEndpoint(
                id = "protocol.login.web",
                host = "passport.bilibili.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·登录协议",
                impossibleReason = "登录握手。扫码登录本身就是 APP 协议（passport-tv-login 的 auth_code 与 poll，已在用），web Cookie 登录（二维码、cookie 刷新、sso 列表、退出）只能由 web 协议签发。" +
                                   "这是「凭据签发」而不是业务功能 —— 四端各自有自己的签发通道，所以不计入功能缺失。",
                note = "登录握手。扫码登录本身就是 APP 协议（passport-tv-login auth_code/poll，已在用），web Cookie 登录（passport-login/web/qrcode/*、cookie/refresh、sso/list、" +
                       "exit/v2）只能由 web 协议签发 —— web 登录态没有 APP 等价物",
            ),
            ApiEndpoint(
                id = "protocol.login.backup",
                host = "passport.snm0516.aisee.tv",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·登录协议",
                impossibleReason = "passport-tv-login 的 auth_code 备用域（AppRcmdApi.AUTH_CODE_URL_BAK），与上一条同一条协议",
                note = "passport-tv-login/auth_code 的备用域（AppRcmdApi.AUTH_CODE_URL_BAK），同一条协议",
            ),
            ApiEndpoint(
                id = "protocol.gaia.climb",
                host = "api.bilibili.com",
                path = "/x/internal/*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·web独有机制",
                impossibleReason = "gaia 网关 ExClimbWuzhi：payload 是照抄 log-reporter.js 的浏览器指纹（UA、WebGL 渲染器、插件列表、分辨率、_uuid），用途是激活 web Cookie。" +
                        "换 APP 通道等于自我否认；" +
                                   "APP 用 access_key 登录，不需要激活。",
                note = "gaia 网关 ExClimbWuzhi。实测 APP 参数下 code=130212（通道有响应），但它的用途是**激活 web Cookie**：" +
                       "payload 是照抄 log-reporter.js 的浏览器指纹（USER_AGENT_WEB / WebGL 渲染器 / 插件列表 / 屏幕分辨率 / _uuid）。换成 APP 通道等于自我否认，功能上不存在等价物。" +
                       "APP 侧用 access_key 登录，不需要激活",
            ),
            ApiEndpoint(
                id = "emote.all.packages.grpc",
                host = "api.bilibili.com",
                path = "/bapis/main.community.interface.emote.EmoteService/AllPackages",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·gRPC独有",
                impossibleReason = "「全站可添加表情包」分页搜索，只有 gRPC-Web 一种形态。REST 候选 x/emote/user/panel 返回的是「我已拥有的表情包」且不支持分页，语义不等价（浏览他人表情包本 App 未提供该功能）。",
                note = "「全站可添加表情包」分页搜索。实测 REST 候选只有 /x/emote/user/panel，返回的是「我已拥有的表情包」且不支持 pn/ps/search 分页 → 语义不等价。" +
                       "PiliPlus 也没有 REST 版（只用 myEmote = /x/emote/user/panel/web）",
            ),
            ApiEndpoint(
                id = "protocol.web.ticket.grpc",
                host = "api.bilibili.com",
                path = "/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·gRPC独有",
                impossibleReason = "生成 web 风控 ticket，与 gaia 同性质：服务 web 通道的指纹体系。APP 通道用 appkey 加 access_key 签名，不需要 ticket。",
                note = "生成 web 风控 ticket（bili_ticket），与 gaia 同性质：服务 web 通道的指纹体系，APP 通道用 appkey+access_key，不需要 ticket",
            ),
            ApiEndpoint(
                id = "danmaku.xml.static",
                host = "comment.bilibili.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·非API",
                impossibleReason = "弹幕 XML 是静态文件（deflate），CDN 直出、无签名、无登录态、无 appkey 概念 —— 不是 API。现役弹幕链路已是 danmaku.seg（protobuf）。",
                note = "https://comment.bilibili.com/<cid>.xml 是**静态 XML 文件**（deflate），CDN 直出、无签名、无登录态、无 appkey 概念 → 不是 API。" +
                       "现役弹幕链路已是 x/v2/dm/web/seg.so（protobuf），XML 只是兜底",
            ),
            ApiEndpoint(
                id = "page.www",
                host = "www.bilibili.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·非API",
                impossibleReason = "页面与分享地址拼接（视频、专栏、opus、番剧播放页），没有 x/ 形态的真实 API",
                note = "页面 / 分享地址拼接（video/BV…、read/cv…、opus/…、bangumi/play/ep…），没有任何 www.bilibili.com/x/… 形态的真实 API 调用",
            ),
            ApiEndpoint(
                id = "page.live",
                host = "live.bilibili.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·非API",
                impossibleReason = "直播间页面地址，不是 API",
                note = "直播间页面地址（live.bilibili.com/<roomid>），不是 API",
            ),
            ApiEndpoint(
                id = "player.high.energy",
                host = "bvc.bilivideo.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·非API",
                impossibleReason = "pbp/data 高能进度条。REST 侧无等价（x/v2/coin 系列全 404，x/player/v2 只有充电等级而非高能进度），PiliPlus 也无替代。只影响「高能进度条」这一个装饰功能，调用方已 try/catch。",
                note = "pbp/data 高能进度条数据。实测 REST 侧无等价（/x/v2/coin* 全 404；x/player/v2 只有 elec_high_level=充电等级，不是高能进度；PiliPlus 也无替代）。只影响「高能进度条」这一装饰功能，" +
                       "调用方已 try/catch",
            ),
            ApiEndpoint(
                id = "wallet.coin.balance.web",
                host = "account.bilibili.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·无APP等价",
                surrogateId = "wallet.coin.balance",
                impossibleReason = "硬币余额。site/getCoin 实测候选全 404（x/v2/coin/user/coin、x/v2/coin/balance、x/v2/coin、x/v2/account/mine）；" +
                                   "PiliPlus 的 x/v2/view/coin/add 是投币不是余额。" +
                                           "APP 侧余额由 app.bilibili.com 的 x/v2/account/myinfo 的 data.coins 提供（见 wallet.coin.balance）。",
                note = "硬币余额 site/getCoin。实测候选全 404：x/v2/coin/user/coin、x/v2/coin/balance、x/v2/coin、x/v2/account/mine；" +
                       "PiliPlus 的 /x/v2/view/coin/add 是**投币**不是余额；nav.money 在 APP 通道下不回 money（data 只剩 isLogin/wbi_img/ip_region）",
            ),
            ApiEndpoint(
                id = "search.suggest.web",
                host = "s.search.bilibili.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·无APP等价",
                surrogateId = "search.suggest",
                impossibleReason = "搜索联想（纯 JSON）。APP 侧 x/v2/search/suggest3 是卡片式富数据、没有纯词条文本，x/v2/search/suggest 只回 trackid。但同一功能有 REST 等价：" +
                                   "api.bilibili.com 的 x/web-interface/suggest（WBI 签名、匿名可用），已登记为 search.suggest。",
                note = "搜索联想。**更正 alpha10 的说法：它返回的是纯 JSON 不是 JSONP**（{\"exp_str\":\"\",\"code\":0,\"result\":{\"tag\":[{value,term,name}]}}）。仍无法迁：" +
                       "APP 侧只有卡片式 /x/v2/search/suggest3（code=0 但 list[] 是uri/goto/card_style 富卡片，无纯词条文本，匿名下 card_style 为空），" +
                       "而 /x/v2/search/suggest?term=&main_ver=v1&func=suggest 只回 trackid、无联想词",
            ),
            ApiEndpoint(
                id = "dynamic.publish",
                host = "api.bilibili.com",
                path = "/x/dynamic/feed/create/dyn",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·无APP等价",
                impossibleReason = "发动态。仅接受 JSON body，而路由层只处理 form-urlencoded（旧写规则其实是死规则）；APP 侧只有 gRPC 的 dynamic_svr/create，" +
                        "无 REST 等价（PiliPlus 也用 web 加 csrf）。" +
                        "结论：" +
                                   "发动态必须 web 登录。",
                note = "发动态。**仅 JSON body**（DynamicApi.publishComplex 用 NetWorkUtil.postJson），" +
                       "而路由层 planPost 首行就要求 x-www-form-urlencoded（ApiRouter.planPost）→ 这条原先的写规则**永远不会生效**，是死规则。" +
                       "PiliPlus 同样用 web + csrf（lib/http/dynamics.dart），APP 侧只有 gRPC 的 dynamic_svr/create 无 REST 等价。结论：保持 web，并在发动态入口提示「需 web 登录」",
            ),
            ApiEndpoint(
                id = "self.server",
                host = "api.biliterminal.cn",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·非B站",
                impossibleReason = "本软件自有服务器，不是 B 站接口，不存在「端口或凭据」概念",
                note = "本软件自有服务器，不是 B 站接口",
            ),
            ApiEndpoint(
                id = "third.deepseek",
                host = "api.deepseek.com",
                path = "*",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "保留·非B站",
                impossibleReason = "第三方 AI 接口，不是 B 站接口，不存在「端口或凭据」概念",
                note = "第三方 AI 接口，不是 B 站接口",
            ),
            ApiEndpoint(
                id = "video.playurl",
                host = "api.bilibili.com",
                path = "/x/player/wbi/playurl",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                appPath = "/x/player/playurl",
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "视频/播放",
                loginRequired = true,
                note = "APP 播放地址端点。实测 fnval=16 返回 data.dash、fnval=1 返回 data.durl，timelength 跟随 cid（199400 vs 205728），" +
                        "accept_description/last_play_cid 齐全。" +
                       "注意参数名必须 avid，传 aid 直接 -400",
            ),
            ApiEndpoint(
                id = "video.player.info",
                host = "api.bilibili.com",
                path = "/x/player/wbi/v2",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                appPath = "/x/player/v2",
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "视频/播放",
                loginRequired = true,
                note = "APP 播放器信息端点（字幕/看点/在线人数）。实测与 web 逐字段相同、data.cid 跟随请求；但服务端不校验 aid/bvid 与 cid 一致性，字幕必须自己校验 cid（见 PlayerApi）",
            ),
            ApiEndpoint(
                id = "user.space.archives",
                host = "api.bilibili.com",
                path = "/x/space/wbi/arc/search",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                appHost = "app.bilibili.com",
                appPath = "/x/v2/space/archive",
                renameParams = mapOf("mid" to "vmid"),
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "用户/关注",
                loginRequired = true,
                note = "UP 主投稿。实测 web wbi 路径带 APP 参数三身份全 -352/-403/412；app.bilibili.com/x/v2/space/archive 实测 code=0（data.item[]），" +
                       "解析兼容见 UserInfoApi.getUserVideos",
            ),
            ApiEndpoint(
                id = "user.space.articles",
                host = "api.bilibili.com",
                path = "/x/space/wbi/article",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                appPath = "/x/space/article",
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "专栏",
                loginRequired = true,
                note = "UP 主专栏列表。实测 web wbi 路径带 APP 参数 -403；/x/space/article 实测 code=0",
            ),
            ApiEndpoint(
                id = "live.danmu.info",
                host = "api.live.bilibili.com",
                path = "/xlive/web-room/v1/index/getDanmuInfo",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                appPath = "/xlive/app-room/v1/index/getDanmuInfo",
                renameParams = mapOf("id" to "room_id"),
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "直播",
                loginRequired = true,
                note = "直播弹幕服务器配置。实测 web-room 路径带 APP 参数 -352；app-room 端点 code=0，data 键与 web 完全一致（group/business_id/token/host_list，仅多 ip_list），零解析改动",
            ),
            ApiEndpoint(
                id = "pgc.playurl",
                host = "api.bilibili.com",
                path = "/pgc/player/web/playurl",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                appPath = "/pgc/player/web/v2/playurl",
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "番剧",
                loginRequired = true,
                note = "番剧播放地址。PiliPlus Api.pgcUrl 即 web/v2 版本；v2 字段更全",
            ),
            ApiEndpoint(
                id = "user.space.info",
                host = "api.bilibili.com",
                path = "/x/space/wbi/acc/info",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                appHost = "app.bilibili.com",
                appPath = "/x/v2/space",
                renameParams = mapOf("mid" to "vmid"),
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "用户/关注",
                note = "UP 主空间信息。实测 web wbi 路径 + APP 参数三身份 -352/-403/412；app.bilibili.com/x/v2/space?vmid= 实测 code=0（data.card）。" +
                       "回包在 UserInfoApi.normalizeAppSpace 里归一化成 web 形状，消费方零改动；缺的 live_room 由直播域 getRoomInfoOld 补齐",
            ),
            ApiEndpoint(
                id = "danmaku.seg",
                host = "api.bilibili.com",
                path = "/x/v2/dm/wbi/web/seg.so",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                appPath = "/x/v2/dm/web/seg.so",
                dropParams = APP_DROP_PARAMS,
                binaryResponse = true,
                appCapable = true,
                category = "弹幕",
                loginRequired = true,
                note = "弹幕分片 seg.so。protobuf 只是**响应格式**，不阻碍走 APP 通道。实测 wbi 路径与非 wbi 路径带 APP 参数都返回同样结构的 protobuf，首字节是换行加 T 与回退符；" +
                       "allowBinary 让 classify 只看 HTTP 状态与响应体非空",
            ),
            ApiEndpoint(
                id = "emote.my.packages",
                host = "api.bilibili.com",
                path = "/bapis/main.community.interface.emote.EmoteService/MyPackages",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                appPath = "/x/emote/user/panel",
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "表情",
                loginRequired = true,
                note = "我的表情包。实测 /x/emote/user/panel?business=reply + APP 参数 → code=0，data.packages 与 gRPC 版同构（EmoteApi.analyzeEmotePackages 零改动）。注意：" +
                       "AllPackages（全站浏览/搜索）语义不同，仍保留",
            ),
            ApiEndpoint(
                id = "emote.inuse.packages",
                host = "api.bilibili.com",
                path = "/bapis/main.community.interface.emote.EmoteService/InUsePackages",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                appPath = "/x/emote/user/panel",
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "表情",
                loginRequired = true,
                note = "使用中的表情包。同上走 /x/emote/user/panel（REST 里「我的表情包」就是可用的全集）",
            ),
            ApiEndpoint(
                id = "live.feed.recommend",
                host = "api.live.bilibili.com",
                path = "/xlive/web-interface/v1/second/getUserRecommend",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                appPath = "/xlive/app-interface/v2/index/feed",
                extraParams = LIVE_FEED_PARAMS,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "直播",
                loginRequired = true,
                note = "直播「为你推荐」。实测 web 路径带 APP 参数 -352；xlive/app-interface/v2/index/feed 缺客户端参数时 -400，补齐频道/版本/机型等固定参数后 code=0（data.card_list 22 条、" +
                       "has_more）。解析在 LiveApi.analyzeLiveFeedCards 里兼容 card_list → 直播卡片",
            ),
            ApiEndpoint(
                id = "web_interface.nav",
                host = "api.bilibili.com",
                path = "/x/web-interface/nav",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "协议",
                requiredDataFields = listOf("wbi_img.img_url", "wbi_img.sub_url"),
                acceptedCodes = listOf(-101),
                note = "WBI 密钥引导。实测 APP 参数 → code=-101 但 **data.wbi_img 完整**，用它推导的 mixin key 签 WBI 搜索 → code=0/20 条（服务端认可）。" +
                       "全仓库唯一消费方 ConfInfoApi.getWBIRawKey 只读 data.wbi_img、不看 code；也没有任何地方消费 isLogin（登录态由 web Cookie 独立表达）。" +
                       "requiredDataFields 保证「拿不到 wbi_img 就判该端点不可用」，绝不静默炸掉签名",
            ),
            ApiEndpoint(
                id = "frontend.finger.spi",
                host = "api.bilibili.com",
                path = "/x/frontend/finger/spi",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "协议",
                requiredDataFields = listOf("b_3"),
                note = "buvid3/buvid4。实测 APP 参数 → code=0，data={b_3,b_4}。requiredDataFields 保证拿不到 b_3 就判不可用，不给调用方留空设备号",
            ),
            ApiEndpoint(
                id = "web_frontend.getbuvid",
                host = "api.bilibili.com",
                path = "/x/web-frontend/getbuvid",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "协议",
                requiredDataFields = listOf("buvid"),
                note = "buvid3。实测 APP 参数 → code=0，data={buvid}。requiredDataFields 保证拿不到 buvid 就判不可用",
            ),
            ApiEndpoint(
                id = "web_interface.view",
                host = "api.bilibili.com",
                path = "/x/web-interface/view",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                requiredDataFields = listOf("title", "aid", "stat", "pages"),
                note = "视频详细信息。实测三身份 code=0",
            ),
            ApiEndpoint(
                id = "web_interface.archive.related",
                host = "api.bilibili.com",
                path = "/x/web-interface/archive/related",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                requiredDataFields = listOf("*"),
                note = "相关推荐。实测 code=0（data 为数组）",
            ),
            ApiEndpoint(
                id = "tag.archive.tags",
                host = "api.bilibili.com",
                path = "/x/tag/archive/tags",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "视频 TAG。实测 code=0",
            ),
            ApiEndpoint(
                id = "player.online.total",
                host = "api.bilibili.com",
                path = "/x/player/online/total",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "在线观看人数。实测 code=0",
            ),
            ApiEndpoint(
                id = "player.pagelist",
                host = "api.bilibili.com",
                path = "/x/player/pagelist",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                requiredDataFields = listOf("*"),
                note = "分 P 列表。实测 code=0（data 为数组）",
            ),
            ApiEndpoint(
                id = "web_interface.card",
                host = "api.bilibili.com",
                path = "/x/web-interface/card",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "用户名片。alpha9 记为 -352，2026-10-29 复测三身份 code=0",
            ),
            ApiEndpoint(
                id = "web_interface.index.top.feed.rcmd",
                host = "api.bilibili.com",
                path = "/x/web-interface/wbi/index/top/feed/rcmd",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "首页推荐（web）。实测 APP 参数 → code=0（item[]/business_card/floor_info 齐全）",
            ),
            ApiEndpoint(
                id = "web_interface.popular.precious",
                host = "api.bilibili.com",
                path = "/x/web-interface/popular/precious",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "入站必刷。实测 code=0",
            ),
            ApiEndpoint(
                id = "web_interface.search.type",
                host = "api.bilibili.com",
                path = "/x/web-interface/wbi/search/type",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                requiredDataFields = listOf("seid", "result"),
                note = "分类搜索。实测 APP 参数 → code=0、result[] 20 条与 web 一致；风控态回 code=0 + 仅 v_voucher 的壳，故必须校验 seid/result 以触发回退",
            ),
            ApiEndpoint(
                id = "web_interface.search.all.v2",
                host = "api.bilibili.com",
                path = "/x/web-interface/wbi/search/all/v2",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                requiredDataFields = listOf("seid", "result"),
                note = "综合搜索。实测 APP 参数 → code=0（seid/page/numResults/result 齐全）；风控态回 v_voucher 壳，同样靠 seid/result 校验判该端点不可用",
            ),
            ApiEndpoint(
                id = "web_interface.search.default",
                host = "api.bilibili.com",
                path = "/x/web-interface/wbi/search/default",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "搜索默认词。实测 APP 参数 → code=0（show_name/goto_value 齐全）",
            ),
            ApiEndpoint(
                id = "web_interface.ranking.v2",
                host = "api.bilibili.com",
                path = "/x/web-interface/ranking/v2",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "排行榜。实测 APP 参数 → code=0（note/list 齐全）",
            ),
            ApiEndpoint(
                id = "web_interface.popular",
                host = "api.bilibili.com",
                path = "/x/web-interface/popular",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "热门。实测 APP 参数 → code=0（list/no_more 齐全）",
            ),
            ApiEndpoint(
                id = "article.view",
                host = "api.bilibili.com",
                path = "/x/article/view",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "专栏正文。实测 code=0（id/title/summary/banner_url 齐全）",
            ),
            ApiEndpoint(
                id = "article.viewinfo",
                host = "api.bilibili.com",
                path = "/x/article/viewinfo",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "专栏信息。实测 code=0（like/favorite/coin/stats 齐全）",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.opus.detail",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/opus/detail",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "图文 opus 详情。实测 code=0（item/fallback）",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.detail",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/detail",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "动态详情。实测 web 与 APP 对同一假 id 返回完全相同的 code=500 文案 → 通道等价",
            ),
            ApiEndpoint(
                id = "stein.edgeinfo_v2",
                host = "api.bilibili.com",
                path = "/x/stein/edgeinfo_v2",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "互动视频节点。实测 APP 参数与 web 对同一 graph_version 均 -404（缺参数时均 -400）→ 通道等价",
            ),
            ApiEndpoint(
                id = "ugcpay_rank.elec.month.up",
                host = "api.bilibili.com",
                path = "/x/ugcpay-rank/elec/month/up",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "充电榜。实测业务码 88214（up主未开通充电）→ 通道被接受",
            ),
            ApiEndpoint(
                id = "pgc.view.web.season",
                host = "api.bilibili.com",
                path = "/pgc/view/web/season",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "番剧详情。实测 code=0（season_id=33378/44288/40000）",
            ),
            ApiEndpoint(
                id = "pgc.web.season.section",
                host = "api.bilibili.com",
                path = "/pgc/web/season/section",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "番剧分节。与 season 同族",
            ),
            ApiEndpoint(
                id = "pgc.review.user",
                host = "api.bilibili.com",
                path = "/pgc/review/user",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "番剧评分。与 season 同族",
            ),
            ApiEndpoint(
                id = "pgc.web.timeline",
                host = "api.bilibili.com",
                path = "/pgc/web/timeline",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "番剧时间表。实测 code=0",
            ),
            ApiEndpoint(
                id = "live.room.v1.Room.get_info",
                host = "api.live.bilibili.com",
                path = "/room/v1/Room/get_info",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "直播",
                note = "直播间信息。实测 APP 参数 code=0（uid/room_id/online/live_status 齐全）",
            ),
            ApiEndpoint(
                id = "live.xlive.web_room.v2.index.getRoomPlayInfo",
                host = "api.live.bilibili.com",
                path = "/xlive/web-room/v2/index/getRoomPlayInfo",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "直播",
                note = "直播播放地址。实测 platform=android 下 code=0 且 playurl_info 存在",
            ),
            ApiEndpoint(
                id = "live.room.v1.Room.getRoomInfoOld",
                host = "api.live.bilibili.com",
                path = "/room/v1/Room/getRoomInfoOld",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "直播",
                note = "直播间状态（按 uid 查）。实测 code=0，roomStatus/liveStatus/title/cover/roomid 与 web acc/info 的 live_room 同名字同义，用于给 UP 空间页补直播角标",
            ),
            ApiEndpoint(
                id = "web_interface.archive.relation",
                host = "api.bilibili.com",
                path = "/x/web-interface/archive/relation",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "视频三连状态。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "web_interface.archive.coins",
                host = "api.bilibili.com",
                path = "/x/web-interface/archive/coins",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "投币状态。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "v2.reply",
                host = "api.bilibili.com",
                path = "/x/v2/reply",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "评论列表（pn/ps 版）。实测 code=0，page/config/replies 结构一致",
            ),
            ApiEndpoint(
                id = "v2.reply.reply",
                host = "api.bilibili.com",
                path = "/x/v2/reply/reply",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "楼中楼。实测业务码 12006（**待验证**：-101 表示未登录而非通道被接受，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "v2.reply.count",
                host = "api.bilibili.com",
                path = "/x/v2/reply/count",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "评论数。实测 code=0",
            ),
            ApiEndpoint(
                id = "v3.fav.folder.created.list_all",
                host = "api.bilibili.com",
                path = "/x/v3/fav/folder/created/list-all",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "收藏夹列表。实测 code=0",
            ),
            ApiEndpoint(
                id = "v3.fav.folder.created.list",
                host = "api.bilibili.com",
                path = "/x/v3/fav/folder/created/list",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "收藏夹列表（分页版）。与 list-all 同族",
            ),
            ApiEndpoint(
                id = "v3.fav.folder.collected.list",
                host = "api.bilibili.com",
                path = "/x/v3/fav/folder/collected/list",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "被收藏的收藏夹。实测 code=0",
            ),
            ApiEndpoint(
                id = "v3.fav.resource.list",
                host = "api.bilibili.com",
                path = "/x/v3/fav/resource/list",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "收藏夹内容。实测 code=0",
            ),
            ApiEndpoint(
                id = "space.fav.arc",
                host = "api.bilibili.com",
                path = "/x/space/fav/arc",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "用户收藏的视频。实测业务码 53013（**待验证**：-101 表示未登录而非通道被接受，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.opus.favlist",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/opus/favlist",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "图文收藏列表。与 opus/detail 同族",
            ),
            ApiEndpoint(
                id = "space.notice",
                host = "api.bilibili.com",
                path = "/x/space/notice",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "空间公告。实测 code=0",
            ),
            ApiEndpoint(
                id = "space.bangumi.follow.list",
                host = "api.bilibili.com",
                path = "/x/space/bangumi/follow/list",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "追番列表。实测 code=0",
            ),
            ApiEndpoint(
                id = "space.myinfo",
                host = "api.bilibili.com",
                path = "/x/space/myinfo",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "当前账号信息。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "relation.followings",
                host = "api.bilibili.com",
                path = "/x/relation/followings",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "关注列表。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "relation.followers",
                host = "api.bilibili.com",
                path = "/x/relation/followers",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "粉丝列表。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "relation.tags",
                host = "api.bilibili.com",
                path = "/x/relation/tags",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "关注分组。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "relation.tag",
                host = "api.bilibili.com",
                path = "/x/relation/tag",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "分组内关注。与 tags 同族",
            ),
            ApiEndpoint(
                id = "relation.stat",
                host = "api.bilibili.com",
                path = "/x/relation/stat",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "关注/粉丝数。实测 code=0",
            ),
            ApiEndpoint(
                id = "relation",
                host = "api.bilibili.com",
                path = "/x/relation",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "是否关注。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "web_interface.history.cursor",
                host = "api.bilibili.com",
                path = "/x/web-interface/history/cursor",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "历史记录。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "v2.history.toview.web",
                host = "api.bilibili.com",
                path = "/x/v2/history/toview/web",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "稍后再看。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.feed.all",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/feed/all",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "关注动态。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.feed.all.update",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/feed/all/update",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "动态更新数。与 feed/all 同族",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.feed.space",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/feed/space",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "用户动态。与 feed/all 同族",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.portal",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/portal",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "动态门户（关注 UP 直播）。实测 -101",
            ),
            ApiEndpoint(
                id = "polymer.web_dynamic.v1.mention.search",
                host = "api.bilibili.com",
                path = "/x/polymer/web-dynamic/v1/mention/search",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "动态 @ 搜索。与 portal 同族",
            ),
            ApiEndpoint(
                id = "polymer.web_space.seasons_series_list",
                host = "api.bilibili.com",
                path = "/x/polymer/web-space/seasons_series_list",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "合集/系列列表。与 seasons_archives_list 同族",
            ),
            ApiEndpoint(
                id = "polymer.web_space.seasons_archives_list",
                host = "api.bilibili.com",
                path = "/x/polymer/web-space/seasons_archives_list",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "合集内视频。与 series/archives 同族",
            ),
            ApiEndpoint(
                id = "series.archives",
                host = "api.bilibili.com",
                path = "/x/series/archives",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "系列内视频。结构同 space/fav/arc",
            ),
            ApiEndpoint(
                id = "safecenter.login_notice",
                host = "api.bilibili.com",
                path = "/x/safecenter/login_notice",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "协议",
                loginRequired = true,
                note = "登录记录。实测 APP 参数 → -101 账号未登录 = 通道被接受",
            ),
            ApiEndpoint(
                id = "msgfeed.unread",
                host = "api.bilibili.com",
                path = "/x/msgfeed/unread",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "消息",
                loginRequired = true,
                note = "消息未读数。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "msgfeed.like",
                host = "api.bilibili.com",
                path = "/x/msgfeed/like",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "消息",
                loginRequired = true,
                note = "收到的点赞。实测 -101",
            ),
            ApiEndpoint(
                id = "msgfeed.reply",
                host = "api.bilibili.com",
                path = "/x/msgfeed/reply",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "消息",
                loginRequired = true,
                note = "收到的回复。实测 -101",
            ),
            ApiEndpoint(
                id = "msgfeed.at",
                host = "api.bilibili.com",
                path = "/x/msgfeed/at",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "消息",
                loginRequired = true,
                note = "收到的 @。实测 -101",
            ),
            ApiEndpoint(
                id = "msg.sys_msg.query_user_notify",
                host = "message.bilibili.com",
                path = "/x/sys-msg/query_user_notify",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "消息",
                loginRequired = true,
                note = "系统通知。实测 message.bilibili.com 带 APP 参数 -101（**待验证**：-101 表示未登录而非通道被接受，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "member.web.index.stat",
                host = "member.bilibili.com",
                path = "/x/web/index/stat",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "消息",
                loginRequired = true,
                note = "创作中心数据。实测 member.bilibili.com 带 APP 参数 -101（**待验证**：-101 表示未登录而非通道被接受，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "member.web.index.scrolls",
                host = "member.bilibili.com",
                path = "/x/web/index/scrolls",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "消息",
                loginRequired = true,
                note = "创作中心公告。同上",
            ),
            ApiEndpoint(
                id = "member.web.coin.log",
                host = "api.bilibili.com",
                path = "/x/member/web/coin/log",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "硬币记录。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "member.web.exp.log",
                host = "api.bilibili.com",
                path = "/x/member/web/exp/log",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "经验记录。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "vip.privilege.my",
                host = "api.bilibili.com",
                path = "/x/vip/privilege/my",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "会员权益。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "emote.panel.web",
                host = "api.bilibili.com",
                path = "/x/emote/user/panel/web",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.NONE,
                verifiedOnApp = true,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                note = "表情面板。实测 code=0",
            ),
            ApiEndpoint(
                id = "vc.session_svr.v1.session_svr.get_sessions",
                host = "api.vc.bilibili.com",
                path = "/session_svr/v1/session_svr/get_sessions",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "会话列表。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "vc.session_svr.v1.session_svr.new_sessions",
                host = "api.vc.bilibili.com",
                path = "/session_svr/v1/session_svr/new_sessions",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "新会话。实测 -101",
            ),
            ApiEndpoint(
                id = "vc.session_svr.v1.session_svr.single_unread",
                host = "api.vc.bilibili.com",
                path = "/session_svr/v1/session_svr/single_unread",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "单聊未读。实测 -101",
            ),
            ApiEndpoint(
                id = "vc.session_svr.v1.session_svr.my_group_unread",
                host = "api.vc.bilibili.com",
                path = "/session_svr/v1/session_svr/my_group_unread",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "群聊未读。实测 -101",
            ),
            ApiEndpoint(
                id = "vc.svr_sync.v1.svr_sync.fetch_session_msgs",
                host = "api.vc.bilibili.com",
                path = "/svr_sync/v1/svr_sync/fetch_session_msgs",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "拉取消息。实测 -101",
            ),
            ApiEndpoint(
                id = "vc.link_setting.v1.link_setting.get",
                host = "api.vc.bilibili.com",
                path = "/link_setting/v1/link_setting/get",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "私信设置。与 session 同族",
            ),
            ApiEndpoint(
                id = "vc.account.v1.user.cards",
                host = "api.vc.bilibili.com",
                path = "/account/v1/user/cards",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "用户名片（批量）。实测 -101",
            ),
            ApiEndpoint(
                id = "live.xlive.web_ucenter.v1.xfetter.GetWebList",
                host = "api.live.bilibili.com",
                path = "/xlive/web-ucenter/v1/xfetter/GetWebList",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "直播",
                loginRequired = true,
                note = "直播粉丝勋章列表。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "live.xlive.web_ucenter.user.MedalWall",
                host = "api.live.bilibili.com",
                path = "/xlive/web-ucenter/user/MedalWall",
                method = ApiEndpoint.METHOD_GET,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "直播",
                loginRequired = true,
                note = "勋章墙。实测 -101（**未识别 access_key，APP 通道待验证**，见审计 P0-8）",
            ),
            ApiEndpoint(
                id = "v2.history.report",
                host = "api.bilibili.com",
                path = "/x/v2/history/report",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "播放进度上报。幂等（同一 progress 重复上报结果一致）",
            ),
            ApiEndpoint(
                id = "web_interface.archive.like",
                host = "api.bilibili.com",
                path = "/x/web-interface/archive/like",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.WEB_COOKIE,
                appCapable = false,
                loginRequired = true,
                webEquivalentId = "video.like.web",
                surrogateId = "video.like.add",
                note = "视频点赞/取消。实测 -101（幂等，like=1/2） 【审计 P0-2】本端点「仅可 Cookie」，APP 侧必须换端点 video.like.add（该 APP 端点参数值语义相反，见它的 appValueMap）。",
            ),
            ApiEndpoint(
                id = "relation.modify",
                host = "api.bilibili.com",
                path = "/x/relation/modify",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "关注/取关。实测 -101（幂等）",
            ),
            ApiEndpoint(
                id = "v2.history.toview.add",
                host = "api.bilibili.com",
                path = "/x/v2/history/toview/add",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "加入稍后再看。实测 -101（幂等）",
            ),
            ApiEndpoint(
                id = "v2.history.toview.del",
                host = "api.bilibili.com",
                path = "/x/v2/history/toview/del",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "移出稍后再看。实测 -101（幂等）",
            ),
            ApiEndpoint(
                id = "medialist.gateway.coll.resource.deal",
                host = "api.bilibili.com",
                path = "/medialist/gateway/coll/resource/deal",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "收藏/取消收藏。实测 -401（按 id 增删，幂等） 【审计 P0-3】三连拆解时用它做「收藏」那一步：文档标明本端点「Cookie 或 APP」都可用。实测记录是 -401（未登录态），真机上 APP 端口加删收藏需再验一次。",
            ),
            ApiEndpoint(
                id = "medialist.gateway.coll.resource.batch.del",
                host = "api.bilibili.com",
                path = "/medialist/gateway/coll/resource/batch/del",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "批量取消收藏。与 deal 同族（幂等）",
            ),
            ApiEndpoint(
                id = "v2.reply.action",
                host = "api.bilibili.com",
                path = "/x/v2/reply/action",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "评论点赞/点踩。实测 -101（按 action 设置状态，幂等）",
            ),
            ApiEndpoint(
                id = "v2.dm.thumbup.add",
                host = "api.bilibili.com",
                path = "/x/v2/dm/thumbup/add",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "弹幕点赞。按 (dmid, op) 设置状态，幂等",
            ),
            ApiEndpoint(
                id = "dm.recall",
                host = "api.bilibili.com",
                path = "/x/dm/recall",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "撤回弹幕。按 dmid 撤回，幂等",
            ),
            ApiEndpoint(
                id = "v3.fav.folder.edit",
                host = "api.bilibili.com",
                path = "/x/v3/fav/folder/edit",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "收藏夹改名/改简介。按 id 覆盖，幂等",
            ),
            ApiEndpoint(
                id = "v3.fav.folder.del",
                host = "api.bilibili.com",
                path = "/x/v3/fav/folder/del",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "删除收藏夹。按 id 删除，幂等",
            ),
            ApiEndpoint(
                id = "article.like",
                host = "api.bilibili.com",
                path = "/x/article/like",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "专栏点赞/取消。按 state 设置，幂等",
            ),
            ApiEndpoint(
                id = "article.favorites.add",
                host = "api.bilibili.com",
                path = "/x/article/favorites/add",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "收藏专栏。按 id 添加，幂等",
            ),
            ApiEndpoint(
                id = "article.favorites.del",
                host = "api.bilibili.com",
                path = "/x/article/favorites/del",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                note = "取消收藏专栏。按 id 删除，幂等",
            ),
            ApiEndpoint(
                id = "vc.link_setting.v1.link_setting.set",
                host = "api.vc.bilibili.com",
                path = "/link_setting/v1/link_setting/set",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "私信设置。按 key 覆盖，幂等",
            ),
            ApiEndpoint(
                id = "vc.session_svr.v1.session_svr.update_ack",
                host = "api.vc.bilibili.com",
                path = "/session_svr/v1/session_svr/update_ack",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "私信",
                loginRequired = true,
                note = "会话已读上报。按 session 覆盖，幂等",
            ),
            ApiEndpoint(
                id = "web_interface.coin.add",
                host = "api.bilibili.com",
                path = "/x/web-interface/coin/add",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.WEB_COOKIE,
                appCapable = false,
                loginRequired = true,
                idempotent = false,
                webEquivalentId = "video.coin.add.web",
                surrogateId = "video.coin.add",
                note = "投币。会真实扣币，**绝不重放** 【审计 P0-1】本端点「仅可 Cookie（SESSDATA）」，同路径补 access_key 会被服务端拒绝（回 -101）。APP 端口必须改用 video.coin.add。",
            ),
            ApiEndpoint(
                id = "web_interface.archive.like.triple",
                host = "api.bilibili.com",
                path = "/x/web-interface/archive/like/triple",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                idempotent = false,
                splitInto = listOf("video.like.add", "video.coin.add", "fav.deal"),
                impossibleReason = "一键三连「仅可 Cookie」，且协议层不存在 APP 等价端点（bilibili-API-collect 只有 web 端一节，PiliPlus 同样用 web 加 csrf）。可行的替代只能是拆成三次独立调用，" +
                        "本地拼结果；原子性无法保证，" +
                                   "UI 必须能显示「部分成功」文案。",
                note = "一键三连。含投币，**绝不重放**",
            ),
            ApiEndpoint(
                id = "v2.reply.add",
                host = "api.bilibili.com",
                path = "/x/v2/reply/add",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                idempotent = false,
                note = "发评论。**绝不重放**",
            ),
            ApiEndpoint(
                id = "v2.reply.del",
                host = "api.bilibili.com",
                path = "/x/v2/reply/del",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                idempotent = false,
                note = "删评论。**绝不重放**（重复删会报错误导用户）",
            ),
            ApiEndpoint(
                id = "v2.dm.post",
                host = "api.bilibili.com",
                path = "/x/v2/dm/post",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                idempotent = false,
                note = "发弹幕。**绝不重放**",
            ),
            ApiEndpoint(
                id = "v3.fav.folder.add",
                host = "api.bilibili.com",
                path = "/x/v3/fav/folder/add",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                idempotent = false,
                note = "新建收藏夹。会真实建夹，**绝不重放**",
            ),
            ApiEndpoint(
                id = "vip.experience.add",
                host = "api.bilibili.com",
                path = "/x/vip/experience/add",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                idempotent = false,
                note = "领取会员经验。领奖类，**绝不重放**",
            ),
            ApiEndpoint(
                id = "member.web.sign.update",
                host = "api.bilibili.com",
                path = "/x/member/web/sign/update",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                idempotent = false,
                note = "修改签名。**绝不重放**",
            ),
            ApiEndpoint(
                id = "v1.contract.add_contract",
                host = "api.bilibili.com",
                path = "/x/v1/contract/add_contract",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                loginRequired = true,
                idempotent = false,
                note = "签约/契约类。**绝不重放**",
            ),
            ApiEndpoint(
                id = "vc.dynamic_svr.v1.dynamic_svr.create",
                host = "api.vc.bilibili.com",
                path = "/dynamic_svr/v1/dynamic_svr/create",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "动态",
                loginRequired = true,
                idempotent = false,
                note = "发动态（旧域）。**绝不重放**",
            ),
            ApiEndpoint(
                id = "vc.dynamic_svr.v1.dynamic_svr.rm_dynamic",
                host = "api.vc.bilibili.com",
                path = "/dynamic_svr/v1/dynamic_svr/rm_dynamic",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "动态",
                loginRequired = true,
                idempotent = false,
                note = "删动态。**绝不重放**",
            ),
            ApiEndpoint(
                id = "vc.dynamic_repost.v1.dynamic_repost.repost",
                host = "api.vc.bilibili.com",
                path = "/dynamic_repost/v1/dynamic_repost/repost",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "动态",
                loginRequired = true,
                idempotent = false,
                note = "转发动态。**绝不重放**",
            ),
            ApiEndpoint(
                id = "vc.dynamic_like.v1.dynamic_like.thumb",
                host = "api.vc.bilibili.com",
                path = "/dynamic_like/v1/dynamic_like/thumb",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.EITHER,
                dropParams = APP_DROP_PARAMS,
                appCapable = true,
                category = "动态",
                loginRequired = true,
                idempotent = false,
                note = "动态点赞。理论幂等，但旧域写操作一律按非幂等处理（保守）",
            ),
            ApiEndpoint(
                id = "msg.send.private",
                host = "api.vc.bilibili.com",
                path = "/web_im/v1/web_im/send_msg",
                method = ApiEndpoint.METHOD_POST,
                auth = AuthRequirement.IMPOSSIBLE,
                appCapable = false,
                category = "私信",
                idempotent = false,
                impossibleReason = "发私信。REST 端点仅认 Cookie（文档全篇没出现「或 APP」）；PiliPlus 已把整条私信链路改成 gRPC（lib/grpc/im.dart 与 lib/grpc/url.dart），" +
                        "说明 REST 侧没有可用的 APP 鉴权方式。" +
                                   "这是唯一「协议层认输」的功能：发私信需 web 登录，读私信仍走 APP 通道。",
                note = "发私信。**绝不重放**",
            ),
    )

    // ==================== 审计 P0 补登的 APP 专用端点 ====================
    //
    // 下面这些**不在** v5.2.0 的 136 条规则里，但审计报告 P0-1/2/3/7 给出了完整证据链
    // （文档原文 + PiliPlus 实现）。单独分节，方便 Lead 一眼看出「比上一版多了什么」，
    // 也方便下一轮只改这几条而不碰 136 条的历史迁移结果。

    /**
     * 投币（APP 专用）。
     *
     * 证据：bilibili-API-collect「投币视频（APP端）」→ `app.bilibili.com/x/v2/view/coin/add`，
     * **认证方式：仅可APP**，参数只有 `access_key / aid / multiply / select_like`（**没有 csrf**）。
     * PiliPlus `lib/http/api.dart:82` 与之逐字一致（`Api.coinVideo`）。
     *
     * 为什么必须有它：web 端点 `x/web-interface/coin/add` 是「仅可Cookie」，
     * 补 access_key 同路径也无效（回 -101）。所以只登 APP 端口的用户，
     * 不换这个端点就 100% 投不了币（审计 P0-1）。
     *
     * **绝不重放**（[ApiEndpoint.idempotent] = false）：会真实扣币，重复发送 = 重复扣币。
     */
    @JvmField
    val APP_ONLY_ENDPOINTS: List<ApiEndpoint> = listOf(
        ApiEndpoint(
            id = "video.coin.add",
            host = APP_HOST,
            path = "/x/v2/view/coin/add",
            method = ApiEndpoint.METHOD_POST,
            auth = AuthRequirement.APP_TOKEN,
            appCapable = true,
            appOnly = true,
            category = "视频/互动",
            loginRequired = true,
            idempotent = false,
            webEquivalentId = "video.coin.add.web",
            note = "投币（APP 专用）。参数只有 access_key / aid / multiply / select_like，没有 csrf。" +
                    "web 端点「仅可 Cookie」，所以 APP 端口只能用这条（审计 P0-1）。会真实扣币 —— 绝不重放。"
        ),

        /**
         * 点赞 / 取消点赞（APP 专用）。
         *
         * 证据：bilibili-API-collect「点赞视频（APP端）」→ `app.bilibili.com/x/v2/view/like`，**仅可APP**。
         * PiliPlus `lib/http/api.dart:53` 用的就是它，调用处 `lib/http/video.dart:503`
         * 传 `like: type ? '0' : '1'`。
         *
         * **★ 语义反转（最容易写错的地方）**：APP 端 `like=0` 是**点赞**、`like=1` 是**取消**；
         * web 端 `like=1` 是点赞、`like=2` 是取消。这个映射登记在 [ApiEndpoint.appValueMap] 里，
         * 由 [AuthChannel.adaptParams] 统一处理 —— **调用方不要自己手写 0/1**，
         * 否则用户点「点赞」在 APP 端口上会变成取消（审计 P0-2）。
         *
         * 幂等：按 (aid, like) 设置状态，重复点赞服务端回 65006（调用方已有点亮逻辑），可安全重发。
         */
        ApiEndpoint(
            id = "video.like.add",
            host = APP_HOST,
            path = "/x/v2/view/like",
            method = ApiEndpoint.METHOD_POST,
            auth = AuthRequirement.APP_TOKEN,
            appCapable = true,
            appOnly = true,
            category = "视频/互动",
            loginRequired = true,
            idempotent = true,
            webEquivalentId = "video.like.web",
            appValueMap = mapOf("like" to mapOf("1" to "0", "2" to "1")),
            verifiedOnApp = true,
            note = "点赞/取消（APP 专用）。★ 语义与 web 相反：APP like=0 是点赞、like=1 是取消" +
                    "（web 是 1=点赞 / 2=取消）。appValueMap 已登记映射：调用方传 web 语义的值即可，" +
                    "由 AuthChannel.adaptParams 统一转换 —— 别自己写 0/1。" +
                    "证据：PiliPlus lib/http/video.dart:497-512 实证。"
        ),

        /**
         * 收藏 / 取消收藏（三连拆解的第 3 步）。
         *
         * 文档标明本端点「Cookie 或 APP」都可用。136 条里已有同名端点 `fav.deal`
         * （带完整的 APP_PARAMS 参数适配），本条只是让「三连拆解清单」有一个自解释的 id。
         */
        ApiEndpoint(
            id = "fav.deal.triple",
            host = API_HOST,
            path = "/medialist/gateway/coll/resource/deal",
            method = ApiEndpoint.METHOD_POST,
            auth = AuthRequirement.EITHER,
            appCapable = true,
            category = "收藏",
            loginRequired = true,
            idempotent = true,
            webEquivalentId = "fav.deal",
            note = "收藏（三连拆解的第 3 步）。与 fav.deal 是同一端点，单独登记只为让三连拆解清单" +
                    "有一个自解释的 id。真正发请求请用 fav.deal 那条完整规则（含 csrf 丢弃等适配）。" +
                    "审计记录实测 -401（未登录态），APP 端口加/删收藏需真机再验一次。"
        ),

        /**
         * 三连状态（点赞 / 投币 / 收藏是否已做）。
         *
         * 审计 P0-7 的根因：`getVideoStats` 依赖 `x/web-interface/archive/relation`，
         * 而该端点在 APP 端口下回 -101 被静默吞掉 → 三个按钮永远不亮。
         * 审计报告指出 APP 侧候选为 `app.bilibili.com/x/v2/view/relation`
         * （**文档中未查到，需实测/抓包确认**），所以本表把它标为**未验证**。
         *
         * 为什么仍然登记：`coverageReport()` 需要知道「这个功能有 APP 候选、但还没证实」，
         * 而不是误以为它已经可用（那正是审计 P0-8 批评的错误口径）。
         */
        ApiEndpoint(
            id = "video.relation.app",
            host = APP_HOST,
            path = "/x/v2/view/relation",
            method = ApiEndpoint.METHOD_GET,
            auth = AuthRequirement.APP_TOKEN,
            appCapable = true,
            appOnly = true,
            category = "视频/互动",
            loginRequired = true,
            webEquivalentId = "video.relation",
            note = "三连状态（APP 侧候选）。审计 P0-7 指出它可能是 archive/relation 的 APP 等价，" +
                    "但**文档中没有查到、也未实测确认**，故标为未验证。" +
                    "若实测不可用，UI 必须把按钮显示成「中性态」而不是「未点赞」（P0-7 的第 3 条建议）。"
        ),

        /**
         * 硬币余额（APP 侧）。
         *
         * `account.bilibili.com/site/getCoin` 实测候选全 404，但 `app.bilibili.com/x/v2/account/myinfo`
         * 的 `data.coins` 就是余额（v5.2.0 的 `UserInfoApi.getCurrentUserCoin` 已试过这条路）。
         * 登记它，`wallet.coin.balance.web` 的 [ApiEndpoint.surrogateId] 才有落点，
         * 「任意端口都能看硬币余额」才成立。
         */
        ApiEndpoint(
            id = "wallet.coin.balance",
            host = APP_HOST,
            path = "/x/v2/account/myinfo",
            method = ApiEndpoint.METHOD_GET,
            auth = AuthRequirement.APP_TOKEN,
            appCapable = true,
            appOnly = true,
            category = "账号",
            loginRequired = true,
            note = "硬币余额（APP 侧）。读 data.coins。account.bilibili.com/site/getCoin 那套 web 端点" +
                    "实测全 404，APP 端口只有这条路能拿到余额。"
        ),

        /**
         * 搜索联想（REST 等价）。
         *
         * 旧规则把 `s.search.bilibili.com` 整域标成 KEEP_WEB（「无APP等价」），但审计与 PiliPlus
         * 都证明有 REST 等价 `api.bilibili.com/x/web-interface/suggest`（WBI 签名、匿名可用）：
         * PiliPlus `lib/http/api.dart:266` + `lib/http/search.dart:23-44`；
         * 本仓库 `SearchApi.getSearchSuggestions` 已实现在用（v5.2.0 P2-3）。
         * 所以「搜索联想」**不是**功能缺失 —— 登记它，让 surrogateId 有落点。
         */
        ApiEndpoint(
            id = "search.suggest",
            host = API_HOST,
            path = "/x/web-interface/suggest",
            method = ApiEndpoint.METHOD_GET,
            auth = AuthRequirement.NONE,
            appCapable = true,
            category = "搜索",
            requiredDataFields = listOf("result"),
            note = "搜索联想（REST 等价）。WBI 签名，读 data.result.tag[].value；匿名可用。" +
                    "旧规则把 s.search.bilibili.com 整域标成「无APP等价」，其实这一条就够用。"
        ),

        /**
         * 当前账号信息（APP 侧）。
         *
         * `x/space/myinfo` 是 web 端点；APP 侧同一份资料来自 `x/v2/account/myinfo`。
         * 这条同时承担「凭据校验」用途（见 [CredentialStore.validate]）。
         */
        ApiEndpoint(
            id = "user.account.myinfo",
            host = APP_HOST,
            path = "/x/v2/account/myinfo",
            method = ApiEndpoint.METHOD_GET,
            auth = AuthRequirement.APP_TOKEN,
            appCapable = true,
            appOnly = true,
            category = "账号",
            loginRequired = true,
            webEquivalentId = "user.myinfo",
            note = "当前账号信息（APP 侧）。也是 APP 端口「凭据是否仍有效」的校验端点" +
                    "（web 端校验走 protocol.nav 的 data.isLogin）。"
        ),

        /**
         * 观看进度心跳（APP 原生端点）。
         *
         * 审计 P0-4 的关键发现是**调用方 `mid` 为 0 就提前 return**，不是端点不可用。
         * 这条给 APP 端口一个原生心跳落点（PiliPlus `lib/http/video.dart:745` 用的就是它），
         * 参数 `aid|bvid / cid / progress / type`。
         */
        ApiEndpoint(
            id = "history.report.app",
            host = API_HOST,
            path = "/x/click-interface/web/heartbeat",
            method = ApiEndpoint.METHOD_POST,
            auth = AuthRequirement.APP_TOKEN,
            appCapable = true,
            category = "历史",
            loginRequired = true,
            idempotent = true,
            webEquivalentId = "history.report",
            note = "观看进度心跳（APP 原生端点，PiliPlus lib/http/video.dart:745 实证）。" +
                    "审计 P0-4 指出：web 未登录时 mid=0 → reportHistory 提前 return，进度 100% 不上报；" +
                    "本条给 APP 端口一个原生落点。"
        )
    )

    // ==================== 索引（首次访问时构建） ====================

    /**
     * id → 端点。
     *
     * 用 `by lazy` 而不是直接初始化：`object` 的 `<clinit>` 里构造 160+ 个对象 + 写 Map，
     * 一旦某步异常就会变成 `NoClassDefFoundError`（本仓库踩过这个坑，见
     * `NetWorkUtil.initialCookie` 的注释）。延迟到第一次真正查表时再建，失败也只影响这一次调用。
     */
    private val byId: Map<String, ApiEndpoint> by lazy { buildIndex() }

    /** (host.lowercase + path) → 端点，用于 [find] 的 O(1) 精确匹配 */
    private val byRoute: Map<String, ApiEndpoint> by lazy { buildRouteIndex() }

    private fun buildIndex(): Map<String, ApiEndpoint> {
        val map = LinkedHashMap<String, ApiEndpoint>()
        for (endpoint in allEndpoints()) {
            // 同一 id 只保留先声明者：APP_ONLY 里刻意与旧规则同主题的那几条都用了独立 id
            // （video.coin.add / video.like.add / fav.deal.triple …），不会与 136 条撞名
            map.getOrPut(endpoint.id) { endpoint }
        }
        return map
    }

    private fun buildRouteIndex(): Map<String, ApiEndpoint> {
        val map = LinkedHashMap<String, ApiEndpoint>()
        // 通配规则（单独星号 / 前缀星号）不进精确索引，只在 [find] 的兜底扫描里处理
        for (endpoint in allEndpoints()) {
            if (isWildcardPath(endpoint.path)) continue
            map.putIfAbsent(routeKey(endpoint.host, endpoint.path), endpoint)
        }
        return map
    }

    /**
     * 该 path 是否为通配规则（单独星号，或以「斜杠加星号」结尾的前缀）。
     *
     * 抽成函数而不是在循环里写两个 continue：一是让 [buildRouteIndex] 的意图一眼可读，
     * 二是避免 detekt 的 LoopWithTooManyJumpStatements（一个循环里多个跳出语句）。
     */
    private fun isWildcardPath(path: String): Boolean {
        return path == ApiEndpoint.ALL_PATHS || path.endsWith(ApiEndpoint.ALL_PATHS)
    }

    private fun routeKey(host: String, path: String): String {
        return host.lowercase(Locale.ROOT) + path
    }

    private fun allEndpoints(): List<ApiEndpoint> = MIGRATED_ENDPOINTS + APP_ONLY_ENDPOINTS

    // ==================== 公开查询接口（契约 §2） ====================

    /** 全部已登记端点（136 条迁移规则 + 审计补登的 APP 专用端点） */
    @JvmStatic
    fun all(): Collection<ApiEndpoint> = allEndpoints()

    /** 按 id 精确查 */
    @JvmStatic
    fun findById(id: String): ApiEndpoint? = byId[id]

    /** 按分类查（设置页 / 覆盖报告用） */
    @JvmStatic
    @JvmSuppressWildcards
    fun byCategory(category: String): List<ApiEndpoint> {
        val list = ArrayList<ApiEndpoint>()
        for (endpoint in allEndpoints()) {
            if (endpoint.category == category) list.add(endpoint)
        }
        return list
    }

    /** 全部分类名（去重、保序） */
    @JvmStatic
    @JvmSuppressWildcards
    fun categories(): List<String> {
        val seen = LinkedHashSet<String>()
        for (endpoint in allEndpoints()) seen.add(endpoint.category)
        return ArrayList(seen)
    }

    /**
     * 按 host + path 精确查（[RequestExecutor] 与 `ApiRouter` 的唯一入口）。
     *
     * `path` 必须传**未解码**的 URL path（`HttpUrl.encodedPath` 口径），否则中文/百分号路径会失配。
     *
     * 查找顺序：
     * 1. 精确索引（`host+path` 全等）；
     * 2. 按声明顺序全扫，处理 `"*"` / 前缀通配（如 passport.bilibili.com 的任意路径）。
     *
     * 未命中返回 null —— **调用方按原样透传**（契约 §5 明写的降级策略：不能因为漏登记
     * 一条规则就让整个功能挂掉）。
     */
    @JvmStatic
    fun find(host: String?, path: String?): ApiEndpoint? {
        if (host.isNullOrEmpty() || path.isNullOrEmpty()) return null
        val exact = byRoute[routeKey(host, path)]
        if (exact != null) return exact
        for (endpoint in allEndpoints()) {
            if (endpoint.matches(host, path)) return endpoint
        }
        return null
    }

    /** 是否有 APP 等价端点（决定「这个功能在非 web 端口能不能做」） */
    @JvmStatic
    fun hasAppEquivalent(id: String): Boolean {
        val endpoint = byId[id] ?: return false
        if (endpoint.auth == AuthRequirement.IMPOSSIBLE) {
            // IMPOSSIBLE 本身没有 APP 等价；但只要登记了 surrogate / splitInto，功能就还在
            return endpoint.surrogateId.isNotEmpty() || endpoint.splitInto.isNotEmpty()
        }
        return endpoint.appCapable || endpoint.auth == AuthRequirement.APP_TOKEN
    }

    /**
     * 协议层不可能迁移的端点 id 白名单。
     *
     * 用户明确要求「去掉回退 web」，所以这份清单必须**短且有理有据**：
     * 只剩「凭据签发（passport）」「gRPC-Web 独有」「非 API / 静态文件 / 页面地址」
     * 「非 B 站域名」「已由 surrogate 兜住的 web 专属端点」这几类。
     */
    @JvmStatic
    @JvmSuppressWildcards
    fun impossibleIds(): Set<String> {
        val set = LinkedHashSet<String>()
        for (endpoint in allEndpoints()) {
            if (endpoint.auth == AuthRequirement.IMPOSSIBLE) set.add(endpoint.id)
        }
        return set
    }

    // ==================== 覆盖报告（「任意端口全功能」的可检查证据） ====================

    /**
     * 覆盖报告的一行。
     *
     * @param endpointId 端点 id
     * @param needsLogin 是否需要登录
     * @param port 本次评估的端口（web / android / hd / tv）
     * @param available 该端口能否提供这个**功能**
     * @param reason 中文原因。**不允许**出现「回退 web」—— 那句话是用户明确要求删掉的语义
     * @param viaEndpointId 功能实际由哪个端点提供（本条可用时通常是自身；由 surrogate 兜住时是对方）
     * @param verified 是否有实测证据（false = 只是「按规则能改写」，还没验证服务端真的认）
     */
    class CoverageRow(
        @JvmField val endpointId: String,
        @JvmField val needsLogin: Boolean,
        @JvmField val port: String,
        @JvmField val available: Boolean,
        @JvmField val reason: String,
        @JvmField val viaEndpointId: String = "",
        @JvmField val verified: Boolean = false
    )

    /**
     * 覆盖报告统计（报告与设置页直接展示，不用调用方自己数）。
     *
     * # 口径：区分「协议上可迁」与「实际验证过」，两者不可互相替代
     *
     * 本报告刻意把两个数字分开报，而不是合成一个「覆盖率」：
     * - [available] 只是「按 catalog 判定应该可用」（协议层有等价形态）；
     * - [verified] 才是「有 `code=0` 且结构正确的实测证据」。
     *
     * 审计 P0-8 的核心教训就是这两者被混为一谈：旧规则表里三十多条 note 写着
     * 「实测 -101（通道被接受）」，而 -101 的真实含义是「账号未登录」。
     * 于是出现「号称迁移了九成、实际半残」这种数字 —— 用户投诉的「去掉 Web 登录之后都是半残」
     * 就是这么来的。
     *
     * **任何对外宣称的完成度都必须引用 [verified] 这一档，不能用 [available] 代替。**
     * 这条口径已由 Lead 采纳为本轮验收标准，并会写进 `repo/plan.md`，
     * 避免下一轮又把「37 条已实测」说成「九成可用」。
     */
    class CoverageSummary(
        @JvmField val port: String,
        @JvmField val total: Int,
        @JvmField val needsLogin: Int,
        @JvmField val available: Int,
        @JvmField val unavailable: Int,
        @JvmField val impossible: Int,
        @JvmField val verified: Int,
        @JvmField val unverified: Int
    ) {
        /** 一行中文摘要 */
        fun describe(): String {
            return "端口 " + port + "：端点 " + total + " 条，需登录 " + needsLogin + " 条，" +
                    "功能可用 " + available + " 条，不可用 " + unavailable + " 条" +
                    "（其中协议层不可能且无替代 " + impossible + " 条），" +
                    "已实测 " + verified + " 条 / 未实测 " + unverified + " 条"
        }
    }

    /**
     * 覆盖报告：列出所有端点 + **指定端口**能否提供。
     *
     * 这是「任意端口全功能」的**可检查证据**，不是口号 —— 用户原话「这一点非常重要」，
     * 所以必须有能一条条看的输出，而不是一句「已实现」。
     *
     * @param port 要评估的端口，默认取用户选中的 `AccountStore.mainPort()`。
     *   `web` / `android` / `hd` / `tv` 四个取值语义完全同级。
     */
    @JvmStatic
    @JvmSuppressWildcards
    @JvmOverloads
    fun coverageReport(port: String = AccountStore.mainPort()): List<CoverageRow> {
        val portValue = AccountStore.normalizePort(port)
        val rows = ArrayList<CoverageRow>(allEndpoints().size)
        val isApp = portValue != AccountStore.PORT_WEB
        val loggedIn = AccountStore.isPortLoggedIn(portValue)
        for (endpoint in allEndpoints()) {
            rows.add(coverageRowOf(endpoint, portValue, isApp, loggedIn))
        }
        return rows
    }

    /**
     * 覆盖报告统计（报告里要贴的那几个数字就来自这里）。
     *
     * **注意 [CoverageSummary.verified] 与 [CoverageSummary.available] 的区别**：
     * 前者是「有实测证据」，后者只是「按 catalog 判定应该可用」。审计 P0-8 的教训是
     * 不能把后者当前者 —— 所以两个数字分开报。
     */
    @JvmStatic
    @JvmSuppressWildcards
    @JvmOverloads
    fun coverageSummary(port: String = AccountStore.mainPort()): CoverageSummary {
        val portValue = AccountStore.normalizePort(port)
        var needsLogin = 0
        var available = 0
        var impossible = 0
        var verified = 0
        var unverified = 0
        for (row in coverageReport(portValue)) {
            if (row.needsLogin) needsLogin++
            if (row.available) available++
            // 「协议层不可能且无替代」才算 impossible；有 surrogate / splitInto 的算功能可用
            if (row.endpointId.isNotEmpty() && !row.available &&
                    impossibleIds().contains(row.endpointId)) impossible++
            if (row.verified) verified++ else unverified++
        }
        val total = allEndpoints().size
        return CoverageSummary(portValue, total, needsLogin, available, total - available,
                impossible, verified, unverified)
    }

    /**
     * 单条端点的评估（从 [coverageReport] 拆出来，避免那个函数圈复杂度超标）。
     */
    private fun coverageRowOf(
        endpoint: ApiEndpoint,
        port: String,
        isApp: Boolean,
        loggedIn: Boolean
    ): CoverageRow {
        val needsLogin = endpoint.needsLogin()
        if (endpoint.auth == AuthRequirement.IMPOSSIBLE) {
            return impossibleRow(endpoint, needsLogin, port)
        }
        if (needsLogin && !loggedIn) {
            return CoverageRow(endpoint.id, true, port, false,
                "端口 " + port + " 尚未登录：在该端口登录后此功能即可用" +
                        "（四端同级，任意一个端口登录都能跑全部功能）",
                endpoint.id, endpoint.verifiedOnApp)
        }
        return if (isApp) appPortRow(endpoint, port, needsLogin) else webPortRow(endpoint, port, needsLogin)
    }

    /** IMPOSSIBLE 的评估：重点是把「为什么不可能 + 功能由谁兜住」说清 */
    private fun impossibleRow(endpoint: ApiEndpoint, needsLogin: Boolean, port: String): CoverageRow {
        val via = endpoint.surrogateId
        if (via.isNotEmpty()) {
            return CoverageRow(endpoint.id, needsLogin, port, true,
                "本端点协议层没有 APP 等价形态，但同一功能由 " + via + " 提供，任意端口都能用",
                via, false)
        }
        if (endpoint.splitInto.isNotEmpty()) {
            return CoverageRow(endpoint.id, needsLogin, port, true,
                "本端点协议层没有 APP 等价形态，功能拆成 " + endpoint.splitInto.size +
                        " 次独立调用完成（" + endpoint.splitInto.joinToString(" + ") +
                        "），原子性由调用方拼装结果",
                endpoint.splitInto[0], false)
        }
        return CoverageRow(endpoint.id, needsLogin, port, false,
            endpoint.impossibleReason.ifEmpty { "协议层没有可用的 APP 等价端点" }, "", false)
    }

    /** APP 端口的评估 */
    private fun appPortRow(endpoint: ApiEndpoint, port: String, needsLogin: Boolean): CoverageRow {
        if (endpoint.auth == AuthRequirement.APP_TOKEN || endpoint.appOnly) {
            return CoverageRow(endpoint.id, needsLogin, port, true,
                "APP 专用端点，" + port + " 端口直连（web 端口形态由 webEquivalentId 指向的端点提供）",
                endpoint.id, endpoint.verifiedOnApp)
        }
        if (endpoint.appCapable) {
            return CoverageRow(endpoint.id, needsLogin, port, true,
                "APP 通道支持（同路径参数化或路径改写），" + port + " 端口直连",
                endpoint.id, endpoint.verifiedOnApp)
        }
        val via = endpoint.surrogateId
        if (via.isNotEmpty()) {
            return CoverageRow(endpoint.id, needsLogin, port, true,
                "本端点仅可 Web Cookie，APP 端口由 " + via + " 提供同一功能", via, false)
        }
        return CoverageRow(endpoint.id, needsLogin, port, false,
            "本端点仅可 Web Cookie，且没有登记 APP 等价端点", "", false)
    }

    /** web 端口的评估 */
    private fun webPortRow(endpoint: ApiEndpoint, port: String, needsLogin: Boolean): CoverageRow {
        if (endpoint.appOnly) {
            return CoverageRow(endpoint.id, needsLogin, port, false,
                "该端点是 APP 专用端点，web 端口没有对应形态" +
                        (if (endpoint.webEquivalentId.isEmpty()) "" else
                            "（web 形态见 " + endpoint.webEquivalentId + "）"),
                endpoint.webEquivalentId, false)
        }
        // verified 只对「有实测证据」的端点置 true。web 端口天然可直连**不等于**已验证 ——
        // 审计 P0-8 的核心教训就是不能把「协议上可行」当成「实测通过」，
        // 所以这里沿用端点自带的 verifiedOnApp，而不是无条件写 true。
        return CoverageRow(endpoint.id, needsLogin, port, true,
            "web 端口可直连", endpoint.id, endpoint.verifiedOnApp)
    }
}
