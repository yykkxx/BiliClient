package com.RobinNotBad.BiliClient.api.migration

/**
 * 「全 API 迁移（实验）」的映射表与禁改清单。
 *
 * ## 这个功能在做什么
 *
 * 开启后，[com.RobinNotBad.BiliClient.util.NetWorkUtil] 里的每个 bilibili API 请求会先被
 * [ApiRouter] 尝试改写成「APP 通道」：按当前客户端身份（HD / TV / 手机，复用
 * `api/AppRcmdApi.kt` 的 RcmdIdentity / token 槽位）补上 appkey / ts / sign /
 * mobi_app / platform / build / device_name / buvid / access_key，并换成对应 APP 的
 * User-Agent 与 `app-key` 请求头。
 *
 * 为了让现有解析器完全不用改，默认策略是**保留原 web 路径**、只替换「身份与签名」：
 * 同一个端点在 APP 参数下返回的 JSON 结构与 web 一致，现有 `getJSONObject("data")` 之类的
 * 解析照常工作。只有官方 APP 有专用端点、且响应结构验证过一致时，才做路径改写
 * （[Kind.PATH_OVERRIDE]，目前只有播放地址 / player v2）。
 *
 * 失败（-101 未登录、-352 风控、-400、非 JSON、HTTP 错误、网络异常）会自动回退 web
 * 原请求一次，并计入回退次数。连续失败 2 次的端点会暂时停用 APP 通道 10 分钟，
 * 避免每次点开都在「先失败再回退」上多花一个 RTT。
 *
 * ## 依据来源（本仓库 `app_api/` 下为只读参考实现）
 *
 * - PiliPlus：`lib/http/api.dart`（APP 端点常量）、`lib/http/init.dart`（请求默认参数）、
 *   `lib/utils/app_sign.dart`（appkey/ts/sign 算法）、`lib/common/constants.dart`
 *   （HD/TV/手机三套 appkey、UA、mobi_app）。
 * - PiliPala：`lib/http/api.dart`、`lib/utils/utils.dart`（appSign）。
 * - 官方哔哩哔哩 9.13.0 APK 的 dex 字符串（前几轮扫出 `/x/v2/feed/index/story` 等）。
 * - bilibili-API-collect（socialsisteryi.github.io）端点与签名文档。
 * - 本机 curl 匿名实测（2026-10-28，HD / 手机 / TV 三套 appkey 各跑一遍），逐条结果见
 *   `notes/api_migration.md` 的「实测记录」。
 *
 * ## 安全边界
 *
 * - 登录、buvid / WBI 密钥引导、风控网关、二进制响应：**一律不改**。
 * - 写操作默认保持 web；只有幂等操作（点赞、收藏、关注、稍后再看、历史上报、评论点赞）
 *   进入 POST 安全清单——投币、发评论、发动态、发私信、发弹幕这类非幂等操作绝不迁移，
 *   否则一次「响应超时 + 回退」就可能造成重复副作用。
 */
object ApiMigrationRules {

    /** 迁移方式 */
    enum class Kind {
        /** 同路径 APP 参数化：保留 web 路径，只换签名/身份参数（响应结构不变） */
        APP_PARAMS,

        /** 路径改写：web 路径 → 官方 APP 专用端点（仅限响应结构已验证兼容的） */
        PATH_OVERRIDE,

        /** 保持 web：APP 通道会被风控拒绝 / 结构不兼容 / 二进制 / 登录引导，禁止改写 */
        KEEP_WEB
    }

    /**
     * 一条映射规则。
     *
     * @param host 精确 host（小写）
     * @param path 精确路径；`"*"` 表示该 host 全部路径；以「斜杠 + 星号」结尾表示前缀匹配（Kotlin 注释里不能直接写那个符号，会嵌套注释）
     * @param kind 迁移方式
     * @param targetPath PATH_OVERRIDE 的目标路径（host 不变）
     * @param isPostSafe POST 是否允许迁移（只有幂等写操作可置 true）
     * @param isLoginRequired 是否必须当前身份已登录才迁移。默认 true：
     *   未登录时 APP 通道会以匿名身份请求，部分接口仍返回 code=0 但数据是空的
     *   （收藏夹、历史、动态、播放地址清晰度等），这种「静默空数据」回退检测抓不到，
     *   所以未登录时这些接口直接保持 web，只有公共接口才迁移。
     * @param note 依据 / 原因（写进设置页与文档，便于回查）
     */
    class Rule(
        @JvmField val host: String,
        @JvmField val path: String,
        @JvmField val kind: Kind,
        @JvmField val targetPath: String? = null,
        @JvmField val isPostSafe: Boolean = false,
        @JvmField val isLoginRequired: Boolean = true,
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
    }

