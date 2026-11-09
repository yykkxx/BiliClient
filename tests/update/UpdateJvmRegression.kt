import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.util.Log
import com.RobinNotBad.BiliClient.activity.update.GiteeApkDownload
import com.RobinNotBad.BiliClient.api.GiteeReleaseApi
import com.RobinNotBad.BiliClient.model.update.GiteeRelease
import com.RobinNotBad.BiliClient.model.update.GiteeReleaseAsset
import com.RobinNotBad.BiliClient.model.update.GiteeReleasePolicy as Policy
import com.RobinNotBad.BiliClient.model.update.UpdateVersion as Version
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray // quality-allow: test fixture builds raw Gitee JSON payloads; production parsing is asserted, not re-implemented.
import org.json.JSONObject // quality-allow: test fixture builds raw Gitee JSON payloads; production parsing is asserted, not re-implemented.
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private var passed = 0
private val failures = mutableListOf<String>()
private fun test(name: String, body: () -> Unit) {
    try { body(); passed++; println("PASS $name") }
    catch (error: Throwable) { failures += "$name: ${error.javaClass.simpleName} ${error.message}"; println("FAIL ${failures.last()}") }
}
private fun rejects(body: () -> Unit) {
    var thrown = false
    try { body() } catch (expected: Exception) { thrown = true }
    check(thrown) { "Expected rejection, but operation succeeded" }
}
private val name = "BiliClient-5.3.2-signed.apk"
private val startUrl = "https://gitee.com/yykkxxykx/BiliClient/releases/download/v5.3.2/$name"
private val asset = GiteeReleaseAsset(name, startUrl)
private val release = GiteeRelease("v5.3.2", "test", "notes", "2026-11-08T00:00:00+08:00", false, listOf(asset))

// Channel builds are produced by changed/build_flavor.sh under these two names; the hidden variant
// only differs by `_hidden` and by having no launcher entry.
private val channelName = "com.tencent.qqmusicwatchs_20260606.apk"
private val channelHiddenName = "com.tencent.qqmusicwatchs_20260606_hidden.apk"
private fun channelAsset(assetName: String) =
    GiteeReleaseAsset(assetName, "https://gitee.com/yykkxxykx/BiliClient/releases/download/v5.3.2/$assetName")
private val channelVisibleAsset = channelAsset(channelName)
private val channelHiddenAsset = channelAsset(channelHiddenName)

private class FixtureBody(bytes: ByteArray, private val declaredLength: Long = bytes.size.toLong()) : ResponseBody() {
    private val buffer = Buffer().write(bytes)
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = declaredLength
    override fun source(): BufferedSource = buffer
}
private fun response(request: Request, code: Int = 200, bytes: ByteArray = byteArrayOf(0x50, 0x4b, 3, 4),
                     mime: String? = Policy.APK_MIME_TYPE, length: Long = bytes.size.toLong(), location: String? = null): Response {
    val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(FixtureBody(bytes, length))
    if (mime != null) builder.header("Content-Type", mime)
    if (location != null) builder.header("Location", location)
    return builder.build()
}
private fun fixtureApi(handler: (Interceptor.Chain) -> Response): GiteeReleaseApi {
    val api = GiteeReleaseApi()
    val field = GiteeReleaseApi::class.java.getDeclaredField("client").apply { isAccessible = true }
    val original = field.get(api) as OkHttpClient
    // First interceptor always returns or throws. No fixture can reach DNS/socket/network.
    field.set(api, original.newBuilder().addInterceptor(Interceptor(handler)).build())
    return api
}
private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { archive ->
        entries.forEach { (entryName, bytes) ->
            val entry = ZipEntry(entryName).apply {
                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            }
            archive.putNextEntry(entry); archive.write(bytes); archive.closeEntry()
        }
    }
    return output.toByteArray()
}
private val manifest = "fixture-manifest-not-a-real-apk".toByteArray()
private val dex = "fixture-dex-not-a-real-apk".toByteArray()
private val goodZip = zip(listOf("AndroidManifest.xml" to manifest, "classes.dex" to dex))

fun main(args: Array<String>) {
    val root = File(args[0])
    val out = File(args[1])
    versionTests()
    policyTests()
    installNameTests()
    apiTests()
    downloadTests(out)
    sourceContracts(root)
    println("SUMMARY passed=$passed failed=${failures.size}")
    println("AUTHORITY actual production Kotlin+OkHttp/JSON/coroutines; Android package metadata/resolver are explicit stubs")
    if (failures.isNotEmpty()) error(failures.joinToString("\n"))
}

