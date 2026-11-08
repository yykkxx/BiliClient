package com.RobinNotBad.BiliClient.activity.update

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.api.GiteeReleaseApi
import com.RobinNotBad.BiliClient.model.update.GiteeRelease
import com.RobinNotBad.BiliClient.model.update.GiteeReleasePolicy
import com.RobinNotBad.BiliClient.model.update.UpdateVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext

/** Validate privately first; publish a pending MediaStore Downloads row only after complete IO. */
class GiteeApkDownload(context: Context, private val api: GiteeReleaseApi) {
    private val appContext = context.applicationContext

    suspend fun download(release: GiteeRelease, progress: suspend (Long, Long) -> Unit): Uri = withContext(Dispatchers.IO) {
        val asset = release.mainApk ?: throw IOException(appContext.getString(R.string.update_gitee_no_apk))
        if (!GiteeReleasePolicy.isSafeAsset(release.tagName, asset)) invalidApk()
        if ((asset.declaredSize ?: 0L) > MAX_APK_BYTES) invalidApk()
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val temporary = File.createTempFile("gitee-update-", ".part", appContext.cacheDir)
        try {
            api.openApk(asset).use { response ->
                if (response.code != 200) throw IOException("Gitee APK HTTP ${response.code}")
                val body = response.body ?: invalidApk()
                if (!GiteeReleasePolicy.isAllowedContentType(response.header("Content-Type"))) invalidApk()
                streamApk(body, temporary, coroutine, progress)
            }
            verifyArchive(temporary, release, coroutine)
            coroutine.ensureActive()
            publish(temporary, asset.name, coroutine)
        } finally {
            discardTemporary(temporary)
        }
    }

    private fun discardTemporary(temporary: File) {
        if (!temporary.delete() && temporary.exists()) Log.w(LOG_TAG, "Could not remove private update temporary file")
    }

