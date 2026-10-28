package com.RobinNotBad.BiliClient.adapter.favorite

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.user.favorite.FavoriteVideoListActivity
import com.RobinNotBad.BiliClient.activity.user.favorite.FavouriteOpusListActivity
import com.RobinNotBad.BiliClient.model.FavoriteFolder
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions

import java.util.ArrayList

//收藏夹Adapter
//2025 收藏夹接口迁移：视频收藏夹列表来自 v3 接口，末尾仍是独立的「图文收藏夹」入口

class FavoriteFolderAdapter(
        context: Context?,
        folderList: ArrayList<FavoriteFolder>?,
        mid: Long,
        /** 顶部提示（未登录 / 加载失败 / 没取到视频收藏夹时显示），null 不占位 */
        private val notice: String? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val context: Context? = context
    val folderList: ArrayList<FavoriteFolder>? = folderList
    val mid: Long = mid
    private var onLongClickListener: OnLongClickListener? = null
    private var onCreateClickListener: OnCreateClickListener? = null

    companion object {
        private const val TYPE_NOTICE = 0
        private const val TYPE_CREATE = 1
        private const val TYPE_FOLDER = 2
        private const val TYPE_OPUS = 3
    }

    // 行布局： [提示条] [新建按钮] [视频收藏夹 x N] [图文收藏夹]
    // 提示条只在需要时占一行，所以下面三个位置都跟着它偏移
    private val noticeCount: Int = if (notice.isNullOrEmpty()) 0 else 1
    private val createPosition: Int = noticeCount
    private val folderStart: Int = createPosition + 1
    private val opusPosition: Int = folderStart + (folderList?.size ?: 0)

    interface OnLongClickListener {
        fun onLongClick(position: Int)
    }

    interface OnCreateClickListener {
        fun onCreateClick()
    }

    fun setOnLongClickListener(listener: OnLongClickListener?) {
        this.onLongClickListener = listener
    }

    fun setOnCreateClickListener(listener: OnCreateClickListener?) {
        this.onCreateClickListener = listener
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            position < noticeCount -> TYPE_NOTICE
            position == createPosition -> TYPE_CREATE
            position == opusPosition -> TYPE_OPUS
            else -> TYPE_FOLDER
        }
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_NOTICE -> NoticeHolder(
                    LayoutInflater.from(this.context).inflate(R.layout.cell_favorite_notice, parent, false)
            )

            TYPE_CREATE -> CreateButtonHolder(
                    LayoutInflater.from(this.context).inflate(R.layout.cell_create_folder_button, parent, false)
            )

            else -> FavoriteHolder(
                    LayoutInflater.from(this.context).inflate(R.layout.cell_favorite_folder_list, parent, false)
            )
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
        val list = folderList ?: return
        when {
            holder is NoticeHolder -> {
                holder.text.text = notice ?: ""
            }

            holder is CreateButtonHolder -> {
                holder.itemView.setOnClickListener {
                    onCreateClickListener?.onCreateClick()
                }
            }

            holder is FavoriteHolder -> {
                if (position == opusPosition) {
                    // 图文收藏夹（动态 / 专栏）保持为独立入口，但永远排在视频收藏夹之后，
                    // 并且用「动态 / 专栏」副标题和图文封面跟视频收藏夹区分开
                    bindOpusRow(holder)
                } else if (position >= folderStart && position < opusPosition) {
                    bindFolderRow(holder, list[position - folderStart], position)
                }
            }
        }
    }

    /** 绑定一个真实的视频收藏夹 */
    private fun bindFolderRow(holder: FavoriteHolder, folder: FavoriteFolder, position: Int) {
        // quality-allow：新代码不用 !!，context 理论上不会是 null（构造时由页面传入本 Activity）
        val ctx = context ?: return
        holder.name.setText(StringUtil.htmlToString(folder.name ?: ""))

        // v3 接口不再返回收藏夹容量上限（maxCount=0 表示未知），这时只显示视频数
        holder.count.setText(
                if (folder.maxCount > 0) folder.videoCount.toString() + "/" + folder.maxCount
                else folder.videoCount.toString() + " 个视频"
        )

        Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(folder.cover ?: ""))
                .transition(GlideUtil.getTransitionOptions())
                .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f))))
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(holder.cover)

        holder.itemView.setOnClickListener {
            val intent = Intent()
            intent.setClass(ctx, FavoriteVideoListActivity::class.java)
            // fid 给旧接口/旧调用方用，media_id 给 v3 收藏夹内容接口用（两者都要传，页面自己挑）
            intent.putExtra("fid", folder.id)
            intent.putExtra("media_id", folder.mediaId)
            intent.putExtra("mid", mid)
            intent.putExtra("name", folder.name)
            ctx.startActivity(intent)
        }
        holder.itemView.setOnLongClickListener {
            if (onLongClickListener != null && !folder.isDefault) {
                onLongClickListener?.onLongClick(position - folderStart)
            } else if (folder.isDefault) {
                com.RobinNotBad.BiliClient.util.MsgUtil.showMsg("默认收藏夹不能编辑")
            }
            true
        }
    }

    /** 绑定末尾的「图文收藏夹」入口 */
    private fun bindOpusRow(holder: FavoriteHolder) {
        val ctx = context ?: return
        holder.name.setText("图文收藏夹")
        holder.count.setText("动态 / 专栏")
        Glide.with(BiliTerminal.context).asDrawable()
                .load(StringUtil.getDrawable(ctx, R.drawable.article_fav_cover))
                .transition(GlideUtil.getTransitionOptions())
                .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f))))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(holder.cover)
        holder.itemView.setOnClickListener {
            ctx.startActivity(Intent(ctx, FavouriteOpusListActivity::class.java))
        }
        holder.itemView.setOnLongClickListener(null)
    }

    override fun getItemCount(): Int {
        // 提示条 + 新建按钮 + 视频收藏夹 + 图文收藏夹（图文入口始终存在）
        return noticeCount + 1 + (folderList?.size ?: 0) + 1
    }

    class NoticeHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.text_notice)
    }

    class FavoriteHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView
        val count: TextView
        val cover: ImageView

        init {
            name = itemView.findViewById(R.id.text_title)
            count = itemView.findViewById(R.id.text_itemcount)
            cover = itemView.findViewById(R.id.img_cover)
        }
    }

    class CreateButtonHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView

        init {
            text = itemView.findViewById(R.id.text)
        }
    }
}
