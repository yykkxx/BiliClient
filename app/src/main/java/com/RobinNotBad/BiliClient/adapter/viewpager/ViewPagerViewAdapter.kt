package com.RobinNotBad.BiliClient.adapter.viewpager

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter

//图片查看专用Adapter

class ViewPagerViewAdapter(private val viewList: List<out View>) : PagerAdapter() {

    override fun getCount(): Int {
        return if (viewList != null) viewList.size else 0
    }

    override fun isViewFromObject(view: View, obj: Any): Boolean {
        return view === obj
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        if (viewList == null || position < 0 || position >= viewList.size) {
            return View(container.context)
        }
        val view: View? = viewList[position]
        if (view != null && view.parent == null) {
            container.addView(view)
        }
        return view ?: View(container.context)
    }

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        if (obj is View) {
            container.removeView(obj)
        }
    }
}
