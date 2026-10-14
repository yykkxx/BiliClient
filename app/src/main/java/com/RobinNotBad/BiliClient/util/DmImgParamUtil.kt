package com.RobinNotBad.BiliClient.util

import android.util.Base64
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.HashMap
import java.util.Objects
import java.util.Random

object DmImgParamUtil {

    @JvmStatic
    fun getDmImgParams(): Map<String, String> {
        val map = HashMap<String, String>()
        map["dm_img_str"] = "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ"
        map["dm_cover_img_str"] = "QU5HTEUgKEludGVsLCBJbnRlbChSKSBVSEQgR3JhcGhpY3MgNjMwIERpcmVjdDNEMTEgdnNfNV8wIHBzXzVfMCwgRDNEMTEpR29vZ2xlIEluYy4gKEludGVsKQ"
        try {
            map["dm_img_list"] = generateDmImgList()
            map["dm_img_inter"] = generateDmImgInter()
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
        return map
    }

    @JvmStatic
    fun getDmImgParamsUrl(orig_url: String): String {
        val builder = Objects.requireNonNull(HttpUrl.parse(orig_url))!!.newBuilder()
        val params = getDmImgParams()
        for ((key, value) in params) {
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    @JvmStatic
    fun f114i(a: Int, b: Int, i: Int): IntArray {
        val random = Random()
        val t = (random.nextDouble() * (114 * i)).toInt()
        return intArrayOf(3 * a + 2 * b + t, 4 * a - 5 * b + t, t)
    }

    @JvmStatic
    fun f114(a: Int, b: Int): IntArray {
        val random = Random()
        val t = (random.nextDouble() * 114).toInt()
        return intArrayOf(2 * a + 2 * b + 3 * t, 4 * a - b + t, t)
    }

    @JvmStatic
    fun f514(a: Int, b: Int): IntArray {
        val random = Random()
        val t = (random.nextDouble() * 514).toInt()
        return intArrayOf(3 * a + 2 * b + t, 4 * a - 4 * b + 2 * t, t)
    }

    private val timestamps = intArrayOf(2943, 3046, 3152, 3252, 3354, 3454, 3558, 3665, 3767, 5566, 5676, 5778, 5881, 7296, 7573, 135289)

    @Throws(JSONException::class)
    private fun generateDmImgList(): String {
        val random = Random()
        val dmImgListJson = JSONArray()
        dmImgListJson.put(
            JSONObject()
                .put("x", 2355)
                .put("y", 725)
                .put("z", 0)
                .put("timestamp", 2145)
                .put("k", (random.nextDouble() * 67 + 60).toInt())
                .put("type", 0)
        )
        val num = random.nextInt(timestamps.size - 6) + 6
        for (i in 0..num) {
            dmImgListJson.put(generateDmImgItem(random.nextInt(200), random.nextInt(200), i + 1, timestamps[i], if (i == num) 1 else 0))
        }
        return dmImgListJson.toString()
    }

    private fun generateDmImgItem(x: Int, y: Int, index: Int, timestamp: Int, type: Int): JSONObject {
        val xyz = f114i(x, y, index)
        val random = Random()
        try {
            return JSONObject()
                .put("x", xyz[0])
                .put("y", xyz[1])
                .put("z", xyz[2])
                .put("timestamp", timestamp)
                .put("k", (random.nextDouble() * 67 + 60).toInt())
                .put("type", type)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
    }

    private val classNames = arrayOf(
        "clearfix", "g-search", "section", "loading", "full-rows", "n-btn",
        "router-link-exact-active", "router-link-active", "active"
    )

    @Throws(JSONException::class)
    private fun generateDmImgInter(): String {
        val random = Random()
        val result = JSONObject()
        val ds = JSONArray()
        val y = getRandomNumberInRange(-1500, -300)
        val x = getRandomNumberInRange(100, 700)
        val width = getRandomNumberInRange(100, 8000)
        val height = getRandomNumberInRange(20, 500)
        val xyz1 = f114(y, x)
        val xyz2 = f514(width, height)
        ds.put(
            JSONObject()
                .put("t", random.nextInt(6))
                .put(
                    "c", Base64.encodeToString(
                        (classNames[random.nextInt(classNames.size)] + " " + classNames[random.nextInt(classNames.size)]).toByteArray(),
                        Base64.DEFAULT
                    )
                )
                .put("p", JSONArray().put(xyz1[0]).put(xyz1[2]).put(xyz1[1]))
                .put("s", JSONArray().put(xyz2[2]).put(xyz2[0]).put(xyz2[1]))
        )
        result.put("ds", ds)
            .put("wh", JSONArray(listOf(f114(width, height).toList())))
            .put("of", JSONArray(listOf(f514(y, x).toList())))
        return result.toString()
    }

    private fun getRandomNumberInRange(min: Int, max: Int): Int {
        if (min > max) {
            throw IllegalArgumentException("max must be greater than or equal to min")
        }
        val random = Random()
        return random.nextInt((max - min) + 1) + min
    }
}
