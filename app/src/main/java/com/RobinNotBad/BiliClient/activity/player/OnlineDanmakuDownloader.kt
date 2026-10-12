package com.RobinNotBad.BiliClient.activity.player

import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import okio.BufferedSink
import okio.Okio
import okio.Sink
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater

/**
 * 旧版 XML 弹幕下载器（Kotlin 重构，替代 PlayerActivity/DownloadActivity 中重复的 decompress 逻辑）
 *
 * 旧接口 https://comment.bilibili.com/{cid}.xml 返回的是 deflate 压缩的数据，
 * 需要先解压再写入 xml 文件。
 *
 * 与旧实现相比的改进：
 * 1. 解压循环加入 needsInput/needsDictionary 防护，数据异常时不再空转卡死
 *    （原实现遇坏数据会在 while(!finished()) 里 inflate 返回 0 无限循环）；
 * 2. 解压失败时**不静默吞掉**，而是明确返回 null，让上层知道“这次没拿到弹幕”；
 * 3. 支持取消标志，播放页退出时立刻中止下载；
 * 4. 下载写入失败时清理半截文件，避免下次误读到损坏的 XML。
 */
object OnlineDanmakuDownloader {

    private const val BUFFER_SIZE = 8 * 1024

    /**
     * 下载并解压旧版 XML 弹幕到指定文件。
     *
     * @param url       弹幕地址，如 https://comment.bilibili.com/{cid}.xml
     * @param target    目标文件
     * @param cancelled 取消标志
     * @return 成功返回写入的文件，失败返回 null
     */
    @JvmStatic
    @JvmOverloads
    fun download(url: String?, target: File, cancelled: AtomicBoolean? = null): File? {
        if (url.isNullOrEmpty()) return null
        if (cancelled != null && cancelled.get()) return null

        var bufferedSink: BufferedSink? = null
        try {
            val response = NetWorkUtil.get(url, NetWorkUtil.webHeaders)
            val body = response.body() ?: run {
                Logu.w("弹幕下载", "旧版弹幕响应体为空")
                return null
            }

            val raw = body.use { it.bytes() }
            if (cancelled != null && cancelled.get()) return null

            val xml = decompress(raw)
            if (xml == null || xml.isEmpty()) {
                Logu.w("弹幕下载", "旧版弹幕解压失败或内容为空")
                return null
            }

            target.parentFile?.mkdirs()
            val sink: Sink = Okio.sink(target)
            bufferedSink = Okio.buffer(sink)
            bufferedSink.write(xml)
            bufferedSink.close()
            bufferedSink = null

            Logu.d("弹幕下载", "旧版弹幕已写入 ${target.absolutePath}（${xml.size} 字节）")
            return target
        } catch (e: Exception) {
            Logu.w("弹幕下载", "旧版弹幕下载失败: " + e.message)
            // 清理可能写坏的文件，避免下次加载到损坏数据
            try {
                if (target.exists()) target.delete()
            } catch (ignored: Exception) {
            }
            return null
        } finally {
            try {
                bufferedSink?.close()
            } catch (ignored: IOException) {
            }
        }
    }

    /**
     * zlib raw deflate 解压。带空转防护：
     * inflate 返回 0 且需要更多输入/字典时立即结束，避免死循环。
     *
     * @return 解压后的数据；失败返回 null
     */
    @JvmStatic
    fun decompress(data: ByteArray?): ByteArray? {
        if (data == null || data.isEmpty()) return null
        val output = java.io.ByteArrayOutputStream(data.size * 2)
        val inflater = Inflater(true) // 这个 true 是关键
        try {
            inflater.setInput(data)
            val buf = ByteArray(BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) {
                    output.write(buf, 0, count)
                    continue
                }
                // count == 0：数据不足或已结束，必须跳出，否则无限空转
                if (inflater.needsInput() || inflater.needsDictionary()) break
                // 既没产出也不需要输入，说明数据异常
                break
            }
            val result = output.toByteArray()
            // 完全没解出内容视为失败
            if (result.isEmpty() && data.isNotEmpty()) {
                // 有可能本来就是未压缩的明文 XML，直接返回原数据兜底
                val asText = String(data, 0, minOf(data.size, 64), Charsets.UTF_8)
                if (asText.contains("<")) return data
                return null
            }
            return result
        } catch (e: Exception) {
            Logu.w("弹幕下载", "解压异常: " + e.message)
            // 兜底：可能本身就是明文
            return try {
                if (String(data, 0, minOf(data.size, 64), Charsets.UTF_8).contains("<")) data else null
            } catch (ignored: Exception) {
                null
            }
        } finally {
            try {
                inflater.end()
            } catch (ignored: Exception) {
            }
            try {
                output.close()
            } catch (ignored: IOException) {
            }
        }
    }

    /** 流式解压（保留给可能的流式调用场景） */
    @JvmStatic
    @Throws(IOException::class)
    fun decompress(input: InputStream): ByteArray? = decompress(input.use { it.readBytes() })
}