private fun versionTests() {
    listOf(
        Triple("v5.3.2", "5.3.1", 1), Triple("5.3.2", "v5.3.2", 0),
        Triple("5.3.1", "5.3.2", -1), Triple("5.3.2", "5.3.2-rc1", 1),
        Triple("5.3.2-alpha10", "5.3.2-alpha9", 1), Triple("5.3.2-beta1", "5.3.2-alpha99", 1),
        Triple("5.3.2-rc1", "5.3.2-beta99", 1), Triple("5.3.2-alpha", "5.3.2-alpha1", -1),
        Triple("5.3.2-alpha.2", "5.3.2-alpha.10", -1), Triple("5.3.2-alpha1", "5.3.2-alpha.1", 0),
        Triple("5.3.2+build1", "5.3.2+build2", 0), Triple(" V005.003.002 ", "5.3.2", 0),
        Triple("999999999999999999999999.0.0", "999999999999999999999998.9.9", 1),
        Triple("5.3.2-alpha99999999999999999999", "5.3.2-alpha99999999999999999998", 1)
    ).forEach { (remote, local, expected) ->
        test("version $remote vs $local") {
            val value = Version.compare(remote, local)!! // quality-allow: fixture asserts a known-parseable version pair.

            check(value.compareTo(0) == expected)
            check(Version.compare(local, remote)!!.compareTo(0) == -expected) // quality-allow: same fixture pair, asserted above.

        }
    }
    listOf("", "5.3", "not-a-version", "5.3.2/../../upstream", "5.3.2-", "5.3.2\n5.3.3").forEach { invalid ->
        test("unknown version ${invalid.replace('\n', '_')}") { check(Version.compare(invalid, "5.3.2") == null); check(Version.releaseVersion(invalid) == null) }
    }
    test("same-day date versionCode does not mask versionName") {
        val localCode = 20261108; val remoteCode = 20261108
        check(localCode == remoteCode && Version.compare("5.3.2", "5.3.1")!! > 0) // quality-allow: fixed parseable literal pair.

    }
}

private fun policyTests() {
    test("exact main asset selected despite QQ/source ordering") {
        val alternatives = listOf(
            GiteeReleaseAsset("BiliClient-5.3.2-qqmusic-signed.apk", startUrl),
            GiteeReleaseAsset("BiliClient-5.3.2-qqmusic-hidden-signed.apk", startUrl),
            GiteeReleaseAsset("Source code.zip", startUrl), GiteeReleaseAsset("app-release.apk", startUrl), asset)
        check(Policy.selectMainApk("v5.3.2", alternatives) == asset)
    }
    test("missing main asset cannot guess first APK") { check(Policy.selectMainApk("v5.3.2", listOf(asset.copy(name = "qqmusic.apk"))) == null) }
    test("duplicate main aliases rejected") { check(Policy.selectMainApk("v5.3.2", listOf(asset, asset)) == null) }
    test("wrong tag rejected") { check(Policy.selectMainApk("v5.3.1", listOf(asset)) == null) }
    test("hyphen prerelease exact name") {
        val prerelease = GiteeReleaseAsset("BiliClient-5.3.2-alpha1-signed.apk", "https://gitee.com/yykkxxykx/BiliClient/releases/download/v5.3.2-alpha1/BiliClient-5.3.2-alpha1-signed.apk")
        check(Policy.selectMainApk("v5.3.2-alpha1", listOf(prerelease)) == prerelease)
    }
    listOf(
        startUrl.replace("https:", "http:"), startUrl.replace("gitee.com", "gitee.com.evil.invalid"),
        startUrl.replace("gitee.com", "user@gitee.com"), startUrl.replace("gitee.com", "gitee.com:444"),
        startUrl.replace("yykkxxykx", "RobinNotBad"), startUrl.replace("BiliClient/releases", "Other/releases"),
        "$startUrl?token=fake", "$startUrl#fragment", "not a URI", "file:///tmp/$name",
        startUrl.replace("v5.3.2", "v5.3.1"), startUrl.replace(name, "qqmusic.apk")
    ).forEachIndexed { index, url -> test("unsafe initial URL $index") { check(Policy.selectMainApk("v5.3.2", listOf(asset.copy(downloadUrl = url))) == null) } }
    listOf(startUrl, "https://gitee.com/yykkxxykx/BiliClient/attach_files/123/download/$name",
        "https://foruda.gitee.com/attach_file/2026/123/file.apk?download=1",
        "https://foruda.gitee.com/attach_files/123/file.apk").forEachIndexed { index, url -> test("allowed HTTPS hop $index") { check(Policy.isSafeDownloadHop(url)) } }
    listOf("http://foruda.gitee.com/attach_file/1/a.apk", "https://user@foruda.gitee.com/attach_file/1/a.apk",
        "https://foruda.gitee.com:444/attach_file/1/a.apk", "https://foruda.gitee.com.evil.invalid/attach_file/a.apk",
        "https://gitee.com/RobinNotBad/BiliClient/releases/download/v5.3.2/$name", "https://foruda.gitee.com/login",
        "https://gitee.com/yykkxxykx/Other/attach_files/1/download/a.apk", "ftp://gitee.com/a", ":::").forEachIndexed { index, url -> test("unsafe hop $index") { check(!Policy.isSafeDownloadHop(url)) } }
    // Must check the URL shape that OkHttp actually sends, not only URI's unnormalized path prefix.
    listOf("../outside.apk", "%2e%2e/outside.apk", "../../../../../../RobinNotBad/BiliClient/releases/download/v5.3.2/$name").forEachIndexed { index, suffix ->
        val malicious = "https://gitee.com/yykkxxykx/BiliClient/releases/download/$suffix"
        test("dot-segment hop cannot escape exact fork $index") { check(!Policy.isSafeDownloadHop(malicious)) { "Accepted unnormalized URL; OkHttp sends ${malicious.toHttpUrl().encodedPath}" } }
    }
    listOf(null, Policy.APK_MIME_TYPE, "application/octet-stream", "application/zip", "APPLICATION/ZIP; charset=binary").forEachIndexed { index, mime -> test("APK MIME $index") { check(Policy.isAllowedContentType(mime)) } }
    listOf("text/html", "text/html; charset=UTF-8", "application/json", "image/png", "").forEachIndexed { index, mime -> test("reject error MIME $index") { check(!Policy.isAllowedContentType(mime)) } }
    test("ZIP PK0304 header only") {
        check(Policy.hasApkZipHeader(byteArrayOf(0x50, 0x4b, 3, 4)))
        listOf(byteArrayOf(), byteArrayOf(0x50, 0x4b, 3), "<htm".toByteArray(), byteArrayOf(0x50, 0x4b, 5, 6)).forEach { check(!Policy.hasApkZipHeader(it)) }
    }
    test("length positive complete and chunked only") {
        check(Policy.isCompleteLength(123, 123)); check(Policy.isCompleteLength(123, -1))
        check(!Policy.isCompleteLength(0, -1)); check(!Policy.isCompleteLength(123, 124)); check(!Policy.isCompleteLength(124, 123))
    }
    targetTests()
    freshnessTests()
}

