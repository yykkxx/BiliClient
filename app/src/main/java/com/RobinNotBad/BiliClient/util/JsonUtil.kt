package com.RobinNotBad.BiliClient.util

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.util.ArrayList

/*
2023-12-22 RobinNotBad
这玩意是可以用来快速拆解的，但也许并没有什么用
哎你别说这玩意还真能从一个html里直接拎出来json，在opus里还怪方便的  //2025-07-13
 */

object JsonUtil {

    @JvmStatic
    fun search(input: JSONObject, name: String?, defaultValue: String?): String? {
        return search(input.toString(), name, defaultValue)
    }

    @JvmStatic
    fun search(input: String?, name: String?, defaultValue: String?): String? {
        if (name == null || name.isEmpty() || input == null) return defaultValue

        val searchKey = "\"" + name + "\":"
        val index = input.indexOf(searchKey)   // "name":
        if (index == -1) return defaultValue

        var count = 0
        val i = index + searchKey.length
        for (j in i until input.length) {
            val thisChar = input[j]
            val nextChar = input[j + 1]
            if (thisChar == '{' || thisChar == '[') count++
            if (thisChar == '}' || thisChar == ']') count--

            if ((nextChar == ',' || nextChar == '}' || nextChar == ']') && count == 0) {
                if (input[i] == '\"') {
                    return StringUtil.unEscape(input.substring(i + 1, j))
                } else return input.substring(i, j + 1)
            }
        }
        return defaultValue
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun jsonToArrayList(jsonArray: JSONArray, reverse: Boolean): ArrayList<String> {
        val arrayList = ArrayList<String>()
        if (reverse) {
            for (i in jsonArray.length() - 1 downTo 0) {
                arrayList.add(jsonArray.get(i) as String)
            }
        } else {
            for (i in 0 until jsonArray.length()) {
                arrayList.add(jsonArray.get(i) as String)
            }
        }
        return arrayList
    }

    /*
     * json列项函数 用于表情包，自己写的以替换luern的库
     * 我当时为什么还要手写这个的解析？明明有现成的不查一下……
     */
    @JvmStatic
    fun getJsonKeys(jsonObject: JSONObject): ArrayList<String> {
        val list = ArrayList<String>()
        val it = jsonObject.keys()
        while (it.hasNext()) {
            val s = it.next()
            list.add(s)
        }
        return list
    }

}
