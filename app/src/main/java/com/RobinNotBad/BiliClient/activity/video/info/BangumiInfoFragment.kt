package com.RobinNotBad.BiliClient.activity.video.info

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.adapter.video.MediaEpisodeAdapter
import com.RobinNotBad.BiliClient.api.BangumiApi
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.model.Bangumi
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

import java.util.ArrayList

class BangumiInfoFragment : Fragment() {
    private var mediaId: Long = 0
    private var selectedSection = 0
    private var selectedEpisode = 0
    private var dialog: Dialog? = null
    private var rootView: View? = null
    private var episodeRecyclerView: RecyclerView? = null
    private var section_choose: Button? = null
    private var episode_choose: TextView? = null
    private var bangumi: Bangumi? = null

    companion object {
        @JvmStatic
        fun newInstance(mediaId: Long): BangumiInfoFragment {
            val args = Bundle()
            args.putLong("media_id", mediaId)
            val fragment = BangumiInfoFragment()
            fragment.setArguments(args)
            return fragment
        }
    }

    @Nullable
    override fun onCreateView(@NonNull inflater: LayoutInflater, @Nullable container: ViewGroup?, @Nullable savedInstanceState: Bundle?): View {
        val arguments = getArguments()
        if (arguments != null) {
            mediaId = arguments.getLong("media_id")
        }
        rootView = inflater.inflate(R.layout.fragment_media_info, container, false)
        return rootView!!
    }

    override fun onViewCreated(@NonNull view: View, @Nullable savedInstanceState: Bundle?) {
        view.setVisibility(View.GONE)
        episodeRecyclerView = rootView!!.findViewById(R.id.rv_episode_list) as RecyclerView
        //拉数据
        CenterThreadPool
            .supplyAsyncWithLiveData { BangumiApi.getBangumi(mediaId) }
            .observe(getViewLifecycleOwner()) { result ->
                result.onSuccess { bangumi ->
                    this.bangumi = bangumi
                    // 与视频详情页同理：initView 跑在主线程回调里，番剧数据缺字段
                    // （未上线/被下架/接口结构变化）时 info/sectionList 可能是 null，
                    // 老写法满屏的 info!! / sectionList!! 会当场 NPE 闪退。
                    try {
                        normalizeBangumi(bangumi)
                        initView()
                    } catch (e: Exception) {
                        MsgUtil.err("打开番剧详情失败", e)
                    }
                }.onFailure { error -> MsgUtil.err("番剧详情：", error) }
            }
    }

