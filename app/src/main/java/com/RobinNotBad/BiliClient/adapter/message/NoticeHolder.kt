package com.RobinNotBad.BiliClient.adapter.message

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.model.MessageCard
import com.RobinNotBad.BiliClient.model.Reply
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

import java.text.SimpleDateFormat

class NoticeHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val avaterList: LinearLayout = itemView.findViewById(R.id.avatar_list)
    val action: TextView = itemView.findViewById(R.id.action)
    val pubdate: TextView = itemView.findViewById(R.id.pubdate)
    val extraCard: ConstraintLayout = itemView.findViewById(R.id.extraCard)

    @SuppressLint("SetTextI18n")
    fun showMessage(message: MessageCard, context: Context) {
        avaterList.removeAllViews()
        if (message.user!!.isEmpty()) avaterList.setVisibility(View.GONE)
        else avaterList.setVisibility(View.VISIBLE)
        for (i in message.user!!.indices) {
            val imageView = ImageView(context)
            Glide.with(BiliTerminal.context)
                    .asDrawable()
                    .load(GlideUtil.url(message.user!![i]!!.avatar ?: ""))
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.akari)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .apply(RequestOptions.circleCropTransform())
                    .into(imageView)
            imageView.setLayoutParams(ViewGroup.LayoutParams(ToolsUtil.dp2px(32f), ToolsUtil.dp2px(32f)))
            imageView.left = ToolsUtil.dp2px(3f)
            val finalI = i
            imageView.setOnClickListener { view1 ->
                val intent = Intent()
                intent.setClass(context, UserInfoActivity::class.java)
                intent.putExtra("mid", message.user!![finalI]!!.mid)
                context.startActivity(intent)
            }
            avaterList.addView(imageView)

            //这个View什么都没有，用来当间隔的
            val view = View(context)
            view.setLayoutParams(ViewGroup.LayoutParams(ToolsUtil.dp2px(3f), ToolsUtil.dp2px(32f)))
            avaterList.addView(view)
        }

        if (message.timeStamp != 0L) {
            @SuppressLint("SimpleDateFormat") val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm")
            pubdate.setText(sdf.format(message.timeStamp * 1000))
        } else pubdate.setText(message.timeDesc)

        action.setText(message.content)
        StringUtil.setCopy(action)

        if (message.videoCard != null) {
            val childVideoCard = message.videoCard as VideoCard
            val holder = VideoCardHolder(View.inflate(context, R.layout.cell_dynamic_video, extraCard))
            holder.showVideoCard(childVideoCard, context)
            holder.itemView.findViewById<View>(R.id.videoCardView).setOnClickListener {
                TerminalContext.getInstance().enterVideoDetailPage(context, 0, childVideoCard.bvid)
            }
        }
        if (message.replyInfo != null || message.dynamicInfo != null) {
            val childReply = if (message.replyInfo != null) message.replyInfo else message.dynamicInfo
            val holder = ReplyCardHolder(View.inflate(context, R.layout.cell_message_reply, extraCard))
            holder.showReplyCard(childReply as Reply)
            holder.itemView.findViewById<View>(R.id.cardView).setOnClickListener {
                try {
                    if (message.itemType == "reply" || message.getType == MessageCard.GET_TYPE_REPLY) {
                        val seekReply = if (message.rootId == 0L) message.sourceId else message.rootId
                        when (message.businessId) {
                            ReplyApi.REPLY_TYPE_VIDEO_CHILD -> {
                                MsgUtil.showMsg("视频的子评论暂时无法定位，也许以后会做吧……")
                                //Todo 定位子评论 MessageApi:70 可拆分native_uri获得id
                                TerminalContext.getInstance().enterVideoDetailPage(context, 0, (childReply as Reply).ofBvid, null, seekReply)
                            }
                            ReplyApi.REPLY_TYPE_VIDEO -> TerminalContext.getInstance().enterVideoDetailPage(context, 0, (childReply as Reply).ofBvid, null, seekReply)
                            ReplyApi.REPLY_TYPE_DYNAMIC_CHILD -> MsgUtil.showMsg("动态的子评论暂时无法跳转，也许以后会做吧……")
                            ReplyApi.REPLY_TYPE_DYNAMIC -> TerminalContext.getInstance().enterDynamicDetailPage(context, message.subjectId, 0, seekReply)
                            ReplyApi.REPLY_TYPE_ARTICLE -> TerminalContext.getInstance().enterArticleDetailPage(context, message.subjectId, seekReply)
                            else -> MsgUtil.showMsg("不支持这个类型喵：" + message.businessId)
                        }
                    } else when (message.getType) {
                        MessageCard.GET_TYPE_LIKE, MessageCard.GET_TYPE_AT -> {
                            when (message.itemType) {
                                "video" -> TerminalContext.getInstance().enterVideoDetailPage(context, 0, (childReply as Reply).ofBvid)
                                "dynamic" -> TerminalContext.getInstance().enterDynamicDetailPage(context, message.subjectId)
                                "article" -> TerminalContext.getInstance().enterArticleDetailPage(context, message.subjectId)
                                else -> MsgUtil.showMsg("不支持这个类型喵：" + message.itemType)
                            }
                        }
                    }

                } catch (e: Exception) {
                    MsgUtil.err("跳转出错？", e)
                }
            }
        }
    }
}
