package com.RobinNotBad.BiliClient.adapter

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.reply.ReplyInfoActivity
import com.RobinNotBad.BiliClient.activity.reply.WriteReplyActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton

import org.json.JSONException

import java.io.IOException
import java.util.ArrayList

//评论Adapter
//2023-07-22

@SuppressLint("ClickableViewAccessibility")
class ReplyAdapter(
        @JvmField val context: Context,
        @JvmField val replyList: ArrayList<Reply>?,
        @JvmField val oid: Long,
        @JvmField val root: Long,
        @JvmField val type: Int,
        @JvmField var sort: Int,
        @JvmField val up_mid: Long
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    @JvmField
    var isDetail: Boolean = false

    @JvmField
    var isManager: Boolean = false

    @JvmField
    var count: Long = -1

    @JvmField
    val replyType: Int = type

    @JvmField
    var listener: OnItemClickListener? = null

    fun setOnSortSwitchListener(listener: OnItemClickListener) {
        this.listener = listener
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            val view: View = LayoutInflater.from(this.context).inflate(R.layout.cell_reply_action, parent, false)
            return WriteReply(view)
        } else {
            val view: View = LayoutInflater.from(this.context).inflate(R.layout.cell_reply_list, parent, false)
            return ReplyHolder(view)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, @SuppressLint("RecyclerView") position: Int) {
        if (holder is WriteReply) {
            val writeReply = holder
            writeReply.write_reply.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(context, WriteReplyActivity::class.java)
                intent.putExtra("oid", oid)
                intent.putExtra("rpid", root)
                intent.putExtra("parent", root)
                intent.putExtra("parentSender", "")
                intent.putExtra("replyType", replyType)
                context.startActivity(intent)
            }
            val sorts = arrayOf("未知排序", "未知排序", "时间排序", "热度排序")
            if (isDetail) {
                writeReply.sort.setVisibility(View.GONE)
                writeReply.count_label.setVisibility(View.GONE)
            } else {
                writeReply.sort.setText(sorts[sort])
                writeReply.sort.setOnClickListener { view ->
                    if (this.listener != null)
                        listener!!.onItemClick(0)
                    writeReply.sort.setText(sorts[sort])
                }
                writeReply.count_label.setText("共" + count + "条评论")
            }
        }
        if (holder is ReplyHolder) {
            val replyHolder = holder
            var realPosition: Int
            if (isDetail) {
                realPosition = if (position != 0) position - 1 else 0
            } else {
                realPosition = position - 1
            }
            if (realPosition < 0 || realPosition >= replyList!!.size)
                return

            val reply: Reply? = replyList!!.get(realPosition)
            if (reply == null || reply.sender == null)
                return

            val sender: UserInfo = reply.sender!!

            if (GlideUtil.url(sender.avatar!!) != replyHolder.lastAvatarUrl) {
                replyHolder.lastAvatarUrl = GlideUtil.url(sender.avatar!!)
                Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(sender.avatar!!))
                        .transition(GlideUtil.getTransitionOptions())
                        .placeholder(R.mipmap.akari)
                        .apply(RequestOptions.circleCropTransform())
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(replyHolder.replyAvatar)
            }

            val name_str = SpannableStringBuilder()

            // 大会员红字
            if (!TextUtils.isEmpty(sender.vip_nickname_color)
                    && !SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NO_VIP_COLOR, false))
                replyHolder.userName.setTextColor(Color.parseColor(sender.vip_nickname_color))

            // up主标识
            if (sender.mid == up_mid) {
                name_str.append(" UP ")
                name_str.append(sender.name)
                name_str.setSpan(
                        RadiusBackgroundSpan(2, context.resources.getDimension(R.dimen.round_small).toInt(),
                                Color.WHITE, Color.rgb(207, 75, 95)),
                        0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                name_str.setSpan(RelativeSizeSpan(0.8f), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else
                name_str.append(sender.name)
            var last_length = name_str.length
            name_str.append(" ").append(sender.level.toString())
            if (sender.is_senior_member == 1)
                name_str.append("+")
            name_str.setSpan(StringUtil.getLevelBadge(context, sender), last_length + 1, name_str.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            // 等级
            if (!TextUtils.isEmpty(sender.medal_name)) {
                last_length = name_str.length
                name_str.append("  ").append(sender.medal_name).append("Lv").append(sender.medal_level.toString())
                        .append(" ")
                name_str.setSpan(
                        RadiusBackgroundSpan(2, context.resources.getDimension(R.dimen.round_small).toInt(),
                                Color.WHITE, Color.argb(140, 158, 186, 232)),
                        last_length + 1, name_str.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                name_str.setSpan(RelativeSizeSpan(0.8f), last_length + 1, name_str.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            replyHolder.userName.setText(name_str)

            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.REPLY_MARQUEE_NAME, true)) {
                replyHolder.userName.setSingleLine(true)
                replyHolder.userName.setMaxLines(1)
            } else {
                replyHolder.userName.setSingleLine(false)
                replyHolder.userName.setMaxLines(3)
            }

            replyHolder.message.setText(reply.message)
            StringUtil.setCopy(replyHolder.message)
            replyHolder.message.setOnTouchListener(StringUtil.ClickableSpanTouchListener())

            replyHolder.likeCount.setText(StringUtil.toWan(reply.likeCount.toLong()))

            if (reply.liked) {
                replyHolder.likeCount.setTextColor(Color.rgb(0xfe, 0x67, 0x9a))
                replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(context, R.drawable.icon_reply_like1), null, null, null)
            } else {
                replyHolder.likeCount.setTextColor(Color.rgb(0xff, 0xff, 0xff))
                replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                        ContextCompat.getDrawable(context, R.drawable.icon_reply_like0), null, null, null)
            }

            if (reply.childCount != 0 && !(realPosition == 0 && isDetail)) {
                replyHolder.childReplyCard.setVisibility(View.VISIBLE)

                if (reply.upReplied)
                    replyHolder.childCount.setText("UP主在内 共" + reply.childCount + "条回复")
                else
                    replyHolder.childCount.setText("共" + reply.childCount + "条回复")

                if (reply.childMsgList != null && replyHolder.childReplies != null) {
                    val childCount: Int = reply.childMsgList!!.size
                    val existingViewCount: Int = replyHolder.childReplies.getChildCount()

                    for (i in 0 until childCount) {
                        val child: Reply? = reply.childMsgList!!.get(i)
                        if (child == null || child.sender == null)
                            continue

                        val childMsg = SpannableStringBuilder()
                        if (child.sender!!.mid == up_mid) {
                            childMsg.append(" UP ")
                            childMsg.append(child.sender!!.name)
                            childMsg.setSpan(RadiusBackgroundSpan(2,
                                    context.resources.getDimension(R.dimen.round_small).toInt(), Color.WHITE,
                                    Color.rgb(207, 75, 95)), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            childMsg.setSpan(RelativeSizeSpan(0.8f), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else
                            childMsg.append(child.sender!!.name)

                        childMsg.append("：").append(child.message)

                        var textView: TextView?
                        if (i < existingViewCount) {
                            textView = replyHolder.childReplies.getChildAt(i) as TextView
                            textView!!.setVisibility(View.VISIBLE)
                        } else {
                            @SuppressLint("InflateParams")
                            val newTextView = LayoutInflater.from(context)
                                    .inflate(R.layout.cell_reply_child, null) as TextView
                            replyHolder.childReplies.addView(newTextView)
                            textView = newTextView
                        }
                        textView!!.setText(childMsg)
                    }

                    for (i in childCount until existingViewCount) {
                        replyHolder.childReplies.getChildAt(i).setVisibility(View.GONE)
                    }
                }
            } else
                replyHolder.childReplyCard.setVisibility(View.GONE)

            if (reply.upLiked)
                replyHolder.upLiked.setVisibility(View.VISIBLE)
            else
                replyHolder.upLiked.setVisibility(View.GONE)

            replyHolder.pubDate.setText(reply.pubTime)

            if (reply.pictureList != null && !reply.pictureList!!.isEmpty()) {
                replyHolder.imageCard.setVisibility(View.VISIBLE)
                replyHolder.imageCount.setVisibility(View.VISIBLE)

                val firstImageUrl: String = GlideUtil.url(reply.pictureList!!.get(0))
                if (firstImageUrl != replyHolder.lastImageUrl) {
                    replyHolder.lastImageUrl = firstImageUrl
                    Glide.with(BiliTerminal.context).asDrawable().load(firstImageUrl)
                            .transition(GlideUtil.getTransitionOptions())
                            .placeholder(R.mipmap.placeholder)
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(replyHolder.imageCard)
                }

                replyHolder.imageCount.setText("共" + reply.pictureList!!.size + "张图片")
                replyHolder.imageCard.setOnClickListener { view ->
                    val intent = Intent()
                    intent.setClass(context, ImageViewerActivity::class.java)
                    intent.putExtra("imageList", reply.pictureList)
                    context.startActivity(intent)
                }
            } else {
                replyHolder.imageCount.setVisibility(View.GONE)
                replyHolder.imageCard.setVisibility(View.GONE)
            }

            replyHolder.childReplyCard.setOnClickListener { view -> startReplyInfoActivity(reply) }
            if (!isDetail) {
                replyHolder.itemView.setOnClickListener { view -> startReplyInfoActivity(reply) }
                replyHolder.message.setOnClickListener { view -> startReplyInfoActivity(reply) }
            }

            replyHolder.replyAvatar.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(context, UserInfoActivity::class.java)
                intent.putExtra("mid", sender.mid)
                context.startActivity(intent)
            }

            replyHolder.likeCount.setOnClickListener { view ->
                CenterThreadPool.run {
                    if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
                        (context as Activity).runOnUiThread { MsgUtil.showMsg("还没有登录喵~") }
                        return@run
                    }
                    if (!reply.liked) {
                        try {
                            if (ReplyApi.likeReply(oid, reply.rpid, true) == 0) {
                                reply.liked = true
                                (context as Activity).runOnUiThread {
                                    MsgUtil.showMsg("点赞成功")
                                    replyHolder.likeCount.setText(StringUtil.toWan((++reply.likeCount).toLong()))
                                    replyHolder.likeCount.setTextColor(Color.rgb(0xfe, 0x67, 0x9a))
                                    replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like1), null, null,
                                            null)
                                }
                            } else
                                (context as Activity).runOnUiThread { MsgUtil.showMsg("点赞失败") }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                    } else {
                        try {
                            if (ReplyApi.likeReply(oid, reply.rpid, false) == 0) {
                                reply.liked = false
                                (context as Activity).runOnUiThread {
                                    MsgUtil.showMsg("取消成功")
                                    replyHolder.likeCount.setText(StringUtil.toWan((--reply.likeCount).toLong()))
                                    replyHolder.likeCount.setTextColor(Color.rgb(0xff, 0xff, 0xff))
                                    replyHolder.likeCount.setCompoundDrawablesWithIntrinsicBounds(
                                            ContextCompat.getDrawable(context, R.drawable.icon_reply_like0), null, null,
                                            null)
                                }
                            } else
                                (context as Activity).runOnUiThread { MsgUtil.showMsg("取消失败") }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            // 删除按钮
            if (isManager || sender.mid == SharedPreferencesUtil.getLong("mid", 0)) {
                val onDeleteClick = View.OnClickListener { view -> MsgUtil.showMsg("长按删除") }
                replyHolder.item_reply_delete.setOnClickListener(onDeleteClick)
                val onDeleteLongClick: View.OnLongClickListener = object : View.OnLongClickListener {
                    private var longClickPosition = -1
                    private var longClickTime: Long = -1

                    override fun onLongClick(view: View): Boolean {
                        val currentTime = System.currentTimeMillis()
                        if (longClickPosition == realPosition && currentTime - longClickTime < 6000) {
                            CenterThreadPool.run {
                                try {
                                    val result = ReplyApi.deleteReply(oid, reply.rpid, replyType)
                                    if (result == 0) {
                                        replyList!!.removeAt(realPosition)
                                        (context as Activity).runOnUiThread {
                                            notifyItemRemoved(position)
                                            notifyItemRangeChanged(position, replyList!!.size - position)
                                            longClickPosition = -1
                                            MsgUtil.showMsg("删除成功~")
                                            if (realPosition == 0 && isDetail) {
                                                (context as Activity).finish()
                                            }
                                        }
                                    } else {
                                        var msg = "操作失败：" + result
                                        when (result) {
                                            -404 -> msg = "没有这条评论！"
                                            -403 -> msg = "权限不足！"
                                        }
                                        val finalMsg = msg
                                        (context as Activity).runOnUiThread { MsgUtil.showMsg(finalMsg) }
                                    }
                                } catch (e: Exception) {
                                    (context as Activity).runOnUiThread { MsgUtil.err(e) }
                                }
                            }
                        } else {
                            longClickPosition = realPosition
                            longClickTime = currentTime
                            MsgUtil.showMsg("再次长按删除")
                        }
                        return true
                    }
                }
                replyHolder.item_reply_delete.setOnLongClickListener(onDeleteLongClick)
                replyHolder.item_reply_delete.setVisibility(View.VISIBLE)
            } else
                replyHolder.item_reply_delete.setVisibility(View.GONE)

            // 回复按钮
            replyHolder.replyBtn.setOnClickListener { view ->
                val noParent = isDetail && realPosition == 0
                val intent = Intent()
                intent.setClass(context, WriteReplyActivity::class.java)
                intent.putExtra("oid", oid)
                intent.putExtra("rpid", if (noParent) root else reply.rpid)
                intent.putExtra("parent", if (noParent) root else reply.rpid)
                intent.putExtra("replyType", replyType)
                intent.putExtra("pos", realPosition)
                if (root != 0L && !noParent)
                    intent.putExtra("parentSender", sender.name)
                else
                    intent.putExtra("parentSender", "")
                context.startActivity(intent)
            }
        }
    }

    override fun onViewRecycled(@NonNull holder: RecyclerView.ViewHolder) {
        if (holder is ReplyHolder) {
            val replyHolder = holder
            replyHolder.lastAvatarUrl = null
            replyHolder.lastImageUrl = null
        }
        super.onViewRecycled(holder)
    }

    fun startReplyInfoActivity(reply: Reply?) {
        if (reply == null)
            return
        val rpid = reply.rpid
        val oid = reply.oid
        val intent = Intent()
        intent.setClass(context, ReplyInfoActivity::class.java)
        intent.putExtra("rpid", rpid)
        intent.putExtra("oid", oid)
        intent.putExtra("type", replyType)
        intent.putExtra("up_mid", up_mid)
        intent.putExtra("is_manager", isManager)
        context.startActivity(intent)
    }

    override fun getItemCount(): Int {
        return if (replyList != null) replyList.size + 1 else 1
    }

    override fun getItemViewType(position: Int): Int {
        if (isDetail && position == 1) {
            return 0
        } else if (!isDetail && position == 0) {
            return 0
        }
        return 1
    }

    class ReplyHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val replyAvatar: ImageView = itemView.findViewById(R.id.replyAvatar)

        @JvmField
        val dislikeBtn: ImageView = itemView.findViewById(R.id.dislikeBtn)

        @JvmField
        val childReplies: LinearLayout = itemView.findViewById(R.id.repliesList)

        @JvmField
        val message: TextView = itemView.findViewById(R.id.replyText)

        @JvmField
        val userName: TextView = itemView.findViewById(R.id.replyUsername)

        @JvmField
        val pubDate: TextView = itemView.findViewById(R.id.replyPubDate)

        @JvmField
        val childCount: TextView = itemView.findViewById(R.id.repliesControl)

        @JvmField
        val likeCount: TextView = itemView.findViewById(R.id.likes)

        @JvmField
        val replyBtn: TextView = itemView.findViewById(R.id.replyBtn)

        @JvmField
        val upLiked: TextView = itemView.findViewById(R.id.upLiked)

        @JvmField
        val imageCount: TextView = itemView.findViewById(R.id.imageCount)

        @JvmField
        val item_reply_delete: TextView = itemView.findViewById(R.id.item_reply_delete)

        @JvmField
        val childReplyCard: LinearLayout = itemView.findViewById(R.id.repliesCard)

        @JvmField
        val imageCard: ImageView = itemView.findViewById(R.id.imageCard)

        @JvmField
        var lastAvatarUrl: String? = null

        @JvmField
        var lastImageUrl: String? = null
    }

    class WriteReply(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val write_reply: MaterialButton = itemView.findViewById(R.id.write_reply)

        @JvmField
        val sort: MaterialButton = itemView.findViewById(R.id.sort)

        @JvmField
        val count_label: TextView = itemView.findViewById(R.id.count_label)
    }
}
