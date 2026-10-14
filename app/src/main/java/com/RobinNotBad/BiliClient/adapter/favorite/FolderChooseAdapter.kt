package com.RobinNotBad.BiliClient.adapter.favorite

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil
import com.google.android.material.card.MaterialCardView

import org.json.JSONException

import java.io.IOException
import java.util.ArrayList

//选择收藏夹
//2023-08-28

class FolderChooseAdapter(context: Context?, folderList: ArrayList<String>, fidList: ArrayList<Long>, chooseState: ArrayList<Boolean>, aid: Long) : RecyclerView.Adapter<FolderChooseAdapter.FolderHolder>() {

    val context: Context? = context
    val folderList: ArrayList<String> = folderList
    val chooseState: ArrayList<Boolean> = chooseState
    val fidList: ArrayList<Long> = fidList
    val aid: Long = aid
    var adding: Boolean = false
    @JvmField
    var added: Boolean = false
    @JvmField
    var changed: Boolean = false

    @NonNull
    override fun onCreateViewHolder(@NonNull parent: ViewGroup, viewType: Int): FolderHolder {
        val view = LayoutInflater.from(this.context).inflate(R.layout.cell_choose, parent, false)
        return FolderHolder(view)
    }

    override fun onBindViewHolder(@NonNull holder: FolderHolder, position: Int) {
        if (position < 0 || position >= folderList.size) return
        if (position >= chooseState.size || position >= fidList.size) return

        val cardView = holder.itemView as MaterialCardView

        holder.folder_name.setText(folderList[position])
        setCardView(cardView, chooseState[position])

        holder.itemView.setOnClickListener {
            if (!adding && position < chooseState.size && position < fidList.size) {
                adding = true
                cardView.setStrokeColor(context!!.resources.getColor(R.color.low_pink))
                cardView.setStrokeWidth(ToolsUtil.dp2px(1f))

                if (chooseState[position]) {
                    CenterThreadPool.run {
                        try {
                            val result = FavoriteApi.deleteFavorite(aid, fidList[position])
                            adding = false
                            if (result == 0) {
                                chooseState[position] = false
                                (context as Activity).runOnUiThread { setCardView(cardView, false) }
                                changed = true
                            } else {
                                (context as Activity).runOnUiThread {
                                    MsgUtil.showMsg("删除失败！错误码：" + result)
                                    setCardView(cardView, true)
                                }
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    cardView.setStrokeColor(context!!.resources.getColor(R.color.gray))
                    cardView.setStrokeWidth(ToolsUtil.dp2px(0.1f))
                    CenterThreadPool.run {
                        try {
                            val result = FavoriteApi.addFavorite(aid, fidList[position])
                            adding = false
                            if (result == 0) {
                                chooseState[position] = true
                                (context as Activity).runOnUiThread { setCardView(cardView, true) }
                                changed = true
                                added = true
                            } else {
                                (context as Activity).runOnUiThread {
                                    MsgUtil.showMsg("添加失败！错误码：" + result)
                                    setCardView(cardView, false)
                                }
                            }
                            if (SharedPreferencesUtil.getBoolean("fav_single", false)) (context as Activity).finish()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

    }

    override fun getItemCount(): Int {
        return folderList.size
    }

    class FolderHolder(@NonNull itemView: View) : RecyclerView.ViewHolder(itemView) {
        val folder_name: TextView

        init {
            folder_name = itemView.findViewById(R.id.text)
        }
    }

    private fun setCardView(cardView: MaterialCardView, bool: Boolean) {
        if (bool) {
            cardView.setStrokeColor(context!!.resources.getColor(R.color.pink))
            cardView.setStrokeWidth(ToolsUtil.dp2px(1f))
        } else {
            cardView.setStrokeColor(context!!.resources.getColor(R.color.gray))
            cardView.setStrokeWidth(ToolsUtil.dp2px(0.1f))
        }
    }

    fun isAllDeleted(): Boolean {
        for (b in chooseState) {
            if (b) return false
        }
        return true
    }
}
