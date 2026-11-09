package com.RobinNotBad.BiliClient.activity.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.RobinNotBad.BiliClient.model.update.GiteeReleasePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 把「刚下载好的安装包」交给系统安装器。下载与安装是两个独立动作：
 * 下载只负责把 APK 落盘到公共 Download，安装由用户点「安装」按钮显式触发。
 *
 * 为什么不直接把 MediaStore 的 `content://` 交给 `UpdateInstallActivity`：
 * 那个页面只接受文件路径（内部 `File(path)`），MediaStore URI 不是文件路径，直接喂进去必崩。
 * 也不直接把 MediaStore URI 丢给系统安装器：Download 集合的读权限取决于
 * READ_EXTERNAL_STORAGE / 安装器的授权策略，Android 10+ 分区存储下不可靠。
 * 所以这里走「先读回自己进程 → 拷到私有 cacheDir → 用本应用 FileProvider 暴露」这条路：
 * - 拷出来的是我们自己进程已校验过的字节（下载阶段已过 ZIP/CRC/包名/签名校验）；
 * - FileProvider 的 authority 是本应用自己的，配合 FLAG_GRANT_READ_URI_PERMISSION，
 *   只给系统安装器一次性的读权限，不涉及 file:// （Android 10+ 会直接 FileUriExposedException）。
 */
object UpdateApkInstaller {
    /** 安装器准备阶段的结果；真正的安装结果由系统安装器页面呈现，应用侧无法代答。 */
    enum class Outcome { STARTED, NEED_PERMISSION, MISSING, NO_INSTALLER, FAILED }

    private const val CACHE_DIR_NAME = "gitee-update-install"

    /**
     * 拷贝 + 拉起系统安装器。必须传入真实 activity context（FileProvider 与 startActivity 都需要）。
     * 返回 [Outcome] 供调用方决定提示文案；异常只映射成 FAILED，绝不把内部路径抛给用户。
     */
    suspend fun install(activity: Context, source: Uri, displayName: String): Outcome {
        if (!hasInstallPermission(activity)) {
            openInstallPermissionSettings(activity)
            return Outcome.NEED_PERMISSION
        }
        val apk = try {
            withContext(Dispatchers.IO) { copyToPrivateCache(activity, source, displayName) }
        } catch (ignored: IOException) {
            return Outcome.MISSING
        } catch (ignored: SecurityException) {
            // 用户在下载后把文件删了/移动了，或者授权被撤销：都按「安装包不在了」处理。
            return Outcome.MISSING
        }
        return launchSystemInstaller(activity, apk)
    }

    /** 安装未知来源应用需要一次性授权（minSdk 29 起 canRequestPackageInstalls 恒可用）。 */
    private fun hasInstallPermission(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    private fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (ignored: ActivityNotFoundException) {
            // 系统没有这个设置页时只能让用户自己去设置里授权，调用方会提示。
        }
    }

    private fun copyToPrivateCache(context: Context, source: Uri, displayName: String): File {
        val directory = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        // 只保留本次这一份：安装包很大，多版本堆积会白占 cacheDir（系统可能随时清理 cacheDir，
        // 所以每次点安装都用最新下载的字节重新拷一份，而不是假定上次的副本还在）。
        directory.listFiles()?.forEach { stale -> if (stale.isFile) stale.delete() }
        val target = File(directory, GiteeReleasePolicy.installFileName(displayName))
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Downloaded APK is no longer readable")
        input.use { stream ->
            target.outputStream().use { output -> stream.copyTo(output) }
        }
        if (target.length() <= 0L) throw IOException("Downloaded APK is empty")
        return target
    }

    private fun launchSystemInstaller(context: Context, apk: File): Outcome = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, GiteeReleasePolicy.APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Outcome.STARTED
    } catch (ignored: ActivityNotFoundException) {
        Outcome.NO_INSTALLER
    } catch (ignored: IllegalArgumentException) {
        // FileProvider 路径不在 file_paths.xml 白名单里时抛这个；配置问题不该表现为「安装成功」。
        Outcome.FAILED
    }
}

/** FileProvider authority 的后缀；渠道包构建脚本会把 manifest 里的 authority 一起改成新包名。 */
private const val FILE_PROVIDER_SUFFIX = ".FileProvider"
