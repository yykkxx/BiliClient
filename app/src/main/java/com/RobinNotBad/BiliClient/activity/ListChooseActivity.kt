package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import java.util.ArrayList

class ListChooseActivity : BaseActivity() {

    var items: List<String>? = null

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_simple_list)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        findViewById<View>(R.id.top).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        val intent = getIntent()
        if (intent.getStringExtra("title") != null) {
            (findViewById<View>(R.id.pageName) as TextView).text = intent.getStringExtra("title")
        } else {
            (findViewById<View>(R.id.pageName) as TextView).text = "请选择"
        }
        if (intent.getSerializableExtra("items") == null) {
            finish()
        } else {
            @Suppress("UNCHECKED_CAST")
            this.items = intent.getSerializableExtra("items") as List<String>
        }

        val adapter = Adapter(this)
        adapter.setNameList(items)

        adapter.setOnItemClickListener(OnItemClickListener { position ->
            setResult(RESULT_OK, Intent().putExtra("item", items!![position]))
            finish()
        })

        recyclerView.layoutManager = CustomLinearManager(this)
        recyclerView.adapter = adapter
    }

    class Adapter constructor(val context: Context) : RecyclerView.Adapter<com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder>() {

        private var nameList: List<String> = ArrayList()

        private var onItemClickListener: OnItemClickListener? = null
        private var onItemLongClickListener: OnItemLongClickListener? = null

        fun setOnItemClickListener(listener: OnItemClickListener?) {
            this.onItemClickListener = listener
        }

        @SuppressLint("NotifyDataSetChanged")
        fun setNameList(newList: List<String>?) {
            this.nameList = newList ?: ArrayList()
            notifyDataSetChanged()
        }

        @NonNull
        override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder {
            val view = LayoutInflater.from(this.context).inflate(R.layout.cell_choose, parent, false)
            return com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder(view)
        }

        override fun onBindViewHolder(@NonNull holder: com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter.Holder, position: Int) {

            holder.folder_name.text = nameList[position]

            holder.itemView.setOnClickListener {
                if (onItemClickListener != null) onItemClickListener!!.onItemClick(position)
            }

            holder.itemView.setOnLongClickListener {
                if (onItemLongClickListener != null) {
                    onItemLongClickListener!!.onItemLongClick(position)
                    true
                } else false
            }

        }

        override fun getItemCount(): Int {
            return nameList.size
        }
    }
}
