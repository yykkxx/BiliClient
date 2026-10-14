package com.RobinNotBad.BiliClient.util.view

import android.content.Context
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.bumptech.glide.Glide

class ImageAutoLoadScrollListener : RecyclerView.OnScrollListener() {
    override fun onScrolled(@NonNull recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)
    }

    override fun onScrollStateChanged(@NonNull recyclerView: RecyclerView, newState: Int) {
        super.onScrollStateChanged(recyclerView, newState)
        when (newState) {
            SCROLL_STATE_IDLE -> { // The RecyclerView is not currently scrolling.
                try {
                    val context: Context? = recyclerView.context
                    if (context != null)
                        Glide.with(context).resumeRequests()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            SCROLL_STATE_DRAGGING, SCROLL_STATE_SETTLING -> {
                // The RecyclerView is currently being dragged by outside input such as user touch input.
                // The RecyclerView is currently animating to a final position while not under outside control.
                try {
                    val context: Context? = recyclerView.context
                    if (context != null)
                        Glide.with(context).pauseRequests()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    companion object {
        @JvmStatic
        fun install(@NonNull recyclerView: RecyclerView) {
            if (SharedPreferencesUtil.getBoolean("image_no_load_onscroll", false))
                recyclerView.addOnScrollListener(ImageAutoLoadScrollListener())
        }
    }
}