private fun targetTests() {
    test("main package resolves to MAIN regardless of launcher entry") {
        check(Policy.resolveTarget(Policy.MAIN_PACKAGE, hasLauncherEntry = true) == Policy.UpdateTarget.MAIN)
        check(Policy.resolveTarget(Policy.MAIN_PACKAGE, hasLauncherEntry = false) == Policy.UpdateTarget.MAIN)
    }
    test("channel package with launcher entry is the visible build") {
        check(Policy.resolveTarget(Policy.CHANNEL_PACKAGE, true) == Policy.UpdateTarget.CHANNEL_VISIBLE)
    }
    test("channel package without launcher entry is the hidden build") {
        check(Policy.resolveTarget(Policy.CHANNEL_PACKAGE, false) == Policy.UpdateTarget.CHANNEL_HIDDEN)
    }
    test("unknown package name downloads nothing") {
        listOf("com.example.fork", "", "com.tencent.qqmusicwatchs.evil").forEach {
            check(Policy.resolveTarget(it, hasLauncherEntry = true) == Policy.UpdateTarget.UNSUPPORTED)
            check(Policy.selectApk(Policy.UpdateTarget.UNSUPPORTED, "v5.3.2", listOf(asset)) == null)
        }
    }
    test("three assets resolve to three distinct targets") {
        val all = listOf(asset, channelVisibleAsset, channelHiddenAsset, GiteeReleaseAsset("SHA256SUMS.txt", startUrl))
        check(Policy.selectApk(Policy.UpdateTarget.MAIN, "v5.3.2", all) == asset)
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_VISIBLE, "v5.3.2", all) == channelVisibleAsset)
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_HIDDEN, "v5.3.2", all) == channelHiddenAsset)
    }
    test("main build never selects a channel APK and vice versa") {
        check(Policy.selectApk(Policy.UpdateTarget.MAIN, "v5.3.2", listOf(channelVisibleAsset, channelHiddenAsset)) == null)
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_VISIBLE, "v5.3.2", listOf(asset)) == null)
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_HIDDEN, "v5.3.2", listOf(asset)) == null)
    }
    test("visible and hidden channel builds never swap assets") {
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_VISIBLE, "v5.3.2", listOf(channelHiddenAsset)) == null)
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_HIDDEN, "v5.3.2", listOf(channelVisibleAsset)) == null)
    }
    test("SHA256SUMS and non-APK attachments are never candidates") {
        val all = listOf(GiteeReleaseAsset("SHA256SUMS.txt", startUrl), GiteeReleaseAsset("Source code.zip", startUrl), asset)
        check(Policy.candidateApks("v5.3.2", all) == listOf(asset))
    }
    test("candidate APKs keep all three flavours so each build can pick its own") {
        val all = listOf(asset, channelVisibleAsset, channelHiddenAsset)
        check(Policy.candidateApks("v5.3.2", all) == all)
    }
    test("wrong-tag download URL drops the asset even if the name matches") {
        val stale = channelVisibleAsset.copy(
            downloadUrl = "https://gitee.com/yykkxxykx/BiliClient/releases/download/v5.3.1/$channelName"
        )
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_VISIBLE, "v5.3.2", listOf(stale)) == null)
    }
    test("duplicate channel aliases rejected instead of silently picking one") {
        check(Policy.selectApk(Policy.UpdateTarget.CHANNEL_VISIBLE, "v5.3.2", listOf(channelVisibleAsset, channelVisibleAsset)) == null)
    }
    test("assetMatchesTarget cross-checks the file name shapes") {
        check(Policy.assetMatchesTarget(Policy.UpdateTarget.MAIN, name))
        check(!Policy.assetMatchesTarget(Policy.UpdateTarget.MAIN, channelName))
        check(Policy.assetMatchesTarget(Policy.UpdateTarget.CHANNEL_VISIBLE, channelName))
        check(!Policy.assetMatchesTarget(Policy.UpdateTarget.CHANNEL_VISIBLE, channelHiddenName))
        check(Policy.assetMatchesTarget(Policy.UpdateTarget.CHANNEL_HIDDEN, channelHiddenName))
        check(!Policy.assetMatchesTarget(Policy.UpdateTarget.CHANNEL_HIDDEN, channelName))
        check(!Policy.assetMatchesTarget(Policy.UpdateTarget.UNSUPPORTED, name))
    }
    test("channel asset names cannot smuggle a path or a second extension") {
        listOf(
            "com.tencent.qqmusicwatchs_20260606.apk/../x.apk",
            "../com.tencent.qqmusicwatchs_20260606.apk",
            "com.tencent.qqmusicwatchs_20260606.apk.exe",
            "com.tencent.qqmusicwatchs.apk"
        ).forEach { hostile -> check(!Policy.assetMatchesTarget(Policy.UpdateTarget.CHANNEL_VISIBLE, hostile)) }
    }
}

