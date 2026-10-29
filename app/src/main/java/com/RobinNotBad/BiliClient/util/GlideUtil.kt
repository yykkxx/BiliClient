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

import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin 重构版（原 GlideUtil.java）。
 * 常量使用 const val，保持 Java 端 ConstantValue 语义（可用于 switch case 标签）；
 * 方法使用 @JvmStatic，保持 Java 端 GlideUtil.url(...) 的静态调用方式不变。
 *
 * 性能说明（v5.0.2-alpha10）：
 * 本类的 `url()` / `getTransitionOptions()` / 圆角 RequestOptions 都是在列表
 * onBindViewHolder 里被频繁调用的热路径。原来每次调用都会新建
 * DrawableCrossFadeFactory + DrawableTransitionOptions + RoundedCorners + RequestOptions，
 * fling 时每秒几十次，是手表上最主要的 GC 压力源之一。
 * 这些对象本身是“只读配置”（Glide 的 RequestBuilder.apply() 只读取、不会写回），
 * 因此可以安全地跨请求复用 —— 这里改成进程内共享/按参数缓存。
 */
object GlideUtil {
    const val QUALITY_HIGH = 80
    const val QUALITY_LOW = 25
    const val MAX_W_HIGH = 1024
    const val MAX_W_LOW = 512

    /** 圆角参数缓存的 key 进制（避免为缓存 key 再分配字符串/Object） */
    private const val ROUND_OPTIONS_KEY_BASE = 1000

    /** 默认 sizeMultiplier 百分比（100 == 不缩放） */
    private const val SIZE_MULTIPLIER_FULL = 100

    /** 圆角 RequestOptions 缓存：key = 圆角dp * 1000 + sizeMultiplier百分比 */
    private val roundOptionsCache = ConcurrentHashMap<Int, RequestOptions>()

    /** 圆形裁剪 RequestOptions：全应用头像共用一份（原来每行 new 一个） */
    private val circleOptions: RequestOptions by lazy { RequestOptions.circleCropTransform() }

    /** 交叉淡入工厂：只建一次；真正的 Transition 由 Glide 按请求各自 build，不共享状态 */
    private val crossFadeFactory: DrawableCrossFadeFactory by lazy {
        DrawableCrossFadeFactory.Builder(300).setCrossFadeEnabled(true).build()
    }

    /**
     * 两个固定的 TransitionOptions 单例（有/无淡入）。
     * 仍然按 SharedPreferencesUtil.LOAD_TRANSITION 实时选择，所以设置页开关照旧即时生效；
     * 省掉的只是「每次请求都重建工厂 + 重建 options」。
     */
    private val transitionFade: DrawableTransitionOptions by lazy {
        DrawableTransitionOptions.with(crossFadeFactory)
    }
    private val transitionNoFade: DrawableTransitionOptions by lazy { DrawableTransitionOptions() }

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

    /**
     * 圆角请求选项（缓存复用）。
     *
     * @param roundCornersDp 圆角半径（dp），与原来 RequestOptions.bitmapTransform(RoundedCorners(dp2px(...))) 等价
     * @param sizeMultiplierPercent 解码尺寸系数百分比（100 = 不缩放，85 = 原来的 sizeMultiplier(0.85f)）
     */
    @JvmStatic
    fun roundOptions(roundCornersDp: Int, sizeMultiplierPercent: Int = SIZE_MULTIPLIER_FULL): RequestOptions {
        val key = roundCornersDp * ROUND_OPTIONS_KEY_BASE + sizeMultiplierPercent
        val cached = roundOptionsCache[key]
        if (cached != null) return cached
        val options = RequestOptions.bitmapTransform(RoundedCorners(ToolsUtil.dp2px(roundCornersDp.toFloat())))
        if (sizeMultiplierPercent != SIZE_MULTIPLIER_FULL) {
            options.sizeMultiplier(sizeMultiplierPercent / SIZE_MULTIPLIER_FULL.toFloat())
        }
        // putIfAbsent 保证并发下也只会有一份，且拿到的实例一定是缓存里那份
        return roundOptionsCache.putIfAbsent(key, options) ?: options
    }

    /** 圆形裁剪请求选项（头像用，缓存复用，等价于原来的 RequestOptions.circleCropTransform()） */
    @JvmStatic
    fun circleOptions(): RequestOptions = circleOptions

    @JvmStatic
    fun request(view: ImageView, url: String, placeholder: Int) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(getTransitionOptions())
                .placeholder(placeholder)
                .into(view)
    }

    @JvmStatic
    fun requestRound(view: ImageView, url: String, placeholder: Int) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(getTransitionOptions())
                .placeholder(placeholder)
                .apply(circleOptions())
                .into(view)
    }

    @JvmStatic
    fun request(view: ImageView, url: String, roundCorners: Int, placeholder: Int) {
        Glide.with(view).asDrawable().load(url(url))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .format(DecodeFormat.PREFER_RGB_565)
                .transition(getTransitionOptions())
                .placeholder(placeholder)
                .apply(roundOptions(roundCorners))
                .into(view)
    }

    @JvmStatic
    fun getTransitionOptions(): TransitionOptions<*, in Drawable> {
        return if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LOAD_TRANSITION, true)) {
            transitionFade
        } else {
            transitionNoFade
        }
    }
}
