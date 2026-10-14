package com.RobinNotBad.BiliClient.activity.user.info

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.activity.base.RefreshListFragment
import com.RobinNotBad.BiliClient.adapter.article.ArticleCardAdapter
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import java.util.ArrayList

//用户专栏
//2023-09-30
//2024-05-03

class UserArticleFragment : RefreshListFragment() {

    private var mid: Long = 0
    private var articleList: ArrayList<ArticleCard>? = null
    private var adapter: ArticleCardAdapter? = null

    companion object {
        @JvmStatic
        fun newInstance(mid: Long): UserArticleFragment {
            val fragment = UserArticleFragment()
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

        articleList = ArrayList()
        setOnLoadMoreListener { pageNum -> continueLoading(pageNum) }

        CenterThreadPool.run(Runnable {
            try {
                val articleList = this.articleList!!
                bottom = UserInfoApi.getUserArticles(mid, page, articleList) == 1
                if (isAdded()) {
                    val adapter = ArticleCardAdapter(requireContext(), articleList)
                    this.adapter = adapter
                    setAdapter(adapter)
                    setRefreshing(false)
                    if (bottom && articleList.isEmpty()) showEmptyView()
                }
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val articleList = this.articleList!!
                val adapter = this.adapter!!
                val list = ArrayList<ArticleCard>()
                val result = UserInfoApi.getUserArticles(mid, page, list)
                if (result != -1) {
                    Log.e("debug", "下一页")
                    if (isAdded()) requireActivity().runOnUiThread(Runnable {
                        articleList.addAll(list)
                        adapter.notifyItemRangeInserted(articleList.size - list.size, list.size)
                    })
                    if (result == 1) {
                        Log.e("debug", "到底了")
                        bottom = true
                    }
                }
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }
}