private fun installNameTests() {
    test("install file name keeps the asset name") {
        check(Policy.installFileName(name) == name)
        check(Policy.installFileName(channelHiddenName) == channelHiddenName)
    }
    test("install file name strips path components") {
        check(Policy.installFileName("../../etc/passwd") == "passwd.apk")
        check(Policy.installFileName("dir/sub/update.apk") == "update.apk")
        check(Policy.installFileName("dir\\sub\\update.apk") == "update.apk")
    }
    test("install file name removes unsafe characters and dot-only names") {
        check(Policy.installFileName("..") == "update.apk")
        check(Policy.installFileName("") == "update.apk")
        check(Policy.installFileName("a b|c.apk") == "abc.apk")
    }
    test("install file name always ends with a single .apk") {
        check(Policy.installFileName("update") == "update.apk")
        check(Policy.installFileName("update.apk.exe").endsWith(".apk"))
        check(!Policy.installFileName("update.apk").endsWith(".apk.apk"))
    }
}

private fun freshnessTests() {
    val hour = 60L * 60L * 1000L
    val releaseAt = 1_800_000_000_000L
    test("channel installed long before the release is likely outdated") {
        check(Policy.channelFreshness(releaseAt - 30 * hour, releaseAt) == Policy.ChannelFreshness.LIKELY_NEWER)
    }
    test("channel installed right after the release is likely current") {
        check(Policy.channelFreshness(releaseAt + 2 * hour, releaseAt) == Policy.ChannelFreshness.LIKELY_CURRENT)
    }
    test("clock skew within one hour must not claim an update") {
        check(Policy.channelFreshness(releaseAt - hour / 2, releaseAt) == Policy.ChannelFreshness.LIKELY_CURRENT)
        check(Policy.channelFreshness(releaseAt - 2 * hour, releaseAt) == Policy.ChannelFreshness.LIKELY_NEWER)
    }
    test("unknown install or release time never claims an update") {
        check(Policy.channelFreshness(0L, releaseAt) == Policy.ChannelFreshness.UNKNOWN)
        check(Policy.channelFreshness(releaseAt - 30 * hour, null) == Policy.ChannelFreshness.UNKNOWN)
        check(Policy.channelFreshness(releaseAt - 30 * hour, 0L) == Policy.ChannelFreshness.UNKNOWN)
    }
}

