package com.RobinNotBad.BiliClient.activity.message

import android.content.Intent
import android.os.Bundle
import android.util.Pair

import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.message.NoticeAdapter
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.model.MessageCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool

class NoticeActivity : RefreshListActivity() {
    private var messageList: MutableList<MessageCard>? = null
    private var noticeAdapter: NoticeAdapter? = null
    private var cursor: MessageCard.Cursor? = null
    private var pageType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName("详情")

        val intent = getIntent()
        pageType = intent.getStringExtra("type")
        messageList = ArrayList()

        CenterThreadPool.run(Runnable {
            try {
                var pair: Pair<MessageCard.Cursor?, MutableList<MessageCard>>? = null
                when (pageType) {
                    "like" -> {
                        pair = MessageApi.getLikeMsg(0L, 0L)
                        cursor = pair.first
                        messageList = pair.second
                    }
                    "reply" -> {
                        pair = MessageApi.getReplyMsg(0L, 0L)
                        cursor = pair.first
                        messageList = pair.second
                    }
                    "at" -> {
                        pair = MessageApi.getAtMsg(0L, 0L)
                        cursor = pair.first
                        messageList = pair.second
                    }
                    "system" -> {
                        messageList = MessageApi.getSystemMsg()
                    }
                }

                val adapterLocal = NoticeAdapter(this, messageList)
                noticeAdapter = adapterLocal
                runOnUiThread {
                    setAdapter(adapterLocal)
                    setRefreshing(false)
                    setOnLoadMoreListener { i -> continueLoading(i) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        })
    }

    private fun continueLoading(i: Int) {
        CenterThreadPool.run(Runnable {
            try {
                val lastSize = messageList!!.size
                var pair: Pair<MessageCard.Cursor?, MutableList<MessageCard>>? = null
                when (pageType) {
                    "like" -> {
                        pair = MessageApi.getLikeMsg(cursor!!.id, cursor!!.time)
                        cursor = pair.first
                        messageList!!.addAll(pair.second)
                    }
                    "reply" -> {
                        pair = MessageApi.getReplyMsg(cursor!!.id, cursor!!.time)
                        cursor = pair.first
                        messageList!!.addAll(pair.second)
                    }
                    "at" -> {
                        pair = MessageApi.getAtMsg(cursor!!.id, cursor!!.time)
                        cursor = pair.first
                        messageList!!.addAll(pair.second)
                    }
                    "system" -> {
                        messageList = MessageApi.getSystemMsg()
                    }
                }
                runOnUiThread { noticeAdapter!!.notifyItemRangeInserted(lastSize, messageList!!.size - lastSize) }
                bottom = cursor!!.is_end
                setRefreshing(false)
            } catch (e: Exception) {
                e.printStackTrace()
                page--
                setRefreshing(false)
            }
        })
    }
}
