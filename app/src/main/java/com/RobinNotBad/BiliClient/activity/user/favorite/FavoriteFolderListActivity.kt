package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle


import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.favorite.FavoriteFolderAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.model.FavoriteFolder
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList

/**
 * 收藏夹列表（长按视频页「收藏」按钮 / 个人中心「收藏」进来）。
 *
 * 页面结构：[提示条(按需)] [新建收藏夹] [视频收藏夹 …] [图文收藏夹]。
 * 视频收藏夹数据来自 v3 接口（旧 space 接口已失效，见 [FavoriteApi.getFavoriteFolders] 注释）；
 * 拉不到时顶部会显示原因提示并支持下拉重试，不会只剩一个「图文收藏夹」入口。
 */
class FavoriteFolderListActivity : RefreshListActivity() {

    private var adapter: FavoriteFolderAdapter? = null
    private var folderList: ArrayList<FavoriteFolder>? = null
    private var mid: Long = 0

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("收藏")

        mid = SharedPreferencesUtil.getLong("mid", 0)
        folderList = ArrayList()

        //接口偶发失败时可以直接下拉重试，不用退出重进
        setOnRefreshListener { loadFolders() }

        loadFolders()
    }

    private fun loadFolders() {
        CenterThreadPool.run(Runnable {
            val folderList = this.folderList
            if (folderList == null) {
                setRefreshing(false)
                return@Runnable
            }
            if (isDestroyed) return@Runnable

            var notice: String? = null
            try {
                folderList.clear()
                if (mid <= 0) {
                    //未登录：新建/编辑也做不了，只给提示，但「图文收藏夹」入口仍保留
                    notice = "尚未登录，登录后才能看到自己的视频收藏夹"
                } else {
                    folderList.addAll(FavoriteApi.getFavoriteFolders(mid))
                    if (folderList.isEmpty()) {
                        notice = "没有获取到视频收藏夹\n可能是登录已失效，或收藏夹都设成了私密\n下拉可以重试"
                        MsgUtil.showMsgLong("没有获取到视频收藏夹")
                    }
                }
            } catch (e: Exception) {
                folderList.clear()
                notice = "收藏夹加载失败：" + (e.message ?: "网络异常") + "\n下拉可以重试"
                report(e)
            }

            if (isDestroyed) return@Runnable

            val adapter = FavoriteFolderAdapter(this, folderList, mid, notice)
            this.adapter = adapter
            adapter.setOnCreateClickListener(object : FavoriteFolderAdapter.OnCreateClickListener {
                override fun onCreateClick() {
                    showCreateDialog()
                }
            })
            adapter.setOnLongClickListener(object : FavoriteFolderAdapter.OnLongClickListener {
                override fun onLongClick(position: Int) {
                    if (position < 0 || position >= folderList.size) return
                    val folder = folderList[position]
                    if (folder.mediaId == 0L) {
                        MsgUtil.showMsg("无法获取收藏夹信息，请稍后重试")
                        return
                    }
                    val intent = Intent(this@FavoriteFolderListActivity, FavoriteFolderEditActivity::class.java)
                    intent.putExtra("mediaId", folder.mediaId)
                    intent.putExtra("title", folder.name)
                    intent.putExtra("intro", "")
                    intent.putExtra("isDefault", folder.isDefault)
                    startActivityForResult(intent, 1)
                }
            })
            setAdapter(adapter)
            setRefreshing(false)
        })
    }

    private fun showCreateDialog() {
        val intent = Intent(this, FavoriteFolderCreateActivity::class.java)
        startActivityForResult(intent, 2)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 1 || requestCode == 2) && resultCode == RESULT_OK) {
            loadFolders()
        }
    }
}
