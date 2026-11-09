import com.RobinNotBad.BiliClient.api.GiteeReleaseApi
import com.RobinNotBad.BiliClient.model.update.GiteeReleasePolicy
import kotlinx.coroutines.runBlocking

/** Optional public read-only smoke probe. Never accepts URLs, secrets, account/device fields or tokens. */
fun main() = runBlocking {
    val api = GiteeReleaseApi()
    try {
        val latest = api.fetchLatest()
        println("PUBLIC_LATEST parsed tag=${latest.tagName} prerelease=${latest.prerelease} created=${latest.publishedAt}")
        println("PUBLIC_LATEST title=${latest.title.take(60)} notesChars=${latest.notes.length}")
        println("PUBLIC_CANDIDATES count=${latest.apkAssets.size} names=${latest.apkAssets.map { it.name }}")
        // Each install flavour must resolve to exactly its own attachment, from the same parsed release.
        GiteeReleasePolicy.UpdateTarget.values().forEach { target ->
            val picked = latest.apkFor(target)?.name ?: "NONE"
            println("PUBLIC_TARGET ${target.name} -> $picked")
        }
        val mainTargetCount = GiteeReleasePolicy.UpdateTarget.values().count { latest.apkFor(it) != null }
        println("PUBLIC_TARGETS resolved=$mainTargetCount/4 (expect 3: main + visible + hidden)")
        val asset = latest.mainApk
        if (asset == null) {
            println("PUBLIC_APK NOT_TESTED: metadata contained no uniquely safe exact main APK")
            return@runBlocking
        }
        println("PUBLIC_ASSET ${asset.name} declaredSize=${asset.declaredSize}")
        // The list endpoint carries no size field; the HEAD probe must resolve it without downloading.
        val probed = api.probeAssetSize(asset)
        println("PUBLIC_HEAD_PROBE resolvedBytes=$probed")
        api.openApk(asset).use { response ->
            // Do not print a possibly signed CDN query. No APK is saved or installed.
            println("PUBLIC_APK status=${response.code} finalHost=${response.request.url.host} declaredBytes=${response.body?.contentLength()}")
            check(response.code == 200) { "Public APK did not return HTTP 200" }
            check(GiteeReleasePolicy.isAllowedContentType(response.header("Content-Type"))) { "Unexpected public APK MIME" }
            val source = response.body?.source() ?: error("Public APK response has no body")
            check(source.request(4)) { "Public APK response is shorter than four bytes" }
            check(GiteeReleasePolicy.hasApkZipHeader(source.readByteArray(4))) { "Public APK response is not a ZIP header" }
            println("PUBLIC_APK PASS: actual production API followed safe redirects and first four bytes are PK0304")
            println("PUBLIC_APK LIMITATION: first-header smoke only; full length, ZIP entries, certificate declaration and install not tested by this probe")
        }
        if (probed == null) {
            println("PUBLIC_HEAD_PROBE LIMITATION: probe returned null; size stays unknown in the UI")
        }
    } catch (error: Exception) {
        // Bound error text; no request headers, URL queries or environment dumps are logged.
        println("PUBLIC_PROBE FAILED_OR_UNAVAILABLE ${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}")
        throw error
    } finally {
        api.cancel()
    }
}
