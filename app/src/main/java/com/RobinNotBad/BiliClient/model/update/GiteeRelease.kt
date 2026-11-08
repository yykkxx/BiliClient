package com.RobinNotBad.BiliClient.model.update

/** Only public release metadata is retained; author/account/device information is not needed. */
data class GiteeRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val mainApk: GiteeReleaseAsset?
)

/** [declaredSize] is null when Gitee omits the field; the HTTP Content-Length remains authoritative. */
data class GiteeReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val declaredSize: Long? = null
)
