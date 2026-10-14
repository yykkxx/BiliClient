package com.RobinNotBad.BiliClient.helper

import android.content.Context
import androidx.annotation.NonNull
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

@GlideModule
class CustomGlideModule : AppGlideModule() {
    override fun registerComponents(@NonNull context: Context, @NonNull glide: Glide, @NonNull registry: Registry) {
        val builder = NetWorkUtil.setOkHttpSsl(OkHttpClient.Builder())
        builder.addInterceptor { chain ->
            val headers: ArrayList<String> = NetWorkUtil.webHeaders
            val requestBuilder = chain.request().newBuilder()
            var i = 0
            while (i < headers.size) {
                requestBuilder.addHeader(headers[i], headers[i + 1])
                i += 2
            }

            chain.proceed(requestBuilder.build())
        }

        registry.replace(
            GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(
                builder
                    .dns(NetWorkUtil.Inet4Selector())
                    .pingInterval(8, TimeUnit.SECONDS)
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(16, TimeUnit.SECONDS).build()
            )
        )
    }
}
