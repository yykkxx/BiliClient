package com.RobinNotBad.BiliClient.util

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.Pair
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

import androidx.annotation.DrawableRes
import androidx.annotation.NonNull
import androidx.core.content.res.ResourcesCompat

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.RobinNotBad.BiliClient.activity.CopyTextActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.ReplyApi
import com.RobinNotBad.BiliClient.model.At
import com.RobinNotBad.BiliClient.model.UserInfo

import org.jsoup.Jsoup

import java.util.Locale
import java.util.regex.Pattern

/**
 * Kotlin 重构版（原 StringUtil.java）。
 *
 * Java 端兼容说明：
 * - 原先全部 public static 方法都放在 object StringUtil 里，并保留 @JvmStatic，
 *   Java 端 StringUtil.xxx(...) 的调用方式完全不变。
 * - 内部类 LinkClickableSpan / ClickableSpanTouchListener 是 object 的嵌套类，
 *   编译后仍是静态嵌套类，Java 端 new StringUtil.ClickableSpanTouchListener() 可用。
 * - 原 Java 的包级成员 levelBadges 只在类内使用，改为 object 内的 private val。
 * - 常量 TYPE_* / 正则来自 LinkUrlUtil，此处使用 LinkUrlUtil.XXX 限定访问（不再 static import）。
 * - 逻辑、常量、字符串、JSON key、URL 与原 Java 完全一致。
 */
@SuppressLint("ClickableViewAccessibility")
object StringUtil {
    @JvmStatic
    fun appendString(stringBuilder: SpannableStringBuilder, str: String): Pair<Int, Int> {
        val startIndex = stringBuilder.length
        stringBuilder.append(str)
        val endIndex = stringBuilder.length
        return Pair<Int, Int>(startIndex, endIndex)
    }

    /**
     * 单位转换
     *
     * @param num 原始数字
     * @return 转换后的字符串
     */
    @JvmStatic
    fun toWan(num: Long): String {
        return if (num >= 100000000)
            String.format(Locale.CHINA, "%.1f", num.toFloat() / 100000000) + "亿"
        else if (num >= 10000)
            String.format(Locale.CHINA, "%.1f", num.toFloat() / 10000) + "万"
        else
            num.toString()
    }

    @JvmStatic
    fun toTime(progress: Int): String {
        val cghour = progress / 3600
        val cgminute = (progress % 3600) / 60
        val cgsecond = progress % 60
        val cghourStr: String
        val cgminStr: String
        val cgsecStr: String

        if (cghour < 10) cghourStr = "0" + cghour
        else cghourStr = cghour.toString()

        if (cgminute < 10) cgminStr = "0" + cgminute
        else cgminStr = cgminute.toString()

        if (cgsecond < 10) cgsecStr = "0" + cgsecond
        else cgsecStr = cgsecond.toString()

        return if (cghour > 0) cghourStr + ":" + cgminStr + ":" + cgsecStr
        else cgminStr + ":" + cgsecStr
    }

    @JvmStatic
    fun htmlToString(html: String): String {
        // v5.0.2-alpha10：9 个待替换实体全部以 '&' 开头，串里没有 '&' 时这 9 次 replace 必然全部
        // 无匹配（String.replace 无匹配时返回自身，但每遍仍要扫全串）。标题/简介里带实体的只是少数，
        // 加这一个短路判断可省掉 9 遍扫描，输出与原来逐字相同。
        if (html.indexOf('&') < 0) return html
        return html.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&#34;", "\"")
                .replace("&#38;", "&")
                .replace("&#60;", "<")
                .replace("&#62;", ">")
    }

    @JvmStatic
    fun htmlReString(html: String): String {
        return html.replace("<p>", "")
                .replace("</p>", "\n")
                .replace("<br>", "\n")
                .replace("<em class=\"keyword\">", "")
                .replace("</em>", "")
    }

    @JvmStatic
    fun removeHtml(html: String): String {
        return Jsoup.parse(html).text()
    }

    @JvmStatic
    fun unEscape(str: String): String {
        return str.replace(Regex("\\\\(.)"), "\$1")
    }

    @JvmStatic
    fun setCopy(textView: TextView, customText: String?) {
        if (SharedPreferencesUtil.getBoolean("copy_enable", true)) {
            textView.setOnLongClickListener { view1 ->
                val intent = Intent(textView.context, CopyTextActivity::class.java)
                intent.putExtra("content", if (customText == null) textView.text.toString() else customText)
                textView.context.startActivity(intent)
                true
            }
        }
    }

    @JvmStatic
    fun setCopy(textView: TextView) {
        setCopy(textView, null) //直接传getText()会导致文本变化后点击不了
    }

