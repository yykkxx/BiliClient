package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView

import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.login.AppRcmdLoginActivity
import com.RobinNotBad.BiliClient.activity.settings.login.LoginActivity
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.api.migration.ApiRouter
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

/**
 * 「账号管理」页（v5.1.5）：两个页签 × 四个端口 × 同端口多账号。
 *
 * 页签 1「推荐页 API」只写 [AccountStore.setRcmdPort]，页签 2「其它页面 API」只写
 * [AccountStore.setMainPort]——两个作用域完全独立，页签之间没有任何隐式联动。
 *
 * 布局刻意不用 Fragment / ConstraintLayout（手表圆屏 + lintVitalRelease 的 NotSibling 坑）：
 * 两套结构相同的控件靠 visibility 切换，账号行动态生成（同端口可以有任意多个账号）。
 *
 * 本页只做「选端口 / 切账号 / 退出 / 登录新账号」的界面逻辑，凭据读写全部交给 [AccountStore]，
 * 不直接碰 cookies / app_rcmd_* 任何槽位。
 */
class SettingAccountActivity : BaseActivity() {

    /** 当前页签：0 = 推荐页 API，1 = 其它页面 API */
    private var currentTab = TAB_RCMD

    /** 已经尝试过拉取昵称/头像的账号（避免每次刷新都重试；失败也不反复打网络） */
    private val profileRequests = HashSet<String>()

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting_account)
        setPageName("账号管理")
        setTopbarExit()

        currentTab = when (intent?.getIntExtra(EXTRA_TAB, TAB_RCMD)) {
            TAB_MAIN -> TAB_MAIN
            else -> TAB_RCMD
        }

        setupTabs()
        setupPortGroup(true)
        setupPortGroup(false)
        setupActions()

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 从扫码登录页返回后账号列表要跟着变
        refreshAll()
    }

    // ==================== 页签 ====================

    private fun setupTabs() {
        val tabs = findViewById<TabLayout>(R.id.accountTabs)
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText("推荐页 API"))
        tabs.addTab(tabs.newTab().setText("其它页面 API"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                applyTabVisibility()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(currentTab)?.select()
        applyTabVisibility()
    }

    /** 页签切换 = 两套控件 visibility 互换（不重建视图，不涉及 Fragment 生命周期） */
    private fun applyTabVisibility() {
        findViewById<LinearLayout>(R.id.accountRcmdGroup)?.visibility =
            if (currentTab == TAB_RCMD) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.accountMainGroup)?.visibility =
            if (currentTab == TAB_MAIN) View.VISIBLE else View.GONE
    }

    // ==================== 端口选择 ====================

    private fun setupPortGroup(isRcmd: Boolean) {
        val group = findViewById<RadioGroup>(
            if (isRcmd) R.id.accountRcmdPortGroup else R.id.accountMainPortGroup
        )
        group?.setOnCheckedChangeListener { _, checkedId ->
            val port = portOfRadioId(checkedId)
            if (port.isNotEmpty()) onPortChosen(isRcmd, port)
        }
    }

    private fun portOfRadioId(checkedId: Int): String {
        return when (checkedId) {
            R.id.accountRcmdPortAndroid, R.id.accountMainPortAndroid -> AccountStore.PORT_ANDROID
            R.id.accountRcmdPortHd, R.id.accountMainPortHd -> AccountStore.PORT_HD
            R.id.accountRcmdPortTv, R.id.accountMainPortTv -> AccountStore.PORT_TV
            R.id.accountRcmdPortWeb, R.id.accountMainPortWeb -> AccountStore.PORT_WEB
            else -> ""
        }
    }

    /** 点选端口：只写对应作用域的键，另一个页签不受影响 */
    private fun onPortChosen(isRcmd: Boolean, port: String) {
        val current = if (isRcmd) AccountStore.rcmdPort() else AccountStore.mainPort()
        if (port == current) {
            refreshScope(isRcmd)
            return
        }
        if (isRcmd) AccountStore.setRcmdPort(port) else AccountStore.setMainPort(port)
        val scopeName = if (isRcmd) "推荐页 API" else "其它页面 API"
        MsgUtil.showMsg(scopeName + " 已改为 " + AccountStore.portDisplayName(port) + " 端")
        refreshAll()
    }

    // ==================== 登录新账号 / 清空统计 ====================

    private fun setupActions() {
        findViewById<MaterialButton>(R.id.accountRcmdLoginNew)?.setOnClickListener { loginNewAccount(true) }
        findViewById<MaterialButton>(R.id.accountMainLoginNew)?.setOnClickListener { loginNewAccount(false) }
        findViewById<MaterialButton>(R.id.accountStatsClear)?.setOnClickListener { confirmClearStats() }
    }

    /** 登录新账号：web 端口走已有的 web 扫码页，APP 端口带 EXTRA_PORT 走移动端扫码页 */
    private fun loginNewAccount(isRcmd: Boolean) {
        val port = if (isRcmd) AccountStore.rcmdPort() else AccountStore.mainPort()
        if (port == AccountStore.PORT_WEB) {
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        val intent = Intent(this, AppRcmdLoginActivity::class.java)
        intent.putExtra(AppRcmdLoginActivity.EXTRA_PORT, port)
        startActivity(intent)
    }

    private fun confirmClearStats() {
        AlertDialog.Builder(this)
            .setTitle("清空迁移统计")
            .setMessage("只清空命中/回退次数与最近失败记录，不影响端口选择、账号和统计之外的任何功能。")
            .setPositiveButton("清空") { _, _ ->
                ApiRouter.resetStats()
                refreshStats()
                MsgUtil.showMsg("已清空迁移统计")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 刷新 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshAll() {
        refreshScope(true)
        refreshScope(false)
        refreshStats()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshScope(isRcmd: Boolean) {
        val port = if (isRcmd) AccountStore.rcmdPort() else AccountStore.mainPort()
        refreshPortRadios(isRcmd, port)
        refreshPortDesc(isRcmd, port)
        refreshAccounts(isRcmd, port)
    }

    /** 四个端口行的文案：端口名 · 已登录 N 个账号 · 当前端口；并把选中项回显到该页签 */
    @SuppressLint("SetTextI18n")
    private fun refreshPortRadios(isRcmd: Boolean, port: String) {
        val group = findViewById<RadioGroup>(
            if (isRcmd) R.id.accountRcmdPortGroup else R.id.accountMainPortGroup
        ) ?: return
        group.setOnCheckedChangeListener(null)
        for (p in AccountStore.ALL_PORTS) {
            val button = group.findViewById<CompoundButton>(radioIdOf(isRcmd, p)) ?: continue
            val count = AccountStore.accountCount(p)
            val logged = if (count > 0) "已登录 " + count + " 个账号" else "未登录"
            val current = if (p == port) " · 当前" else ""
            button.text = AccountStore.portDisplayName(p) + " · " + logged + current
            button.isChecked = p == port
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val chosen = portOfRadioId(checkedId)
            if (chosen.isNotEmpty()) onPortChosen(isRcmd, chosen)
        }
    }

    private fun radioIdOf(isRcmd: Boolean, port: String): Int {
        return when (port) {
            AccountStore.PORT_ANDROID ->
                if (isRcmd) R.id.accountRcmdPortAndroid else R.id.accountMainPortAndroid
            AccountStore.PORT_HD ->
                if (isRcmd) R.id.accountRcmdPortHd else R.id.accountMainPortHd
            AccountStore.PORT_TV ->
                if (isRcmd) R.id.accountRcmdPortTv else R.id.accountMainPortTv
            else -> if (isRcmd) R.id.accountRcmdPortWeb else R.id.accountMainPortWeb
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshPortDesc(isRcmd: Boolean, port: String) {
        val desc = findViewById<TextView>(
            if (isRcmd) R.id.accountRcmdPortDesc else R.id.accountMainPortDesc
        ) ?: return
        desc.text = when (port) {
            AccountStore.PORT_WEB ->
                "Web：用软件本身的网页扫码登录。同一端口也可以登录多个 web 账号，随时切换。"
            AccountStore.PORT_ANDROID ->
                "Android手机：按官方手机客户端协议扫码登录，与 web 登录互相独立，可同时登录多个账号。"
            AccountStore.PORT_HD ->
                "HD：按 HD（android_hd）协议扫码登录，与 web 登录互相独立，可同时登录多个账号。"
            else ->
                "TV：按云视听小电视（android_tv）协议扫码登录，与 web 登录互相独立，可同时登录多个账号。"
        }
    }

    // ==================== 账号列表 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshAccounts(isRcmd: Boolean, port: String) {
        val container = findViewById<LinearLayout>(
            if (isRcmd) R.id.accountRcmdList else R.id.accountMainList
        ) ?: return
        val empty = findViewById<TextView>(
            if (isRcmd) R.id.accountRcmdEmpty else R.id.accountMainEmpty
        )
        container.removeAllViews()
        val accounts = AccountStore.accounts(port)
        empty?.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        for (entry in accounts) container.addView(buildAccountRow(port, entry))
        loadMissingProfiles(port, accounts)
    }

    /**
     * 一行账号：昵称（没有就 uid）+「当前账号」标记 +「切换」「退出」两个按钮。
     * 激活账号不显示「切换」（再点一次没有意义），避免用户误点。
     */
    private fun buildAccountRow(port: String, entry: AccountStore.AccountEntry): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 2, 0, 2)

        val label = TextView(this)
        val displayName = entry.name.ifBlank { "uid " + entry.mid }
        label.text = if (entry.active) displayName + " · 当前账号" else displayName
        label.textSize = 13f
        label.maxLines = 1
        row.addView(label)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.setPadding(0, 2, 0, 2)

        if (!entry.active) {
            val switchButton = MaterialButton(this)
            switchButton.text = "切换"
            switchButton.textSize = 11f
            switchButton.setOnClickListener { onSwitchAccount(port, entry.mid) }
            buttons.addView(switchButton, weightedParams())
        }

        val logoutButton = MaterialButton(this)
        logoutButton.text = "退出"
        logoutButton.textSize = 11f
        logoutButton.setOnClickListener { confirmLogout(port, entry) }
        buttons.addView(logoutButton, weightedParams())

        row.addView(buttons)
        return row
    }

    private fun weightedParams(): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        params.marginEnd = (4 * resources.displayMetrics.density).toInt()
        return params
    }

    private fun onSwitchAccount(port: String, mid: Long) {
        if (!AccountStore.setActive(port, mid)) {
            MsgUtil.showMsg("账号已不存在，请刷新")
            return
        }
        MsgUtil.showMsg("已切换到 " + AccountStore.portDisplayName(port) + " 端的该账号")
        refreshAll()
    }

    /** 退出需二次确认；退出激活账号后 AccountStore 会自动切到同端口下一个账号，这里只刷新 UI */
    private fun confirmLogout(port: String, entry: AccountStore.AccountEntry) {
        val displayName = entry.name.ifBlank { "uid " + entry.mid }
        AlertDialog.Builder(this)
            .setTitle("退出该账号")
            .setMessage(
                "将退出「" + AccountStore.portDisplayName(port) + " · " + displayName + "」的登录。\n" +
                        "只清这个账号的登录凭据，不删除收藏、历史、稍后再看等任何数据；" +
                        "如果它是当前账号，会自动切到同端口的下一个账号。"
            )
            .setPositiveButton("退出") { _, _ ->
                AccountStore.logout(port, entry.mid)
                MsgUtil.showMsg("已退出该账号")
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 昵称/头像懒加载：只对「还没有昵称」的账号发一次请求，成功后写回 [AccountStore]。
     * 失败静默（账号行继续显示 uid），且同一个账号本次页面生命周期内不会重试。
     */
    private fun loadMissingProfiles(port: String, accounts: List<AccountStore.AccountEntry>) {
        val pending = ArrayList<AccountStore.AccountEntry>()
        for (entry in accounts) {
            if (entry.name.isBlank() && profileRequests.add(port + ":" + entry.mid)) {
                pending.add(entry)
            }
        }
        if (pending.isEmpty()) return
        CoroutineScopes.launchUi {
            var changed = false
            for (entry in pending) {
                // 拉取失败静默：runCatching 拿到 null，账号行继续显示 uid 占位
                val info = CoroutineScopes.io {
                    runCatching { UserInfoApi.getUserInfo(entry.mid) }.getOrNull()
                }
                val name = info?.name
                if (!name.isNullOrBlank()) {
                    AccountStore.updateProfile(port, entry.mid, name, info.avatar)
                    changed = true
                }
            }
            // 页面可能已在拉取过程中被关掉：销毁后不再刷 UI
            if (changed && !isDestroyed) refreshAll()
        }
    }

    // ==================== 迁移统计 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshStats() {
        val stats = findViewById<TextView>(R.id.accountStatsText) ?: return
        val hits = ApiRouter.hitCount()
        val fallbacks = ApiRouter.fallbackCount()
        val attempts = hits + fallbacks
        val rate = if (attempts > 0) hits * 100L / attempts else 0L
        val sb = StringBuilder()
        sb.append("APP 通道命中：").append(hits).append(" 次")
        sb.append("\n回退 web：").append(fallbacks).append(" 次（命中率 ").append(rate).append("%）")
        sb.append("\n写操作不重放：").append(ApiRouter.noReplayCount()).append(" 次")
        stats.text = sb.toString()
    }

    companion object {
        /** 打开页时定位到哪个页签：0 = 推荐页 API（长按主页「推荐」用这个），1 = 其它页面 API */
        const val EXTRA_TAB = "account_tab"

        const val TAB_RCMD = 0
        const val TAB_MAIN = 1
    }
}
