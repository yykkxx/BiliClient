package com.RobinNotBad.BiliClient.activity.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.GiteeReleaseApi
import com.RobinNotBad.BiliClient.model.update.GiteeRelease
import com.RobinNotBad.BiliClient.model.update.GiteeReleaseAsset
import com.RobinNotBad.BiliClient.model.update.GiteeReleasePolicy
import com.RobinNotBad.BiliClient.model.update.UpdateVersion
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Settings opens this page immediately; old upstream Intent extras are deliberately ignored. */
class UpdateInfoActivity : BaseActivity() {
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = GiteeReleaseApi()
    private var release: GiteeRelease? = null

    /** 当前这份安装包要下载的是哪一份 APK（主包 / 渠道显示版 / 渠道隐藏版），onCreate 时判定一次。 */
    private var target: GiteeReleasePolicy.UpdateTarget = GiteeReleasePolicy.UpdateTarget.UNSUPPORTED
    private var loading = false
    private var downloading = false
    private var installing = false
    private var savedUri: Uri? = null
    private var savedTag: String? = null
    private var interrupted = false

    /** 更新说明默认折叠：正文可能上百行，展开前不占屏；状态随旋转/重建保留。 */
    private var notesExpanded = false

    /** Gitee 不返回资产体积，HEAD 探测到的值存在这里（Release 快照本身不因此被复制）。 */
    private var probedSize: Long? = null
    private lateinit var status: TextView
    private lateinit var version: TextView
    private lateinit var releaseDetails: TextView
    private lateinit var notes: TextView
    private lateinit var notesHeader: View
    private lateinit var notesArrow: ImageView
    private lateinit var fileStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var detailsCard: View
    private lateinit var retryButton: MaterialButton
    private lateinit var downloadButton: MaterialButton
    private lateinit var installButton: MaterialButton
    private lateinit var downloadsButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_info)
        target = resolveTarget()
        notesExpanded = savedInstanceState?.getBoolean(STATE_NOTES_EXPANDED, false) == true
        bindViews()
        setPageName(getString(R.string.update_gitee_title))
        savedUri = savedInstanceState?.getString(STATE_URI)?.let(Uri::parse)
        savedTag = savedInstanceState?.getString(STATE_TAG)
        interrupted = savedInstanceState?.getBoolean(STATE_DOWNLOADING, false) == true
        renderNotesState()
        loadRelease()
    }

    private fun bindViews() {
        status = findViewById(R.id.updateStatus)
        version = findViewById(R.id.updateVersion)
        releaseDetails = findViewById(R.id.updateReleaseDetails)
        notes = findViewById(R.id.updateNotes)
        notesHeader = findViewById(R.id.updateNotesHeader)
        notesArrow = findViewById(R.id.updateNotesArrow)
        fileStatus = findViewById(R.id.updateFileStatus)
        progressBar = findViewById(R.id.updateProgress)
        detailsCard = findViewById(R.id.updateDetailsCard)
        retryButton = findViewById(R.id.updateRetry)
        downloadButton = findViewById(R.id.updateDownload)
        installButton = findViewById(R.id.updateInstall)
        downloadsButton = findViewById(R.id.updateOpenDownloads)
        findViewById<View>(R.id.top).setOnClickListener { finish() }
        retryButton.setOnClickListener { loadRelease() }
        downloadButton.setOnClickListener { downloadRelease() }
        installButton.setOnClickListener { installDownloaded() }
        downloadsButton.setOnClickListener { openDownloads() }
        notesHeader.setOnClickListener { toggleNotes() }
        findViewById<MaterialButton>(R.id.updateOpenGitee).setOnClickListener { openReleases() }
        findViewById<TextView>(R.id.updateInstalledVersion).text = installedVersionLabel()
    }

    /**
     * 判定当前是哪种安装包：主包 / 渠道显示版（桌面有图标）/ 渠道隐藏版（桌面无图标）。
     * 渠道包两份共用同一个包名，唯一稳定区别就是「有没有启动器入口」——
     * `changed/build_flavor.sh` 保证隐藏版删掉 LauncherActivity 的 MAIN/LAUNCHER。
     * 这里用运行期查询而不是新增 BuildConfig 字段：渠道包构建脚本只改 applicationId /
     * 版本号 / 清单，不会替我们给新增字段单独打补丁。
     */
    private fun resolveTarget(): GiteeReleasePolicy.UpdateTarget {
        val packageName = applicationContext.packageName
        if (packageName != GiteeReleasePolicy.CHANNEL_PACKAGE) {
            return GiteeReleasePolicy.resolveTarget(packageName, hasLauncherEntry = true)
        }
        return GiteeReleasePolicy.resolveTarget(packageName, hasLauncherEntry = hasLauncherEntry())
    }

    private fun hasLauncherEntry(): Boolean = try {
        packageManager.getLaunchIntentForPackage(packageName) != null
    } catch (ignored: Exception) {
        // 查询失败时按「隐藏版」处理会下错包，按「无法判定」处理更安全（不提供下载）。
        false
    }

    private fun installedVersionLabel(): String {
        val info = installedPackageInfo()
        if (target.isChannel && info != null) {
            return getString(
                R.string.update_gitee_installed_channel,
                info.versionName.orEmpty(),
                info.longVersionCode
            )
        }
        return getString(R.string.update_gitee_installed, BuildConfig.VERSION_NAME)
    }

    private fun installedPackageInfo(): android.content.pm.PackageInfo? = try {
        packageManager.getPackageInfo(packageName, 0)
    } catch (ignored: PackageManager.NameNotFoundException) {
        null
    }

    /** 加载/下载/安装三态互斥：任一在飞时，其余按钮都必须保持不可点。 */
    private fun isBusy(): Boolean = loading || downloading || installing

    private fun currentAsset(): GiteeReleaseAsset? = release?.apkFor(target)

    private fun assetSize(asset: GiteeReleaseAsset): Long? = asset.declaredSize ?: probedSize

    private fun loadRelease() {
        if (isBusy()) return
        loading = true
        release = null
        probedSize = null
        status.setText(R.string.update_gitee_loading)
        detailsCard.visibility = View.GONE
        fileStatus.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        updateButtons()
        pageScope.launch {
            try {
                val latest = api.fetchLatest()
                val existing = withContext(Dispatchers.IO) { savedDownloadExists(latest) }
                if (!existing) savedUri = null
                release = latest
                showRelease(latest)
                pageScope.launch { showResolvedSize(latest) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                status.text = getString(R.string.update_gitee_load_error, exception.message.orEmpty().take(160))
            } finally {
                loading = false
                if (!isDestroyed()) {
                    progressBar.visibility = View.GONE
                    updateButtons()
                }
            }
        }
    }

    private fun savedDownloadExists(latest: GiteeRelease): Boolean {
        val uri = savedUri ?: return false
        if (savedTag != latest.tagName) return false
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getLong(0) > 0
            } == true
        } catch (ignored: Exception) {
            false // A removed/revoked file must never be advertised as an existing successful download.
        }
    }

    private fun showRelease(latest: GiteeRelease) {
        detailsCard.visibility = View.VISIBLE
        status.setText(comparisonStatus(latest))
        version.text = getString(R.string.update_gitee_latest, latest.tagName)
        releaseDetails.text = getString(
            R.string.update_gitee_release_details, latest.title, stageLabel(latest.prerelease),
            formatPublishedAt(latest.publishedAt)
        )
        notes.text = latest.notes.ifBlank { getString(R.string.update_gitee_no_notes) }
        showFileStatus(latest, interrupted)
        interrupted = false
    }

    private fun showFileStatus(latest: GiteeRelease, wasInterrupted: Boolean) {
        fileStatus.visibility = View.VISIBLE
        val asset = latest.apkFor(target)
        fileStatus.text = when {
            savedUri != null -> getString(R.string.update_gitee_saved, asset?.name.orEmpty())
            wasInterrupted -> getString(R.string.update_gitee_interrupted)
            target == GiteeReleasePolicy.UpdateTarget.UNSUPPORTED -> getString(
                R.string.update_gitee_unsupported_build,
                applicationContext.packageName,
                GiteeReleasePolicy.MAIN_PACKAGE,
                GiteeReleasePolicy.CHANNEL_PACKAGE
            )
            asset == null -> targetMissingApkText(latest)
            else -> getString(R.string.update_gitee_target, asset.name, describeSize(assetSize(asset)))
        }
    }

    private fun targetMissingApkText(latest: GiteeRelease): String = when {
        target == GiteeReleasePolicy.UpdateTarget.MAIN && latest.apkAssets.isEmpty() ->
            getString(R.string.update_gitee_no_apk)
        else -> getString(R.string.update_gitee_no_target_apk, applicationContext.packageName)
    }

    /** Gitee omits asset sizes, so a HEAD probe fills them in without downloading the APK. */
    private suspend fun showResolvedSize(latest: GiteeRelease) {
        val asset = latest.apkFor(target) ?: return
        if (asset.declaredSize != null) return
        val resolved = api.probeAssetSize(asset) ?: return
        if (isDestroyed() || release?.tagName != latest.tagName) return
        if (downloading || savedUri != null) return
        probedSize = resolved
        showFileStatus(latest, wasInterrupted = false)
    }

    /**
     * 主包比语义版本；渠道包没有可比的语义版本（versionName 是「QQ音乐」、versionCode 固定），
     * 只能按「本机安装时间 vs 本次 Release 发布时间」给一个保守提示，绝不假装精确。
     */
    private fun comparisonStatus(latest: GiteeRelease): Int {
        if (!target.isChannel) {
            val comparison = UpdateVersion.compare(latest.tagName, BuildConfig.VERSION_NAME)
                ?: return R.string.update_gitee_version_unknown
            return when {
                comparison > 0 -> R.string.update_gitee_new_version
                comparison == 0 -> R.string.update_gitee_current
                else -> R.string.update_gitee_local_newer
            }
        }
        val installedAt = installedPackageInfo()?.lastUpdateTime ?: 0L
        return when (GiteeReleasePolicy.channelFreshness(installedAt, publishedEpochMs(latest.publishedAt))) {
            GiteeReleasePolicy.ChannelFreshness.LIKELY_NEWER -> R.string.update_gitee_channel_newer
            GiteeReleasePolicy.ChannelFreshness.LIKELY_CURRENT -> R.string.update_gitee_channel_current
            GiteeReleasePolicy.ChannelFreshness.UNKNOWN -> R.string.update_gitee_channel_unknown
        }
    }

    private fun stageLabel(prerelease: Boolean): String =
        getString(if (prerelease) R.string.update_gitee_prerelease else R.string.update_gitee_release)

    /** Gitee returns `created_at` with an offset; show it as local time and never crash on odd input. */
    private fun formatPublishedAt(raw: String): String {
        if (raw.isBlank()) return getString(R.string.update_gitee_unknown_time)
        val parsed = parsePublishedAt(raw) ?: return raw
        return LOCAL_FORMAT.format(parsed)
    }

    private fun publishedEpochMs(raw: String): Long? = parsePublishedAt(raw)?.time

    private fun parsePublishedAt(raw: String): Date? {
        if (raw.isBlank()) return null
        return try {
            ISO_FORMATS.firstNotNullOfOrNull { format -> runCatching { format.parse(raw) }.getOrNull() }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun describeSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return getString(R.string.update_gitee_size_unknown)
        val mib = bytes / (1024.0 * 1024.0)
        return getString(R.string.update_gitee_size_mib, mib)
    }

    private fun downloadRelease() {
        val latest = release ?: return
        val asset = latest.apkFor(target) ?: return
        if (isBusy() || savedUri != null) return
        if (target == GiteeReleasePolicy.UpdateTarget.UNSUPPORTED) {
            fileStatus.visibility = View.VISIBLE
            fileStatus.text = targetMissingApkText(latest)
            return
        }
        downloading = true
        fileStatus.visibility = View.VISIBLE
        fileStatus.setText(R.string.update_gitee_download_start)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        updateButtons()
        pageScope.launch {
            try {
                val uri = GiteeApkDownload(applicationContext, api, target).download(latest) { actual, total ->
                    withContext(Dispatchers.Main.immediate) { showProgress(actual, total) }
                }
                savedUri = uri
                savedTag = latest.tagName
                fileStatus.text = getString(R.string.update_gitee_saved, asset.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                fileStatus.text = getString(R.string.update_gitee_download_error, exception.message.orEmpty().take(160))
            } finally {
                downloading = false
                if (!isDestroyed()) {
                    progressBar.visibility = View.GONE
                    updateButtons()
                }
            }
        }
    }

    private fun showProgress(actual: Long, total: Long) {
        if (isDestroyed()) return
        if (total > 0) {
            val percent = (actual * 100 / total).coerceIn(0, 100).toInt()
            progressBar.isIndeterminate = false
            progressBar.progress = percent
            fileStatus.text = if (percent == 100) getString(R.string.update_gitee_verifying)
            else getString(R.string.update_gitee_progress, percent)
        } else {
            fileStatus.text = getString(R.string.update_gitee_progress_unknown, actual / 1024)
        }
    }

    /**
     * 安装是一个独立动作：把已下载并校验过的 APK 交给系统安装器。
     * 下载完成后按钮从「下载」切成「安装」，两者不会同时可点（见 [updateButtons]）。
     */
    private fun installDownloaded() {
        val uri = savedUri ?: return
        if (isBusy()) return
        val name = currentAsset()?.name.orEmpty()
        installing = true
        updateButtons()
        pageScope.launch {
            try {
                val outcome = UpdateApkInstaller.install(this@UpdateInfoActivity, uri, name)
                fileStatus.visibility = View.VISIBLE
                fileStatus.setText(installOutcomeText(outcome))
                if (outcome == UpdateApkInstaller.Outcome.MISSING) {
                    // 文件已被清理/移走：保持「未下载」态，让用户重新走下载，别留一个必然失败的安装按钮。
                    savedUri = null
                    savedTag = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                fileStatus.visibility = View.VISIBLE
                fileStatus.text = getString(R.string.update_gitee_download_error, exception.message.orEmpty().take(160))
            } finally {
                installing = false
                if (!isDestroyed()) updateButtons()
            }
        }
    }

    private fun installOutcomeText(outcome: UpdateApkInstaller.Outcome): Int = when (outcome) {
        UpdateApkInstaller.Outcome.STARTED -> R.string.update_gitee_install_start
        UpdateApkInstaller.Outcome.NEED_PERMISSION -> R.string.update_gitee_install_permission
        UpdateApkInstaller.Outcome.MISSING -> R.string.update_gitee_install_missing
        UpdateApkInstaller.Outcome.NO_INSTALLER -> R.string.update_gitee_install_no_handler
        UpdateApkInstaller.Outcome.FAILED -> R.string.update_gitee_install_failed
    }

    private fun toggleNotes() {
        notesExpanded = !notesExpanded
        renderNotesState()
    }

    /** 折叠只改正文可见性 + 箭头方向；标题始终可见，用户永远知道这里能点。 */
    private fun renderNotesState() {
        notes.visibility = if (notesExpanded) View.VISIBLE else View.GONE
        notesArrow.setImageResource(if (notesExpanded) R.drawable.arrow_up else R.drawable.arrow_down)
        notesArrow.contentDescription = getString(
            if (notesExpanded) R.string.update_gitee_notes_collapse else R.string.update_gitee_notes_expand
        )
    }

    private fun updateButtons() {
        val idle = !isBusy()
        val hasAsset = currentAsset() != null
        val hasDownload = savedUri != null
        retryButton.isEnabled = idle
        retryButton.setText(if (release == null) R.string.update_gitee_retry else R.string.update_gitee_refresh)
        // 三态互斥：未下载 → 可点的「下载」；下载中 → 禁用的「正在下载…」；已下载 → 只留「安装」。
        downloadButton.visibility = if (hasDownload) View.GONE else View.VISIBLE
        downloadButton.isEnabled = idle && hasAsset
        downloadButton.setText(if (downloading) R.string.update_gitee_downloading else R.string.update_gitee_download)
        installButton.visibility = if (hasDownload) View.VISIBLE else View.GONE
        installButton.isEnabled = idle && hasDownload
        downloadsButton.visibility = if (hasDownload) View.VISIBLE else View.GONE
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (ignored: ActivityNotFoundException) {
            fileStatus.visibility = View.VISIBLE
            fileStatus.setText(R.string.update_gitee_open_downloads_failed)
        }
    }

    private fun openReleases() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GiteeReleasePolicy.RELEASES_URL)))
        } catch (ignored: ActivityNotFoundException) {
            fileStatus.visibility = View.VISIBLE
            fileStatus.setText(R.string.update_gitee_open_browser_failed)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_URI, savedUri?.toString())
        outState.putString(STATE_TAG, savedTag)
        outState.putBoolean(STATE_DOWNLOADING, downloading)
        outState.putBoolean(STATE_NOTES_EXPANDED, notesExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // Cancels blocked OkHttp reads too; pending rows/private files are removed by helper finally.
        api.cancel()
        pageScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val STATE_URI = "gitee_update_saved_uri"
        private const val STATE_TAG = "gitee_update_saved_tag"
        private const val STATE_DOWNLOADING = "gitee_update_downloading"
        private const val STATE_NOTES_EXPANDED = "gitee_update_notes_expanded"
        private val ISO_FORMATS = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        )
        private val LOCAL_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
