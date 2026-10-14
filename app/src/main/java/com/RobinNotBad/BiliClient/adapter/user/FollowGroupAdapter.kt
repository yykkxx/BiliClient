package com.RobinNotBad.BiliClient.adapter.user

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.user.info.UserInfoActivity
import com.RobinNotBad.BiliClient.model.FollowTag
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import java.util.ArrayList
import java.util.HashMap

class FollowGroupAdapter(val context: Context) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface OnGroupExpandListener {
        fun onGroupExpand(tagid: Int)
    }

    fun setOnGroupExpandListener(listener: OnGroupExpandListener?) {
        this.expandListener = listener
    }

    fun addGroup(tag: FollowTag, users: MutableList<UserInfo>) {
        groupList.add(GroupItem(tag, users))
        expandedMap[tag.tagid] = false
    }

    fun updateGroupUsers(tagid: Int, users: MutableList<UserInfo>) {
        for (group in groupList) {
            if (group.tag.tagid == tagid) {
                group.users.clear()
                group.users.addAll(users)
                notifyDataSetChanged()
                break
            }
        }
    }

    fun addGroupUsers(tagid: Int, users: MutableList<UserInfo>) {
        for (group in groupList) {
            if (group.tag.tagid == tagid) {
                group.users.addAll(users)
                notifyDataSetChanged()
                break
            }
        }
    }

    fun toggleGroup(tagid: Int) {
        val expanded = expandedMap[tagid]
        if (expanded != null) {
            val newExpanded = !expanded
            expandedMap[tagid] = newExpanded

            if (newExpanded) {
                for (group in groupList) {
                    if (group.tag.tagid == tagid && group.users.isEmpty() && expandListener != null) {
                        expandListener!!.onGroupExpand(tagid)
                        break
                    }
                }
            }

            var groupPosition = -1
            var currentPos = 0
            for (group in groupList) {
                if (group.tag.tagid == tagid) {
                    groupPosition = currentPos
                    break
                }
                currentPos++
                val isExpanded = expandedMap[group.tag.tagid]
                if (isExpanded != null && isExpanded) {
                    currentPos += group.users.size
                }
            }

            if (groupPosition >= 0) {
                notifyItemChanged(groupPosition)
                var group: GroupItem? = null
                for (g in groupList) {
                    if (g.tag.tagid == tagid) {
                        group = g
                        break
                    }
                }
                if (group != null && group.users.isNotEmpty()) {
                    if (newExpanded) {
                        notifyItemRangeInserted(groupPosition + 1, group.users.size)
                    } else {
                        notifyItemRangeRemoved(groupPosition + 1, group.users.size)
                    }
                }
            } else {
                notifyDataSetChanged()
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        var currentPos = 0

        for (group in groupList) {
            if (currentPos == position) {
                return TYPE_GROUP
            }
            currentPos++

            val expanded = expandedMap[group.tag.tagid]
            if (expanded != null && expanded) {
                val userCount = group.users.size
                if (position >= currentPos && position < currentPos + userCount) {
                    return TYPE_USER
                }
                currentPos += userCount
            }
        }

        return TYPE_GROUP
    }

    private fun getGroupForPosition(position: Int): GroupItem? {
        var currentPos = 0
        for (group in groupList) {
            if (currentPos == position) {
                return group
            }
            currentPos++

            val expanded = expandedMap[group.tag.tagid]
            if (expanded != null && expanded) {
                val userCount = group.users.size
                if (position >= currentPos && position < currentPos + userCount) {
                    return group
                }
                currentPos += userCount
            }
        }
        if (groupList.isNotEmpty()) {
            return groupList[groupList.size - 1]
        }
        return null
    }

    private fun getUserForPosition(position: Int): UserInfo? {
        var currentPos = 0
        for (group in groupList) {
            currentPos++

            val expanded = expandedMap[group.tag.tagid]
            if (expanded != null && expanded) {
                val userCount = group.users.size
                if (position >= currentPos && position < currentPos + userCount) {
                    val userIndex = position - currentPos
                    return group.users[userIndex]
                }
                currentPos += userCount
            }
        }
        return null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_GROUP) {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_follow_group, parent, false)
            GroupHolder(view)
        } else {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_user_list, parent, false)
            UserHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < 0 || position >= itemCount) return

        if (holder is GroupHolder) {
            val group = getGroupForPosition(position)
            if (group != null) {
                val groupHolder = holder
                groupHolder.groupName.text = group.tag.name ?: ""
                groupHolder.groupCount.text = group.tag.count.toString() + " 位成员"

                val expanded = expandedMap[group.tag.tagid]
                val targetRotation = if (expanded != null && expanded) 90f else 0f
                animateRotation(groupHolder.expandIcon, targetRotation)

                groupHolder.itemView.setOnClickListener { toggleGroup(group.tag.tagid) }
            }
        } else if (holder is UserHolder) {
            val user = getUserForPosition(position)
            if (user != null) {
                val userHolder = holder

                userHolder.name.text = user.name ?: ""
                if (user.vip_nickname_color != null && user.vip_nickname_color!!.isNotEmpty()) {
                    try {
                        userHolder.name.setTextColor(Color.parseColor(user.vip_nickname_color))
                    } catch (e: IllegalArgumentException) {
                        userHolder.name.setTextColor(Color.WHITE)
                    }
                }
                userHolder.desc.text = user.sign ?: ""

                if (user.avatar == null || user.avatar!!.isEmpty()) {
                    userHolder.avatar.visibility = View.GONE
                    userHolder.desc.setSingleLine(false)
                } else {
                    Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(user.avatar!!))
                        .transition(GlideUtil.getTransitionOptions())
                        .placeholder(R.mipmap.akari)
                        .apply(RequestOptions.circleCropTransform())
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(userHolder.avatar)
                    userHolder.avatar.visibility = View.VISIBLE
                    userHolder.desc.setSingleLine(true)
                }

                if (user.mid != -1L) {
                    userHolder.itemView.setOnClickListener {
                        val intent = Intent()
                            .setClass(context, UserInfoActivity::class.java)
                            .putExtra("mid", user.mid)
                        context.startActivity(intent)
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int {
        var count = groupList.size
        for (group in groupList) {
            val expanded = expandedMap[group.tag.tagid]
            if (expanded != null && expanded) {
                count += group.users.size
            }
        }
        return count
    }

    private fun animateRotation(imageView: ImageView, targetRotation: Float) {
        val animator = ObjectAnimator.ofFloat(imageView, "rotation", imageView.rotation, targetRotation)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    class GroupHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val groupName: TextView = itemView.findViewById(R.id.groupName)
        val groupCount: TextView = itemView.findViewById(R.id.groupCount)
        val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)
    }

    class UserHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.userName)
        val desc: TextView = itemView.findViewById(R.id.userDesc)
        val avatar: ImageView = itemView.findViewById(R.id.userAvatar)
    }

    private class GroupItem(val tag: FollowTag, val users: MutableList<UserInfo>)

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_USER = 1
    }

    private val groupList: MutableList<GroupItem> = ArrayList()
    private val expandedMap: MutableMap<Int, Boolean> = HashMap()
    private var expandListener: OnGroupExpandListener? = null
}
