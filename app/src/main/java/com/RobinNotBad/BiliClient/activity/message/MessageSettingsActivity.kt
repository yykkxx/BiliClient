package com.RobinNotBad.BiliClient.activity.message

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView

import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.message.MessageSettingsAdapter
import com.RobinNotBad.BiliClient.api.MessageApi
import com.RobinNotBad.BiliClient.model.message.MessageSettingItem
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil

import org.json.JSONObject

/**
 * 消息设置页（#1 卡死修复重写版）。
 *
 * 旧实现「点开关后卡死」的根因：
 * 1. 每次保存失败都 loadSettings() 整页重载：loadSettings 无重入保护，弱网下（连接超时 8s +
 *    读超时 16s）连续点击会堆积一堆 POST/GET，每个回调都在主线程 new 一个 Adapter 换上
 *    （recyclerView.adapter = adapter），触发整页重新布局；
 * 2. buildSettingsList() 在 IO 线程里 clear()/add() 的正是旧 Adapter 手里那个**同一个** List
 *    实例，主线程 RecyclerView 布局与 IO 线程改列表并发竞争 → 布局错乱/ANR；
 * 3. show_unfollowed_msg 语义反了：接口键义是「显示未关注人消息」（1=显示），而 UI 标题是
 *    「收起未关注人消息」，旧代码 value==1 判定选中，导致开关显示与真实状态相反，
 *    用户点了以后服务端存反值、开关回弹，看起来像「点了没反应」，诱发更多连点。
 *
 * 修复要点：
 * - Adapter 只创建一次，数据只在主线程改动（IO 线程构造新 List，主线程 clear+addAll）；
 * - loadSettings 用 loading 标志防重入；所有回调先判 isDestroyed；
 * - 保存开关改为「乐观更新 + 单项回滚」：不再整页重载，同一 key 有请求在途时忽略并回弹；
 * - show_unfollowed_msg 极性修正：选中（收起）= 发送 0，未选中（展开）= 发送 1。
 */
class MessageSettingsActivity : BaseActivity() {
    private var recyclerView: RecyclerView? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var emptyView: TextView? = null
    private var adapter: MessageSettingsAdapter? = null

    /** 只在主线程读写；IO 线程不允许碰这个实例 */
    private val settingsList: MutableList<MessageSettingItem> = ArrayList()

    /** loadSettings 防重入标志（跨线程可见） */
    @Volatile
    private var loading = false

    /** 正在提交的设置项 key，防止同一开关被连点出请求风暴 */
    private val pendingKeys = HashSet<String>()

    /** 是否有保存请求在途（#1：全局串行，同一时间最多一个保存请求） */
    @Volatile
    private var saveInFlight = false

    /** 排队的保存动作（在途请求结束后立即执行；超过一个直接丢弃） */
    private val queuedSave = ArrayDeque<Pair<String, Boolean>>()

