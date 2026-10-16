package com.RobinNotBad.BiliClient.adapter.dynamic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.message.PrivateMsgActivity
import com.RobinNotBad.BiliClient.activity.user.FollowUsersActivity
import com.RobinNotBad.BiliClient.activity.user.MedalWallActivity
import com.RobinNotBad.BiliClient.adapter.user.ElectricUserAdapter
import com.RobinNotBad.BiliClient.api.ElectricApi
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.model.ElectricPanel
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.ui.widget.RadiusBackgroundSpan
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.ThemeUtil
import com.RobinNotBad.BiliClient.util.TerminalContext
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

//用户信息页专用Adapter 独立出来也是为了做首项不同

class UserDynamicAdapter(private val context: Context, private val dynamicList: ArrayList<Dynamic>?, private val userInfo: UserInfo) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val view: View = LayoutInflater.from(context).inflate(R.layout.cell_user_info, parent, false)
            UserInfoHolder(view)
        } else {
            val view: View = LayoutInflater.from(context).inflate(R.layout.cell_dynamic, parent, false)
            DynamicHolder(view, context as BaseActivity, false)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(@NonNull holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is DynamicHolder) {
            val realPosition = position - 1
            if (realPosition < 0 || realPosition >= dynamicList!!.size) return

            val dynamic = dynamicList[realPosition] ?: return

            val dynamicHolder = holder as DynamicHolder
            dynamicHolder.showDynamic(context, dynamic, true)

            val forward = dynamic.dynamic_forward
            if (forward != null) {
                val childCard = dynamicHolder.cell_dynamic_child
                if (dynamicHolder.childDynamicHolder == null) {
                    dynamicHolder.childDynamicHolder = DynamicHolder(childCard!!, context as BaseActivity, true)
                }
                dynamicHolder.childDynamicHolder!!.showDynamic(context, forward, true)
                dynamicHolder.cell_dynamic_child!!.visibility = View.VISIBLE
            } else {
                dynamicHolder.cell_dynamic_child!!.visibility = View.GONE
            }

            val onDeleteLongClick = DynamicHolder.getDeleteListener(context as Activity,
                    dynamicList!!, realPosition, this)
            dynamicHolder.item_dynamic_delete!!.setOnLongClickListener(onDeleteLongClick)
            if (dynamic.canDelete)
                dynamicHolder.item_dynamic_delete!!.visibility = View.VISIBLE
        }
        if (holder is UserInfoHolder) {
            (holder as UserInfoHolder).bind(context, userInfo)
        }
    }

    override fun onViewRecycled(@NonNull holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        return if (dynamicList != null) dynamicList.size + 1 else 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) 0 else 1
    }

    class UserInfoHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val userName: TextView = itemView.findViewById(R.id.userName)
        val userFollowings: TextView = itemView.findViewById(R.id.userFollowings)
        val userLevel: TextView = itemView.findViewById(R.id.userLevel)
        val userFans: TextView = itemView.findViewById(R.id.userFollowers)
        val userMedal: TextView = itemView.findViewById(R.id.userMedal)
        val userDesc: TextView = itemView.findViewById(R.id.userDesc)
        val userNotice: TextView = itemView.findViewById(R.id.userNotice)
        val userOfficial: TextView = itemView.findViewById(R.id.userOfficial)
        val exclusiveTipLabel: TextView = itemView.findViewById(R.id.exclusiveTipLabel)
        val liveRoomLabel: TextView = itemView.findViewById(R.id.liveRoomLabel)
        val electricPanelHeader: TextView = itemView.findViewById(R.id.electricPanelHeader)
        val exclusiveTip: MaterialCardView = itemView.findViewById(R.id.exclusiveTip)
        val liveRoom: MaterialCardView = itemView.findViewById(R.id.liveRoom)
        val electricPanel: MaterialCardView = itemView.findViewById(R.id.electricPanel)
        val userAvatar: ImageView = itemView.findViewById(R.id.userAvatar)
        val officialIcon: ImageView = itemView.findViewById(R.id.officialIcon)
        val uidTv: TextView = itemView.findViewById(R.id.uidText)
        val followBtn: MaterialButton = itemView.findViewById(R.id.followBtn)
        val msgBtn: MaterialButton = itemView.findViewById(R.id.msgBtn)
        val contractBtn: MaterialButton = itemView.findViewById(R.id.contractBtn)
        val electricUserList: RecyclerView = itemView.findViewById(R.id.electricUserList)
        val electricPanelDivider: View = itemView.findViewById(R.id.electricPanelDivider)
        val divider: View = itemView.findViewById(R.id.divider)

        var notice_expand: Boolean = false
        var desc_expand: Boolean = false
        var electric_expand: Boolean = false

        init {
            StringUtil.setCopy(userDesc, userNotice)
        }

        fun setFollowed(followed: Boolean) {
            msgBtn.visibility = if (followed) View.VISIBLE else View.GONE
            followBtn.backgroundTintList = ColorStateList
                    .valueOf(if (followed) Color.argb(0xDD, 0x26, 0x26, 0x26) else Color.argb(0xFE, 0xF0, 0x5D, 0x8E))
            followBtn.text = if (followed) "已关注" else "关注"
        }

        @SuppressLint("SetTextI18n")
        fun bind(context: Context, userInfo: UserInfo) {
            val lvStr = SpannableStringBuilder("Lv" + userInfo.level)
            lvStr.setSpan(StringUtil.getLevelBadge(context, userInfo), 0, lvStr.length,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (userInfo.vip_role > 0) {
                val vipTypeMap = LinkedHashMap<Int, String>()
                vipTypeMap[1] = "月度大会员"
                vipTypeMap[3] = "年度大会员"
                vipTypeMap[7] = "十年大会员"
                vipTypeMap[15] = "百年大会员"
                lvStr.append("  ").append(vipTypeMap[userInfo.vip_role]).append(" ")
                lvStr.setSpan(
                        RadiusBackgroundSpan(1, context.resources.getDimension(R.dimen.card_round).toInt(),
                                Color.WHITE, ThemeUtil.mapLegacyColor(0xFFCF4B5F.toInt())),
                        ("Lv" + userInfo.level).length + 1, lvStr.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
            this.userLevel.text = lvStr
            val nicknameColor = userInfo.vip_nickname_color ?: ""
            if (nicknameColor.isNotEmpty())
                this.userName.setTextColor(Color.parseColor(nicknameColor))
            this.userName.text = userInfo.name
            this.userDesc.text = userInfo.sign
            if (!(userInfo.notice ?: "").isEmpty())
                this.userNotice.text = userInfo.notice
            else
                this.userNotice.visibility = View.GONE
            this.uidTv.text = userInfo.mid.toString()
            StringUtil.setCopy(this.uidTv)
            StringUtil.setLink(this.userDesc, this.userNotice)
            this.userFans.text = StringUtil.toWan(userInfo.fans.toLong()) + "粉丝"
            this.userFans.setOnClickListener(
                    { view -> view.context.startActivity(Intent(view.context, FollowUsersActivity::class.java)
                            .putExtra("mode", 1).putExtra("mid", userInfo.mid)) })

            this.userMedal.setOnClickListener(
                    { view -> view.context.startActivity(Intent(view.context, MedalWallActivity::class.java)
                            .putExtra("mid", userInfo.mid)) })

            this.userFollowings.text = StringUtil.toWan(userInfo.following.toLong()) + "关注"
            this.userFollowings.setOnClickListener(
                    { view -> view.context.startActivity(Intent(view.context, FollowUsersActivity::class.java)
                            .putExtra("mode", 0).putExtra("mid", userInfo.mid)) })

            if (userInfo.official != 0) {
                this.officialIcon.visibility = View.VISIBLE
                this.userOfficial.visibility = View.VISIBLE
                val official_signs = arrayOf("哔哩哔哩不知名UP主", "哔哩哔哩知名UP主", "哔哩哔哩大V达人", "哔哩哔哩企业认证",
                        "哔哩哔哩组织认证", "哔哩哔哩媒体认证", "哔哩哔哩政府认证", "哔哩哔哩高能主播", "社会不知名人士", "社会知名人士")
                val officialDesc = userInfo.officialDesc ?: ""
                this.userOfficial.text = official_signs[userInfo.official] +
                        (if (officialDesc.isEmpty()) "" else "\n" + officialDesc)
            } else {
                this.officialIcon.visibility = View.GONE
                this.userOfficial.visibility = View.GONE
            }

            Glide.with(BiliTerminal.context).asDrawable().load(GlideUtil.url(userInfo.avatar ?: ""))
                    .transition(GlideUtil.getTransitionOptions())
                    .placeholder(R.mipmap.akari)
                    .apply(RequestOptions.circleCropTransform())
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(this.userAvatar)

            this.userAvatar.setOnClickListener { view ->
                val intent = Intent()
                intent.setClass(context, ImageViewerActivity::class.java)
                val imageList = ArrayList<String>()
                imageList.add(userInfo.avatar ?: "")
                intent.putExtra("imageList", imageList)
                context.startActivity(intent)
            }

            if (!(userInfo.sys_notice ?: "").isEmpty()) {
                this.exclusiveTip.visibility = View.VISIBLE
                val spannableString = SpannableString("!:" + (userInfo.sys_notice ?: ""))
                val drawable = StringUtil.getDrawable(context, R.drawable.icon_warning)
                drawable.setBounds(0, 0, 30, 30)
                spannableString.setSpan(ImageSpan(drawable), 0, 2, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                this.exclusiveTipLabel.text = spannableString
            } else
                this.exclusiveTip.visibility = View.GONE

            val liveRoomInfo = userInfo.live_room
            if (liveRoomInfo != null) {
                this.liveRoom.visibility = View.VISIBLE
                this.liveRoomLabel.text = liveRoomInfo.title
                this.liveRoom.setOnClickListener(
                        { view -> TerminalContext.getInstance().enterLiveDetailPage(context, liveRoomInfo.roomid) })
            } else
                this.liveRoom.visibility = View.GONE

            if ((userInfo.mid == SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0))
                    || (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) || (userInfo.mid == 0L))
                this.followBtn.visibility = View.GONE
            else
                this.followBtn.isChecked = userInfo.followed
            this.followBtn.setOnClickListener { btn ->
                followBtn.isEnabled = false
                this.setFollowed(!(userInfo.followed))
                CenterThreadPool.run(Runnable {
                    try {
                        val result = UserInfoApi.followUser(userInfo.mid, !(userInfo.followed))
                        val msg: String
                        if (result == 0) {
                            userInfo.followed = !(userInfo.followed)
                            msg = "操作成功喵~"
                        } else {
                            CenterThreadPool.runOnUiThread(Runnable { this.setFollowed(userInfo.followed) })
                            if (result == 22015)
                                msg = "被B站风控系统拦截了\n（无法解决，详见公告）"
                            else
                                msg = "操作失败（原因未知）：" + result
                        }
                        MsgUtil.showMsg(msg)
                    } catch (e: Exception) {
                        MsgUtil.err(e)
                    }
                    CenterThreadPool.runOnUiThread(Runnable { followBtn.isEnabled = true })
                })
            }

            this.setFollowed(userInfo.followed)

            this.msgBtn.setOnClickListener { view ->
                val intent = Intent(context, PrivateMsgActivity::class.java)
                intent.putExtra("uid", userInfo.mid)
                context.startActivity(intent)
            }

            val currentMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0)
            if (userInfo.mid != currentMid && currentMid != 0L && userInfo.mid != 0L && userInfo.is_follow_display) {
                this.contractBtn.visibility = View.VISIBLE
                val layout = itemView as ConstraintLayout
                val constraintSet = ConstraintSet()
                constraintSet.clone(layout)
                constraintSet.connect(R.id.divider, ConstraintSet.TOP, R.id.contractBtn, ConstraintSet.BOTTOM)
                constraintSet.applyTo(layout)
                this.contractBtn.setOnClickListener { view ->
                    contractBtn.isEnabled = false
                    CenterThreadPool.run(Runnable {
                        try {
                            val result = UserInfoApi.addContract(userInfo.mid)
                            val msg: String
                            if (result == 0) {
                                msg = "加入成功"
                            } else if (result == 158001) {
                                msg = "不满足条件"
                            } else {
                                msg = "操作失败：" + result
                            }
                            MsgUtil.showMsg(msg)
                        } catch (e: Exception) {
                            MsgUtil.err(e)
                        }
                        CenterThreadPool.runOnUiThread(Runnable { contractBtn.isEnabled = true })
                    })
                }
            } else {
                this.contractBtn.visibility = View.GONE
                val layout = itemView as ConstraintLayout
                val constraintSet = ConstraintSet()
                constraintSet.clone(layout)
                constraintSet.connect(R.id.divider, ConstraintSet.TOP, R.id.followBtn, ConstraintSet.BOTTOM)
                constraintSet.applyTo(layout)
            }

            this.userDesc.setOnClickListener { view1 ->
                if (desc_expand)
                    this.userDesc.maxLines = 2
                else
                    this.userDesc.maxLines = 32
                desc_expand = !desc_expand
            }

            this.userNotice.setOnClickListener { view1 ->
                if (notice_expand)
                    this.userNotice.maxLines = 2
                else
                    this.userNotice.maxLines = 32
                notice_expand = !notice_expand
            }

            // 加载充电公示数据
            loadElectricPanel(context, userInfo)

        }

        /**
         * 加载充电公示数据
         */
        private fun loadElectricPanel(context: Context, userInfo: UserInfo) {
            this.electricPanel.visibility = View.GONE

            CenterThreadPool.run(Runnable {
                try {
                    val panel: ElectricPanel? = ElectricApi.getElectricPanel(userInfo.mid)

                    if (panel != null && panel.hasData()) {
                        CenterThreadPool.runOnUiThread(Runnable {
                            this.electricPanel.visibility = View.VISIBLE

                            // 设置头部文本
                            this.electricPanelHeader.text = "充电公示（本月" + panel.count + "人）"

                            // 设置RecyclerView
                            this.electricUserList.layoutManager = LinearLayoutManager(context)
                            val adapter = ElectricUserAdapter(context, panel.list)
                            this.electricUserList.adapter = adapter

                            // 设置点击展开/收起
                            this.electricPanelHeader.setOnClickListener { v ->
                                electric_expand = !electric_expand
                                if (electric_expand) {
                                    this.electricUserList.visibility = View.VISIBLE
                                    this.electricPanelDivider.visibility = View.VISIBLE
                                    this.electricPanelHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                            0, 0, R.drawable.arrow_up, 0)
                                } else {
                                    this.electricUserList.visibility = View.GONE
                                    this.electricPanelDivider.visibility = View.GONE
                                    this.electricPanelHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                            0, 0, R.drawable.arrow_down, 0)
                                }
                            }
                        })
                    }
                } catch (e: Exception) {
                    // 加载失败，不显示充电公示卡片
                    e.printStackTrace()
                }
            })
        }
    }
}
