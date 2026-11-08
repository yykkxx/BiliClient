package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.model.update.GiteeRelease
import com.RobinNotBad.BiliClient.model.update.GiteeReleaseAsset
import com.RobinNotBad.BiliClient.model.update.GiteeReleasePolicy
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.model.update.UpdateVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray // quality-allow: public Gitee boundary DTO, no JSON library dependency added.
import org.json.JSONObject // quality-allow: public Gitee boundary DTO, no JSON library dependency added.
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private, credential-free transport: never use NetWorkUtil's client or AppInfoApi headers here.
 * Only the shared Inet4Selector() DNS rule is reused: IPv6 lookups to Gitee fail on this device.
 */
class GiteeReleaseApi {
    private val closed = AtomicBoolean(false)

    /**
     * Every in-flight call, so cancel() reaches the metadata GET, the HEAD size probe and the APK
     * stream alike. Entries are few (one per request the page makes) and only added on demand.
     */
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(NetWorkUtil.Inet4Selector())
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * The list endpoint is used instead of `/releases/latest`: Gitee's `latest` only answers the
     * newest non-prerelease release, while this project publishes far more prereleases. The list is
     * requested newest-first (50 newest entries, newest first) and the highest tag is chosen by
     * [UpdateVersion.compare]; a same-version tie keeps the more recent entry.
     */
    suspend fun fetchLatest(): GiteeRelease = withContext(Dispatchers.IO) {
        execute(LIST_URL, "application/json").use { response ->
            if (response.code != 200) throw IOException("Gitee HTTP ${response.code}")
            val body = response.body ?: throw IOException("Gitee response is empty")
            // Read at most 2 MiB; don't feed an unbounded error page to the JSON parser.
            val source = body.source()
            if (source.request(MAX_METADATA_BYTES + 1)) throw IOException("Gitee release metadata is too large")
            parseLatest(source.readUtf8())
        }
    }

    /** Blocking IO only. Caller closes the returned body after streaming. */
    fun openApk(asset: GiteeReleaseAsset): Response {
        var url = asset.downloadUrl
        repeat(MAX_REDIRECTS + 1) { index ->
            val response = requestHop(url, index == MAX_REDIRECTS)
            if (response.code !in REDIRECT_CODES) return response
            url = response.use { nextHop(it) }
        }
        throw IOException("APK download did not return a response")
    }

    /**
     * Gitee's asset payload carries no size field, so a HEAD probe fills it in for display.
     * Best-effort only: any failure returns null and the UI keeps showing "size unknown".
     */
    suspend fun probeAssetSize(asset: GiteeReleaseAsset): Long? = withContext(Dispatchers.IO) {
        try {
            resolveSize(asset)
        } catch (ignored: IOException) {
            null
        }
    }

    private fun resolveSize(asset: GiteeReleaseAsset): Long? {
        var url = asset.downloadUrl
        repeat(MAX_REDIRECTS + 1) {
            val hop = sizeHop(url) ?: return null
            hop.length?.let { return it }
            url = hop.next ?: return null
        }
        return null
    }

