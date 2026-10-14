package com.RobinNotBad.BiliClient.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.StringUtil

class CopyTextActivity : BaseActivity() {
    private var content: String? = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_copy)

        val intent = getIntent()

        content = intent.getStringExtra("content")

        if (content == null) {
            finish()
            return
        }

        val edittext = findViewById<EditText>(R.id.content)
        edittext.setText(content)

        val beginEdit = findViewById<EditText>(R.id.begin_index)
        val endEdit = findViewById<EditText>(R.id.end_index)

        edittext.setOnFocusChangeListener { view, b ->
            if (!b) {
                beginEdit.setText(edittext.selectionStart.toString())
                endEdit.setText(edittext.selectionEnd.toString())
            }
        }


        val textWatcher: TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
            }

            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                try {
                    edittext.setSelection(Integer.parseInt(beginEdit.text.toString()), Integer.parseInt(endEdit.text.toString()))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun afterTextChanged(editable: Editable) {

            }
        }
        beginEdit.addTextChangedListener(textWatcher)
        endEdit.addTextChangedListener(textWatcher)

        findViewById<View>(R.id.copy_all).setOnClickListener {
            StringUtil.copyText(this, content!!)
            MsgUtil.showMsg("已复制")
        }
        findViewById<View>(R.id.copy).setOnClickListener {
            try {
                StringUtil.copyText(this, content!!.substring(Integer.parseInt(beginEdit.text.toString())
                        , Integer.parseInt(endEdit.text.toString())))   //我嘞个超级括号
                MsgUtil.showMsg("已复制")
            } catch (e: Exception) {
                MsgUtil.showMsg("复制失败，请检查选择的范围")
            }
        }

        findViewById<View>(R.id.begin_left).setOnClickListener {
            try {
                if (Integer.parseInt(beginEdit.text.toString()) - 1 < 0)
                    beginEdit.setText("0")
                else
                    beginEdit.setText((Integer.parseInt(beginEdit.text.toString()) - 1).toString())
            } catch (e: Exception) {
                beginEdit.setText("0")
            }
        }
        findViewById<View>(R.id.begin_right).setOnClickListener {
            try {
                if (Integer.parseInt(beginEdit.text.toString()) + 1 > edittext.text.length)
                    beginEdit.setText(edittext.text.length.toString())
                else
                    beginEdit.setText((Integer.parseInt(beginEdit.text.toString()) + 1).toString())
            } catch (e: Exception) {
                beginEdit.setText("0")
            }
        }
        findViewById<View>(R.id.end_left).setOnClickListener {
            try {
                if (Integer.parseInt(endEdit.text.toString()) - 1 < 0)
                    endEdit.setText("0")
                else
                    endEdit.setText((Integer.parseInt(endEdit.text.toString()) - 1).toString())
            } catch (e: Exception) {
                endEdit.setText("0")
            }
        }
        findViewById<View>(R.id.end_right).setOnClickListener {
            try {
                if (Integer.parseInt(endEdit.text.toString()) + 1 > edittext.text.length)
                    endEdit.setText(edittext.text.length.toString())
                else
                    endEdit.setText((Integer.parseInt(endEdit.text.toString()) + 1).toString())
            } catch (e: Exception) {
                endEdit.setText("0")
            }
        }
        findViewById<View>(R.id.begin_left).setOnLongClickListener {
            beginEdit.setText("0")
            false
        }
        findViewById<View>(R.id.begin_right).setOnLongClickListener {
            beginEdit.setText(edittext.text.length.toString())
            false
        }
        findViewById<View>(R.id.end_left).setOnLongClickListener {
            endEdit.setText("0")
            false
        }
        findViewById<View>(R.id.end_right).setOnLongClickListener {
            endEdit.setText(edittext.text.length.toString())
            false
        }
    }
}
