package com.RobinNotBad.BiliClient.activity.user

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.activity.settings.SettingAccountActivity
import com.RobinNotBad.BiliClient.activity.user.favorite.FavoriteFolderListActivity
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.AccountStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.card.MaterialCardView

class MySpaceActivity : InstanceActivity() {

    private var userAvatar: ImageView? = null
    private var userName: TextView? = null
    private var userFans: TextView? = null
    private var userExp: TextView? = null
    private var myInfo: MaterialCardView? = null
    private var follow: MaterialCardView? = null
    private var watchLater: MaterialCardView? = null
    private var favorite: MaterialCardView? = null
    private var bangumi: MaterialCardView? = null
    private var history: MaterialCardView? = null
    private var creative: MaterialCardView? = null
    private var vip: MaterialCardView? = null
    private var loginRecord: MaterialCardView? = null
    private var coinLog: MaterialCardView? = null
    private var expLog: MaterialCardView? = null
    private var editSign: MaterialCardView? = null
    private var logout: MaterialCardView? = null

    /** 滚动容器：表冠/方向键焦点兜底用（与上面各卡片一样走「字段 + null 安全」风格） */
    private var scrollView: View? = null

    private var confirmLogout = false
    private var currentUserInfo: UserInfo? = null

    @SuppressLint("SetTextI18n", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 回调参数（布局 View / 资源 id）本页用不到，用 _ 占位以消掉「参数未使用」编译警告
        asyncInflate(R.layout.activity_myspace) { _, _ ->
            Log.e("debug", "进入个人页")

            userAvatar = findViewById(R.id.userAvatar)
            userName = findViewById(R.id.userName)
            userFans = findViewById(R.id.userFans)
            userExp = findViewById(R.id.userExp)

            myInfo = findViewById(R.id.myinfo)
            follow = findViewById(R.id.follow)
            watchLater = findViewById(R.id.watchlater)
            favorite = findViewById(R.id.favorite)
            bangumi = findViewById(R.id.bangumi)
            history = findViewById(R.id.history)
            creative = findViewById(R.id.creative)
            vip = findViewById(R.id.vip)
            loginRecord = findViewById(R.id.login_record)
            coinLog = findViewById(R.id.coin_log)
            expLog = findViewById(R.id.exp_log)
            editSign = findViewById(R.id.edit_sign)
            logout = findViewById(R.id.logout)
            scrollView = findViewById(R.id.scrollView)


            // v5.1.4 账号解耦：本页在「web 未登录、但端口账号已登录」时也必须完整可用。
            //
            // 历史问题：本页所有内容都包在 `UserInfoApi.getCurrentUserInfo()` 成功之后，
            // 而这个接口是 web 通道的（api.bilibili.com/x/space/myinfo）。web 一退出，
            // 请求就拿不到数据 → 整页停在空白，用户看到的就是「退出 web 后『我的』界面没了」。
            //
            // 现在的策略是「先铺好页面，再用能拿到的资料去填充」：
            // 1. 立刻用 AccountStore 的本地信息（昵称/头像/uid）把头部渲染出来，不等网络；
            // 2. 再把本页所有卡片点击事件绑好（它们只依赖 mid，不依赖网络结果）；
            // 3. 最后才尝试联网补全 fans/exp/硬币这些**装饰性**数据，失败就保持占位。
            // 这样无论哪种登录组合，页面都是可用的，最差也只是少几行数字。
            bindPageActions()
            renderLocalProfile()
            loadRemoteProfile()
        }
    }

    /**
     * 绑定本页所有卡片的点击事件。
     *
     * 这些入口只依赖「当前账号 mid」，与 web 是否登录无关——
     * 三级模式下 Favorites/History/WatchLater 等请求会由路由层走端口通道，所以照常可点。
     * 真的一个号都没有时，点击会由各子页面自己给出「去登录」提示。
     */
    private fun bindPageActions() {
        val uid = AccountStore.primaryMid()
        myInfo?.setOnClickListener {
            if (uid <= 0L) {
                jumpToLogin()
                return@setOnClickListener
            }
            startActivity(Intent(this, UserInfoActivity::class.java).putExtra("mid", uid))
        }
        follow?.setOnClickListener {
            startActivity(
                Intent(this, FollowUsersActivity::class.java)
                    .putExtra("mid", uid)
                    .putExtra("mode", 0)
            )
        }
        watchLater?.setOnClickListener { startActivity(Intent(this, WatchLaterActivity::class.java)) }
        favorite?.setOnClickListener { startActivity(Intent(this, FavoriteFolderListActivity::class.java)) }
        bangumi?.setOnClickListener { startActivity(Intent(this, FollowingBangumisActivity::class.java)) }
        history?.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        creative?.setOnClickListener { startActivity(Intent(this, CreativeCenterActivity::class.java)) }
        if (!SharedPreferencesUtil.getBoolean("creative_enable", true)) {
            creative?.visibility = View.GONE
        }
        vip?.setOnClickListener { startActivity(Intent(this, VipActivity::class.java)) }
        loginRecord?.setOnClickListener { startActivity(Intent(this, LoginRecordActivity::class.java)) }
        coinLog?.setOnClickListener { startActivity(Intent(this, CoinLogActivity::class.java)) }
        expLog?.setOnClickListener { startActivity(Intent(this, ExpLogActivity::class.java)) }
        editSign?.setOnClickListener {
            val intent = Intent()
            intent.setClass(this@MySpaceActivity, EditSignActivity::class.java)
            intent.putExtra("currentSign", currentUserInfo?.sign ?: "")
            startActivity(intent)
        }
        logout?.setOnClickListener { onLogoutClicked() }

        // 表冠/方向键焦点兜底：让滚动容器自己先拿到焦点（同历史行为）。
        // 用已有的 null 安全字段风格，不再新增 findViewById 调用点。
        scrollView?.let { view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
        }
    }

