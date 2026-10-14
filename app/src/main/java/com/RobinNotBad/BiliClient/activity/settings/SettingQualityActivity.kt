package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle
import android.view.View

import androidx.annotation.Nullable
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Objects

class SettingQualityActivity : BaseActivity() {
    @JvmField
    var adapter: QualityChooseAdapter? = null

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_simple_list)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        findViewById<View>(R.id.top).setOnClickListener { view ->
            setResult(RESULT_CANCELED)
            finish()
        }

        setPageName("请选择清晰度")

        adapter = QualityChooseAdapter(this)
        adapter!!.setNameList(ArrayList(qnMap.keys))
        adapter!!.setOnItemClickListener { position: Int -> save(position) }

        recyclerView.layoutManager = CustomLinearManager(this)
        recyclerView.adapter = adapter!!
    }

    private fun save(position: Int) {
        val str = adapter!!.getName(position)
        if (qnMap.containsKey(str))
            SharedPreferencesUtil.putInt("play_qn", Objects.requireNonNull(qnMap[str])!!)
        finish()
    }

    companion object {
        @JvmField
        val qnMap = LinkedHashMap<String, Int>()

        init {
            qnMap["360P"] = 16
            qnMap["720P"] = 64
            qnMap["1080P"] = 80
        }
    }
}
