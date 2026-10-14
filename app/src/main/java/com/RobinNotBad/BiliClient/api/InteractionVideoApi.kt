package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.InteractionVideoData
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONObject
import org.json.JSONException
import java.io.IOException

object InteractionVideoApi {
    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun getEdgeInfo(aid: Long, bvid: String?, graphVersion: Long, edgeId: Long): InteractionVideoData {
        val urlBuilder = StringBuilder("https://api.bilibili.com/x/stein/edgeinfo_v2?")

        if (aid > 0) {
            urlBuilder.append("aid=").append(aid)
        } else if (bvid != null && !bvid.isEmpty()) {
            urlBuilder.append("bvid=").append(bvid)
        } else {
            throw IllegalArgumentException("aid和bvid必须提供其中一个")
        }

        urlBuilder.append("&graph_version=").append(graphVersion)

        if (edgeId > 0) {
            urlBuilder.append("&edge_id=").append(edgeId)
        }

        val url = urlBuilder.toString()
        Logu.d("互动视频API", url)

        val response = NetWorkUtil.getJson(url, NetWorkUtil.webHeaders)

        val code = response.optInt("code", -1)
        if (code != 0) {
            val message = response.optString("message", "未知错误")
            throw java.io.IOException("API返回错误: " + code + " - " + message)
        }

        val data = response.getJSONObject("data")
        return parseInteractionData(data)
    }

    private fun parseInteractionData(data: JSONObject): InteractionVideoData {
        val result = InteractionVideoData()

        result.title = data.optString("title", "")
        result.edgeId = data.optLong("edge_id", 0)
        result.isLeaf = data.optInt("is_leaf", 0)
        result.noTutorial = data.optInt("no_tutorial", 0)
        result.noBacktracking = data.optInt("no_backtracking", 0)
        result.noEvaluation = data.optInt("no_evaluation", 0)

        if (data.has("story_list") && !data.isNull("story_list")) {
            val storyArray = data.getJSONArray("story_list")
            result.storyList = ArrayList()
            for (i in 0 until storyArray.length()) {
                val storyObj = storyArray.getJSONObject(i)
                val node = InteractionVideoData.InteractionStoryNode()
                node.nodeId = storyObj.optLong("node_id", 0)
                node.edgeId = storyObj.optLong("edge_id", 0)
                node.title = storyObj.optString("title", "")
                node.cid = storyObj.optLong("cid", 0)
                node.startPos = storyObj.optLong("start_pos", 0)
                node.cover = storyObj.optString("cover", "")
                node.isCurrent = storyObj.optInt("is_current", 0)
                node.cursor = storyObj.optLong("cursor", 0)
                result.storyList!!.add(node)
            }
        }

        if (data.has("edges") && !data.isNull("edges")) {
            val edgesObj = data.getJSONObject("edges")
            result.edges = parseEdge(edgesObj)
        }

        if (data.has("preload") && !data.isNull("preload")) {
            val preloadObj = data.getJSONObject("preload")
            result.preload = parsePreload(preloadObj)
        }

        if (data.has("hidden_vars") && !data.isNull("hidden_vars")) {
            val varsArray = data.getJSONArray("hidden_vars")
            result.hiddenVars = ArrayList()
            for (i in 0 until varsArray.length()) {
                val varObj = varsArray.getJSONObject(i)
                val `var` = InteractionVideoData.InteractionHiddenVar()
                `var`.value = varObj.optLong("value", 0)
                `var`.id = varObj.optString("id", "")
                `var`.idV2 = varObj.optString("id_v2", "")
                `var`.type = varObj.optInt("type", 1)
                `var`.isShow = varObj.optInt("is_show", 0)
                `var`.name = varObj.optString("name", "")
                `var`.skipOverwrite = varObj.optInt("skip_overwrite", 0)
                result.hiddenVars!!.add(`var`)
            }
        }

        return result
    }

