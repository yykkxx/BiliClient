package com.RobinNotBad.BiliClient.util

import android.graphics.drawable.Drawable
import android.widget.ImageView

import com.bumptech.glide.Glide
import com.bumptech.glide.TransitionOptions
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory

/**
 * Kotlin 重构版（原 GlideUtil.java）。
 * 常量使用 const val，保持 Java 端 ConstantValue 语义（可用于 switch case 标签）；
 * 方法使用 @JvmStatic，保持 Java 端 GlideUtil.url(...) 的静态调用方式不变。
 */
object GlideUtil {
    const val QUALITY_HIGH = 80
    const val QUALITY_LOW = 25
    const val MAX_W_HIGH = 1024
    const val MAX_W_LOW = 512

    @JvmStatic
    fun url(url: String): String {
        if (!url.startsWith("http") || url.endsWith("gif") || url.contains("@") || url.contains("afdian"))
            return url
        if (SharedPreferencesUtil.getBoolean("image_request_jpg", false)) {
            if (url.endsWith("jpeg") || url.endsWith("jpg")) return url
            return url + "@0e_" +
                    QUALITY_LOW + "q_" + //+ MAX_H_LOW + "h_"
                    MAX_W_LOW + "w.jpeg"
        } else {
            if (url.endsWith("webp")) return url
            return url + "@0e_" +
                    QUALITY_LOW + "q_" + //+ MAX_H_LOW + "h_"
                    MAX_W_LOW + "w.webp"
        }
    }

    @JvmStatic
    fun url_hq(url: String): String {
        if (!url.startsWith("http") || url.endsWith("gif") || url.contains("@") || url.contains("afdiancdn.com"))
            return url
        if (SharedPreferencesUtil.getBoolean("image_request_jpg", false)) {
            if (url.endsWith("jpeg") || url.endsWith("jpg")) return url
            return url + "@0e_" +
                    QUALITY_HIGH + "q_" + //+ MAX_H_HIGH + "h_"
                    MAX_W_HIGH + "w.jpeg"
        } else {
            if (url.endsWith("webp")) return url
            return url + "@0e_" +
                    QUALITY_HIGH + "q_" + //+ MAX_H_HIGH + "h_"
                    MAX_W_HIGH + "w.webp"
        }
    }

    @JvmStatic
    fun request(view: ImageView, url: String, placeholder: Int) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .into(view)
    }

    @JvmStatic
    fun requestRound(view: ImageView, url: String, placeholder: Int) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .apply(RequestOptions.circleCropTransform())
                .into(view)
    }

    @JvmStatic
    fun request(view: ImageView, url: String, roundCorners: Int, placeholder: Int) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(placeholder)
                .apply(RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(roundCorners.toFloat()))))
                .into(view)
    }

    @JvmStatic
    fun getTransitionOptions(): TransitionOptions<*, in Drawable> {
        return if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LOAD_TRANSITION, true)) {
            DrawableTransitionOptions.with(DrawableCrossFadeFactory.Builder(300).setCrossFadeEnabled(true).build())
        } else {
            DrawableTransitionOptions()
        }
    }
}
