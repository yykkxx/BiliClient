package com.RobinNotBad.BiliClient.activity.user.favorite

import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.article.OpusAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.model.Opus
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import java.util.ArrayList

class FavouriteOpusListActivity : RefreshListActivity() {
    var list: ArrayList<Opus>? = null
    var adapter: OpusAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("图文收藏夹")

        list = ArrayList()

        CenterThreadPool.run(Runnable {
            try {
                val list = this.list!!
                FavoriteApi.getFavouriteOpus(list, page)
                val adapter = OpusAdapter(this, list)
                this.adapter = adapter
                Log.e("", "amount:" + list.size)
                setAdapter(adapter)
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })

        setOnLoadMoreListener { pageNum -> loadMore(pageNum) }
    }

    fun loadMore(page: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val list = this.list!!
                val adapter = this.adapter!!
                val lastSize = list.size
                setBottom(!FavoriteApi.getFavouriteOpus(list, page))
                runOnUiThread(Runnable { adapter.notifyItemRangeInserted(lastSize, list.size - lastSize) })
                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }

        })
    }
}
