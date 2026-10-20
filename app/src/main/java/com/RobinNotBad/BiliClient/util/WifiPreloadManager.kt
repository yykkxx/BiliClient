package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import com.RobinNotBad.BiliClient.BiliTerminal

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 实验室功能（#5）：WiFi 下进入视频详情页时预加载视频数据。
 *
 * 工作方式：
 * 1. [preload]：在视频详情页加载完成时调用（仅 WiFi + 开关打开），
 *    用 OkHttp 把视频流 URL 的前 [PRELOAD_BYTES] 字节拉到
 *    cacheDir/preload/{aid}_{cid}.part，并顺手把 URL 存进内存表。
 *    —— 相当于提前完成 DNS + TLS 握手 + 首段数据传输，点播放时秒开。
 * 2. [proxyUrlFor]：播放器启动前调用。若该视频已有预加载文件，则返回
 *    本地代理地址 http://127.0.0.1:{port}/p/{aid}_{cid}，ijkplayer 访问本地代理：
 *    - 请求范围落在预加载文件内 → 直接回本地字节（零网络、极快）；
 *    - 超出预加载范围 → 代理继续向源站发 Range 请求并把数据流回播放器
 *      （边转发边写入预加载文件，之后 seek 回已缓冲区域仍然走本地）。
 * 3. 播放结束/退出时调用 [stopProxy] 收掉服务器线程；[cleanup] 清理预加载文件。
 *
 * 默认关闭（lab_wifi_preload=false），关闭时所有方法都是空操作，
 * 播放行为与旧版本完全一致。
 */
object WifiPreloadManager {

    /** 实验室设置键：WiFi 下进入详情页自动预加载视频 */
    const val PREF_WIFI_PRELOAD = "lab_wifi_preload"

    /** 预加载的字节数：4MB —— 覆盖低清晰度整段 / 高清晰度开头约 30~60 秒 */
    private const val PRELOAD_BYTES = 4 * 1024 * 1024

    /** 预加载文件 30 分钟后过期（URL 本身也会过期，别留太久） */
    private const val EXPIRE_MS = 30L * 60 * 1000

    private class PreloadEntry(
        @JvmField val file: File,
        @JvmField val remoteUrl: String,
        @JvmField val referer: String,
        @JvmField val createdAt: Long
    )

    /** aid_cid -> 预加载条目 */
    private val entries = ConcurrentHashMap<String, PreloadEntry>()

