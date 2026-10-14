package com.RobinNotBad.BiliClient.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.ExpLog

class ExpLogAdapter(private val context: Context, private val logList: List<ExpLog>) : RecyclerView.Adapter<ExpLogAdapter.ViewHolder>() {

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        val view: View = LayoutInflater.from(context).inflate(R.layout.cell_exp_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        if (position < 0 || position >= logList.size) {
            return
        }
        val log: ExpLog? = logList[position]
        if (log == null) {
            return
        }

        val deltaText = "+" + log.delta
        holder.delta.setText(deltaText)
        holder.reason.setText(log.reason)
        holder.time.setText(log.time)
    }

    override fun getItemCount(): Int {
        return if (logList != null) logList.size else 0
    }

    class ViewHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val delta: TextView = itemView.findViewById(R.id.delta)

        @JvmField
        val reason: TextView = itemView.findViewById(R.id.reason)

        @JvmField
        val time: TextView = itemView.findViewById(R.id.time)
    }
}
