package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Objects
import java.util.Timer
import java.util.TimerTask
import java.util.zip.Inflater
import okhttp3.Response
import okio.BufferedSink
import okio.sink
import okio.buffer
import okio.Sink

class DownloadActivity : BaseActivity() {

    var progressView: View? = null
    var progressText: TextView? = null

    var rootPath: File? = null
    var downPath: File? = null
    var downFile: File? = null
    var link: String? = null
    var scrHeight = 0

    var dldText = ""
    var dldPercent = 0f

    var type = 0

    var finish = false

    var no_bili_headers = false

    val timer = Timer()
    val showText: TimerTask = object : TimerTask() {
        @SuppressLint("SetTextI18n")
        override fun run() {
            val viewHeight = (dldPercent * scrHeight).toInt()
            runOnUiThread {
                progressText!!.text = dldText + "\n" + (dldPercent * 100) + "%"
                val params = progressView!!.layoutParams
                params.height = viewHeight
                progressView!!.layoutParams = params
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)

        val intent = getIntent()

        type = intent.getIntExtra("type", 0)  //0=单个文件，1=视频，2=分页视频
        link = intent.getStringExtra("link")
        no_bili_headers = intent.getBooleanExtra("terminal", false)

        progressText = findViewById(R.id.progressText)
        progressView = findViewById(R.id.progressView)

        scrHeight = window_height

        if (!FileUtil.checkStoragePermission()) FileUtil.requestStoragePermission(this)

        timer.schedule(showText, 100, 100)
        CenterThreadPool.run(Runnable {
            if (type == 0) {
                rootPath = File(intent.getStringExtra("path"))
                if (!rootPath!!.exists()) rootPath!!.mkdirs()
                downFile = File(rootPath, FileUtil.getFileNameFromLink(link!!))
                download(link, downFile!!, "下载文件中", true)
            } else {
                val title = FileUtil.stringToFile(intent.getStringExtra("title") ?: "")

                rootPath = FileUtil.getVideoDownloadPath()

                if (type == 1) {
                    downPath = File(rootPath, title)
                    rootPath = downPath
                }
                if (type == 2) {
                    rootPath = File(rootPath, FileUtil.stringToFile(intent.getStringExtra("parent_title") ?: ""))
                    downPath = File(rootPath, title)
                }

                if (!downPath!!.exists()) downPath!!.mkdirs()

                val danmaku = intent.getStringExtra("danmaku")
                val cover = intent.getStringExtra("cover")
                val dmFile = File(downPath, "danmaku.xml")
                val coverFile = File(rootPath, "cover.png")
                val videoFile = File(downPath, "video.mp4")
                downdanmu(danmaku, dmFile)
                if (!coverFile.exists()) download(cover, coverFile, "下载封面", false)
                download(link, videoFile, "下载视频", true)
            }
        })

    }

    @SuppressLint("SetTextI18n")
    private fun download(url: String?, file: File, desc: String, exitOnFinish: Boolean) {
        dldText = desc
        try {
            val response = NetWorkUtil.get(url,
                    if (no_bili_headers) AppInfoApi.customHeaders else NetWorkUtil.webHeaders)
            if (!file.exists()) file.createNewFile()
            val inputStream: InputStream = response.body!!.byteStream()
            val fileOutputStream = FileOutputStream(file)
            var len: Int
            val bytes = ByteArray(1024 * 10)
            val TotalFileSize = response.body!!.contentLength()
            while (inputStream.read(bytes).also { len = it } != -1) {
                fileOutputStream.write(bytes, 0, len)
                val CompleteFileSize = file.length()
                dldPercent = 1.0f * CompleteFileSize / TotalFileSize
            }
            inputStream.close()
            fileOutputStream.close()
            if (exitOnFinish) {
                runOnUiThread { MsgUtil.showMsg("下载完成") }
                val timer = Timer()
                timer.schedule(object : TimerTask() {
                    override fun run() {
                        finish = true
                        finish()
                    }
                }, 200)
            }
            response.body!!.close()
            response.close()
        } catch (e: IOException) {
            runOnUiThread { MsgUtil.showMsg("下载失败") }
            e.printStackTrace()
            finish()
        }
    }


    private fun downdanmu(danmaku: String?, danmakuFile: File) {
        try {
            val response = NetWorkUtil.get(danmaku,
                    if (no_bili_headers) AppInfoApi.customHeaders else NetWorkUtil.webHeaders)
            var bufferedSink: BufferedSink? = null
            try {
                if (!danmakuFile.exists()) danmakuFile.createNewFile()
                val sink: Sink = danmakuFile.sink()
                val decompressBytes = decompress(response.body!!.bytes())//调用解压函数进行解压，返回包含解压后数据的byte数组
                bufferedSink = sink.buffer()
                bufferedSink.write(decompressBytes)//将解压后数据写入文件（sink）中
                bufferedSink.close()
            } catch (e: Exception) {
                e.printStackTrace()
                finish()
            } finally {
                if (bufferedSink != null) {
                    bufferedSink.close()
                }
            }
            if (response.body != null) response.body!!.close()
            response.close()
        } catch (e: IOException) {
            runOnUiThread { MsgUtil.showMsg("弹幕下载失败！") }
            finish()
            e.printStackTrace()
        }
    }


    companion object {
        @JvmStatic
        fun decompress(data: ByteArray): ByteArray {
            var output: ByteArray
            val decompresser = Inflater(true)//这个true是关键
            decompresser.reset()
            decompresser.setInput(data)
            val o = ByteArrayOutputStream(data.size)
            try {
                val buf = ByteArray(2048)
                while (!decompresser.finished()) {
                    val i = decompresser.inflate(buf)
                    o.write(buf, 0, i)
                }
                output = o.toByteArray()
            } catch (e: Exception) {
                output = data
                e.printStackTrace()
            } finally {
                try {
                    o.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            decompresser.end()
            return output
        }
    }

    override fun onDestroy() {
        timer.cancel()
        if (!finish) {
            if (type != 0 && downPath != null) FileUtil.deleteFolder(downPath!!)
            else if (downFile != null) downFile!!.delete()
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        finish()
    }
}