    @JvmStatic
    fun setCopy(vararg textViews: TextView) {
        for (textView in textViews) setCopy(textView)
    }

    @JvmStatic
    fun copyText(context: Context, str: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val mClipData = ClipData.newPlainText("Label", str)
        cm.setPrimaryClip(mClipData)
    }

    @JvmStatic
    fun setLink(spannableString: SpannableStringBuilder) {
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LINK_ENABLE, true)) return
        if (TextUtils.isEmpty(spannableString)) return

        val text = spannableString.toString()

        // v5.0.2-alpha10：先做一次「有没有可能命中」的廉价预判，再跑 4 个正则。
        // 4 个模式都要求文本里出现 http/ftp/file/BV/av/cv 之一（模式本身大小写敏感、逐字如此），
        // 所以不含这些子串时四个 matcher 必然零命中 —— 而 6 次 String.contains 比跑 4 遍正则便宜得多。
        // 简介/评论这类长文本在详情页主线程上会走这里，绝大多数内容其实没有任何链接。
        if (!mayContainLink(text)) return

        // 正则改为常量复用。原来每次调用都 Pattern.compile() 一遍，
        // 而 setLink 会在评论/简介/动态内容的 bind 里被反复调用（尤其长简介在详情页主线程里），
        // 正则编译是这里最贵的一步（解析表达式 + 构建匹配器状态）。
        val urlMatcher = LinkUrlUtil.WEB_URL_PATTERN.matcher(text)
        while (urlMatcher.find()) {
            val start = urlMatcher.start()
            val end = urlMatcher.end()
            spannableString.setSpan(LinkClickableSpan(text.substring(start, end), LinkUrlUtil.TYPE_WEB_URL),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        var matcher = LinkUrlUtil.BV_PATTERN.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            spannableString.setSpan(LinkClickableSpan(text.substring(start, end), LinkUrlUtil.TYPE_BVID),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        matcher = LinkUrlUtil.AV_PATTERN.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            spannableString.setSpan(LinkClickableSpan(text.substring(start, end), LinkUrlUtil.TYPE_AVID),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        matcher = LinkUrlUtil.CV_PATTERN.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            spannableString.setSpan(LinkClickableSpan(text.substring(start, end), LinkUrlUtil.TYPE_CVID),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * setLink 的廉价预判（v5.0.2-alpha10）：四个模式都要求出现下面某个子串才可能命中，
     * 所以不含这些子串时直接跳过 4 遍正则，输出完全一致。
     */
    private fun mayContainLink(text: String): Boolean {
        return text.contains("http") || text.contains("ftp") || text.contains("file") ||
                text.contains("BV") || text.contains("av") || text.contains("cv")
    }

    @JvmStatic
    fun setLink(vararg textViews: TextView) {
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LINK_ENABLE, true)) return
        for (textView in textViews) {
            if (TextUtils.isEmpty(textView.text)) continue
            val spannableString = SpannableStringBuilder(textView.text)
            setLink(spannableString)
            textView.text = spannableString
            textView.setOnTouchListener(SHARED_TOUCH_LISTENER)
        }
    }

    @JvmStatic
    fun setSingleAt(spannableString: SpannableStringBuilder, atName: String, atMid: Long) {
        val pattern = Pattern.compile("@" + atName)
        val text = spannableString.toString()
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            spannableString.setSpan(LinkClickableSpan(text.substring(start, end), LinkUrlUtil.TYPE_USER, atMid.toString()),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @JvmStatic
    fun setSingleAt(spannableString: SpannableStringBuilder, at: At) {
        spannableString.setSpan(LinkClickableSpan(spannableString.subSequence(at.start, at.end).toString(),
                        LinkUrlUtil.TYPE_USER, at.id.toString()),
                at.start, at.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    @JvmStatic
    fun setAtLink(ats: List<At>?, vararg textViews: TextView) {
        if (ats == null || ats.isEmpty()) return
        for (textView in textViews) {
            if (TextUtils.isEmpty(textView.text)) continue
            val text = textView.text.toString()
            val spannableString = SpannableString(textView.text)

            for (at in ats) {
                spannableString.setSpan(LinkClickableSpan(text.substring(at.start, at.end), LinkUrlUtil.TYPE_USER, at.id.toString()),
                        at.start, at.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            textView.text = spannableString
            textView.setOnTouchListener(SHARED_TOUCH_LISTENER)
        }
    }

    @JvmStatic
    fun setTopSpan(spannableString: SpannableStringBuilder) {
        spannableString.setSpan(ForegroundColorSpan(ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt())), 0, ReplyApi.TOP_TIP.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }

    @JvmStatic
    fun getLevelBadge(context: Context, userInfo: UserInfo): ImageSpan {
        var level = userInfo.level

        if (level <= -1 || level >= 7) level = 0
        if (userInfo.is_senior_member == 1) level = 7

        val drawable = getDrawable(context, levelBadges[level])

        val lineHeight = getTextHeightWithSize(context)
        var lineWidth = lineHeight * 1.56f
        if (userInfo.is_senior_member == 1) lineWidth = lineHeight * 1.96f
        drawable.setBounds(0, 0, lineWidth.toInt(), lineHeight.toInt())
        return ImageSpan(drawable)
    }

    @JvmStatic
    fun getTextHeightWithSize(context: Context): Float {
        // v5.0.2-alpha10：该值只取决于「12sp 在当前 scaledDensity 下的像素高度」，
        // 但原来每次调用都要 new Paint() + TypedValue 换算 + 取 fontMetrics，
        // 而 getLevelBadge 在每条评论/回复 bind 时都会调用它。
        // 这里按 scaledDensity 做单槽缓存：设置里改字号/密度（scale 变化）时会自动重算，输出不变。
        val metrics = context.resources.displayMetrics
        val scale = metrics.scaledDensity
        if (cachedTextHeightScale == scale) return cachedTextHeight
        val paint = Paint()
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)
        val fontMetrics = paint.fontMetrics
        val height = fontMetrics.descent - fontMetrics.ascent
        cachedTextHeightScale = scale
        cachedTextHeight = height
        return height
    }

    /** 徽章文字高度缓存（见 getTextHeightWithSize 说明；多线程并发下最坏只是重算一次，结果不变） */
    private var cachedTextHeightScale = -1f
    private var cachedTextHeight = 0f

    @JvmStatic
    fun getDrawable(context: Context, @DrawableRes res: Int): Drawable {
        val drawable = ResourcesCompat.getDrawable(context.resources, res, context.theme)
        return drawable ?: BitmapDrawable()
    }

    class LinkClickableSpan(private val text: String, private val type: Int, private val `val`: String?) : ClickableSpan() {
        constructor(text: String, type: Int) : this(text, type, null)

        override fun onClick(@NonNull widget: View) {
            when (type) {
                LinkUrlUtil.TYPE_USER ->
                    widget.context.startActivity(Intent(widget.context, UserInfoActivity::class.java).putExtra("mid", java.lang.Long.parseLong(`val`)))
                LinkUrlUtil.TYPE_WEB_URL ->
                    LinkUrlUtil.handleWebURL(widget.context, text)
                LinkUrlUtil.TYPE_BVID ->
                    TerminalContext.getInstance().enterVideoDetailPage(widget.context, text)
                LinkUrlUtil.TYPE_AVID ->
                    TerminalContext.getInstance().enterVideoDetailPage(widget.context, java.lang.Long.parseLong(text.replace("av", "")))
                LinkUrlUtil.TYPE_CVID ->
                    TerminalContext.getInstance().enterArticleDetailPage(widget.context, java.lang.Long.parseLong(text.replace("cv", "")))
            }
        }

        override fun updateDrawState(@NonNull ds: TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = false
            ds.color = Color.rgb(0x66, 0xcc, 0xff)
        }
    }

    // 查到的一种LinkMovementMethod问题的解决方法
    class ClickableSpanTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (v !is TextView) {
                return false
            }
            val widget = v
            val text = widget.text
            if (text !is Spanned) {
                return false
            }
            val buffer = text
            val action = event.action
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                var x = event.x.toInt()
                var y = event.y.toInt()

                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop

                x += widget.scrollX
                y += widget.scrollY

                val layout = widget.layout
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())

                val links = buffer.getSpans(off, off, ClickableSpan::class.java)

                if (links.isNotEmpty()) {
                    val link = links[0]
                    if (action == MotionEvent.ACTION_UP) {
                        link.onClick(widget)
                    }
                    return true
                }
            }
            return false
        }
    }

    private val levelBadges = intArrayOf(
            R.mipmap.level_0,
            R.mipmap.level_1,
            R.mipmap.level_2,
            R.mipmap.level_3,
            R.mipmap.level_4,
            R.mipmap.level_5,
            R.mipmap.level_6,
            R.mipmap.level_h
    )

    /**
     * 共享的链接触摸监听器（v5.0.2-alpha10）。
     *
     * ClickableSpanTouchListener 本身**没有任何状态**（onTouch 只用入参 view/event），
     * 但 setLink / setAtLink 与 ReplyAdapter 原来每设置一次就 new 一个对象 ——
     * 评论列表每条 bind 一次，属于可以完全省掉的短命对象。共用同一实例行为不变。
     */
    @JvmField
    val SHARED_TOUCH_LISTENER: ClickableSpanTouchListener = ClickableSpanTouchListener()
}
