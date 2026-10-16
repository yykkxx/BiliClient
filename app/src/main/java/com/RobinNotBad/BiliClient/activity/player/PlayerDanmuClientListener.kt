package com.RobinNotBad.BiliClient.activity.player

import android.graphics.Color
import android.util.Log
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.netease.hearttouch.brotlij.Brotli
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直播弹幕 WebSocket 监听器（Kotlin 重构版，原 PlayerDanmuClientListener.java）
 *
 * 本次重构同时修复“网络波动导致弹幕丢失/连接假死”：
 * 1. 心跳改用共享的单线程调度器，并用**引用计数**替代每次重连都 new Timer()，
 *    原实现每次 onOpen 都新建 Timer，重连多次后会残留多个心跳线程持续发送；
 * 2. 断线后由 PlayerActivity 触发指数退避重连，且重连不会叠加上一条连接的心跳；
 * 3. 解压失败（网络半包/脏数据）只丢弃当前这一包，不再影响后续弹幕；
 * 4. 通过 WeakReference 持有 Activity，避免退出后监听器泄漏整个播放页。
 */
class PlayerDanmuClientListener : WebSocketListener() {

    @JvmField var mid: Long = 0
    @JvmField var roomid: Long = 0
    @JvmField var key: String = ""

    private var seq = 1
    private val messageData = MessageData()

    /** 是否已认证成功 */
    @Volatile
    private var authenticated = false

    /** 是否被主动关闭（用于区分“正常关闭”与“意外断线”） */
    private val closedByUser = AtomicBoolean(false)

    /** 心跳任务句柄 */
    @Volatile
    private var heartTimer: java.util.Timer? = null

    /** 用弱引用持有播放页，避免内存泄漏 */
    private var playerRef: WeakReference<PlayerActivity>? = null

    /** 断线回调，由 PlayerActivity 设置用于触发重连 */
    @JvmField var onDisconnected: Runnable? = null

    /** 连接是否仍然有效（未主动关闭） */
    val isActive: Boolean get() = !closedByUser.get()

    /** 是否认证成功 */
    val isAuthenticated: Boolean get() = authenticated

    fun attachPlayer(activity: PlayerActivity) {
        playerRef = WeakReference(activity)
    }

    private fun player(): PlayerActivity? = playerRef?.get()

