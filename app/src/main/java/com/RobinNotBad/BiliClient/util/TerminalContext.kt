package com.RobinNotBad.BiliClient.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.TextUtils

import androidx.annotation.Nullable
import androidx.collection.LruCache
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import com.RobinNotBad.BiliClient.activity.article.OpusInfoActivity
import com.RobinNotBad.BiliClient.activity.live.LiveInfoActivity
import com.RobinNotBad.BiliClient.activity.video.info.VideoInfoActivity
import com.RobinNotBad.BiliClient.api.ArticleApi
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.LiveApi
import com.RobinNotBad.BiliClient.api.OpusApi
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.api.VideoInfoApi
import com.RobinNotBad.BiliClient.model.ArticleInfo
import com.RobinNotBad.BiliClient.model.ContentType
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.model.LiveInfo
import com.RobinNotBad.BiliClient.model.LivePlayInfo
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.model.Opus
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.model.VideoInfo

import java.util.ArrayDeque
import java.util.concurrent.Future

/**
 * @author silent碎月
 * @date 2024/11/03
 *
 * 哔哩终端的中央上下文（进程内单例，见 [getInstance]）。
 *
 * 本类只有 4 项职责，且**不持有任何 Activity / Context 引用**，因此不会泄漏页面：
 * 1. 转发功能的数据源：[forwardContent]；
 * 2. 详情页跳转入口：enterXxxDetailPage(...) 拼 Intent 并启动对应详情页；
 * 3. 详情页数据缓存 [contentLruCache]（容量 10）：getXxx(...) 未命中时在后台线程 fetch，
 *    成功后写入（key 规则见各 fetchXxx/getXxx，格式为 `typeCode + "_" + id`）；
 * 4. 详情页缓存释放：enterXxxDetailPage 把该页数据实际使用的缓存 key 压栈，
 *    详情页 onDestroy 出栈并移除缓存（见 [leaveDetailPage] 与各 leaveXxxDetailPage）。
 *
 * 缓存写入时机：只在 getXxx(...) 未命中、后台 fetch 成功时写入；
 * 释放时机：只在详情页 onDestroy（列表页/详情页内部子页面不释放，由所属详情页负责）。
 * 线程模型：缓存是 androidx LruCache（内部已同步，可跨线程读写）；页面 key 栈由
 * [detailPageKeysLock] 保护，任意线程可 push/pop；所有日志仅用于排查，不影响主流程。
 *
 * 历史说明：本类早期是一组「什么都能往里塞」的静态上下文（旧注释自称「屎山集中地」），
 * Kotlin 化后已收敛为上述 4 项。新增能力请优先放进专门的工具类，不要无脑往这里堆。
 */
class TerminalContext private constructor() {

    //转发功能的数据源。Kotlin 属性本身已生成 getForwardContent()/setForwardContent() 访问器，
    //Java 侧调用方直接用这对访问器即可，不需要再手写方法。
    var forwardContent: Any? = null

    /**
     * 详情页数据对象缓存（LruCache，容量 10，满了按 LRU 淘汰）。
     *
     * - key 格式：`typeCode + "_" + id`，具体组合见各 fetchXxx/getXxx 方法；
     * - 写入：仅当 getXxx(...) 未命中、后台 fetch 成功且 saveToCache=true（见各 fetchXxx）；
     * - 释放：仅当详情页 onDestroy 调用 leaveXxxDetailPage(...) / [leaveDetailPage]（见「详情页缓存释放」段）。
     */
    private val contentLruCache: LruCache<String, Any>

    /**
     * 详情页缓存 key 栈（只存 key 字符串，不持有 Activity）。
     *
     * enterXxxDetailPage 进入详情页时压入该页数据实际使用的缓存 key；
     * 无法确定该删哪个 key 的详情页在 onDestroy 调用 [leaveDetailPage] 弹出最近一条并释放。
     * 由 [detailPageKeysLock] 保护，允许在任意线程 push / pop。
     */
    private val detailPageKeys = ArrayDeque<String>()
    private val detailPageKeysLock = Any()

    init {
        contentLruCache = LruCache<String, Any>(10)
    }