    /**
     * 用本地已知信息渲染头部（不发网络请求）。
     *
     * 没账号时给出明确占位并引导登录，而不是留一片空白——
     * 空白会让用户以为 App 坏了，占位至少告诉他「该去哪里登录」。
     */
    @SuppressLint("SetTextI18n")
    private fun renderLocalProfile() {
        val loggedIn = AccountStore.hasAnyAccount()
        if (!loggedIn) {
            userName?.text = "未登录"
            userFans?.text = "登录后可使用收藏、历史、稍后再看"
            userExp?.text = ""
            editSign?.visibility = View.GONE
            return
        }
        userName?.text = AccountStore.primaryName()
        // 联网数据未到时先给一句中性文案，避免看起来像"加载失败"
        userFans?.text = "正在获取账号信息…"
        val avatar = AccountStore.primaryFace()
        val avatarView = userAvatar
        if (avatar.isNotEmpty() && avatarView != null) {
            Glide.with(this)
                .load(GlideUtil.url(avatar))
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(R.mipmap.akari)
                .apply(RequestOptions.circleCropTransform())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(avatarView)
        }
    }

    /**
     * 尝试联网补全账号资料（粉丝数/等级/硬币/签名）。
     *
     * 失败是**预期内**的，这里静默降级——不弹错误弹窗、不打断用户，只是少几行数字。
     *
     * v5.2.0（P2-1）：不再要求「web 已登录」。历史上这里是
     * `if (AccountStore.activeMid(PORT_WEB) <= 0L) { ... "详细数据需登录 Web" ... return }`，
     * 于是「web 未登录 + APP 端口已登录」的用户永远停在占位文案上（调研报告 §3.3 的头号缺陷）。
     * 现在只要**任意端口有账号**就请求：迁移层启用（其它页面 API 选非 Web 端口）时
     * `/x/space/myinfo` 会走端口 token 的 APP 通道；未启用时仍按原 web 通道，
     * 失败被下面同一个 catch 吃掉，行为与原「web 未登录」一致（只是不再提前写死文案）。
     */
    @SuppressLint("SetTextI18n")
    private fun loadRemoteProfile() {
        if (!AccountStore.hasAnyAccount()) return
        CenterThreadPool.run(Runnable {
            try {
                val userInfo = UserInfoApi.getCurrentUserInfo()
                // getCurrentUserInfo() 拿不到数据时**不抛异常**，而是返回一个占位对象
                // UserInfo(0, "加载失败", "", "", 0, 0, 0)。若原样往下走，页面会被覆盖成
                // 「加载失败 / 0 粉丝」——比 renderLocalProfile() 铺好的本地占位更糟。
                // 所以这里先校验：mid 为 0 视为没拿到，直接保持本地占位（静默降级）。
                if (userInfo.mid <= 0L) {
                    Log.w("MySpaceActivity", "账号资料为空（多为 web 未登录且迁移未启用），保持本地占位")
                    return@Runnable
                }
                currentUserInfo = userInfo
                // 缓存昵称/头像，供「本地占位」复用。
                // 必须写进**资料实际来源的那个端口**：P2-1 之后请求可能走 APP 通道
                // （其它页面 API 选了非 Web 端口时 /x/space/myinfo 由端口 token 返回），
                // 那是端口账号的资料，写进 web 槽位会把 web 账号的缓存名字/头像改错。
                val sourcePort = if (AccountStore.mainIdentity() != null) AccountStore.mainPort() else AccountStore.PORT_WEB
                val sourceMid = AccountStore.activeMid(sourcePort)
                if (sourceMid > 0L) AccountStore.updateProfile(sourcePort, sourceMid, userInfo.name, userInfo.avatar)
                val userCoin = UserInfoApi.getCurrentUserCoin()
                if (isDestroyed) return@Runnable
                CoroutineScopes.launchUi {
                    applyRemoteProfile(userInfo, userCoin)
                }
            } catch (e: Exception) {
                // 静默降级：不弹错误弹窗（原注释就这么要求的）。
                // 这里**不能用 report(e)** —— BaseActivity.report 的实现是 MsgUtil.err(弹窗)，
                // 而 web 未登录 + 端口未启用迁移时请求失败是**预期内**的常态，
                // 弹窗会把「少几行装饰性数字」变成每次进页面都报错。
                Log.w("MySpaceActivity", "拉取账号资料失败，保持本地占位", e)
            }
        })
    }

