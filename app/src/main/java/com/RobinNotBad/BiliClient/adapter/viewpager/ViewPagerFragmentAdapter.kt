package com.RobinNotBad.BiliClient.adapter.viewpager

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter

//ViewPagerAdapter，适用于各类需要翻页的场景

class ViewPagerFragmentAdapter(val fm: FragmentManager, private val fragmentList: List<Fragment>) : FragmentStatePagerAdapter(fm) {

    override fun getItem(position: Int): Fragment {
        if (position < 0 || position >= fragmentList.size) {
            return Fragment()
        }
        return fragmentList[position]
    }

    override fun getCount(): Int {
        return if (fragmentList != null) fragmentList.size else 0
    }

    /*
     * @NonNull
     *
     * @Override
     * public Object instantiateItem(@NonNull ViewGroup container, int position) {
     * Fragment fragment = (Fragment) super.instantiateItem(container, position);
     * this.fm.beginTransaction().show(fragment).commit();
     * return fragment;
     * }
     *
     * @Override
     * public void destroyItem(@NonNull ViewGroup container, int position, @NonNull
     * Object object) {
     * this.fm.beginTransaction().hide(fragmentList.get(position)).commit();
     * }
     */
}