    // ------------------------转发功能数据源上下文 start-------------------------------
    //setForwardContent/getForwardContent 见上方 forwardContent 属性
    //-------------------------转发功能数据源上下文 end ----------------------------------

    // --------------------------详情页跳转功能 start  ----------------------------------
    // 视频详情页跳转
    fun enterVideoDetailPage(context: Context, aid: Long) {
        enterVideoDetailPage(context, aid, null, "video", -1)
    }

    fun enterVideoDetailPage(context: Context, bvid: String) {
        enterVideoDetailPage(context, -1, bvid, "video", -1)
    }

    fun enterVideoDetailPage(context: Context, aid: Long, bvid: String?) {
        enterVideoDetailPage(context, aid, bvid, null, -1)
    }

    fun enterVideoDetailPage(context: Context, aid: Long, bvid: String?, type: String?) {
        enterVideoDetailPage(context, aid, bvid, type, -1)
    }

    fun enterVideoDetailPage(context: Context, aid: Long, bvid: String?, type: String?, seekReply: Long) {
        //创建intent并填充信息
        val intent = Intent(context, VideoInfoActivity::class.java)
        intent.putExtra("aid", aid)
        if (!TextUtils.isEmpty(bvid)) {
            intent.putExtra("bvid", bvid!!)
        }
        if (type != null) {
            intent.putExtra("type", type)
        }
        intent.putExtra("seekReply", seekReply)
        //登记本页缓存 key。视频对象实际写入缓存时用的是 aid（见 fetchVideoInfoByAid/ByBvId），
        //但进入页面时可能只有 bvid，所以先按当前能拿到的信息登记；退出时
        //leaveVideoDetailPage(aid, bvid) 会把 aid / bvid 两种候选 key 都释放一遍。
        pushDetailPageKey(
            if (aid > 0) videoKey(aid)
            else if (!TextUtils.isEmpty(bvid)) videoBvKey(bvid.orEmpty())
            else ""
        )
        //启动activity
        context.startActivity(intent)
    }