    private fun parseEdge(edgesObj: JSONObject): InteractionVideoData.InteractionEdge {
        val edge = InteractionVideoData.InteractionEdge()

        if (edgesObj.has("dimension") && !edgesObj.isNull("dimension")) {
            val dimObj = edgesObj.getJSONObject("dimension")
            val dimension = InteractionVideoData.InteractionDimension()
            dimension.width = dimObj.optInt("width", 0)
            dimension.height = dimObj.optInt("height", 0)
            dimension.rotate = dimObj.optInt("rotate", 0)
            dimension.sar = dimObj.optString("sar", "")
            edge.dimension = dimension
        }

        if (edgesObj.has("questions") && !edgesObj.isNull("questions")) {
            val questionsArray = edgesObj.getJSONArray("questions")
            edge.questions = ArrayList()
            for (i in 0 until questionsArray.length()) {
                val questionObj = questionsArray.getJSONObject(i)
                val question = parseQuestion(questionObj)
                edge.questions!!.add(question)
            }
        }

        if (edgesObj.has("skin") && !edgesObj.isNull("skin")) {
            val skinObj = edgesObj.getJSONObject("skin")
            val skin = InteractionVideoData.InteractionSkin()
            skin.choiceImage = skinObj.optString("choice_image", "")
            skin.titleTextColor = skinObj.optString("title_text_color", "")
            skin.titleShadowColor = skinObj.optString("title_shadow_color", "")
            skin.titleShadowOffsetX = skinObj.optInt("title_shadow_offset_x", 0)
            skin.titleShadowOffsetY = skinObj.optInt("title_shadow_offset_y", 0)
            skin.titleShadowRadius = skinObj.optInt("title_shadow_radius", 0)
            skin.progressbarColor = skinObj.optString("progressbar_color", "")
            skin.progressbarShadowColor = skinObj.optString("progressbar_shadow_color", "")
            edge.skin = skin
        }

        return edge
    }

    private fun parseQuestion(questionObj: JSONObject): InteractionVideoData.InteractionQuestion {
        val question = InteractionVideoData.InteractionQuestion()
        question.id = questionObj.optLong("id", 0)
        question.type = questionObj.optInt("type", 0)
        question.startTimeR = questionObj.optLong("start_time_r", 0)
        question.duration = questionObj.optLong("duration", -1)
        question.pauseVideo = questionObj.optInt("pause_video", 0)
        question.title = questionObj.optString("title", "")
        question.fadeInTime = questionObj.optLong("fade_in_time", 0)
        question.fadeOutTime = questionObj.optLong("fade_out_time", 0)

        if (questionObj.has("choices") && !questionObj.isNull("choices")) {
            val choicesArray = questionObj.getJSONArray("choices")
            question.choices = ArrayList()
            for (i in 0 until choicesArray.length()) {
                val choiceObj = choicesArray.getJSONObject(i)
                val choice = parseChoice(choiceObj)
                question.choices!!.add(choice)
            }
        }

        return question
    }

    private fun parseChoice(choiceObj: JSONObject): InteractionVideoData.InteractionChoice {
        val choice = InteractionVideoData.InteractionChoice()
        choice.id = choiceObj.optLong("id", 0)
        choice.platformAction = choiceObj.optString("platform_action", "")
        choice.nativeAction = choiceObj.optString("native_action", "")
        choice.condition = choiceObj.optString("condition", "")
        choice.cid = choiceObj.optLong("cid", 0)
        choice.x = choiceObj.optInt("x", 0)
        choice.y = choiceObj.optInt("y", 0)
        choice.textAlign = choiceObj.optInt("text_align", 0)
        choice.option = choiceObj.optString("option", "")
        choice.isDefault = choiceObj.optInt("is_default", 0)
        choice.isHidden = choiceObj.optInt("is_hidden", 0)

        if (choiceObj.has("selected") && !choiceObj.isNull("selected")) {
            val selectedObj = choiceObj.getJSONObject("selected")
            val anim = InteractionVideoData.InteractionAnimation()
            anim.type = selectedObj.optString("type", "")
            anim.duration = selectedObj.optLong("duration", 0)
            choice.selected = anim
        }

        if (choiceObj.has("submited") && !choiceObj.isNull("submited")) {
            val submitedObj = choiceObj.getJSONObject("submited")
            val anim = InteractionVideoData.InteractionAnimation()
            anim.type = submitedObj.optString("type", "")
            anim.duration = submitedObj.optLong("duration", 0)
            choice.submited = anim
        }

        return choice
    }

    private fun parsePreload(preloadObj: JSONObject): InteractionVideoData.InteractionPreload {
        val preload = InteractionVideoData.InteractionPreload()

        if (preloadObj.has("video") && !preloadObj.isNull("video")) {
            val videoArray = preloadObj.getJSONArray("video")
            preload.video = ArrayList()
            for (i in 0 until videoArray.length()) {
                val videoObj = videoArray.getJSONObject(i)
                val video = InteractionVideoData.InteractionPreloadVideo()
                video.aid = videoObj.optLong("aid", 0)
                video.cid = videoObj.optLong("cid", 0)
                preload.video!!.add(video)
            }
        }

        return preload
    }
}