private fun apiTests() {
    fun releaseJson(tag: String, prerelease: Boolean = false, extra: JSONArray? = null) = JSONObject()
        .put("tag_name", tag).put("name", "").put("body", "release notes")
        .put("created_at", "2026-11-08T00:00:00+08:00").put("prerelease", prerelease)
        .put("author", JSONObject().put("irrelevant", "never-retained"))
        .put("assets", extra ?: JSONArray().put(7)
            .put(JSONObject().put("name", "qqmusic.apk").put("browser_download_url", startUrl))
            .put(JSONObject().put("name", name).put("browser_download_url", startUrl)))
    test("real parseLatest picks highest tag from list and drops public author fields") {
        val json = JSONArray().put(releaseJson("v5.3.1"))
            .put(releaseJson("v5.3.2")).put(releaseJson("v5.3.0"))
        val api = fixtureApi { chain ->
            val request = chain.request()
            check(request.url.toString() == "https://gitee.com/api/v5/repos/yykkxxykx/BiliClient/releases?per_page=50&page=1&direction=desc")
            check(request.header("Authorization") == null && request.header("Cookie") == null)
            check(request.header("User-Agent") == "BiliClient-Gitee-Updater")
            check(request.header("Accept-Encoding") == "identity")
            response(request, bytes = json.toString().toByteArray(), mime = "application/json")
        }
        try { runBlocking { val result = api.fetchLatest(); check(result.mainApk != null); check(result.title == "v5.3.2"); check(result.notes == "release notes"); check(!result.prerelease) } }
        finally { api.cancel() }
    }
    test("newest prerelease is visible instead of the latest stable release") {
        val alpha = GiteeReleaseAsset("BiliClient-5.3.2-alpha1-signed.apk",
            "https://gitee.com/yykkxxykx/BiliClient/releases/download/v5.3.2-alpha1/BiliClient-5.3.2-alpha1-signed.apk")
        val json = JSONArray().put(releaseJson("v5.3.1"))
            .put(releaseJson("v5.3.2-alpha1", prerelease = true,
                extra = JSONArray().put(JSONObject().put("name", alpha.name).put("browser_download_url", alpha.downloadUrl))))
        val api = fixtureApi { response(it.request(), bytes = json.toString().toByteArray(), mime = "application/json") }
        try { runBlocking { val result = api.fetchLatest(); check(result.tagName == "v5.3.2-alpha1"); check(result.prerelease); check(result.mainApk == alpha) } }
        finally { api.cancel() }
    }
    test("local newer than every published release stays local-newer") {
        val json = JSONArray().put(releaseJson("v5.3.1"))
        val api = fixtureApi { response(it.request(), bytes = json.toString().toByteArray(), mime = "application/json") }
        try { runBlocking { check(Version.compare(api.fetchLatest().tagName, "5.3.2")!! < 0) } } finally { api.cancel() } // quality-allow: fixture tag is a fixed parseable literal.

    }
    test("parse missing assets stays no-main-APK") {
        val json = JSONArray().put(JSONObject().put("tag_name", "v5.3.2"))
        val api = fixtureApi { response(it.request(), bytes = json.toString().toByteArray(), mime = "application/json") }
        try { runBlocking { check(api.fetchLatest().mainApk == null) } } finally { api.cancel() }
    }
    test("equal-version tie keeps the newest listed entry") {
        val newer = releaseJson("v5.3.2").put("name", "newest")
        val older = releaseJson("v5.3.2").put("name", "older-duplicate")
        val json = JSONArray().put(newer).put(older)
        val api = fixtureApi { response(it.request(), bytes = json.toString().toByteArray(), mime = "application/json") }
        try { runBlocking { check(api.fetchLatest().title == "newest") } } finally { api.cancel() }
    }
    test("empty release list rejected") {
        val api = fixtureApi { response(it.request(), bytes = "[]".toByteArray(), mime = "application/json") }
        try { rejects { runBlocking { api.fetchLatest() } } } finally { api.cancel() }
    }
    listOf(404, 500).forEach { status -> test("metadata HTTP $status rejected") {
        val api = fixtureApi { response(it.request(), code = status) }
        try { rejects { runBlocking { api.fetchLatest() } } } finally { api.cancel() }
    } }
    test("metadata HTML200 rejected by actual JSON parser") {
        val api = fixtureApi { response(it.request(), bytes = "<html>gateway</html>".toByteArray()) }
        try { rejects { runBlocking { api.fetchLatest() } } } finally { api.cancel() }
    }
    test("metadata greater than 2MiB rejected") {
        val api = fixtureApi { response(it.request(), bytes = ByteArray(2 * 1024 * 1024 + 1) { 32 }) }
        try { rejects { runBlocking { api.fetchLatest() } } } finally { api.cancel() }
    }
    listOf(301, 302, 303, 307, 308).forEach { code -> test("manual $code redirect to allowlisted CDN") {
        var calls = 0
        val api = fixtureApi { chain ->
            calls++
            check(chain.request().header("Cookie") == null && chain.request().header("Authorization") == null)
            if (calls == 1) response(chain.request(), code = code, location = "https://foruda.gitee.com/attach_file/test/file.apk?download=1")
            else { check(chain.request().url.host == "foruda.gitee.com"); response(chain.request()) }
        }
        try { api.openApk(asset).use { check(it.code == 200 && calls == 2) } } finally { api.cancel() }
    } }
    test("relative attach redirect accepted") {
        var calls = 0
        val api = fixtureApi { chain ->
            calls++
            if (calls == 1) response(chain.request(), code = 302, location = "/yykkxxykx/BiliClient/attach_files/123/download/$name")
            else response(chain.request())
        }
        try { api.openApk(asset).use { check(calls == 2) } } finally { api.cancel() }
    }
    listOf("http://foruda.gitee.com/attach_file/test/file.apk", "https://evil.invalid/file.apk",
        "https://gitee.com/RobinNotBad/BiliClient/releases/download/v5.3.2/$name").forEachIndexed { index, target -> test("API blocks unsafe redirect before request $index") {
        var calls = 0
        val api = fixtureApi { chain -> calls++; response(chain.request(), code = 302, location = target) }
        try { rejects { api.openApk(asset).close() }; check(calls == 1) } finally { api.cancel() }
    } }
    test("redirect loop bounded to six responses") {
        var calls = 0
        val api = fixtureApi { chain -> calls++; response(chain.request(), code = 302, location = startUrl) }
        try { rejects { api.openApk(asset).close() }; check(calls == 6) } finally { api.cancel() }
    }
    test("redirect missing location rejected") {
        val api = fixtureApi { response(it.request(), code = 302) }
        try { rejects { api.openApk(asset).close() } } finally { api.cancel() }
    }
    test("HEAD size probe reads Content-Length without downloading bytes") {
        var calls = 0
        val api = fixtureApi { chain ->
            calls++
            check(chain.request().method == "HEAD") { "Size probe must be a HEAD request" }
            if (calls == 1) response(chain.request(), code = 302, location = "https://foruda.gitee.com/attach_file/test/file.apk?download=1")
            else { val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").header("Content-Length", "12421928")
                .body(FixtureBody(byteArrayOf())).build(); builder }
        }
        try { runBlocking { check(api.probeAssetSize(asset) == 12421928L); check(calls == 2) } } finally { api.cancel() }
    }
    test("HEAD size probe failure degrades to unknown size") {
        val api = fixtureApi { response(it.request(), code = 404) }
        try { runBlocking { check(api.probeAssetSize(asset) == null) } } finally { api.cancel() }
    }
    test("HEAD size probe never leaves the fork allowlist") {
        var calls = 0
        val api = fixtureApi { calls++; response(it.request()) }
        val unsafe = asset.copy(downloadUrl = "https://evil.invalid/$name")
        try { runBlocking { check(api.probeAssetSize(unsafe) == null) }; check(calls == 0) } finally { api.cancel() }
    }
    test("cancel before execute sends no request") {
        var calls = 0
        val api = fixtureApi { calls++; response(it.request()) }; api.cancel()
        rejects { api.openApk(asset).close() }; check(calls == 0)
    }
    test("API initial dot traversal never sends an upstream request") {
        var calls = 0
        val malicious = asset.copy(downloadUrl = "https://gitee.com/yykkxxykx/BiliClient/releases/download/../../../../RobinNotBad/BiliClient/releases/download/v5.3.2/$name")
        val api = fixtureApi { chain -> calls++; response(chain.request()) }
        try { rejects { api.openApk(malicious).close() }; check(calls == 0) } finally { api.cancel() }
    }
}

