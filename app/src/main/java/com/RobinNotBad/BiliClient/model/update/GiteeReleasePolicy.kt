package com.RobinNotBad.BiliClient.model.update

import java.net.URI

/** Fail closed: never guess from the first APK, extension, channel name or source archive. */
object GiteeReleasePolicy {
    const val RELEASES_URL = "https://gitee.com/yykkxxykx/BiliClient/releases"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    const val MAIN_PACKAGE = "com.RobinNotBad.BiliClient"
    const val CHANNEL_PACKAGE = "com.tencent.qqmusicwatchs"
    private const val GITEE_HOST = "gitee.com"
    private const val CDN_HOST = "foruda.gitee.com"
    private const val DOWNLOAD_PATH = "/yykkxxykx/BiliClient/releases/download/"
    private val ATTACH_DOWNLOAD_PATH = Regex("^/yykkxxykx/BiliClient/attach_files/[0-9]+/download/[^/]+\\.apk$")
    // 下划线出现在渠道包产物名里（com.tencent.qqmusicwatchs_20260606.apk），其余规则不变：
    // 仍要求以字母/数字开头、只允许单段文件名、必须以 .apk 结尾，不接受任何路径分隔符。
    private val safeFileName = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,159}\\.apk$")
    // 渠道包由 changed/build_flavor.sh 以「包名_版本码[_hidden].apk」产出；版本码是渠道包自己的
    // 固定 digit 串（不是主包 versionName），所以这里只约束形状、不写死 20260606。
    private val channelApkName = Regex("^com\\.tencent\\.qqmusicwatchs_[0-9]{1,9}(_hidden)?\\.apk$")
    private val encodedDot = Regex("%2e", RegexOption.IGNORE_CASE)

    /**
     * 本次更新要下的是「属于当前这份安装包」的那个附件，三者互斥：
     * - 主包 → `BiliClient-<ver>-signed.apk`
     * - 渠道包显示版（桌面有图标）→ `com.tencent.qqmusicwatchs_<版本码>.apk`
     * - 渠道包隐藏版（桌面无图标）→ `com.tencent.qqmusicwatchs_<版本码>_hidden.apk`
     *
     * 为什么要这么分：三个包共用同一份源码，但包名/版本号/启动器入口都不同。
     * 主包绝不能下载渠道包（装上去会变成另一个应用），渠道包也绝不能下载主包
     * （包名不一致，系统直接拒绝安装），显示版和隐藏版装反则会让桌面图标消失。
     */
    enum class UpdateTarget(val packageName: String, val checksVersionName: Boolean) {
        MAIN(MAIN_PACKAGE, true),
        CHANNEL_VISIBLE(CHANNEL_PACKAGE, false),
        CHANNEL_HIDDEN(CHANNEL_PACKAGE, false),

        /** 包名既不是主包也不是渠道包（例如别人重签的 fork）：一个 APK 都不提供下载。 */
        UNSUPPORTED("", false);

        val isChannel: Boolean get() = this == CHANNEL_VISIBLE || this == CHANNEL_HIDDEN
    }

    /** 渠道包没有可比较的语义版本，只能用「安装时间 vs 本次发布时间」给一个保守提示。 */
    enum class ChannelFreshness { LIKELY_NEWER, LIKELY_CURRENT, UNKNOWN }

    /**
     * 渠道包显示版与隐藏版共用同一个包名 `com.tencent.qqmusicwatchs`，唯一稳定的区别是
     * 「有没有启动器入口」：`changed/build_flavor.sh` 保证隐藏版删掉 LauncherActivity 的
     * MAIN/LAUNCHER（历史多次 `aapt2 dump badging` 验证 launchable 分别为 1 条 / 0 条）。
     * 所以运行期用 `getLaunchIntentForPackage(packageName) != null` 判定，而不是新增 BuildConfig 字段
     * ——渠道包构建脚本只改 applicationId/版本号/清单，不会为我们新增的字段单独打补丁。
     */
    fun resolveTarget(packageName: String, hasLauncherEntry: Boolean): UpdateTarget = when (packageName) {
        MAIN_PACKAGE -> UpdateTarget.MAIN
        CHANNEL_PACKAGE ->
            if (hasLauncherEntry) UpdateTarget.CHANNEL_VISIBLE else UpdateTarget.CHANNEL_HIDDEN
        else -> UpdateTarget.UNSUPPORTED
    }

    /** 按目标挑选唯一的 APK 附件；挑不出唯一一个就返回 null，绝不用「第一个 apk」兜底。 */
    fun selectApk(target: UpdateTarget, tag: String, assets: List<GiteeReleaseAsset>): GiteeReleaseAsset? =
        when (target) {
            UpdateTarget.MAIN -> selectMainApk(tag, assets)
            UpdateTarget.CHANNEL_VISIBLE -> selectChannelApk(tag, assets, hidden = false)
            UpdateTarget.CHANNEL_HIDDEN -> selectChannelApk(tag, assets, hidden = true)
            UpdateTarget.UNSUPPORTED -> null
        }

    /** 下载前再核一次「这个文件名是不是本目标的包」，防止调用方把选错的资产喂进来。 */
    fun assetMatchesTarget(target: UpdateTarget, assetName: String): Boolean = when (target) {
        UpdateTarget.MAIN -> assetName.startsWith("BiliClient-") && assetName.endsWith("-signed.apk")
        UpdateTarget.CHANNEL_VISIBLE -> channelApkName.matches(assetName) && !assetName.endsWith(SHADOW_SUFFIX)
        UpdateTarget.CHANNEL_HIDDEN -> channelApkName.matches(assetName) && assetName.endsWith(SHADOW_SUFFIX)
        UpdateTarget.UNSUPPORTED -> false
    }

    /**
     * 解析阶段先把「形状合法 + URL 属于本 fork 该 tag」的 APK 全留下（主包 + 两个渠道包）。
     * 不能在这里就选主包：渠道包运行时需要的是它自己那一份。
     */
    fun candidateApks(tag: String, assets: List<GiteeReleaseAsset>): List<GiteeReleaseAsset> = assets.filter {
        safeFileName.matches(it.name) && isSafeAsset(tag, it)
    }

    fun selectMainApk(tag: String, assets: List<GiteeReleaseAsset>): GiteeReleaseAsset? {
        val version = UpdateVersion.releaseVersion(tag) ?: return null
        val expectedName = "BiliClient-$version-signed.apk"
        val matches = assets.filter { it.name == expectedName }
        // Duplicated attachment aliases can point to stale files: don't silently choose one.
        val candidate = matches.singleOrNull() ?: return null
        return candidate.takeIf { isSafeAsset(tag, it) }
    }

    private fun selectChannelApk(tag: String, assets: List<GiteeReleaseAsset>, hidden: Boolean): GiteeReleaseAsset? {
        // 渠道包文件名里带的是渠道包自己的版本码，与 tag 的语义版本无关，所以只校验 tag 合法。
        if (UpdateVersion.releaseVersion(tag) == null) return null
        val matches = assets.filter { channelApkName.matches(it.name) && it.name.endsWith(SHADOW_SUFFIX) == hidden }
        val candidate = matches.singleOrNull() ?: return null
        return candidate.takeIf { isSafeAsset(tag, it) }
    }

    /**
     * 渠道包版本号固定（versionName「QQ音乐」、versionCode 也不随主包递增），
     * `UpdateVersion.compare` 对「QQ音乐」必然返回 null，所以只能退一步做时间判断：
     * 本机安装时间明显早于本次 Release 建立时间 → 装的很可能不是这一版。
     * 容差 1 小时用于吸收设备时钟偏差（Release 时间来自 Gitee 服务端）。
     * 结论只用于提示，永远不阻止下载 —— 版本新鲜度最终由 Release tag 本身保证。
     */
    fun channelFreshness(installedUpdateTimeMs: Long, releaseTimeMs: Long?): ChannelFreshness {
        if (installedUpdateTimeMs <= 0L || releaseTimeMs == null || releaseTimeMs <= 0L) {
            return ChannelFreshness.UNKNOWN
        }
        return if (installedUpdateTimeMs + CHANNEL_TIME_TOLERANCE_MS < releaseTimeMs) {
            ChannelFreshness.LIKELY_NEWER
        } else {
            ChannelFreshness.LIKELY_CURRENT
        }
    }

    fun isSafeAsset(tag: String, asset: GiteeReleaseAsset): Boolean {
        if (!safeFileName.matches(asset.name)) return false
        val uri = parseUri(asset.downloadUrl) ?: return false
        if (!isSafeHttps(uri) || uri.host != GITEE_HOST) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        // Compare the normalized path: OkHttp collapses dot segments before sending, so a raw
        // "…/download/../../Other/…" would reach a different repository than the URI suggests.
        val path = normalizedPath(uri) ?: return false
        return path == "$DOWNLOAD_PATH$tag/${asset.name}"
    }

    /** Redirects are followed manually, not via a global Bilibili client/interceptor. */
    fun isSafeDownloadHop(url: String): Boolean {
        val uri = parseUri(url) ?: return false
        if (!isSafeHttps(uri)) return false
        val path = normalizedPath(uri) ?: return false
        return when (uri.host) {
            GITEE_HOST -> path.startsWith(DOWNLOAD_PATH) || ATTACH_DOWNLOAD_PATH.matches(path)
            CDN_HOST -> path.startsWith("/attach_file/") || path.startsWith("/attach_files/")
            else -> false
        }
    }

    fun isAllowedContentType(contentType: String?): Boolean {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        return mime == null || mime in setOf(APK_MIME_TYPE, "application/octet-stream", "application/zip")
    }

    fun hasApkZipHeader(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()

    fun isCompleteLength(actual: Long, expected: Long): Boolean = actual > 0 && (expected < 0 || actual == expected)

    /**
     * 安装前把 APK 拷进私有 cacheDir 时用的文件名。资产名来自网络响应，必须先削平路径
     * （`..`、分隔符、盘符）再只留安全字符，否则 `File(dir, name)` 可能被拼出目录穿越。
     * 后缀固定补 `.apk`，方便系统安装器识别类型。
     */
    fun installFileName(displayName: String): String {
        val base = displayName.substringAfterLast('/').substringAfterLast('\\')
        val sanitized = base.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .trimStart('.')
        if (sanitized.isEmpty() || sanitized == "apk") return "update.apk"
        return if (sanitized.endsWith(".apk")) sanitized else "$sanitized.apk"
    }

    private fun isSafeHttps(uri: URI): Boolean =
        uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)

    /**
     * RFC 3986 remove_dot_segments, matching OkHttp's path handling, plus the "%2e" spellings
     * OkHttp also treats as dots. Returns null when the path escapes above the root.
     */
    private fun normalizedPath(uri: URI): String? {
        val raw = uri.rawPath ?: return null
        if (!raw.startsWith("/")) return null
        val output = ArrayList<String>()
        for ((index, segment) in decodeDots(raw).split('/').withIndex()) {
            if (index == 0) continue // leading separator
            when (segment) {
                "." -> Unit
                ".." -> {
                    if (output.isEmpty()) return null
                    output.removeAt(output.size - 1)
                }
                else -> output.add(segment)
            }
        }
        return "/" + output.joinToString("/")
    }

    /** OkHttp also treats "%2e" spellings as dot segments, so normalize those before splitting. */
    private fun decodeDots(rawPath: String): String = encodedDot.replace(rawPath, ".")

    private fun parseUri(url: String): URI? = try {
        URI(url)
    } catch (ignored: java.net.URISyntaxException) {
        null
    }

    private const val SHADOW_SUFFIX = "_hidden.apk"

    /** absorbs device clock skew against Gitee's server-side release time; see [channelFreshness]. */
    private const val CHANNEL_TIME_TOLERANCE_MS = 60L * 60L * 1000L
}
