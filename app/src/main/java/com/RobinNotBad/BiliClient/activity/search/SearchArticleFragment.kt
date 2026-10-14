package com.RobinNotBad.BiliClient.activity.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.adapter.article.ArticleCardAdapter
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import org.json.JSONArray

import java.util.ArrayList

class SearchArticleFragment : SearchFragment() {

    private var articleCardList: ArrayList<ArticleCard> = ArrayList()
    private var articleCardAdapter: ArticleCardAdapter? = null

    companion object {
        @JvmStatic
        fun newInstance(): SearchArticleFragment {
            return SearchArticleFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        articleCardList = ArrayList()
        articleCardAdapter = ArticleCardAdapter(requireContext(), articleCardList)
        setAdapter(articleCardAdapter!!)

        setOnRefreshListener { refreshInternal() }
        setOnLoadMoreListener(OnLoadMoreListener { page: Int -> continueLoading(page) })
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run(Runnable {
            Log.e("debug", "加载下一页")
            try {
                val result = SearchApi.searchType(keyword, page, "article") as JSONArray?
                if (result != null) {
                    if (page == 1) showEmptyView(false)
                    val list = ArrayList<ArticleCard>()
                    SearchApi.getArticlesFromSearchResult(result, list)
                    if (list.size == 0) setBottom(true)
                    CenterThreadPool.runOnUiThread(Runnable {
                        val lastSize = articleCardList.size
                        articleCardList.addAll(list)
                        articleCardAdapter!!.notifyItemRangeInserted(lastSize + 1, articleCardList.size - lastSize)
                    })
                } else setBottom(true)
            } catch (e: Exception) {
                report(e)
            }
            setRefreshing(false)
        })
    }

    override fun refreshInternal() {
        CenterThreadPool.runOnUiThread(Runnable {
            page = 1
            if (this.articleCardAdapter == null)
                this.articleCardAdapter = ArticleCardAdapter(this.requireContext(), this.articleCardList)
            val size_old = this.articleCardList.size
            this.articleCardList.clear()
            if (size_old != 0) this.articleCardAdapter!!.notifyItemRangeRemoved(0, size_old)
            CenterThreadPool.run(Runnable { continueLoading(page) })
        })
    }
}
