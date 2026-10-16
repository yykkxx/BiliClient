package com.RobinNotBad.BiliClient.api

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Log

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.update.UpdateInfoActivity
import com.RobinNotBad.BiliClient.model.Announcement
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

import org.json.JSONException
import org.json.JSONObject

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar

object AppInfoApi {
    @JvmStatic
    fun check(context: Context) {
        // 讲真这免责声明没啥卵用，写这个也就是半开玩笑的，难道免责声明能挡住律师函吗
        // 而且"fuck_uncle"过分了嗷，咱做第三方软件的真不能这么干……
        if (!SharedPreferencesUtil.getBoolean("disclaimer_shown", false)) {
            MsgUtil.showDialog("免责声明", "使用前请先阅读：\n" + context.getString(R.string.about_to_uncle), 3)
            SharedPreferencesUtil.putBoolean("disclaimer_shown", true)
        }

        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.NIGHT_REMINDER_ENABLE, true)) {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            if (hour >= 23 || hour <= 3) {
                MsgUtil.showDialog("温馨提醒", "夜深了，要注意休息呐~", 3)
            }
        }

        try {
            val version = BiliTerminal.getVersion()
            val curr = ConfInfoApi.getDateCurr()

            checkAnnouncement()

            val last_ver = SharedPreferencesUtil.getInt("app_version_last", 0)
            if (last_ver < version) {
                val update_apk = SharedPreferencesUtil.getString("terminal_update_pkg", "")
                if (!TextUtils.isEmpty(update_apk)) {
                    val file = File(update_apk)
                    if (file.exists()) {
                        if (file.delete()) {
                            SharedPreferencesUtil.putString("terminal_update_pkg", "")
                            MsgUtil.showMsg("更新包已删除")
                        } else MsgUtil.showMsg("更新包删除失败")
                    } else SharedPreferencesUtil.putString("terminal_update_pkg", "")
                }

                MsgUtil.showDialog("提醒", context.getString(R.string.text_update_success), 5)

                if (last_ver != 0) {
                    if (last_ver < 20240606)
                        MsgUtil.showDialog("部分风控问题已解决", "当前的新版本实现了对抗部分类型的风控，建议您重新登录账号以确保成功使用")

                    if (last_ver < 20250329 && SharedPreferencesUtil.getBoolean("player_ui_round", false)) {
                        SharedPreferencesUtil.putInt("paddingV_percent", 3)
                        SharedPreferencesUtil.putInt("paddingH_percent", 7)
                    }
                }
                MsgUtil.showText("更新公告", context.getResources().getString(R.string.update_tip) + "\n\n更新细节：\n" + ToolsUtil.getUpdateLog(context))
                if (ToolsUtil.isDebugBuild())
                    MsgUtil.showDialog("警告", context.getString(R.string.warning_debug))
                SharedPreferencesUtil.putInt("app_version_last", version)
            }

            if (SharedPreferencesUtil.getInt("app_version_check", 0) < curr) {    //限制一天一次
                Log.e("debug", "检查更新")
                SharedPreferencesUtil.putInt("app_version_check", curr)

                checkUpdate(context, false)
            }
        } catch (e: IOException) {
            MsgUtil.showMsg("无法连接到终端公告接口\n也许是服务器宕机了？\n（对软件内容无影响）")
        } catch (e: Exception) {
            Log.e("debug-terminal", e.toString())
            MsgUtil.err("终端接口出现问题（不影响软件内容）", e)
        }
    }

    @JvmField
    val customHeaders = ArrayList<String>()

    init {
        customHeaders.add("User-Agent")
        customHeaders.add(NetWorkUtil.USER_AGENT_WEB)    //防止携带b站cookies导致可能存在的开发者盗号问题（
        customHeaders.add("App-Info")
        try {
            customHeaders.add(asciiHeaderValue(JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("isBeta", BuildConfig.BETA)
                    .put("applicationId", BuildConfig.APPLICATION_ID)
                    .put("buildType", BuildConfig.BUILD_TYPE)
                    .put("debugEnabled", BuildConfig.DEBUG)
                    .toString()))
        } catch (e: JSONException) {
            MsgUtil.showMsg("版本信息json生成出错\n无影响，正常情况下你应该不会遇到")
        }
        customHeaders.add("Device-Info")
        try {
            customHeaders.add(asciiHeaderValue(JSONObject()
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("release", Build.VERSION.RELEASE)
                    .put("product", Build.PRODUCT)
                    .put("brand", Build.BRAND)
                    .put("device", Build.DEVICE)
                    .put("type", Build.TYPE)
                    .put("id", Build.ID)
                    .toString()))
        } catch (e: JSONException) {
            MsgUtil.showMsg("设备信息json生成出错\n无影响，正常情况下你应该不会遇到")
        }
    }

    /**
     * OkHttp 拒绝含非 ASCII 字符的请求头值（#1）：
     * 渠道包 versionName 为 "QQ音乐" 时，App-Info 头直接抛
     * IllegalArgumentException: Unexpected char 0x97f3，导致主界面首次打开就报
     * “终端接口出现问题（不影响软件内容）”。
     * 这里把头值里 0x20~0x7E 之外的 UTF-8 字节做百分号编码（逐字节，信息无损），
     * 保证任何包名/版本名组合下头值都合法。
     */
    private fun asciiHeaderValue(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) sb.append(c.toChar())
            else {
                sb.append('%')
                val hex = Integer.toHexString(c).uppercase()
                if (hex.length < 2) sb.append('0')
                sb.append(hex)
            }
        }
        return sb.toString()
    }

    private fun checkUpdate(context: Context, need_toast: Boolean, debug_ver: Boolean) {
        try {
            val realIsDebug = ToolsUtil.isDebugBuild()
            var url = "http://api.biliterminal.cn/terminal/version/get_last"
            if (debug_ver) url += "?debug"
            val result = NetWorkUtil.getJson(url, customHeaders)

            if (result.getInt("code") != 0) throw Exception(result.getString("msg"))
            val data = result.getJSONObject("data")

            val version_name = data.getString("version_name")
            val update_log = data.getString("update_log")
            val latest = data.getInt("version_code")
            val ctime = data.optLong("ctime", -1)
            val can_download = data.optInt("can_download", 0)
            val is_release = data.optInt("is_release", 0)

            val version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode
            if (latest > version) {
                MsgUtil.showMsg(if (debug_ver) "发现新的测试版！" else "发现新版本！")
                context.startActivity(Intent(context, UpdateInfoActivity::class.java)
                        .putExtra("versionName", version_name)
                        .putExtra("versionCode", latest)
                        .putExtra("updateLog", update_log)
                        .putExtra("ctime", ctime)
                        .putExtra("isRelease", is_release)
                        .putExtra("canDownload", can_download))
                return
            } else if (need_toast && !(realIsDebug && !debug_ver)) {
                MsgUtil.showMsg(if (debug_ver) "没有新的测试版了！" else "当前是最新版本！")
            }
            if (realIsDebug && !debug_ver) {
                checkUpdate(context, need_toast, true)
            }
        } catch (e: IOException) {
            MsgUtil.err("检查更新：", e)
        } catch (e: JSONException) {
            MsgUtil.err("检查更新：", e)
        } catch (e: Exception) {
            MsgUtil.showMsg(e.message)
        }
    }

    @JvmStatic
    fun checkUpdate(context: Context, need_toast: Boolean) {
        checkUpdate(context, need_toast, false)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun getDownloadUrl(versionCode: Int): String {
        val url = "https://api.biliterminal.cn/terminal/version/get_download_url" + NetWorkUtil.FormData().setUrlParam(true)
                .put("version_code", versionCode)
        val result = NetWorkUtil.getJson(url, customHeaders)

        if (result.getInt("code") != 0) throw Exception("错误：" + result.getString("msg"))

        return result.optString("data")
    }

    @Throws(Exception::class)
    @JvmStatic
    fun checkAnnouncement() {
        val url = "http://api.biliterminal.cn/terminal/announcement/get_list?from=" + SharedPreferencesUtil.getInt("app_announcement_last", -1)
        val result = NetWorkUtil.getJson(url, customHeaders)

        if (result.getInt("code") != 0) throw Exception("错误：" + result.getString("msg"))
        val data = result.getJSONArray("data")
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)

            val id = item.getInt("id")

            if (SharedPreferencesUtil.getInt("app_announcement_last", 0) < id)
                SharedPreferencesUtil.putInt("app_announcement_last", id)
            val title = item.getString("title")
            val content = item.getString("content")
            MsgUtil.showText(title, content)
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun getAnnouncementList(): ArrayList<Announcement> {
        val url = "http://api.biliterminal.cn/terminal/announcement/get_list"
        val result = NetWorkUtil.getJson(url, customHeaders)

        if (result.getInt("code") != 0) throw Exception("错误：" + result.getString("msg"))
        val data = result.getJSONArray("data")

        @Suppress("SimpleDateFormat") val sdf = SimpleDateFormat("yyyy-MM-dd")

        val list = ArrayList<Announcement>()
        for (i in 0 until data.length()) {
            val section = data.getJSONObject(i)
            val announcement = Announcement()
            announcement.id = section.getInt("id")
            announcement.ctime = sdf.format(section.getLong("ctime") * 1000)
            announcement.title = section.getString("title")
            announcement.content = section.getString("content")
            list.add(announcement)
        }
        return list
    }

    // 崩溃堆栈上传功能已移除（相关界面按钮与逻辑一并删除）

    @Throws(Exception::class)
    @JvmStatic
    fun getSponsors(list: ArrayList<UserInfo>, page: Int): Int {
        val url = "http://api.biliterminal.cn/terminal/afdian/get_sponsor?page=$page"
        val result = NetWorkUtil.getJson(url, customHeaders)

        if (result.getInt("code") != 200) throw Exception("获取失败")
        val data = result.getJSONArray("data")

        if (data.length() == 0) return 1

        @Suppress("SimpleDateFormat") val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm")

        for (i in 0 until data.length()) {
            val sponsor = data.getJSONObject(i)

            val user = UserInfo()

            user.name = sponsor.getString("name")
            user.avatar = sponsor.getString("avatar")
            user.sign = "总金额：" + sponsor.getInt("sum_amount") + "r | 捐赠时间：" + sdf.format(sponsor.getLong("last_time") * 1000)
            user.mid = -1
            user.fans = 0
            user.followed = true
            user.level = 6
            user.notice = ""
            user.official = 0
            user.officialDesc = ""

            list.add(user)
        }
        return 0
    }
}
