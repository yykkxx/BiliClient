package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.Opus
import com.RobinNotBad.BiliClient.model.OpusParagraph
import com.RobinNotBad.BiliClient.model.Stats
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.JsonUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList

import okhttp3.Response
import okhttp3.ResponseBody

object OpusApi {

    @JvmSuppressWildcards
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getOpus(id: Long): Opus {
        val opus = Opus()
        opus.type = Opus.TYPE_DYNAMIC
        opus.id = id

        val url: String
        if (id > 100000000)
            url = "https://www.bilibili.com/opus/" + id // 别问，问就是动态和专栏都被统一了，判断不了类型，只能判断id长度了。能用。
        else url = "https://www.bilibili.com/read/cv" + id
        try {
            var response = NetWorkUtil.get(url)
            if (id <= 100000000)
                response = NetWorkUtil.get(response.headers().get("location")) // 访问/read/cv[id]的话会重定向到/opus/，这里要手动“重定向”，因为OkHTTP不认。
            val responseBody = response.body()
            if (responseBody == null) return opus

            val html = responseBody.string()

            val detail = JSONObject(JsonUtil.search(html, "detail", ""))  //效率不高 能用就行 死去的jsonUtil居然还能发光发热

            val basic = detail.getJSONObject("basic")
            opus.commentId = basic.optString("comment_id_str", "0").toLong()
            opus.commentType = basic.optInt("comment_type")

            if (detail.isNull("modules")) return opus    //isNull其实涵盖了!has的情况，之前都是咋想的判断两次，我简直是sb
            val modules = detail.getJSONArray("modules")

            for (i in 0 until modules.length()) {
                val module = modules.getJSONObject(i)
                when (module.optString("module_type")) {
                    "MODULE_TYPE_TITLE" ->
                        opus.title = module.getJSONObject("module_title").getString("text")
                    "MODULE_TYPE_TOP" -> {
                        val topImages: ArrayList<String> = ArrayList()
                        val module_top = module.getJSONObject("module_top")
                        val display = module_top.getJSONObject("display")
                        val displayType = display.optInt("type")
                        if (displayType == 1) {
                            val album = display.getJSONObject("album")
                            val pics = album.getJSONArray("pics")
                            for (j in 0 until pics.length()) {
                                topImages.add(pics.getJSONObject(j).getString("url"))
                            }
                        }
                        opus.topImages = topImages
                        Logu.d("yes")
                    }
                    "MODULE_TYPE_AUTHOR" -> {
                        val module_author = module.getJSONObject("module_author")    //我感觉b站也是一个巨大的草台班子，用户信息格式都好几种，头像有avatar有face有head的，他们自己的程序员不累吗……
                        val author = UserInfo()
                        author.mid = module_author.getLong("mid")
                        author.name = module_author.getString("name")
                        author.followed = module_author.optBoolean("following", false)
                        author.avatar = module_author.getString("face")
                        if (!module_author.isNull("vip"))
                            author.vip_nickname_color = module_author.getJSONObject("vip").optString("nickname_color", "")

                        opus.pubTime = module_author.getString("pub_time")
                        opus.upInfo = author
                    }
                    "MODULE_TYPE_CONTENT" -> {
                        val paragraphs = module.getJSONObject("module_content").getJSONArray("paragraphs")
                        opus.paragraphs = analyzeParagraphs(paragraphs)
                    }
                    "MODULE_TYPE_STAT" ->
                        opus.stats = Stats.fromOpus(module.optJSONObject("module_stat"))
                }
            }

            if (opus.upInfo == null) opus.upInfo = UserInfo()
            if (opus.stats == null) opus.stats = Stats()
        } catch (e: IllegalArgumentException) { // 取不出来的时候，会重定向，但重定向的域名是//开头的，会报错
            //这里给opus设置一个参数，让OpusInfoActivity跳转到旧版的DynamicInfoActivity，从而无需重写解析
            //判断方式很简单粗暴，看报错信息里有没有URL这个关键字，有就是跳转错误
            val errMsg = e.message
            if (errMsg != null && errMsg.contains("URL")) opus.type = Opus.TYPE_DYNAMIC_OLD_STYLE
            else MsgUtil.err(e)
            return opus

            /*
            url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?";
            url += "timezone_offset=-480&platform=web&gaia_source=main_web&id=" + id + "&features=itemOpusStyle,opusBigCover,onlyfansVote,endFooterHidden,decorationCard,onlyfansAssetsV2,ugcDelete,onlyfansQaCard,editable,opusPrivateVisible,avatarAutoTheme&web_location=333.1368&x-bili-device-req-json=%7B%22platform%22:%22web%22,%22device%22:%22pc%22%7D&x-bili-web-req-json=%7B%22spm_id%22:%22333.1368%22%7D";
            Response response = NetWorkUtil.get(ConfInfoApi.signWBI(url));
            ResponseBody responseBody = response.body();
            if(responseBody == null) return opus;

            JSONObject json = new JSONObject(responseBody.string());
            JSONObject item = json.getJSONObject("data").getJSONObject("item");

            analyzeOldStyleDynamic(opus, item);
             */
        }
        // B站是会做图文的

        opus.cover = ""
        return opus
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeParagraphs(jsonArray: JSONArray): Array<OpusParagraph?> {
        val paragraphs = arrayOfNulls<OpusParagraph>(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            val paragraphJson = jsonArray.getJSONObject(i)
            val paragraph = OpusParagraph(paragraphJson)
            paragraphs[i] = paragraph
        }
        return paragraphs
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun analyzeOldStyleDynamic(opus: Opus, item: JSONObject) {
        val basic = item.getJSONObject("basic")
        opus.commentId = basic.optString("comment_id_str", "0").toLong()
        opus.commentType = basic.optInt("comment_type")

        val dynamicType = item.getString("type")

        if (item.isNull("modules")) return
        val modules = item.getJSONObject("modules")

        //up主信息
        val author = UserInfo()
        if (!modules.isNull("module_author")) {
            val module_author = modules.getJSONObject("module_author")
            author.mid = module_author.getLong("mid")
            author.name = module_author.getString("name")
            author.followed = module_author.optBoolean("following", false)
            author.avatar = module_author.getString("face")
            if (!module_author.isNull("vip"))
                author.vip_nickname_color = module_author.getJSONObject("vip").optString("nickname_color", "")
            opus.pubTime = module_author.getString("pub_time")
        }
        opus.upInfo = author

        if (dynamicType == "DYNAMIC_TYPE_NONE") {
            opus.content = "[动态不存在]"
            return
        }

        //动态主体内容
        val module_dynamic = modules.getJSONObject("module_dynamic")

        val paragraphList: ArrayList<OpusParagraph> = ArrayList()

        if (!module_dynamic.isNull("desc")) {
            val `object` = JSONObject()
            `object`.put("para_type", OpusParagraph.TYPE_TEXT_OPUS)
            `object`.put("data", module_dynamic.getJSONObject("desc").getJSONArray("rich_text_nodes"))
            paragraphList.add(OpusParagraph(`object`))
        }

        if (!module_dynamic.isNull("major")) {
            val major = module_dynamic.getJSONObject("major")

            if (!major.isNull("opus")) {
                val dynamic_opus = major.getJSONObject("opus")
                val opus_pics = dynamic_opus.getJSONArray("pics")

                // 为了排版正常，这里必须把列表完整传递给OpusParagraph，让OpusParagraph那边解析
                // 这么干主要是为了适配这神秘的代码结构，我研究OpusParagraph的使用方法就研究了半天
                // by Moye

                var `object` = JSONObject()
                `object`.put("para_type", OpusParagraph.TYPE_TEXT_OPUS)
                `object`.put("data", dynamic_opus.getJSONObject("summary").getJSONArray("rich_text_nodes"))
                paragraphList.add(OpusParagraph(`object`))

                `object` = JSONObject()
                `object`.put("para_type", OpusParagraph.TYPE_PIC)
                `object`.put("pic", JSONObject().put("pics", opus_pics))
                paragraphList.add(OpusParagraph(`object`))
            }

            if (!major.isNull("archive")) {
                // 这里是视频卡片
            }

        }

        val paragraphArray = arrayOfNulls<OpusParagraph>(paragraphList.size)
        for (i in paragraphList.indices) paragraphArray[i] = paragraphList[i]
        opus.paragraphs = paragraphArray

        val module_stat = modules.getJSONObject("module_stat")
        val stats = Stats()
        stats.reply = module_stat.getJSONObject("comment").getInt("count")
        stats.like = module_stat.getJSONObject("like").getInt("count")

        opus.stats = stats
    }
}