private fun downloadTests(out: File) {
    fun downloadCase(label: String, bytes: ByteArray = goodZip, mime: String? = Policy.APK_MIME_TYPE,
                     length: Long = bytes.size.toLong(), status: Int = 200, expectSuccess: Boolean = false,
                     target: Policy.UpdateTarget = Policy.UpdateTarget.MAIN,
                     packageName: String = Policy.MAIN_PACKAGE,
                     packagedRelease: GiteeRelease = release,
                     configure: (Context) -> Unit = {}, after: (Context) -> Unit = {}) {
        test(label) {
            val cache = File(out, "fixture-${passed + failures.size}").apply { mkdirs() }
            val context = Context(cache, ContentResolver(), PackageManager(), packageName)
            configure(context)
            val api = fixtureApi { response(it.request(), code = status, bytes = bytes, mime = mime, length = length) }
            try {
                val run = { runBlocking { GiteeApkDownload(context, api, target).download(packagedRelease) { _, _ -> } }; Unit }
                if (expectSuccess) run() else rejects(run)
                check(cache.listFiles().orEmpty().isEmpty()) { "private .part file leaked" }
                after(context)
            } finally { api.cancel() }
        }
    }
    val notPublished: (Context) -> Unit = { check(it.contentResolver.inserts == 0) }
    val failedPending: (Context) -> Unit = { check(it.contentResolver.inserts == 1); check(it.contentResolver.deletes == 1); check(!it.contentResolver.rowExists) }
    downloadCase("helper valid ZIP+simulated package metadata publishes pending row", expectSuccess = true, after = {
        check(it.contentResolver.inserts == 1 && it.contentResolver.deletes == 0)
        check(it.contentResolver.pending == 0 && it.contentResolver.rowExists)
        check(it.contentResolver.bytes.toByteArray().contentEquals(goodZip))
    })
    downloadCase("helper chunked complete ZIP allowed", length = -1, expectSuccess = true)
    downloadCase("helper missing MIME valid ZIP allowed", mime = null, expectSuccess = true)
    downloadCase("helper HTTP500 rejected", status = 500, after = notPublished)
    downloadCase("helper HTML200 content-type rejected", bytes = "<html>error</html>".toByteArray(), mime = "text/html", after = notPublished)
    downloadCase("helper HTML200 spoofed APK MIME rejected", bytes = "<html>error</html>".toByteArray(), after = notPublished)
    downloadCase("helper three-byte ZIP prefix rejected", bytes = byteArrayOf(0x50, 0x4b, 3), after = notPublished)
    downloadCase("helper empty body rejected", bytes = byteArrayOf(), after = notPublished)
    downloadCase("helper 256MiB declared cap rejected", length = 256L * 1024 * 1024 + 1, after = notPublished)
    downloadCase("helper short HTTP Content-Length rejected", length = goodZip.size.toLong() + 1, after = notPublished)
    downloadCase("helper extra bytes beyond declared length rejected", length = goodZip.size.toLong() - 1, after = notPublished)
    downloadCase("helper chunked half ZIP rejected by ZipFile", bytes = goodZip.copyOf(goodZip.size / 2), length = -1, after = notPublished)
    downloadCase("helper missing central directory rejected", bytes = goodZip.copyOf(goodZip.size - 22), after = notPublished)
    val corrupt = goodZip.copyOf()
    val payloadOffset = 30 + "AndroidManifest.xml".toByteArray().size
    corrupt[payloadOffset] = (corrupt[payloadOffset].toInt() xor 1).toByte()
    downloadCase("helper stored entry CRC mismatch rejected", bytes = corrupt, after = notPublished)
    downloadCase("helper missing classes.dex rejected", bytes = zip(listOf("AndroidManifest.xml" to manifest)), after = notPublished)
    downloadCase("helper empty AndroidManifest rejected", bytes = zip(listOf("AndroidManifest.xml" to byteArrayOf(), "classes.dex" to dex)), after = notPublished)
    downloadCase("helper channel package declaration rejected", configure = { it.packageManager.archive!!.packageName = "com.tencent.qqmusicwatchs" } // quality-allow: fixture pre-sets archive metadata.
, after = notPublished)
    downloadCase("helper mismatched archive version rejected", configure = { it.packageManager.archive!!.versionName = "5.3.1" } // quality-allow: fixture pre-sets archive metadata.
, after = notPublished)
    downloadCase("helper absent archive metadata rejected", configure = { it.packageManager.archive = null }, after = notPublished)
    downloadCase("helper incompatible signer declaration rejected", configure = { it.packageManager.archive!!.signingInfo = SigningInfo(arrayOf(Signature("other"))) } // quality-allow: fixture pre-sets archive metadata.
, after = notPublished)
    downloadCase("helper absent installed signer declaration rejected", configure = { it.packageManager.installed.signingInfo = null }, after = notPublished)
    downloadCase("helper failed MediaStore insert no delete needed", configure = { it.contentResolver.insertNull = true }, after = { check(it.contentResolver.inserts == 1 && it.contentResolver.deletes == 0) })
    downloadCase("helper null MediaStore output deletes pending", configure = { it.contentResolver.outputNull = true }, after = failedPending)
    downloadCase("helper storage write failure deletes pending", configure = { it.contentResolver.outputThrows = true }, after = failedPending)
    downloadCase("helper update zero rows deletes pending", configure = { it.contentResolver.updateCount = 0 }, after = failedPending)
    downloadCase("helper update throws deletes pending", configure = { it.contentResolver.updateThrows = true }, after = failedPending)
    downloadCase("helper cleanup exception reported not swallowed as success", configure = { it.contentResolver.outputThrows = true; it.contentResolver.deleteThrows = true }, after = {
        check(it.contentResolver.deletes == 1); check(it.contentResolver.rowExists)
        check(Log.warnings.any { message -> message.contains("Could not remove pending") })
    })
    val channelReleaseAll = release.copy(apkAssets = listOf(asset, channelVisibleAsset, channelHiddenAsset))
    fun channelContext(context: Context) {
        context.packageManager.archive!!.packageName = Policy.CHANNEL_PACKAGE // quality-allow: fixture pre-sets archive metadata.
        context.packageManager.archive!!.versionName = "QQ音乐" // quality-allow: fixture pre-sets the real channel versionName.
        context.packageManager.archive!!.longVersionCode = 20260606L // quality-allow: fixture pre-sets the real channel versionCode.
        context.packageManager.installed.packageName = Policy.CHANNEL_PACKAGE // quality-allow: fixture pre-sets installed metadata.
        context.packageManager.installed.versionName = "QQ音乐" // quality-allow: fixture pre-sets the real channel versionName.
        context.packageManager.installed.longVersionCode = 20260606L // quality-allow: fixture pre-sets the real channel versionCode.
        context.packageManager.installed.lastUpdateTime = 1_700_000_000_000L // quality-allow: fixture install timestamp.
    }
    downloadCase("channel visible build downloads its own APK", goodZip, Policy.APK_MIME_TYPE, goodZip.size.toLong(), 200,
        true, Policy.UpdateTarget.CHANNEL_VISIBLE, Policy.CHANNEL_PACKAGE, channelReleaseAll,
        configure = { channelContext(it) },
        after = {
            check(it.contentResolver.inserts == 1)
            check(it.contentResolver.pending == 0 && it.contentResolver.rowExists)
        })
    downloadCase("channel hidden build downloads its own APK", goodZip, Policy.APK_MIME_TYPE, goodZip.size.toLong(), 200,
        true, Policy.UpdateTarget.CHANNEL_HIDDEN, Policy.CHANNEL_PACKAGE, channelReleaseAll,
        configure = { channelContext(it) }, after = { check(it.contentResolver.inserts == 1) })
    downloadCase("channel build rejects an archive still declaring the main package", goodZip, Policy.APK_MIME_TYPE,
        goodZip.size.toLong(), 200, false, Policy.UpdateTarget.CHANNEL_VISIBLE, Policy.CHANNEL_PACKAGE, channelReleaseAll,
        configure = { channelContext(it); it.packageManager.archive!!.packageName = Policy.MAIN_PACKAGE }, // quality-allow: fixture pre-sets archive metadata.
        after = notPublished)
    downloadCase("main build rejects an archive declaring the channel package", goodZip, Policy.APK_MIME_TYPE,
        goodZip.size.toLong(), 200, false, Policy.UpdateTarget.MAIN, Policy.MAIN_PACKAGE, release,
        configure = {
            it.packageManager.archive!!.packageName = Policy.CHANNEL_PACKAGE // quality-allow: fixture pre-sets archive metadata.
            it.packageManager.archive!!.versionName = "QQ音乐" // quality-allow: fixture pre-sets the real channel versionName.
        }, after = notPublished)
    downloadCase("channel build still requires the shared signing key", goodZip, Policy.APK_MIME_TYPE,
        goodZip.size.toLong(), 200, false, Policy.UpdateTarget.CHANNEL_VISIBLE, Policy.CHANNEL_PACKAGE, channelReleaseAll,
        configure = { channelContext(it); it.packageManager.archive!!.signingInfo = SigningInfo(arrayOf(Signature("other"))) }, // quality-allow: fixture pre-sets archive metadata.
        after = notPublished)
    downloadCase("channel build with no channel asset in this release downloads nothing", goodZip, Policy.APK_MIME_TYPE,
        goodZip.size.toLong(), 200, false, Policy.UpdateTarget.CHANNEL_VISIBLE, Policy.CHANNEL_PACKAGE, release,
        configure = { channelContext(it) }, after = notPublished)
    downloadCase("visible target cannot consume the hidden asset", goodZip, Policy.APK_MIME_TYPE,
        goodZip.size.toLong(), 200, false, Policy.UpdateTarget.CHANNEL_VISIBLE, Policy.CHANNEL_PACKAGE,
        release.copy(apkAssets = listOf(asset, channelHiddenAsset)),
        configure = { channelContext(it) }, after = notPublished)
    downloadCase("unsupported build downloads nothing even with a valid release", goodZip, Policy.APK_MIME_TYPE,
        goodZip.size.toLong(), 200, false, Policy.UpdateTarget.UNSUPPORTED, "com.example.fork", channelReleaseAll,
        configure = { }, after = notPublished)

    test("cancelled coroutine before download does not request/insert/create .part") {
        val cache = File(out, "cancel-before").apply { mkdirs() }
        val context = Context(cache, ContentResolver(), PackageManager())
        var calls = 0
        val api = fixtureApi { calls++; response(it.request(), bytes = goodZip) }
        val job = Job().apply { cancel() }
        try { rejects { runBlocking(job) { GiteeApkDownload(context, api, Policy.UpdateTarget.MAIN).download(release) { _, _ -> } } }; check(calls == 0); check(context.contentResolver.inserts == 0); check(cache.listFiles().orEmpty().isEmpty()) }
        finally { api.cancel() }
    }
    test("coroutine cancellation while copying deletes pending and private file") {
        val cache = File(out, "cancel-copy").apply { mkdirs() }
        val context = Context(cache, ContentResolver(), PackageManager())
        val job = Job()
        // Cancel inside first storage write; subsequent ensureActive must prevent publishing IS_PENDING=0.
        context.contentResolver.beforeWrite = { job.cancel() }
        val api = fixtureApi { response(it.request(), bytes = goodZip) }
        try {
            rejects { runBlocking(job) { GiteeApkDownload(context, api, Policy.UpdateTarget.MAIN).download(release) { _, _ -> } } }
            failedPending(context); check(context.contentResolver.pending == 1); check(cache.listFiles().orEmpty().isEmpty())
        } finally { api.cancel() }
    }
}