    private fun sizeHop(url: String): HeadHop? {
        if (!GiteeReleasePolicy.isSafeDownloadHop(url)) return null
        execute(url, GiteeReleasePolicy.APK_MIME_TYPE, METHOD_HEAD).use { response ->
            if (response.code in REDIRECT_CODES) return HeadHop(null, nextHop(response))
            if (response.code != 200) return HeadHop(null, null)
            return HeadHop(response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }, null)
        }
    }

    fun cancel() {
        closed.set(true)
        activeCalls.forEach { it.cancel() }
    }

    /** One safe hop; rejects the destination before any request leaves the device. */
    private fun requestHop(url: String, lastAllowed: Boolean): Response {
        if (!GiteeReleasePolicy.isSafeDownloadHop(url)) throw IOException("Unsafe APK redirect")
        val response = execute(url, GiteeReleasePolicy.APK_MIME_TYPE)
        if (response.code in REDIRECT_CODES && lastAllowed) {
            response.close()
            throw IOException("Too many APK redirects")
        }
        return response
    }

    private fun nextHop(response: Response): String {
        val location = response.header("Location") ?: throw IOException("Missing APK redirect location")
        return response.request.url.resolve(location)?.toString()
            ?: throw IOException("Invalid APK redirect location")
    }

    private fun execute(url: String, accept: String, method: String = METHOD_GET): Response {
        if (closed.get()) throw IOException("Update request cancelled")
        val request = Request.Builder().url(url).method(method, null)
            .header("Accept", accept)
            .header("User-Agent", "BiliClient-Gitee-Updater")
            .header("Accept-Encoding", "identity")
            .build()
        val call = client.newCall(request)
        activeCalls.add(call)
        // Close the race where onDestroy happened before the call was registered.
        if (closed.get()) call.cancel()
        val response = call.execute()
        val body = response.body
        if (body == null) {
            activeCalls.remove(call)
            return response
        }
        return response.newBuilder().body(ReleasingBody(body) { activeCalls.remove(call) }).build()
    }

    private fun parseLatest(json: String): GiteeRelease {
        val items = JSONArray(json) // quality-allow: untrusted public release array mapped to typed DTOs.
        val releases = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let(::parseRelease)
        }
        if (releases.isEmpty()) throw IOException("Gitee returned no releases")
        return highest(releases)
    }

    private fun parseRelease(item: JSONObject): GiteeRelease {
        val tag = item.optString("tag_name")
        if (tag.isBlank()) throw IOException("Gitee release without tag_name")
        return GiteeRelease(
            tagName = tag,
            title = item.optString("name").ifBlank { tag },
            notes = item.optString("body"),
            // Gitee's payload has created_at only; published_at is accepted if a future API adds it.
            publishedAt = item.optString("published_at").ifBlank { item.optString("created_at") },
            prerelease = item.optBoolean("prerelease", false),
            mainApk = GiteeReleasePolicy.selectMainApk(tag, candidateAssets(item))
        )
    }

    private fun candidateAssets(item: JSONObject): List<GiteeReleaseAsset> {
        val assets = item.optJSONArray("assets") ?: return emptyList()
        return (0 until assets.length()).mapNotNull { index ->
            val asset = assets.optJSONObject(index) ?: return@mapNotNull null
            val size = asset.optLong("size", -1L)
            GiteeReleaseAsset(
                name = asset.optString("name"),
                downloadUrl = asset.optString("browser_download_url"),
                declaredSize = size.takeIf { it > 0 }
            )
        }
    }

    /** Highest parseable tag wins; a pre-release tag stays visible instead of being hidden. */
    private fun highest(releases: List<GiteeRelease>): GiteeRelease {
        val versioned = releases.filter { UpdateVersion.releaseVersion(it.tagName) != null }
        if (versioned.isEmpty()) return releases.first()
        return versioned.reduce { best, candidate ->
            // Strictly greater only: the list is newest-first, so an equal version must keep the
            // entry that was published (and therefore listed) first.
            if ((UpdateVersion.compare(candidate.tagName, best.tagName) ?: 0) > 0) candidate else best
        }
    }

    private data class HeadHop(val length: Long?, val next: String?)

    /** Frees the call slot as soon as the caller closes the body, including error/timeout paths. */
    private class ReleasingBody(
        private val delegate: ResponseBody,
        private val onClose: () -> Unit
    ) : ResponseBody() {
        private var released = false

        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun close() {
                super.close()
                if (!released) {
                    released = true
                    onClose()
                }
            }
        }.buffer()
    }

    companion object {
        private const val METHOD_GET = "GET"
        private const val METHOD_HEAD = "HEAD"
        private const val LIST_URL =
            "https://gitee.com/api/v5/repos/yykkxxykx/BiliClient/releases?per_page=50&page=1&direction=desc"
        private const val MAX_METADATA_BYTES = 2L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
