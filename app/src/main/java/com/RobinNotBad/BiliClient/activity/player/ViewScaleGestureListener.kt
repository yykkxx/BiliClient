package com.RobinNotBad.BiliClient.activity.player

import android.view.View

class ViewScaleGestureListener(view: View) : ScaleGestureDetector.SimpleOnScaleGestureListener() {

    private val view: View = view

    @JvmField
    var scaling: Boolean = false

    @JvmField
    var can_reset: Boolean = false

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        if (view.visibility == View.GONE) {
            return false
        }
        val scaleFactor = detector.scaleFactor

        val currentScale = view.scaleX
        var newScale = currentScale * scaleFactor
        newScale = Math.max(1f, Math.min(5f, newScale))
        view.scaleX = newScale
        view.scaleY = newScale

        can_reset = newScale != 1.0f
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        scaling = true
        return super.onScaleBegin(detector)
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        scaling = false
        super.onScaleEnd(detector)
    }
}
