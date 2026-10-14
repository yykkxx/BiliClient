package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.model.Emote
import com.bumptech.glide.Glide

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList
import java.util.concurrent.ExecutionException


//表情包工具，用于将文本中的表情包替换为对应图片，部分代码来自catGPT
//2023-07-23

object EmoteUtil {
    @Throws(JSONException::class, ExecutionException::class, InterruptedException::class)
    @JvmStatic
    fun textReplaceEmote(text: String, emote: JSONArray?, scale: Float, context: Context): SpannableStringBuilder {
        val result = SpannableStringBuilder(text)
        if (emote != null && emote.length() > 0) {
            for (i in 0 until emote.length()) {    //遍历每一个表情包
                val key = emote.getJSONObject(i)

                val name = key.getString("name")
                val emoteUrl = key.getString("url")
                val size = key.getInt("size")  //B站十分贴心的帮你把表情包大小都写好了，快说谢谢蜀黍

                replaceSingle(result, name, emoteUrl, size, scale, context)
            }
        }
        return result
    }

    @JvmStatic
    fun textReplaceEmote(text: String, emotes: ArrayList<Emote>?, scale: Float, context: Context, source: CharSequence?): SpannableStringBuilder {
        val result = if (source is SpannableStringBuilder) source else SpannableStringBuilder(text)

        if (emotes != null && emotes.size > 0) {
            for (i in 0 until emotes.size) {    //遍历每一个表情包
                val key = emotes[i]

                val name = key.name
                val emoteUrl = key.url
                val size = key.size  //B站十分贴心的帮你把表情包大小都写好了，快说谢谢蜀黍

                replaceSingle(result, name, emoteUrl, size, scale, context)
            }
        }
        return result
    }

    @JvmStatic
    fun textReplaceEmote(text: String, emotes: ArrayList<Emote>?, scale: Float, context: Context): SpannableStringBuilder {
        return textReplaceEmote(text, emotes, scale, context, null)
    }

    @JvmStatic
    fun replaceSingle(spannableString: SpannableStringBuilder, name: String?, url: String?, size: Int, scale: Float, context: Context) {
        try {
            val origText = spannableString.toString()
            val drawable: Drawable = Glide.with(context).asDrawable().load(url).submit().get()  //获得url并通过glide得到一张图片

            drawable.setBounds(0, 0, (size * ToolsUtil.sp2px(18f) * scale).toInt(), (size * ToolsUtil.sp2px(18f) * scale).toInt())  //参考了隔壁腕上哔哩并进行了改进

            val emoteName = name!!    //原 Java 代码在 name 为 null 时抛 NPE（被下方 catch 吞掉），此处保持同样的行为
            var start = origText.indexOf(emoteName)    //检测此字符串的起始位置
            while (start >= 0) {
                val end = start + emoteName.length    //计算得出结束位置
                val imageSpan = ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)  //获得一个imagespan  这句不能放while上面，imagespan不可以复用，我也不知道为什么
                spannableString.setSpan(imageSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)  //替换
                start = origText.indexOf(emoteName, end)    //重新检测起始位置，直到找不到，然后开启下一个循环
            }
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun replaceSingle(spannableString: SpannableStringBuilder, url: String?, size: Int, start: Int, end: Int, scale: Float) {
        try {
            val drawable: Drawable = Glide.with(BiliTerminal.context).asDrawable().load(url).submit().get()
            drawable.setBounds(0, 0, (size * ToolsUtil.sp2px(18f) * scale).toInt(), (size * ToolsUtil.sp2px(18f) * scale).toInt())
            val imageSpan = ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)
            spannableString.setSpan(imageSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (ignored: Exception) {
        }
    }

}
