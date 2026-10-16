package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.DmSegMobileReply
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.ProtobufParser
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 弹幕 api（Kotlin 重构版，原 DanmakuApi.java）
 *
 * 本次重构的核心目标之一：**修复网络波动导致的弹幕丢失**。
 *
 * 原实现的问题：
 * 1. getAllVideoDanmaku 对每一段只请求一次，catch 住异常后仅打印日志就 continue，
 *    该段弹幕**永久丢失**，且外层完全不知道少了哪一段；
 * 2. 遇到第一个空分段就 break，而“某段恰好没人发弹幕”是很常见的，
 *    这会导致该段之后的所有弹幕全部丢失；
 * 3. 单段解析失败（数据被截断）同样静默丢弃。
 *
 * 现在的策略：
 * - 每段独立重试（指数退避），区分“真·空段”与“请求失败”；
 * - 空段不再中断后续分段，按视频时长算出应有的分段数继续拉；
 * - 通过 onSegment 回调把每一段的成果**立即**交给播放器渲染，
 *   后面的分段失败也不影响已经到手的内容；
 * - 把失败的分段索引回传给上层，由播放器在后台继续补拉并增量追加，
 *   做到“网络波动只影响延迟，不影响最终完整性”。
 */
object DanmakuApi {

    /** 每段 6 分钟 */
    const val SEGMENT_SECONDS: Int = 360

    /** 单段最大重试次数（首次 + 重试） */
    private const val DEFAULT_MAX_ATTEMPTS = 4

    /** 分段拉取结果报告 */
    class SegmentFetchReport(
        /** 成功拿到的分段索引 -> 数据 */
        @JvmField val segments: MutableMap<Int, DmSegMobileReply> = LinkedHashMap(),
        /** 最终仍失败、需要后续补拉的分段索引 */
        @JvmField val missing: MutableList<Int> = ArrayList(),
        /** 本次计算出的分段总数 */
        @JvmField var totalSegments: Int = 0,
        /** 是否被取消 */
        @JvmField var cancelled: Boolean = false
    ) {
        val fetchedCount: Int get() = segments.size
        val danmakuCount: Int get() = segments.values.sumOf { it.elems.size }
        val isComplete: Boolean get() = missing.isEmpty()
    }

    /** 逐段回调：拿到一段就回调一次，便于边下边渲染 */
    fun interface SegmentCallback {
        fun onSegment(index: Int, reply: DmSegMobileReply)
    }

