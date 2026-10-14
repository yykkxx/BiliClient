package com.RobinNotBad.BiliClient.activity.update

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.TextView

import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Nullable

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.DownloadActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.google.android.material.button.MaterialButton

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateInfoActivity : BaseActivity() {

    var versionNameTv: TextView? = null
    var versionCodeTv: TextView? = null
    var isReleaseTv: TextView? = null
    var pubTimeTv: TextView? = null
    var updateLogTv: TextView? = null
    var downloadBtn: MaterialButton? = null

    var apkFile: File? = null

    val launcher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ActivityResultCallback { o: ActivityResult? ->
            Logu.d("下载回调", "onActivityResult")
            if (apkFile != null && apkFile!!.exists()) {
                downloadComplete()
            }
        }
    )

    @SuppressLint("InflateParams")
    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = getIntent()
        val versionName = intent.getStringExtra("versionName")
        val versionCode = intent.getIntExtra("versionCode", -1)
        val isRelease = intent.getIntExtra("isRelease", 0)
        val ctime = intent.getLongExtra("ctime", -1)
        val updateLog = intent.getStringExtra("updateLog")
        val canDownload = intent.getIntExtra("canDownload", 0)

        asyncInflate(R.layout.activity_update_info) { layoutView, resId ->

            versionNameTv = findViewById(R.id.versionName)
            versionCodeTv = findViewById(R.id.versionCode)
            isReleaseTv = findViewById(R.id.isRelease)
            pubTimeTv = findViewById(R.id.pubTime)
            updateLogTv = findViewById(R.id.updateLog)
            downloadBtn = findViewById(R.id.download)

            if (versionName == null || versionCode == -1) {
                finish()
                return@asyncInflate
            }

            versionNameTv!!.text = String.format("版本名: %s", versionName)
            versionCodeTv!!.text = String.format(Locale.getDefault(), "版本号: %d", versionCode)
            isReleaseTv!!.text = String.format("是否为正式版: %s", if (isRelease == 1) "是" else "否")
            pubTimeTv!!.text = String.format("发布时间: %s", if (ctime == -1L) "未知" else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(if (ctime.toString().length == 13) ctime else ctime * 1000)))
            updateLogTv!!.text = if (TextUtils.isEmpty(updateLog)) "暂无更新日志" else updateLog

            if (canDownload != 1) downloadBtn!!.visibility = View.GONE
            downloadBtn!!.setOnClickListener { view1 ->
                if (!FileUtil.checkStoragePermission()) {
                    MsgUtil.showMsg("请授权存储空间权限")
                    FileUtil.requestStoragePermission(this)
                    return@setOnClickListener
                }

                CenterThreadPool.run(Runnable {
                    try {
                        runOnUiThread { MsgUtil.showMsg("获取下载地址……") }
                        val url = AppInfoApi.getDownloadUrl(versionCode)

                        apkFile = File(FileUtil.getDownloadPath(), FileUtil.getFileNameFromLink(url))
                        if (apkFile!!.exists()) {
                            MsgUtil.showMsg("已有本地安装包！")
                            downloadComplete()
                            return@Runnable
                        }

                        val downloadIntent = Intent(this, DownloadActivity::class.java)
                                .putExtra("link", url)
                                .putExtra("path", FileUtil.getDownloadPath().absolutePath)
                                .putExtra("terminal", true)
                                .putExtra("type", 0)
                        launcher.launch(downloadIntent)
                    } catch (e: Throwable) {
                        MsgUtil.err("下载更新", e)
                    }
                })



                /*
                CenterThreadPool.run(() -> {
                    try {
                        runOnUiThread(() -> MsgUtil.showMsg("开始获取下载地址"));
                        String url = AppInfoApi.getDownloadUrl(versionCode);
                        if (TextUtils.isEmpty(url)) {
                            runOnUiThread(() -> MsgUtil.showMsg("没有该版本的下载地址"));
                        } else {
                            Request request = new Request.Builder()
                                    .get()
                                    .url(url)
                                    .build();

                            NetWorkUtil.getOkHttpInstance().newCall(request).enqueue(new Callback() {
                                @Override
                                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                    runOnUiThread(() -> {
                                        MsgUtil.showMsg("下载失败: " + e.getMessage());
                                        downloadBtn.setText("下载");
                                    });
                                }

                                @Override
                                public void onResponse(@NonNull Call call, @NonNull Response response) {
                                    if (!response.isSuccessful()) {
                                        runOnUiThread(() -> MsgUtil.showMsg("下载失败！"));
                                    } else {
                                        try {
                                            downloading = true;
                                            stopDownload = false;
                                            ResponseBody responseBody = response.body();
                                            if (responseBody != null) {
                                                long fileSize = responseBody.contentLength();
                                                File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BiliTerminal_" + versionCode + ".apk");
                                                if (file.exists() && !file.delete()) {
                                                    int num = 1;
                                                    while (file.exists()) {
                                                        if (file.delete()) break;
                                                        file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BiliTerminal_" + versionCode + "(" + num++ + ").apk");
                                                    }
                                                }
                                                FileOutputStream fos = new FileOutputStream(file);
                                                int bytesRead;
                                                byte[] data = new byte[1024];
                                                long totalBytesRead = 0;
                                                while ((bytesRead = responseBody.byteStream().read(data)) != -1) {
                                                    if (stopDownload) break;
                                                    fos.write(data, 0, bytesRead);
                                                    totalBytesRead += bytesRead;
                                                    int progress = (int) ((totalBytesRead * 100) / fileSize);
                                                    runOnUiThread(()->downloadBtn.setText(String.format(Locale.getDefault(), "下载(%d%%)", progress)));
                                                }
                                                fos.flush();
                                                fos.close();
                                                if (!stopDownload) {
                                                    startActivity(new Intent(UpdateInfoActivity.this, UpdateDownloadResultActivity.class)
                                                            .putExtra("path", file.getAbsolutePath()));
                                                } else {
                                                    if (file.delete()) {
                                                        runOnUiThread(() -> MsgUtil.showMsg("已中止下载并删除文件"));
                                                    } else {
                                                        runOnUiThread(() -> MsgUtil.showMsg("已中止下载，删除文件失败"));
                                                    }
                                                }
                                                stopDownload = false;
                                                downloading = false;
                                            }
                                        } catch (Throwable th) {
                                            MsgUtil.err("下载出错：",th);
                                            downloading = false;
                                        }
                                    }
                                    downloadBtn.setText("下载");
                                }
                            });
                        }
                    } catch (IOException e) {
                        runOnUiThread(() -> MsgUtil.showMsg("网络异常"));
                    } catch (Throwable th) {
                        runOnUiThread(() -> MsgUtil.showMsg("错误: " + th.getMessage()));
                    }
                });
                 */
            }
        }
    }

    private fun downloadComplete() {
        runOnUiThread {
            val apk = apkFile ?: return@runOnUiThread
            SharedPreferencesUtil.putString("terminal_update_pkg", apk.absolutePath)
            downloadBtn!!.visibility = View.GONE
            val installBtn = findViewById<MaterialButton>(R.id.install)
            val deleteBtn = findViewById<MaterialButton>(R.id.delete)
            installBtn.visibility = View.VISIBLE
            deleteBtn.visibility = View.VISIBLE

            installBtn.setOnClickListener { v -> startActivity(Intent(this, UpdateInstallActivity::class.java)
                    .putExtra("path", apk.absolutePath)) }

            deleteBtn.setOnClickListener { v ->
                CenterThreadPool.run(Runnable {
                    if (apk.delete()) MsgUtil.showMsg("删除成功")
                    else MsgUtil.showMsg("删除失败")
                    SharedPreferencesUtil.putString("terminal_update_pkg", "")
                    finish()
                    startActivity(getIntent())
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