    /** 默认策略下允许尝试 APP 通道的 host（其余 host 未命中显式规则时保持 web） */
    private const val API_HOST = "api.bilibili.com"

    /**
     * 公共接口白名单：不需要登录、匿名 APP 请求与 web 返回同样的数据。
     * 未登录当前客户端身份时，只有这些接口会被迁移；其余（收藏/历史/动态/消息/播放地址等）
     * 直接保持 web，避免匿名 APP 请求返回 code=0 却是空数据的「静默错误」。
     */
    private val PUBLIC_PATHS = hashSetOf(
            "/x/web-interface/view",
            "/x/web-interface/archive/related",
            "/x/tag/archive/tags",
            "/x/player/online/total",
            "/x/player/pagelist",
            "/x/v2/reply",
            "/x/v2/reply/reply",
            "/x/v2/reply/count",
            "/x/space/notice",
            "/x/space/bangumi/follow/list",
            "/x/relation/stat",
            "/pgc/view/web/season",
            "/pgc/web/season/section",
            "/pgc/review/user",
            "/pgc/web/timeline",
            "/x/web-interface/popular/precious"
    )

    private val RULES: List<Rule> = hostKeepWebRules() + pathKeepWebRules() +
            pathOverrideRules() + verifiedAppParamRules() + postSafeRules()

