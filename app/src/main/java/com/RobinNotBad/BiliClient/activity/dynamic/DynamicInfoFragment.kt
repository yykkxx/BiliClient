package com.RobinNotBad.BiliClient.activity.dynamic

import android.content.Context
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.base.BaseFragment
import com.RobinNotBad.BiliClient.adapter.dynamic.DynamicHolder
import com.RobinNotBad.BiliClient.model.Dynamic
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalContext

class DynamicInfoFragment : BaseFragment() {
    var dynamic: Dynamic? = null
    var id: Long = 0

    companion object {
        @JvmStatic
        fun newInstance(id: Long): DynamicInfoFragment {
            val fragment = DynamicInfoFragment()
            val args = Bundle()
            args.putLong("id", id)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bundle = arguments ?: return
        id = bundle.getLong("id", 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_empty, container, false)
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        TerminalContext.getInstance().getDynamicById(id)
                .observe(viewLifecycleOwner, { dynamicResult -> dynamicResult.onSuccess { dynamic ->
                    this.dynamic = dynamic
                    initView(view)
                }.onFailure { e ->
                } })

    }

    private fun initView(view: View) {

        val scrollView = view.findViewById<ScrollView>(R.id.scrollView)
        val dynamic = this.dynamic!!

        if (SharedPreferencesUtil.getBoolean("ui_landscape", false)) {
            val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val paddings = metrics.widthPixels / 6
            scrollView.setPadding(paddings, 0, paddings, 0)
        }

        val dynamicView = View.inflate(requireContext(), R.layout.cell_dynamic, scrollView)
        val holder = DynamicHolder(dynamicView, activity as BaseActivity, false)
        holder.showDynamic(requireContext(), dynamic, false)
        val onDeleteLongClick = DynamicHolder.getDeleteListener(requireActivity(), dynamic)
        holder.item_dynamic_delete!!.setOnLongClickListener(onDeleteLongClick)
        if (dynamic.canDelete) holder.item_dynamic_delete!!.visibility = View.VISIBLE

        if (dynamic.dynamic_forward != null) {
            Log.e("debug", "有子动态！")
            val childCard = holder.cell_dynamic_child
            val childHolder = DynamicHolder(childCard!!, activity as BaseActivity, true)
            childHolder.showDynamic(requireContext(), dynamic.dynamic_forward!!, true)
            childCard.visibility = View.VISIBLE
        }

    }

}
