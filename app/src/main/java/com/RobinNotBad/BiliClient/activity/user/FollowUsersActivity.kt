package com.RobinNotBad.BiliClient.activity.user

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.user.FollowGroupAdapter
import com.RobinNotBad.BiliClient.adapter.user.UserListAdapter
import com.RobinNotBad.BiliClient.api.FollowApi
import com.RobinNotBad.BiliClient.model.FollowTag
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil


//关注列表
//2023-07-22
//2024-05-01

class FollowUsersActivity : RefreshListActivity() {

    var mid: Long = 0
    var userList: ArrayList<UserInfo>? = null
    var adapter: UserListAdapter? = null
    var groupAdapter: FollowGroupAdapter? = null
    var mode: Int = 0
    var groupMode: Boolean = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mode = getIntent().getIntExtra("mode", 0)
        mid = getIntent().getLongExtra("mid", -1)

        if (mode < 0 || mode > 1 || mid == -1L) {
            finish()
            return
        }

        val currentUserMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0L)
        groupMode = mode == 0 && mid == currentUserMid && SharedPreferencesUtil.getBoolean("follow_group_mode", false)

        setPageName(if (mode == 0) "关注列表" else "粉丝列表")

        recyclerView!!.setHasFixedSize(true)

        userList = ArrayList()

        if (groupMode) {
            loadGroupMode()
        } else {
            loadNormalMode()
        }
    }

    private fun loadNormalMode() {
        val midLocal = mid
        val modeLocal = mode
        val userListLocal = userList
        CenterThreadPool.run(Runnable {
            try {
                val result = if (modeLocal == 0) FollowApi.getFollowingList(midLocal, page, userListLocal!!) else FollowApi.getFollowerList(midLocal, page, userListLocal!!)
                val adapter = UserListAdapter(this, userListLocal)
                this.adapter = adapter
                setOnLoadMoreListener { p -> continueLoading(p) }
                setRefreshing(false)
                setAdapter(adapter)

                if (result == 1) {
                    Log.e("debug", "到底了")
                    setBottom(true)
                }
            } catch (e: Exception) {
                if (e.message != null && (e.message!!.startsWith("22115") || e.message!!.startsWith("22118"))) {
                    finish()
                    MsgUtil.showMsg(e.message)
                } else {
                    loadFail(e)
                }
            }
        })
    }

    private fun loadGroupMode() {
        CenterThreadPool.run(Runnable {
            try {
                val tagList = FollowApi.getFollowTags()
                runOnUiThread {
                    val groupAdapter = FollowGroupAdapter(this)
                    this.groupAdapter = groupAdapter
                    groupAdapter.setOnGroupExpandListener(object : FollowGroupAdapter.OnGroupExpandListener {
                        override fun onGroupExpand(tagid: Int) {
                            loadGroupUsers(tagid)
                        }
                    })
                    setAdapter(groupAdapter)
                    for (tag in tagList) {
                        if (tag.count > 0) {
                            groupAdapter.addGroup(tag, ArrayList())
                        }
                    }
                    groupAdapter.notifyDataSetChanged()
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                if (e.message != null && (e.message!!.startsWith("22115") || e.message!!.startsWith("22118"))) {
                    finish()
                    MsgUtil.showMsg(e.message)
                } else {
                    loadFail(e)
                }
            }
        })
    }

    fun loadGroupUsers(tagid: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val tagUsers = ArrayList<UserInfo>()
                val result = FollowApi.getFollowTagUsers(tagid, 1, tagUsers)
                runOnUiThread {
                    groupAdapter!!.updateGroupUsers(tagid, tagUsers)
                }
                if (result == 0 && tagUsers.size == 20) {
                    loadMoreGroupUsers(tagid, tagUsers.size)
                }
            } catch (e: Exception) {
                Log.e("debug", "加载分组用户失败", e)
            }
        })
    }

    private fun loadMoreGroupUsers(tagid: Int, currentCount: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val p = (currentCount / 20) + 1
                val tagUsers = ArrayList<UserInfo>()
                val result = FollowApi.getFollowTagUsers(tagid, p, tagUsers)
                runOnUiThread {
                    groupAdapter!!.addGroupUsers(tagid, tagUsers)
                }
                if (result == 0 && tagUsers.size == 20) {
                    loadMoreGroupUsers(tagid, currentCount + tagUsers.size)
                }
            } catch (e: Exception) {
                Log.e("debug", "加载分组用户失败", e)
            }
        })
    }

    private fun continueLoading(p: Int) {
        if (groupMode) {
            setRefreshing(false)
            return
        }
        val midLocal = mid
        val modeLocal = mode
        val userListLocal = userList
        CenterThreadPool.run(Runnable {
            try {
                val list = ArrayList<UserInfo>()
                val result = if (modeLocal == 0) FollowApi.getFollowingList(midLocal, p, list) else FollowApi.getFollowerList(midLocal, p, list)
                Log.e("debug", "下一页")
                runOnUiThread {
                    userListLocal!!.addAll(list)
                    adapter!!.notifyItemRangeInserted(userListLocal.size - list.size, list.size)
                }
                if (result == 1) {
                    Log.e("debug", "到底了")
                    setBottom(true)
                }
                setRefreshing(false)
            } catch (e: Exception) {
                if (e.message != null && (e.message!!.startsWith("22115") || e.message!!.startsWith("22118"))) {
                    finish()
                    MsgUtil.showMsg(e.message)
                } else {
                    loadFail(e)
                }
            }
        })
    }
}
