package com.RobinNotBad.BiliClient.adapter.article

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.listener.OnItemLongClickListener
import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.util.TerminalContext

import java.util.ArrayList

class ArticleCardAdapter(val context: Context, val articleCardList: ArrayList<ArticleCard>) : RecyclerView.Adapter<ArticleCardHolder>() {

    var longClickListener: OnItemLongClickListener? = null

    fun setOnLongClickListener(listener: OnItemLongClickListener?) {
        this.longClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleCardHolder {
        val view = LayoutInflater.from(this.context).inflate(R.layout.cell_article_list, parent, false)
        return ArticleCardHolder(view)
    }

    override fun onBindViewHolder(holder: ArticleCardHolder, position: Int) {
        if (position < 0 || position >= articleCardList.size)
            return
        val articleCard = articleCardList[position] ?: return

        holder.showArticleCard(articleCard, context)

        holder.itemView.setOnClickListener {
            TerminalContext.getInstance().enterArticleDetailPage(context, articleCard.id)
        }

        holder.itemView.setOnLongClickListener {
            val listener = longClickListener
            if (listener != null) {
                listener.onItemLongClick(position)
                true
            } else {
                false
            }
        }
    }

    override fun getItemCount(): Int {
        return articleCardList.size
    }
}
