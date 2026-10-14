package com.RobinNotBad.BiliClient.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.IBinder

import androidx.core.app.NotificationCompat

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.video.local.DownloadListActivity
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity
import com.RobinNotBad.BiliClient.api.PlayerApi
import com.RobinNotBad.BiliClient.helper.sql.DownloadSqlHelper
import com.RobinNotBad.BiliClient.model.DownloadSection
import com.RobinNotBad.BiliClient.model.PlayerData
import com.RobinNotBad.BiliClient.model.SubtitleLink
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONException

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.Objects
import java.util.Timer
import java.util.TimerTask
import java.util.zip.Inflater

import okhttp3.Response
import okio.BufferedSink
import okio.Okio
import okio.Sink

class DownloadService : Service() {
    val NOTIFICATION_CHANNEL_ID = "biliterminal_download"
    val FOREGROUND_ID = 1027
    var statusBuilder: NotificationCompat.Builder? = null
    var completionBuilder: NotificationCompat.Builder? = null
    var notifyManager: NotificationManager? = null

    private var exitMessage: String? = null

    private var toastTimer: Timer? = null
    private var notifyTimer: Timer? = null

    override fun onCreate() {
        super.onCreate()

        Logu.d("onCreate")

        notifyManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "哔哩终端下载服务",
                    NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "哔哩终端下载服务"
            channel.setSound(null, null)
            channel.enableVibration(false)

            notifyManager!!.createNotificationChannel(channel)
        }