    private fun fetchVideoInfoByAid(aid: Long, saveToCache: Boolean): Result<VideoInfo> {
        var videoInfo: VideoInfo?
        try {
            Logu.v("aid")
            videoInfo = VideoInfoApi.getVideoInfo(aid)
            if (videoInfo != null) {
                if (saveToCache) {
                    contentLruCache.put(ContentType.Video.getTypeCode().toString() + "_" + aid, videoInfo)
                }
                return Result.success(videoInfo)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return Result.failure(IllegalTerminalStateException("video object is null"))
    }

    private fun fetchVideoInfoByBvId(bvid: String?, saveToCache: Boolean): Result<VideoInfo> {
        var videoInfo: VideoInfo?
        try {
            Logu.v("bvid")
            videoInfo = VideoInfoApi.getVideoInfo(bvid!!)
            if (videoInfo != null) {
                if (saveToCache) {
                    contentLruCache.put(ContentType.Video.getTypeCode().toString() + "_" + videoInfo.aid, videoInfo)
                }
                return Result.success(videoInfo)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return Result.failure(IllegalTerminalStateException("video object is null"))
    }

    private fun fetchVideoInfoByAidOrBvId(aid: Long, bvid: String?, saveToCache: Boolean): Result<VideoInfo> {
        return if (aid > 0) {
            fetchVideoInfoByAid(aid, saveToCache)
        } else {
            fetchVideoInfoByBvId(bvid, saveToCache)
        }
    }

    //专栏详情页跳转（注意：当前实际启动的也是 OpusInfoActivity，页面数据按 Opus 码取/缓存）
    fun enterArticleDetailPage(context: Context, cvid: Long) {
        enterArticleDetailPage(context, cvid, -1)
    }

    fun enterArticleDetailPage(context: Context, cvid: Long, seekReply: Long) {
        val intent = Intent(context, OpusInfoActivity::class.java)
        intent.putExtra("id", cvid)
        intent.putExtra("seekReply", seekReply)
        //专栏入口落到 OpusInfoActivity，页面数据由 getOpusById/fetchOpus 取，
        //实际写入缓存的 key 是 Opus 码（`ContentType.Opus_<cvid>`），因此这里登记 Opus key；
        //退出时由 OpusInfoActivity 的 leaveDetailPage() 栈式释放。
        pushDetailPageKey(opusKey(cvid))
        context.startActivity(intent)
    }

    //图文（opus）/新式动态详情页跳转（与专栏入口共用 OpusInfoActivity，数据按 Opus 码缓存）
    fun enterOpusDetailPage(context: Context, id: Long) {
        enterOpusDetailPage(context, id, -1)
    }

    fun enterOpusDetailPage(context: Context, id: Long, seekReply: Long) {
        val intent = Intent(context, OpusInfoActivity::class.java)
        intent.putExtra("id", id)
        intent.putExtra("seekReply", seekReply)
        //与专栏入口同理：实际缓存 key 是 Opus 码
        pushDetailPageKey(opusKey(id))
        context.startActivity(intent)
    }

    private fun fetchArticleInfo(cvid: Long, saveToCache: Boolean): Result<ArticleInfo> {
        try {
            val article = ArticleApi.getArticle(cvid)
            if (article != null && saveToCache) {
                contentLruCache.put(ContentType.Article.getTypeCode().toString() + "_" + cvid, article)
            }
            return Result.success(article)
        } catch (t: Exception) {
            return Result.failure(t)
        }
    }

    // 动态详情页跳转（新式动态同样落在 OpusInfoActivity；老式动态会被该页重定向到 DynamicInfoActivity）
    fun enterDynamicDetailPage(context: Context, id: Long) {
        enterDynamicDetailPage(context, id, 0, -1)
    }

    fun enterDynamicDetailPage(context: Context, id: Long, position: Int) {
        enterDynamicDetailPage(context, id, position, -1)
    }

    fun enterDynamicDetailPage(context: Context, id: Long, position: Int, seekReply: Long) {
        val intent = Intent(context, OpusInfoActivity::class.java)
        intent.putExtra("position", position)
        intent.putExtra("id", id)
        intent.putExtra("seekReply", seekReply)
        //OpusInfoActivity 取数据走 getOpusById/fetchOpus，缓存 key 是 Opus 码；
        //若该动态是老式动态，OpusInfoActivity 会 finish 并重定向到 DynamicInfoActivity，
        //此时 OpusInfoActivity.onDestroy 弹出这里的登记，DynamicInfoActivity 再按
        //`Dynamic_<id>` 精确释放它自己的缓存（见 leaveDynamicDetailPage）。
        pushDetailPageKey(opusKey(id))
        context.startActivity(intent)
    }

    /*
     * 由于动态有可删除的特性，部分页面依赖动态页面activity的result实现页面更新，这里加入额外的一个兼容方法
     */
    fun enterDynamicDetailPageForResult(activity: Activity, id: Long, position: Int, requestId: Int) {
        val intent = Intent(activity, OpusInfoActivity::class.java)
        intent.putExtra("id", id)
        intent.putExtra("position", position)
        //与 enterDynamicDetailPage 一致：登记 Opus 码 key，由 OpusInfoActivity.onDestroy 栈式释放
        pushDetailPageKey(opusKey(id))
        activity.startActivityForResult(intent, requestId)
    }

    private fun fetchOpus(id: Long, saveToCache: Boolean): Result<Opus> {
        try {
            val opus = OpusApi.getOpus(id)
            if (saveToCache) {
                contentLruCache.put(ContentType.Opus.getTypeCode().toString() + "_" + id, opus)
            }
            return Result.success(opus)
        } catch (t: Exception) {
            return Result.failure(t)
        }
    }

    private fun fetchDynamic(id: Long, saveToCache: Boolean): Result<Dynamic> {
        try {
            val dynamic = DynamicApi.getDynamic(id)
            if (saveToCache) {
                contentLruCache.put(ContentType.Dynamic.getTypeCode().toString() + "_" + id, dynamic)
            }
            return Result.success(dynamic)
        } catch (t: Exception) {
            return Result.failure(t)
        }
    }

    /**
     * 进行一个直播详情页的启动
     *
     * @param context Android上下文对象
     * @param roomId  直播房间号
     */
    fun enterLiveDetailPage(context: Context, roomId: Long) {
        val intent = Intent(context, LiveInfoActivity::class.java)
        intent.putExtra("room_id", roomId)
        //登记本页缓存 key：LiveInfoActivity 走 getLiveInfoByRoomId/fetchLiveInfo，key 为 Live 码
        pushDetailPageKey(liveKey(roomId))
        context.startActivity(intent)
    }

    fun fetchLiveInfo(roomId: Long, saveToCache: Boolean): Result<LiveInfo> {
        val livePlayInfoFuture: Future<LivePlayInfo?> =
            CenterThreadPool.supplyAsyncWithFuture { LiveApi.getRoomPlayInfo(roomId, 80) }
        //先把最慢的直播流地址（LivePlayInfo）丢到线程池后台下，本线程同步下载 LiveRoom；
        //LiveRoom 拿到后再同步取 UserInfo，最后才 future.get() 等后台的 playInfo。
        try {
            val liveRoom: LiveRoom = LiveApi.getRoomInfo(roomId)
                ?: return Result.failure(IllegalTerminalStateException("liveRoom is null"))
            //LiveRoom下载完成后下UserInfo
            val userInfo = UserInfoApi.getUserInfo(liveRoom.uid)
            val playInfo = livePlayInfoFuture.get()
            val liveInfo = LiveInfo(userInfo, liveRoom, playInfo)
            if (saveToCache) {
                contentLruCache.put(ContentType.Live.getTypeCode().toString() + "_" + roomId, liveInfo)
            }
            return Result.success(liveInfo)
        } catch (t: Exception) {
            return Result.failure(t)
        }
    }
    // ---------------------------详情页跳转功能 end---------------------------------------

    // ---------------------------- 详情页缓存释放 start ----------------------------------
    /**
     * 退出详情页（栈式释放，无参版本）。
     *
     * 进入详情页时 [enterVideoDetailPage] / [enterArticleDetailPage] / [enterOpusDetailPage] /
     * [enterDynamicDetailPage] / [enterLiveDetailPage] 会把该页数据实际使用的缓存 key 压入内部栈；
     * 本方法弹出最近一条登记（后进先出），再从 [contentLruCache] 移除对应对象。
     *
     * 与旧注释的差别（重要）：旧注释写「所有启动详情页的 Activity 都要在 onDestroy 调用它」，
     * 而它此前是空实现、什么也没做。现在它会真的释放缓存，但**只在页面需要按栈顺序释放、
     * 又不知道自己该删哪个 key 时才调用**（当前是 OpusInfoActivity：专栏/图文/动态三个入口
     * 共用同一个页面）。知道自己 id 的页面请调用下面的 leaveXxxDetailPage(...)：
     * 那是按 key 精确释放，不依赖栈顶顺序，也不会误弹别人的登记。
     *
     * 行为约定：
     * - 空栈（页面不是经 enterXxxDetailPage 进入，或同一页面重复调用）只记一条 warn 日志，
     *   不抛异常、不崩溃；
     * - 弹出的 key 已不在缓存里（数据没下载成功 / 已被 LRU 淘汰）也算正常；
     * - 线程安全；弹出即移除该条登记，不会误删其它页面已登记的 key。
     */
    fun leaveDetailPage() {
        val key = popDetailPageKey()
        if (key.isEmpty()) {
            Logu.w("leaveDetailPage(): 详情页栈为空，本次不释放缓存（页面可能不是经 enterXxxDetailPage 进入的）")
            return
        }
        releaseCacheKey(key)
    }

    /**
     * 退出视频详情页（VideoInfoActivity.onDestroy 调用）。
     *
     * 视频对象实际写入缓存的 key 一定是 `Video_<aid>`（fetchVideoInfoByAid 与
     * fetchVideoInfoByBvId 都按 aid 存），但进入页面时可能只知道 bvid，
     * 所以这里把 aid / bvid 两种候选 key 都释放一遍（不存在的跳过，可重复调用），
     * 并同步把它们从 key 栈里摘掉——不依赖「弹出栈顶」，因此进入顺序异常时也不会误删别人的缓存。
     *
     * @param aid  视频 aid；未知时传 0 或负数
     * @param bvid 视频 bvid；未知时传 null 或空串
     */
    fun leaveVideoDetailPage(aid: Long, bvid: String?) {
        val keys = mutableListOf<String>()
        if (aid > 0) keys.add(videoKey(aid))
        if (!TextUtils.isEmpty(bvid)) {
            val bvKey = videoBvKey(bvid.orEmpty())
            if (!keys.contains(bvKey)) keys.add(bvKey)
        }
        if (keys.isEmpty()) {
            Logu.w("leaveVideoDetailPage(): aid 与 bvid 都不可用，无法定位视频缓存 key")
            return
        }
        for (key in keys) releaseDetailPageCache(key)
    }

    /**
     * 退出老式动态详情页（DynamicInfoActivity.onDestroy 调用）。
     * 该页面通过 [getDynamicById] 取数据，缓存 key 为 `Dynamic_<id>`（与 fetchDynamic 一致）。
     */
    fun leaveDynamicDetailPage(id: Long) {
        releaseDetailPageCache(dynamicKey(id))
    }

    /**
     * 退出专栏详情页。缓存 key 为 `Article_<cvid>`（与 [getArticleInfoByCvId]/fetchArticleInfo 一致）。
     *
     * 现状说明：当前专栏入口 [enterArticleDetailPage] 实际启动 OpusInfoActivity，页面数据按
     * Opus 码缓存，由 OpusInfoActivity 的 [leaveDetailPage] 释放；本方法供直接使用
     * [getArticleInfoByCvId] 的调用方（目前没有）或将来改回独立专栏页时使用，幂等。
     */
    fun leaveArticleDetailPage(cvid: Long) {
        releaseDetailPageCache(articleKey(cvid))
    }

    /**
     * 退出图文（opus）详情页。缓存 key 为 `Opus_<id>`（与 fetchOpus 一致）。
     * 与 [leaveDetailPage] 的区别：按 id 精确删除，不依赖栈顶顺序，可重复调用。
     */
    fun leaveOpusDetailPage(id: Long) {
        releaseDetailPageCache(opusKey(id))
    }

    /**
     * 退出直播详情页（LiveInfoActivity.onDestroy 调用）。
     * 缓存 key 为 `Live_<roomId>`（与 [getLiveInfoByRoomId]/fetchLiveInfo 一致），幂等。
     */
    fun leaveLiveDetailPage(roomId: Long) {
        releaseDetailPageCache(liveKey(roomId))
    }

    /**
     * 退出评论详情页（ReplyInfoActivity.onDestroy 调用）。
     * 缓存 key 为 `<contentType>_<contentId>_<replyId>`（与 [getReply]/fetchReply 一致），幂等。
     */
    fun leaveReplyDetailPage(contentType: ContentType, contentId: Long, replyId: Long) {
        releaseDetailPageCache(replyKey(contentType, contentId, replyId))
    }
    // ---------------------------- 详情页缓存释放 end ------------------------------------

    fun fetchReply(contentType: ContentType, contentId: Long, replyId: Long, saveToCache: Boolean): Result<Reply> {
        val replyResult = ReplyApi.getRootReply(contentType, contentId, replyId)
        if (replyResult.isSuccess() && saveToCache) {
            val reply = replyResult.getOrNull()
            if (reply != null) {
                contentLruCache.put(contentType.getTypeCode().toString() + "_" + contentId + "_" + replyId, reply)
            }
        }
        return replyResult
    }

    // ---------------------------- 数据源上下文 ----------------------------------------
    fun getVideoInfoByAidOrBvId(aid: Long, bvid: String?): LiveData<Result<VideoInfo>> {
        //注意（历史遗留，行为保持不变）：按 bvid 进入时这里读的是 `Video_<bvid>` 的 key，
        //而 fetchVideoInfoByBvId 写入的是 `Video_<aid>` 的 key，因此 bvid 入口几乎不会命中缓存。
        //这只影响命中率，不影响正确性；退出时 leaveVideoDetailPage 会把两个候选 key 都清掉。
        val key: String
        if (aid > 0) {
            key = ContentType.Video.getTypeCode().toString() + "_" + aid
        } else {
            key = ContentType.Video.getTypeCode().toString() + "_" + bvid
        }
        val obj = contentLruCache.get(key)
        if (obj !is VideoInfo) {
            return CenterThreadPool.supplyAsyncWithLiveData { fetchVideoInfoByAidOrBvId(aid, bvid, true).getOrThrow() }
        } else {
            return MutableLiveData<Result<VideoInfo>>(Result.success(obj))
        }
    }

    fun getArticleInfoByCvId(cvid: Long): LiveData<Result<ArticleInfo>> {
        val key = ContentType.Article.getTypeCode().toString() + "_" + cvid
        val obj = contentLruCache.get(key)
        if (obj !is ArticleInfo) {
            return CenterThreadPool.supplyAsyncWithLiveData { fetchArticleInfo(cvid, true).getOrThrow() }
        } else {
            return MutableLiveData<Result<ArticleInfo>>(Result.success(obj))
        }
    }

    fun getDynamicById(id: Long): LiveData<Result<Dynamic>> {
        val key = ContentType.Dynamic.getTypeCode().toString() + "_" + id
        val obj = contentLruCache.get(key)
        if (obj !is Dynamic) {
            return CenterThreadPool.supplyAsyncWithLiveData { fetchDynamic(id, true).getOrThrow() }
        } else {
            return MutableLiveData<Result<Dynamic>>(Result.success(obj))
        }
    }

    fun getOpusById(id: Long): LiveData<Result<Opus>> {
        //注意（历史遗留，行为保持不变）：这里读的是 Dynamic 码的 key，而 fetchOpus 写入的是
        //Opus 码的 key，所以该读取路径实际几乎不会命中、每次都会重新请求。
        //只影响命中率，不影响正确性；退出时由 leaveDetailPage/leaveOpusDetailPage 释放 Opus key。
        val key = ContentType.Dynamic.getTypeCode().toString() + "_" + id
        val obj = contentLruCache.get(key)
        if (obj !is Opus) {
            return CenterThreadPool.supplyAsyncWithLiveData { fetchOpus(id, true).getOrThrow() }
        } else {
            return MutableLiveData<Result<Opus>>(Result.success(obj))
        }
    }

    fun getLiveInfoByRoomId(roomId: Long): LiveData<Result<LiveInfo>> {
        val key = ContentType.Live.getTypeCode().toString() + "_" + roomId
        val obj = contentLruCache.get(key)
        if (obj !is LiveInfo) {
            return CenterThreadPool.supplyAsyncWithLiveData { fetchLiveInfo(roomId, true).getOrThrow() }
        } else {
            return MutableLiveData<Result<LiveInfo>>(Result.success(obj))
        }
    }

    fun getReply(contentType: ContentType, contentId: Long, replyId: Long): LiveData<Result<Reply>> {
        val key = contentType.getTypeCode().toString() + "_" + contentId + "_" + replyId
        val obj = contentLruCache.get(key)
        if (obj is Reply) {
            return MutableLiveData<Result<Reply>>(Result.success(obj))
        } else {
            return CenterThreadPool.supplyAsyncWithLiveData { fetchReply(contentType, contentId, replyId, true).getOrThrow() }
        }
    }


    fun getTerminalKey(item: Any?): String? {
        if (item is VideoInfo) {
            val videoInfo = item
            if (TextUtils.isEmpty(videoInfo.bvid)) {
                return ContentType.Video.getTypeCode().toString() + "_" + videoInfo.aid
            } else {
                return ContentType.Video.getTypeCode().toString() + "_" + videoInfo.bvid
            }
        } else if (item is ArticleInfo) {
            return ContentType.Article.getTypeCode().toString() + "_" + item.id
        } else if (item is Dynamic) {
            return ContentType.Dynamic.getTypeCode().toString() + "_" + item.dynamicId
        } else if (item is LiveInfo) {
            return ContentType.Live.getTypeCode().toString() + "_" + item.liveRoom!!.roomid
        } else if (item is Reply) {
            val reply = item
        }
        return null
    }
    // ------------------------- 数据源上下文 end ------------------------------------


    //----------------------------------私有函数区----------------------------------------
    /**
     * 缓存 key 构造。**必须与对应 fetchXxx/getXxx 里使用的字符串逐字一致**，
     * 否则会导致「页面退出删不掉缓存」或「删掉别的页面缓存」。统一格式：typeCode + "_" + id。
     */
    private fun videoKey(aid: Long): String =
        ContentType.Video.getTypeCode().toString() + "_" + aid

    private fun videoBvKey(bvid: String): String =
        ContentType.Video.getTypeCode().toString() + "_" + bvid

    private fun articleKey(cvid: Long): String =
        ContentType.Article.getTypeCode().toString() + "_" + cvid

    private fun opusKey(id: Long): String =
        ContentType.Opus.getTypeCode().toString() + "_" + id

    private fun dynamicKey(id: Long): String =
        ContentType.Dynamic.getTypeCode().toString() + "_" + id

    private fun liveKey(roomId: Long): String =
        ContentType.Live.getTypeCode().toString() + "_" + roomId

    private fun replyKey(contentType: ContentType, contentId: Long, replyId: Long): String =
        contentType.getTypeCode().toString() + "_" + contentId + "_" + replyId

    /** 压栈登记；空 key 直接忽略（该页不写缓存时无需登记）。超过上限时丢最旧记录兜底，避免异常场景下无限增长。 */
    private fun pushDetailPageKey(key: String) {
        if (key.isEmpty()) return
        synchronized(detailPageKeysLock) {
            detailPageKeys.addLast(key)
            while (detailPageKeys.size > MAX_DETAIL_PAGE_STACK_SIZE) {
                Logu.w("详情页 key 栈超过 $MAX_DETAIL_PAGE_STACK_SIZE 层，丢弃最旧登记: " + detailPageKeys.removeFirst())
            }
        }
    }

    /** 弹出最近一条登记；空栈返回空串（调用方据此只记日志）。 */
    private fun popDetailPageKey(): String = synchronized(detailPageKeysLock) {
        if (detailPageKeys.isEmpty()) "" else detailPageKeys.removeLast()
    }

    /** 从栈里移除指定 key（若存在多条相同 key，只移除最近压入的一条）；返回是否移除成功。 */
    private fun removeDetailPageKey(key: String): Boolean = synchronized(detailPageKeysLock) {
        detailPageKeys.removeLastOccurrence(key)
    }

    /** 精确释放：移除缓存中的 key（幂等：不存在也算正常），并同步从 key 栈里摘掉该登记。 */
    private fun releaseDetailPageCache(key: String) {
        releaseCacheKey(key)
        if (!removeDetailPageKey(key)) {
            Logu.v("该 key 未在详情页栈中登记（页面可能经非 enterXxxDetailPage 入口进入）: " + key)
        }
    }

    /** 真正的缓存移除；key 不存在时只记日志，不报错。 */
    private fun releaseCacheKey(key: String) {
        val removed: Any? = contentLruCache.remove(key)
        if (removed != null) Logu.d("详情页缓存已释放: " + key)
        else Logu.v("详情页缓存本就不存在，跳过: " + key)
    }

    private class InstanceHolder {
        companion object {
            @JvmField
            val INSTANCE = TerminalContext()
        }
    }

    companion object {
        /** 详情页 key 栈的兜底上限（正常嵌套深度是个位数，超过说明有页面漏了 onDestroy 调用） */
        private const val MAX_DETAIL_PAGE_STACK_SIZE = 32

        @JvmStatic
        fun getInstance(): TerminalContext {
            return InstanceHolder.INSTANCE
        }
    }

    class IllegalTerminalStateException : Exception {
        private val description: String

        constructor() {
            description = ""
        }

        constructor(description: String) {
            this.description = description
        }

        @get:Nullable
        override val message: String?
            get() = this.description

        @Nullable
        override fun getLocalizedMessage(): String? {
            return this.description
        }
    }
}
