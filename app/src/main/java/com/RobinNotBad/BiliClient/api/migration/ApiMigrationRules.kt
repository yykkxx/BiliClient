package com.RobinNotBad.BiliClient.api.migration

import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.util.AccountStore

/**
 * APP 通道的端点映射表（「账号管理 → 其它页面 API」选非 Web 端口时生效）。
 *
 * ## 这个功能在做什么
 *
 * 开启后，[com.RobinNotBad.BiliClient.util.NetWorkUtil] 里的每个 bilibili API 请求会先被
 * [ApiRouter] 尝试改写成「APP 通道」：按当前客户端身份（HD / TV / 手机，复用
 * `api/AppRcmdApi.kt` 的 RcmdIdentity / token 槽位）补上 appkey / ts / sign /
 * mobi_app / platform / build / device_name / buvid / access_key，并换成对应 APP 的
 * User-Agent 与 `app-key` 请求头。
 *
 * alpha9 的策略是「只换签名参数、尽量保留 web 路径 + 一大堆 KEEP_WEB」；
 * alpha10 按用户要求改成**真迁移**：只要协议层存在 APP 等价端点就迁，web 路径只保留
 * 「协议层确实没有 REST 等价物」的极少数（见 [keepWebRules]）。
 *
 * ## 三种迁移方式（[Kind]）
 *
 * - [Kind.APP_PARAMS]：同 host 同路径，只换身份与签名参数。响应结构不变，
 *   现有解析器零改动 —— 这是最安全的一类，实测覆盖面也最大。
 * - [Kind.PATH_OVERRIDE]：换 host 和/或路径到官方 APP 专用端点
 *   （如 api.bilibili.com/x/player/wbi/playurl → 同 host /x/player/playurl；
 *   api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo →
 *   同 host /xlive/app-room/v1/index/getDanmuInfo；
 *   api.bilibili.com/x/space/wbi/arc/search → app.bilibili.com/x/v2/space/archive）。
 *   支持 [Rule.renameParams] 改参数名、[Rule.extraParams] 补 APP 必需参数。
 * - [Kind.KEEP_WEB]：保持 web。**每一条都必须写明「为什么在协议层不可能迁移」**。
 *
 * ## 写操作安全（重要）
 *
 * - [Rule.isPostSafe] = 幂等写操作（点赞/收藏/关注/稍后再看/历史上报/评论点赞/收藏夹改名…）：
 *   失败可以安全回退 web 重发一次，结果一致。
 * - [Rule.isPostWrite] = **非幂等写操作**（投币/发评论/发动态/发弹幕/发私信/删评论…）：
 *   也走 APP 通道，但一旦请求「可能已经发出」，就**绝不回退重发**——
 *   NetWorkUtil.post 会直接把 APP 通道的错误响应/异常抛给上层，由业务层提示用户，
 *   避免「响应超时 + 回退」造成重复投币 / 重复发送。
 *   只有「连接根本没建立」（ConnectException / UnknownHostException 等）才允许回退。
 *
 * ## 依据来源（本仓库 `app_api/` 下为只读参考实现）
 *
 * - PiliPlus：`lib/http/api.dart`（APP 端点常量）、`lib/http/video.dart`（playInfo/playurl 参数）、
 *   `lib/http/live.dart`、`lib/http/msg.dart`、`lib/common/constants.dart`（三套 appkey/UA/mobi_app）、
 *   `lib/utils/app_sign.dart`（appkey/ts/sign 算法）。
 * - PiliPala：`lib/http/api.dart`、`lib/utils/utils.dart`（appSign），作第二信源。
 * - bilibili-API-collect（socialsisteryi.github.io）端点与签名文档。
 * - **本机 curl 匿名实测（2026-10-29，HD/TV/手机三套 appkey 各跑一遍）**，逐条结果见
 *   `notes/api_full_migration_alpha10.md` 的「实测记录」表。
 * - **v5.3.0 真实四端凭据补测（2026-11-07，web/android/hd/tv 同一账号）**，
 *   逐条结果见 `notes/api_matrix_v530.md` 的 §5。这是本文件所有注释里
 *   「实测通过 / 实测失败」标注的**唯一依据**。
 *
 * ## 规则注释里的三档标注（v5.3.0 新增，别删）
 *
 * 每条规则上方都有一行 `// [实测通过]` / `// [未实测]` / `// [实测失败]`：
 * - **实测通过**：v5.3.0 用真实凭据跑过 `code=0`，注释里写明是哪个端口（三端通常一致）。
 * - **未实测**：本轮没跑。**所有写操作都在这一类**（点赞/投币/收藏/关注/稍后再看/发评论/
 *   发动态/发私信/发弹幕…有真实副作用，实测会污染用户正在使用的账号）——这是铁律，不是偷懒。
 * - **实测失败**：跑过但不是 0，注释里写清 `code` + host/path + 复现条件。
 *
 * 三档是给人看的「可信度标签」：改规则前先看标签，`实测失败` 的那几条不要再当作可用端点。
 *
 * ## 实测定下来的两条硬结论（别再改回去）
 *
 * 1. api.bilibili.com/x/player/playurl 的参数名必须是 **avid**；传 aid= 直接 -400。
 * 2. api.bilibili.com/x/player/v2 服务端**不校验** aid/bvid 与 cid 的一致性
 *    （bvid=BV1GJ…&cid=<别的视频的 cid> 会回 aid=80433022 + 那个 cid，混着回）。
 *    所以字幕必须自己做 cid 校验，见 `api/PlayerApi.getSubtitleLinks`。
 */
object ApiMigrationRules {

    /** 迁移方式 */
    enum class Kind {
        /** 同 host 同路径 APP 参数化：只换签名/身份参数（响应结构不变） */
        APP_PARAMS,

        /** 路径/主机改写：换成官方 APP 专用端点（可能改名参数） */
        PATH_OVERRIDE,

        /** 保持 web：协议层没有 APP 等价物，禁止改写 */
        KEEP_WEB
    }

    /**
     * 一条映射规则。
     *
     * @param host 精确 host（小写）
     * @param path 精确路径；`"*"` 表示该 host 全部路径；以「斜杠 + 星号」结尾表示前缀匹配
     *   （Kotlin 注释里不能直接写那个符号，会被当成嵌套注释开始）
     * @param kind 迁移方式
     * @param targetHost PATH_OVERRIDE 的目标 host；null = host 不变
     * @param targetPath PATH_OVERRIDE 的目标路径；null = 路径不变
     * @param renameParams 参数改名（web 参数名 → APP 参数名），如 mid → vmid、id → room_id
     * @param extraParams APP 端点额外必需参数（签名前注入，值固定）
     * @param isPostSafe POST 是否允许迁移且**允许失败回退重发**（仅幂等写操作）
     * @param isPostWrite POST 是否允许迁移但**绝不重放**（非幂等写操作）
     * @param isLoginRequired 是否必须当前身份已登录才迁移。默认 true：
     *   未登录时 APP 通道会以匿名身份请求，部分接口仍返回 code=0 但数据是空的
     *   （收藏夹、历史、动态、播放地址清晰度等），这种「静默空数据」回退检测抓不到。
     * @param preferredIdentity 指定身份（`AppRcmdApi.IDENTITY_*`）；null = 跟随用户当前身份。
     *   本轮实测三身份表现一致，未发现必须定点覆盖的端点，字段保留给后续使用。
     * @param category 设置页「按分类覆盖数」用的分组名
     * @param note 依据 / 原因（写进设置页与文档，便于回查）
     */
    class Rule(
        @JvmField val host: String,
        @JvmField val path: String,
        @JvmField val kind: Kind,
        @JvmField val targetHost: String? = null,
        @JvmField val targetPath: String? = null,
        @JvmField val renameParams: Map<String, String>? = null,
        @JvmField val extraParams: Map<String, String>? = null,
        @JvmField val isPostSafe: Boolean = false,
        @JvmField val isPostWrite: Boolean = false,
        @JvmField val isLoginRequired: Boolean = true,
        @JvmField val preferredIdentity: String? = null,
        /**
         * 允许把哪些业务码当成「通道可用」。
         *
         * 默认情况下 classify 遇到 -101/-352/-400… 一律回退 web；但有的端点
         * **匿名也返回正确数据**，只是顺带回一个 -101（典型：`x/web-interface/nav`
         * 会带完整的 `wbi_img`，只是 `isLogin=false`）。这种端点用本字段放行。
         * 放行不等于盲信 —— 必须配合 [requiredDataFields] 做内容校验。
         */
        @JvmField val acceptedCodes: IntArray? = null,
        /**
         * 内容校验：这些「点号路径」必须都能在回包的 `data` 里找到，找不到就回退 web。
         *
         * 用途：`acceptedCodes` 放行了 -101 之后，还要确认「该拿到的数据真的在」，
         * 否则一旦服务端行为变化，会把「静默空数据」当成成功。
         */
        @JvmField val requiredDataFields: List<String>? = null,
        /**
         * 允许二进制响应（protobuf / deflate 等非 JSON）。
         *
         * 弹幕分片 `seg.so` 返回的就是 protobuf；它只是**响应格式**不是 JSON，
         * 不影响走 APP 通道，但 `classify` 的「非 JSON 即失败」会误判。
         */
        @JvmField val allowBinary: Boolean = false,
        @JvmField val category: String = "其他",
        @JvmField val note: String = ""
    ) {
        /** 该规则是否命中给定 host + path（path 为未解码的 URL path） */
        fun matches(host: String, path: String): Boolean {
            if (!this.host.equals(host, ignoreCase = true)) return false
            return when {
                this.path == "*" -> true
                this.path.endsWith("/*") -> path.startsWith(this.path.substring(0, this.path.length - 1))
                else -> path == this.path
            }
        }

        /** 是否为写操作规则（两种写清单之一） */
        fun isPostRule(): Boolean = isPostSafe || isPostWrite
    }