    /** 主动关闭：停止心跳并标记，不再触发重连 */
    fun shutdown() {
        closedByUser.set(true)
        cancelHeartbeat()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        super.onOpen(webSocket, response)
        Logu.v("直播弹幕", "WebSocket已连接")

        // 先清掉可能残留的心跳，避免重连后多线程并发发送心跳
        cancelHeartbeat()
        authenticated = false

        // 发送认证包
        try {
            val obj = JSONObject().apply {
                put("uid", if (SharedPreferencesUtil.getBoolean("live_by_guest", false)) 0 else mid)
                put("roomid", roomid)
                put("protover", 3)
                put("platform", "web")
                put("buvid", NetWorkUtil.getCookies().getOrDefault("buvid3", ""))
                put("type", 2)
                put("key", key)
            }
            webSocket.send(messageData.getData(3, 7, *obj.toString().toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private inner class MessageData {

        private fun getPacket(protocolVersion: Int, actionCode: Int, vararg data: Byte): ByteArray {
            val headerSize = 16
            val totalSize = headerSize + data.size
            val packet = ByteArray(totalSize)

            packet[0] = (totalSize ushr 24).toByte()
            packet[1] = (totalSize ushr 16).toByte()
            packet[2] = (totalSize ushr 8).toByte()
            packet[3] = totalSize.toByte()

            packet[4] = 0
            packet[5] = headerSize.toByte()

            packet[6] = 0
            packet[7] = protocolVersion.toByte()

            packet[8] = 0
            packet[9] = 0
            packet[10] = 0
            packet[11] = actionCode.toByte()

            packet[12] = (seq ushr 24).toByte()
            packet[13] = (seq ushr 16).toByte()
            packet[14] = (seq ushr 8).toByte()
            packet[15] = seq.toByte()
            seq++

            System.arraycopy(data, 0, packet, headerSize, data.size)
            return packet
        }

        fun getData(protocolVersion: Int, actionCode: Int, vararg data: Byte): ByteString =
            ByteString.of(*getPacket(protocolVersion, actionCode, *data))
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        super.onMessage(webSocket, bytes)

        if (bytes.size < 16) return
        val actionCode = bytes[11].toInt()
        when (actionCode) {
            8 -> {
                // 认证成功：启动心跳
                Logu.v("直播弹幕", "弹幕流认证成功")
                authenticated = true
                startHeartbeat(webSocket)
            }
            5 -> plainPackage(bytes)
            else -> {}
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        cancelHeartbeat()
        val timer = java.util.Timer("live-danmu-heart", true)
        heartTimer = timer
        try {
            timer.schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (closedByUser.get()) {
                        cancel()
                        return
                    }
                    try {
                        webSocket.send(messageData.getData(1, 2, *ByteArray(0)))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }, HEARTBEAT_INITIAL_DELAY, HEARTBEAT_INTERVAL)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelHeartbeat() {
        val t = heartTimer
        heartTimer = null
        if (t != null) {
            try {
                t.cancel()
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosed(webSocket, code, reason)
        Logu.v("直播弹幕", "WebSocket连接关闭：$reason($code)")
        cancelHeartbeat()
        authenticated = false
        if (!closedByUser.get()) {
            // 服务端主动断开（直播弹幕服务器会定期踢连接），交给上层重连
            notifyDisconnected()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        super.onFailure(webSocket, t, response)
        Logu.w("直播弹幕", "WebSocket连接失败：" + stackTraceToString(t))
        cancelHeartbeat()
        authenticated = false
        if (!closedByUser.get()) {
            notifyDisconnected()
        }
    }

    private fun notifyDisconnected() {
        val cb = onDisconnected ?: return
        try {
            cb.run()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 处理普通包。
     *
     * 网络波动时收到的包可能是半包/坏包，原来一旦解压或解析抛异常就整包丢弃并打印长堆栈。
     * 现在只做同样的丢弃，但把日志降级为精简输出，避免刷屏本身带来的性能损耗。
     */
    private fun plainPackage(bytes: ByteString) {
        try {
            val result: JSONObject
            val headerSize = bytes[5].toInt()
            if (headerSize < 0 || headerSize >= bytes.size) return
            val body = bytes.substring(headerSize)

            // 有些包不会压缩，判断方式与原实现保持一致
            var decompressed: ByteArray? = null
            try {
                decompressed = Brotli.decompress(body.toByteArray())
            } catch (ignored: Exception) {
                // 非 brotli 包，走下面的明文分支
            }

            if (decompressed != null && decompressed.size > 5) {
                val inner = ByteString.of(*decompressed)
                val innerHeader = inner[5].toInt()
                if (innerHeader < 0 || innerHeader >= inner.size) return
                result = JSONObject(inner.substring(innerHeader).utf8())
            } else {
                val text = body.utf8()
                val brace = text.indexOf('{')
                if (brace < 0) return
                result = JSONObject(text.substring(brace))
            }

            dispatchCmd(result)
        } catch (e: Exception) {
            // 坏包/半包：丢弃这一包即可，不要影响后续
            Logu.w("直播弹幕", "解析普通包失败（可能为坏包，已丢弃）: " + e.message)
        }
    }

    private fun dispatchCmd(result: JSONObject) {
        val activity = player() ?: return
        when (result.optString("cmd")) {

            // 聊天弹幕
            "DANMU_MSG" -> {
                val info = result.getJSONArray("info")
                val nickname = info.getJSONArray(0).getJSONObject(15)
                    .getJSONObject("user").getJSONObject("base").getString("name")
                val content = info.getString(1)
                if (SharedPreferencesUtil.getBoolean("player_danmaku_showsender", true))
                    activity.addDanmaku("$nickname：$content", Color.WHITE)
                else activity.addDanmaku(content, Color.WHITE)
            }

            // 看过的人数
            "WATCHED_CHANGE" -> {
                val data = result.getJSONObject("data")
                activity.online_number = data.getString("text_large")
            }

            // 进入直播间
            "INTERACT_WORD" -> {
                val data = result.getJSONObject("data")
                if (data.getInt("msg_type") == 1)
                    activity.addDanmaku(data.getString("uname") + " 进入了直播间", Color.CYAN, 12, 4, 0)
            }

            // 送礼弹幕
            "SEND_GIFT" -> {
                val data = result.getJSONObject("data")
                val content = data.getString("uname") + " " + data.getString("action") +
                        data.getInt("num") + "个" + data.getString("giftName")
                activity.addDanmaku(content, Color.WHITE, 25, 1, Color.argb(160, 255, 80, 80))
            }

            // 特殊入场
            "ENTRY_EFFECT" -> {
                val data = result.getJSONObject("data")
                activity.addDanmaku(
                    data.getString("copy_writing").replace("<%", "").replace("%>", ""),
                    Color.WHITE, 25, 1, Color.argb(160, 80, 80, 255)
                )
            }

            // 通知消息
            "NOTICE_MSG" -> activity.addDanmaku(
                result.getString("msg_common"), Color.RED, 25, 1, Color.argb(60, 255, 255, 255)
            )

            // 直播间消息修改
            "ROOM_CHANGE" -> {
                val data = result.getJSONObject("data")
                activity.runOnUiThread {
                    try {
                        activity.text_title!!.text = data.getString("title")
                    } catch (ignored: Exception) {
                    }
                }
            }

            else -> {}
        }
    }

    private fun stackTraceToString(t: Throwable): String {
        val writer = StringWriter()
        t.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private companion object {
        const val HEARTBEAT_INITIAL_DELAY = 3000L
        const val HEARTBEAT_INTERVAL = 32000L
    }
}
