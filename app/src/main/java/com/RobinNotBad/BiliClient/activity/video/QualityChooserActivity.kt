package com.RobinNotBad.BiliClient.activity.video

import android.os.Bundle
import android.view.View
import android.widget.TextView

import androidx.annotation.Nullable
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.TerminalContext

import java.util.ArrayList
import java.util.Arrays

class QualityChooserActivity : BaseActivity() {

    var qns: IntArray? = null
    var isAudioOnlyOption = false

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_simple_list)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        findViewById<View>(R.id.top).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        (findViewById<View>(R.id.pageName) as TextView).text = "请选择清晰度"

        val aid = getIntent().getLongExtra("aid", 0)
        val bvid = getIntent().getStringExtra("bvid")

        TerminalContext.getInstance().getVideoInfoByAidOrBvId(aid, bvid).observe(this) { result ->
            result.onSuccess { videoInfo ->

                val adapter = QualityChooseAdapter(this)
                val page = getIntent().getIntExtra("page", 0)
                CenterThreadPool.run(Runnable {
                    // 获取清晰度列表
                    try {
                        val playerData = videoInfo.toPlayerData(page)
                        PlayerApi.getVideo(playerData, true)
                        qns = playerData.qnValueList

                        // 在清晰度列表末尾添加"仅音频"选项
                        val qualityList = ArrayList(listOf(*(playerData.qnStrList ?: arrayOfNulls(0))))
                        qualityList.add("仅音频")
                        isAudioOnlyOption = true

                        runOnUiThread { adapter.setNameList(qualityList.filterNotNull()) }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.showMsg("清晰度列表获取失败！") }
                        e.printStackTrace()
                    }
                })
                adapter.setOnItemClickListener { position ->
                    val qns = this.qns ?: return@setOnItemClickListener

                    if (isAudioOnlyOption && position == qns.size) {
                        // 获取DASH格式数据
                        CenterThreadPool.run(Runnable {
                            try {
                                val playerData = videoInfo.toPlayerData(page)
                                playerData.qn = qns[0] // 使用最高清晰度获取音频
                                PlayerApi.getVideoDash(playerData)

                                if (playerData.audioUrl == null || playerData.audioUrl!!.isEmpty()) {
                                    runOnUiThread { MsgUtil.showMsg("该视频没有可用的音频流") }
                                    return@Runnable
                                }

                                PlayerApi.startDownloadingAudioOnly(videoInfo, page, qns[0], playerData.audioUrl)
                                runOnUiThread { finish() }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    MsgUtil.showMsg("获取音频信息失败：" + e.message)
                                    e.printStackTrace()
                                }
                            }
                        })
                    } else {
                        // 选择了普通清晰度
                        val qn = qns[position]
                        PlayerApi.startDownloading(videoInfo, page, qn)
                        finish()
                    }
                }

                recyclerView.layoutManager = CustomLinearManager(this)
                recyclerView.adapter = adapter
            }
        }

    }
}
