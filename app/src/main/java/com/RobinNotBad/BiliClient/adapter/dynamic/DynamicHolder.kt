package com.RobinNotBad.BiliClient.adapter.dynamic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.dynamic.send.SendDynamicActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.adapter.article.ArticleCardHolder
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.model.LiveRoom
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

import java.io.IOException
import java.util.ArrayList

class DynamicHolder(@NonNull itemView: View, @JvmField val mActivity: BaseActivity, @JvmField val isChild: Boolean) :
    RecyclerView.ViewHolder(itemView) {

    @JvmField val username: TextView
    @JvmField val content: TextView
    @JvmField val title: TextView
    @JvmField var pubdate: TextView? = null
    @JvmField val avatar: ImageView
    @JvmField val extraCard: LinearLayout
    @JvmField var item_dynamic_share: TextView? = null
    @JvmField var likeCount: TextView? = null
    @JvmField var item_dynamic_delete: TextView? = null
    @JvmField var cell_dynamic_child: View? = null
    @JvmField val cell_dynamic_video: View
    @JvmField val cell_dynamic_image: View
    @JvmField val cell_dynamic_article: View
    @JvmField var relayDynamicLauncher: ActivityResultLauncher<Intent>? = null
    @JvmField var childDynamicHolder: DynamicHolder? = null
    private var videoCardHolder: VideoCardHolder? = null
    private var articleCardHolder: ArticleCardHolder? = null
    private var lastAvatarUrl: String? = null
    private var lastImageUrl: String? = null

    init {
        if (isChild) {
            username = itemView.findViewById(R.id.child_username)
            content = itemView.findViewById(R.id.child_content)
            avatar = itemView.findViewById(R.id.child_avatar)
            title = itemView.findViewById(R.id.child_title)
            extraCard = itemView.findViewById(R.id.child_extraCard)
            this.cell_dynamic_video = extraCard.findViewById(R.id.dynamic_video_child)
            this.cell_dynamic_article = extraCard.findViewById(R.id.dynamic_article_child)
            this.cell_dynamic_image = extraCard.findViewById(R.id.dynamic_image_child)
        } else {
            username = itemView.findViewById(R.id.username)
            pubdate = itemView.findViewById(R.id.pubdate)
            content = itemView.findViewById(R.id.content)
            avatar = itemView.findViewById(R.id.avatar)
            title = itemView.findViewById(R.id.title)
            extraCard = itemView.findViewById(R.id.extraCard)
            item_dynamic_share = itemView.findViewById(R.id.item_dynamic_share)
            likeCount = itemView.findViewById(R.id.likes)
            item_dynamic_delete = itemView.findViewById(R.id.item_dynamic_delete)
            relayDynamicLauncher = mActivity.relayDynamicLauncher
            this.cell_dynamic_child = extraCard.findViewById(R.id.dynamic_child)
            this.cell_dynamic_video = extraCard.findViewById(R.id.dynamic_video_extra)
            this.cell_dynamic_article = extraCard.findViewById(R.id.dynamic_article_extra)
            this.cell_dynamic_image = extraCard.findViewById(R.id.dynamic_image_extra)
        }
    }

    companion object {
        const val GO_TO_INFO_REQUEST: Int = 71

        @JvmStatic
        fun removeDynamicFromList(dynamicList: List<Dynamic>, finalPosition: Int,
                                  adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>) {
            removeDynamicFromList(dynamicList, finalPosition, adapter, false)
        }

        @JvmStatic
        fun removeDynamicFromList(dynamicList: List<Dynamic>, finalPosition: Int,
                                  adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>, showRecentUp: Boolean) {
            (dynamicList as MutableList).removeAt(finalPosition)
            val offset = if (showRecentUp) 2 else 1
            adapter.notifyItemRemoved(finalPosition + offset)
            adapter.notifyItemRangeChanged(finalPosition + offset, dynamicList.size - finalPosition)
        }

        @JvmStatic
        fun getDeleteListener(dynamicActivity: Activity, dynamicList: List<Dynamic>,
                              finalPosition: Int, adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>): View.OnLongClickListener {
            return getDeleteListener(dynamicActivity, dynamicList, finalPosition, adapter, false)
        }

        @JvmStatic
        fun getDeleteListener(dynamicActivity: Activity, dynamicList: List<Dynamic>,
                              finalPosition: Int, adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>,
                              showRecentUp: Boolean): View.OnLongClickListener {
            return object : View.OnLongClickListener {
                private var longClickPosition: Int = -1
                private var longClickTime: Long = -1

                override fun onLongClick(view: View): Boolean {
                    if (dynamicList[finalPosition].canDelete) {
                        val currentTime = System.currentTimeMillis()
                        if (longClickPosition == finalPosition && currentTime - longClickTime < 10000) {
                            CenterThreadPool.run(Runnable {
                                try {
                                    val result = DynamicApi.deleteDynamic(dynamicList[finalPosition].dynamicId)
                                    if (result == 0) {
                                        (dynamicList as MutableList).removeAt(finalPosition)
                                        dynamicActivity.runOnUiThread(Runnable {
                                            val offset = if (showRecentUp) 2 else 1
                                            adapter.notifyItemRemoved(finalPosition + offset)
                                            adapter.notifyItemRangeChanged(finalPosition + offset,
                                                    dynamicList.size - finalPosition)
                                            longClickPosition = -1
                                            MsgUtil.showMsg("删除成功~")
                                        })
                                    } else {
                                        var msg = "操作失败：$result"
                                        when (result) {
                                            500404 -> msg = "已经删除过了哦~"
                                            500406 -> msg = "不是自己的动态！"
                                        }
                                        val finalMsg = msg
                                        dynamicActivity.runOnUiThread(Runnable { MsgUtil.showMsg(finalMsg) })
                                    }
                                } catch (e: IOException) {
                                    dynamicActivity.runOnUiThread(Runnable { MsgUtil.err(e) })
                                }
                            })
                        } else {
                            longClickPosition = finalPosition
                            longClickTime = currentTime
                            MsgUtil.showMsg("再次长按删除")
                        }
                    }
                    return true
                }
            }
        }

        @JvmStatic
        fun getDeleteListener(dynamicActivity: Activity, dynamic: Dynamic): View.OnLongClickListener {
            return object : View.OnLongClickListener {
                private var longClickTime: Long = -1

                override fun onLongClick(view: View): Boolean {
                    if (dynamic.canDelete) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - longClickTime < 10000) {
                            CenterThreadPool.run(Runnable {
                                try {
                                    val result = DynamicApi.deleteDynamic(dynamic.dynamicId)
                                    if (result == 0) {
                                        dynamicActivity.runOnUiThread(Runnable {
                                            dynamicActivity.setResult(Activity.RESULT_OK,
                                                    if (dynamicActivity.intent.extras != null)
                                                        Intent()
                                                                .putExtras(dynamicActivity.intent.extras!!)
                                                    else
                                                        Intent())
                                            dynamicActivity.finish()
                                            MsgUtil.showMsg("删除成功~")
                                        })
                                    } else {
                                        var msg = "操作失败：$result"
                                        when (result) {
                                            500404 -> msg = "已经删除过了哦~"
                                            500406 -> msg = "不是自己的动态！"
                                        }
                                        val finalMsg = msg
                                        dynamicActivity.runOnUiThread(Runnable { MsgUtil.showMsg(finalMsg) })
                                    }
                                } catch (e: IOException) {
                                    dynamicActivity.runOnUiThread(Runnable { MsgUtil.err(e) })
                                }
                            })
                        } else {
                            longClickTime = currentTime
                            MsgUtil.showMsg("再次长按删除")
                        }
                    }
                    return true
                }
            }
        }
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    fun showDynamic(context: Context, dynamic: Dynamic, clickable: Boolean) { // 公用的显示函数 这样修改和调用都方便
        if (!TextUtils.isEmpty(dynamic.title)) {
            title.visibility = View.VISIBLE
            title.text = dynamic.title
        } else
            title.visibility = View.GONE

        val info = dynamic.userInfo
        username.text = info!!.name
        val nicknameColor = info.vip_nickname_color ?: ""
        if (nicknameColor.isNotEmpty()) {
            username.setTextColor(Color.parseColor(nicknameColor))
        } else {
            username.setTextColor(-0x1)
        }
        pubdate?.text = dynamic.pubTime
        if (dynamic.content != null && !TextUtils.isEmpty(dynamic.content)) {
            content.visibility = View.VISIBLE
            content.text = dynamic.content
            StringUtil.setCopy(content)
            content.setOnTouchListener(StringUtil.ClickableSpanTouchListener())
        } else
            content.visibility = View.GONE

        if (info.avatar != lastAvatarUrl) {
            lastAvatarUrl = info.avatar
            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(info.avatar ?: ""))
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.akari)
                    .apply(RequestOptions.circleCropTransform())
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(avatar)
        }

        avatar.setOnClickListener { view ->
            val intent = Intent()
            intent.setClass(context, UserInfoActivity::class.java)
            intent.putExtra("mid", info.mid)
            context.startActivity(intent)
        }

        var isPgc = false
        val allCards = ArrayList<View>()
        allCards.add(cell_dynamic_video)
        if (cell_dynamic_child != null) allCards.add(cell_dynamic_child!!)
        allCards.add(cell_dynamic_image)
        allCards.add(cell_dynamic_article)
        for (view1 in allCards) {
            view1.visibility = View.GONE
        }
        if (dynamic.major_type != null)
            when (dynamic.major_type) {
                "MAJOR_TYPE_PGC", "MAJOR_TYPE_ARCHIVE", "MAJOR_TYPE_UGC_SEASON" -> {
                    if ("MAJOR_TYPE_PGC" == dynamic.major_type) {
                        isPgc = true
                    }
                    val childVideoCard = dynamic.major_object as VideoCard
                    if (videoCardHolder == null) {
                        videoCardHolder = VideoCardHolder(cell_dynamic_video)
                    }
                    videoCardHolder!!.showVideoCard(childVideoCard, context)
                    val finalIsPgc = isPgc
                    cell_dynamic_video.setOnClickListener { view ->
                        TerminalContext.getInstance()
                                .enterVideoDetailPage(context, childVideoCard.aid, "", if (finalIsPgc) "media" else null)
                    }
                    cell_dynamic_video.visibility = View.VISIBLE
                }
                "MAJOR_TYPE_LIVE", "MAJOR_TYPE_LIVE_RCMD" -> {
                    val liveRoom = dynamic.major_object as LiveRoom
                    val childLiveCard = VideoCard()
                    childLiveCard.title = liveRoom.title
                    childLiveCard.cover = liveRoom.cover
                    childLiveCard.upName = liveRoom.uname
                    childLiveCard.view = ""
                    childLiveCard.type = "live"

                    if (videoCardHolder == null) {
                        videoCardHolder = VideoCardHolder(cell_dynamic_video)
                    }
                    videoCardHolder!!.showVideoCard(childLiveCard, context)
                    cell_dynamic_video.setOnClickListener(
                            { view -> TerminalContext.getInstance().enterLiveDetailPage(context, liveRoom.roomid) })
                    cell_dynamic_video.visibility = View.VISIBLE
                }
                "MAJOR_TYPE_ARTICLE" -> {
                    val articleCard = dynamic.major_object as ArticleCard
                    if (articleCardHolder == null) {
                        articleCardHolder = ArticleCardHolder(cell_dynamic_article)
                    }
                    articleCardHolder!!.showArticleCard(articleCard, context)
                    cell_dynamic_article.setOnClickListener(
                            { view -> TerminalContext.getInstance().enterArticleDetailPage(context, articleCard.id) })
                    cell_dynamic_article.visibility = View.VISIBLE
                }
                "MAJOR_TYPE_DRAW", "MAJOR_TYPE_OPUS" -> {
                    var pictureList: ArrayList<String>
                    if (dynamic.major_object is ArrayList<*>) {
                        pictureList = dynamic.major_object as ArrayList<String>
                    } else {
                        pictureList = ArrayList()
                    }

                    if (!pictureList.isEmpty()) {
                        val imageView = cell_dynamic_image.findViewById<ImageView>(R.id.imageView)
                        val firstImageUrl = pictureList[0]
                        if (firstImageUrl != lastImageUrl) {
                            lastImageUrl = firstImageUrl
                            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(firstImageUrl))
                                    .transition(GlideUtil.getTransitionOptions())
                                    .placeholder(R.mipmap.placeholder)
                                    .centerCrop()
                                    .format(DecodeFormat.PREFER_RGB_565)
                                    .sizeMultiplier(0.85f)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                                    .into(imageView)
                        }
                        val textView = cell_dynamic_image.findViewById<TextView>(R.id.imageCount)
                        textView.text = "共" + pictureList.size + "张图片"
                        imageView.setOnClickListener { view ->
                            val intent = Intent()
                            intent.setClass(context, ImageViewerActivity::class.java)
                            intent.putExtra("imageList", pictureList)
                            context.startActivity(intent)
                        }
                        cell_dynamic_image.visibility = View.VISIBLE
                    }
                }
            }

        if (dynamic.major_object == null && dynamic.dynamic_forward == null)
            extraCard.visibility = View.GONE // 这部分在adapter里
        else
            extraCard.visibility = View.VISIBLE

        if (clickable) {
            content.maxLines = 5
            if (dynamic.dynamicId != 0L) {
                (if (isChild) itemView.findViewById<View>(R.id.dynamic_child) else itemView).setOnClickListener { view ->
                    if (context is Activity) {
                        TerminalContext.getInstance().enterDynamicDetailPageForResult(context,
                                dynamic.dynamicId, adapterPosition, GO_TO_INFO_REQUEST)
                    } else {
                        TerminalContext.getInstance().enterDynamicDetailPage(context, dynamic.dynamicId,
                                adapterPosition)
                    }
                }
                content.setOnClickListener { view ->
                    val targetView = if (isChild) itemView.findViewById<View>(R.id.dynamic_child) else itemView
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                        targetView.callOnClick()
                    } else {
                        targetView.performClick()
                    }
                }
            }
        } else {
            content.maxLines = 999
        }
        content.ellipsize = TextUtils.TruncateAt.END

        val onRelayClick = View.OnClickListener { view ->
            val launcher = relayDynamicLauncher
            if (launcher == null) {
                return@OnClickListener
            }
            val intent = Intent()
            intent.setClass(mActivity, SendDynamicActivity::class.java)
            intent.putExtra("dynamicId", dynamic.dynamicId)
            TerminalContext.getInstance().forwardContent = dynamic
            launcher.launch(intent)
        }
        if (item_dynamic_share != null && clickable)
            item_dynamic_share!!.setOnClickListener(onRelayClick)

        val onDeleteClick = View.OnClickListener { MsgUtil.showMsg("长按删除") }
        if (item_dynamic_delete != null) {
            item_dynamic_delete!!.setOnClickListener(onDeleteClick)
            item_dynamic_delete!!.visibility = View.GONE
        }

        if (likeCount != null) {
            val stats = dynamic.stats
            if (stats != null) {
                if (stats.liked) { // 这里，还有下面，一定要加else！否则会导致错乱
                    likeCount!!.setTextColor(Color.rgb(0xfe, 0x67, 0x9a))
                    likeCount!!.setCompoundDrawablesWithIntrinsicBounds(
                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like1), null, null, null)
                } else {
                    likeCount!!.setTextColor(Color.rgb(0xff, 0xff, 0xff))
                    likeCount!!.setCompoundDrawablesWithIntrinsicBounds(
                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like0), null, null, null)
                }
                likeCount!!.text = StringUtil.toWan(stats.like.toLong())
            } else {
                likeCount!!.visibility = View.GONE
            }
            likeCount!!.setOnClickListener { view ->
                CenterThreadPool.run(Runnable {
                    if (!dynamic.stats!!.liked) {
                        try {
                            if (DynamicApi.likeDynamic(dynamic.dynamicId, true) == 0) {
                                dynamic.stats!!.liked = true
                                (context as Activity).runOnUiThread(Runnable {
                                    MsgUtil.showMsg("点赞成功")
                                    dynamic.stats!!.like = dynamic.stats!!.like + 1
                                    likeCount!!.text = StringUtil.toWan(dynamic.stats!!.like.toLong())
                                    likeCount!!.setTextColor(Color.rgb(0xfe, 0x67, 0x9a))
                                    likeCount!!.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like1), null, null,
                                            null)
                                })
                            } else
                                (context as Activity).runOnUiThread(Runnable { MsgUtil.showMsg("点赞失败") })
                        } catch (e: IOException) {
                            MsgUtil.err(e)
                        }
                    } else {
                        try {
                            if (DynamicApi.likeDynamic(dynamic.dynamicId, false) == 0) {
                                dynamic.stats!!.liked = false
                                (context as Activity).runOnUiThread(Runnable {
                                    MsgUtil.showMsg("取消成功")
                                    dynamic.stats!!.like = dynamic.stats!!.like - 1
                                    likeCount!!.text = StringUtil.toWan(dynamic.stats!!.like.toLong())
                                    likeCount!!.setTextColor(Color.rgb(0xff, 0xff, 0xff))
                                    likeCount!!.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like0), null, null,
                                            null)
                                })
                            } else
                                (context as Activity).runOnUiThread(Runnable { MsgUtil.showMsg("取消失败") })
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                })
            }
        }
    }
}
