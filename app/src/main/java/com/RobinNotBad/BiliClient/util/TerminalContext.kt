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

import java.util.concurrent.Future

/**
 * @author silent碎月
 * @date 2024/11/03
 * 哔哩终端的中央上下文，所有不方便传的
 * 希望在任何地方都能拿得到，不想再额外建工具类的话
 * 扔这里就好，这里是屎山的集中地
 * 所有的工具类，也可以往这里扔一个实现
 */
class TerminalContext private constructor() {

    //要转发的东西的数据源
    //原 private 字段 + public getForwardContent()/setForwardContent() 由该属性的公开访问器提供
    var forwardContent: Any? = null
    /**
     * 详情页以及对应数据对象的存储， 每进入一个页面，例如动态，动态点击进入视频， 视频下面有个专栏
     * 然后再返回，此时的逻辑就是像栈一样。
     */
    private val contentLruCache: LruCache<String, Any>

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

    //专栏详情页跳转
    fun enterArticleDetailPage(context: Context, cvid: Long) {
        enterArticleDetailPage(context, cvid, -1)
    }

    fun enterArticleDetailPage(context: Context, cvid: Long, seekReply: Long) {
        val intent = Intent(context, OpusInfoActivity::class.java)
        intent.putExtra("id", cvid)
        intent.putExtra("seekReply", seekReply)
        context.startActivity(intent)
    }

    //文章详情页跳转
    fun enterOpusDetailPage(context: Context, id: Long) {
        enterOpusDetailPage(context, id, -1)
    }

    fun enterOpusDetailPage(context: Context, id: Long, seekReply: Long) {
        val intent = Intent(context, OpusInfoActivity::class.java)
        intent.putExtra("id", id)
        intent.putExtra("seekReply", seekReply)
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

    // 动态详情页跳转
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
        context.startActivity(intent)
    }

    /*
     * 由于动态有可删除的特性，部分页面依赖动态页面activity的result实现页面更新，这里加入额外的一个兼容方法
     */
    fun enterDynamicDetailPageForResult(activity: Activity, id: Long, position: Int, requestId: Int) {
        val intent = Intent(activity, OpusInfoActivity::class.java)
        intent.putExtra("id", id)
        intent.putExtra("position", position)
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
        context.startActivity(intent)
    }

    fun fetchLiveInfo(roomId: Long, saveToCache: Boolean): Result<LiveInfo> {
        val livePlayInfoFuture: Future<LivePlayInfo?> =
            CenterThreadPool.supplyAsyncWithFuture { LiveApi.getRoomPlayInfo(roomId, 80) }
        //利用future的特性让UserInfo在后面慢慢下着，同时开始下载LiveRoom，这里要等待LiveRoom下载完成。
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

    /**
     * 退出详情页的调用，所有启动详情页的Activity中需要再onDestroy的回调中调用该方法，释放自己的上下文对象
     */
    fun leaveDetailPage() {
    }

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
    private class InstanceHolder {
        companion object {
            @JvmField
            val INSTANCE = TerminalContext()
        }
    }

    companion object {
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
