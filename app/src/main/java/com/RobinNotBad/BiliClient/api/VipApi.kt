package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.VipInfo
import com.RobinNotBad.BiliClient.util.NetWorkUtil

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.util.ArrayList

object VipApi {
    @Throws(JSONException::class, IOException::class)
    @JvmStatic
    fun getVipInfo(): VipInfo {
        val url = "https://api.bilibili.com/x/vip/privilege/my"
        val all = NetWorkUtil.getJson(url)
        if (all.getInt("code") != 0) throw JSONException(all.getString("message"))

        val data = all.getJSONObject("data")
        val vipInfo = VipInfo()

        vipInfo.isShortVip = data.optBoolean("is_short_vip", false)
        vipInfo.isFreightOpen = data.optBoolean("is_freight_open", false)
        vipInfo.level = data.optInt("level", 0)
        vipInfo.curExp = data.optLong("cur_exp", 0)
        vipInfo.nextExp = data.optLong("next_exp", 0)
        vipInfo.isVip = data.optBoolean("is_vip", false)
        vipInfo.isSeniorMember = data.optInt("is_senior_member", 0)
        vipInfo.format060102 = data.optInt("format060102", 0)
        vipInfo.isOverdueVip = data.optBoolean("is_overdue_vip", false)
        vipInfo.vipStatus = data.optInt("vip_status", 0)
        vipInfo.vipType = data.optInt("vip_type", 0)
        vipInfo.keeptimeEnd = data.optLong("keeptime_end", 0)
        vipInfo.vipDueDate = data.optLong("vip_due_date", 0)
        vipInfo.vipIsAnnual = data.optBoolean("vip_is_annual", false)
        vipInfo.vipIsMonth = data.optBoolean("vip_is_month", false)
        vipInfo.vipIsNewUser = data.optBoolean("vip_is_new_user", false)
        vipInfo.bindPhone = data.optString("bind_phone", "")

        val taobaoAccount = data.optJSONObject("taobao_account")
        if (taobaoAccount != null) {
            vipInfo.taobaoAccount = taobaoAccount.toString()
        }

        val list = data.optJSONArray("list")
        if (list != null) {
            val privilegeList = ArrayList<VipInfo.Privilege>()
            for (i in 0 until list.length()) {
                val privilege = list.getJSONObject(i)
                val privilegeItem = VipInfo.Privilege()
                privilegeItem.type = privilege.optInt("type", 0)
                privilegeItem.state = privilege.optInt("state", 0)
                privilegeItem.expireTime = privilege.optLong("expire_time", 0)
                privilegeItem.vipType = privilege.optInt("vip_type", 0)
                privilegeItem.nextReceiveDays = privilege.optInt("next_receive_days", 0)
                privilegeItem.periodEndUnix = privilege.optLong("period_end_unix", 0)
                privilegeList.add(privilegeItem)
            }
            vipInfo.privilegeList = privilegeList
        }

        return vipInfo
    }

    @Throws(IOException::class, JSONException::class)
    @JvmStatic
    fun addExperience(): JSONObject {
        val url = "https://api.bilibili.com/x/vip/experience/add"
        val cookies = NetWorkUtil.getCookies()
        val csrf = cookies.get("bili_jct")
        val formData = NetWorkUtil.FormData()
        formData.put("csrf", csrf)
        val response = NetWorkUtil.post(url, formData.toString())
        return response.use { resp ->
            val body = resp.body() ?: throw JSONException("在访问" + url + "时返回数据为空")
            JSONObject(body.use { it.string() })
        }
    }
}