    /** 正在预加载的 key，防止重复拉 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val serverRunning = AtomicBoolean(false)
    private val nextClientSeq = AtomicInteger(0)

    // ------------------------ 开关与网络判断 ------------------------

    @JvmStatic
    fun isEnabled(): Boolean =
        SharedPreferencesUtil.getBoolean(PREF_WIFI_PRELOAD, false)

    /** 当前是否在 WiFi（不含计费热点判断，够用即可） */
    @JvmStatic
    fun isOnWifi(): Boolean {
        return try {
            val cm = BiliTerminal.context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------ 预加载 ------------------------

    /**
     * 进入视频详情页时调用：WiFi + 开关打开才真正拉数据。
     * 在 IO 线程执行，调用方无需再切线程。
     */
    @JvmStatic
    fun preload(aid: Long, cid: Long, videoUrl: String?) {
        if (!isEnabled() || !isOnWifi()) return
        if (aid <= 0 || cid <= 0) return
        if (videoUrl.isNullOrEmpty()) return
        if (!videoUrl.startsWith("http")) return

        val key = keyOf(aid, cid)
        if (!inFlight.add(key)) return  //已在拉

        CenterThreadPool.run(Runnable {
            try {
                val existing = entries[key]
                if (existing != null && existing.file.exists()
                        && System.currentTimeMillis() - existing.createdAt < EXPIRE_MS
                        && existing.remoteUrl == videoUrl) {
                    return@Runnable  //还新鲜，不重复拉
                }

                val dir = File(BiliTerminal.context.cacheDir, "preload")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$key.part")

                val request = okhttp3.Request.Builder()
                        .url(videoUrl)
                        .header("Range", "bytes=0-" + (PRELOAD_BYTES - 1))
                        .header("Referer", "https://www.bilibili.com/")
                        .header("User-Agent", NetWorkUtil.USER_AGENT_WEB)
                        .header("Cookie", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
                        .build()

                val response = NetWorkUtil.getOkHttpInstance().newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Logu.w("preload", "预加载请求失败 code=" + resp.code)
                        return@Runnable
                    }
                    val body = resp.body ?: return@Runnable
                    var downloaded = 0L
                    file.outputStream().use { out ->
                        val src = body.byteStream()
                        val buf = ByteArray(32 * 1024)
                        while (downloaded < PRELOAD_BYTES) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                        }
                    }
                    if (downloaded > 0) {
                        entries[key] = PreloadEntry(
                                file, videoUrl, "https://www.bilibili.com/",
                                System.currentTimeMillis())
                        Logu.d("preload", "预加载完成 $key, $downloaded bytes")
                    } else {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Logu.w("preload", "预加载失败: " + e.message)
                try { File(File(BiliTerminal.context.cacheDir, "preload"), "$key.part").delete() } catch (ignored: Exception) {}
            } finally {
                inFlight.remove(key)
            }
        })
    }

    // ------------------------ 播放时代理 ------------------------

    /**
     * 播放器启动前调用：若已有预加载数据则返回本地代理 URL，否则返回 null（原样播放远程地址）。
     */
    @JvmStatic
    fun proxyUrlFor(aid: Long, cid: Long, videoUrl: String?): String? {
        if (!isEnabled()) return null
        if (aid <= 0 || cid <= 0 || videoUrl.isNullOrEmpty()) return null
        val key = keyOf(aid, cid)
        val entry = entries[key] ?: return null
        if (!entry.file.exists() || entry.file.length() == 0L) return null
        if (entry.remoteUrl != videoUrl) return null  //URL 变了（换清晰度/过期重取），别硬代理
        if (System.currentTimeMillis() - entry.createdAt > EXPIRE_MS) {
            entries.remove(key)
            entry.file.delete()
            return null
        }
        return startProxyAndGetUrl(key)
    }

    /** 懒启动本地 HTTP 服务，返回代理地址 */
    private fun startProxyAndGetUrl(key: String): String? {
        try {
            if (!serverRunning.get()) {
                synchronized(this) {
                    if (!serverRunning.get()) {
                        serverSocket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
                        serverRunning.set(true)
                        serverThread = Thread({ serverLoop() }, "wifi-preload-proxy").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
            }
            val port = serverSocket?.localPort ?: return null
            return "http://127.0.0.1:$port/p/$key"
        } catch (e: IOException) {
            Logu.w("preload", "代理服务启动失败: " + e.message)
            serverRunning.set(false)
            return null
        }
    }

    private fun serverLoop() {
        val sock = serverSocket ?: return
        while (serverRunning.get()) {
            try {
                val client = sock.accept() ?: continue
                Thread({ handleClient(client) }, "wifi-preload-" + nextClientSeq.incrementAndGet()).apply {
                    isDaemon = true
                    start()
                }
            } catch (e: IOException) {
                if (serverRunning.get()) continue else break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 15000
                val input = c.getInputStream()
                val output = c.getOutputStream()

                // 读取请求行（我们只关心路径和 Range 头，逐行读到空行为止）
                var requestLine: String? = null
                var rangeHeader: String? = null
                var line: String?
                while (true) {
                    line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    if (requestLine == null) requestLine = line
                    val lower = line.lowercase()
                    if (lower.startsWith("range:")) rangeHeader = line.substring(6).trim()
                }
                if (requestLine == null) return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val path = parts[1]
                if (!path.startsWith("/p/")) {
                    writeSimple(output, 404, "Not Found")
                    return
                }
                val key = path.substring(3)
                val entry = entries[key]
                if (entry == null || !entry.file.exists()) {
                    writeSimple(output, 404, "Not Found")
                    return
                }

                // 解析 Range: bytes=start-end（ijkplayer 一定会带 Range）
                var start = 0L
                var endIncl = -1L
                if (rangeHeader != null) {
                    val m = Regex("bytes=(\\d*)-(\\d*)").find(rangeHeader)
                    if (m != null) {
                        if (m.groupValues[1].isNotEmpty()) start = m.groupValues[1].toLong()
                        if (m.groupValues[2].isNotEmpty()) endIncl = m.groupValues[2].toLong()
                    }
                }

                val localLen = entry.file.length()
                val localAvailable = localLen - start

                // 情况一：请求范围完全落在预加载文件内 → 纯本地回
                if (start < localLen && (endIncl < 0 || endIncl < localLen - 1)) {
                    serveFromFile(entry, output, start, if (endIncl < 0) localLen - 1 else endIncl)
                    return
                }

                // 情况二：需要文件之外的数据 → 向源站透传（Range 从 start 开始），
                // 同时把响应体追加写入预加载文件（下次同样的范围就能走本地）。
                relayFromRemote(entry, output, start, endIncl, localLen)
            }
        } catch (e: Exception) {
            // 客户端断开等，安静退出
        }
    }

    /** 从预加载文件回数据 */
    private fun serveFromFile(entry: PreloadEntry, output: OutputStream, start: Long, endIncl: Long) {
        val fileLen = entry.file.length()
        var end = endIncl
        if (end < 0 || end >= fileLen) end = fileLen - 1
        if (start > end) {
            writeSimple(output, 416, "Range Not Satisfiable")
            return
        }
        val length = end - start + 1
        val head = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: video/mp4\r\n" +
                "Content-Length: $length\r\n" +
                "Content-Range: bytes $start-$end/$fileLen\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        entry.file.inputStream().use { ins ->
            var skipped = 0L
            while (skipped < start) {
                val n = ins.skip(start - skipped)
                if (n <= 0) break
                skipped += n
            }
            copyLimited(ins, output, length)
        }
        output.flush()
    }

    /** 从源站透传，并把新数据追加进预加载文件 */
    private fun relayFromRemote(entry: PreloadEntry, output: OutputStream, start: Long, endIncl: Long, localLen: Long) {
        val rb = StringBuilder("bytes=").append(start)
        if (endIncl >= 0) rb.append("-").append(endIncl)
        val request = okhttp3.Request.Builder()
                .url(entry.remoteUrl)
                .header("Range", rb.toString())
                .header("Referer", entry.referer)
                .header("User-Agent", NetWorkUtil.USER_AGENT_WEB)
                .header("Cookie", SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "") ?: "")
                .build()
        try {
            val response = NetWorkUtil.getOkHttpInstance().newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    writeSimple(output, 502, "Bad Gateway")
                    return
                }
                val body = resp.body ?: run { writeSimple(output, 502, "Bad Gateway"); return }
                val contentLength = body.contentLength()
                val totalLen = if (contentLength > 0) start + contentLength else -1L

                val head = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        (if (contentLength > 0) "Content-Length: $contentLength\r\n" else "") +
                        (if (totalLen > 0) "Content-Range: bytes $start-/${start + contentLength - 1}/$totalLen\r\n" else "Accept-Ranges: bytes\r\n") +
                        "Accept-Ranges: bytes\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(head.toByteArray(Charsets.ISO_8859_1))

                // 顺序透传（只有紧接本地文件末尾的请求才追加写盘，乱序/跳跃不写）
                val appendToDisk = start == localLen
                val src: InputStream = body.byteStream()
                if (appendToDisk) {
                    try {
                        java.io.RandomAccessFile(entry.file, "rw").use { raf ->
                            raf.seek(localLen)
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                output.write(buf, 0, n)
                            }
                        }
                    } catch (e: Exception) {
                        copyAll(src, output)
                    }
                } else {
                    copyAll(src, output)
                }
                output.flush()
            }
        } catch (e: Exception) {
            try { writeSimple(output, 502, "Bad Gateway") } catch (ignored: Exception) {}
        }
    }

    private fun copyLimited(ins: InputStream, out: OutputStream, limit: Long) {
        val buf = ByteArray(32 * 1024)
        var done = 0L
        while (done < limit) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), limit - done).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
        }
    }

    private fun copyAll(ins: InputStream, out: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    /** 读一行（以 \n 结尾，去掉 \r），流结束返回 null */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.trimEnd('\r').toString()
    }

    private fun writeSimple(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.ISO_8859_1)
        val head = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    // ------------------------ 清理 ------------------------

    /** 播放页退出时调用：停掉代理服务（预加载文件保留给下次进入用） */
    @JvmStatic
    fun stopProxy() {
        if (!serverRunning.getAndSet(false)) return
        try { serverSocket?.close() } catch (ignored: Exception) {}
        serverSocket = null
        serverThread = null
    }

    /** 清空全部预加载（清缓存入口用） */
    @JvmStatic
    fun cleanup() {
        entries.clear()
        try {
            val dir = File(BiliTerminal.context.cacheDir, "preload")
            if (dir.exists()) FileUtil.deleteFolder(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun keyOf(aid: Long, cid: Long): String = "${aid}_$cid"
}
