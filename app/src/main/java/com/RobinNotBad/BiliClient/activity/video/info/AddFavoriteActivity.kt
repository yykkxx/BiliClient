package com.RobinNotBad.BiliClient.activity.video.info

import android.content.Intent
import android.os.Bundle

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.favorite.FolderChooseAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList

//添加收藏
//2023-08-28

class AddFavoriteActivity : RefreshListActivity() {
    var folderAdapter: FolderChooseAdapter? = null
    val folderList = ArrayList<String>()
    val stateList = ArrayList<Boolean>()
    val fidList = ArrayList<Long>()
    var aid: Long = 0
    val RESULT_ADDED = 1
    val RESULT_DELETED = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("添加收藏")

        val intent = getIntent()
        aid = intent.getLongExtra("aid", 0)

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L) == 0L) {
            MsgUtil.showMsg("还没有登录喵~")
            finish()
            return
        }

        CenterThreadPool.run(Runnable {
            try {
                FavoriteApi.getFavoriteState(aid, folderList, fidList, stateList)

                folderAdapter = FolderChooseAdapter(this, folderList, fidList, stateList, aid)

                setAdapter(folderAdapter!!)

                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    override fun finish() {
        val folderAdapter = this.folderAdapter
        if (folderAdapter != null) {
            if (folderAdapter.added) {
                setResult(RESULT_ADDED)
            } else if (folderAdapter.isAllDeleted()) {
                setResult(RESULT_DELETED)
            }
        }
        super.finish()
    }

    override fun onDestroy() {
        val folderAdapter = this.folderAdapter
        if (folderAdapter != null) {
            if (SharedPreferencesUtil.getBoolean("fav_notice", true)) {
                if (folderAdapter.added) MsgUtil.showMsg("添加成功")
                else if (folderAdapter.isAllDeleted()) MsgUtil.showMsg("删除成功")
                else if (folderAdapter.changed) MsgUtil.showMsg("更改成功")
            }
        }

        super.onDestroy()

    }
}
