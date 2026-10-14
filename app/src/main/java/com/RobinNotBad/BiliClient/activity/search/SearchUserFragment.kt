package com.RobinNotBad.BiliClient.activity.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.adapter.user.UserListAdapter
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import org.json.JSONArray

import java.util.ArrayList

class SearchUserFragment : SearchFragment() {

    private var userInfoList: MutableList<UserInfo> = ArrayList()
    private var userInfoAdapter: UserListAdapter? = null

    companion object {
        @JvmStatic
        fun newInstance(): SearchUserFragment {
            return SearchUserFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }


    @SuppressLint("SetTextI18n")
    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        userInfoList = ArrayList()
        userInfoAdapter = UserListAdapter(requireContext(), userInfoList)
        setAdapter(userInfoAdapter!!)

        setOnRefreshListener { refreshInternal() }
        setOnLoadMoreListener(OnLoadMoreListener { page: Int -> continueLoading(page) })
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            Log.e("debug", "加载下一页")
            try {
                val result = SearchApi.searchType(keyword, page, "bili_user") as JSONArray?
                if (result != null) {
                    if (page == 1) showEmptyView(false)
                    val list: MutableList<UserInfo> = ArrayList()
                    SearchApi.getUsersFromSearchResult(result, list)
                    if (list.size == 0) setBottom(true)
                    CenterThreadPool.runOnUiThread(Runnable {
                        val lastSize = userInfoList.size
                        userInfoList.addAll(list)
                        userInfoAdapter!!.notifyItemRangeInserted(lastSize + 1, userInfoList.size - lastSize)
                    })
                } else setBottom(true)
            } catch (e: Exception) {
                loadFail(e)
            }
            setRefreshing(false)
        })
    }

    override fun refreshInternal() {
        CenterThreadPool.runOnUiThread(Runnable {
            page = 1
            if (this.userInfoAdapter == null)
                this.userInfoAdapter = UserListAdapter(this.requireContext(), this.userInfoList)
            val size_old = this.userInfoList.size
            this.userInfoList.clear()
            if (size_old != 0) this.userInfoAdapter!!.notifyItemRangeRemoved(0, size_old)
            CenterThreadPool.run(Runnable { continueLoading(page) })
        })
    }


}