    /** 默认 APP 主机：未命中显式规则的 GET 在这里按同路径 APP 参数化尝试 */
    const val API_HOST = "api.bilibili.com"

    /** 官方手机端 APP 专用接口主机 */
    const val APP_HOST = "app.bilibili.com"

    /** 直播域 */
    const val LIVE_HOST = "api.live.bilibili.com"

    /** 私信 / 动态旧域 */
    const val VC_HOST = "api.vc.bilibili.com"

    /** 通知中心域（APP 通道实测 -101 未登录 = 通道被接受） */
    const val MSG_HOST = "message.bilibili.com"

    /** 创作中心（APP 通道实测 -101 = 通道被接受） */
    const val MEMBER_HOST = "member.bilibili.com"

    /**
     * 直播 APP feed 端点的固定客户端参数。
     *
     * 实测：`xlive/app-interface/v2/index/feed` 只带 `page`/`platform` 会 **-400**，
     * 必须补齐 PiliPlus `Api.liveFeedIndex` 那套客户端参数才 code=0。
     * 这些值与 `AppRcmdApi.PHONE_IDENTITY`（android / 8.43.0 / 8430300）对齐。
     */
    private val LIVE_FEED_PARAMS = mapOf(
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

    /**
     * 路径前缀 → 分类。**顺序有意义**（先匹配先生效），
     * `web-dynamic` 必须排在 `web-space` 之前之类的细节都在这个表里。
     */
    private val CATEGORY_PREFIXES = listOf(
            "/x/v2/reply" to "评论",
            "/x/v3/fav" to "收藏",
            "/x/space/fav" to "收藏",
            "/x/polymer/web-dynamic" to "动态",
            "/x/dynamic" to "动态",
            "/x/polymer/web-space" to "合集",
            "/x/series" to "合集",
            "/x/relation" to "用户/关注",
            "/x/space" to "用户/关注",
            "/x/msgfeed" to "消息",
            "/x/sys-msg" to "消息",
            "/x/member" to "消息",
            "/x/vip" to "消息",
            "/pgc" to "番剧",
            "/x/article" to "专栏",
            "/x/web-interface/wbi/search" to "搜索",
            "/x/web-interface/ranking" to "排行/热门",
            "/x/web-interface/popular" to "排行/热门",
            "/x/v2/history" to "历史",
            "/x/web-interface/history" to "历史",
            "/x/emote" to "表情",
            "/x/safecenter" to "协议",
            "/x/web-interface/nav" to "协议",
            "/x/frontend/finger" to "协议",
            "/x/web-frontend/getbuvid" to "协议",
            "/x/v2/dm" to "弹幕",
            "/x/emote" to "表情",
            "/x/stein" to "互动/其他",
            "/x/ugcpay-rank" to "互动/其他"
    )

    private val RULES: List<Rule> = keepWebRules() + pathOverrideRules() +
            publicGetRules() + loginGetRules() + writeRules()

    // ==================== 保留项（协议层没有 APP 等价物） ====================

    /**
     * 只有这些真的不迁，分七组理由：
     * 1. 保留·登录协议 —— 登录协议 / WBI 引导 / 指纹 / gaia 风控，APP 侧是另一套机制；
     * 2. 保留·web独有机制 —— 浏览器指纹 / bili_ticket；
     * 3. 保留·gRPC独有 —— 无 REST 等价；
     * 4. 保留·非API —— 二进制 / 页面地址 / 埋点；
     * 5. 保留·无APP等价 —— B 站 REST 业务接口里确实拿不到 APP 等价的（硬币 / 搜索联想 / 发动态）；
     * 6. 保留·非B站 —— 自有服务器与第三方接口；
     * 7. **保留·实测被拒**（v5.3.0 新增）—— web 端口正常、APP 三端**稳定失败**的端点。
     *
     * 其中「发动态」是**路由层不收 JSON body** 的死规则（原先误列在写操作清单里），P1-bis 已归到本组。
     *
     * ## 为什么第 7 组必须存在（v5.3.0 的关键教训）
     *
     * v5.2.1 之后 [com.RobinNotBad.BiliClient.util.NetWorkUtil] **不再回退 web**：
     * APP 通道失败时只把坏响应原样交给上层。于是「一条必然失败的迁移规则」不再是
     * 「多花一个 RTT」这种小浪费，而是**该功能在整个 APP 端口下直接消失**，且没有自愈路径。
     * v5.3.0 用真实四端凭据补测（见 `notes/api_matrix_v530.md` §5）发现有 4 个端点属于这种情况
     * （用户空间动态 HTTP412、合集内视频 -352、member 创作中心 -101、充电榜 -352），
     * 全部从迁移表移到本组。以后新增规则请先实测；稳定失败的端点不要放进迁移表。
     */
    private fun keepWebRules(): List<Rule> = listOf(
            // —— 1. 登录握手本身（APP 侧是另一套协议，不是「没迁」）——
            // [未实测] passport 登录握手无法用 access_key 通道验证（APP 侧是另一套协议），本轮未跑
            Rule("passport.bilibili.com", "*", Kind.KEEP_WEB, category = "保留·登录协议",
                    note = "登录握手。扫码登录本身就是 APP 协议（passport-tv-login auth_code/poll，已在用），" +
                            "web Cookie 登录（passport-login/web/qrcode/*、cookie/refresh、sso/list、exit/v2）" +
                            "只能由 web 协议签发 —— web 登录态没有 APP 等价物"),
            // [未实测] 同 R001，passport-tv-login 备用域，未跑
            Rule("passport.snm0516.aisee.tv", "*", Kind.KEEP_WEB, category = "保留·登录协议",
                    note = "passport-tv-login/auth_code 的备用域（AppRcmdApi.AUTH_CODE_URL_BAK），同一条协议"),
            // —— 2. web 独有的指纹/风控机制（APP 侧不用这套）——
            // [未实测] gaia 指纹上报需要浏览器运行时载荷，本轮未构造，未实测
            Rule(API_HOST, "/x/internal/*", Kind.KEEP_WEB, category = "保留·web独有机制",
                    note = "gaia 网关 ExClimbWuzhi。实测 APP 参数下 code=130212（通道有响应），" +
                            "但它的用途是**激活 web Cookie**：payload 是照抄 log-reporter.js 的浏览器指纹" +
                            "（USER_AGENT_WEB / WebGL 渲染器 / 插件列表 / 屏幕分辨率 / _uuid）。" +
                            "换成 APP 通道等于自我否认，功能上不存在等价物。APP 侧用 access_key 登录，不需要激活"),
            // —— 3. gRPC-Web 独有语义（无 REST 等价）——
            // [未实测] gRPC-Web 端点，本轮未构造 protobuf 请求，未实测
            Rule(API_HOST, "/bapis/main.community.interface.emote.EmoteService/AllPackages", Kind.KEEP_WEB,
                    category = "保留·gRPC独有",
                    note = "「全站可添加表情包」分页搜索。实测 REST 候选只有 /x/emote/user/panel，" +
                            "返回的是「我已拥有的表情包」且不支持 pn/ps/search 分页 → 语义不等价。" +
                            "PiliPlus 也没有 REST 版（只用 myEmote = /x/emote/user/panel/web）"),
            // [未实测] bili_ticket 生成属风控体系，本轮未跑（避免干扰风控计数）
            Rule(API_HOST, "/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket", Kind.KEEP_WEB,
                    category = "保留·gRPC独有",
                    note = "生成 web 风控 ticket（bili_ticket），与 gaia 同性质：服务 web 通道的指纹体系，" +
                            "APP 通道用 appkey+access_key，不需要 ticket"),
            // —— 4. 非 API（静态文件 / 页面地址 / 埋点）——
            // [未实测] comment.bilibili.com 是静态 XML 文件，不是 API，不需要实测
            Rule("comment.bilibili.com", "*", Kind.KEEP_WEB, category = "保留·非API",
                    note = "https://comment.bilibili.com/<cid>.xml 是**静态 XML 文件**（deflate），" +
                            "CDN 直出、无签名、无登录态、无 appkey 概念 → 不是 API。" +
                            "现役弹幕链路已是 x/v2/dm/web/seg.so（protobuf），XML 只是兜底"),
            // [未实测] www.bilibili.com 只有页面地址拼接，代码里没有 /x/ 形态调用，不需要实测
            Rule("www.bilibili.com", "*", Kind.KEEP_WEB, category = "保留·非API",
                    note = "页面 / 分享地址拼接（video/BV…、read/cv…、opus/…、bangumi/play/ep…），" +
                            "没有任何 www.bilibili.com/x/… 形态的真实 API 调用"),
            // [未实测] live.bilibili.com 只用于直播间页面地址，不是 API，不需要实测
            Rule("live.bilibili.com", "*", Kind.KEEP_WEB, category = "保留·非API",
                    note = "直播间页面地址（live.bilibili.com/<roomid>），不是 API"),
            // [未实测] bvc.bilivideo.com 高能进度条为二进制 pbp 数据，本轮未跑
            Rule("bvc.bilivideo.com", "*", Kind.KEEP_WEB, category = "保留·非API",
                    note = "pbp/data 高能进度条数据。实测 REST 侧无等价（/x/v2/coin* 全 404；" +
                            "x/player/v2 只有 elec_high_level=充电等级，不是高能进度；PiliPlus 也无替代）。" +
                            "只影响「高能进度条」这一装饰功能，调用方已 try/catch"),
            // —— 5. B 站 API 里确实拿不到 APP 等价的 ——
            // [实测通过] 已实测 web 端口 account.bilibili.com/site/getCoin -> code=0、data.money=482.3；APP 侧候选（myinfo.coins）语义等价但路径不同，故仍保持 web 合理
            Rule("account.bilibili.com", "*", Kind.KEEP_WEB, category = "保留·无APP等价",
                    note = "硬币余额 site/getCoin。实测候选全 404：x/v2/coin/user/coin、x/v2/coin/balance、" +
                            "x/v2/coin、x/v2/account/mine；PiliPlus 的 /x/v2/view/coin/add 是**投币**不是余额；" +
                            "nav.money 在 APP 通道下不回 money（data 只剩 isLogin/wbi_img/ip_region）"),
            // [实测通过] web 端口 s.search.bilibili.com/main/suggest -> code=0；
            // APP 候选 /x/v2/search/suggest3 三端 code=0 但 data.list[] 全是
            // uri/goto/card_style 富卡片，无裸词条文本 -> 确认无等价，保持 web
            Rule("s.search.bilibili.com", "*", Kind.KEEP_WEB, category = "保留·无APP等价",
                    note = "搜索联想。**更正 alpha10 的说法：它返回的是纯 JSON 不是 JSONP**" +
                            "（{\"exp_str\":\"\",\"code\":0,\"result\":{\"tag\":[{value,term,name}]}}）。" +
                            "仍无法迁：APP 侧只有卡片式 /x/v2/search/suggest3（code=0 但 list[] 是" +
                            "uri/goto/card_style 富卡片，无纯词条文本，匿名下 card_style 为空），" +
                            "而 /x/v2/search/suggest?term=&main_ver=v1&func=suggest 只回 trackid、无联想词"),
            // [未实测] 发动态仅接受 JSON body，路由层 planPost 要求 form-urlencoded（死规则），未实测
            Rule(API_HOST, "/x/dynamic/feed/create/dyn", Kind.KEEP_WEB, category = "保留·无APP等价",
                    note = "发动态。**仅 JSON body**（DynamicApi.publishComplex 用 NetWorkUtil.postJson），" +
                            "而路由层 planPost 首行就要求 x-www-form-urlencoded（ApiRouter.planPost）" +
                            "→ 这条原先的写规则**永远不会生效**，是死规则。PiliPlus 同样用 web + csrf" +
                            "（lib/http/dynamics.dart），APP 侧只有 gRPC 的 dynamic_svr/create 无 REST 等价。" +
                            "结论：保持 web，并在发动态入口提示「需 web 登录」"),
            // —— 6. 非 B 站服务器 ——
            // [未实测] 本软件自有服务器，不是 B 站接口，不需要实测
            Rule("api.biliterminal.cn", "*", Kind.KEEP_WEB, category = "保留·非B站",
                    note = "本软件自有服务器，不是 B 站接口"),
            // [未实测] 第三方 AI 接口，不是 B 站接口，不需要实测
            Rule("api.deepseek.com", "*", Kind.KEEP_WEB, category = "保留·非B站",
                    note = "第三方 AI 接口，不是 B 站接口"),
            // —— 7. 实测被拒（v5.3.0 用真实凭据补测新增，详见 notes/api_matrix_v530.md §5.7/5.8/5.11/5.13）——
            //
            // 为什么这批必须挪进 KEEP_WEB 而不是留在迁移表：
            // v5.2.1 之后路由层**不再回退 web**（NetWorkUtil 只把坏响应原样交给上层），
            // 所以一条「必然失败」的迁移规则 = 该功能在 APP 端口下**直接不可用**，且没有自愈路径。
            // 下面每条都是「web 端口正常、APP 三端稳定失败」的实证，留着就是 bug。
            // [实测失败] android/hd/tv 稳定 HTTP412（12/12 全挂），同路径 web 端口 code=0 -> 端点级拒绝
            Rule(API_HOST, "/x/polymer/web-dynamic/v1/feed/space", Kind.KEEP_WEB,
                    category = "保留·实测被拒",
                    note = "用户空间动态。实测 android/hd/tv **稳定 HTTP 412**（换 3 个 mid=2/546195/1969630448、" +
                            "加 offset/page、间隔重试共 12 次全挂），同路径 web 端口 code=0（items[24]）。" +
                            "不是抖动，是端点级拒绝。保持 web：DynamicApi.getUserDynamic 是空间页主数据，" +
                            "迁过去整页空白。APP 等价候选 dynamic_svr/dynamic_new 实测 code=0，但语义是" +
                            "「关注动态」而非「某人的空间动态」，且结构由 items 变 cards，需解析层配套改造，本轮不做"),
            // [实测失败] android/hd/tv 连续 8 次全 -352（真实 season_id），web 端口 code=0 -> 端点级拒绝
            Rule(API_HOST, "/x/polymer/web-space/seasons_archives_list", Kind.KEEP_WEB,
                    category = "保留·实测被拒",
                    note = "合集内视频。用真实 season_id=1903592（web 端口正常回 archives[10]）实测，" +
                            "android/hd/tv **连续 8 次全部 -352**；再补 sort_reverse、去掉 mid 重试仍是 -352。" +
                            "端点级拒绝，保持 web。注意「合集内视频」与「系列内视频」不是一回事：" +
                            "/x/series/archives 三端实测 code=0，那条继续迁移"),
            // [实测失败] android/hd/tv 稳定 -101（web Cookie 侧 code=0）-> member 域不接受 access_key
            Rule(MEMBER_HOST, "/x/web/index/stat", Kind.KEEP_WEB, category = "保留·实测被拒",
                    note = "创作中心数据。实测 **member.bilibili.com 不接受 access_key**：" +
                            "web Cookie 侧 code=0，而 android/hd/tv 三端稳定 -101「账号未登录」；" +
                            "同域 /x/web/archive/data 四端都 404（域名可达、路由正常，不是连不上）。" +
                            "CreativeCenterApi.getVideoStat() 会直接拿到 null"),
            // [实测失败] 同 stat：三端稳定 -101，member 域不接受 access_key
            Rule(MEMBER_HOST, "/x/web/index/scrolls", Kind.KEEP_WEB, category = "保留·实测被拒",
                    note = "创作中心公告。同 /x/web/index/stat：member 域不接受 access_key，" +
                            "三端稳定 -101。CreativeCenterApi.getBeUPTime() 会拿到 -101 而非天数"),
            // [实测失败] android/hd/tv 稳定 -352（4 个 up_mid 逐一试），web 端口 code=0/88214 均正常
            Rule(API_HOST, "/x/ugcpay-rank/elec/month/up", Kind.KEEP_WEB,
                    category = "保留·实测被拒",
                    note = "充电公示榜。实测 4 个不同 up_mid（2 / 546195 / 1969630448 / 1024）：" +
                            "web 端口回 code=0（其中 1024 回 88214=UP 未开通充电，属正常业务码），" +
                            "而 android/hd/tv **全部 -352**，一次都没成功 → 端点级拒绝，保持 web。" +
                            "危害小于前三条（ElectricApi 在 code!=0 时返回 null，不崩，只是不显示充电面板），" +
                            "但「必然失败」的规则不该留在迁移表里")
    )

    // ==================== 路径 / 主机改写 ====================

    /**
     * 换端点的清单。每条都实测过：web 路径 + APP 参数被拒（或结构不可用），
     * 而 APP 专用端点返回兼容结构。
     */
    private fun pathOverrideRules(): List<Rule> = listOf(
            // 播放地址：实测 dash/durl 结构与 web 一致，timelength 跟随 cid
            // [实测通过] android/hd/tv 三端 code=0（fnval=16 返回 data.dash，7 个键；参数名 avid 正确）
            Rule(API_HOST, "/x/player/wbi/playurl", Kind.PATH_OVERRIDE,
                    targetPath = "/x/player/playurl", category = "视频/播放",
                    note = "APP 播放地址端点。实测 fnval=16 返回 data.dash、fnval=1 返回 data.durl，" +
                            "timelength 跟随 cid（199400 vs 205728），accept_description/last_play_cid 齐全。" +
                            "注意参数名必须 avid，传 aid 直接 -400"),
            // 播放器信息（字幕/看点/互动）：实测逐字段等价，data.cid 跟随请求
            // [实测通过] android/hd/tv 三端 code=0（level_info{5}/subtitle{6}，与 web 相同）
            Rule(API_HOST, "/x/player/wbi/v2", Kind.PATH_OVERRIDE,
                    targetPath = "/x/player/v2", category = "视频/播放",
                    note = "APP 播放器信息端点（字幕/看点/在线人数）。实测与 web 逐字段相同、data.cid 跟随请求；" +
                            "但服务端不校验 aid/bvid 与 cid 一致性，字幕必须自己校验 cid（见 PlayerApi）"),
            // UP 主投稿：web 的 wbi 版本三身份全被拒，APP 手机端专用端点可用
            // [实测通过] app.bilibili.com/x/v2/space/archive 三端 code=0（item[5] count=22）；同路径放 api 主机为 HTTP404，host 必须 app
            Rule(API_HOST, "/x/space/wbi/arc/search", Kind.PATH_OVERRIDE,
                    targetHost = APP_HOST, targetPath = "/x/v2/space/archive",
                    renameParams = mapOf("mid" to "vmid"), category = "用户/关注",
                    note = "UP 主投稿。实测 web wbi 路径带 APP 参数三身份全 -352/-403/412；" +
                            "app.bilibili.com/x/v2/space/archive 实测 code=0（data.item[]），" +
                            "解析兼容见 UserInfoApi.getUserVideos"),
            // UP 主专栏：web wbi 版 -403，APP 版 code=0
            // [实测通过] /x/space/article 三端 code=0；用有专栏的 UP(mid=946974) 实测 count=126 + list 齐全，与 web wbi 版逐字段一致
            Rule(API_HOST, "/x/space/wbi/article", Kind.PATH_OVERRIDE,
                    targetPath = "/x/space/article", category = "专栏",
                    note = "UP 主专栏列表。实测 web wbi 路径带 APP 参数 -403；/x/space/article 实测 code=0"),
            // 直播：弹幕配置。web-room 路径 -352，app-room 端点 code=0，结构仅多一个 ip_list
            // [实测通过] app-room 端点三端 code=0（token/host_list/ip_list 齐全）；web-room 路径三端稳定 -352 -> 改写成立
            Rule(LIVE_HOST, "/xlive/web-room/v1/index/getDanmuInfo", Kind.PATH_OVERRIDE,
                    targetPath = "/xlive/app-room/v1/index/getDanmuInfo",
                    renameParams = mapOf("id" to "room_id"), category = "直播",
                    note = "直播弹幕服务器配置。实测 web-room 路径带 APP 参数 -352；" +
                            "app-room 端点 code=0，data 键与 web 完全一致" +
                            "（group/business_id/token/host_list，仅多 ip_list），零解析改动"),
            // 番剧播放地址：v1 在 APP 参数下也能拿到 result.durl，但 v2 是官方现行端点
            // [实测通过] v1 与 v2 都用真实 ep_id=321808 实测：四端全 code=0 且顶层 result 有值。v1 并非不可用，改 v2 只是字段更全的可选优化
            Rule(API_HOST, "/pgc/player/web/playurl", Kind.PATH_OVERRIDE,
                    targetPath = "/pgc/player/web/v2/playurl", category = "番剧",
                    note = "番剧播放地址。PiliPlus Api.pgcUrl 即 web/v2 版本；v2 字段更全"),
            // UP 主空间信息：web wbi 路径带 APP 参数 -352/-403，改走 APP 手机端空间接口
            // [实测通过] app.bilibili.com/x/v2/space 三端 code=0（data 有 season 等）；api 主机同路径 HTTP404 -> host 必须 app
            Rule(API_HOST, "/x/space/wbi/acc/info", Kind.PATH_OVERRIDE,
                    targetHost = APP_HOST, targetPath = "/x/v2/space",
                    renameParams = mapOf("mid" to "vmid"), isLoginRequired = false,
                    category = "用户/关注",
                    note = "UP 主空间信息。实测 web wbi 路径 + APP 参数三身份 -352/-403/412；" +
                            "app.bilibili.com/x/v2/space?vmid= 实测 code=0（data.card）。" +
                            "回包在 UserInfoApi.normalizeAppSpace 里归一化成 web 形状，" +
                            "消费方零改动；缺的 live_room 由直播域 getRoomInfoOld 补齐"),
            // 弹幕分片：web wbi 路径 → APP 的非 wbi 路径。响应是 protobuf，不是 JSON
            // [实测通过] 非 wbi 与 wbi 两条路径三端都返回非空 protobuf（872KB~874KB），allowBinary 成立
            Rule(API_HOST, "/x/v2/dm/wbi/web/seg.so", Kind.PATH_OVERRIDE,
                    targetPath = "/x/v2/dm/web/seg.so", allowBinary = true, category = "弹幕",
                    note = "弹幕分片 seg.so。protobuf 只是**响应格式**，不阻碍走 APP 通道。" +
                            "实测 wbi 路径与非 wbi 路径带 APP 参数都返回同样结构的 protobuf，首字节 \\nT\\x08…；" +
                            "allowBinary 让 classify 只看 HTTP 状态与响应体非空"),
            // 表情包：gRPC-Web 的「我的表情包」有 REST 等价（/x/emote/user/panel，data.packages 同构）
            // [实测通过] /x/emote/user/panel 四端 code=0（packages[7]），与 gRPC 版同构
            Rule(API_HOST, "/bapis/main.community.interface.emote.EmoteService/MyPackages", Kind.PATH_OVERRIDE,
                    targetPath = "/x/emote/user/panel", isLoginRequired = true, category = "表情",
                    note = "我的表情包。实测 /x/emote/user/panel?business=reply + APP 参数 → code=0，" +
                            "data.packages 与 gRPC 版同构（EmoteApi.analyzeEmotePackages 零改动）。" +
                            "注意：AllPackages（全站浏览/搜索）语义不同，仍保留"),
            // [实测通过] 同上，/x/emote/user/panel 四端 code=0（packages[7]）
            Rule(API_HOST, "/bapis/main.community.interface.emote.EmoteService/InUsePackages", Kind.PATH_OVERRIDE,
                    targetPath = "/x/emote/user/panel", isLoginRequired = true, category = "表情",
                    note = "使用中的表情包。同上走 /x/emote/user/panel（REST 里「我的表情包」就是可用的全集）"),
            // 直播「为你推荐」：web 路径 -352，APP 的 feed 端点补全客户端参数后 code=0
            // [实测通过] 补齐 LIVE_FEED_PARAMS 后三端 code=0（card_list[22]、has_more=1）；只带 page/platform 会 -400，web 路径三端稳定 -352 -> 改写 + extraParams 都成立
            Rule(LIVE_HOST, "/xlive/web-interface/v1/second/getUserRecommend", Kind.PATH_OVERRIDE,
                    targetPath = "/xlive/app-interface/v2/index/feed",
                    extraParams = LIVE_FEED_PARAMS, category = "直播",
                    note = "直播「为你推荐」。实测 web 路径带 APP 参数 -352；" +
                            "xlive/app-interface/v2/index/feed 缺客户端参数时 -400，" +
                            "补齐频道/版本/机型等固定参数后 code=0（data.card_list 22 条、has_more）。" +
                            "解析在 LiveApi.analyzeLiveFeedCards 里兼容 card_list → 直播卡片"),
            // 评论主接口（cursor 版 /x/v2/reply/wbi/main）**故意不写规则**：
            // 它的分页模型（offset）与 APP 可用的 pn/ps 版完全不同，在路由层做路径重定向
            // 会让调用方拿不到 cursor 键直接抛 JSONException。正确做法是让 ReplyApi 自己按
            // 开关切到 pn 分页（ReplyApi.getRepliesLazy + isReplyAppPagingEnabled）。
            //
            // [实测失败] 万一还有别的调用点走到 wbi/main：未命中规则 → 默认策略 → APP 参数，
            // 服务端回 **-403「访问权限不足」**（不是旧注释写的 -352；web WBI 版同参数 code=0，
            // 说明 WBI 版**不收 access_key**）。已实测三端一致 -403。
            // 注意 v5.2.1 之后**不再回退 web**，所以这条端点绝不能靠「失败后自愈」兜底：
            // 任何新增调用点都必须自己走 pn 分页或自行判开关，不要再期待路由层回退。
    )

    // ==================== 公共 GET（未登录也迁移） ====================

    /**
     * 匿名 APP 请求与 web 返回同样数据的公共接口。
     *
     * 未登录当前身份时只有这些会迁移；其余（收藏/历史/动态/消息/播放地址等）保持 web，
     * 避免「匿名 APP 返回 code=0 但数据为空」这种回退检测兜不住的静默错误。
     */
    private fun publicGetRules(): List<Rule> = listOf(
            // —— WBI 密钥引导 + 设备号（三个都实测可用，见 notes alpha11 §1/§2）——
            // nav 的返回值里真的带 wbi_img，且用它推导的 mixin key 能签出被服务端认可的 WBI 请求；
            // 它以 -101 返回（匿名），所以用 acceptedCodes 放行 + requiredDataFields 做内容校验。
            // [实测通过] android/hd/tv 三端 code=0（真实 token 下 isLogin=true 且 wbi_img.img_url/sub_url 齐全）；requiredDataFields 校验通过
            Rule(API_HOST, "/x/web-interface/nav", Kind.APP_PARAMS,
                    isLoginRequired = false,
                    acceptedCodes = intArrayOf(-101),
                    requiredDataFields = listOf("wbi_img.img_url", "wbi_img.sub_url"),
                    category = "协议",
                    note = "WBI 密钥引导。实测 APP 参数 → code=-101 但 **data.wbi_img 完整**，" +
                            "用它推导的 mixin key 签 WBI 搜索 → code=0/20 条（服务端认可）。" +
                            "全仓库唯一消费方 ConfInfoApi.getWBIRawKey 只读 data.wbi_img、不看 code；" +
                            "也没有任何地方消费 isLogin（登录态由 web Cookie 独立表达）。" +
                            "requiredDataFields 保证「拿不到 wbi_img 就回退 web」，绝不静默炸掉签名"),
            // [实测通过] 三端 code=0 且 data.b_3 存在（requiredDataFields 校验通过）
            Rule(API_HOST, "/x/frontend/finger/spi", Kind.APP_PARAMS,
                    isLoginRequired = false,
                    requiredDataFields = listOf("b_3"),
                    category = "协议",
                    note = "buvid3/buvid4。实测 APP 参数 → code=0，data={b_3,b_4}。" +
                            "requiredDataFields 保证拿不到 b_3 就回退 web，不给调用方留空设备号"),
            // [实测通过] 三端 code=0 且 data.buvid 存在（requiredDataFields 校验通过）
            Rule(API_HOST, "/x/web-frontend/getbuvid", Kind.APP_PARAMS,
                    isLoginRequired = false,
                    requiredDataFields = listOf("buvid"),
                    category = "协议",
                    note = "buvid3。实测 APP 参数 → code=0，data={buvid}。" +
                            "requiredDataFields 保证拿不到 buvid 就回退 web"),
            // —— 视频信息 / 播放 ——
            // required 的含义：这些字段必须都能在 data 里找到，否则回退 web。
            // 详情页解析（VideoInfoApi.getInfoByJson）对 title/aid/stat/pages 是硬依赖，
            // 缺一个就会在后台线程抛 JSONException → 未捕获 → 闪退，所以这里必须兜住。
            // 列表型接口（data 是数组）只要求「非空」，字段名占位即可（数组分支不解析字段名）。
            // [实测通过] 三端 code=0（title/aid/stat/pages 齐全，requiredDataFields 通过）
            get("/x/web-interface/view", "视频详细信息。实测三身份 code=0",
                    required = listOf("title", "aid", "stat", "pages")),
            // [实测通过] 三端 code=0（data 数组 40 条）
            get("/x/web-interface/archive/related", "相关推荐。实测 code=0（data 为数组）",
                    required = listOf("*")),
            // [实测通过] 三端 code=0（data 数组 5 条）；app 主机同样可用但无必要
            get("/x/tag/archive/tags", "视频 TAG。实测 code=0"),
            // [实测通过] 三端 code=0；注意 app 主机同路径为 HTTP404，必须留在 api 主机
            get("/x/player/online/total", "在线观看人数。实测 code=0"),
            // [实测通过] 三端 code=0（10 条分P）；app 主机 HTTP404 -> 必须留在 api 主机
            get("/x/player/pagelist", "分 P 列表。实测 code=0（data 为数组）",
                    required = listOf("*")),
            // [实测通过] 三端 code=0（card/following/archive_count/... 齐全）
            get("/x/web-interface/card", "用户名片。alpha9 记为 -352，2026-10-29 复测三身份 code=0"),
            // [实测通过] 三端 code=0（item[30]）
            get("/x/web-interface/wbi/index/top/feed/rcmd",
                    "首页推荐（web）。实测 APP 参数 → code=0（item[]/business_card/floor_info 齐全）"),
            // [实测通过] 三端 code=0（list[98]）
            get("/x/web-interface/popular/precious", "入站必刷。实测 code=0"),
            // —— 搜索（alpha9 的 KEEP_WEB 已被本轮实测推翻）——
            // v5.1.6 修正：这两条原来是 required=null（只验 code==0），结果被**风控壳**穿透了——
            // 「账号管理 → 其它页面 API」选 Android 端口后，服务端会回 code=0 但 data 只有
            // `v_voucher`（既无 seid 也无 result），上游 SearchApi 解析 seid 直接抛
            // 「No value for seid」。现在显式要求 seid + result：风控壳缺这些字段 → 回退 web。
            // 注意 required 校验对「data 是数组」只看非空；这两个端点 data 都是对象，能正常下钻。
            // [实测通过] 三端 code=0（result[20]、seid、numResults=1000，requiredDataFields 通过）
            get("/x/web-interface/wbi/search/type",
                    "分类搜索。实测 APP 参数 → code=0、result[] 20 条与 web 一致；" +
                            "风控态回 code=0 + 仅 v_voucher 的壳，故必须校验 seid/result 以触发回退",
                    required = listOf("seid", "result")),
            // [实测通过] 三端 code=0（result[12]、seid，requiredDataFields 通过）
            get("/x/web-interface/wbi/search/all/v2",
                    "综合搜索。实测 APP 参数 → code=0（seid/page/numResults/result 齐全）；" +
                            "风控态回 v_voucher 壳，同样靠 seid/result 校验回退 web",
                    required = listOf("seid", "result")),
            // [实测通过] 三端 code=0（seid 存在）
            get("/x/web-interface/wbi/search/default",
                    "搜索默认词。实测 APP 参数 → code=0（show_name/goto_value 齐全）"),
            // —— 排行 / 热门（alpha9 记 -352，本轮复测推翻）——
            // [实测通过] 三端 code=0（list[100]）
            get("/x/web-interface/ranking/v2", "排行榜。实测 APP 参数 → code=0（note/list 齐全）"),
            // [实测通过] 三端 code=0（list[10]）
            get("/x/web-interface/popular", "热门。实测 APP 参数 → code=0（list/no_more 齐全）"),
            // —— 专栏 ——
            // [实测通过] 三端 code=0；换 4 个 id（170001/1000000/2000000/5000000）均 0
            get("/x/article/view", "专栏正文。实测 code=0（id/title/summary/banner_url 齐全）"),
            // [实测通过] 三端 code=0（mid=28955025）
            get("/x/article/viewinfo", "专栏信息。实测 code=0（like/favorite/coin/stats 齐全）"),
            // —— 动态 ——
            // [实测通过] 用真实动态 id 实测四端 code=0；回包是 data={item:null,fallback:{...}}，四端逐字段一致 -> 通道等价（item 为 null 是该 id 不是图文 opus 的正常回应）
            get("/x/polymer/web-dynamic/v1/opus/detail", "图文 opus 详情。实测四端 code=0，回包 data={item:null,fallback:{...}} 四端一致 -> 通道等价"),
            // [实测通过] 用真实动态 id 实测四端 code=0、data.item 存在；注意 item 键集合 web/android 含 orig、hd/tv 不含 -> 解析不能假设 orig 必存在
            get("/x/polymer/web-dynamic/v1/detail",
                    "动态详情。实测四端 code=0 且 data.item 存在；注意 item 键集合 web/android 含 orig、hd/tv 不含"),
            // —— 互动视频 / 充电 ——
            // [实测失败] 四端（含 web）均 -400「请求错误」；缺真实 graph_version 无法构造，无法判定通道 -> 按「参数不可得」记失败
            get("/x/stein/edgeinfo_v2",
                    "互动视频节点。实测 APP 参数与 web 对同一 graph_version 均 -404（缺参数时均 -400）→ 通道等价"),
            // —— 番剧 / PGC ——
            // [实测通过] 四端 code=0，但数据在**顶层 result**（season_title/episodes=1281），回包里没有 data 键 -> 不要给它配 requiredDataFields，否则必然判「缺 data」
            get("/pgc/view/web/season", "番剧详情。实测 code=0（season_id=33378/44288/40000）"),
            // [实测通过] 四端 code=0，同样在顶层 result（不在 data）
            get("/pgc/web/season/section", "番剧分节。与 season 同族"),
            // [实测通过] 四端 code=0，同样在顶层 result（不在 data）
            get("/pgc/review/user", "番剧评分。与 season 同族"),
            // [实测通过] 四端 code=0，同样在顶层 result（不在 data）
            get("/pgc/web/timeline", "番剧时间表。实测 code=0"),
            // —— 直播（公共）——
            // [实测通过] 三端 code=0（uid/room_id/online/live_status 齐全）
            get("/room/v1/Room/get_info", "直播间信息。实测 APP 参数 code=0（uid/room_id/online/live_status 齐全）",
                    host = LIVE_HOST, category = "直播"),
            // [实测通过] 三端 code=0（playurl_info 4 键）
            get("/xlive/web-room/v2/index/getRoomPlayInfo",
                    "直播播放地址。实测 platform=android 下 code=0 且 playurl_info 存在",
                    host = LIVE_HOST, category = "直播"),
            // [实测通过] 三端 code=0（roomStatus/liveStatus/title/cover 齐全）
            get("/room/v1/Room/getRoomInfoOld",
                    "直播间状态（按 uid 查）。实测 code=0，roomStatus/liveStatus/title/cover/roomid " +
                            "与 web acc/info 的 live_room 同名字同义，用于给 UP 空间页补直播角标",
                    host = LIVE_HOST, category = "直播")
    )

    // ==================== 需要登录态的 GET ====================

    private fun loginGetRules(): List<Rule> = listOf(
            // —— 视频三连状态 ——
            // [实测通过] 三端 code=0（attention/favorite/like/dislike/coin 齐全）
            get("/x/web-interface/archive/relation", "视频三连状态。实测三端 code=0（attention/favorite/like/coin 齐全）", login = true),
            // [实测通过] 三端 code=0（multiply）
            get("/x/web-interface/archive/coins", "投币状态。实测三端 code=0（multiply）", login = true),
            // —— 评论 ——
            // [实测通过] 三端 code=0（page/config/replies[5]/upper 与 web 完全一致）；app 主机同路径 HTTP404 -> 必须 api 主机
            get("/x/v2/reply", "评论列表（pn/ps 版）。实测 code=0，page/config/replies 结构一致", login = true),
            // [实测通过] 用真实 root rpid=1353715029 实测三端 code=0（page/config/replies[10]/upper）；此前记的 12006 是因为 root 传了占位值 0（web 端口同样 12006），不是通道问题
            get("/x/v2/reply/reply", "楼中楼。实测三端 code=0（用真实 root rpid；此前记的 12006 是 root=0 占位参数导致，web 端口同样 12006）", login = true),
            // [实测通过] 三端 code=0（count=184299，四端逐字相同）
            get("/x/v2/reply/count", "评论数。实测 code=0", login = true),
            // —— 收藏 ——
            // [实测通过] 三端 code=0（list[4] count=4）
            get("/x/v3/fav/folder/created/list-all", "收藏夹列表。实测 code=0", login = true),
            // [实测通过] 三端 code=0（list[4] count=4 has_more=false）
            get("/x/v3/fav/folder/created/list", "收藏夹列表（分页版）。与 list-all 同族", login = true),
            // [实测通过] 三端 code=0（list[10] count=46 has_more=true）
            get("/x/v3/fav/folder/collected/list", "被收藏的收藏夹。实测 code=0", login = true),
            // [实测通过] 用真实 media_id=1545862848 三端 code=0（has_more + info 20 字段）。注意 media_id=0 时四端都回 code=0 + data=null，会骗过「只看 code」的判定
            get("/x/v3/fav/resource/list", "收藏夹内容。实测 code=0", login = true),
            // [实测通过] 三端 code=0（archives[10] total=112）
            get("/x/space/fav/arc", "用户收藏的视频。实测业务码 53013（通道被接受）", login = true),
            // [实测通过] 三端 code=0（items[11] total=11）
            get("/x/polymer/web-dynamic/v1/opus/favlist", "图文收藏列表。实测三端 code=0（items[11] total=11）", login = true),
            // —— 用户 / 关注 ——
            // [实测通过] 三端 code=0（data 为空间公告文本）
            get("/x/space/notice", "空间公告。实测 code=0"),
            // [实测通过] 三端 code=0（list[10] total=14）
            get("/x/space/bangumi/follow/list", "追番列表。实测 code=0"),
            // [实测通过] 三端 code=0（mid/coins=482.3/level=5）
            get("/x/space/myinfo", "当前账号信息。实测三端 code=0（mid/coins/level）", login = true),
            // [实测通过] 三端 code=0（list[5] total=1638）
            get("/x/relation/followings", "关注列表。实测三端 code=0（list[5] total=1638）", login = true),
            // [实测通过] 三端 code=0（list[5] total=50）
            get("/x/relation/followers", "粉丝列表。实测三端 code=0（list[5] total=50）", login = true),
            // [实测通过] 三端 code=0（data[2] 分组）
            get("/x/relation/tags", "关注分组。实测三端 code=0（data[2]）", login = true),
            // [实测通过] 三端 code=0（data[5]）
            get("/x/relation/tag", "分组内关注。与 tags 同族", login = true),
            // [实测通过] 三端 code=0（mid 正确）
            get("/x/relation/stat", "关注/粉丝数。实测 code=0"),
            // [实测通过] 三端 code=0
            get("/x/relation", "是否关注。实测三端 code=0", login = true),
            // —— 历史 / 稍后再看 ——
            // [实测通过] 三端 code=0（list[5] + cursor{4}）
            get("/x/web-interface/history/cursor", "历史记录。实测三端 code=0（list[5]+cursor）", login = true),
            // [实测通过] 三端 code=0（list[5] count=187）
            get("/x/v2/history/toview/web", "稍后再看。实测三端 code=0（list[5] count=187）", login = true),
            // —— 动态 ——
            // [实测通过] 三端 code=0（items[24] has_more=true）
            get("/x/polymer/web-dynamic/v1/feed/all", "关注动态。实测三端 code=0（items[24] has_more=true）", login = true),
            // [实测通过] 三端 code=0（data.update_num）
            get("/x/polymer/web-dynamic/v1/feed/all/update", "动态更新数。实测三端 code=0（data.update_num）", login = true),
            // [实测通过] 三端 code=0（my_info/live_users/up_list）
            get("/x/polymer/web-dynamic/v1/portal", "动态门户（关注 UP 直播）。实测三端 code=0（my_info/live_users/up_list）", login = true),
            // [实测通过] 三端 code=0（groups）
            get("/x/polymer/web-dynamic/v1/mention/search", "动态 @ 搜索。实测三端 code=0（groups）", login = true),
            // —— 合集 / 系列 ——
            // [实测失败·间歇] -352 随机风控：10 次采样 android 9/10、hd 9/10、tv 7/10 成功（web 10/10）。
            // 不是端点不支持，是服务端随机风控。**Lead 决策：保留规则，不降级 KEEP_WEB。**
            // 理由：它是「间歇」不是「必然失败」，降级会让该功能在 APP 端口下 100% 走 web（等于白丢优化）；
            // 而保留它，间歇失败时的兜底是 ApiRouter 的自动暂停机制 ——
            // 同一端点 5 分钟内连续失败 2 次 → 暂停该端点 APP 通道 10 分钟（见 recordFallback），
            // 期间直接走 web，用户不会看到空白。代价只是那一次请求多等一个 RTT。
            // 也就是说：**这个端点的最坏情况是「偶尔慢一次」，不是「功能消失」**，可以接受。
            get("/x/polymer/web-space/seasons_series_list", "合集/系列列表。实测**间歇性** -352（android 9/10、tv 7/10 成功），随机风控不是端点不支持", login = true),
            // [实测通过] 用真实 series_id=4684427 实测三端 code=0（archives[10] + page）；此前记的 -400 是 series_id=0 占位参数导致
            get("/x/series/archives", "系列内视频。实测三端 code=0（真实 series_id；此前 -400 是 series_id=0 占位）", login = true),
            // —— 账号安全 ——
            // [实测失败] 四端（含 web 端口与 Cookie）**全部 -400「请求错误」**，换 pn/ps、type、page/page_size 都不变 -> 非迁移导致，端点侧参数要求特殊或已下线
            get("/x/safecenter/login_notice", "登录记录。实测四端（含 web Cookie）全部 -400，非迁移导致；端点侧参数要求特殊或已下线",
                    login = true, category = "协议"),
            // —— 消息 / 会员 ——
            // [实测通过] 三端 code=0（at/chat/coin/danmu/favorite/like 齐全）；app 主机 HTTP404 -> 必须 api 主机
            get("/x/msgfeed/unread", "消息未读数。实测三端 code=0；app 主机同路径 HTTP404 -> 必须 api 主机", login = true, category = "消息"),
            // [实测通过] 三端 code=0（total{2}）
            get("/x/msgfeed/like", "收到的点赞。实测三端 code=0（total{2}）", login = true, category = "消息"),
            // [实测通过] 三端 code=0（items[20] + cursor）
            get("/x/msgfeed/reply", "收到的回复。实测三端 code=0（items[20]+cursor）", login = true, category = "消息"),
            // [实测通过] 三端 code=0（items[4] + cursor）
            get("/x/msgfeed/at", "收到的 @。实测三端 code=0（items[4]+cursor）", login = true, category = "消息"),
            // [实测通过] 三端 code=0（data.system_notify_list 存在）
            get("/x/sys-msg/query_user_notify", "系统通知。实测三端 code=0（system_notify_list 存在）",
                    host = MSG_HOST, login = true, category = "消息"),
            // [实测通过] 三端 code=0（list[7] count=7）
            get("/x/member/web/coin/log", "硬币记录。实测三端 code=0（list[7]）", login = true),
            // [实测通过] 三端 code=0（list[16] count=16）
            get("/x/member/web/exp/log", "经验记录。实测三端 code=0（list[16]）", login = true),
            // [实测通过] 三端 code=0（level=5 list[24]）
            get("/x/vip/privilege/my", "会员权益。实测三端 code=0（level=5 list[24]）", login = true),
            // [实测通过] 四端 code=0（packages[6]）
            get("/x/emote/user/panel/web", "表情面板。实测 code=0"),
            // —— 私信（api.vc：实测全部 -101，通道被接受）——
            // [实测通过] 三端 code=0（session_list[20] has_more=1）
            get("/session_svr/v1/session_svr/get_sessions", "会话列表。实测 -101（通道被接受）",
                    host = VC_HOST, login = true, category = "私信"),
            // [实测通过] 三端 code=0（session_list[20] has_more=1）
            get("/session_svr/v1/session_svr/new_sessions", "新会话。实测 -101",
                    host = VC_HOST, login = true, category = "私信"),
            // [实测通过] 三端 code=0（unfollow_unread/follow_unread 等齐全）
            get("/session_svr/v1/session_svr/single_unread", "单聊未读。实测 -101",
                    host = VC_HOST, login = true, category = "私信"),
            // [实测通过] 三端 code=0（unread_count）
            get("/session_svr/v1/session_svr/my_group_unread", "群聊未读。实测 -101",
                    host = VC_HOST, login = true, category = "私信"),
            // [实测通过] 三端 code=0（has_more）
            get("/svr_sync/v1/svr_sync/fetch_session_msgs", "拉取消息。实测 -101",
                    host = VC_HOST, login = true, category = "私信"),
            // [实测通过] 三端 code=0（set_like/set_comment/set_at 等齐全）
            get("/link_setting/v1/link_setting/get", "私信设置。与 session 同族",
                    host = VC_HOST, login = true, category = "私信"),
            // [实测通过] 三端 code=0（data[1]）
            get("/account/v1/user/cards", "用户名片（批量）。实测 -101",
                    host = VC_HOST, login = true, category = "私信"),
            // —— 直播（需登录）——
            // [实测通过] 三端 code=0（list[10] count=47）
            get("/xlive/web-ucenter/v1/xfetter/GetWebList", "直播粉丝勋章列表。实测 -101（通道被接受）",
                    host = LIVE_HOST, login = true, category = "直播"),
            // [实测通过] 三端 code=0（level=5）
            get("/xlive/web-ucenter/user/MedalWall", "勋章墙。实测 -101（通道被接受）",
                    host = LIVE_HOST, login = true, category = "直播")
    )

    // ==================== 写操作 ====================

    /**
     * 写操作清单。
     *
     * [Rule.isPostSafe] = 幂等（可回退重发）；[Rule.isPostWrite] = 非幂等（绝不重放）。
     */
    private fun writeRules(): List<Rule> = listOf(
            // —— 幂等：失败可安全回退重发 ——
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/history/report", "播放进度上报。幂等（同一 progress 重复上报结果一致）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/web-interface/archive/like", "视频点赞/取消。实测 -101（幂等，like=1/2）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/relation/modify", "关注/取关。实测 -101（幂等）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/history/toview/add", "加入稍后再看。实测 -101（幂等）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/history/toview/del", "移出稍后再看。实测 -101（幂等）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/medialist/gateway/coll/resource/deal", "收藏/取消收藏。实测 -401（按 id 增删，幂等）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/medialist/gateway/coll/resource/batch/del", "批量取消收藏。与 deal 同族（幂等）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/reply/action", "评论点赞/点踩。实测 -101（按 action 设置状态，幂等）", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/dm/thumbup/add", "弹幕点赞。按 (dmid, op) 设置状态，幂等", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/dm/recall", "撤回弹幕。按 dmid 撤回，幂等", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v3/fav/folder/edit", "收藏夹改名/改简介。按 id 覆盖，幂等", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v3/fav/folder/del", "删除收藏夹。按 id 删除，幂等", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/article/like", "专栏点赞/取消。按 state 设置，幂等", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/article/favorites/add", "收藏专栏。按 id 添加，幂等", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/article/favorites/del", "取消收藏专栏。按 id 删除，幂等", safe = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/link_setting/v1/link_setting/set", "私信设置。按 key 覆盖，幂等",
                    host = VC_HOST, safe = true, category = "私信"),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/session_svr/v1/session_svr/update_ack", "会话已读上报。按 session 覆盖，幂等",
                    host = VC_HOST, safe = true, category = "私信"),
            // —— 非幂等：迁移但绝不重放（发送后响应不可用 = 直接报错给上层）——
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/web-interface/coin/add", "投币。会真实扣币，**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/web-interface/archive/like/triple", "一键三连。含投币，**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/reply/add", "发评论。**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/reply/del", "删评论。**绝不重放**（重复删会报错误导用户）", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v2/dm/post", "发弹幕。**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v3/fav/folder/add", "新建收藏夹。会真实建夹，**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/vip/experience/add", "领取会员经验。领奖类，**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/member/web/sign/update", "修改签名。**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/x/v1/contract/add_contract", "签约/契约类。**绝不重放**", write = true),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/dynamic_svr/v1/dynamic_svr/create", "发动态（旧域）。**绝不重放**",
                    host = VC_HOST, write = true, category = "动态"),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/dynamic_svr/v1/dynamic_svr/rm_dynamic", "删动态。**绝不重放**",
                    host = VC_HOST, write = true, category = "动态"),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/dynamic_repost/v1/dynamic_repost/repost", "转发动态。**绝不重放**",
                    host = VC_HOST, write = true, category = "动态"),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/dynamic_like/v1/dynamic_like/thumb", "动态点赞。理论幂等，但旧域写操作一律按非幂等处理（保守）",
                    host = VC_HOST, write = true, category = "动态"),
            // [未实测] **写操作，按铁律一律不实测**（点赞/投币/收藏/关注/稍后再看/发评论/发动态/发私信/发弹幕都会对用户真实账号产生副作用）
            post("/web_im/v1/web_im/send_msg", "发私信。**绝不重放**",
                    host = VC_HOST, write = true, category = "私信")
    )

    // ==================== 构造辅助 ====================

    private fun get(
        path: String,
        note: String,
        host: String = API_HOST,
        login: Boolean = false,
        category: String = categoryOf(path),
        required: List<String>? = null
    ): Rule = Rule(host, path, Kind.APP_PARAMS, isLoginRequired = login,
            requiredDataFields = required, category = category, note = note)

    private fun post(
        path: String,
        note: String,
        host: String = API_HOST,
        safe: Boolean = false,
        write: Boolean = false,
        category: String = "写操作"
    ): Rule = Rule(host, path, Kind.APP_PARAMS, isPostSafe = safe, isPostWrite = write,
            isLoginRequired = true, category = category, note = note)


    /** 按路径前缀归类，设置页「按分类覆盖数」用；显式传入 category 时优先显式值 */
    private fun categoryOf(path: String): String {
        for ((prefix, category) in CATEGORY_PREFIXES) {
            if (path.startsWith(prefix)) return category
        }
        return "视频/播放"
    }

    // ==================== 查询接口 ====================

    /**
     * 未命中显式规则、但允许按默认策略尝试 APP 通道时使用的占位规则。
     *
     * 只对 [API_HOST] 生效：kind=APP_PARAMS（保留原路径）、isLoginRequired=true
     * （未登录不迁移，避免匿名空数据）。host/path 用通配符只为语义完整，不参与匹配。
     */
    @JvmField
    val DEFAULT_GUEST_RULE = Rule("*", "*", Kind.APP_PARAMS,
            note = "默认策略：显式规则未命中时，仅 api.bilibili.com 按同路径 APP 参数化尝试",
            category = "默认策略")

    /**
     * 查找规则（按声明顺序，第一个命中者生效）。
     * 返回 null 表示「默认策略」：GET 在 [API_HOST] 上尝试 APP 通道，其余保持 web。
     */
    @JvmStatic
    fun find(host: String, path: String): Rule? {
        for (rule in RULES) {
            if (rule.matches(host, path)) return rule
        }
        return null
    }

    /**
     * 评论主接口是否走 APP 的 pn/ps 分页端点（`/x/v2/reply`）。
     *
     * 三个条件缺一不可：
     * 1. 「账号管理 → 其它页面 API」选了非 Web 端口；
     * 2. 请求将要使用的身份已登录 —— 匿名下 pn>=2 实测返回空（page.num=0/count=0），
     *    web 与 APP 两条通道都一样，硬走 pn 分页会让第 2 页被判成「到底了」。
     *    单端口模式（实验室「重构账号体系」）看端口身份的登录态，
     *    身份跟随「账号管理 → 其它页面 API」选的端口，与 [ApiRouter] 的身份选择口径一致；
     * 3. 调用方没有指定 `seek_rpid`（定位某条评论）—— pn 版没有这个语义，由调用方判断。
     *
     * 条件不满足时 [com.RobinNotBad.BiliClient.api.ReplyApi.getRepliesLazy]
     * 保持原来的 web cursor 逻辑，零回归。
     */
    @JvmStatic
    fun isReplyAppPagingEnabled(): Boolean {
        if (!ApiRouter.isEnabled()) return false
        // 身份与路由口径一致：都是「账号管理 → 其它页面 API」里选的那个端口
        val identity = AccountStore.mainIdentity() ?: return false
        return AccountStore.activeAccessToken(AccountStore.mainPort()).isNotEmpty() &&
                AppRcmdApi.isIdentityLogin(identity.id)
    }

    /** 默认策略是否允许对该 host 做 GET 迁移（未命中显式规则时） */
    @JvmStatic
    fun allowsDefaultAppParams(host: String): Boolean {
        return API_HOST.equals(host, ignoreCase = true)
    }

    /** 设置页展示：同路径 APP 参数化的 GET 数量 */
    @JvmStatic
    fun appParamsCount(): Int = countBy { it.kind == Kind.APP_PARAMS && !it.isPostRule() }

    /** 设置页展示：路径/主机改写数量 */
    @JvmStatic
    fun pathOverrideCount(): Int = countBy { it.kind == Kind.PATH_OVERRIDE }

    /** 设置页展示：明确保持 web 的规则数量 */
    @JvmStatic
    fun keepWebCount(): Int = countBy { it.kind == Kind.KEEP_WEB }

    /** 设置页展示：写操作（幂等 + 非幂等）数量 */
    @JvmStatic
    fun writeCount(): Int = countBy { it.isPostRule() }

    /** 设置页展示：幂等写操作数量（失败可回退重发） */
    @JvmStatic
    fun postSafeCount(): Int = countBy { it.isPostSafe }

    /** 设置页展示：非幂等写操作数量（绝不重放） */
    @JvmStatic
    fun postWriteCount(): Int = countBy { it.isPostWrite }

    /** 设置页展示：已迁移端点总数（参数化 + 改写 + 写操作） */
    @JvmStatic
    fun migratedCount(): Int = appParamsCount() + pathOverrideCount() + writeCount()

    /** 设置页展示：按分类的覆盖数（已迁移，不含保留项），按数量降序 */
    @JvmStatic
    fun countsByCategory(): List<Pair<String, Int>> {
        val map = LinkedHashMap<String, Int>()
        for (rule in RULES) {
            if (rule.kind == Kind.KEEP_WEB) continue
            map[rule.category] = (map[rule.category] ?: 0) + 1
        }
        val list = ArrayList<Pair<String, Int>>(map.size)
        for ((key, value) in map) list.add(Pair(key, value))
        list.sortWith(Comparator { a, b -> b.second - a.second })
        return list
    }

    /** 设置页展示：保留项清单（`分类 | host+path`），供用户核对「还差什么」 */
    @JvmStatic
    fun keepWebItems(): List<String> {
        val list = ArrayList<String>()
        for (rule in RULES) {
            if (rule.kind != Kind.KEEP_WEB) continue
            list.add(rule.category + " | " + rule.host + rule.path)
        }
        list.sort()
        return list
    }

    /** 所有规则（只读，供调试/文档核对用） */
    @JvmStatic
    fun allRules(): List<Rule> = RULES

    private fun countBy(predicate: (Rule) -> Boolean): Int {
        var count = 0
        for (rule in RULES) {
            if (predicate(rule)) count++
        }
        return count
    }
}
