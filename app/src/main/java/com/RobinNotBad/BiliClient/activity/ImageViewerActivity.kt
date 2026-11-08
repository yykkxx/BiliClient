package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.ImageButton
import android.widget.TextView
import androidx.viewpager.widget.ViewPager

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerViewAdapter
import com.RobinNotBad.BiliClient.ui.widget.PhotoViewpager
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.PhotoView

/**
 * 图片查看页（v5.2.1 重做）。
 *
 * # 用户需求原文
 * 「在点开图片之后的预览界面里面，取消侧滑退出手势（因为这会影响到图片放大），
 * 改为只有点击顶部才能退出。优化图片操作的逻辑，支持双指放大缩小」
 *
 * # 三个关键行为（改动前请先读完"为什么"）
 *
 * ## 1. 侧滑退出必须彻底关掉，且要"双保险"
 * 很反直觉的一点：**Manifest 上早就配了 NoSwipe 主题（`windowSwipeToDismiss=false`），
 * 但侧滑仍然存在。** 真正的元凶是本类 `onCreate` 里的 `setTheme(R.style.Theme_BiliClient)` ——
 * 它在 `super.onCreate` 之后执行，会把 Manifest 上那套主题**覆盖掉**，于是
 * `Theme.BiliClient` 的 `windowSwipeToDismiss=true` 又生效了。
 *
 * 所以这里做两件事：
 * - `Theme.BiliClient.ImagePreview` 继承 `NoSwipe`，再关闭全屏；
 *   运行时恢复系统栏，防止基类色调覆盖层重新开启全屏，其余控件样式保留
 *   （不能用 `Theme.NoSwipe`，它父类是 `android:Theme.Black`，按钮/文字会变丑）；
 * - `setSystemGestureExclusionRects`（API 29+）：把左右边缘的系统返回手势区也屏蔽掉，
 *   否则「从屏幕最左边往右滑」会被系统抢走当成返回，用户正在放大图片时尤其恼火。
 *   这是第二重保险：主题管的是 Activity 的窗口动画手势，系统手势是另一套机制。
 *
 * ## 2. 只有点顶部才退出，点图片绝不退出
 * `setTopbarExit()`（[BaseActivity] 提供）会给 `@+id/top` 挂 `finish()`。
 * 图片区域**不挂任何退出监听** —— 这是用户明确要求，也是相册类 App 的标准手感：
 * 看图时手指几乎总会落在图片上，让它退出是最糟的误触。
 *
 * ## 3. 单击切浮层，且**绝不能**用 setOnDoubleTapListener
 * PhotoView 2.3.0 内置的 `PhotoViewAttacher$3` 是它的 `OnDoubleTapListener`。
 * 一旦你调 `setOnDoubleTapListener(...)`，就把这个内置实现**顶掉了**，
 * **双击缩放会直接失效**（这是读字节码确认过的结论，不是猜的）。
 * 正确做法是用 `setOnViewTapListener(...)`：内置实现会在 `onSingleTapConfirmed` 里
 * 依次回调 `OnClickListener` → `OnViewTapListener` → `OnPhotoTapListener`，
 * 单双击的时序判断由它自己做，我们只管"单击要干什么"，双击缩放完好无损。
 *
 * # 双指缩放（"优化图片操作的逻辑"）
 * - 1x ~ 8x，中档 2.5x；双击沿用 PhotoView 内置的最小/中档/最大档循环；
 * - 放大后可拖动看图，拖到横向边缘且继续向外横滑才翻页；
 * - 垂直拖动、多指缩放及抬指后剩余单指都不翻页（见 [PhotoViewpager]）。
 */
class ImageViewerActivity : BaseActivity() {

    private var longClickTimestamp: Long = 0