    /** 把可空的容器字段补成「空但合法」，让 initView 里的 !! 用法不至于崩进程。 */
    private fun normalizeBangumi(bangumi: Bangumi) {
        if (bangumi.info == null) {
            // info 整块缺失（接口异常/被风控）时补一个空壳：页面照常渲染出空内容，
            // 也比直接崩掉好；标题等文案字段本身可空，setText(null) 不会崩。
            bangumi.info = Bangumi.Info()
        }
        if (bangumi.sectionList == null) bangumi.sectionList = ArrayList()
        bangumi.info?.let { info ->
            if (info.cover_horizontal == null) info.cover_horizontal = info.cover ?: ""
            if (info.title == null) info.title = ""
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        val bangumi = this.bangumi ?: return
        //init data.
        val imageMediaCover = rootView!!.findViewById(R.id.image_media_cover) as ImageView
        val playButton = rootView!!.findViewById(R.id.btn_play) as Button
        val title = rootView!!.findViewById(R.id.text_title) as TextView
        val subtitle = rootView!!.findViewById(R.id.text_subtitle) as TextView
        val areaType = rootView!!.findViewById(R.id.text_area_type) as TextView
        val rating = rootView!!.findViewById(R.id.text_rating) as TextView
        val pubTime = rootView!!.findViewById(R.id.text_pub_time) as TextView
        val stats = rootView!!.findViewById(R.id.text_stats) as TextView
        val styles = rootView!!.findViewById(R.id.text_styles) as TextView
        val evaluateHeader = rootView!!.findViewById(R.id.layout_evaluate_header) as View
        val evaluateArrow = rootView!!.findViewById(R.id.icon_evaluate_arrow) as ImageView
        val evaluate = rootView!!.findViewById(R.id.text_evaluate) as TextView
        val staffHeader = rootView!!.findViewById(R.id.layout_staff_header) as View
        val staffArrow = rootView!!.findViewById(R.id.icon_staff_arrow) as ImageView
        val staff = rootView!!.findViewById(R.id.text_staff) as TextView
        val record = rootView!!.findViewById(R.id.text_record) as TextView
        section_choose = rootView!!.findViewById(R.id.section_choose) as Button
        episode_choose = rootView!!.findViewById(R.id.episode_choose) as TextView
        selectedSection = 0

        rootView!!.setVisibility(View.GONE)

        Glide.with(requireContext())
            .load(GlideUtil.url(bangumi.info!!.cover_horizontal!!))
            .transition(GlideUtil.getTransitionOptions())
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .placeholder(R.mipmap.placeholder)
            .into(imageMediaCover)
        imageMediaCover.setOnClickListener { view ->
            val imageList = ArrayList<String>()
            imageList.add(bangumi.info!!.cover_horizontal!!)
            startActivity(Intent(view.getContext(), ImageViewerActivity::class.java).putExtra("imageList", imageList))
        }
        title.setText(bangumi.info!!.title)

        // 副标题
        if (bangumi.info!!.subtitle != null && !bangumi.info!!.subtitle!!.isEmpty()) {
            subtitle.setText(bangumi.info!!.subtitle)
            subtitle.setVisibility(View.VISIBLE)
        } else {
            subtitle.setVisibility(View.GONE)
        }

        // 地区和类型
        val areaTypeText = (if (bangumi.info!!.area_name != null) bangumi.info!!.area_name else "") +
            (if (bangumi.info!!.type_name != null) " | " + bangumi.info!!.type_name else "")
        if (!areaTypeText.trim { it <= ' ' }.isEmpty()) {
            areaType.setText(areaTypeText.trim { it <= ' ' })
            areaType.setVisibility(View.VISIBLE)
        } else {
            areaType.setVisibility(View.GONE)
        }

        // 评分
        if (bangumi.info!!.score > 0) {
            rating.setText(String.format("评分：%.1f (%d人)", bangumi.info!!.score, bangumi.info!!.count))
            rating.setVisibility(View.VISIBLE)
        } else {
            rating.setVisibility(View.GONE)
        }

        // 发布时间
        if (bangumi.info!!.publish != null && bangumi.info!!.publish!!.pub_time_show != null && !bangumi.info!!.publish!!.pub_time_show!!.isEmpty()) {
            val status = if (bangumi.info!!.publish!!.is_finish == 1) "已完结" else "连载中"
            pubTime.setText(bangumi.info!!.publish!!.pub_time_show!! + " " + status)
            pubTime.setVisibility(View.VISIBLE)
        } else {
            pubTime.setVisibility(View.GONE)
        }

        // 状态数
        if (bangumi.info!!.stat != null) {
            val statBuilder = StringBuilder()
            if (bangumi.info!!.stat!!.views > 0) {
                statBuilder.append("播放：").append(formatNumber(bangumi.info!!.stat!!.views))
            }
            if (bangumi.info!!.stat!!.favorites > 0) {
                if (statBuilder.length > 0) statBuilder.append(" ")
                statBuilder.append("收藏：").append(formatNumber(bangumi.info!!.stat!!.favorites))
            }
            if (bangumi.info!!.stat!!.series_follow > 0) {
                if (statBuilder.length > 0) statBuilder.append(" ")
                statBuilder.append("追番：").append(formatNumber(bangumi.info!!.stat!!.series_follow))
            }
            if (statBuilder.length > 0) {
                stats.setText(statBuilder.toString())
                stats.setVisibility(View.VISIBLE)
            } else {
                stats.setVisibility(View.GONE)
            }
        } else {
            stats.setVisibility(View.GONE)
        }

        // 标签
        if (bangumi.info!!.styles != null && !bangumi.info!!.styles!!.isEmpty()) {
            val styleText = "标签：" + bangumi.info!!.styles!!.joinToString(" ")
            styles.setText(styleText)
            styles.setVisibility(View.VISIBLE)
        } else {
            styles.setVisibility(View.GONE)
        }

        // 简介
        if (bangumi.info!!.evaluate != null && !bangumi.info!!.evaluate!!.trim { it <= ' ' }.isEmpty()) {
            evaluate.setText(bangumi.info!!.evaluate!!.trim { it <= ' ' })
            evaluateHeader.setVisibility(View.VISIBLE)
            evaluate.setVisibility(View.GONE) // 默认折叠
            evaluateHeader.setOnClickListener { v ->
                val isExpanded = evaluate.getVisibility() == View.VISIBLE
                evaluate.setVisibility(if (isExpanded) View.GONE else View.VISIBLE)
                evaluateArrow.animate().rotation((if (isExpanded) 0 else 180).toFloat()).setDuration(200L).start()
            }
        } else {
            evaluateHeader.setVisibility(View.GONE)
            evaluate.setVisibility(View.GONE)
        }

        // 制作人员
        if (bangumi.info!!.staff != null && !bangumi.info!!.staff!!.trim { it <= ' ' }.isEmpty()) {
            staff.setText(bangumi.info!!.staff!!.trim { it <= ' ' })
            staffHeader.setVisibility(View.VISIBLE)
            staff.setVisibility(View.GONE) // 默认折叠
            staffHeader.setOnClickListener { v ->
                val isExpanded = staff.getVisibility() == View.VISIBLE
                staff.setVisibility(if (isExpanded) View.GONE else View.VISIBLE)
                staffArrow.animate().rotation((if (isExpanded) 0 else 180).toFloat()).setDuration(200L).start()
            }
        } else {
            staffHeader.setVisibility(View.GONE)
            staff.setVisibility(View.GONE)
        }

        // 备案号
        if (bangumi.info!!.record != null && !bangumi.info!!.record!!.trim { it <= ' ' }.isEmpty()) {
            record.setText("备案号：" + bangumi.info!!.record!!.trim { it <= ' ' })
            record.setVisibility(View.VISIBLE)
        } else {
            record.setVisibility(View.GONE)
        }
        //section selector setting.
        val adapter = MediaEpisodeAdapter()

        adapter.setOnItemClickListener { index ->
            selectedEpisode = index
            refreshReplies()
        }

        val indexShow = rootView!!.findViewById(R.id.indexShow) as TextView
        indexShow.setText(bangumi.info!!.indexShow)

        if (bangumi.sectionList!!.isEmpty()) {
            section_choose!!.setText("敬请期待")
            playButton.setVisibility(View.GONE)
            rootView!!.findViewById<View>(R.id.episodes).setVisibility(View.GONE)    //未上线的番剧Activity activity = getActivity();
            val activity: Activity = requireActivity()
            if (activity is VideoInfoActivity) {
                activity.replyFragment!!.setRefreshing(false)
            }
            return
        }

        section_choose!!.setText((bangumi.sectionList!!.get(0).title ?: "") + " 点击切换")
        section_choose!!.setOnClickListener { getSectionChooseDialog().show() }
        episode_choose!!.setOnClickListener { getEposideChooseDialog().show() }

        adapter.setData(bangumi.sectionList!!.get(0).episodeList)
        episodeRecyclerView!!.setLayoutManager(CustomLinearManager(requireContext(), LinearLayoutManager.HORIZONTAL, false))
        episodeRecyclerView!!.setAdapter(adapter)

        //play button setting
        playButton.setOnClickListener { v ->
            // 点击回调跑在主线程：切季/切集的索引与数据不同步（某季 episodeList 为空）时
            // 老写法双 get 会越界 → 点播放直接闪退。安全取用，取不到只提示。
            val section = bangumi.sectionList?.getOrNull(selectedSection)
            val episode = section?.episodeList?.getOrNull(selectedEpisode)
            if (episode == null) {
                MsgUtil.showMsg("这一季还没有可播放的正片")
                return@setOnClickListener
            }
            Glide.get(requireContext()).clearMemory()
            //直接使用内置播放器（已去掉第三方播放器跳转逻辑）
            startActivity(PlayerApi.buildPlayerIntent(episode.toPlayerData()))
        }
        onFinishLoad()

        refreshReplies()
    }

    @SuppressLint("SetTextI18n")
    private fun getSectionChooseDialog(): Dialog {
        val bangumi = this.bangumi ?: return Dialog(requireContext())
        val choices = arrayOfNulls<String>(bangumi.sectionList!!.size)
        for (i in bangumi.sectionList!!.indices) {
            choices[i] = bangumi.sectionList!!.get(i).title
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setSingleChoiceItems(choices, selectedSection) { dialog, which ->
            selectedSection = which
            selectedEpisode = 0

            refreshReplies()
            val section = bangumi.sectionList!!.get(which)
            section_choose!!.setText((section.title ?: "") + " 点击切换")
            val adapter = episodeRecyclerView!!.getAdapter() as MediaEpisodeAdapter?
            if (adapter != null) {
                adapter.setData(bangumi.sectionList!!.get(which).episodeList)
                episodeRecyclerView!!.scrollToPosition(0)
            }
            episode_choose!!.setOnClickListener { getEposideChooseDialog().show() }
            dialog.dismiss()
        }
        this.dialog = builder.create()

        return this.dialog!!
    }

    private fun getEposideChooseDialog(): Dialog {
        val bangumi = this.bangumi ?: return Dialog(requireContext())
        val episodeList = bangumi.sectionList!!.get(selectedSection).episodeList

        val choices = arrayOfNulls<String>(episodeList!!.size)
        for (i in episodeList.indices) {
            val episode = episodeList.get(i)
            choices[i] = (episode.title ?: "") + "." + (episode.title_long ?: "")
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setSingleChoiceItems(choices, selectedEpisode) { dialog, which ->
            selectedEpisode = which
            refreshReplies()

            val adapter = episodeRecyclerView!!.getAdapter() as MediaEpisodeAdapter?
            if (adapter != null) {
                adapter.setSelectedItemIndex(which)
                episodeRecyclerView!!.scrollToPosition(which)
            }
            dialog.dismiss()
        }
        this.dialog = builder.create()

        return this.dialog!!
    }

    private fun refreshReplies() {
        val activity = getActivity()
        if (activity is VideoInfoActivity) {
            activity.setCurrentAid(bangumi!!.sectionList!!.get(selectedSection).episodeList!!.get(selectedEpisode).aid)
        }
    }

    fun onFinishLoad() {
        try {
            val activity: Activity = requireActivity()
            if (activity is VideoInfoActivity) {
                activity.crossFade(requireView())
            }
        } catch (ignored: Exception) {
        }
    }

    private fun formatNumber(num: Int): String {
        if (num >= 100000000) { // 亿
            return String.format("%.1f亿", num / 100000000.0)
        } else if (num >= 10000) { // 万
            return String.format("%.1f万", num / 10000.0)
        } else {
            return num.toString()
        }
    }
}
