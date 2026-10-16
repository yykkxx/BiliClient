package com.RobinNotBad.BiliClient.adapter.message

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.CopyTextActivity
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.api.PrivateMsgApi
import com.RobinNotBad.BiliClient.model.PrivateMessage
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import com.google.android.material.card.MaterialCardView

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList

class PrivateMsgAdapter(msgList: MutableList<PrivateMessage>?, emoteArray: JSONArray?, context: Context) : RecyclerView.Adapter<PrivateMsgAdapter.ViewHolder>() {
    private val mPrivateMsgList: MutableList<PrivateMessage>? = msgList
    private var selfUid: Long = -1
    private val emoteArray: JSONArray? = emoteArray
    private val context: Context = context

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTv: TextView
        val textContentTv: TextView
        val tipTv: TextView
        val playTimesTv: TextView
        val upNameTv: TextView
        val videoTitleTv: TextView
        val textContentCard: MaterialCardView
        val videoCard: MaterialCardView
        val picMsg: ImageView
        val videoCover: ImageView
        val root: LinearLayout

        init {
            root = view.findViewById(R.id.msg_layout)
            nameTv = view.findViewById(R.id.msg_name)
            textContentTv = view.findViewById(R.id.msg_text_content)
            tipTv = view.findViewById(R.id.msg_type_tip_text)
            playTimesTv = view.findViewById(R.id.text_viewcount)
            upNameTv = view.findViewById(R.id.text_upname)
            videoTitleTv = view.findViewById(R.id.text_title)
            textContentCard = view.findViewById(R.id.msg_type_text_card)
            videoCard = view.findViewById(R.id.cardView)
            picMsg = view.findViewById(R.id.msg_type_pic)
            videoCover = view.findViewById(R.id.img_cover)
        }
    }

    @NonNull
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_private_msg, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        if (position < 0 || position >= mPrivateMsgList!!.size) return
        val msg = mPrivateMsgList!![position] ?: return

        try {
            holder.nameTv.setText(msg.name)
            if (selfUid == -1L) {
                selfUid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, -1)
            }
            if (msg.uid == selfUid) {
                holder.root.setGravity(Gravity.END)
                holder.textContentCard.setCardBackgroundColor(ThemeUtil.getColor())
                holder.textContentCard.setStrokeWidth(0)
            } else {
                holder.root.setGravity(Gravity.START)
                holder.textContentCard.setCardBackgroundColor(Color.parseColor("#78242424"))
                holder.textContentCard.setStrokeWidth(1)
            }

            when (msg.type) {
                PrivateMessage.TYPE_TEXT -> {
                    holder.tipTv.setVisibility(View.GONE)
                    holder.picMsg.setVisibility(View.GONE)
                    holder.nameTv.setVisibility(View.VISIBLE)
                    holder.videoCard.setVisibility(View.GONE)
                    holder.textContentCard.setVisibility(View.VISIBLE)

                    if (msg.msg_source >= 8 && msg.msg_source <= 11) {
                        holder.tipTv.setText("此条消息为自动回复")
                        holder.tipTv.setVisibility(View.VISIBLE)
                    }

                    try {
                        val textContent = msg.content!!.getString("content")
                        holder.textContentTv.setText(textContent)
                        CenterThreadPool.run(Runnable {
                            try {
                                val contentWithEmote = PrivateMsgApi.textReplaceEmote(textContent,
                                        emoteArray, 1f, context)
                                if (holder.getAdapterPosition() == position) {
                                    (context as Activity)
                                            .runOnUiThread { holder.textContentTv.setText(contentWithEmote) }
                                }
                            } catch (err: Exception) {
                                Log.e("PrivateMsgAdapter", err.toString())
                            }
                        })
                    } catch (e: JSONException) {
                        Log.e("PrivateMsgAdapter", e.toString())
                    }
                }
                PrivateMessage.TYPE_FACE, PrivateMessage.TYPE_PIC -> { // 结构同图片消息
                    holder.picMsg.setVisibility(View.VISIBLE)
                    holder.tipTv.setVisibility(View.GONE)
                    holder.nameTv.setVisibility(View.VISIBLE)
                    holder.textContentCard.setVisibility(View.GONE)
                    holder.videoCard.setVisibility(View.GONE)
                    try {
                        val picUrl = msg.content!!.getString("url")
                        Glide.with(BiliTerminal.context)
                                .asDrawable()
                                .load(GlideUtil.url(picUrl))
                                .transition(GlideUtil.getTransitionOptions())
                                .override(Target.SIZE_ORIGINAL)
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .into(holder.picMsg)
                        holder.picMsg.setOnClickListener {
                            val imageList = ArrayList<String>()
                            try {
                                imageList.add(msg.content!!.getString("url"))
                            } catch (e: JSONException) {
                                Log.e("PrivateMsgAdapter", e.toString())
                            }
                            val intent = Intent(context, ImageViewerActivity::class.java)
                            intent.putStringArrayListExtra("imageList", imageList)
                            context.startActivity(intent)
                        }
                    } catch (e: JSONException) {
                        Log.e("PrivateMsgAdapter", e.toString())
                    }
                }
                PrivateMessage.TYPE_RETRACT -> {
                    holder.tipTv.setVisibility(View.VISIBLE)
                    holder.nameTv.setVisibility(View.GONE)
                    holder.picMsg.setVisibility(View.GONE)
                    holder.videoCard.setVisibility(View.GONE)
                    holder.textContentCard.setVisibility(View.GONE)
                    holder.tipTv.setText(msg.name + "撤回了一条消息")
                }
                PrivateMessage.TYPE_VIDEO -> {
                    holder.videoCard.setVisibility(View.VISIBLE)
                    holder.nameTv.setVisibility(View.VISIBLE)
                    holder.picMsg.setVisibility(View.GONE)
                    holder.textContentCard.setVisibility(View.GONE)
                    holder.tipTv.setVisibility(View.GONE)
                    Glide.with(BiliTerminal.context)
                            .asDrawable()
                            .load(GlideUtil.url(msg.content!!.getString("thumb")))
                            .transition(GlideUtil.getTransitionOptions())
                            .format(DecodeFormat.PREFER_RGB_565)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(holder.videoCover)
                    holder.upNameTv.setText(msg.content!!.getString("author"))
                    holder.videoTitleTv.setText(msg.content!!.getString("title"))
                    holder.videoCard.setOnClickListener {
                        CenterThreadPool.run(Runnable {
                            try {
                                val aid = msg.content!!.getLong("id")
                                TerminalContext.getInstance().enterVideoDetailPage(context, aid, "", "video")
                            } catch (err: JSONException) {
                                Log.e("", err.toString())
                            }
                        })
                    }
                }
                PrivateMessage.TYPE_SYSTEM -> {
                    holder.tipTv.setVisibility(View.VISIBLE)
                    holder.nameTv.setVisibility(View.GONE)
                    holder.picMsg.setVisibility(View.GONE)
                    holder.videoCard.setVisibility(View.GONE)
                    holder.textContentCard.setVisibility(View.GONE)
                    holder.tipTv.setText((msg.content_array!![0] as JSONObject).getString("text"))
                }
                else -> {
                    holder.textContentCard.setVisibility(View.VISIBLE)
                    holder.textContentTv.setText("暂时无法显示该消息")
                    holder.tipTv.setVisibility(View.GONE)
                    holder.picMsg.setVisibility(View.GONE)
                    holder.nameTv.setVisibility(View.VISIBLE)
                    holder.videoCard.setVisibility(View.GONE)
                }
            }

            holder.textContentCard.setOnLongClickListener {
                try {
                    val intent = Intent(context, CopyTextActivity::class.java)
                    intent.putExtra("content", msg.content!!.getString("content"))
                    context.startActivity(intent)

                } catch (err: Exception) {
                    err.printStackTrace()
                }
                false
            }
        } catch (err: JSONException) {
            Log.e(PrivateMessage::class.java.getName(), err.toString())
        }
    }

    fun addItem(list: ArrayList<PrivateMessage>?) {
        if (list == null || list.isEmpty()) return
        mPrivateMsgList!!.addAll(0, list)
        this.notifyItemRangeInserted(0, list.size)
    }

    override fun getItemCount(): Int {
        return mPrivateMsgList?.size ?: 0
    }
}
