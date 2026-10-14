package com.RobinNotBad.BiliClient.adapter.user

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.UserInfo
import java.util.ArrayList

//视频详情页的联合投稿列表
//尝试用了extend，两边代码基本完全一样只是布局差别而已
//2024-06-13

class UpListAdapter(context: Context, userList: ArrayList<UserInfo>?) :
    UserListAdapter(context, userList) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserListAdapter.Holder {
        val view = LayoutInflater.from(context).inflate(R.layout.cell_up_list, parent, false)
        return Holder(view)
    }

}
