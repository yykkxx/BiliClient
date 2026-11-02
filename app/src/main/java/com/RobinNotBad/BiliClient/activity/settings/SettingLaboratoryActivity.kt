package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.os.Bundle

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.SettingsAdapter
import com.RobinNotBad.BiliClient.api.migration.ApiRouter
import com.RobinNotBad.BiliClient.model.SettingSection
import android.content.Intent
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.ui.widget.recycler.ListLayoutUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StreamPolicyManager
import com.RobinNotBad.BiliClient.util.WifiPreloadManager

import java.util.ArrayList

class SettingLaboratoryActivity : RefreshListActivity() {

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setPageName("实验室")

        val debugBuild = BiliTerminal.isDebugBuild()

        val sectionList = ArrayList<SettingSection>()

        // ==================== 三级迁移模型（v5.1.4） ====================
        // 用户需求把 API 迁移分成三级，这里按级别顺序展示，并在每个开关文案里
        // 写清「打开后进入第几级、会带来什么变化」。级别判定收敛在 AccountManager.currentTier()。
        sectionList.add(SettingSection("title", "API 迁移（三级）", "", "", ""))
        sectionList.add(SettingSection("click", "当前级别", "",
                AccountManager.tierName(AccountManager.currentTier()) +
                        "。一级=纯Web；二级=仅推荐页走APP接口；三级=全部接口走端口且账号与页面解耦。" +
                        "点此查看迁移统计与命中情况", "false",
                Runnable {
                    startActivity(Intent(this, SettingApiMigrationActivity::class.java))
                }))
        // 推荐 API 迁移（第二级）：只影响推荐页
        sectionList.add(SettingSection("click", "推荐 API 迁移", "",
                "打开后：推荐页改用移动端（APP端）接口，其余功能仍全部走 Web（二级）。" +
                        "需要在进入的页面里扫码登录该端后开启。" +
                        "三级模式下，此栏目还会多出「Web 端」选项，可让推荐页改回 Web 接口。", "false",
                Runnable {
                    startActivity(Intent(this, SettingAppRcmdActivity::class.java))
                }))
        // 全 API 迁移（第三级）：所有接口走端口
        sectionList.add(SettingSection("switch", "全 API 迁移（三级）",
                SharedPreferencesUtil.API_MIGRATION_ENABLED,
                "打开后进入三级：所有接口都走所选端口（身份/签名/登录复用上面的推荐 API 设置），" +
                        "失败自动回退 Web。推荐 API 栏目会增设 Web 端选项。" +
                        "此级别下账号与页面彻底解耦——退出 Web 登录后，「我的」「稍后再看」「动态」「消息」等照常可用，" +
                        "只要任一端口已登录。关闭则回到二级（若推荐 API 也关则回到一级纯 Web）。",
                // 注意：ApiRouter 里这个键的读取默认值是 true（5.0.4 起默认开启）。
                // 这里的 default 必须与之一致，否则开关会显示"关"而实际行为是"开"。
                "true"))
        sectionList.add(SettingSection("click", "全 API 迁移状态", "",
                "查看当前身份/登录、APP 命中次数、回退次数、最近失败端点，并可清空统计", "false",
                Runnable {
                    startActivity(Intent(this, SettingApiMigrationActivity::class.java))
                }))
        // 重构账号体系（实验，v5.1.1）：单端口模式总开关。打开后到「设置 → 登录选项」选端口并登录该端口；
        // 路由是否生效由 ApiRouter.isEnabled 综合判断（端口=web 时本开关不改变请求行为），
        // 与旧「全 API 迁移」开关相互独立、旧开关默认值不动。切换时清掉连续失败暂停，
        // 让端点立刻按新配置重试（幂等操作，重复触发无副作用）。
        sectionList.add(SettingSection("switch", "重构账号体系（单端口）",
                SharedPreferencesUtil.LAB_ACCOUNT_REFACTOR,
                "打开后请在「设置 → 登录选项」里选择端口并登录该端口：全接口统一走所选端，" +
                        "并可在此处完全退出 Web 登录（该端登录后，收藏/历史/动态/消息等全部功能照常可用）。" +
                        "本开关只决定全 API 路由走哪个端口；推荐页移动端逻辑的身份仍由其自身设置控制。" +
                        "实验特性：降级回老版本后本开关自动失效，旧登录不受影响。",
                "false",
                Runnable { ApiRouter.clearSuspensions() }))

        sectionList.add(SettingSection("title", "其他实验性功能", "", "", ""))

