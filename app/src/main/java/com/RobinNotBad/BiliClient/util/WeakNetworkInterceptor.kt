package com.RobinNotBad.BiliClient.util

import java.io.IOException

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 弱网 / 移动网络的请求优化（#11）。
 *
 * 目标：**更少开销、更少计算**，降低 4G 下的发热（射频唤醒与连接握手是主要热源）。
 *
 * 这里做的都是“不改变语义”的优化：
 * 1. 连接复用：显式声明 keep-alive，配合 NetWorkUtil 的连接池，减少 TCP/TLS 握手次数
 *    （一次握手往往比传一个 JSON 还费电）；
 * 2. 不做多余的 gzip 协商：**不**手动设置 Accept-Encoding。
 *    OkHttp 只在由它自己添加该头时才会透明解压，手动设置会导致响应体不被解压，
 *    上层 JSON 解析直接失败——所以这里刻意不碰它；
 * 3. 媒体分段请求（带 Range）不添加额外头部，避免影响播放器/下载器的分片逻辑。
 *
 * 真正的重试与退避交给各业务侧统一处理（弹幕分段补拉、播放看门狗等），
 * 避免在弱网下“一个请求卡很久”持续耗电。
 */
class WeakNetworkInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // 媒体分段/下载请求保持原样，不额外加头
        val isMediaRequest = request.header("Range") != null

        val requestBuilder = request.newBuilder()
        if (!isMediaRequest && request.header("Connection") == null) {
            requestBuilder.header("Connection", "keep-alive")
        }

        val response = chain.proceed(requestBuilder.build())

        if (!isMediaRequest && response.header("Connection") == null) {
            return response.newBuilder()
                .header("Connection", "keep-alive")
                .build()
        }
        return response
    }
}
