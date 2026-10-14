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

class FavoriteFolderAdapter(context: Context?, folderList: ArrayList<FavoriteFolder>?, mid: Long) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val context: Context? = context
    val folderList: ArrayList<FavoriteFolder>? = folderList
    val mid: Long = mid
    private var onLongClickListener: OnLongClickListener? = null
    private var onCreateClickListener: OnCreateClickListener? = null

    companion object {
        private const val TYPE_CREATE = 0
        private const val TYPE_FOLDER = 1
        private const val TYPE_OPUS = 2
    }

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
        return if (position == 0) {
            TYPE_CREATE
        } else if (position == folderList!!.size + 1) {
            TYPE_OPUS
        } else {
            TYPE_FOLDER
        }
    }

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_CREATE) {
            val view = LayoutInflater.from(this.context).inflate(R.layout.cell_create_folder_button, parent, false)
            return CreateButtonHolder(view)
        } else {
            val view = LayoutInflater.from(this.context).inflate(R.layout.cell_favorite_folder_list, parent, false)
            return FavoriteHolder(view)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
        val list = folderList ?: return
        if (holder is CreateButtonHolder) {
            val createHolder = holder as CreateButtonHolder
            createHolder.itemView.setOnClickListener {
                if (onCreateClickListener != null) {
                    onCreateClickListener!!.onCreateClick()
                }
            }
        } else if (holder is FavoriteHolder) {
            val favoriteHolder = holder as FavoriteHolder
            if (position == list.size + 1) {
                favoriteHolder.name.setText("图文收藏夹")
                favoriteHolder.count.setText("")
                Glide.with(BiliTerminal.context).asDrawable()
                        .load(StringUtil.getDrawable(this.context!!, R.drawable.article_fav_cover))
                        .transition(GlideUtil.getTransitionOptions())
                        .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f))))
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(favoriteHolder.cover)
                favoriteHolder.itemView.setOnClickListener {
                    val intent = Intent(this.context!!, FavouriteOpusListActivity::class.java)
                    context!!.startActivity(intent)
                }
                favoriteHolder.itemView.setOnLongClickListener(null)
            } else if (position > 0 && position <= list.size) {
                val folder = list[position - 1] ?: return

                favoriteHolder.name.setText(StringUtil.htmlToString(folder.name ?: ""))
                favoriteHolder.count.setText(folder.videoCount.toString() + "/" + folder.maxCount)
                Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(folder.cover ?: ""))
                        .transition(GlideUtil.getTransitionOptions())
                        .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f))))
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(favoriteHolder.cover)
                favoriteHolder.itemView.setOnClickListener {
                    val intent = Intent()
                    intent.setClass(this.context!!, FavoriteVideoListActivity::class.java)
                    intent.putExtra("fid", folder.id)
                    intent.putExtra("mid", mid)
                    intent.putExtra("name", folder.name)
                    context!!.startActivity(intent)
                }
                favoriteHolder.itemView.setOnLongClickListener {
                    if (onLongClickListener != null && !folder.isDefault) {
                        onLongClickListener!!.onLongClick(position - 1)
                    } else if (folder.isDefault) {
                        com.RobinNotBad.BiliClient.util.MsgUtil.showMsg("默认收藏夹不能编辑")
                    }
                    true
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return if (folderList != null) folderList.size + 2 else 2
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