        sectionList.add(SettingSection("title", "可用性", "", "", ""))
        sectionList.add(SettingSection("switch", "新版弹幕获取方式", "new_danmaku_api",
                getString(R.string.desc_new_danmaku_api), "true"))
        sectionList.add(SettingSection("switch", "私信未读标记", SharedPreferencesUtil.PRIVATE_MSG_UNREAD_BADGE_ENABLE,
                getString(R.string.desc_private_msg_unread_badge_enable), "false"))

        sectionList.add(SettingSection("title", "下载", "", "", ""))
        sectionList.add(SettingSection("switch", "使用旧版下载器", "dev_download_old",
                getString(R.string.setting_lab_download_old), "false"))
        sectionList.add(SettingSection("input_string", "缓存路径", "save_path_video",
                getString(R.string.setting_lab_path_video), FileUtil.getVideoDownloadPath().toString()))
        sectionList.add(SettingSection("input_string", "图片下载路径", "save_path_pictures",
                getString(R.string.setting_lab_path_pictures), FileUtil.getPicturePath().toString()))

        sectionList.add(SettingSection("title", "UI", "", "", ""))
        sectionList.add(SettingSection("switch", "文字跑马灯", "marquee_enable", getString(R.string.setting_lab_marquee),
                "true"))
        // 视频列表双列模式（#12）
        sectionList.add(SettingSection("switch", "视频列表双列模式", ListLayoutUtil.PREF_DUAL_COLUMN,
                getString(R.string.setting_lab_dual_column), "false"))
        // 拉流策略（#11，替代原"高温预缓存"）：慢速持续=稳（4G 耗电发热）；脉冲式=4G 不发热但可能偶卡
        sectionList.add(SettingSection("multi_choose", "拉流策略", StreamPolicyManager.PREF_STREAM_POLICY,
                "①慢速持续拉取：稳定，4G 耗电、发热\n②脉冲式：快速拉一段就停，循环往复——4G 不发热，但可能偶尔卡顿",
                "1", Pair(arrayOf("慢速持续", "脉冲式"), intArrayOf(StreamPolicyManager.POLICY_CONTINUOUS, StreamPolicyManager.POLICY_PULSE))))

        sectionList.add(SettingSection("title", "播放器", "", "", ""))
        // 短视频模式（shortvideo 分支专属）：菜单显示「短视频」入口
        sectionList.add(SettingSection("switch", "短视频入口", "menu_story",
                "在主页菜单显示「短视频」入口：竖屏短视频专用接口（feed/index/story），上滑下一个、看完自动连播，独立播放器不影响原播放器。", "true"))
        // WiFi 预加载（#5）：进详情页即预取视频流前几 MB，点播放秒开
        sectionList.add(SettingSection("switch", "WiFi视频预加载", WifiPreloadManager.PREF_WIFI_PRELOAD,
                "连接WiFi时，进入视频详情页就开始预加载视频数据（约4MB），点播放时秒开。仅在WiFi下生效。", "false"))
        sectionList.add(SettingSection("switch", "播放器旋屏兼容方案", "dev_player_rotate_software",
                "在极少数手表上（如小米手表），系统旋屏存在显示不全的问题。打开此开关，播放器将会使用软件旋屏方法。", "false"))
        sectionList.add(SettingSection("switch", "显示视频分段", "player_show_viewpoints",
                "显示视频的章节看点信息，可快速跳转到指定章节", "false"))
        sectionList.add(SettingSection("switch", "系统媒体控件", SharedPreferencesUtil.PLAYER_MEDIA_SESSION_ENABLE,
                getString(R.string.setting_lab_media_session), "false"))
        sectionList.add(SettingSection("switch", "互动视频调试", "player_interaction_debug",
                "在互动视频播放时，在左侧倍速按钮上方显示调试按钮，可以查看和修改互动视频的变量", "false"))

        sectionList.add(SettingSection("title", "调试", "", "", ""))
        sectionList.add(SettingSection("switch", "允许Logu.v", "dev_logv", getString(R.string.setting_lab_logv),
                debugBuild.toString()))
        sectionList.add(SettingSection("switch", "允许Logu.d", "dev_logd", "", debugBuild.toString()))
        sectionList.add(SettingSection("switch", "允许Logu.i", "dev_logi", "", debugBuild.toString()))
        sectionList.add(SettingSection("switch", "详细显示数据解析报错", "dev_jsonerr_detailed",
                getString(R.string.setting_lab_jsonerr_detailed), debugBuild.toString()))
        sectionList.add(SettingSection("switch", "详细显示列表报错", "dev_recyclererr_detailed",
                getString(R.string.setting_lab_recyclererr_detailed), debugBuild.toString()))

        recyclerView!!.setHasFixedSize(true)

        val adapter = SettingsAdapter(this, sectionList)
        setAdapter(adapter)

        setRefreshing(false)
    }

}
