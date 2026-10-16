package com.RobinNotBad.BiliClient.activity.video.info

import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.RecommendApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool

//视频下推荐页面
//2023-11-26

class VideoRcmdFragment : RefreshListFragment() {

    private var aid: Long = 0

    companion object {
        @JvmStatic
        fun newInstance(aid: Long): VideoRcmdFragment {
            val fragment = VideoRcmdFragment()
            val args = Bundle()
            args.putLong("aid", aid)
            fragment.setArguments(args)
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getArguments() != null) {
            aid = getArguments()!!.getLong("aid")
        }
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.e("debug-av号", aid.toString())

        CenterThreadPool.supplyAsyncWithLiveData { RecommendApi.getRelated(aid) }
            .observe(getViewLifecycleOwner()) { result ->
                result.onSuccess { videoList ->
                    val adapter = VideoCardAdapter(requireContext(), videoList)
                    adapter.feedLayout = ListLayoutUtil.isDualColumnVideo()
                    video_card_layout = true
                    setAdapter(adapter)
                    setRefreshing(false)
                }.onFailure { error -> loadFail(error) }
            }
    }

}
