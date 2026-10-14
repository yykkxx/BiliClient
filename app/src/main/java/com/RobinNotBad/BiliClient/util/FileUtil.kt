package com.RobinNotBad.BiliClient.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.RobinNotBad.BiliClient.BiliTerminal
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Objects

//用于清除缓存，因为glide实际上会往本地存不少缩略图，时间一长就会爆炸
//清除调用我放在了每次刷新推荐页

object FileUtil {

    @JvmStatic
    fun clearCache(context: Context) {
        val cacheDir = context.cacheDir
        if (cacheDir.exists() && Objects.requireNonNull(cacheDir.listFiles())!!.size != 0)
            deleteFolder(cacheDir)
        Log.e("debug", "清除了缓存")
    }

    @JvmStatic
    fun deleteFolder(folder: File) {
        if (!folder.exists()) return

        if (folder.isFile) {
            folder.delete()
            return
        }

        val templist = folder.listFiles()
        assert(templist != null)
        for (file in templist!!) {
            if (file.isFile) {   //如果该项是文件，直接删除
                file.delete()
            } else {    //如果该项是目录
                if (Objects.requireNonNull(file.listFiles())!!.size != 0)
                    deleteFolder(file)    //如果子文件夹不是空的，继续扫描下去，实现套娃效果
            }
            Log.e("debug", file.toString())
        }
        folder.delete()
    }

    @JvmStatic
    fun readString(file: File?): String? {
        if (file == null || !file.exists() || !file.canRead() || !file.isFile) return null
        try {
            val outputStream = ByteArrayOutputStream()
            val inputStream = FileInputStream(file)
            val channel = inputStream.channel
            val buffer = ByteBuffer.allocate(1 shl 13)
            var i = 0
            while (channel.read(buffer).also { i = it } != -1) {
                buffer.flip()
                outputStream.write(buffer.array(), 0, i)
                buffer.clear()
            }
            return outputStream.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    @JvmStatic
    fun checkStoragePermission(): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 17) return true
        val context = BiliTerminal.context
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun requestStoragePermission(activity: Activity) {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 100)
    }

    @JvmStatic
    fun getVideoDownloadPath(): File {
        val path = File(SharedPreferencesUtil.getString("save_path_video",
                Environment.getExternalStorageDirectory().toString() + "/Android/media/" + BiliTerminal.context.packageName + "/"))

        try {
            val nomedia = File(path, ".nomedia")
            if (SharedPreferencesUtil.getBoolean("save_ban_gallery", true) && !nomedia.exists())
                nomedia.createNewFile()
            else if (nomedia.exists()) nomedia.delete()
        } catch (ignored: Exception) {
        }
        return path
    }

    @JvmStatic
    fun getVideoDownloadPath(title: String?, child: String?): File {
        val parentFolder = File(getVideoDownloadPath(), stringToFile(title!!))
        if (child.isNullOrEmpty()) return parentFolder
        return File(parentFolder, stringToFile(child))
    }

    @JvmStatic
    fun getPicturePath(): File {
        return File(SharedPreferencesUtil.getString("save_path_pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + "/哔哩终端/"))
    }

    @JvmStatic
    fun getDownloadPath(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    @JvmStatic
    fun stringToFile(str: String): String {
        return str.substring(0, Math.min(85, str.length))    //防止长度溢出
                .replace("|", "｜")
                .replace(":", "：")
                .replace("*", "﹡")
                .replace("?", "？")
                .replace("\"", "”")
                .replace("<", "＜")
                .replace(">", "＞")
                .replace("/", "／")
                .replace("\\", "＼")    //文件名里不能包含非法字符
    }

    @JvmStatic
    fun getFileNameFromLink(link: String): String {
        val length = link.length
        for (i in length - 1 downTo 1) {
            if (link[i] == '/') {
                return link.substring(i + 1)
            }
        }
        return "fail"
    }

    @JvmStatic
    fun getFileFirstName(file: String): String {
        for (i in 0 until file.length) {
            if (file[i] == '.') {
                return file.substring(0, i)
            }
        }
        return "fail"
    }
}
