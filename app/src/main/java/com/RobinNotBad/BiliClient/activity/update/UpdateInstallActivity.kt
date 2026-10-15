package com.RobinNotBad.BiliClient.activity.update

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.video.MediaEpisodeAdapter
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.model.Bangumi
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.StringUtil

import java.io.File

class UpdateInstallActivity : BaseActivity() {

    var path: String? = null
    var pathTv: TextView? = null
    var installBtn: Button? = null
    var installWayList: RecyclerView? = null
    var adapter: MediaEpisodeAdapter = MediaEpisodeAdapter(true)

    var selectedInstallWay: Int = 0

    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        path = getIntent().getStringExtra("path")
        if (path == null) {
            finish()
            return
        }

        TutorialHelper.showTutorialList(this, R.array.tutorial_update_install, 3)

        asyncInflate(R.layout.activity_update_install) { layoutView, resId ->

            pathTv = findViewById(R.id.path)
            installBtn = findViewById(R.id.install)
            installWayList = findViewById(R.id.install_way_list)

            val ptv = pathTv
            ptv!!.text = String.format(getString(R.string.text_update), path)
            StringUtil.setCopy(ptv, path)
            installWayList!!.layoutManager = CustomLinearManager(this, LinearLayoutManager.VERTICAL, false)
            val episodeList: MutableList<Bangumi.Episode> = ArrayList()
            for (i in installWays.indices) {
                val episode = Bangumi.Episode()
                episode.id = i.toLong()
                episode.title = wayIdToText[installWays[i]]
                if (episode.title == null)
                    throw RuntimeException("Mapping between ID and text is error")
                episodeList.add(episode)
            }
            adapter.setData(episodeList)
            adapter.setOnItemClickListener { pos -> this.selectedInstallWay = pos }
            installWayList!!.adapter = adapter
            selectedInstallWay = 0
            installBtn!!.setOnClickListener { view1 ->
                val str = installWays[selectedInstallWay]
                if ("system" == str) {
                    installBySystemInstaller()
                } else {
                    MsgUtil.showMsg("未知的安装方式")
                }
            }
        }
    }

    private fun checkRequestInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:" + packageName)
                startActivity(intent)
                return false
            }
        }
        return true
    }

    private fun installBySystemInstaller() {
        try {
            if (!checkRequestInstallPermission()) {
                MsgUtil.showMsg("没有授予请求安装应用权限")
                return
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            val contentUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".FileProvider", File(path))
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive")
            if (packageManager.canRequestPackageInstalls()) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "没有授予请求安装应用权限", Toast.LENGTH_SHORT).show()
            }
        } catch (th: Throwable) {
            Log.e("BiliClient", th.toString())
            MsgUtil.showMsg("未知错误: " + th.message)
        }
    }

    companion object {
        @JvmField
        val installWays: List<String> = listOf(
            "system"
        )

        @JvmField
        val wayIdToText: Map<String, String> = mapOf(
            "system" to "系统安装器"
        )
    }
}