    /** 顶部与底部浮层是否可见（单击图片切换）。默认可见，与用户"打开就能看到页码"的预期一致。 */
    private var overlayVisible = true

    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        // 基类套色调时保留强调色，只重申本页的非全屏/禁侧滑窗口属性。
        theme.applyStyle(R.style.ImagePreviewWindow, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在创建窗口前选图片专属主题，同时保留 NoSwipe 与应用控件样式。
        setTheme(R.style.Theme_BiliClient_ImagePreview)
        super.onCreate(savedInstanceState)
        // setTheme 的专属窗口覆盖层保留基类色调；再清除历史系统 UI 标志。
        showSystemBars()
        setContentView(R.layout.activity_image_viewer)

        val imageList = intent.getStringArrayListExtra("imageList")
        if (imageList.isNullOrEmpty()) {
            MsgUtil.showMsg("没有可查看的图片")
            finish()
            return
        }

        // 点顶部退出（BaseActivity 的通用实现：给 @+id/top 挂 finish）
        setTopbarExit()

        // API 29+ 屏蔽左右边缘的系统返回手势，避免"想放大却触发了返回"
        excludeSystemGestureEdges()

        val viewPager = findViewById<PhotoViewpager>(R.id.viewPager)
        val pageText = findViewById<TextView>(R.id.text_page)

        val photoViewList = ArrayList<View>()
        for (i in imageList.indices) {
            val photoView = createPhotoView(imageList[i])
            // 给每页打上页索引：PhotoViewpager 要靠它找到"当前页"来判断是否已放大
            // （不能用 adapter.instantiateItem 找，那会在手势热路径上重复创建 View）。
            photoView.tag = i
            photoViewList.add(photoView)
        }

        viewPager.adapter = ViewPagerViewAdapter(photoViewList)

        // 页码初值：进入页面立刻显示「第1/N张」。
        // 旧实现只在 onPageScrolled 里更新，而首屏并不一定触发滚动回调，
        // 于是用户看到的是 layout 里写死的占位文本（曾是「第0/0张」）。
        setPageText(pageText, 1, imageList.size)

        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            /**
             * 用 onPageSelected 而不是 onPageScrolled 更新页码。
             *
             * 旧实现在 onPageScrolled 里判 `positionOffset % 1 == 0f` —— 那是**浮点比较**：
             * 滑动过程中 positionOffset 是 0.0~1.0 的浮点值，`% 1` 的结果几乎不可能**恰好**等于 0f，
             * 于是只在"完全停住"的偶发时刻才更新，表现为页码跳动/不更新。而 onPageSelected
             * 是语义化事件（选中的页变了），天然对应"页码该变了"。
             */
            override fun onPageSelected(position: Int) {
                setPageText(pageText, position + 1, imageList.size)
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) = Unit

            override fun onPageScrollStateChanged(state: Int) = Unit
        })

        // 下载按钮：连点两次（3 秒内）才真正下载，防止误触。
        val download = findViewById<ImageButton>(R.id.btn_download)
        download.setOnClickListener {
            val timeNow = System.currentTimeMillis()
            if (timeNow - longClickTimestamp < DOUBLE_CLICK_WINDOW_MS) {
                val current = viewPager.currentItem
                val url = imageList.getOrNull(current)
                if (url.isNullOrEmpty()) {
                    MsgUtil.showMsg("这张图片还没加载出来")
                    return@setOnClickListener
                }
                val intent1 = Intent(this, DownloadActivity::class.java)
                    .putExtra("link", url)
                    .putExtra("path", FileUtil.getPicturePath().absolutePath)
                    .putExtra("type", 0)
                startActivity(intent1)
            } else {
                MsgUtil.showMsg("再次点击下载")
            }
            longClickTimestamp = timeNow
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showSystemBars()
    }

    /** 保留系统栏及其布局空间；工具浮层显隐绝不进入沉浸模式。 */
    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        val decor = window.decorView
        val hiddenFlags = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LOW_PROFILE
        decor.systemUiVisibility = decor.systemUiVisibility and hiddenFlags.inv()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, decor).show(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * 创建一页图片的 PhotoView（含缩放档位与单击切浮层）。
     *
     * 抽成独立函数只是为了让 [onCreate] 读起来是"搭页面"而不是"一屏配置"。
     */
    private fun createPhotoView(url: String): PhotoView {
        val photoView = PhotoView(this)
        // 三档倍率：1x（图片适配）/ 2.5x（双击细节档）/ 8x（上限）；捏合连续缩放。
        photoView.setScaleLevels(MIN_SCALE, MEDIUM_SCALE, MAX_SCALE)
        // 放大后左右可以拖动看图；拖到边缘把翻页权还给 ViewPager（配合 PhotoViewpager 的拦截逻辑）
        photoView.setAllowParentInterceptOnEdge(true)
        // 双击缩放的动画时长：200ms 比默认更跟手，又不至于看不出动画
        photoView.setZoomTransitionDuration(ZOOM_TRANSITION_MS)
        // 单击切浮层：用 OnViewTapListener，**绝不用 setOnDoubleTapListener**（理由见类注释）
        photoView.setOnViewTapListener { _, _, _ -> toggleOverlay() }
        loadImage(photoView, url)
        return photoView
    }

    /**
     * 加载图片。
     *
     * `override(Target.SIZE_ORIGINAL)` 是必须的：B 站图床有 `@xxx.jpg` 这类尺寸后缀，
     * Glide 默认会按 View 尺寸降采样，不 override 的话**一放大就糊成马赛克**。
     * `diskCacheStrategy(DiskCacheStrategy.NONE)` 沿用旧行为（看图页不做磁盘缓存，
     * 避免用户看过的每张原图都留在磁盘里）。
     */
    private fun loadImage(photoView: PhotoView, url: String) {
        try {
            Glide.with(this).asDrawable()
                .load(GlideUtil.url_hq(url))
                .transition(GlideUtil.getTransitionOptions())
                .override(Target.SIZE_ORIGINAL)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(photoView)
        } catch (e: OutOfMemoryError) {
            // 原图（尤其长图/超清）在低内存设备上确实会 OOM，这是必须兜住的（旧行为保留）
            MsgUtil.showMsg("超出内存，加载失败")
        } catch (e: Exception) {
            MsgUtil.err("图片查看", e)
        }
    }

    /**
     * 单击图片：切顶部/底部浮层的显隐（相册 App 的标准手感）。
     *
     * 为什么退化成"隐藏顶栏"不是问题：顶栏是**唯一**的退出入口（用户要求），
     * 所以隐藏后再单击一次就能恢复 —— 两次单击的成本，换来看图时不被 UI 遮挡。
     * 只切换应用内工具，状态栏与导航栏始终保留；INVISIBLE 不触发布局尺寸变化。
     */
    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        val visibility = if (overlayVisible) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.top)?.visibility = visibility
        findViewById<View>(R.id.btn_download)?.visibility = visibility
        findViewById<View>(R.id.text_page)?.visibility = visibility
    }

    @SuppressLint("SetTextI18n")
    private fun setPageText(textView: TextView, current: Int, total: Int) {
        textView.text = "第$current/$total 张"
    }

    /**
     * API 29+：把左右两条窄边的"系统返回手势区"排除掉。
     *
     * 系统对每条侧边排除区的有效高度有约 200dp 的预算，不是按矩形面积计算。
     * 仅覆盖图片两侧居中的常用拖动区；预算外的系统返回手势仍由 Android 决定，
     * 不能宣称全屏边缘返回都已屏蔽。顶部退出及底部系统导航区不受此设置影响。
     *
     * 低版本（<29）没有这套系统手势，直接跳过；`Build.VERSION` 判断放在调用处更清楚。
     */
    private fun excludeSystemGestureEdges() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val root = findViewById<View>(R.id.viewPager) ?: return
        root.post {
            if (isDestroyed) return@post
            val width = root.width
            val height = root.height
            if (width <= 0 || height <= 0) return@post
            val edge = minOf(width / 2, dpToPx(GESTURE_EDGE_WIDTH_DP))
            val safeHeight = minOf(height, dpToPx(GESTURE_EDGE_MAX_HEIGHT_DP))
            val top = (height - safeHeight) / 2
            val rects = ArrayList<Rect>(2)
            rects.add(Rect(0, top, edge, top + safeHeight))
            rects.add(Rect(width - edge, top, width, top + safeHeight))
            root.systemGestureExclusionRects = rects
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private companion object {
        /** 最小倍率：1x（图片适配屏幕） */
        const val MIN_SCALE = 1f

        /** 中档：双击缩放的细节倍率 */
        const val MEDIUM_SCALE = 2.5f

        /** 最大倍率：8x（再大就纯粹是糊了） */
        const val MAX_SCALE = 8f

        /** 双击缩放动画时长（毫秒） */
        const val ZOOM_TRANSITION_MS = 200

        /** 下载按钮"再点一次"的判定窗口（毫秒） */
        const val DOUBLE_CLICK_WINDOW_MS = 3000L

        /** 系统返回手势区的排除宽度（dp）：覆盖"从边缘起滑"的常用路径。 */
        const val GESTURE_EDGE_WIDTH_DP = 20

        /** 排除区最大高度（dp）：系统对每条侧边的排除高度有预算限制。 */
        const val GESTURE_EDGE_MAX_HEIGHT_DP = 200
    }
}
