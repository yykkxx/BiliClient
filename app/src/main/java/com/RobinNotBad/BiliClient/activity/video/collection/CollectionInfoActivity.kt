package com.RobinNotBad.BiliClient.activity.video.collection

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardHolder
import com.RobinNotBad.BiliClient.model.Collection
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.model.VideoInfo
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions

import java.util.ArrayList
import java.util.Collections
import java.util.Objects

class CollectionInfoActivity : RefreshListActivity() {
    private var collection: Collection? = null

    init {
        // #4：双列模式适用于所有视频列表（合集详情页）
        video_card_layout = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val from_aid = getIntent().getLongExtra("fromVideo", -1)
        val season_id = getIntent().getIntExtra("season_id", -1)
        val mid = getIntent().getLongExtra("mid", -1)
        setPageName("合集详情")

        TerminalContext.getInstance().getVideoInfoByAidOrBvId(from_aid, null).observe(this, { result -> result.onSuccess { videoInfo ->
            collection = videoInfo.collection
            val col = videoInfo.collection!!

            val adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>
            if (col.sections == null && col.cards != null) {
                adapter = CardAdapter(this, col)
            } else if (col.sections != null) {
                adapter = SectionAdapter(this, col, recyclerView!!)
                val sections = col.sections!!
                var pos = 1
                for (section in sections) {
                    pos++
                    val episodes = section.episodes
                    for (episode in episodes) {
                        pos++
                        if (episode.aid == from_aid) {
                            recyclerView!!.layoutManager!!.scrollToPosition(--pos)
                        }
                    }
                }
            } else {
                finish()
                return@onSuccess
            }

            setAdapter(adapter)
            setRefreshing(false)
        }})
    }

    class CardAdapter(val context: Context, val collection: Collection) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val data: List<VideoCard> = collection.cards