    private suspend fun streamApk(
        body: ResponseBody,
        temporary: File,
        coroutine: CoroutineContext,
        progress: suspend (Long, Long) -> Unit
    ) {
        val expected = body.contentLength()
        if (expected == 0L || expected > MAX_APK_BYTES) invalidApk()
        var actual = 0L
        var lastProgress = 0L
        body.byteStream().use { input ->
            temporary.outputStream().use { output ->
                actual += copyZipHeader(input, output, coroutine)
                actual += copyPayload(input, output, coroutine, actual) { now, written ->
                    if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                        lastProgress = now
                        progress(written, expected)
                    }
                }
                output.flush()
            }
        }
        if (!GiteeReleasePolicy.isCompleteLength(actual, expected)) invalidApk()
        coroutine.ensureActive()
        progress(actual, expected)
    }

    /** The first four bytes must already look like a ZIP local file header; aborts on short bodies. */
    private fun copyZipHeader(input: InputStream, output: OutputStream, coroutine: CoroutineContext): Int {
        val header = ByteArray(4)
        var headerSize = 0
        while (headerSize < header.size) {
            coroutine.ensureActive()
            val count = input.read(header, headerSize, header.size - headerSize)
            if (count < 0) invalidApk()
            headerSize += count
        }
        if (!GiteeReleasePolicy.hasApkZipHeader(header)) invalidApk()
        output.write(header)
        return header.size
    }

    /** Streams the remaining bytes, enforcing the hard size cap and periodic progress callbacks. */
    private suspend fun copyPayload(
        input: InputStream,
        output: OutputStream,
        coroutine: CoroutineContext,
        alreadyWritten: Long,
        onTick: suspend (Long, Long) -> Unit
    ): Long {
        var written = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(buffer)
        while (count != -1) {
            coroutine.ensureActive()
            written += count
            if (alreadyWritten + written > MAX_APK_BYTES) invalidApk()
            output.write(buffer, 0, count)
            onTick(SystemClock.elapsedRealtime(), alreadyWritten + written)
            count = input.read(buffer)
        }
        return written
    }

    private fun verifyArchive(file: File, release: GiteeRelease, coroutine: CoroutineContext) {
        // A valid header alone is insufficient: truncated ZIPs and broken entry CRCs must fail.
        ZipFile(file).use { archive ->
            requireRequiredEntries(archive)
            verifyEntries(archive, coroutine)
        }
        verifyPackage(file, release)
    }

    private fun requireRequiredEntries(archive: ZipFile) {
        val manifest = archive.getEntry("AndroidManifest.xml") ?: invalidApk()
        val dex = archive.getEntry("classes.dex") ?: invalidApk()
        if (manifest.size <= 0 || dex.size <= 0) invalidApk()
    }

    private fun verifyEntries(archive: ZipFile, coroutine: CoroutineContext) {
        var expanded = 0L
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            coroutine.ensureActive()
            expanded = verifyEntry(archive, entries.nextElement(), expanded, coroutine)
        }
    }

    /** Decompresses one entry, enforcing both the expanded-size cap and the stored CRC. */
    private fun verifyEntry(archive: ZipFile, entry: ZipEntry, expandedSoFar: Long, coroutine: CoroutineContext): Long {
        if (entry.isDirectory) return expandedSoFar
        var expanded = expandedSoFar
        archive.getInputStream(entry).use { input ->
            val crc = CRC32()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count != -1) {
                coroutine.ensureActive()
                expanded += count
                if (expanded > MAX_EXPANDED_BYTES) invalidApk()
                crc.update(buffer, 0, count)
                count = input.read(buffer)
            }
            if (crc.value != entry.crc) invalidApk()
        }
        return expanded
    }

    private fun verifyPackage(file: File, release: GiteeRelease) {
        val manager = appContext.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: invalidApk()
        if (archive.packageName != MAIN_PACKAGE) invalidApk()
        if (UpdateVersion.compare(archive.versionName.orEmpty(), release.tagName) != 0) invalidApk()
        val installed = manager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val expectedSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val actualSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        // The updater must never offer a renamed channel APK or an incompatible signing key.
        if (expectedSigners.isEmpty() || actualSigners != expectedSigners) invalidApk()
    }

    private fun publish(file: File, name: String, coroutine: CoroutineContext): Uri {
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pendingValues(name))
            ?: throw IOException(appContext.getString(R.string.update_gitee_save_failed))
        var published = false
        try {
            writePendingRow(resolver, uri, file, coroutine)
            coroutine.ensureActive()
            confirmPublished(resolver, uri)
            published = true
            return uri
        } finally {
            if (!published) discardPendingRow(resolver, uri)
        }
    }

    private fun pendingValues(name: String) = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, GiteeReleasePolicy.APK_MIME_TYPE)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }

    private fun writePendingRow(resolver: ContentResolver, uri: Uri, file: File, coroutine: CoroutineContext) {
        file.inputStream().use { input ->
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException(appContext.getString(R.string.update_gitee_save_failed))
            output.use { copyToDownloads(input, it, coroutine) }
        }
    }

    /** IS_PENDING=0 is the atomic publish moment; a zero-row update must not look successful. */
    private fun confirmPublished(resolver: ContentResolver, uri: Uri) {
        val complete = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        if (resolver.update(uri, complete, null, null) != 1) {
            throw IOException(appContext.getString(R.string.update_gitee_save_failed))
        }
    }

    private fun discardPendingRow(resolver: ContentResolver, uri: Uri) {
        try {
            if (resolver.delete(uri, null, null) != 1) Log.w(LOG_TAG, "Pending APK cleanup returned no row")
        } catch (exception: Exception) {
            Log.w(LOG_TAG, "Could not remove pending APK after download failure", exception)
        }
    }

    private fun copyToDownloads(input: InputStream, output: OutputStream, coroutine: CoroutineContext) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(buffer)
        while (count != -1) {
            coroutine.ensureActive()
            output.write(buffer, 0, count)
            count = input.read(buffer)
        }
        output.flush()
    }

    private fun invalidApk(): Nothing = throw IOException(appContext.getString(R.string.update_gitee_invalid_apk))

    companion object {
        /** Only this package is ever updated; channel builds must never pull the main APK. */
        const val MAIN_PACKAGE = "com.RobinNotBad.BiliClient"
        private const val MAX_APK_BYTES = 256L * 1024L * 1024L
        private const val MAX_EXPANDED_BYTES = 512L * 1024L * 1024L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val LOG_TAG = "GiteeApkDownload"
    }
}
