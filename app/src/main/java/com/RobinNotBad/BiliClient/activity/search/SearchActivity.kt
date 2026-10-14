package com.RobinNotBad.BiliClient.activity.search

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.SearchHistoryAdapter
import com.RobinNotBad.BiliClient.adapter.SearchSuggestionsAdapter
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.helper.TutorialHelper
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.JsonUtil
import com.RobinNotBad.BiliClient.util.LinkUrlUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

import org.json.JSONArray
import org.json.JSONException

import java.util.ArrayList
import java.util.Objects

class SearchActivity : InstanceActivity() {
    private var lastKeyword = "≠~`"
    private var historyRecyclerview: RecyclerView? = null
    private var suggestionsRecyclerview: RecyclerView? = null

    @JvmField
    var searchHistoryAdapter: SearchHistoryAdapter? = null

    @JvmField
    var searchSuggestionsAdapter: SearchSuggestionsAdapter? = null

    @JvmField
    var viewPager: ViewPager2? = null

    @JvmField
    var keywordInput: EditText? = null
    private var searchBar: ConstraintLayout? = null
    private var searchBarVisible = true
    private var refreshing = false
    private var animate_last: Long = 0

    @JvmField
    var handler: Handler? = null

    @JvmField
    var searchHistory: ArrayList<String>? = null

    @JvmField
    var searchSuggestions: ArrayList<String>? = null
    private var suggestionRunnable: Runnable? = null
    private var suggestionsEnabled = false
    private var defaultSearchContent: String? = null
    private var defaultSearchContentEnabled = false

    @JvmField
    var tutorial_show = false

    @JvmField
    var classname: String? = null

    @JvmField
    var specialList = arrayOf("心理疾病", "自杀", "自尽", "自残", "抑郁", "双相", "安眠药")

