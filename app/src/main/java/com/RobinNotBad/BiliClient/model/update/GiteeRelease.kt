package com.RobinNotBad.BiliClient.model.update

/**
 * Only public release metadata is retained; author/account/device information is not needed.
 *
 * [apkAssets] 只保留「文件名形状合法 + 下载 URL 属于本 fork 该 tag」的 APK 候选（见
 * [GiteeReleasePolicy.candidateApks]）。页面按当前安装包的类型（主包 / 渠道显示版 / 渠道隐藏版）
 * 从中挑出唯一那一个，所以这里不再预先决定「主包 APK」——否则渠道包就没有可选项。
 */
data class GiteeRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val apkAssets: List<GiteeReleaseAsset>
) {
    /** 主包更新用的那个附件；渠道包装的是另两份，所以页面要按目标取 [apkFor]。 */
    val mainApk: GiteeReleaseAsset?
        get() = GiteeReleasePolicy.selectMainApk(tagName, apkAssets)

    fun apkFor(target: GiteeReleasePolicy.UpdateTarget): GiteeReleaseAsset? =
        GiteeReleasePolicy.selectApk(target, tagName, apkAssets)
}

/** [declaredSize] is null when Gitee omits the field; the HTTP Content-Length remains authoritative. */
data class GiteeReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val declaredSize: Long? = null
)
