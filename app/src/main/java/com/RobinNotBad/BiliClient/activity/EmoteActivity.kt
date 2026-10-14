package com.RobinNotBad.BiliClient.activity

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.widget.TooltipCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.EmoteApi
import com.RobinNotBad.BiliClient.model.Emote
import com.RobinNotBad.BiliClient.model.EmotePackage
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomGridManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import java.util.Objects
import java.util.concurrent.ExecutionException

class EmoteActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emote)

        val loading = findViewById<ImageView>(R.id.loading)
        val tabLayout = findViewById<TabLayout>(R.id.tl_tab)
        val viewPager = findViewById<ViewPager>(R.id.viewPager)
        tabLayout.setBackgroundColor(getResources().getColor(R.color.bgblack))
        val onScrollListener: RecyclerView.OnScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(@NonNull recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
            }
        }

        CenterThreadPool.run(Runnable {
            try {
                var from = getIntent().getStringExtra("from")
                if (from == null) from = EmoteApi.BUSINESS_REPLY

                val packages = EmoteApi.getEmotes(from)
                runOnUiThread {
                    loading.visibility = View.GONE
                    viewPager.adapter = PagerAdapter(supportFragmentManager, packages, PagerAdapter.FragmentHandler { origin ->
                        origin.setOnListScroll(onScrollListener)
                        origin
                    })
                    tabLayout.setupWithViewPager(viewPager)
                    tabLayout.setInlineLabel(true)
                    tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                        override fun onTabSelected(tab: TabLayout.Tab) {
                            tab.setTabLabelVisibility(TabLayout.TAB_LABEL_VISIBILITY_LABELED)
                        }

                        override fun onTabUnselected(tab: TabLayout.Tab) {
                            tab.setTabLabelVisibility(TabLayout.TAB_LABEL_VISIBILITY_UNLABELED)
                        }

                        override fun onTabReselected(tab: TabLayout.Tab) {
                            tab.setTabLabelVisibility(TabLayout.TAB_LABEL_VISIBILITY_LABELED)
                        }
                    })
                    tabLayout.tabIconTint = null
                    val count = tabLayout.tabCount

                    CenterThreadPool.run(Runnable {
                        for (i in 0 until count) {
                            val finalI = i
                            Objects.requireNonNull(packages)

                            runOnUiThread {
                                Objects.requireNonNull(tabLayout.getTabAt(finalI))!!.text = packages!![finalI].text
                                if (finalI != 0)
                                    Objects.requireNonNull(tabLayout.getTabAt(finalI))!!.setTabLabelVisibility(TabLayout.TAB_LABEL_VISIBILITY_UNLABELED)
                            }

                            try {
                                val drawable = Glide.with(this).asDrawable()
                                        .transition(GlideUtil.getTransitionOptions())
                                        .load(packages!![finalI].url)
                                        .submit().get()
                                runOnUiThread { Objects.requireNonNull(tabLayout.getTabAt(finalI))!!.setIcon(drawable) }
                            } catch (e: ExecutionException) {
                                MsgUtil.err("加载表情列表图标时出现错误：", e)
                                e.printStackTrace()
                            } catch (e: InterruptedException) {
                                e.printStackTrace()
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                runOnUiThread { MsgUtil.err(e) }
            }
        })
    }

    class PagerAdapter constructor(@NonNull fm: FragmentManager, val emotes: List<EmotePackage>?, val handler: FragmentHandler) : FragmentPagerAdapter(fm) {

        override fun getItem(position: Int): Fragment {
            return handler.handleCreateFragment(EmoteFragment.newInstance(emotes!![position]))
        }

        override fun getCount(): Int {
            return emotes!!.size
        }

        fun interface FragmentHandler {
            fun handleCreateFragment(origin: EmoteFragment): Fragment
        }
    }

    class EmoteFragment : Fragment() {
        private var emotePackage: EmotePackage? = null
        private var recyclerView: RecyclerView? = null
        private var hasListener = false
        private var onListScroll: RecyclerView.OnScrollListener? = null

        companion object {
            @JvmStatic
            fun newInstance(emotePackage: EmotePackage): EmoteFragment {
                val emoteFragment = EmoteFragment()
                val bundle = Bundle()
                bundle.putParcelable("emotePackage", emotePackage)
                emoteFragment.arguments = bundle
                return emoteFragment
            }
        }

        override fun onCreate(@Nullable savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val bundle = arguments
            if (bundle != null) {
                this.emotePackage = bundle.getParcelable("emotePackage")
            }
        }

        @Nullable
        override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?, @Nullable savedInstanceState: Bundle?): View {
            return inflater.inflate(R.layout.fragment_simple_list, container, false)
        }

        override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            this.recyclerView = view.findViewById(R.id.recyclerView)
            val layoutManager = CustomGridManager(requireContext(), 4, RecyclerView.VERTICAL, false)
            layoutManager.setSpanSizeLookup(object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (emotePackage!!.type == 4) 2 else emotePackage!!.emotes!![position].size
                }
            })
            recyclerView!!.layoutManager = layoutManager
            if (onListScroll != null) {
                hasListener = true
                recyclerView!!.addOnScrollListener(onListScroll!!)
            }

            val adapter = EmoteAdapter(emotePackage, requireContext())
            adapter.setOnClickEmote(EmoteAdapter.OnClickEmoteListener { emote ->
                requireActivity().setResult(RESULT_OK, Intent().putExtra("text", emote.name))
                requireActivity().finish()
            })
            recyclerView!!.addItemDecoration(GridSpacingItemDecoration(4, getResources().getDimensionPixelSize(R.dimen.grid_spacing), true))
            recyclerView!!.adapter = adapter
        }

        fun setOnListScroll(onScrollListener: RecyclerView.OnScrollListener?) {
            if (this.onListScroll == null) this.onListScroll = onScrollListener
        }

        override fun onDestroyView() {
            super.onDestroyView()
            if (this.onListScroll != null && hasListener)
                recyclerView!!.removeOnScrollListener(onListScroll!!)
        }
    }

    class GridSpacingItemDecoration constructor(private val spanCount: Int, private val spacing: Int, private val includeEdge: Boolean) : RecyclerView.ItemDecoration() {

        override fun getItemOffsets(@NonNull outRect: Rect, @NonNull view: View, @NonNull parent: RecyclerView, @NonNull state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view) // item position
            val column = position % spanCount // item column

            if (includeEdge) {
                outRect.left = spacing - column * spacing / spanCount // spacing - column * ((1f / spanCount) * spacing)
                outRect.right = (column + 1) * spacing / spanCount // (column + 1) * ((1f / spanCount) * spacing)

                if (position < spanCount) { // top edge
                    outRect.top = spacing
                }
                outRect.bottom = spacing // item bottom
            } else {
                outRect.left = column * spacing / spanCount // column * ((1f / spanCount) * spacing)
                outRect.right = spacing - (column + 1) * spacing / spanCount // spacing - (column + 1) * ((1f /    spanCount) * spacing)
                if (position >= spanCount) {
                    outRect.top = spacing // item top
                }
            }
        }
    }

    class EmoteAdapter(private val emotePackage: EmotePackage?, private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var listener: OnClickEmoteListener? = null

        @NonNull
        override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) Holder(ImageView(context)) else TextHolder(TextView(context))
        }

        override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
            val emote = emotePackage!!.emotes!![position]
            if (holder is Holder) {
                Glide.with(context).asDrawable()
                        .transition(GlideUtil.getTransitionOptions())
                        .load(GlideUtil.url(emote.url ?: ""))
                        .into(holder.imageView)
                holder.itemView.setOnClickListener { view ->
                    if (listener != null) {
                        listener!!.onClickEmote(emote)
                    }
                }
            } else {
                (holder as TextHolder).textView.setSingleLine()
                (holder as TextHolder).textView.ellipsize = TextUtils.TruncateAt.END
                (holder as TextHolder).textView.text = emote.name
                holder.itemView.setOnClickListener { view ->
                    if (listener != null) {
                        listener!!.onClickEmote(emote)
                    }
                }
            }
            TooltipCompat.setTooltipText(holder.itemView, if (emote.alias != null) emote.alias else emote.name)
        }

        override fun getItemCount(): Int {
            return emotePackage!!.emotes!!.size
        }

        override fun getItemViewType(position: Int): Int {
            return if (emotePackage!!.type == 4) 0 else 1
        }

        fun setOnClickEmote(listener: OnClickEmoteListener?) {
            this.listener = listener
        }

        fun interface OnClickEmoteListener {
            fun onClickEmote(emote: Emote)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView as ImageView
        }

        class TextHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textView: TextView = itemView as TextView
        }
    }

}
