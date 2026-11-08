package com.RobinNotBad.BiliClient.model.update

import java.net.URI

/** Fail closed: never guess from the first APK, extension, channel name or source archive. */
object GiteeReleasePolicy {
    const val RELEASES_URL = "https://gitee.com/yykkxxykx/BiliClient/releases"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val GITEE_HOST = "gitee.com"
    private const val CDN_HOST = "foruda.gitee.com"
    private const val DOWNLOAD_PATH = "/yykkxxykx/BiliClient/releases/download/"
    private val ATTACH_DOWNLOAD_PATH = Regex("^/yykkxxykx/BiliClient/attach_files/[0-9]+/download/[^/]+\\.apk$")
    private val safeFileName = Regex("^[A-Za-z0-9][A-Za-z0-9.+-]{0,159}\\.apk$")
    private val encodedDot = Regex("%2e", RegexOption.IGNORE_CASE)

    fun selectMainApk(tag: String, assets: List<GiteeReleaseAsset>): GiteeReleaseAsset? {
        val version = UpdateVersion.releaseVersion(tag) ?: return null
        val expectedName = "BiliClient-$version-signed.apk"
        val matches = assets.filter { it.name == expectedName }
        // Duplicated attachment aliases can point to stale files: don't silently choose one.
        val candidate = matches.singleOrNull() ?: return null
        return candidate.takeIf { isSafeAsset(tag, it) }
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
}
