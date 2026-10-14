package com.RobinNotBad.BiliClient.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.LoginRecord

class LoginRecordAdapter(private val context: Context, private val recordList: List<LoginRecord>) : RecyclerView.Adapter<LoginRecordAdapter.ViewHolder>() {

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): ViewHolder {
        val view: View = LayoutInflater.from(context).inflate(R.layout.cell_login_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: ViewHolder, position: Int) {
        if (position < 0 || position >= recordList.size) {
            return
        }
        val record: LoginRecord? = recordList[position]
        if (record == null) {
            return
        }

        holder.deviceName.setText(record.deviceName)
        holder.loginType.setText("登录方式：" + record.loginType)
        holder.loginTime.setText("登录时间：" + record.loginTime)
        holder.location.setText(record.location)
        holder.ip.setText(record.ip)
    }

    override fun getItemCount(): Int {
        return if (recordList != null) recordList.size else 0
    }

    class ViewHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField
        val deviceName: TextView = itemView.findViewById(R.id.device_name)

        @JvmField
        val loginType: TextView = itemView.findViewById(R.id.login_type)

        @JvmField
        val loginTime: TextView = itemView.findViewById(R.id.login_time)

        @JvmField
        val location: TextView = itemView.findViewById(R.id.location)

        @JvmField
        val ip: TextView = itemView.findViewById(R.id.ip)
    }
}
