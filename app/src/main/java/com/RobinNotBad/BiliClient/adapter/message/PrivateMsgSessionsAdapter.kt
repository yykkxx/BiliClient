package com.RobinNotBad.BiliClient.adapter.message

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.message.PrivateMsgActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.model.PrivateMessage
import com.RobinNotBad.BiliClient.model.PrivateMsgSession
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

import org.json.JSONException

import java.util.ArrayList
import java.util.HashMap

class PrivateMsgSessionsAdapter(context: Context?, sessionsList: ArrayList<PrivateMsgSession>?, userMap: HashMap<Long, UserInfo>?) : RecyclerView.Adapter<PrivateMsgSessionsAdapter.PrivateMsgSessionsHolder>() {

    val context: Context? = context
    val sessionsList: ArrayList<PrivateMsgSession>? = sessionsList
    val userMap: HashMap<Long, UserInfo>? = userMap
    private val cardRoundRadius: Int
    companion object {
        private val BADGE_TEXT_COLOR = Color.WHITE
        private val BADGE_BG_COLOR get() = ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt())
        private const val BADGE_TEXT = "  未读 "
    }

    init {
        this.cardRoundRadius = (context!!.resources.getDimension(R.dimen.card_round)).toInt()
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): PrivateMsgSessionsHolder {
        val view = LayoutInflater.from(this.context).inflate(R.layout.cell_user_list, parent, false)
        return PrivateMsgSessionsHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: PrivateMsgSessionsHolder, position: Int) {
        if (position < 0 || position >= sessionsList!!.size) return
        val msgContent = sessionsList!![position] ?: return

        try {
            if (msgContent.content != null) when (msgContent.contentType) {
                PrivateMessage.TYPE_TEXT -> holder.contentText.setText(msgContent.content!!.getString("content"))
                PrivateMessage.TYPE_PIC -> holder.contentText.setText("[图片消息]")

                PrivateMessage.TYPE_VIDEO, PrivateMessage.TYPE_PIC_CARD, PrivateMessage.TYPE_NOMAL_CARD -> holder.contentText.setText(msgContent.content!!.getString("title"))

                PrivateMessage.TYPE_TEXT_WITH_VIDEO -> holder.contentText.setText(msgContent.content!!.getString("reply_content"))
                PrivateMessage.TYPE_RETRACT -> holder.contentText.setText("[撤回消息]")

                else -> holder.contentText.setText("")
            } else holder.contentText.setText("")

            holder.contentText.setEllipsize(TextUtils.TruncateAt.END)

            val user = if (userMap != null) userMap[msgContent.talkerUid] else null
            if (user != null) {
                if (msgContent.unread > 0 && SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.PRIVATE_MSG_UNREAD_BADGE_ENABLE, false)) {
                    val nameStr = SpannableStringBuilder(user.name)
                    val nameLength = user.name!!.length
                    nameStr.append(BADGE_TEXT)
                    nameStr.setSpan(
                            RadiusBackgroundSpan(1, cardRoundRadius, BADGE_TEXT_COLOR, BADGE_BG_COLOR),
                            nameLength + 1, nameStr.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    holder.nameText.setText(nameStr)
                } else {
                    holder.nameText.setText(user.name)
                }
                Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(user.avatar ?: ""))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .placeholder(R.mipmap.akari)
                        .apply(RequestOptions.circleCropTransform())
                        .into(holder.avatarView)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(context, PrivateMsgActivity::class.java)
                intent.putExtra("uid", msgContent.talkerUid)
                context!!.startActivity(intent)
            }
            holder.itemView.setOnLongClickListener {
                val intent = Intent(context, UserInfoActivity::class.java)
                intent.putExtra("mid", msgContent.talkerUid)
                context!!.startActivity(intent)
                true
            }
        } catch (err: JSONException) {
            Log.e("PrivateMsgUserAdapter", err.toString())
        }
    }

    override fun getItemCount(): Int {
        return sessionsList?.size ?: 0
    }

    class PrivateMsgSessionsHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarView: ImageView
        val nameText: TextView
        val contentText: TextView

        init {
            avatarView = itemView.findViewById(R.id.userAvatar)
            nameText = itemView.findViewById(R.id.userName)
            contentText = itemView.findViewById(R.id.userDesc)
        }

    }
}