        val intent = Intent(this, DownloadListActivity::class.java)
        @SuppressLint("UnspecifiedImmutableFlag")
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, 0)
        statusBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle("下载视频中")
                .setProgress(100, 0, false)
                .setContentIntent(pendingIntent)
                .setSound(null)
                .setVibrate(null)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        completionBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle("下载完成")
                .setContentIntent(pendingIntent)
                .setOngoing(false)
                .setSound(null)
                .setVibrate(null)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @SuppressLint("MutatingSharedPrefs")
    override fun onStartCommand(serviceIntent: Intent?, flags: Int, startId: Int): Int {
        Logu.d("onStartCommand")
        startForeground(FOREGROUND_ID, statusBuilder!!.build())

        exitCode = ERR_UNKNOWN
        startNotifyProgress()

        CenterThreadPool.run(Runnable {
            var failed = false
            var url_video: String?
            var url_danmaku: String?
            var url_audio: String?
            while (!failed && started) {
                val section_tmp = getFirst()
                if (section_tmp == null) break

                section = section_tmp

                // 获取视频链接
                try {
                    val data = section!!.toPlayerData()

                    // 如果是仅音频下载，使用DASH格式获取音频流
                    if (section!!.isAudioOnly()) {
                        PlayerApi.getVideoDash(data)
                        url_audio = if (section!!.audioUrl != null && section!!.audioUrl!!.isNotEmpty())
                            section!!.audioUrl
                        else
                            data.audioUrl
                        url_video = null // 仅音频模式不需要视频
                    } else {
                        PlayerApi.getVideo(data, true)
                        url_video = data.videoUrl
                        url_audio = null
                    }
                    url_danmaku = data.danmakuUrl
                } catch (e: JSONException) {
                    setState(section!!.id, "error")
                    notifyCompletion("下载链接获取失败：\n" + section!!.name_short, (section!!.id).toInt())
                    section = null
                    refreshDownloadList()
                    continue
                } catch (e: IOException) {
                    failed = true
                    exitCode = ERR_NETWORK
                    setState(section!!.id, "none")
                    continue
                }

                try {
                    setState(section!!.id, "downloading")
                    percent = 0f
                    refreshDownloadList()

                    var file_sign: File? = null

                    when (section!!.type) {
                        "video_single" -> { // 单集视频
                            val path_single = section!!.getPath()

                            file_sign = File(path_single, ".DOWNLOADING")
                            if (!file_sign.exists() && !file_sign.createNewFile()) {
                                failed = true
                                exitCode = ERR_FILE
                                continue
                            }

                            toastState("下载封面")
                            var result = downFile(section!!.url_cover, File(path_single, "cover.png"))
                            if (result != NORMAL) {
                                failed = true
                                exitCode = result
                                continue
                            }

                            // 仅音频模式不下载字幕和弹幕
                            if (!section!!.isAudioOnly()) {
                                toastState("下载字幕")
                                downSubtitles(section!!.aid, section!!.cid, path_single)

                                toastState("下载弹幕")
                                result = downDanmaku(url_danmaku!!, File(path_single, "danmaku.xml"))
                                if (result != NORMAL) {
                                    failed = true
                                    exitCode = result
                                    continue
                                }
                            }

                            // 根据下载类型选择下载内容
                            if (section!!.isAudioOnly()) {
                                toastState("下载音频")
                                result = downFile(url_audio, File(path_single, "audio.m4a"))
                                if (result != NORMAL) {
                                    failed = true
                                    exitCode = result
                                    continue
                                }
                            } else {
                                toastState("下载视频")
                                result = downFile(url_video, File(path_single, "video.mp4"))
                                if (result != NORMAL) {
                                    failed = true
                                    exitCode = result
                                    continue
                                }
                            }
                        }
                        "video_multi" -> { // 多集视频
                            val path_page = section!!.getPath()
                            val path_parent = path_page.parentFile

                            if (!path_page.exists() && !path_page.mkdirs()) {
                                failed = true
                                exitCode = ERR_FILE
                                continue
                            }

                            file_sign = File(path_page, ".DOWNLOADING")
                            if (!file_sign.exists() && !file_sign.createNewFile()) {
                                failed = true
                                exitCode = ERR_FILE
                                continue
                            }

                            toastState("下载封面")
                            val cover_multi = File(path_parent, "cover.png")
                            if (!cover_multi.exists()) {
                                val result0 = downFile(section!!.url_cover, cover_multi)
                                if (result0 != NORMAL) {
                                    failed = true
                                    exitCode = result0
                                    continue
                                }
                            }

                            // 仅音频模式不下载字幕和弹幕
                            if (!section!!.isAudioOnly()) {
                                toastState("下载字幕")
                                downSubtitles(section!!.aid, section!!.cid, path_page)

                                toastState("下载弹幕")
                                val result1 = downDanmaku(url_danmaku!!, File(path_page, "danmaku.xml"))
                                if (result1 != NORMAL) {
                                    failed = true
                                    exitCode = result1
                                    continue
                                }
                            }

                            // 根据下载类型选择下载内容
                            if (section!!.isAudioOnly()) {
                                toastState("下载音频")
                                val result2 = downFile(url_audio, File(path_page, "audio.m4a"))
                                if (result2 != NORMAL) {
                                    failed = true
                                    exitCode = result2
                                    continue
                                }
                            } else {
                                toastState("下载视频")
                                val result3 = downFile(url_video, File(path_page, "video.mp4"))
                                if (result3 != NORMAL) {
                                    failed = true
                                    exitCode = result3
                                    continue
                                }
                            }
                        }
                    }

                    notifyCompletion("下载成功：\n" + section!!.name_short, (section!!.id).toInt())

                    if (file_sign != null && file_sign.exists()) file_sign.delete()

                    deleteSection(section!!.id)

                    refreshLocalList()
                    section = null
                } catch (e: IOException) {
                    failed = true
                    exitCode = ERR_FILE
                    setState(section!!.id, "error")
                }
            }

            section = null
            refreshDownloadList()

            if (!failed) {
                exitCode = NORMAL
                exitMessage = "全部下载完成"
            } else when (exitCode) {
                ERR_NETWORK -> exitMessage = "下载失败，网络错误"
                ERR_JSON -> exitMessage = "下载失败，视频链接获取错误"
                ERR_FILE -> exitMessage = "下载失败，文件错误"
                ERR_DATABASE -> exitMessage = "下载失败，数据库错误"
                else -> exitMessage = "下载失败，未知错误"
            }

            stopSelf()
        })

        return Service.START_STICKY
    }

    private fun toastState(newState: String) {
        state = newState
        percent = 0f
        if (toastTimer != null) toastTimer!!.cancel()
    }

    private fun startNotifyProgress() {
        notifyTimer = Timer()
        notifyTimer!!.schedule(object : TimerTask() {
            override fun run() {
                if (section == null || notifyTimer == null) return

                statusBuilder!!.setContentText(state + "：" + section!!.name_short)
                statusBuilder!!.setProgress(100, (percent * 100).toInt(), false)
                notifyManager!!.notify(FOREGROUND_ID, statusBuilder!!.build())
            }
        }, 500, 500)
    }

    private fun notifyExit(content: String) {
        MsgUtil.showMsg(content)
        notifyManager!!.cancel(FOREGROUND_ID)
        completionBuilder!!.setContentTitle("下载结束")
        completionBuilder!!.setContentText(content)
        completionBuilder!!.setProgress(0, 0, false)
        notifyManager!!.notify(2, completionBuilder!!.build())
    }

    private fun notifyCompletion(content: String, id: Int) {
        MsgUtil.showMsg(content)
        completionBuilder!!.setContentText(content)
        notifyManager!!.notify(id % 100 + 100, completionBuilder!!.build())
    }

    private fun refreshDownloadList() {
        if (DownloadListActivity.weakRef != null && DownloadListActivity.weakRef!!.get() != null) {
            DownloadListActivity.weakRef!!.get()!!.refreshList(true)
        }
    }

    private fun refreshLocalList() {
        val instance = BiliTerminal.getInstanceActivityOnTop()
        if (instance is LocalListActivity && !instance.isDestroyed) (instance as LocalListActivity).refresh()
    }

    private fun downSubtitles(aid: Long, cid: Long, folder: File): Int {
        return try {
            val subtitleLinks = PlayerApi.getSubtitleLinks(aid, cid)
            if (subtitleLinks.size <= 1) return NORMAL

            val subtitleFolder = File(folder, "subtitles")
            if (!subtitleFolder.mkdirs()) return ERR_FILE
            for (subtitleLink in subtitleLinks) {
                if (subtitleLink != null && subtitleLink.id != -1L) {
                    val subtitleFile = File(subtitleFolder, subtitleLink.lang + ".json")
                    if (!subtitleFile.createNewFile()) return ERR_FILE
                    val result = downFile(subtitleLink.url, subtitleFile)
                    if (result != NORMAL) return result
                }
            }
            NORMAL
        } catch (e: IOException) {
            ERR_NETWORK
        } catch (e: JSONException) {
            ERR_JSON
        }
    }

    @Throws(IOException::class)
    private fun downFile(url: String?, file: File): Int {
        val response: Response
        try {
            response = NetWorkUtil.get(url)
        } catch (e: IOException) {
            return ERR_NETWORK
        }
        var inputStream: InputStream? = null
        var fileOutputStream: FileOutputStream? = null
        try {
            if (!file.exists() && !file.createNewFile()) return ERR_FILE else if (!file.delete() || !file.createNewFile()) return ERR_FILE

            inputStream = response.body()!!.byteStream()
            fileOutputStream = FileOutputStream(file)
            var len: Int
            val bytes = ByteArray(1024 * 10)
            val TotalFileSize = response.body()!!.contentLength()
            while (inputStream.read(bytes).also { len = it } != -1 && started) {
                fileOutputStream.write(bytes, 0, len)
                val CompleteFileSize = file.length()
                percent = (1.0f * CompleteFileSize / TotalFileSize).toFloat()
            }
            if (!started) return ERR_UNKNOWN
        } catch (e: IOException) {
            return ERR_FILE
        } finally {
            inputStream?.close()
            fileOutputStream?.close()
            response.body()?.close()
            response.close()
        }
        return NORMAL
    }

    @Throws(IOException::class)
    private fun downDanmaku(danmaku: String, danmakuFile: File): Int {
        val response: Response
        try {
            response = NetWorkUtil.get(danmaku)
        } catch (e: IOException) {
            return ERR_NETWORK
        }
        var bufferedSink: BufferedSink? = null
        try {
            if (!danmakuFile.exists() && !danmakuFile.createNewFile()) return ERR_FILE else if (!danmakuFile.delete() || !danmakuFile.createNewFile()) return ERR_FILE

            val sink: Sink = Okio.sink(danmakuFile)
            val decompressBytes = decompress(response.body()!!.bytes()) // 调用解压函数进行解压，返回包含解压后数据的byte数组
            bufferedSink = Okio.buffer(sink)
            bufferedSink.write(decompressBytes) // 将解压后数据写入文件（sink）中
            bufferedSink.close()
        } catch (e: IOException) {
            return ERR_FILE
        } finally {
            bufferedSink?.close()
            response.body()?.close()
            response.close()
        }
        return NORMAL
    }

    override fun onDestroy() {
        Logu.d("结束")

        started = false
        percent = -1f
        state = null

        if (toastTimer != null) toastTimer!!.cancel()
        toastTimer = null

        if (notifyTimer != null) notifyTimer!!.cancel()
        notifyTimer = null

        if (exitMessage == null) exitMessage = "下载服务已退出"

        Logu.d("退出下载服务")
        if (section != null) {
            val id = section!!.id
            val folder = section!!.getPath()
            section = null

            CenterThreadPool.run(Runnable {
                notifyExit(exitMessage!!)
                if (exitCode != NORMAL) {
                    setState(id, "none")
                    FileUtil.deleteFolder(folder)
                }
                refreshDownloadList()
            })
        }

        super.onDestroy()
    }

    companion object {
        @JvmField
        var started = false

        @JvmField
        var exitCode = 0

        @JvmField
        var percent = -1f

        @JvmField
        var state: String? = null

        @JvmField
        var section: DownloadSection? = null
        private var firstDown: Long = 0

        private const val NORMAL = 0
        private const val ERR_NETWORK = -1
        private const val ERR_JSON = -2
        private const val ERR_FILE = -3
        private const val ERR_DATABASE = -4
        private const val ERR_UNKNOWN = -7

        @JvmStatic
        fun decompress(data: ByteArray): ByteArray {
            var output: ByteArray
            val decompresser = Inflater(true) // 这个true是关键
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
                    decompresser.end()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return output
        }

        @JvmStatic
        fun getFirst(): DownloadSection? {
            var cursor: Cursor? = null
            var database: SQLiteDatabase? = null
            return try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.readableDatabase

                if (firstDown >= 0) cursor = database.rawQuery(
                        "select * from download where id=? limit 1",
                        arrayOf(firstDown.toString()))
                if (cursor == null) cursor = database.rawQuery(
                        "select * from download where state!=? limit 1", arrayOf("error"))

                firstDown = -1

                if (cursor == null || cursor.count == 0) return null

                cursor.moveToFirst()
                DownloadSection(cursor)
            } catch (e: Exception) {
                MsgUtil.err(e)
                null
            } finally {
                cursor?.close()
                database?.close()
            }
        }

        @JvmStatic
        fun getAll(): ArrayList<DownloadSection>? {
            var cursor: Cursor? = null
            var database: SQLiteDatabase? = null
            return try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.readableDatabase
                cursor = database.rawQuery("select * from download", null)
                if (cursor == null || cursor.count == 0) return null

                val list = ArrayList<DownloadSection>()
                while (cursor.moveToNext()) {
                    list.add(DownloadSection(cursor))
                }
                list
            } catch (e: Exception) {
                MsgUtil.err(e)
                ArrayList()
            } finally {
                cursor?.close()
                database?.close()
            }
        }

        @JvmStatic
        fun deleteSection(id: Long) {
            var database: SQLiteDatabase? = null
            try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.writableDatabase
                database.execSQL("delete from download where id=?", arrayOf<Any>(id))
            } catch (e: Exception) {
                MsgUtil.err(e)
            } finally {
                database?.close()
            }
        }

        @JvmStatic
        fun clear() {
            var database: SQLiteDatabase? = null
            try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.writableDatabase
                database.execSQL("delete from download")
            } catch (e: Exception) {
                MsgUtil.err(e)
            } finally {
                database?.close()
            }
        }

        @JvmStatic
        fun setState(id: Long, state: String) {
            var database: SQLiteDatabase? = null
            try {
                val helper = DownloadSqlHelper(BiliTerminal.context)
                database = helper.writableDatabase
                database.execSQL("update download set state=? where id=?", arrayOf<Any>(state, id))
            } catch (e: Exception) {
                MsgUtil.err(e)
            } finally {
                database?.close()
            }
        }

        @JvmStatic
        fun startDownload(title: String, aid: Long, cid: Long, cover: String?, qn: Int, downloadType: String,
                          audioUrl: String?) {
            CenterThreadPool.run(Runnable {
                var database: SQLiteDatabase? = null
                var cursor: Cursor? = null
                try {
                    val helper = DownloadSqlHelper(BiliTerminal.context)
                    database = helper.writableDatabase

                    cursor = database.rawQuery("select * from download where aid=? and cid=?",
                            arrayOf(aid.toString(), cid.toString()))
                    if (cursor != null && cursor!!.count > 0) {
                        MsgUtil.showMsg("该视频已在下载队列中")
                        return@Runnable
                    }
                    cursor?.close()

                    database.execSQL(
                            "insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                            arrayOf("video_single", "none", aid, cid, qn, title, "", GlideUtil.url(cover ?: ""),
                                    downloadType, audioUrl))

                    val path_single = FileUtil.getVideoDownloadPath(title, null)
                    path_single.mkdirs()

                    val file_sign = File(path_single, ".DOWNLOADING")
                    if (!file_sign.exists()) file_sign.createNewFile()

                    val msg = if ("audio_only" == downloadType) "已添加音频下载" else "已添加下载"
                    MsgUtil.showMsg(msg)

                    start(-1)
                } catch (e: Exception) {
                    MsgUtil.err(e)
                } finally {
                    cursor?.close()
                    database?.close()
                }
            })
        }

        @JvmStatic
        fun startDownload(parent: String, child: String, aid: Long, cid: Long, cover: String?, qn: Int,
                          downloadType: String, audioUrl: String?) {
            CenterThreadPool.run(Runnable {
                var database: SQLiteDatabase? = null
                var cursor: Cursor? = null
                try {
                    val helper = DownloadSqlHelper(BiliTerminal.context)
                    database = helper.writableDatabase

                    cursor = database.rawQuery("select * from download where aid=? and cid=?",
                            arrayOf(aid.toString(), cid.toString()))
                    if (cursor != null && cursor!!.count > 0) {
                        MsgUtil.showMsg("该视频已在下载队列中")
                        return@Runnable
                    }
                    cursor?.close()

                    database.execSQL(
                            "insert into download(type,state,aid,cid,qn,title,child,cover,download_type,audio_url) values(?,?,?,?,?,?,?,?,?,?)",
                            arrayOf("video_multi", "none", aid, cid, qn, parent, child, GlideUtil.url(cover ?: ""),
                                    downloadType, audioUrl))

                    val path_page = FileUtil.getVideoDownloadPath(parent, child)
                    path_page.mkdirs()

                    val file_sign = File(path_page, ".DOWNLOADING")
                    if (!file_sign.exists()) file_sign.createNewFile()

                    val msg = if ("audio_only" == downloadType) "已添加音频下载" else "已添加下载"
                    MsgUtil.showMsg(msg)

                    start(-1)
                } catch (e: Exception) {
                    MsgUtil.err(e)
                } finally {
                    cursor?.close()
                    database?.close()
                }
            })
        }

        @JvmStatic
        fun start(first: Long) {
            if (started) return
            started = true
            Logu.d("start")
            firstDown = first

            val context = BiliTerminal.context
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(Intent(context, DownloadService::class.java))
            else
                context.startService(Intent(context, DownloadService::class.java))
        }
    }
}