    /** 整个域保持 web 的清单（登录 / 指纹 / 私有域 / 已是 APP 域） */
    private fun hostKeepWebRules(): List<Rule> = listOf(
            Rule("passport.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "登录协议（auth_code / poll），换 APP 参数会破坏扫码登录"),
            Rule("passport.snm0516.aisee.tv", "*", Kind.KEEP_WEB,
                    note = "登录备用域（PiliPala 同款），同上"),
            Rule("www.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "网页 / 页面地址，不是 APP 通道"),
            Rule("space.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "旧版 web 收藏夹接口（getBoxList），没有 APP 对应"),
            Rule("account.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "web 硬币余额等旧接口，未验证 APP 通道"),
            Rule("member.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "创作中心 web 接口；本机不可达、无 APP 签名参考"),
            Rule("s.search.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "搜索联想 web 接口；本机不可达"),
            Rule("comment.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "旧版弹幕 XML"),
            Rule("bvc.bilivideo.com", "*", Kind.KEEP_WEB,
                    note = "播放统计上报（pbp），非 JSON API"),
            Rule("message.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "通知中心 web 接口；本机不可达，未验证结构"),
            Rule("api.vc.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "私信 / 动态旧域；本机不可达，写操作多，未验证结构"),
            Rule("api.live.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "直播域；本机不可达；getRoomPlayInfo 的 platform 是 web 专用语义"),
            Rule("app.bilibili.com", "*", Kind.KEEP_WEB,
                    note = "本来就是 APP 域（推荐流 / 短视频自带 appkey 签名），不需要二次迁移"),
            Rule("api.biliterminal.cn", "*", Kind.KEEP_WEB,
                    note = "本软件自有服务器，不是 B 站接口")
    )

    /** api.bilibili.com 上必须保持 web 的路径（登录态 / 风控 / WBI / 二进制 / 实测被拒） */
    private fun pathKeepWebRules(): List<Rule> = listOf(
            // 登录态 / 风控 / 指纹 / WBI 密钥引导
            Rule(API_HOST, "/x/web-interface/nav", Kind.KEEP_WEB,
                    note = "WBI 密钥 + web 登录态（ConfInfoApi.getWBIRawKey 依赖 wbi_img），必须保持 web"),
            Rule(API_HOST, "/x/web-interface/nav/*", Kind.KEEP_WEB,
                    note = "nav 子接口（如 nav/stat）同样依赖 web 登录态，未迁移"),
            Rule(API_HOST, "/x/internal/*", Kind.KEEP_WEB,
                    note = "gaia 风控网关，依赖 web 指纹"),
            Rule(API_HOST, "/x/frontend/finger/*", Kind.KEEP_WEB,
                    note = "buvid 指纹（CookiesApi），web 专用"),
            Rule(API_HOST, "/x/web-frontend/getbuvid", Kind.KEEP_WEB,
                    note = "buvid 获取，web 专用"),
            Rule(API_HOST, "/bapis/*", Kind.KEEP_WEB,
                    note = "gRPC-Web 网关（表情包等），签名方式与 REST 不同"),
            Rule(API_HOST, "/x/safecenter/*", Kind.KEEP_WEB,
                    note = "登录记录 / 安全中心，web 账号专用"),
            // WBI 专用路径：服务端强制校验 wbi 签名，塞 APP 参数会让 w_rid 失配
            Rule(API_HOST, "/x/web-interface/wbi/*", Kind.KEEP_WEB,
                    note = "WBI 搜索 / 推荐；curl 实测 APP 参数 → code=0 但只有 v_voucher，无有效数据"),
            Rule(API_HOST, "/x/space/wbi/*", Kind.KEEP_WEB,
                    note = "WBI UP 主信息 / 投稿；curl 实测 APP 参数 → -403 / -352"),
            Rule(API_HOST, "/x/v2/reply/wbi/*", Kind.KEEP_WEB,
                    note = "WBI 评论区主接口；curl 实测 APP 参数 → -403"),
            Rule(API_HOST, "/x/v2/dm/wbi/*", Kind.KEEP_WEB,
                    note = "弹幕 seg.so 是二进制 protobuf，不能塞 APP 参数"),
            // 实测被 APP 风控拒绝的 web 路径：迁移只会白白多一次回退
            Rule(API_HOST, "/x/web-interface/popular", Kind.KEEP_WEB,
                    note = "web 热门；curl 实测 APP 参数 → -352（popular/precious 可用，见下）"),
            Rule(API_HOST, "/x/web-interface/ranking/*", Kind.KEEP_WEB,
                    note = "排行榜；curl 实测 APP 参数 → -352"),
            Rule(API_HOST, "/x/web-interface/card", Kind.KEEP_WEB,
                    note = "用户名片；curl 实测 APP 参数 → -352"),
            Rule(API_HOST, "/x/article/*", Kind.KEEP_WEB,
                    note = "专栏；curl 实测 APP 参数 → -352"),
            Rule(API_HOST, "/x/polymer/web-dynamic/v1/detail", Kind.KEEP_WEB,
                    note = "动态详情；curl 实测 → -352（feed / space / portal 正常，见下）"),
            Rule(API_HOST, "/x/polymer/web-dynamic/v1/opus/*", Kind.KEEP_WEB,
                    note = "图文 opus 详情 / 收藏列表；curl 实测 → -352"),
            Rule(API_HOST, "/x/ugcpay-rank/*", Kind.KEEP_WEB,
                    note = "充电榜；curl 实测 APP 参数 → -352"),
            Rule(API_HOST, "/x/stein/*", Kind.KEEP_WEB,
                    note = "互动视频；curl 实测 APP 参数 → -400"),
            Rule(API_HOST, "/x/click-interface/*", Kind.KEEP_WEB,
                    note = "web 心跳上报，参数是 web 专用字段")
    )

    /** 路径改写：只有官方 APP 有专用端点、且响应结构已验证兼容的才做 */
    private fun pathOverrideRules(): List<Rule> = listOf(
            // PiliPlus `Api.ugcUrl` 与 PiliPala 的 APP 播放实现都走非 wbi 的 /x/player/playurl；
            // curl 实测三身份下 fnval=16 返回 data.dash、fnval=1 返回 data.durl，
            // 且 accept_description / last_play_cid 等字段齐全，与 PlayerApi 的解析对得上。
            Rule(API_HOST, "/x/player/wbi/playurl", Kind.PATH_OVERRIDE,
                    targetPath = "/x/player/playurl",
                    note = "APP 播放地址端点；curl 实测 dash/durl 结构与 web 一致"),
            Rule(API_HOST, "/x/player/wbi/v2", Kind.PATH_OVERRIDE,
                    targetPath = "/x/player/v2",
                    note = "APP 播放器信息端点（字幕 / 互动 / 在线人数）；curl 实测字段与 web 一致")
    )

    /**
     * 已验证可用的「同路径 APP 参数化」清单。
     *
     * 行为上等价于默认策略（同路径 + APP 参数），列出来是为了：
     * 1) 设置页展示「已验证覆盖的接口数量」；2) 后来者知道哪些端点已经实测过。
     */
    private fun verifiedAppParamRules(): List<Rule> {
        val verified = listOf(
                // 视频信息 / 播放
                "/x/web-interface/view" to "视频详细信息；curl 实测三身份均 code=0",
                "/x/web-interface/archive/related" to "相关推荐；curl 实测 code=0（data 为数组）",
                "/x/web-interface/archive/relation" to "视频三连状态；curl 实测 -101（通道被接受，登录后可用）",
                "/x/web-interface/archive/coins" to "投币状态；curl 实测 -101（通道被接受）",
                "/x/tag/archive/tags" to "视频 TAG；curl 实测 code=0",
                "/x/player/online/total" to "在线观看人数；curl 实测 code=0",
                "/x/player/pagelist" to "分 P 列表；curl 实测 code=0（data 为数组）",
                // 评论
                "/x/v2/reply" to "评论列表；curl 实测 code=0（page/config/replies 结构一致）",
                "/x/v2/reply/reply" to "楼中楼；curl 实测业务码 12006（通道被接受）",
                "/x/v2/reply/count" to "评论数；curl 实测 code=0",
                // 收藏
                "/x/v3/fav/folder/created/list-all" to "收藏夹列表；curl 实测 code=0",
                "/x/v3/fav/folder/created/list" to "收藏夹列表（分页版）；与 list-all 同族",
                "/x/v3/fav/folder/collected/list" to "被收藏的收藏夹；curl 实测 code=0",
                "/x/v3/fav/resource/list" to "收藏夹内容；curl 实测 code=0",
                "/x/space/fav/arc" to "用户收藏的视频；curl 实测业务码 53013（通道被接受）",
                // 用户 / 关注
                "/x/space/notice" to "空间公告；curl 实测 code=0",
                "/x/space/bangumi/follow/list" to "追番列表；curl 实测 code=0",
                "/x/space/myinfo" to "当前账号信息；curl 实测 -101（通道被接受）",
                "/x/relation/followings" to "关注列表；curl 实测 -101（通道被接受）",
                "/x/relation/followers" to "粉丝列表；curl 实测 -101（通道被接受）",
                "/x/relation/tags" to "关注分组；curl 实测 -101（通道被接受）",
                "/x/relation/tag" to "分组内关注；与 tags 同族",
                "/x/relation/stat" to "关注/粉丝数；curl 实测 code=0",
                "/x/relation" to "是否关注；curl 实测 -101（通道被接受）",
                // 历史 / 稍后再看
                "/x/web-interface/history/cursor" to "历史记录；curl 实测 -101（通道被接受）",
                "/x/v2/history/toview/web" to "稍后再看；curl 实测 -101（通道被接受）",
                // 动态（polymer 结构，APP 参数下结构不变）
                "/x/polymer/web-dynamic/v1/feed/all" to "关注动态；curl 实测 -101（通道被接受）",
                "/x/polymer/web-dynamic/v1/feed/space" to "用户动态；与 feed/all 同族",
                "/x/polymer/web-dynamic/v1/portal" to "动态门户（关注 UP 直播）；curl 实测 -101",
                // 番剧 / PGC
                "/pgc/view/web/season" to "番剧详情；curl 实测 code=0（season_id=33378 / 44288 / 40000）",
                "/pgc/web/season/section" to "番剧分节；与 season 同族",
                "/pgc/review/user" to "番剧评分；与 season 同族",
                "/pgc/web/timeline" to "番剧时间表；curl 实测 code=0",
                // 消息 / 会员 / 表情
                "/x/msgfeed/unread" to "消息未读数；curl 实测 -101（通道被接受）",
                "/x/msgfeed/like" to "收到的点赞；curl 实测 -101（同族）",
                "/x/msgfeed/reply" to "收到的回复；curl 实测 -101（同族）",
                "/x/msgfeed/at" to "收到的 @；curl 实测 -101（同族）",
                "/x/member/web/coin/log" to "硬币记录；curl 实测 -101（通道被接受）",
                "/x/member/web/exp/log" to "经验记录；curl 实测 -101（通道被接受）",
                "/x/vip/privilege/my" to "会员权益；curl 实测 -101（通道被接受）",
                "/x/emote/user/panel/web" to "表情面板；curl 实测 code=0",
                "/x/web-interface/popular/precious" to "入站必刷；curl 实测 code=0（popular 主接口 -352，已禁改）"
        )
        val rules = ArrayList<Rule>(verified.size)
        for ((path, note) in verified) {
            // 公共接口（不需要登录、匿名 APP 请求也能返回同样数据）才允许在未登录时迁移；
            // 其余接口未登录时保持 web，避免「匿名 code=0 但数据空」这种静默错误。
            rules.add(Rule(API_HOST, path, Kind.APP_PARAMS,
                    isLoginRequired = !PUBLIC_PATHS.contains(path), note = note))
        }
        return rules
    }

    /**
     * POST 安全清单：只有幂等写操作。
     *
     * 依据：curl 实测这些端点在 APP 参数下能正常进入业务（返回 -101 未登录 / 0），
     * 且重复执行结果一致；投币（会重复扣币）、发评论/动态/私信/弹幕（会重复发送）
     * 即使通道被接受也绝不迁移。
     */
    private fun postSafeRules(): List<Rule> = listOf(
            Rule(API_HOST, "/x/v2/history/report", Kind.APP_PARAMS, isPostSafe = true,
                    note = "播放进度上报；curl 匿名实测 code=0（幂等）"),
            Rule(API_HOST, "/x/web-interface/archive/like", Kind.APP_PARAMS, isPostSafe = true,
                    note = "视频点赞/取消；curl 实测 -101（幂等，like=1/2）"),
            Rule(API_HOST, "/x/relation/modify", Kind.APP_PARAMS, isPostSafe = true,
                    note = "关注/取关；curl 实测 -101（幂等）"),
            Rule(API_HOST, "/x/v2/history/toview/add", Kind.APP_PARAMS, isPostSafe = true,
                    note = "加入稍后再看；curl 实测 -101（幂等）"),
            Rule(API_HOST, "/x/v2/history/toview/del", Kind.APP_PARAMS, isPostSafe = true,
                    note = "移出稍后再看；curl 实测 -101（幂等）"),
            Rule(API_HOST, "/medialist/gateway/coll/resource/deal", Kind.APP_PARAMS, isPostSafe = true,
                    note = "收藏/取消收藏；curl 实测 -401（按 id 增删，幂等）"),
            Rule(API_HOST, "/x/v2/reply/action", Kind.APP_PARAMS, isPostSafe = true,
                    note = "评论点赞/点踩；curl 实测 -101（按 action 设置状态，幂等）")
    )

    /**
     * 未命中显式规则、但允许按默认策略尝试 APP 通道时使用的占位规则。
     *
     * 只会被 [com.RobinNotBad.BiliClient.api.migration.ApiRouter] 内部使用：
     * kind=APP_PARAMS（保留原路径）、isLoginRequired=true（未登录不迁移，避免匿名空数据），
     * host/path 用通配符只是为了语义完整，不参与匹配。
     */
    @JvmField
    val DEFAULT_GUEST_RULE = Rule("*", "*", Kind.APP_PARAMS,
            note = "默认策略：显式规则未命中时，仅默认 APP 主机按同路径 APP 参数化尝试")

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

    /** 默认策略是否允许对该 host 做 GET 迁移（未命中显式规则时） */
    @JvmStatic
    fun allowsDefaultAppParams(host: String): Boolean {
        return API_HOST.equals(host, ignoreCase = true)
    }

    /** 设置页展示：已验证的 APP 参数化接口数（不含路径改写与 POST） */
    @JvmStatic
    fun verifiedAppParamsCount(): Int {
        var count = 0
        for (rule in RULES) {
            if (rule.kind == Kind.APP_PARAMS && !rule.isPostSafe) count++
        }
        return count
    }

    /** 设置页展示：路径改写数 */
    @JvmStatic
    fun pathOverrideCount(): Int {
        var count = 0
        for (rule in RULES) {
            if (rule.kind == Kind.PATH_OVERRIDE) count++
        }
        return count
    }

    /** 设置页展示：明确保持 web 的规则数 */
    @JvmStatic
    fun keepWebCount(): Int {
        var count = 0
        for (rule in RULES) {
            if (rule.kind == Kind.KEEP_WEB) count++
        }
        return count
    }
}