    @SuppressLint("MissingInflatedId", "NotifyDataSetChanged", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        classname = javaClass.getSimpleName()
        tutorial_show = SharedPreferencesUtil.getBoolean("tutorial_pager_" + classname, true)

        asyncInflate(R.layout.activity_search) { layoutView, resId ->
            Log.e("debug", "进入搜索页")

            TutorialHelper.showTutorialList(this, R.array.tutorial_search, 4)

            handler = Handler()

            suggestionsEnabled = SharedPreferencesUtil.getBoolean("search_suggestions_enable", true)
            defaultSearchContentEnabled = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_DEFAULT_CONTENT_ENABLE, false)

            if (defaultSearchContentEnabled) {
                Thread {
                    try {
                        defaultSearchContent = SearchApi.getDefaultSearchContent()
                        if (defaultSearchContent != null && !defaultSearchContent!!.isEmpty()) {
                            runOnUiThread { keywordInput!!.hint = defaultSearchContent }
                        }
                    } catch (e: Exception) {
                        Log.e("SearchActivity", "获取默认搜索内容失败", e)
                    }
                }.start()
            }

            viewPager = findViewById(R.id.viewPager)

            val searchBtn = findViewById<View>(R.id.search)
            keywordInput = findViewById(R.id.keywordInput)
            searchBar = findViewById(R.id.searchbar)
            historyRecyclerview = findViewById(R.id.history_recyclerview)
            suggestionsRecyclerview = findViewById(R.id.suggestions_recyclerview)
            viewPager!!.offscreenPageLimit = 4

            keywordInput!!.setOnFocusChangeListener { view, b ->
                if (b) {
                    // 获得焦点时，根据输入内容决定显示历史还是建议
                    val keyword = keywordInput!!.getText().toString()
                    if (keyword.isEmpty() || !suggestionsEnabled) {
                        historyRecyclerview!!.visibility = View.VISIBLE
                        suggestionsRecyclerview!!.visibility = View.GONE
                    } else {
                        historyRecyclerview!!.visibility = View.GONE
                        suggestionsRecyclerview!!.visibility = View.VISIBLE
                    }
                } else {
                    // 失去焦点时隐藏所有列表
                    historyRecyclerview!!.visibility = View.GONE
                    suggestionsRecyclerview!!.visibility = View.GONE
                }
            }
            historyRecyclerview!!.visibility = View.VISIBLE
            suggestionsRecyclerview!!.visibility = View.GONE
            val vpfAdapter: FragmentStateAdapter = object : FragmentStateAdapter(this) {
                override fun getItemCount(): Int {
                    return 4
                }

                @NonNull
                override fun createFragment(position: Int): Fragment {
                    if (position == 0) return SearchVideoFragment.newInstance()
                    if (position == 1) return SearchArticleFragment.newInstance()
                    if (position == 2) return SearchUserFragment.newInstance()
                    if (position == 3) return SearchLiveFragment.newInstance()
                    throw IllegalArgumentException(
                            "return value of getItemCount() method maybe not associate with argument position")
                }
            }
            viewPager!!.adapter = vpfAdapter

            viewPager!!.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    if (position != 0) {
                        onScrolled(256) // 让搜索框隐藏
                        if (tutorial_show) {
                            tutorial_show = false
                            findViewById<View>(R.id.text_tutorial_pager).visibility = View.GONE
                            SharedPreferencesUtil.putBoolean("tutorial_pager_" + classname, false)
                        }
                    }

                    val fragmentCurr = supportFragmentManager
                            .findFragmentByTag("f" + viewPager!!.getCurrentItem())
                    if (fragmentCurr != null) {
                        (fragmentCurr as SearchFragment).refresh() // 在fragment里已做判断
                    }
                    super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                }
            })

            searchBtn.setOnClickListener { view -> searchKeyword(keywordInput!!.getText().toString()) }
            searchBtn.setOnLongClickListener { view -> jumpToTargetId(view) }
            keywordInput!!.setOnEditorActionListener { textView, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || event != null &&
                        KeyEvent.KEYCODE_ENTER == event.getKeyCode() && KeyEvent.ACTION_DOWN == event.getAction()) {
                    searchKeyword(keywordInput!!.getText().toString())
                }
                false
            }

            try {
                searchHistory = JsonUtil.jsonToArrayList(
                        JSONArray(SharedPreferencesUtil.getString(SharedPreferencesUtil.search_history, "[]")),
                        false)
            } catch (e: JSONException) {
                runOnUiThread { MsgUtil.err(e) }
                searchHistory = ArrayList()
            }
            searchHistoryAdapter = SearchHistoryAdapter(this, searchHistory!!)
            searchHistoryAdapter!!.setOnClickListener { position -> keywordInput!!.setText(searchHistory!![position]) }
            searchHistoryAdapter!!.setOnLongClickListener { position ->
                MsgUtil.showMsg("删除成功")
                searchHistory!!.removeAt(position)
                searchHistoryAdapter!!.notifyItemRemoved(position)
                searchHistoryAdapter!!.notifyItemRangeChanged(position, searchHistory!!.size - position)
                SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                        JSONArray(searchHistory).toString())
            }
            historyRecyclerview!!.layoutManager = CustomLinearManager(this)
            historyRecyclerview!!.adapter = searchHistoryAdapter
            if (searchHistory!!.size > 4) {
                historyRecyclerview!!.isFocusable = true
                historyRecyclerview!!.isFocusableInTouchMode = true
                historyRecyclerview!!.requestFocus()
            }

            // 初始化搜索建议
            searchSuggestions = ArrayList()
            searchSuggestionsAdapter = SearchSuggestionsAdapter(this, searchSuggestions!!)
            searchSuggestionsAdapter!!.setOnClickListener { position ->
                val suggestion = searchSuggestions!![position]
                keywordInput!!.setText(suggestion)
                keywordInput!!.setSelection(suggestion.length)
                searchKeyword(suggestion)
            }
            suggestionsRecyclerview!!.layoutManager = CustomLinearManager(this)
            suggestionsRecyclerview!!.adapter = searchSuggestionsAdapter

            // 添加输入监听器获取搜索建议
            if (suggestionsEnabled) {
                keywordInput!!.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                    }

                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    }

                    override fun afterTextChanged(s: Editable) {
                        val keyword = s.toString()

                        // 移除之前的请求
                        val runnable1 = suggestionRunnable
                        if (runnable1 != null) {
                            handler!!.removeCallbacks(runnable1)
                        }

                        if (keyword.isEmpty()) {
                            // 输入为空时显示历史记录
                            runOnUiThread {
                                if (keywordInput!!.hasFocus()) {
                                    historyRecyclerview!!.visibility = View.VISIBLE
                                    suggestionsRecyclerview!!.visibility = View.GONE
                                }
                            }
                        } else {
                            suggestionRunnable = Runnable {
                                Thread {
                                    try {
                                        val suggestions = SearchApi.getSearchSuggestions(keyword)
                                        runOnUiThread {
                                            if (keywordInput!!.hasFocus()) {
                                                searchSuggestions!!.clear()
                                                searchSuggestions!!.addAll(suggestions)
                                                searchSuggestionsAdapter!!.notifyDataSetChanged()

                                                if (!suggestions.isEmpty()) {
                                                    historyRecyclerview!!.visibility = View.GONE
                                                    suggestionsRecyclerview!!.visibility = View.VISIBLE
                                                } else {
                                                    historyRecyclerview!!.visibility = View.VISIBLE
                                                    suggestionsRecyclerview!!.visibility = View.GONE
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("SearchActivity", "获取搜索建议失败", e)
                                    }
                                }.start()
                            }
                            handler!!.postDelayed(suggestionRunnable!!, 300)
                        }
                    }
                })
            }

            if (getIntent().getStringExtra("keyword") != null) {
                findViewById<View>(R.id.top).setOnClickListener { view1 -> finish() }
                keywordInput!!.setText(getIntent().getStringExtra("keyword"))
                MsgUtil.showMsg("可点击标题栏返回详情页")
            }
        }
    }

    fun jumpToTargetId(view: View): Boolean {
        val text = keywordInput!!.getText().toString()
        LinkUrlUtil.handleId(this, text)
        return true
    }

    @SuppressLint("NotifyDataSetChanged")
    fun searchKeyword(str: String) {
        var str = str
        if (str.contains("Robin") || str.contains("robin")) {
            if (str.contains("撅")) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_special))
                return
            }
            if (str.contains("纳西妲")) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_robin_nahida))
                return
            }
        }
        for (s in specialList) {
            if (str.contains(s)) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_warmwords_warmworld))
                break
            }
        }

        if (!refreshing) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            var curFocus: View?
            curFocus = getCurrentFocus()
            if (curFocus != null) {
                manager.hideSoftInputFromWindow(curFocus.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
            }

            if (str.isEmpty()) {
                if (defaultSearchContentEnabled && defaultSearchContent != null && !defaultSearchContent!!.isEmpty()) {
                    str = defaultSearchContent as String
                } else {
                    runOnUiThread { MsgUtil.showMsg("还没输入内容喵~") }
                    return
                }
            }

            if (Objects.equals(lastKeyword, str)) {
                runOnUiThread {
                    keywordInput!!.clearFocus()
                    historyRecyclerview!!.visibility = View.GONE
                }
            } else {
                refreshing = true
                lastKeyword = str

                // 搜索记录
                runOnUiThread {
                    historyRecyclerview!!.visibility = View.GONE
                    keywordInput!!.clearFocus()
                }

                if (!searchHistory!!.contains(str)) {
                    try {
                        searchHistory!!.add(0, str)
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                                JSONArray(searchHistory).toString())
                        runOnUiThread {
                            searchHistoryAdapter!!.notifyItemInserted(0)
                            searchHistoryAdapter!!.notifyItemRangeChanged(0, searchHistory!!.size)
                            historyRecyclerview!!.scrollToPosition(0)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err(e) }
                    }
                } else {
                    try {
                        val pos = searchHistory!!.indexOf(str)
                        searchHistory!!.remove(str)
                        searchHistory!!.add(0, str)
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                                JSONArray(searchHistory).toString())
                        runOnUiThread {
                            searchHistoryAdapter!!.notifyItemMoved(pos, 0)
                            searchHistoryAdapter!!.notifyItemRangeChanged(0, searchHistory!!.size)
                            historyRecyclerview!!.scrollToPosition(0)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err(e) }
                    }
                }

                try {
                    for (i in 0 until 4) {
                        val fragmentById = supportFragmentManager.findFragmentByTag("f" + i)
                        if (fragmentById != null)
                            (fragmentById as SearchFragment).update(str)
                    }
                    val fragmentCurr = supportFragmentManager
                            .findFragmentByTag("f" + viewPager!!.getCurrentItem())
                    if (fragmentCurr != null) {
                        (fragmentCurr as SearchFragment).refresh()
                        requestFragmentFocus()
                    }
                } catch (e: Exception) {
                    report(e)
                }
                refreshing = false

                if (tutorial_show) {
                    runOnUiThread {
                        val textView = findViewById<TextView>(R.id.text_tutorial_pager)
                        textView.visibility = View.VISIBLE
                        textView.text = getString(R.string.tutorial_pager, 4)
                    }
                }
            }
        }
    }

    fun onScrolled(dy: Int) {
        val height = (searchBar!!.getHeight() + ToolsUtil.dp2px(2f)).toFloat()

        if (System.currentTimeMillis() - animate_last > 200) {
            if (dy > 0 && searchBarVisible) {
                animate_last = System.currentTimeMillis()
                searchBarVisible = false
                @SuppressLint("ObjectAnimatorBinding")
                val animator = ObjectAnimator.ofFloat(searchBar, "translationY", 0f, -height)
                animator.start()
                handler!!.postDelayed({ searchBar!!.visibility = View.GONE }, 200)
            }
            if (dy < -1 && !searchBarVisible) {
                animate_last = System.currentTimeMillis()
                searchBarVisible = true
                searchBar!!.visibility = View.VISIBLE
                @SuppressLint("ObjectAnimatorBinding")
                val animator = ObjectAnimator.ofFloat(searchBar, "translationY", -height, 0f)
                animator.start()
            }
        }

        requestFragmentFocus()
    }

    private fun requestFragmentFocus() {
        val fragmentCurr = supportFragmentManager
                .findFragmentByTag("f" + viewPager!!.getCurrentItem())
        if (fragmentCurr != null) {
            (fragmentCurr as SearchFragment).refresh()
            if (fragmentCurr.getView() != null) {
                val recyclerView = fragmentCurr.getView()!!.findViewById<View>(R.id.recyclerView)
                recyclerView.isFocusable = true
                recyclerView.isFocusableInTouchMode = true
                recyclerView.requestFocus()
            }
        }
    }
}
