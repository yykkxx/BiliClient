package com.RobinNotBad.BiliClient.activity.base

import android.content.Context
import androidx.fragment.app.Fragment
import com.RobinNotBad.BiliClient.BiliTerminal

open class BaseFragment : Fragment() {
    open fun runOnUiThread(runnable: Runnable) {
        if (isAdded()) requireActivity().runOnUiThread(runnable)
    }

    open fun getAppContext(): Context {
        return BiliTerminal.context
    }
}
