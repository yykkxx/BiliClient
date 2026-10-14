package com.RobinNotBad.BiliClient.model

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

import com.RobinNotBad.BiliClient.util.EmoteUtil
import com.RobinNotBad.BiliClient.util.LinkUrlUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.StringUtil

import org.json.JSONArray
import org.json.JSONObject

class OpusParagraph {
    @JvmField var align: Int = 0
    @JvmField var type: Int = 0
    @JvmField var content: Any? = null

    constructor()

    constructor(para: JSONObject) {
        this.type = para.optInt("para_type")
        this.align = para.optInt("align")
        when (type) {
            TYPE_TEXT -> this.content = analyzeText(para.optJSONObject("text"))
            TYPE_TEXT_BLOCKQUOTE -> this.content = analyzeBlockQuote(para.optJSONObject("blockquote"))
            TYPE_HEADING -> this.content = analyzeText(para.optJSONObject("heading"))
            TYPE_PIC -> this.content = analyzePic(para.optJSONObject("pic"))
            TYPE_DIVIDER -> this.content = analyzeDivider(para.optJSONObject("line"))
            TYPE_LIST -> this.content = analyzeList(para.optJSONObject("list"))
            TYPE_TEXT_OPUS -> this.content = analyzeOpus(para.optJSONArray("data"))
            else -> this.content = "[无法识别段落：" + type + "]"
        }
    }