private fun sourceContracts(root: File) {
    val activityDir = File(root, "app/src/main/java/com/RobinNotBad/BiliClient/activity/update")
    val source = File(activityDir, "UpdateInfoActivity.kt").readText()
    val installer = File(activityDir, "UpdateApkInstaller.kt").readText()
    val layout = File(root, "app/src/main/res/layout/activity_update_info.xml").readText()
    test("source contract: no versionCode-only decision or legacy Intent extras") {
        check(source.contains("UpdateVersion.compare(latest.tagName, BuildConfig.VERSION_NAME)"))
        check(!source.contains("VERSION_CODE") && !source.contains("getStringExtra") && !source.contains("AppInfoApi"))
    }
    test("source contract: main-thread loading/downloading guards before launch") {
        check(source.contains("private fun isBusy(): Boolean = loading || downloading || installing"))
        check(source.contains("if (isBusy() || savedUri != null) return"))
        check(source.contains("val asset = latest.apkFor(target) ?: return"))
        check(source.indexOf("loading = true") < source.indexOf("pageScope.launch"))
    }
    test("source contract: lifecycle cancels network and scope") {
        val destroy = source.substringAfter("override fun onDestroy()")
        check(destroy.indexOf("api.cancel()") < destroy.indexOf("super.onDestroy()"))
        check(destroy.indexOf("pageScope.cancel()") < destroy.indexOf("super.onDestroy()"))
        // Three cancellable launches now exist: load, download and install.
        check(source.split("catch (cancelled: CancellationException)").size == 4)
    }
    test("source contract: collapse state is saved and restored") {
        check(source.contains("STATE_NOTES_EXPANDED"))
        check(source.contains("outState.putBoolean(STATE_NOTES_EXPANDED, notesExpanded)"))
        check(source.contains("savedInstanceState?.getBoolean(STATE_NOTES_EXPANDED, false)"))
        check(source.contains("private fun toggleNotes()") && source.contains("private fun renderNotesState()"))
        // Default must be collapsed: long release notes should not fill the screen before a tap.
        check(source.contains("private var notesExpanded = false"))
        check(layout.contains("android:id=\"@+id/updateNotesHeader\""))
        // The body starts hidden in XML so the first frame is collapsed even before onCreate finishes.
        check(Regex("updateNotes\"[\\s\\S]{0,400}?android:visibility=\"gone\"").containsMatchIn(layout))
    }
    test("source contract: download and install are two separate buttons") {
        check(layout.contains("android:id=\"@+id/updateDownload\""))
        check(layout.contains("android:id=\"@+id/updateInstall\""))
        check(source.contains("installButton.setOnClickListener { installDownloaded() }"))
        // Mutually exclusive: the download button disappears as soon as a verified file exists.
        check(source.contains("downloadButton.visibility = if (hasDownload) View.GONE else View.VISIBLE"))
        check(source.contains("installButton.visibility = if (hasDownload) View.VISIBLE else View.GONE"))
    }
    test("source contract: channel build is resolved at runtime, not by BuildConfig") {
        check(source.contains("resolveTarget()"))
        check(source.contains("packageManager.getLaunchIntentForPackage(packageName) != null"))
        check(!source.contains("CHANNEL_BUILD") && !source.contains("isMainPackageBuild"))
    }
    test("source contract: installer copies out of MediaStore and uses FileProvider") {
        // The old page only accepted a File path; a content:// URI must be copied to cacheDir first.
        check(installer.contains("openInputStream(source)"))
        check(installer.contains("FileProvider.getUriForFile"))
        check(installer.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        check(!installer.contains("Intent(Intent.ACTION_INSTALL_PACKAGE)"))
        // file:// URIs are rejected by Android 10+, so the installer must never build one.
        check(!installer.contains("Uri.fromFile"))
        check(installer.contains("canRequestPackageInstalls"))
    }
    test("source contract: round-screen padding keeps the timeText sibling") {
        check(layout.contains("android:id=\"@+id/timeText\""))
        check(layout.contains("android:id=\"@+id/pageName\""))
    }
    println("SOURCE_CONTRACT_LIMITATION activity lexical guards checked, not Android lifecycle/UI/concurrent click execution")
}
