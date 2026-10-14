package com.RobinNotBad.BiliClient.activity.user.favorite

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.EditText

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil

class FavoriteFolderEditActivity : BaseActivity() {

    private var mediaId: Long = 0
    private var originalTitle: String? = null
    private var isDefault = false
    private var editTitle: EditText? = null
    private var editIntro: EditText? = null
    private var btnSave: com.google.android.material.card.MaterialCardView? = null
    private var btnDelete: com.google.android.material.card.MaterialCardView? = null
    private var deleteClickCount = 0

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_folder_edit)

        val intent = getIntent()
        mediaId = intent.getLongExtra("mediaId", 0)
        originalTitle = intent.getStringExtra("title")
        val intro = intent.getStringExtra("intro")
        isDefault = intent.getBooleanExtra("isDefault", false)

        editTitle = findViewById(R.id.editTitle)
        editIntro = findViewById(R.id.editIntro)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)

        if (originalTitle != null) {
            editTitle!!.setText(originalTitle)
        }
        if (intro != null) {
            editIntro!!.setText(intro)
        }

        if (isDefault) {
            editTitle!!.isEnabled = false
            editIntro!!.isEnabled = false
            btnSave!!.isClickable = false
            btnSave!!.alpha = 0.5f
            btnDelete!!.visibility = android.view.View.GONE
            MsgUtil.showMsg("默认收藏夹不能编辑或删除")
        } else {
            btnSave!!.setOnClickListener { v -> saveFolder() }
            btnDelete!!.setOnClickListener { v -> handleDeleteClick() }
        }
    }

    private fun saveFolder() {
        val title = editTitle!!.text.toString().trim()
        if (title.isEmpty()) {
            MsgUtil.showMsg("请输入收藏夹名称")
            return
        }

        val intro = editIntro!!.text.toString().trim()
        val saveBtn = btnSave
        saveBtn!!.isClickable = false

        CenterThreadPool.run(Runnable {
            try {
                val result = FavoriteApi.editFolder(mediaId, title, intro, 0)
                runOnUiThread(Runnable {
                    saveBtn!!.isClickable = true
                    if (result == 0) {
                        MsgUtil.showMsg("保存成功")
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        MsgUtil.showMsg("保存失败，错误码：" + result)
                    }
                })
            } catch (e: Exception) {
                runOnUiThread(Runnable {
                    saveBtn!!.isClickable = true
                    report(e)
                })
            }
        })
    }

    private fun handleDeleteClick() {
        deleteClickCount++
        if (deleteClickCount == 1) {
            MsgUtil.showMsg("再次点击删除按钮确认删除")
            val handler = android.os.Handler()
            handler.postDelayed({ deleteClickCount = 0 }, 3000)
        } else if (deleteClickCount >= 2) {
            deleteClickCount = 0
            deleteFolder()
        }
    }

    private fun deleteFolder() {
        val delBtn = btnDelete
        delBtn!!.isClickable = false

        CenterThreadPool.run(Runnable {
            try {
                val result = FavoriteApi.deleteFolder(mediaId)
                runOnUiThread(Runnable {
                    delBtn!!.isClickable = true
                    if (result == 0) {
                        MsgUtil.showMsg("删除成功")
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        MsgUtil.showMsg("删除失败，错误码：" + result)
                    }
                })
            } catch (e: Exception) {
                runOnUiThread(Runnable {
                    delBtn!!.isClickable = true
                    report(e)
                })
            }
        })
    }
}
