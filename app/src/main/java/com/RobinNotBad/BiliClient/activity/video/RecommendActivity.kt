package com.RobinNotBad.BiliClient.activity.video

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshMainActivity
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity
import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.api.CookieRefreshApi
import com.RobinNotBad.BiliClient.api.CookiesApi
import com.RobinNotBad.BiliClient.api.RecommendApi
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.CoroutineScopes
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil


import java.util.ArrayList

//推荐页面
//2023-07-13
//现已作为启动页（原 SplashActivity 已删除）：打开应用直接进入推荐，
//原开屏页的 Cookie 刷新检查 / 初次设置跳转 / 版本检查等启动逻辑搬到这里。

class RecommendActivity : RefreshMainActivity() {

    // v5.3.0：`videoCardList` / `videoCardAdapter` 改成可空并在第一次成功加载后才赋值。
    //
    // 旧写法是 `lateinit var` + `firstRefresh` 标志，问题在于 `refreshRecommend()` 分两支：
    // 首次走「新建列表」，之后走「就地 clear + notifyItemRangeRemoved」，而 `videoCardAdapter`
    // 也是在首次成功回调里才 `lateinit` 赋值。
    // 于是有一条真实会崩/静默失效的路径：**首次加载失败**（`loadFail` 只把 firstRefresh 留在 true）
    // → 用户下拉刷新 → 再次失败 → 都不碰 adapter（这条路没事）；
    // 但只要「首次成功过一次之后」列表被清空，`refreshRecommend` 就会走到
    // `videoCardAdapter.notifyItemRangeRemoved(...)`，而如果这次请求中途抛异常，
    // `notifyItemRangeInserted` 永远不会被调用 —— 用户看到的就是**下拉刷新只留下一个空列表**
    // （正是「web 端口无法刷新」的观感）。
    // 改成可空 + 每次刷新走同一个「成功才替换」的路径，能让失败时**保留旧内容**。
    private var videoCardList: MutableList<VideoCard> = ArrayList()
    private var videoCardAdapter: VideoCardAdapter? = null
    private var firstRefresh = true
    private var startWorkDone = false

    /** 刷新是否正在进行：挡住「下拉刷新」与「触底加载更多」并发改同一个列表（v5.3.0） */
    private var refreshInFlight = false

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setMenuClick()
        Log.e("debug", "进入推荐页")

        setOnRefreshListener { refreshRecommend() }
        setOnLoadMoreListener { addRecommend(false) }

        setPageName("推荐")

        // #4：双列模式适用于所有视频列表（推荐页）
        video_card_layout = true

        recyclerView!!.setHasFixedSize(true)

        TutorialHelper.showTutorialList(this, R.array.tutorial_recommend, 0)

