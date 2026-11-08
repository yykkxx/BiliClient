package com.RobinNotBad.BiliClient.activity.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.GiteeReleaseApi
import com.RobinNotBad.BiliClient.model.update.GiteeRelease
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
import java.util.Locale
import java.util.TimeZone

/** Settings opens this page immediately; old upstream Intent extras are deliberately ignored. */
class UpdateInfoActivity : BaseActivity() {
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = GiteeReleaseApi()
    private var release: GiteeRelease? = null
    private var loading = false
    private var downloading = false
    private var savedUri: Uri? = null
    private var savedTag: String? = null
    private var interrupted = false
    private lateinit var status: TextView
    private lateinit var version: TextView
    private lateinit var releaseDetails: TextView
    private lateinit var notes: TextView
    private lateinit var fileStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var detailsCard: View
    private lateinit var retryButton: MaterialButton
    private lateinit var downloadButton: MaterialButton
    private lateinit var downloadsButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_info)
        bindViews()
        setPageName(getString(R.string.update_gitee_title))
        savedUri = savedInstanceState?.getString(STATE_URI)?.let(Uri::parse)
        savedTag = savedInstanceState?.getString(STATE_TAG)
        interrupted = savedInstanceState?.getBoolean(STATE_DOWNLOADING, false) == true
        loadRelease()
    }

    private fun bindViews() {
        status = findViewById(R.id.updateStatus)
        version = findViewById(R.id.updateVersion)
        releaseDetails = findViewById(R.id.updateReleaseDetails)
        notes = findViewById(R.id.updateNotes)
        fileStatus = findViewById(R.id.updateFileStatus)
        progressBar = findViewById(R.id.updateProgress)
        detailsCard = findViewById(R.id.updateDetailsCard)
        retryButton = findViewById(R.id.updateRetry)
        downloadButton = findViewById(R.id.updateDownload)
        downloadsButton = findViewById(R.id.updateOpenDownloads)
        findViewById<View>(R.id.top).setOnClickListener { finish() }
        retryButton.setOnClickListener { loadRelease() }
        downloadButton.setOnClickListener { downloadRelease() }
        downloadsButton.setOnClickListener { openDownloads() }
        findViewById<MaterialButton>(R.id.updateOpenGitee).setOnClickListener { openReleases() }
        findViewById<TextView>(R.id.updateInstalledVersion).text =
            getString(R.string.update_gitee_installed, BuildConfig.VERSION_NAME)
    }

    private fun loadRelease() {
        if (loading || downloading) return
        loading = true
        release = null
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
        fileStatus.text = when {
            savedUri != null -> getString(R.string.update_gitee_saved, latest.mainApk?.name.orEmpty())
            wasInterrupted -> getString(R.string.update_gitee_interrupted)
            latest.mainApk == null -> getString(R.string.update_gitee_no_apk)
            !isMainPackageBuild() -> getString(R.string.update_gitee_channel_build)
            else -> getString(R.string.update_gitee_target, latest.mainApk.name, describeSize(latest.mainApk.declaredSize))
        }
    }

    /** Gitee omits asset sizes, so a HEAD probe fills them in without downloading the APK. */
    private suspend fun showResolvedSize(latest: GiteeRelease) {
        val asset = latest.mainApk ?: return
        if (asset.declaredSize != null) return
        val resolved = api.probeAssetSize(asset) ?: return
        if (isDestroyed() || release?.tagName != latest.tagName) return
        if (downloading || savedUri != null) return
        val sized = asset.copy(declaredSize = resolved)
        release = latest.copy(mainApk = sized)
        showFileStatus(latest.copy(mainApk = sized), wasInterrupted = false)
    }

    /** Null comparison means the tag format is unknown: never claim "already up to date". */
    private fun comparisonStatus(latest: GiteeRelease): Int {
        val comparison = UpdateVersion.compare(latest.tagName, BuildConfig.VERSION_NAME)
            ?: return R.string.update_gitee_version_unknown
        return when {
            comparison > 0 -> R.string.update_gitee_new_version
            comparison == 0 -> R.string.update_gitee_current
            else -> R.string.update_gitee_local_newer
        }
    }

    private fun stageLabel(prerelease: Boolean): String =
        getString(if (prerelease) R.string.update_gitee_prerelease else R.string.update_gitee_release)

    /** Gitee returns `created_at` with an offset; show it as local time and never crash on odd input. */
    private fun formatPublishedAt(raw: String): String {
        if (raw.isBlank()) return getString(R.string.update_gitee_unknown_time)
        return try {
            val parsed = ISO_FORMATS.firstNotNullOfOrNull { format ->
                runCatching { format.parse(raw) }.getOrNull()
            } ?: return raw
            LOCAL_FORMAT.format(parsed)
        } catch (ignored: Exception) {
            raw
        }
    }

    private fun describeSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return getString(R.string.update_gitee_size_unknown)
        val mib = bytes / (1024.0 * 1024.0)
        return getString(R.string.update_gitee_size_mib, mib)
    }

    private fun downloadRelease() {
        val latest = release ?: return
        val asset = latest.mainApk ?: return
        if (loading || downloading || savedUri != null) return
        // Defence in depth: a channel build must never pull the main-package APK even by tap spam.
        if (!isMainPackageBuild()) {
            fileStatus.visibility = View.VISIBLE
            fileStatus.setText(R.string.update_gitee_channel_build)
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
                val uri = GiteeApkDownload(applicationContext, api).download(latest) { actual, total ->
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

    /** A channel build (e.g. qqmusic) shares this code but must not download the main APK. */
    private fun isMainPackageBuild(): Boolean = applicationContext.packageName == GiteeApkDownload.MAIN_PACKAGE

    private fun updateButtons() {
        val buttonReleased = !loading && !downloading
        retryButton.isEnabled = buttonReleased
        retryButton.setText(if (release == null) R.string.update_gitee_retry else R.string.update_gitee_refresh)
        downloadButton.isEnabled = buttonReleased && release?.mainApk != null &&
            savedUri == null && isMainPackageBuild()
        downloadButton.setText(if (downloading) R.string.update_gitee_downloading else R.string.update_gitee_download)
        downloadsButton.visibility = if (savedUri != null) View.VISIBLE else View.GONE
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
        private val ISO_FORMATS = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        )
        private val LOCAL_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