    fun analyzeBlockQuote(blockquote: JSONObject?): CharSequence {
        if (blockquote == null) return ""
        val children = blockquote.optJSONArray("children")
        if (children == null) return ""

        val stringBuilder = SpannableStringBuilder()

        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i)
            if (child == null) break
            stringBuilder.append(analyzeText(child.optJSONObject("text")))
        }

        stringBuilder.setSpan(BackgroundColorSpan(0x33ffffff),
                0, stringBuilder.length - 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return stringBuilder
    }

    fun analyzeList(list: JSONObject?): CharSequence {
        if (list == null) return ""
        val listStyle = list.optInt("style")

        val stringBuilder = SpannableStringBuilder()

        val items = list.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                if (i != 0) stringBuilder.append("\n")
                val item = items.getJSONObject(i)
                when (listStyle) {
                    LIST_STYLE_NUMBER ->
                        stringBuilder.append(item.optInt("order").toString()).append(". ")
                    LIST_STYLE_DOT ->
                        stringBuilder.append("· ")
                }
                stringBuilder.append(analyzeText(item))
            }
        }

        val childrenPrimary = list.optJSONArray("children")
        if (childrenPrimary != null) {
            for (i in 0 until childrenPrimary.length()) {
                if (i != 0) stringBuilder.append("\n")
                val childPrimary = childrenPrimary.optJSONObject(i)
                if (childPrimary == null) break
                when (listStyle) {
                    LIST_STYLE_NUMBER ->
                        stringBuilder.append(childPrimary.optInt("order").toString()).append(". ")
                    LIST_STYLE_DOT ->
                        stringBuilder.append("· ")
                }

                val childrenSecondary = childPrimary.optJSONArray("children")
                if (childrenSecondary != null) {
                    val childBuilder = SpannableStringBuilder()
                    for (j in 0 until childrenSecondary.length()) {
                        val childSecondary = childrenSecondary.optJSONObject(j)
                        childBuilder.append(analyzeText(childSecondary.optJSONObject("text")))
                    }
                    stringBuilder.append(childBuilder)
                }
            }
        }

        return stringBuilder
    }

    fun analyzeText(text: JSONObject?): CharSequence {
        if (text == null) return ""
        val nodes = text.optJSONArray("nodes")
        if (nodes == null) return ""

        val stringBuilder = SpannableStringBuilder()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            when (node.optString("type")) {
                "TEXT_NODE_TYPE_WORD" -> {
                    val word = node.getJSONObject("word")

                    val startPosition = stringBuilder.length
                    stringBuilder.append(word.optString("words", ""))
                    val endPosition = stringBuilder.length

                    //粗体斜体，BOLD/ITALIC/BOLD_ITALIC 正好对应 1/2/3 所以可以这样减少判断量？
                    val style = word.optJSONObject("style")
                    if (style != null) {
                        val bold = style.optBoolean("bold")
                        val italic = style.optBoolean("italic")
                        val styleInt = (if (bold) Typeface.BOLD else 0) + (if (italic) Typeface.ITALIC else 0)
                        if (styleInt != 0)
                            stringBuilder.setSpan(StyleSpan(styleInt), startPosition, endPosition, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    //字体大小，使用的是相对大小，17号的字体在手表上还是太逆天了
                    val fontSize = word.optInt("font_size", 17)
                    if (fontSize != FONT_SIZE_BILI_DEFAULT) {
                        val fontScale = fontSize * 1.0f / FONT_SIZE_BILI_DEFAULT
                        stringBuilder.setSpan(RelativeSizeSpan(fontScale), startPosition, endPosition, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    //字体颜色
                    val color = word.optString("color", "#18191c")
                    if (color.startsWith("#") && !color.equals("#18191c")) try {
                        stringBuilder.setSpan(ForegroundColorSpan(Color.parseColor(color)), startPosition, endPosition, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } catch (ignored: Exception) {
                        Logu.e("color error", color)
                    }
                }

                "TEXT_NODE_TYPE_RICH" -> {
                    val rich = node.getJSONObject("rich")
                    stringBuilder.append(rich.getString("text"))
                    when (rich.optString("type")) {
                        "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                            val emoji = rich.getJSONObject("emoji")
                            EmoteUtil.replaceSingle(stringBuilder, emoji.optString("icon_url"), emoji.optInt("size"), stringBuilder.length - rich.getString("text").length, stringBuilder.length, 1.0f)
                        }
                        "RICH_TEXT_NODE_TYPE_RICH" ->  //TODO:忘记这个叫什么名字了，根据记忆应该是这个，如果不对请调整
                            stringBuilder.setSpan(StringUtil.LinkClickableSpan(rich.optString("jump_url"), LinkUrlUtil.TYPE_WEB_URL, rich.getString("jump_url")), stringBuilder.length - rich.getString("text").length, stringBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                else ->
                    stringBuilder.append("[").append(node.optString("type")).append("]")
            }
        }

        return stringBuilder
    }

    fun analyzePic(allJson: JSONObject?): Array<String?> {
        if (allJson == null) return arrayOfNulls(0)
        val picsJson = allJson.optJSONArray("pics")
        if (picsJson == null) return arrayOfNulls(0)
        val pics = arrayOfNulls<String>(picsJson.length())
        for (i in 0 until picsJson.length()) {
            val url = picsJson.getJSONObject(i).optString("url")
            pics[i] = if (url.startsWith("http")) url else "http:" + url
        }
        return pics
    }

    fun analyzeDivider(allJson: JSONObject?): Array<String?> {
        if (allJson == null) return arrayOfNulls(0)
        val url = allJson.getJSONObject("pic").optString("url")
        return arrayOf<String?>(if (url.startsWith("http")) url else "http:" + url)
    }

    fun analyzeOpus(rich_list: JSONArray?): CharSequence {
        if (rich_list == null) return ""
        val stringBuilder = SpannableStringBuilder()
        for (i in 0 until rich_list.length()) {
            val rich = rich_list.getJSONObject(i)
            val startLength = stringBuilder.length
            when (rich.getString("type")) {
                "RICH_TEXT_NODE_TYPE_AT" -> {
                    stringBuilder.append(rich.getString("text"))
                    val at = At(rich.optLong("rid"), startLength, stringBuilder.length)
                    StringUtil.setSingleAt(stringBuilder, at)
                }
                "RICH_TEXT_NODE_TYPE_TOPIC" -> {
                    stringBuilder.append(rich.getString("text"))
                    stringBuilder.setSpan(StringUtil.LinkClickableSpan(rich.getString("jump_url"), LinkUrlUtil.TYPE_WEB_URL, rich.getString("jump_url")), startLength, stringBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                    stringBuilder.append(rich.optString("text"))
                    val emoji = rich.getJSONObject("emoji")
                    val url = emoji.optString("icon_url")
                    val size = emoji.optInt("size")
                    EmoteUtil.replaceSingle(stringBuilder, url, size, startLength, stringBuilder.length, 1.0f)
                }
                "RICH_TEXT_NODE_TYPE_TEXT" -> {
                    stringBuilder.append(rich.getString("orig_text"))
                }
                else -> {
                    stringBuilder.append(rich.getString("orig_text"))
                }
            }
        }
        return stringBuilder
    }

    companion object {
        //这几个是b站的
        const val TYPE_TEXT: Int = 1

        const val TYPE_PIC: Int = 2

        const val TYPE_DIVIDER: Int = 3

        const val TYPE_TEXT_BLOCKQUOTE: Int = 4

        const val TYPE_LIST: Int = 5

        const val TYPE_HEADING: Int = 8

        //这几个是自定义的
        const val TYPE_TEXT_OPUS: Int = 99

        const val TYPE_VIDEO: Int = 100

        const val TYPE_DYNAMIC: Int = 101

        const val TYPE_ARTICLE: Int = 102

        const val LIST_STYLE_NUMBER: Int = 1

        const val LIST_STYLE_DOT: Int = 2

        @JvmField
        var FONT_SIZE_BILI_DEFAULT: Int = 17
    }
}