        /** 信息流布局（#4）：由外部按双列开关赋值 */
        @JvmField
        var feedLayout: Boolean = ListLayoutUtil.isDualColumnVideo()

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) -1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == -1) {
                val view = LayoutInflater.from(this.context).inflate(R.layout.cell_collection_info, parent, false)
                CollectionInfoHolder(view)
            } else {
                val layoutRes = if (feedLayout) R.layout.cell_video_feed else R.layout.cell_video_list // #4
                val view = LayoutInflater.from(this.context).inflate(layoutRes, parent, false)
                VideoCardHolder(view)
            }
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is VideoCardHolder) {
                var position = position
                position--
                val videoCardHolder = holder as VideoCardHolder
                val videoCard = data[position]
                videoCardHolder.itemView.setOnClickListener { view -> TerminalContext.getInstance().enterVideoDetailPage(context, videoCard.aid, videoCard.bvid) }
                videoCardHolder.showVideoCard(videoCard, context)
            } else if (holder is CollectionInfoHolder) {
                val collectionInfoHolder = holder as CollectionInfoHolder
                collectionInfoHolder.name.text = collection.title ?: ""
                collectionInfoHolder.desc.text = if (TextUtils.isEmpty(collection.intro)) "这里没有简介哦" else collection.intro ?: ""
                collectionInfoHolder.playTimes.text = "共" + collection.view
                Glide.with(context).asDrawable().load(GlideUtil.url(collection.cover ?: ""))
                        .transition(GlideUtil.getTransitionOptions())
                        .placeholder(R.mipmap.placeholder)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f))).sizeMultiplier(0.85f).dontAnimate())
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(collectionInfoHolder.cover)
                collectionInfoHolder.cover.setOnClickListener { view -> context.startActivity(Intent(context, ImageViewerActivity::class.java).putExtra("imageList", ArrayList(Collections.singletonList(collection.cover)))) }
                StringUtil.setCopy(collectionInfoHolder.name, collectionInfoHolder.desc)
                StringUtil.setLink(collectionInfoHolder.desc)
            }
        }

        override fun getItemCount(): Int {
            return data.size + 1
        }

        class CollectionInfoHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.name)
            val desc: TextView = itemView.findViewById(R.id.desc)
            val playTimes: TextView = itemView.findViewById(R.id.playTimes)
            val cover: ImageView = itemView.findViewById(R.id.img_cover)
        }
    }

    class SectionAdapter(val context: Context, val collection: Collection, val recyclerView: RecyclerView) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val data: List<Collection.Section> = collection.sections

        /** 信息流布局（#4）：由外部按双列开关赋值 */
        @JvmField
        var feedLayout: Boolean = ListLayoutUtil.isDualColumnVideo()
        val types = ArrayList<Int>()

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return -1
            var position = position
            return getTypes()[--position]
        }

        private fun getTypes(): List<Int> {
            synchronized(this) {
                types.clear()
                for (section in data) {
                    types.add(1)
                    for (i in section.episodes.indices) {
                        types.add(0)
                    }
                }
                return types
            }
        }

        private fun getSectionPos(pos: Int): Int {
            val list = getTypes()
            var sectionPos = -1
            for (i in 0..pos) {
                if (list[i] == 1) sectionPos++
            }
            return sectionPos
        }

        private fun getEpisodePos(pos: Int): Int {
            val list = getTypes()
            var episodePos = -1
            var i = pos
            while (i >= 0) {
                if (list[i] == 1) return episodePos
                episodePos++
                i--
            }
            return 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == -1) {
                val view = LayoutInflater.from(this.context).inflate(R.layout.cell_collection_info, parent, false)
                CollectionInfoHolder(view)
            } else if (viewType == 0) {
                val layoutRes = if (feedLayout) R.layout.cell_video_feed else R.layout.cell_video_list // #4
                val view = LayoutInflater.from(this.context).inflate(layoutRes, parent, false)
                VideoCardHolder(view)
            } else {
                SectionHolder(TextView(context))
            }
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            var position = position
            if (holder is SectionHolder) {
                position--
                (holder as SectionHolder).item.text = data[getSectionPos(position)].title ?: ""
            } else if (holder is VideoCardHolder) {
                position--
                val videoCardHolder = holder as VideoCardHolder
                val videoInfo = data[getSectionPos(position)].episodes[getEpisodePos(position)].arc!!
                val videoCard = VideoCard(videoInfo.title, "", StringUtil.toWan(videoInfo.stats!!.view.toLong()), videoInfo.cover, videoInfo.aid, videoInfo.bvid)
                videoCardHolder.itemView.setOnClickListener { view -> TerminalContext.getInstance().enterVideoDetailPage(context, videoCard.aid, videoCard.bvid) }
                videoCardHolder.showVideoCard(videoCard, context)
            } else if (holder is CollectionInfoHolder) {
                val collectionInfoHolder = holder as CollectionInfoHolder
                collectionInfoHolder.name.text = collection.title ?: ""
                collectionInfoHolder.desc.text = if (TextUtils.isEmpty(collection.intro)) "这里没有简介哦" else collection.intro ?: ""
                collectionInfoHolder.playTimes.text = "共" + collection.view
                Glide.with(context).asDrawable().load(GlideUtil.url(collection.cover ?: ""))
                        .transition(GlideUtil.getTransitionOptions())
                        .placeholder(R.mipmap.placeholder)
                        .format(DecodeFormat.PREFER_RGB_565)
                        .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(5f))).sizeMultiplier(0.85f).dontAnimate())
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(collectionInfoHolder.cover)
                collectionInfoHolder.cover.setOnClickListener { view -> context.startActivity(Intent(context, ImageViewerActivity::class.java).putExtra("imageList", ArrayList(Collections.singletonList(collection.cover)))) }
            }
        }

        override fun getItemCount(): Int {
            var count = 0
            for (section in data) {
                count++
                count += section.episodes.size
            }
            return ++count
        }

        class SectionHolder(itemView: TextView) : RecyclerView.ViewHolder(itemView) {
            val item: TextView = itemView

            init {
                this.item.left = 5
            }
        }

        class CollectionInfoHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val name: TextView = itemView.findViewById(R.id.name)
            val desc: TextView = itemView.findViewById(R.id.desc)
            val playTimes: TextView = itemView.findViewById(R.id.playTimes)
            val cover: ImageView = itemView.findViewById(R.id.img_cover)
        }
    }
}
