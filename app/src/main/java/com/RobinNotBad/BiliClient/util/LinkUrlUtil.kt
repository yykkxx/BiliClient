package com.RobinNotBad.BiliClient.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Pair

import com.RobinNotBad.BiliClient.activity.search.SearchActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity

import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Matcher
import java.util.regex.Pattern

import okhttp3.Response
import okhttp3.ResponseBody

/**
 * Kotlin 重构版（原 LinkUrlUtil.java）。
 *
 * Java 端兼容说明：
 * - 原类只有静态成员（常量 + 静态方法），因此用 object 承载，方法加 @JvmStatic，
 *   保证 SearchActivity.java 的 LinkUrlUtil.handleId(...) / StringUtil.java 的
 *   LinkUrlUtil.handleWebURL(...) 调用方式不变。
 * - TYPE_* 常量原本被 StringUtil.java 用作 switch 的 case 标签，因此这里用 const val
 *   （编译期常量）；@JvmField 会失去常量性，Java 端 case 标签将无法编译。
 * - BV_PATTERN / AV_PATTERN / CV_PATTERN / UID_PATTERN 使用 @JvmField，
 *   Java 端仍为静态字段（可 static import），Kotlin 端用 LinkUrlUtil.XXX 访问。
 * - handleShortUrl / parseId 原为 private static，保留在 object 内为 private。
 */
object LinkUrlUtil {
    /** Java 端作为 switch case 标签使用，必须是编译期常量 */
    const val TYPE_USER = -1
    const val TYPE_WEB_URL = 0
    const val TYPE_BVID = 1
    const val TYPE_AVID = 2
    const val TYPE_CVID = 3
    const val TYPE_UID = 4

    @JvmField
    val BV_PATTERN: Pattern = Pattern.compile("BV[A-Za-z0-9]{10}")

    @JvmField
    val AV_PATTERN: Pattern = Pattern.compile("av\\d{1,10}")

    @JvmField
    val CV_PATTERN: Pattern = Pattern.compile("cv\\d{1,10}")

    @JvmField
    val UID_PATTERN: Pattern = Pattern.compile("^(?i)uid\\d+$")

    @JvmStatic
    fun handleWebURL(context: Context, text: String) {
        var text = text
        try {
            text = (if (text.startsWith("http://") || text.startsWith("https://")) text else (if (text.startsWith("//")) "http:" + text else "http://" + text))
            // 很傻逼的一系列解析
            val url = URL(text)
            var path = url.path
            val index = path.indexOf('?')
            if (index != -1) {
                path = path.substring(0, index)
            }
            val domain = url.host
            if (domain == "b23.tv") {
                handleShortUrl(context, text)
                return
            }
            if (domain.matches(".*\\.bilibili\\.com$".toRegex())) {
                if (path.isNotEmpty()) {
                    // 等价于原 Java 的 path.replaceAll(".*/([^/]+)/?$", "$1")
                    var lastPathItem = Regex(".*/([^/]+)/?$").replace(path, "$1")
                    if (domain == "space.bilibili.com") {
                        lastPathItem = "UID" + lastPathItem
                    }
                    if (domain == "search.bilibili.com") {
                        val intent = Intent(context, SearchActivity::class.java)
                        intent.putExtra("keyword", URLDecoder.decode(url.query.replace("keyword=", "")))
                        context.startActivity(intent)
                        return
                    }
                    val parse = parseId(lastPathItem)
                    if (parse != null) {
                        val `val` = parse.first
                        val type = parse.second
                        when (type) {
                            TYPE_BVID -> {
                                TerminalContext.getInstance().enterVideoDetailPage(context, `val`)
                                return
                            }
                            TYPE_AVID -> {
                                TerminalContext.getInstance().enterVideoDetailPage(context, java.lang.Long.parseLong(`val`.replace("av", "")))
                                return
                            }
                            TYPE_CVID -> {
                                TerminalContext.getInstance().enterArticleDetailPage(context, java.lang.Long.parseLong(`val`.replace("cv", "")))
                                return
                            }
                            TYPE_UID -> {
                                context.startActivity(Intent(context, UserInfoActivity::class.java).putExtra("mid", java.lang.Long.parseLong(`val`.replaceFirst("(?i)^uid".toRegex(), ""))))
                                return
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            MsgUtil.err(e)
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(text)))
        } catch (e: ActivityNotFoundException) {
            MsgUtil.showMsg("没有可处理此链接的应用！")
        } catch (th: Throwable) {
            MsgUtil.err(th)
        }
    }

    @JvmStatic
    fun handleId(context: Context, text: String) {
        val parse = parseId(text)
        if (parse != null) {
            val `val` = parse.first
            val type = parse.second
            when (type) {
                TYPE_BVID -> TerminalContext.getInstance().enterVideoDetailPage(context, `val`)
                TYPE_AVID -> TerminalContext.getInstance().enterVideoDetailPage(context, java.lang.Long.parseLong(`val`.replace("av", "")))
                TYPE_CVID -> TerminalContext.getInstance().enterArticleDetailPage(context, java.lang.Long.parseLong(`val`.replace("cv", "")))
                TYPE_UID -> context.startActivity(Intent(context, UserInfoActivity::class.java).putExtra("mid", java.lang.Long.parseLong(`val`.replaceFirst("(?i)^uid".toRegex(), ""))))
                TYPE_WEB_URL -> handleWebURL(context, `val`)
            }
        }
    }

    private fun handleShortUrl(context: Context, url: String) {
        CenterThreadPool.run(Runnable {
            try {
                val response: Response = NetWorkUtil.get(url, NetWorkUtil.webHeaders, object : NetWorkUtil.RedirectHandler {
                    override fun handleRedirect(location: String?) {
                        if (location != null) handleWebURL(context, location)
                    }
                })
                if (response.code() == 200) {
                    val body: ResponseBody? = response.body()
                    if (body != null) {
                        val json = JSONObject(body.string())
                        if (json.has("code") && json.getInt("code") == -404) {
                            MsgUtil.showMsg("啥都木有~")
                        }
                    }
                }
            } catch (e: IOException) {
                MsgUtil.showMsg("解析失败！")
            } catch (e: JSONException) {
                MsgUtil.showMsg("解析失败！")
            }
        })
    }

    private fun parseId(item: String): Pair<String, Int>? {
        var matcher: Matcher

        matcher = BV_PATTERN.matcher(item)
        if (matcher.find()) {
            return Pair<String, Int>(matcher.group(), TYPE_BVID)
        }

        matcher = AV_PATTERN.matcher(item)
        if (matcher.find()) {
            return Pair<String, Int>(matcher.group(), TYPE_AVID)
        }

        matcher = CV_PATTERN.matcher(item)
        if (matcher.find()) {
            return Pair<String, Int>(matcher.group(), TYPE_CVID)
        }

        matcher = UID_PATTERN.matcher(item)
        if (matcher.find()) {
            return Pair<String, Int>(matcher.group(), TYPE_UID)
        }

        matcher = Pattern.compile("(https?|ftp|file)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]").matcher(item)
        if (matcher.find()) {
            return Pair<String, Int>(matcher.group(), TYPE_WEB_URL)
        }

        return null
    }
}
