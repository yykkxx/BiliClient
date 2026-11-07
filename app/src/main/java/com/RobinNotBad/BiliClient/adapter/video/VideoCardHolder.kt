package com.RobinNotBad.BiliClient.adapter.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.api.WatchLaterApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy

class VideoCardHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    @JvmField
    var title: TextView? = null
    @JvmField
    var upName: TextView? = null
    @JvmField
    var viewCount: TextView? = null
    @JvmField
    var cover: ImageView? = null
    private var lastCoverUrl: String? = null

    /** 当前绑定的视频卡片（长按稍后再看用） */
    private var boundCard: VideoCard? = null

    /**
     * 最近一次 bind 的位置（v5.0.2-alpha10）。
     * 点击/长按监听器已经提到 onCreateViewHolder 里只挂一次，靠这个 int 字段拿位置，
     * 语义与原来「在 bind 的闭包里捕获 position」完全一致，但省掉了每次 bind 的两次 lambda 分配。
     */
    @JvmField
    var boundPosition: Int = RecyclerView.NO_POSITION

    /** 该卡片是否已知在稍后再看列表里（避免每次长按都全量拉列表） */
    private var watchLaterKnown = false

    init {
        title = itemView.findViewById(R.id.text_title)
        upName = itemView.findViewById(R.id.text_upname)
        viewCount = itemView.findViewById(R.id.text_viewcount)
        cover = itemView.findViewById(R.id.img_cover)
    }

    @SuppressLint("SetTextI18n")
    fun showVideoCard(videoCard: VideoCard, context: Context) {
        boundCard = videoCard
        val str_upName = videoCard.upName
        if (str_upName == null || str_upName.isEmpty() || str_upName == "null") {
            upName!!.visibility = View.GONE
        } else upName!!.text = str_upName

        val str_viewCount = videoCard.view
        // "null" 字面量兜底（#5）：部分老数据源 getString(JSON null) 产生
        if (str_viewCount == null || str_viewCount.isEmpty() || str_viewCount == "null") {
            viewCount!!.visibility = View.GONE
        } else {
            viewCount!!.text = str_viewCount
        }

        try {
            val coverUrl = GlideUtil.url(videoCard.cover!!)
            if (coverUrl != lastCoverUrl) {
                lastCoverUrl = coverUrl
                Glide.with(BiliTerminal.context).asDrawable().load(coverUrl)
                        .transition(GlideUtil.getTransitionOptions())
                        .placeholder(R.mipmap.placeholder)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        // v5.0.2-alpha10：圆角 RequestOptions 改为进程内缓存复用
                        // （原来每次 bind 都 new RoundedCorners + new RequestOptions，
                        //  fling 时每秒几十次；参数与原来完全一致：圆角 5dp + 0.85 解码系数）
                        .apply(GlideUtil.roundOptions(5, 85))
                        .into(cover!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        when (videoCard.type) {
            "live" -> {
                val sstr_live = SpannableString("[直播]" + StringUtil.htmlToString(videoCard.title ?: ""))
                sstr_live.setSpan(ForegroundColorSpan(ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt())), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                title!!.text = sstr_live
            }
            "series" -> {
                val sstr_series = SpannableString("[系列]" + StringUtil.htmlToString(videoCard.title ?: ""))
                sstr_series.setSpan(ForegroundColorSpan(ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt())), 0, 4,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                title!!.text = sstr_series
            }
            else -> title!!.text = StringUtil.htmlToString(videoCard.title ?: "")
        }

    }
    /**
     * 长按切换「稍后再看」（#3/#6）。
     *
     * 所有视频列表（推荐/详情第三页/搜索/热门等）行为统一：
     * 长按一次加入稍后再看（Toast 提示），再次长按移出。
     * 已知状态缓存到 watchLaterKnown，只有第一次长按才全量拉列表判断，
     * 之后直接按记录的状态切换，避免每次长按都请求。
     *
     * # v5.3.0：不再静默失败
     * 旧代码在这里是 `if (card.aid <= 0) return` —— **什么都不做、也不提示**。
     * 而 web 推荐端口给出的卡片长期只有 bvid（aid=0），于是用户长按推荐页卡片
     * 「完全没反应」，看起来像手势没生效。现在分三条：
     * 1. aid>0：正常走 aid 路径；
     * 2. aid<=0 但 bvid 非空：用 bvid **反查 aid** 后再走（`WatchLaterApi.resolveAid`）；
     * 3. 两者都没有：明确 Toast 告诉用户这张卡不支持，而不是装作没听见。
     */
    fun toggleWatchLater() {
        val card = boundCard ?: return
        if (card.type != null && card.type != "video") {
            MsgUtil.showMsg("此类卡片不支持稍后再看")
            return
        }
        val bvid = card.bvid
        if (card.aid <= 0 && bvid.isNullOrBlank()) {
            // 直播/番剧推广这类卡片既没有 aid 也没有 bvid，如实说明
            MsgUtil.showMsg("这类卡片不支持稍后再看")
            return
        }

        val willAdd = !watchLaterKnown
        MsgUtil.showMsg(if (willAdd) "正在加入稍后再看…" else "正在移出稍后再看…")
        CenterThreadPool.run(Runnable { performWatchLaterToggle(willAdd, card.aid, bvid) })
    }

    /**
     * 后台线程实际执行「加入/移出稍后再看」。
     *
     * 从 [toggleWatchLater] 拆出来的唯一目的是**控制圈复杂度**（detekt CyclomaticComplexMethod
     * 阈值 15）：UI 入口负责「能不能做 + 提示」，本函数负责「做」和「结果怎么说」，
     * 两条职责本来就该分开，合在一起时任何一个新增分支都会顶破阈值。
     *
     * @param willAdd 期望的动作方向（true=加入）。注意它只是**期望**：
     *   如果查到该视频已在稍后再看列表里，方向会被改成「移出」（见下面的 inList 分支）。
     */
    private fun performWatchLaterToggle(willAdd: Boolean, rawAid: Long, bvid: String?) {
        try {
            // 先确保有 aid：web 推荐卡片可能只带 bvid（v5.3.0 的反查兜底）。
            // resolveAid 在 aid 已有时直接返回，不产生网络请求。
            val aid = WatchLaterApi.resolveAid(rawAid, bvid)
            if (aid <= 0L) {
                MsgUtil.showMsg("拿不到这个视频的 aid，稍后再看失败")
                return
            }
            if (!watchLaterKnown && isAlreadyInWatchLater(aid)) {
                // 已在列表里 → 这次长按是移出（方向与 willAdd 相反）
                reportResult(WatchLaterApi.delete(aid), false)
                return
            }
            reportResult(if (willAdd) WatchLaterApi.add(aid) else WatchLaterApi.delete(aid), willAdd)
        } catch (e: Exception) {
            e.printStackTrace()
            MsgUtil.showMsg("操作失败，请稍后再试")
        }
    }

    /**
     * 该视频是否已在稍后再看列表里（只在第一次长按时问一次）。
     *
     * 查询本身失败时返回 false 而不是抛出去：调用方据此当作「不在列表里」继续走加入流程，
     * 比整条长按直接失败更符合用户预期（多试一次加入是幂等的）。
     */
    private fun isAlreadyInWatchLater(aid: Long): Boolean {
        return try {
            WatchLaterApi.getWatchLaterList().any { it.aid == aid }
        } catch (e: Exception) {
            e.printStackTrace()
            MsgUtil.showMsg("操作失败，请稍后再试")
            false
        }
    }

    /** 按服务端返回码更新本地已知状态并提示用户 */
    private fun reportResult(code: Int, added: Boolean) {
        if (code != 0) {
            MsgUtil.showMsg("操作失败，错误码：" + code)
            return
        }
        watchLaterKnown = added
        MsgUtil.showMsg(if (added) "已加入稍后再看" else "已移出稍后再看")
    }
}
