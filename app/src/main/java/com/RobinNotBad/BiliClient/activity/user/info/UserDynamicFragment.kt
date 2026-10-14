package com.RobinNotBad.BiliClient.activity.user.info

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder
import com.RobinNotBad.BiliClient.adapter.dynamic.UserDynamicAdapter
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil

import java.util.ArrayList

//用户动态
//2023-09-30
//2024-05-03

class UserDynamicFragment : RefreshListFragment() {

    private var mid: Long = 0
    private var dynamicList: ArrayList<Dynamic>? = null
    private var adapter: UserDynamicAdapter? = null
    private var offset: Long = 0

    companion object {
        @JvmStatic
        fun newInstance(mid: Long): UserDynamicFragment {
            val fragment = UserDynamicFragment()
            val args = Bundle()
            args.putLong("mid", mid)
            fragment.setArguments(args)
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getArguments() != null) {
            mid = getArguments()!!.getLong("mid")
        }
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dynamicList = ArrayList()
        setOnLoadMoreListener { pageNum -> continueLoading() }

        CenterThreadPool.run(Runnable {
            try {
                val dynamicList = this.dynamicList!!
                val userInfo = UserInfoApi.getUserInfo(mid)
                if (userInfo == null) {
                    runOnUiThread(Runnable {
                        MsgUtil.showMsg("用户不存在")
                        requireActivity().finish()
                    })
                } else {
                    Log.e("debug", "获取到用户信息")

                    try {
                        offset = DynamicApi.getDynamicList(dynamicList, offset, mid, null)
                        bottom = offset == -1L
                        Log.e("debug", "获取到用户动态")
                    } catch (e: Exception) {
                        loadFail(e)
                    }

                    if (isAdded()) {
                        val adapter = UserDynamicAdapter(requireContext(), dynamicList, userInfo)
                        this.adapter = adapter
                        setAdapter(adapter)
                        setRefreshing(false)
                    }
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading() {
        CenterThreadPool.run(Runnable {
            try {
                val dynamicList = this.dynamicList!!
                val adapter = this.adapter!!
                val list = ArrayList<Dynamic>()
                offset = DynamicApi.getDynamicList(list, offset, mid, null)
                runOnUiThread(Runnable {
                    dynamicList.addAll(list)
                    adapter.notifyItemRangeInserted(dynamicList.size - list.size + 1, list.size)
                })
                bottom = offset == -1L
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    fun onDynamicRemove(position: Int) {
        try {
            val dynamicList = this.dynamicList
            val adapter = this.adapter
            if (dynamicList != null && adapter != null) {
                DynamicHolder.removeDynamicFromList(dynamicList, position, adapter)
            }
        } catch (ignored: Throwable) {
        }
    }
}
