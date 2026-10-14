package com.RobinNotBad.BiliClient.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.View
import androidx.annotation.NonNull
import com.RobinNotBad.BiliClient.util.CenterThreadPool.runOnUiThread

object AnimationUtils {

    @JvmStatic
    fun fadeIn(view: View?, duration: Int) {
        if (view == null) return
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LOAD_TRANSITION, true)) {
            view.visibility = View.VISIBLE
            return
        }
        runOnUiThread {
            val fadeIn = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
            fadeIn.duration = duration.toLong()
            view.visibility = View.VISIBLE
            fadeIn.start()
        }
    }

    @JvmStatic
    fun fadeOut(view: View?, duration: Int) {
        if (view == null) return
        runOnUiThread {
            val fadeOut = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)
            fadeOut.duration = duration.toLong()
            fadeOut.start()
            fadeOut.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                }
            })
        }
    }

    @JvmStatic
    fun crossFade(toShow: View?, toHide: View?) {
        crossFade(toShow, toHide, 100)
    }

    @JvmStatic
    fun crossFade(toHide: View?, toShow: View?, duration: Int) {
        runOnUiThread {
            if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.LOAD_TRANSITION, true)) {
                toHide?.visibility = View.GONE
                toShow?.visibility = View.VISIBLE
                return@runOnUiThread
            }
            if (toHide != null) {
                val fadeOut = ObjectAnimator.ofFloat(toHide, "alpha", 1f, 0f)
                fadeOut.duration = duration.toLong()
                fadeOut.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(@NonNull animation: Animator) {
                    }

                    override fun onAnimationEnd(@NonNull animation: Animator) {
                        toHide.visibility = View.GONE
                    }

                    override fun onAnimationCancel(@NonNull animation: Animator) {
                    }

                    override fun onAnimationRepeat(@NonNull animation: Animator) {
                    }
                })
                fadeOut.start()
            }

            if (toShow != null) {
                val showUp = ObjectAnimator.ofFloat(toShow, "alpha", 0f, 1f)
                showUp.duration = duration.toLong()
                toShow.visibility = View.VISIBLE
                showUp.start()
            }
        }
    }
}