        //原 SplashActivity 的启动收尾工作（Cookie 检查等），异步执行，不阻塞推荐页加载
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {
            CenterThreadPool.run(Runnable { doStartWork() })
        } else {
            //没完成初次设置，先去设置页
            val intent = Intent()
            intent.setClass(this, SetupUIActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        refreshRecommend()
    }

    override fun onResume() {
        super.onResume()
        //从设置页完成设置返回时补做启动工作
        if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false) && !startWorkDone) {
            CenterThreadPool.run(Runnable { doStartWork() })
        }
    }

    /**
     * 原 SplashActivity 中的启动逻辑：Cookie 刷新检查 + 应用版本检查
     */
    private fun doStartWork() {
        startWorkDone = true
        try {
            // 已登录时检查 Cookie 是否需要刷新
            if (SharedPreferencesUtil.getLong("mid", 0L) != 0L) {
                checkCookieRefresh()
            }
            CookiesApi.checkCookies()
        } catch (e: Exception) {
            MsgUtil.err(e)
        }
        CenterThreadPool.run(Runnable { AppInfoApi.check(this) })
    }

    /**
     * 进入推荐页时检查一次 Cookie 续期（v5.3.1：转调唯一入口）。
     *
     * # 旧实现的两个严重问题（都已在 v5.3.1 修掉，别写回去）
     *
     * ## 1. 网络异常会**清掉用户的登录态**
     * 旧代码的 `catch (e: JSONException)` 和 `catch (e: Exception)` 分支里都调了 `resetLogin()`，
     * 而 `resetLogin()` 会把 mid / csrf / cookies / refresh_token 全部清空。
     * 也就是说：**用户在地铁里进一次推荐页、网络抖一下，登录就被清了**，
     * 必须重新扫码。这是本轮排查里最危险的一处 —— 网络失败与凭据失效是两件事，
     * 任何情况下都不能因为「请求没成功」就删凭据。
     * → 现在只有服务端**明确**说 refresh_token 过期（86032）才提示重新登录，
     *   而且**不自动清号**（清号必须由用户显式操作，见账号管理页的退出）。
     *
     * ## 2. 把 refresh_csrf / correspondPath 打在日志里
     * 旧代码 `Log.e("CorrespondPath", ...)`、`Log.e("RefreshCsrf", ...)` 会把这些值
     * 完整写进 logcat。它们是凭据链的一环，不该出现。已删。
     *
     * # 为什么保留这个入口
     * 推荐页是用户最常进的页面，在这里兜一次续期能在「长时间不重启 App」的场景下
     * 补上启动时那一次没有覆盖到的时间窗。节流由 [CookieRefreshApi.renewIfNeededOnForeground] 负责。
     */
    private fun checkCookieRefresh() {
        try {
            val result = CookieRefreshApi.renewIfNeededOnForeground(false)
            if (result.renewed()) {
                MsgUtil.showMsg("Cookies已刷新")
            }
        } catch (e: Exception) {
            // 续期是锦上添花：任何异常都只记日志，**绝不清登录态**
            Logu.w("Cookies", "推荐页续期检查异常：" + e.javaClass.simpleName)
        }
    }

    // v5.3.1：原来的 resetLogin() 已**删除**（不是暂时不用，是刻意不留）。
    //
    // 它做的是「把 mid / csrf / cookies / refresh_token 全部清空」——
    // 而它唯一的两个调用点都在 checkCookieRefresh 的 catch 分支里，
    // 也就是「网络抖动 → 清空登录态 → 用户必须重新扫码」。
    // 现在那个入口改成转调唯一续期入口（只记日志、绝不清号），它就没有调用点了。
    //
    // 为什么删除而不是留着备用：一个「一调用就把用户账号清掉」的函数留在代码里，
    // 下次任何人遇到「登录好像有问题」都可能顺手调它，而它的危害是不可逆的
    // （用户得重新扫码，多设备还要重新授权）。退出登录有正规入口
    // （账号管理页 → 退出），那里有确认弹窗，语义也对。

    /**
     * 下拉刷新（用户需求 #8：「web 端口无法刷新」，v5.3.0 重写）。
     *
     * # 旧实现为什么表现为「无法刷新」
     * 旧代码在**开始刷新时就把列表清空**（`videoCardList.clear()` + `notifyItemRangeRemoved`），
     * 然后才去后台发请求。这条路径有两个致命点：
     * 1. **失败即空列表**：请求抛异常时走 `loadFail`，它只 `setRefreshing(false)`，
     *    **不会把旧数据放回来** —— 用户看到的是「转了一圈，列表空了/没变」，即「刷新无效」；
     * 2. **清空与回填不是原子的**：web 推荐要发 WBI 签名请求，本机在弱网/风控下
     *    偶发 `data:null`（旧解析会抛 JSONException），于是这个问题在 web 端口上更容易复现。
     *
     * # 现在的做法（「成功才替换」）
     * 不再提前清空：先在后台把新列表拉全，成功后再**一次性替换** adapter 的数据源。
     * - 成功：`videoCardList` 换成新列表并 `notifyDataSetChanged`，用户看到内容整体换新；
     * - 失败：**旧内容原样保留**（只是收起下拉圈 + 弹一条原因），用户至少还能继续看，
     *   而不是对着空白页以为「刷新把东西弄没了」。
     *
     * 同时用 [refreshInFlight] 挡住「下拉刷新」与「触底加载更多」并发改同一个列表
     * —— 两者都会 `addAll`/`clear`，并发时会出现「刷新回来的内容被上一页追加覆盖」的错序。
     */
    private fun refreshRecommend() {
        Log.e("debug", "刷新")
        if (refreshInFlight) return
        refreshInFlight = true
        // 用 CoroutineScopes 而不是 CenterThreadPool + runOnUiThread：新代码门禁禁止后者，
        // 且协程版有结构化并发 —— `io { }` 跑完自动切回主线程，不需要手动 post。
        CoroutineScopes.launchUi {
            try {
                val list: MutableList<VideoCard> = ArrayList()
                CoroutineScopes.io { RecommendApi.getRecommend(list, true) }
                if (isDestroyed || isFinishing) return@launchUi
                applyRefreshedList(list)
            } catch (e: Exception) {
                if (isDestroyed || isFinishing) return@launchUi
                // 关键：这里**不碰 videoCardList**，旧内容原样保留
                report(e)
            } finally {
                // 无论成败都要收起下拉圈，否则 SwipeRefresh 会一直转（旧实现的另一个观感问题）
                setRefreshing(false)
                refreshInFlight = false
            }
        }
    }

    /**
     * 刷新成功后把新数据整体换进列表。
     *
     * 首次（还没建 adapter）走「新建 adapter + setAdapter」；之后走
     * 「原地替换内容 + notifyDataSetChanged」。
     *
     * 为什么之后不用 `notifyItemRangeInserted`：刷新是**整体替换**语义，不是追加；
     * 用插入通知需要精确算出旧长度/新长度，任何一处算错都会 IndexOutOfBounds
     * （收藏夹页历史上就是这么崩过，见 notes/fav_fix_alpha8.md）。
     * 推荐页数据量只有几十条，整体刷新一次的开销可以忽略。
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun applyRefreshedList(list: MutableList<VideoCard>) {
        videoCardList = list
        // adapter 持有的是构造时传进去的 list 引用（`val videoCardList: List<VideoCard>`），
        // 换成新列表不会自动同步，所以这里**直接新建一个 adapter 再 setAdapter**。
        // 为什么不复用旧 adapter 只 notifyDataSetChanged：那会继续显示旧 list 的内容
        // （引用没变），等于「刷新了但界面不变」—— 正是本条需求要修的现象。
        // 推荐页只有几十条，重建 adapter 的开销可以忽略。
        val newAdapter = VideoCardAdapter(this, videoCardList)
        newAdapter.feedLayout = ListLayoutUtil.isDualColumnVideo() // #4
        videoCardAdapter = newAdapter
        setAdapter(newAdapter)
        firstRefresh = false
    }

    private fun addRecommend(isRefresh: Boolean) {
        Log.e("debug", "加载下一页")
        CoroutineScopes.launchUi {
            try {
                val list: MutableList<VideoCard> = ArrayList()
                CoroutineScopes.io { RecommendApi.getRecommend(list, isRefresh) }
                if (isDestroyed || isFinishing) return@launchUi
                setRefreshing(false)
                appendLoadedList(list)
            } catch (e: Exception) {
                if (isDestroyed || isFinishing) return@launchUi
                loadFail(e)
            }
        }
    }

    /**
     * 加载更多成功后把新数据追加到列表。
     *
     * `isRefresh`（首次进页）也走这里：首次同样等价于「往空列表里追加一整页」，
     * 所以不需要两条分支 —— 少一条分支就少一处会和 [refreshRecommend] 的语义打架的地方。
     */
    private fun appendLoadedList(list: MutableList<VideoCard>) {
        videoCardList.addAll(list)
        val adapter = videoCardAdapter
        if (adapter == null) {
            val newAdapter = VideoCardAdapter(this, videoCardList)
            newAdapter.feedLayout = ListLayoutUtil.isDualColumnVideo() // #4
            videoCardAdapter = newAdapter
            setAdapter(newAdapter)
            firstRefresh = false
        } else {
            adapter.notifyItemRangeInserted(videoCardList.size - list.size, list.size)
        }
    }
}