    // ------------------------------------------------------------------
    // 发送 / 点赞 / 撤回（签名与行为保持不变，@JvmStatic 兼容 Java 调用）
    // ------------------------------------------------------------------

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun sendVideoDanmakuByBvid(cid: Long, msg: String?, bvid: String?, progress: Long, color: Int, mode: Int): Int {
        val url = "https://api.bilibili.com/x/v2/dm/post"
        val arg = "type=1&oid=" + cid + "&msg=" + msg + "&bvid=" + bvid + "&progress=" + progress +
                "&color=" + color + "&mode=" + mode + "&rnd=" + (System.currentTimeMillis() * 1000000) +
                "&csrf=" + SharedPreferencesUtil.getString("csrf", "")
        val result = JSONObject(postAndReadBody(url, arg))
        Logu.i(result.toString())
        return result.getInt("code")
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun sendVideoDanmakuByAid(cid: Long, msg: String?, aid: Long, progress: Long, color: Int, mode: Int): Int {
        val url = "https://api.bilibili.com/x/v2/dm/post"
        val arg = "type=1&oid=" + cid + "&msg=" + msg + "&aid=" + aid + "&progress=" + progress +
                "&color=" + color + "&mode=" + mode + "&rnd=" + (System.currentTimeMillis() * 1000000) +
                "&csrf=" + SharedPreferencesUtil.getString("csrf", "")
        val result = JSONObject(postAndReadBody(url, arg))
        Logu.i(result.toString())
        return result.getInt("code")
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun likeDanmaku(dmid: Long, cid: Long, op: Int): Int {
        val url = "https://api.bilibili.com/x/v2/dm/thumbup/add"
        val arg = "oid=" + cid + "&dmid=" + dmid + "&op=" + op + "&platform=web_player" +
                "&csrf=" + SharedPreferencesUtil.getString("csrf", "")
        val result = JSONObject(postAndReadBody(url, arg))
        Logu.i(result.toString())
        return result.getInt("code")
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun recallDanmaku(dmid: Long, cid: Long): Int {
        val url = "https://api.bilibili.com/x/dm/recall"
        // 文档里就是cid，不是oid
        val arg = "cid=" + cid + "&dmid=" + dmid + "&csrf=" + SharedPreferencesUtil.getString("csrf", "")
        val result = JSONObject(postAndReadBody(url, arg))
        Logu.i(result.toString())
        return result.getInt("code")
    }

    /** POST 并读取响应体，响应体为空时抛出明确异常（而不是 NPE） */
    @Throws(IOException::class)
    private fun postAndReadBody(url: String, arg: String): String {
        val response = NetWorkUtil.post(url, arg, NetWorkUtil.webHeaders)
        val body = response.body ?: throw IOException("请求 $url 返回空响应体")
        return body.use { it.string() }
    }

    // ------------------------------------------------------------------
    // 弹幕拉取
    // ------------------------------------------------------------------

    /**
     * 获取视频弹幕（新版 API，protobuf 格式）的单个分段，不做重试。
     *
     * @param aid          稿件 avid
     * @param cid          视频 cid
     * @param segmentIndex 分包索引（6min 一包，从 1 开始）
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getVideoDanmakuSegment(aid: Long, cid: Long, segmentIndex: Int): DmSegMobileReply {
        val baseUrl = "https://api.bilibili.com/x/v2/dm/wbi/web/seg.so"
        var url = baseUrl + "?type=1" +
                "&oid=" + cid +
                "&pid=" + aid +
                "&segment_index=" + segmentIndex

        // 使用 WBI 签名（内部带缓存，网络异常时会抛出，交由调用方重试）
        url = ConfInfoApi.signWBI(url)
        Logu.d("新版弹幕API", "URL: $url")

        val response: Response = NetWorkUtil.get(url, NetWorkUtil.webHeaders)
        val data = response.body?.use { it.bytes() }
            ?: throw IOException("弹幕分段 $segmentIndex 返回空响应体")

        if (data.isEmpty()) {
            // 空 body 视为该段无弹幕，但**不是**错误
            Logu.d("新版弹幕API", "分段 $segmentIndex 返回空数据")
            return DmSegMobileReply()
        }

        val reply = ProtobufParser.parseDmSegMobileReply(data)
        Logu.d("新版弹幕API", "分段 $segmentIndex 获取到 " + reply.elems.size + " 条弹幕")
        return reply
    }

    /**
     * 带重试地拉取单个分段。全部重试失败返回 null（而不是抛异常），
     * 让上层可以把该段登记为“缺失”，稍后补拉。
     *
     * @param maxAttempts 最大尝试次数
     * @param cancelled   取消标志，播放器退出时置位可快速中止重试
     */
    @JvmStatic
    fun getVideoDanmakuSegmentWithRetry(
        aid: Long,
        cid: Long,
        segmentIndex: Int,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        cancelled: AtomicBoolean? = null
    ): DmSegMobileReply? {
        var attempt = 0
        while (attempt < maxAttempts) {
            if (cancelled != null && cancelled.get()) return null
            try {
                return getVideoDanmakuSegment(aid, cid, segmentIndex)
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) {
                    Logu.w("新版弹幕API", "分段 $segmentIndex 重试 $attempt 次后仍失败: " + e.message)
                    return null
                }
                // 指数退避：300ms, 600ms, 1200ms...，避免网络抖动时把请求打满
                val backoff = 300L shl (attempt - 1)
                Logu.d("新版弹幕API", "分段 $segmentIndex 第 $attempt 次失败，${backoff}ms 后重试")
                try {
                    Thread.sleep(backoff)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    /**
     * 根据视频时长计算应拉取的分段数（每段 6 分钟）。
     */
    @JvmStatic
    fun calcSegmentCount(maxDuration: Int): Int {
        if (maxDuration <= 0) return 1
        return (maxDuration + SEGMENT_SECONDS - 1) / SEGMENT_SECONDS
    }

    /**
     * 抗网络波动的分段拉取：逐段请求 + 重试，每拿到一段立即回调。
     *
     * 与旧实现的关键差异：**任何一段失败都不会中断整体流程**，
     * 失败的分段记录在 report.missing 里，交给上层稍后补拉。
     *
     * @param maxDuration 视频时长（秒），用于计算分段数
     * @param onSegment   每成功获取一段的回调（在调用线程执行）
     * @param cancelled   取消标志
     * @return 拉取报告（含成功分段与缺失分段）
     */
    @JvmStatic
    @JvmOverloads
    fun fetchAllSegments(
        aid: Long,
        cid: Long,
        maxDuration: Int,
        onSegment: SegmentCallback? = null,
        cancelled: AtomicBoolean? = null
    ): SegmentFetchReport {
        val report = SegmentFetchReport()
        val segmentCount = calcSegmentCount(maxDuration)
        report.totalSegments = segmentCount
        Logu.d("新版弹幕API", "视频时长: ${maxDuration}秒, 需要获取 $segmentCount 个分段")

        for (i in 1..segmentCount) {
            if (cancelled != null && cancelled.get()) {
                report.cancelled = true
                Logu.d("新版弹幕API", "分段拉取被取消")
                break
            }
            val reply = getVideoDanmakuSegmentWithRetry(aid, cid, i, DEFAULT_MAX_ATTEMPTS, cancelled)
            if (reply == null) {
                // 这一段网络失败了：登记为缺失，继续拉后面的（不要整段放弃）
                report.missing.add(i)
                Logu.w("新版弹幕API", "分段 $i 获取失败，已登记等待补拉")
                continue
            }
            report.segments[i] = reply
            onSegment?.onSegment(i, reply)
        }

        Logu.d(
            "新版弹幕API",
            "拉取结束：成功 ${report.fetchedCount}/$segmentCount 段，共 ${report.danmakuCount} 条弹幕" +
                    if (report.missing.isEmpty()) "" else "，缺失分段 ${report.missing}"
        )
        return report
    }

    /**
     * 兼容旧调用：获取视频所有弹幕（新版 API）。
     *
     * 保留原方法签名（Java 端可能仍在使用）。注意本方法只返回**成功**的分段；
     * 播放器内部已改用 fetchAllSegments 以获得缺失分段信息与边下边渲染能力。
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun getAllVideoDanmaku(aid: Long, cid: Long, maxDuration: Int): List<DmSegMobileReply> {
        val report = fetchAllSegments(aid, cid, maxDuration, null, null)
        return ArrayList(report.segments.values)
    }
}
