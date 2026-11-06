package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.RobinNotBad.BiliClient.api.AppRcmdApi
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.api.migration.ApiRouter
import com.RobinNotBad.BiliClient.api.gateway.CredentialStore
import com.RobinNotBad.BiliClient.util.AccountCredentials
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
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

    /** 程序性回填开关时挡住监听器，避免「读值」被当成「用户点击」立刻写回偏好 */
    private var suppressPhoneTvChange = false

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
        setupPhoneTvSignSwitch()
    }

    /**
     * 手机端口签名口径开关（v5.2.0 P0-2）。
     *
     * 历史做法：手机端口扫码用 TV 密钥、请求用手机密钥 → token 与 appkey **跨源**，
     * 严格校验的接口会回 -663「鉴权失败，请联系账号管理员」。打开这个开关后扫码与请求同源
     * （都用 TV 那对密钥），可消除该错误。
     *
     * 为什么默认关闭：切换会改变请求风控画像，且旧行为对绝大多数接口是能用的（只有少数严格接口
     * 受影响）。默认保持历史行为 = 升级零回归；遇到 -663 的用户可以自己打开验证。
     * 切换不会丢已登录 token（身份 id 仍是 IDENTITY_PHONE，槽位键不变）。
     */
    private fun setupPhoneTvSignSwitch() {
        val switch = findViewById<SwitchMaterial>(R.id.accountPhoneTvSign) ?: return
        // 必须挡住监听器：setChecked 会同步回调，否则「读值回填」会被当成用户点击、立刻写回偏好
        suppressPhoneTvChange = true
        switch.isChecked = SharedPreferencesUtil.getBoolean(AppRcmdApi.PHONE_TV_APPKEY_KEY, false)
        suppressPhoneTvChange = false
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressPhoneTvChange) return@setOnCheckedChangeListener
            SharedPreferencesUtil.putBoolean(AppRcmdApi.PHONE_TV_APPKEY_KEY, isChecked)
            // 身份变了要清掉挂起的路由决策，让下一次请求立刻按新身份重新判定
            ApiRouter.clearSuspensions()
            MsgUtil.showMsg(
                if (isChecked) "已切换为同源签名，重新进页面后生效"
                else "已切回历史签名口径"
            )
        }
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
        refreshCredentialsCard()
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

    // ==================== 凭据总览（v5.2.1 四端统一） ====================

    /**
     * 刷新「登录凭据（四端统一）」卡片：四端状态 + 覆盖报告 + 三个按钮。
     *
     * ## 为什么状态要四端一行读完
     * 用户原话「所有操作登录凭据的地方都放在那里」。四个端口的登录态以前分散在两个页签
     * （每页签一份）里，导致「web 退了但 HD 登着」这种关键状态**要翻两个页签才能拼出来**。
     * 这里一行读完四个端口，用户一眼就知道自己有几个号活着。
     *
     * ## 为什么覆盖报告默认隐藏
     * 它是给「排查/验收」用的（`ApiCatalog.coverageSummary` 的数字），普通用户看到
     * 「144 个端点 / 已实测 38」只会困惑。所以放在一个 `visibility = gone` 的 TextView 里，
     * 只有点了按钮才显示 —— 需要它的人自然会找到。
     */
    @SuppressLint("SetTextI18n")
    private fun refreshCredentialsCard() {
        val states = CredentialStore.loginStates()
        val credText = findViewById<TextView>(R.id.accountCredText) ?: return
        val sb = StringBuilder()
        for ((port, loggedIn) in states) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(AccountStore.portDisplayName(port))
            sb.append(if (loggedIn) " 已登录" else " 未登录")
        }
        credText.text = sb.toString()

        bindCredentialButtons()
    }

    /**
     * 三个凭据按钮：导出 / 导入 / 立刻续期 Web。
     *
     * 为什么每次刷新都重绑监听：本页会 `refreshAll()` 多次，用 `setOnClickListener` 覆盖绑定
     * 天然幂等（不会叠多层监听）；写成「首次绑定一次」反而要额外维护一个标志位。
     */
    private fun bindCredentialButtons() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.accountExportCred)
            ?.setOnClickListener { onExportCredentials() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.accountImportCred)
            ?.setOnClickListener { onImportCredentials() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.accountRenewWeb)
            ?.setOnClickListener { onRenewWeb() }
    }

    /**
     * 导出四端全部凭据 → 复制到剪贴板。
     *
     * 为什么不写文件：手表上没有方便的文件选择器，而用户换机的真实路径是
     * 「旧机导出 → 复制 → 发给自己 → 新机粘贴」。剪贴板是这个路径上最短的一环。
     * ⚠️ 凭据含 SESSDATA/access_key，**只放剪贴板、绝不写日志、绝不弹出来显示**。
     */
    private fun onExportCredentials() {
        val json = try {
            AccountCredentials.exportAll()
        } catch (e: Exception) {
            MsgUtil.err("导出凭据失败", e)
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            MsgUtil.showMsg("系统剪贴板不可用")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("biliclient-credentials", json))
        // 只提示条数，不打印内容（内容含完整凭据）
        MsgUtil.showMsg("已复制四端凭据到剪贴板（" + json.length + " 字节）")
    }

    /**
     * 从输入框导入凭据（**合并**语义，不覆盖现有账号）。
     *
     * 二次确认是必要的：导入会写信箱（账号池），误粘贴一段无关文本也会走解析失败路径，
     * 但「粘贴了别人的凭据」是不可逆的账号污染，值得多一次点击。
     */
    private fun onImportCredentials() {
        val input = findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.accountImportInput
        )?.text?.toString()?.trim() ?: ""
        if (input.isEmpty()) {
            MsgUtil.showMsg("请先粘贴凭据 JSON")
            return
        }
        CenterThreadPool.run(Runnable {
            val result = try {
                AccountCredentials.importAll(input)
            } catch (e: Exception) {
                MsgUtil.err("导入凭据失败", e)
                return@Runnable
            }
            val total = result.values.sum()
            CoroutineScopes.launchUi {
                if (total <= 0) {
                    MsgUtil.showMsg("没有导入任何账号（格式不对或凭据为空）")
                } else {
                    MsgUtil.showMsg("已导入 " + total + " 个账号（" + result.size + " 个端口）")
                    findViewById<com.google.android.material.textfield.TextInputEditText>(
                        R.id.accountImportInput
                    )?.setText("")
                    refreshAll()
                }
            }
        })
    }

    /**
     * 立刻续期 Web 登录（忽略 30 分钟节流）。
     *
     * 用户的真实场景：某次写操作报「登录过期」，他不想重扫二维码，希望有个按钮能先试试
     * 主动续期（`refresh_token` 还有效时能救回来）。所以这里 `force = true`。
     * 结果分三种如实说：续期成功 / 不需要续（Cookie 还有效）/ 续不了（需要重新扫码）。
     */
    private fun onRenewWeb() {
        if (AccountStore.mainPort() == AccountStore.PORT_WEB && !AccountManager.isWebLoggedIn()) {
            MsgUtil.showMsg("Web 端当前没有登录，无需续期")
            return
        }
        MsgUtil.showMsg("正在检查 Web 登录状态…")
        CenterThreadPool.run(Runnable {
            val renewed = try {
                CredentialStore.renewWeb(true)
            } catch (e: Exception) {
                MsgUtil.err("续期失败", e)
                return@Runnable
            }
            CoroutineScopes.launchUi {
                if (renewed) {
                    MsgUtil.showMsg("Web 登录已续期")
                    refreshAll()
                } else {
                    // false 的含义见 CookieRefreshApi.checkAndRefresh 的注释：
                    // 「不需要续」与「续失败」都会返回 false，所以文案要覆盖两种可能
                    MsgUtil.showMsg("未能续期：可能 Cookie 仍有效，或 refresh_token 已失效（需重新登录）")
                }
            }
        })
    }

    companion object {
        /** 打开页时定位到哪个页签：0 = 推荐页 API（长按主页「推荐」用这个），1 = 其它页面 API */
        const val EXTRA_TAB = "account_tab"

        const val TAB_RCMD = 0
        const val TAB_MAIN = 1
    }
}