    /** 最近一次从服务端拿到的原始值，保存失败时用它回滚开关 */
    private val serverValues = HashMap<String, Int>()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_refresh)

        setPageName("消息设置")

        emptyView = findViewById(R.id.emptyTip)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout!!.isEnabled = false
        swipeRefreshLayout!!.isRefreshing = true
        recyclerView = findViewById(R.id.recyclerView)
        // #4：用带兜底的 LayoutManager（onLayoutChildren 捕获列表越界错误，
        // 旧实现裸 LinearLayoutManager 在数据并发变化时直接 IndexOutOfBoundsException）
        recyclerView!!.layoutManager = CustomLinearManager(this)

        //Adapter 只创建一次，后续刷新只改数据，不再整页换 Adapter
        adapter = MessageSettingsAdapter(this, settingsList, object : MessageSettingsAdapter.OnSettingChangedListener {
            override fun onSettingChanged(key: String?, value: Boolean) {
                if (key != null) onSettingChanged(key, value)
            }
        })
        recyclerView!!.adapter = adapter

        loadSettings()
    }

    /** 服务端原始值 -> 开关选中态 */
    private fun checkedOf(key: String, serverValue: Int): Boolean {
        return if (key == "show_unfollowed_msg") {
            //键义是「显示未关注人消息」：0=收起 -> 开关选中
            serverValue == 0
        } else {
            serverValue == 1
        }
    }

    /** 开关选中态 -> 服务端原始值 */
    private fun serverValueOf(key: String, checked: Boolean): Int {
        return when {
            key == "msg_notify" -> if (checked) 1 else 3
            key == "show_unfollowed_msg" -> if (checked) 0 else 1
            else -> if (checked) 1 else 0
        }
    }

    private fun loadSettings() {
        if (loading) return  //防重入：上一轮还没回来就不发新请求
        loading = true
        CenterThreadPool.run(Runnable {
            var fresh: List<MessageSettingItem>? = null
            var errMsg: String? = null
            try {
                val response = MessageApi.getMsgSettings()
                if (response.getInt("code") == 0) {
                    fresh = buildSettingsList(response.getJSONObject("data"))
                } else {
                    errMsg = response.optString("message", "未知错误")
                }
            } catch (e: Exception) {
                report(e)
                errMsg = e.message ?: e.javaClass.simpleName
            }

            CenterThreadPool.runOnUiThread(Runnable {
                loading = false
                if (isDestroyed) return@Runnable
                swipeRefreshLayout!!.isRefreshing = false

                if (fresh != null) {
                    //数据只在这里（主线程）进入 Adapter 使用的列表
                    serverValues.clear()
                    settingsList.clear()
                    settingsList.addAll(fresh!!)
                    adapter!!.notifyDataSetChanged()
                    if (settingsList.isEmpty()) {
                        emptyView!!.text = "啥都木有~"
                        emptyView!!.visibility = View.VISIBLE
                    } else {
                        emptyView!!.visibility = View.GONE
                    }
                } else {
                    //保留已有数据，只有首次加载失败才露出错误页
                    if (settingsList.isEmpty()) {
                        emptyView!!.text = "加载失败，请重试"
                        emptyView!!.visibility = View.VISIBLE
                    }
                    MsgUtil.toast("获取设置失败: " + (errMsg ?: "未知错误"))
                }
            })
        })
    }

    /** 在 IO 线程构造全新 List（不碰 Adapter 正在用的实例） */
    private fun buildSettingsList(data: JSONObject): List<MessageSettingItem> {
        val list = ArrayList<MessageSettingItem>()
        for (key in KEYS) {
            if (!data.has(key)) continue
            val serverValue = data.optInt(key, if (key == "msg_notify" || key == "should_receive_group" || key == "receive_unfollow_msg") 1 else 0)
            list.add(MessageSettingItem(
                    key,
                    TITLES[key]!!,
                    DESCS[key]!!,
                    if (key == "msg_notify") MessageSettingItem.TYPE_CHOOSE else MessageSettingItem.TYPE_SWITCH,
                    checkedOf(key, serverValue),
                    if (key == "msg_notify") arrayOf("接收", "不接收") else null))
        }
        return list
    }

    private fun onSettingChanged(key: String, value: Boolean) {
        if (isDestroyed) return

        //同一项有请求在途：忽略这次点击并回弹，避免弱网下连点堆请求
        if (!pendingKeys.add(key)) {
            revertItem(key)
            MsgUtil.toast("上一项设置还在保存中…")
            return
        }

        // #1：全局串行——同一时间只允许一个保存请求在途；
        // 已有在途时这次点击进排队（只保留最后一个），再多的直接忽略回弹。
        if (saveInFlight) {
            if (queuedSave.isEmpty()) queuedSave.addLast(key to value)
            else {
                queuedSave.clear()
                queuedSave.addLast(key to value)
            }
            return
        }
        dispatchSave(key, value)
    }

    /** 真正发保存请求（一次只跑一个，结束后消化队列） */
    private fun dispatchSave(key: String, value: Boolean) {
        saveInFlight = true
        val serverValue = serverValueOf(key, value)
        CenterThreadPool.run(Runnable {
            var ok = false
            var errMsg: String? = null
            try {
                val settings = JSONObject()
                settings.put(key, serverValue)
                val response = MessageApi.setMsgSettings(settings)
                if (response.getInt("code") == 0) ok = true
                else errMsg = response.optString("message", "未知错误")
            } catch (e: Exception) {
                errMsg = e.message ?: e.javaClass.simpleName
            }

            CenterThreadPool.runOnUiThread(Runnable {
                pendingKeys.remove(key)
                if (isDestroyed) {
                    saveInFlight = false
                    queuedSave.clear()
                    return@Runnable
                }
                if (ok) {
                    //成功：记录为已知服务端状态
                    serverValues[key] = serverValue
                    MsgUtil.toast("设置已保存")
                } else {
                    //失败：只回滚这一项，绝不整页重载（旧版卡死根源）
                    revertItem(key)
                    MsgUtil.toast("保存失败: " + (errMsg ?: "未知错误"))
                }

                //消化队列里的下一个保存（如果有）
                saveInFlight = false
                val next = queuedSave.removeFirstOrNull()
                if (next != null && !pendingKeys.contains(next.first)) {
                    if (pendingKeys.add(next.first)) dispatchSave(next.first, next.second)
                } else if (next != null) {
                    revertItem(next.first)
                }
            })
        })
    }

    /** 把某一项的开关回滚到最近已知的服务端状态 */
    private fun revertItem(key: String) {
        val index = settingsList.indexOfFirst { it.key == key }
        if (index < 0) return
        val item = settingsList[index]
        val knownServer = serverValues[key]
        item.value = if (knownServer != null) checkedOf(key, knownServer) else !item.value
        adapter!!.notifyItemChanged(index)
    }

    companion object {
        private val KEYS = arrayOf(
                "msg_notify", "show_unfollowed_msg", "is_group_fold",
                "should_receive_group", "receive_unfollow_msg", "ai_intercept")

        private val TITLES = mapOf(
                "msg_notify" to "消息提醒",
                "show_unfollowed_msg" to "收起未关注人消息",
                "is_group_fold" to "收起应援团消息",
                "should_receive_group" to "接收应援团消息",
                "receive_unfollow_msg" to "接收未关注人消息",
                "ai_intercept" to "私信智能拦截")

        private val DESCS = mapOf(
                "msg_notify" to "是否接收消息提醒",
                "show_unfollowed_msg" to "收起来自未关注用户的消息",
                "is_group_fold" to "折叠应援团相关消息",
                "should_receive_group" to "是否接收应援团消息",
                "receive_unfollow_msg" to "是否接收未关注用户的消息",
                "ai_intercept" to "使用AI智能过滤骚扰私信")
    }
}
