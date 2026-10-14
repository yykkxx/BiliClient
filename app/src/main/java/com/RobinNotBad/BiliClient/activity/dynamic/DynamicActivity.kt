package com.RobinNotBad.BiliClient.activity.dynamic

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Nullable

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicAdapter
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder
import com.RobinNotBad.BiliClient.api.DynamicApi
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList
import java.util.HashMap
import java.util.regex.Pattern

//动态页面
//2023-09-17

open class DynamicActivity : RefreshMainActivity() {

    private var dynamicList: ArrayList<Dynamic>? = null
    private var dynamicAdapter: DynamicAdapter? = null
    private var recentUpList: List<DynamicApi.UpInfo>? = null
    private var offset: Long = 0
    private var firstRefresh = true
    private var type = "all"
    private val typeNameMap = mapOf(
            "全部" to "all",
            "视频投稿" to "video",
            "追番" to "pgc",
            "专栏" to "article"
    )

    @JvmField
    val selectTypeLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null && data.getStringExtra("item") != null) {
            val newType = typeNameMap[data.getStringExtra("item")]
            if (newType != null) {
                if (isRefreshing) {
                    MsgUtil.showMsg("还在加载中OvO")
                } else {
                    type = newType
                    setRefreshing(true)
                    refreshDynamic()
                }
            }
        }
    }

    @JvmField
    val writeDynamicLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.resultCode
        val data = result.data
        if (code == RESULT_OK && data != null) {
            val text = data.getStringExtra("text") ?: ""
            CenterThreadPool.run(Runnable {
                try {
                    var dynId: Long
                    val atUids = HashMap<String, Long>()
                    val pattern = Pattern.compile("@(\\S+)\\s")
                    val matcher = pattern.matcher(text)
                    while (matcher.find()) {
                        val matchedString = matcher.group(1)
                        var uid: Long
                        if (DynamicApi.mentionAtFindUser(matchedString).also { uid = it } != -1L) {
                            atUids[matchedString] = uid
                        }
                    }
                    dynId = if (atUids.isEmpty()) {
                        DynamicApi.publishTextContent(text)
                    } else {
                        DynamicApi.publishTextContent(text, atUids)
                    }
                    if (dynId != -1L) {
                        runOnUiThread(Runnable { MsgUtil.showMsg("发送成功~") })
                        CenterThreadPool.run(Runnable {
                            try {
                                val dynamic = DynamicApi.getDynamic(dynId)
                                val list = dynamicList!!
                                list.add(0, dynamic)
                                runOnUiThread(Runnable {
                                    if (type == "all") {
                                        dynamicAdapter!!.notifyItemInserted(0)
                                        dynamicAdapter!!.notifyItemRangeChanged(0, list.size)
                                    }
                                })
                            } catch (e: Exception) {
                                MsgUtil.err(e)
                            }
                        })
                    } else {
                        runOnUiThread(Runnable { MsgUtil.showMsg("发送失败") })
                    }
                } catch (e: Exception) {
                    runOnUiThread(Runnable { MsgUtil.err(e) })
                }
            })
        }
    }

    companion object {
        /**
         * 该方法务必在Activity的onStart生命周期之前调用,否则系统底层会抛异常!!!
         *
         * @param activity
         * @return
         */
        @JvmStatic
        fun getRelayDynamicLauncher(activity: BaseActivity): ActivityResultLauncher<Intent> {
            return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val code = result.resultCode
                val data = result.data
                if (code == RESULT_OK && data != null) {
                    var text = data.getStringExtra("text") ?: ""
                    if (TextUtils.isEmpty(text)) text = "转发动态"
                    val dynamicId = data.getLongExtra("dynamicId", -1)
                    val finalText = text
                    CenterThreadPool.run(Runnable {
                        try {
                            var dynId: Long
                            val atUids = HashMap<String, Long>()
                            val pattern = Pattern.compile("@(\\S+)\\s")
                            val matcher = pattern.matcher(finalText)
                            while (matcher.find()) {
                                val matchedString = matcher.group(1)
                                var uid: Long
                                if (DynamicApi.mentionAtFindUser(matchedString).also { uid = it } != -1L) {
                                    atUids[matchedString] = uid
                                }
                            }
                            dynId = DynamicApi.relayDynamic(finalText, if (atUids.isEmpty()) null else atUids, dynamicId)
                            if (dynId != -1L) {
                                activity.runOnUiThread(Runnable { MsgUtil.showMsg("转发成功~") })
                            } else {
                                activity.runOnUiThread(Runnable { MsgUtil.showMsg("转发失败") })
                            }
                        } catch (e: Exception) {
                            activity.runOnUiThread(Runnable { MsgUtil.err(e) })
                        }
                    })
                }
            }
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setMenuClick()
        Log.e("debug", "进入动态页")

        setOnRefreshListener { refreshDynamic() }
        setOnLoadMoreListener { page -> addDynamic(type) }

        setPageName("动态")

        TutorialHelper.showTutorialList(this, R.array.tutorial_dynamic, 6)

        loadRecentUpList()
        refreshDynamic()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshDynamic() {
        Log.e("debug", "刷新")
        if (firstRefresh) {
            dynamicList = ArrayList()
        } else {
            offset = 0
            bottom = false
            dynamicList!!.clear()
            dynamicAdapter!!.notifyDataSetChanged()
        }

        loadRecentUpList()
        addDynamic(type, true)
    }

    private fun addDynamic(type: String) {
        addDynamic(type, false)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun addDynamic(type: String, refresh: Boolean) {
        Log.e("debug", "加载下一页")
        CenterThreadPool.run(Runnable {
            try {
                val list = ArrayList<Dynamic>()
                offset = DynamicApi.getDynamicList(list, offset, 0L, type)
                bottom = offset == -1L
                setRefreshing(false)

                runOnUiThread(Runnable {
                    val dynList = dynamicList!!
                    val adapter = dynamicAdapter
                    dynList.addAll(list)
                    if (firstRefresh) {
                        firstRefresh = false
                        dynamicAdapter = DynamicAdapter(this, dynList, recyclerView!!, recentUpList)
                        setAdapter(dynamicAdapter!!)
                    } else {
                        if (refresh) {
                            adapter!!.notifyDataSetChanged()
                        } else {
                            val offset = if (showRecentUp()) 2 else 1
                            adapter!!.notifyItemRangeInserted(dynList.size - list.size + offset, list.size)
                        }
                    }
                    if (refresh) {
                        SharedPreferencesUtil.putInt(SharedPreferencesUtil.DYNAMIC_UPDATE_NUM, 0)
                    }
                })

            } catch (e: Exception) {
                loadFail(e)
            }
        })
    }

    private fun loadRecentUpList() {
        CenterThreadPool.run(Runnable {
            try {
                recentUpList = DynamicApi.getRecentUpList()
                val upList = recentUpList
                runOnUiThread(Runnable {
                    val adapter = dynamicAdapter
                    val dynList = dynamicList
                    if (adapter != null) {
                        adapter.recentUpList = upList
                        val shouldShow = showRecentUp()
                        val currentItemCount = adapter.itemCount
                        val newItemCount = (dynList?.size?.plus(1) ?: 1) + (if (shouldShow) 1 else 0)
                        if (currentItemCount != newItemCount) {
                            if (shouldShow) {
                                adapter.notifyItemInserted(1)
                            } else {
                                adapter.notifyItemRemoved(1)
                            }
                        } else if (shouldShow) {
                            adapter.notifyItemChanged(1)
                        }
                    }
                })
            } catch (e: Exception) {
                recentUpList = null
            }
        })
    }

    private fun showRecentUp(): Boolean {
        return SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.RECENT_UP_DISPLAY_ENABLE, true)
                && recentUpList != null && recentUpList!!.isNotEmpty()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, @Nullable data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DynamicHolder.GO_TO_INFO_REQUEST && resultCode == RESULT_OK) {
            try {
                if (data != null && !isRefreshing) {
                    val adapterPosition = data.getIntExtra("position", 0)
                    val offset = if (showRecentUp()) 2 else 1
                    val realPosition = adapterPosition - offset
                    val dynList = dynamicList
                    if (dynList != null && realPosition >= 0 && realPosition < dynList.size) {
                        DynamicHolder.removeDynamicFromList(dynList, realPosition, dynamicAdapter!!, showRecentUp())
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }
}
