package android.content.pm

// These are declarations returned by a simulated parser, not cryptographic signature verification.
data class Signature(val fixtureId: String)
class SigningInfo(val apkContentsSigners: Array<Signature>)
class PackageInfo(
    var packageName: String,
    var versionName: String?,
    var signingInfo: SigningInfo?,
    var longVersionCode: Long = 0L,
    var lastUpdateTime: Long = 0L
)
class PackageManager {
    var archive: PackageInfo? = PackageInfo("com.RobinNotBad.BiliClient", "5.3.2", SigningInfo(arrayOf(Signature("main"))))
    var installed = PackageInfo("com.RobinNotBad.BiliClient", "5.3.1", SigningInfo(arrayOf(Signature("main"))))
    var archiveReads = 0
    fun getPackageArchiveInfo(path: String, flags: Int): PackageInfo? { archiveReads++; return archive }
    fun getPackageInfo(name: String, flags: Int): PackageInfo = installed
    companion object { const val GET_SIGNING_CERTIFICATES = 134217728 }
}