    @SuppressLint("SetTextI18n")
    private fun applyRemoteProfile(userInfo: UserInfo, userCoin: Int) {
        val avatarView = userAvatar
        if (avatarView != null) {
            Glide.with(this)
                .load(GlideUtil.url(userInfo.avatar ?: ""))
                .transition(GlideUtil.getTransitionOptions())
                .placeholder(R.mipmap.akari)
                .apply(RequestOptions.circleCropTransform())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(avatarView)
        }
        userName?.text = userInfo.name
        userFans?.text = StringUtil.toWan(userInfo.fans.toLong()) + "粉丝 " + userCoin + "硬币"
        userExp?.text = "EXP:" + userInfo.current_exp + (if (userInfo.level >= 6) "" else "/" + userInfo.next_exp)
        editSign?.visibility = View.VISIBLE
    }

    /**
     * 退出登录。
     *
     * 必须退掉**用户感知到的那一个账号**，否则会出现「点了退出、页面还显示已登录」的困惑。
     * v5.1.5 起账号有多份（同一端口也能多号并存），所以一律按「激活账号」退：
     * - web 端口有激活账号 → 退 web 的那个激活账号（`AccountStore.logout(PORT_WEB, activeMid)`）；
     * - web 没有 → 退「页签 2（其它页面 API）」端口的激活账号；
     * - 都没有 → 直接去登录页（用户主动触发，不是强制跳转）。
     *
     * 退出激活账号后 AccountStore 会自动把该端口的下一个账号切为激活（没有则变「未登录」）。
     */
    private fun onLogoutClicked() {
        if (!AccountStore.hasAnyAccount()) {
            jumpToLogin()
            return
        }
        if (!confirmLogout) {
            MsgUtil.showMsg("再点一次退出登录！")
            confirmLogout = true
            return
        }
        confirmLogout = false
        val webMid = AccountStore.activeMid(AccountStore.PORT_WEB)
        if (webMid > 0L) {
            doWebLogout(webMid)
        } else {
            doPortLogout()
        }
    }

    /** 退 web 激活账号：协议退出 + 交给 AccountStore 清该账号槽位并自动切换下一个 */
    private fun doWebLogout(webMid: Long) {
        CenterThreadPool.run(Runnable { UserInfoApi.exitLogin() })
        AccountStore.logout(AccountStore.PORT_WEB, webMid)
        // 换号后 Cookie 变了，请求头必须同步刷新，否则后面的请求还带着旧号的 Cookie
        NetWorkUtil.refreshHeaders()
        MsgUtil.showMsg("Web 账号已退出")
        afterAccountChanged()
    }

    /** 退端口激活账号：按「页签 2」选的端口退，不影响 web 与其他端口 */
    private fun doPortLogout() {
        val port = AccountStore.mainPort()
        val mid = AccountStore.activeMid(port)
        if (mid <= 0L) {
            // 该端口没有激活账号（可能是别的端口登录着），退掉真正登录着的那个端口
            AccountStore.logoutActive(AccountStore.primaryPort())
        } else {
            AccountStore.logout(port, mid)
        }
        MsgUtil.showMsg("已退出" + AccountStore.portDisplayName(port) + "端账号")
        afterAccountChanged()
    }

    /**
     * 退出之后的收尾：**不再强制跳登录页**。
     *
     * 历史行为是退完立刻 `startActivity(LoginActivity)` —— 这等于「刚退出就被塞回登录页」，
     * 与本次「不强制登录」的诉求直接冲突，也让人没法看看别的页面。
     * 现在的处理：
     * - 还有别的通道账号 → 就地刷成本地资料，留在本页（用户能继续用）；
     * - 一个账号都没有 → 就地刷成「未登录」占位（页面本身仍可用，顶部有去登录的引导），
     *   用户想登录时自己点，而不是被强推。
     */
    private fun afterAccountChanged() {
        renderLocalProfile()
        bindPageActions()
    }

    /**
     * 仅供页面内「去登录」入口 / 无账号时点击卡片使用（用户主动触发，不是强制跳转）。
     *
     * v5.1.5 起登录入口统一收敛到「设置 → 账号管理」：那里能选端口（Web / Android手机 / HD / TV）
     * 并登录任意端口的任意多个账号。这里不再直接跳 web 扫码页——否则用户会以为「只能用 web」，
     * 而实际上哪个端口登录都能用全部功能。
     */
    private fun jumpToLogin() {
        startActivity(Intent(this, SettingAccountActivity::class.java))
    }
}
